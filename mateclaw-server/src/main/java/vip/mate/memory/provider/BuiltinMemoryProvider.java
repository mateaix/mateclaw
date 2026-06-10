package vip.mate.memory.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.memory.spi.MemoryProvider;
import vip.mate.workspace.document.WorkspaceFileService;

import java.util.List;

/**
 * Built-in memory provider backed by workspace files (PROFILE.md, MEMORY.md, daily notes).
 * <p>
 * Always active, cannot be disabled. Wraps the existing WorkspaceFileService
 * for system prompt assembly and WorkspaceMemoryTool for agent tool access.
 * <p>
 * Post-conversation summarization continues to work via the existing
 * PostConversationMemoryListener event path (not duplicated here).
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinMemoryProvider implements MemoryProvider {

    private final WorkspaceFileService workspaceFileService;

    @Override
    public String id() {
        return "builtin";
    }

    @Override
    public int order() {
        return 0; // always first
    }

    @Override
    public boolean isAvailable() {
        return true; // always on
    }

    /**
     * Returns workspace files content as system prompt block.
     * Delegates to WorkspaceFileService.buildSystemPrompt() which loads
     * all enabled workspace files (PROFILE.md, MEMORY.md, etc.).
     */
    @Override
    public String systemPromptBlock(Long agentId) {
        try {
            String prompt = workspaceFileService.buildSystemPrompt(agentId);
            return prompt != null ? prompt : "";
        } catch (Exception e) {
            log.warn("[BuiltinMemory] Failed to build system prompt for agent={}: {}",
                    agentId, e.getMessage());
            return "";
        }
    }

    /**
     * Shared (TEAM / GLOBAL) memory is baked into the system prompt at build
     * time. Per-owner PERSONAL memory cannot be — the agent instance is cached
     * and reused across users — so it is injected here, per turn, for the
     * current requester only.
     */
    @Override
    public String prefetch(Long agentId, String userQuery, String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            return "";
        }
        try {
            String block = workspaceFileService.buildOwnerMemoryBlock(agentId, ownerKey);
            return block != null ? block : "";
        } catch (Exception e) {
            log.warn("[BuiltinMemory] Failed to build owner memory block for agent={}, owner={}: {}",
                    agentId, ownerKey, e.getMessage());
            return "";
        }
    }

    /**
     * Post-turn sync is handled by the existing PostConversationMemoryListener
     * event path, not duplicated here.
     */
    @Override
    public void syncTurn(Long agentId, String conversationId,
                         String userMessage, String assistantReply) {
        // no-op: summarization handled via ConversationCompletedEvent
    }

    /**
     * WorkspaceMemoryTool is already discovered by ToolRegistry's component scan.
     * No need to re-register it here. Returns empty list.
     */
    @Override
    public List<Object> getToolBeans() {
        return List.of();
    }
}
