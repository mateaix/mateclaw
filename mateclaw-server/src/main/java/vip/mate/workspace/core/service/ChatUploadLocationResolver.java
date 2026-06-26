package vip.mate.workspace.core.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;
import vip.mate.workspace.core.config.ChatUploadProperties;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the on-disk root directory where a conversation's chat attachments
 * are stored. Replaces the previously hardcoded {@code data/chat-uploads}
 * literal with a workspace/agent-aware location.
 *
 * <h3>Resolution precedence</h3>
 * <ol>
 *   <li>When the conversation's agent has a {@code workspaceBasePath} override,
 *       it is resolved under the workspace {@code basePath} (same rule as
 *       {@link AgentGraphBuilder#resolveAgentBasePath}) and the upload root is
 *       {@code {resolved}/chat-uploads}.</li>
 *   <li>Otherwise, when the workspace has a {@code basePath}, the upload root is
 *       {@code {workspace.basePath}/chat-uploads}.</li>
 *   <li>Otherwise, the configurable fallback {@link ChatUploadProperties#getBaseDir()}
 *       (default {@code data/chat-uploads}) is used.</li>
 * </ol>
 *
 * <h3>Backward compatibility</h3>
 * Reads and cleanup use {@link #resolveCandidateUploadRoots(String)} which
 * returns <em>both</em> the workspace-scoped root and the default fallback root,
 * so attachments written before this change (under the default dir) remain
 * resolvable and cleanable. Writes always target a single root returned by
 * {@link #resolveUploadRoot(String)}.
 *
 * <p>The {@code conversationId → ConversationEntity} lookup is cached for 5
 * minutes (the mapping is immutable once a conversation exists), matching the
 * TTL of the existing {@code WorkspaceLookupCache} on the tool-call hot path.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class ChatUploadLocationResolver {

    /** Sub-directory appended under a configured base path. */
    public static final String UPLOAD_SUBDIR = "chat-uploads";

    private final ConversationMapper conversationMapper;
    private final WorkspaceService workspaceService;
    private final ChatUploadProperties properties;

    /**
     * {@code AgentService} is injected lazily because the bean graph is cyclic:
     * {@code agentService → agentGraphBuilder → conversationService → this}.
     * The agent is only consulted at resolve time (never at construction), so a
     * lazy proxy is safe and breaks the cycle cleanly.
     */
    @Lazy
    private final AgentService agentService;

    public ChatUploadLocationResolver(ConversationMapper conversationMapper,
                                      WorkspaceService workspaceService,
                                      ChatUploadProperties properties,
                                      @Lazy AgentService agentService) {
        this.conversationMapper = conversationMapper;
        this.workspaceService = workspaceService;
        this.properties = properties;
        this.agentService = agentService;
    }

    private final Cache<String, ConversationEntity> conversationCache = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    // ==================== write path: single root ====================

    /**
     * Resolve the single upload root for a conversation (write target).
     *
     * @param conversationId business conversation id
     * @return absolute, normalized upload root (never {@code null})
     */
    public Path resolveUploadRoot(String conversationId) {
        ConversationEntity conv = lookupConversation(conversationId);
        Long workspaceId = conv != null ? conv.getWorkspaceId() : null;
        Long agentId = conv != null ? conv.getAgentId() : null;
        return resolveUploadRoot(workspaceId, agentId);
    }

    /**
     * Resolve the upload root when the caller already knows the workspace and
     * agent (e.g. a web request thread carrying {@code X-Workspace-Id} + the
     * picked agent), avoiding a DB lookup.
     */
    public Path resolveUploadRoot(Long workspaceId, Long agentId) {
        Path resolved = resolveWorkspaceScopedRoot(workspaceId, agentId);
        if (resolved != null) {
            return resolved;
        }
        return defaultRoot();
    }

    // ==================== read / cleanup path: candidate roots ====================

    /**
     * Resolve every upload root a conversation's attachments may live under, in
     * lookup order: the workspace-scoped root first (if any), then the default
     * fallback root. Used by file-serving endpoints, the tool-side resolver, and
     * cleanup so legacy uploads stored under the default dir are still found.
     *
     * @return de-duplicated, ordered list (at least the default root is present)
     */
    public List<Path> resolveCandidateUploadRoots(String conversationId) {
        ConversationEntity conv = lookupConversation(conversationId);
        Long workspaceId = conv != null ? conv.getWorkspaceId() : null;
        Long agentId = conv != null ? conv.getAgentId() : null;
        return resolveCandidateUploadRoots(workspaceId, agentId);
    }

    /**
     * Variant of {@link #resolveCandidateUploadRoots(String)} for callers that
     * already hold the workspace / agent ids.
     */
    public List<Path> resolveCandidateUploadRoots(Long workspaceId, Long agentId) {
        Set<Path> roots = new LinkedHashSet<>();
        Path scoped = resolveWorkspaceScopedRoot(workspaceId, agentId);
        if (scoped != null) {
            roots.add(scoped);
        }
        roots.add(defaultRoot());
        return new ArrayList<>(roots);
    }

    // ==================== internals ====================

    /**
     * Resolve the workspace/agent-scoped root, or {@code null} when neither the
     * agent override nor the workspace {@code basePath} is configured (caller
     * then falls back to {@link #defaultRoot()}).
     */
    private Path resolveWorkspaceScopedRoot(Long workspaceId, Long agentId) {
        WorkspaceEntity workspace = null;
        if (workspaceId != null) {
            try {
                workspace = workspaceService.getById(workspaceId);
            } catch (MateClawException e) {
                // Workspace row missing — fall through to the default root.
                log.debug("[ChatUpload] workspace {} not found: {}", workspaceId, e.getMessage());
            }
        }

        String agentOverride = null;
        if (agentId != null) {
            try {
                AgentEntity agent = agentService.getAgent(agentId);
                agentOverride = agent.getWorkspaceBasePath();
            } catch (MateClawException e) {
                log.debug("[ChatUpload] agent {} not found: {}", agentId, e.getMessage());
            }
        }

        // A conversation's agent always belongs to the conversation's workspace
        // (enforced at creation), so the workspace basePath is the scoping root
        // for both the agent override and the no-override case.
        String workspaceBase = workspace != null ? workspace.getBasePath() : null;

        String resolvedBase;
        try {
            resolvedBase = AgentGraphBuilder.resolveAgentBasePath(agentOverride, workspaceBase);
        } catch (IllegalArgumentException e) {
            // Agent override escapes the workspace root — inherit the workspace
            // basePath so chat stays available (mirrors AgentGraphBuilder's own
            // fallback). Surface it so the operator can fix the override.
            log.warn("[ChatUpload] agent {} basePath override rejected, using workspace root: {}",
                    agentId, e.getMessage());
            resolvedBase = workspaceBase;
        }

        if (resolvedBase == null || resolvedBase.isBlank()) {
            return null;
        }
        return Paths.get(resolvedBase).toAbsolutePath().normalize().resolve(UPLOAD_SUBDIR);
    }

    /** The configurable default upload root (legacy location by default). */
    public Path defaultRoot() {
        return Paths.get(properties.getBaseDir()).toAbsolutePath().normalize();
    }

    private ConversationEntity lookupConversation(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return null;
        }
        return conversationCache.get(conversationId, id -> conversationMapper.selectOne(
                Wrappers.<ConversationEntity>lambdaQuery()
                        .eq(ConversationEntity::getConversationId, id)
                        .eq(ConversationEntity::getDeleted, 0)
                        .last("LIMIT 1")));
    }

    /** Drop the cached conversation row (test hook / on conversation re-create). */
    public void invalidate(String conversationId) {
        if (conversationId != null) {
            conversationCache.invalidate(conversationId);
        }
    }

    /**
     * Drop the cached {@code conversationId → ConversationEntity} mapping when a
     * conversation is deleted, mirroring {@code WorkspaceLookupCache}'s listener.
     * Without this, a re-created conversation with the same id (rare, but
     * possible across a backup restore) would inherit the stale workspace/agent
     * mapping for up to five minutes — and {@code cleanAttachmentFiles} would
     * walk the wrong (stale) upload directory. The delete tx has already
     * committed when this fires, so the cache entry is safe to evict.
     */
    @EventListener
    public void onConversationDeleted(ConversationDeletedEvent event) {
        invalidate(event.conversationId());
    }
}
