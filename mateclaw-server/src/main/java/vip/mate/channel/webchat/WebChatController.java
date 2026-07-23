package vip.mate.channel.webchat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import vip.mate.channel.web.Utf8SseEmitter;
import vip.mate.agent.AgentService;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.common.result.R;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import reactor.core.Disposable;
import vip.mate.approval.PendingApproval;
import vip.mate.approval.ResolveOutcome;
import vip.mate.agent.context.ChatOrigin;

/**
 * WebChat 嵌入式对话接口
 * <p>
 * 独立于 ChatController，使用 API Key 认证（不依赖 JWT）。
 * 供外部网站通过 JS SDK 嵌入 MateClaw 对话能力。
 * <p>
 * 认证方式：请求头 X-MC-Key 携带 API Key
 *
 * @author MateClaw Team
 */
@Tag(name = "WebChat 嵌入式对话")
@Slf4j
@RestController
@RequestMapping("/api/v1/channels/webchat")
@RequiredArgsConstructor
public class WebChatController {

    private final ChannelService channelService;
    private final AgentService agentService;
    private final ConversationService conversationService;
    private final ChatStreamTracker streamTracker;
    private final ObjectMapper objectMapper;
    private final ConversationCompletionPublisher completionPublisher;
    private final vip.mate.memory.identity.MemoryOwnerResolver memoryOwnerResolver;
    private final WebChatFileService fileService;
    private final WebChatTokenRevocationService tokenRevocationService;
    private final vip.mate.audit.service.AuditEventService auditService;
    private final vip.mate.llm.routing.AgentBindingResolver agentBindingResolver;
    private final vip.mate.skill.repository.SkillMapper skillMapper;
    private final vip.mate.wiki.repository.WikiPageMapper wikiPageMapper;
    private final vip.mate.wiki.repository.WikiKnowledgeBaseMapper wikiKbMapper;
    /**
     * ISSUE #413 P1-A2/A3/A4: drives the approval lifecycle for WebChat
     * (API-Key) channels. Before this, a tool guarded by ToolGuard would
     * create a pending approval and park the turn, but the visitor had no
     * way to resolve it -- the approval hung until the 30-min GC timeout
     * and the turn was wasted.
     */
    private final vip.mate.approval.ApprovalWorkflowService approvalService;

    /** Visitor-token TTL in seconds (7 days). Mirrors GeneratedFileCache's TTL. */
    static final long VISITOR_TOKEN_TTL_SECONDS = 7 * 24 * 3600L;

    /**
     * Server-only secret used to sign per-visitor tokens. Reuses the JWT secret so no extra
     * config/migration is needed; it is never sent to the client (unlike the public channel API key).
     */
    @Value("${mateclaw.jwt.secret:MateClaw-JWT-Secret-Key-2024-Please-Change-In-Production}")
    private String visitorTokenSecret;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /**
     * WebChat SSE 流式对话
     */
    @Operation(summary = "WebChat SSE 流式对话")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestBody WebChatRequest request) {

        // RFC-058 PR-1: Utf8SseEmitter 显式 charset=UTF-8，防止中文 SSE 乱码
        SseEmitter emitter = new Utf8SseEmitter(10 * 60 * 1000L);

        // 验证 API Key 并获取关联的 Channel 配置
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            sendErrorAndComplete(emitter, "Invalid API Key");
            return emitter;
        }

        // Resolve the target agent: an explicit request agentId overrides the channel's
        // bound agent, but must belong to the channel's workspace (anti privilege-escalation:
        // a shared channel Key must not be able to drive arbitrary agents in other workspaces).
        Long agentId = channel.getAgentId();
        if (request.getAgentId() != null) {
            var requested = agentService.getAgent(request.getAgentId());
            if (requested == null) {
                sendErrorAndComplete(emitter, "Requested agent not found");
                return emitter;
            }
            if (channel.getWorkspaceId() != null && requested.getWorkspaceId() != null
                    && !channel.getWorkspaceId().equals(requested.getWorkspaceId())) {
                sendErrorAndComplete(emitter, "Requested agent does not belong to this channel's workspace");
                return emitter;
            }
            agentId = request.getAgentId();
        }
        if (agentId == null) {
            sendErrorAndComplete(emitter, "No agent configured for this WebChat channel");
            return emitter;
        }
        final Long resolvedAgentId = agentId;

        // Optional sessionId lets one visitor hold multiple isolated threads. It is only ever
        // composed into the server-derived conversationId (kept under the key+visitor namespace),
        // never accepted as a raw conversationId — so a caller can't reach another tenant's history.
        final String visitorId;
        final String effectiveSessionId;
        try {
            visitorId = normalizeVisitorId(request.getVisitorId());
            effectiveSessionId = normalizeSessionId(request.getSessionId());
        } catch (IllegalArgumentException ex) {
            sendErrorAndComplete(emitter, ex.getMessage());
            return emitter;
        }
        String conversationId = deriveConversationId(channel.getId(), visitorId, effectiveSessionId);
        // Server-issued, unforgeable proof that this caller owns this visitorId. Returned in the
        // meta event below; the session-management endpoints require it back (see verifyVisitorToken).
        final String visitorToken = computeVisitorToken(visitorTokenSecret, channel.getId(), visitorId);
        String message = request.getMessage() != null ? request.getMessage() : "";

        if (message.isBlank()) {
            sendErrorAndComplete(emitter, "Message is required");
            return emitter;
        }

        log.info("[WebChat] Stream: agentId={}, conversationId={}, visitor={}", agentId, conversationId, visitorId);

        // 注册 emitter 回调
        emitter.onCompletion(() -> log.debug("[WebChat] SSE completed: {}", conversationId));
        emitter.onTimeout(() -> {
            log.debug("[WebChat] SSE timeout: {}", conversationId);
            streamTracker.complete(conversationId);
        });
        emitter.onError(e -> {
            log.debug("[WebChat] SSE error: {} - {}", conversationId, e.getMessage());
            streamTracker.complete(conversationId);
        });

        sseExecutor.execute(() -> {
            try {
                // 创建或获取会话（workspace 从 agent 获取）
                var webAgent = agentService.getAgent(resolvedAgentId);
                Long webWsId = webAgent != null ? webAgent.getWorkspaceId() : 1L;
                var conv = conversationService.getOrCreateWebchatConversation(
                        conversationId, resolvedAgentId, webchatUsername(visitorId), webWsId, effectiveSessionId);

                // 保存用户消息（含访客本轮引用的附件）。附件元数据一律服务端按 fileId 回查，
                // 不信客户端传入；path 用于 Agent 侧工具读取，对外消息视图会被剥离。
                List<MessageContentPart> userParts = buildUserParts(conversationId, message, request.getAttachmentIds());
                if (!request.isInternalSkipUserPersist()) {
                    // Regenerate reuses the already-persisted seed user row —
                    // inserting again would duplicate it.
                    conversationService.saveMessage(conversationId, "user", message, userParts);
                }

                // 初始化 SSE 流跟踪
                streamTracker.register(conversationId);
                streamTracker.attach(conversationId, emitter);

                // Echo the effective session so the caller can persist it (especially when
                // sessionId was omitted) and address the same thread on subsequent calls. The
                // visitorToken must be stored by the caller and sent back on list/messages/delete.
                streamTracker.broadcast(conversationId, "meta",
                        "{\"sessionId\":" + escapeJson(effectiveSessionId)
                                + ",\"conversationId\":" + escapeJson(conversationId)
                                + ",\"visitorToken\":" + escapeJson(visitorToken) + "}");

                // Accumulate the assistant reply so it can be persisted on stream completion.
                // Pattern mirrors ChatController: always accumulate, only broadcast when the
                // delta is not a persistence-only echo of content already streamed by inner nodes.
                StringBuilder assistantReply = new StringBuilder();
                // Token usage + model attribution: capture _usage_final event emitted at stream end
                final int[] usage = {0, 0, 0, 0, 0}; // [prompt, completion, cacheRead, cacheWrite, reasoning]
                final String[] modelInfo = {null, null}; // [runtimeModel, runtimeProvider]

                // Attribute memory to this external visitor so each end-user
                // behind the shared webchat account is isolated. The same origin
                // resolves the owner key for both the read (recall) and write
                // (publish) paths below.
                vip.mate.agent.context.ChatOrigin webchatOrigin =
                        vip.mate.agent.context.ChatOrigin.web(conversationId, visitorId, webWsId, null)
                                .withSender(null, "api", null);
                String webchatOwnerKey = memoryOwnerResolver.resolve(webchatOrigin);

                reactor.core.Disposable disposable = agentService.chatStructuredStream(resolvedAgentId, message, conversationId, visitorId, null, webchatOrigin)
                        .doOnNext(delta -> {
                            if (delta.isEvent() && "_usage_final".equals(delta.eventType())) {
                                Map<String, Object> data = delta.eventData();
                                usage[0] = ((Number) data.getOrDefault("promptTokens", 0)).intValue();
                                usage[1] = ((Number) data.getOrDefault("completionTokens", 0)).intValue();
                                usage[2] = ((Number) data.getOrDefault("cacheReadTokens", 0)).intValue();
                                usage[3] = ((Number) data.getOrDefault("cacheWriteTokens", 0)).intValue();
                                usage[4] = ((Number) data.getOrDefault("reasoningTokens", 0)).intValue();
                                Object model = data.get("runtimeModelName");
                                Object provider = data.get("runtimeProviderId");
                                if (model != null) modelInfo[0] = model.toString();
                                if (provider != null) modelInfo[1] = provider.toString();
                            }
                            // Forward a curated subset of agent lifecycle events to the
                            // visitor SSE stream. The full event vocabulary (iteration_*,
                            // perf_summary, _routing_decision, feedback_event, ...) is
                            // internal — exposing it to 3rd-party websites would leak
                            // graph internals and complicate the SDK contract. The four
                            // types below are the ones that drive visible UX: typing
                            // indicator (phase), tool execution badges (tool_start/end),
                            // plan-execute checklist (plan). See docs/zh/webchat.md.
                            if (delta.isEvent()) {
                                forwardVisitorEvent(conversationId, delta.eventType(), delta.eventData());
                            }
                            if (delta.content() != null && !delta.content().isEmpty()) {
                                assistantReply.append(delta.content());
                                if (!delta.persistenceOnly()) {
                                    streamTracker.broadcast(conversationId, "content_delta",
                                            "{\"text\":" + escapeJson(delta.content()) + "}");
                                }
                            }
                            if (delta.thinking() != null && !delta.thinking().isEmpty()
                                    && !delta.persistenceOnly()) {
                                streamTracker.broadcast(conversationId, "thinking_delta",
                                        "{\"text\":" + escapeJson(delta.thinking()) + "}");
                            }
                        })
                        .doOnComplete(() -> {
                            String reply = assistantReply.toString();
                            try {
                                if (!reply.isBlank()) {
                                    conversationService.saveMessage(
                                            conversationId, "assistant", reply, List.of(),
                                            "completed", usage[0], usage[1], usage[2], usage[3], usage[4], modelInfo[0], modelInfo[1], null);
                                }
                                completionPublisher.publish(
                                        resolvedAgentId, conversationId, message, reply, "webchat", webchatOwnerKey);
                            } catch (Exception persistErr) {
                                log.warn("[WebChat] Failed to persist assistant reply / publish event: {}",
                                        persistErr.getMessage());
                            }
                            streamTracker.broadcast(conversationId, "done", "{\"status\":\"completed\"}");
                            streamTracker.complete(conversationId);
                        })
                        .doOnError(e -> {
                            log.error("[WebChat] Stream error: {}", e.getMessage());
                            streamTracker.broadcast(conversationId, "error",
                                    "{\"message\":" + escapeJson(e.getMessage()) + "}");
                            streamTracker.complete(conversationId);
                        })
                        .subscribe();
                // Bind the subscription's Disposable so requestStop() (invoked by
                // POST /sessions/stop) can actually dispose the Flux and interrupt
                // the LLM stream. Without this, stopRequested is set but the underlying
                // HTTP call keeps running — token burn + side-effect tools still fire.
                // Mirrors ChatController#chatStream line 495.
                streamTracker.setDisposable(conversationId, disposable);

            } catch (Exception e) {
                log.error("[WebChat] Error: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", e.getMessage())));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    /**
     * 获取 WebChat 配置（前端 SDK 初始化用）
     */
    @Operation(summary = "获取 WebChat 配置")
    @GetMapping("/config")
    public R<Map<String, Object>> getConfig(@RequestHeader("X-MC-Key") String apiKey) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        JsonNode config = parseConfig(channel.getConfigJson());
        return R.ok(Map.of(
                "channelName", channel.getName(),
                "agentId", channel.getAgentId() != null ? channel.getAgentId() : 0,
                "title", textOrDefault(config, "title", channel.getName()),
                "placeholder", textOrDefault(config, "placeholder", "Type a message..."),
                "primaryColor", textOrDefault(config, "primary_color", "#409eff"),
                "welcomeMessage", textOrDefault(config, "welcome_message", "")
        ));
    }

    /**
     * 列出访客在当前 channel 上可见的技能清单(供下游集成方实现 "/" slash
     * picker UI)。返回的是<b>展示级元数据</b>——id、slug、本地化名、描述、
     * 图标,不暴露 SKILL.md 正文、config、安全扫描结果等内部字段。
     * <p>
     * 鉴权链跟 {@link #listSessions} 一致:API Key 解析 channel + visitorToken
     * HMAC 校验。{@code agentId} 可选,缺省回落到 channel 绑定的 agent;
     * 必须属于该 channel 的 workspace(沿用 {@code /stream} 的反越权路径)。
     * <p>
     * 可见范围 = 显式绑定到该 agent 的 enabled 技能。无显式绑定的 agent
     * (意为"用全局默认")返回空清单——visitor 看不到候选,但仍可走自然语言
     * 让 LLM 自行调 {@code load_skill}。
     */
    @Operation(summary = "列出访客可见技能(供 slash picker UI)")
    @GetMapping("/skills")
    public R<List<WebChatSkillView>> listSkills(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam(required = false) Long agentId,
            @RequestParam String visitorId) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        // Resolve the target agent: same anti-escalation rule as /stream — an
        // explicit agentId must belong to the channel's workspace.
        Long resolvedAgentId = channel.getAgentId();
        if (agentId != null) {
            var requested = agentService.getAgent(agentId);
            if (requested == null) {
                return R.fail(404, "Requested agent not found");
            }
            if (channel.getWorkspaceId() != null && requested.getWorkspaceId() != null
                    && !channel.getWorkspaceId().equals(requested.getWorkspaceId())) {
                return R.fail(403, "Requested agent does not belong to this channel's workspace");
            }
            resolvedAgentId = agentId;
        }
        if (resolvedAgentId == null) {
            return R.ok(List.of());
        }
        // Bound-skill IDs is null when the agent has no explicit binding (meaning
        // "use global defaults"); treat that as "no candidates surfaced to the
        // picker" so the agent config stays the source of truth for visitor UI.
        java.util.Set<Long> boundIds = agentBindingResolver.getBoundSkillIds(resolvedAgentId);
        if (boundIds == null || boundIds.isEmpty()) {
            return R.ok(List.of());
        }
        List<vip.mate.skill.model.SkillEntity> skills = skillMapper.selectBatchIds(boundIds);
        return R.ok(skills.stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                // Stable order: by slug asc, fall back to id for ties (e.g. null slug).
                .sorted(java.util.Comparator.comparing(
                        s -> s.getName() != null ? s.getName() : "",
                        java.util.Comparator.nullsFirst(String::compareToIgnoreCase)))
                .map(WebChatSkillView::from)
                .toList());
    }

    /**
     * 列出访客可见的 wiki 页面，供下游自建「`[[slug]]` 引用 picker」UI。
     * <p>
     * 鉴权链跟 {@link #listSkills} 一致：API Key 解析 channel + visitorToken
     * HMAC 校验。{@code agentId} 可选，缺省回落到 channel 绑定的 agent；
     * 必须属于该 channel 的 workspace（沿用 {@code /stream} 的反越权路径）。
     * <p>
     * 可见范围 = agent 绑定的 KB（无显式绑定时回落到 workspace 内全部 KB）
     * 下的所有 page，排除 {@code pageType=synthesis}（LLM 中间产物）。
     * 上限 {@value WEBCHAT_WIKI_PICKER_MAX_PAGES}：超出时强制要求 {@code keyword}。
     * <p>
     * 出参仅暴露展示级元数据（slug / title / summary / pageType / kbId /
     * kbName），不包含正文、embedding、sourceRawIds 等内部字段。
     */
    @Operation(summary = "列出访客可见 wiki 页面（供 [[slug]] picker UI）")
    @GetMapping("/wiki/pages")
    public R<List<WebChatWikiPageView>> listWikiPages(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam(required = false) Long agentId,
            @RequestParam String visitorId,
            @RequestParam(required = false) String keyword) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        // Resolve the target agent — same anti-escalation rule as /stream and
        // /skills: an explicit agentId must belong to the channel's workspace.
        Long resolvedAgentId = channel.getAgentId();
        if (agentId != null) {
            var requested = agentService.getAgent(agentId);
            if (requested == null) {
                return R.fail(404, "Requested agent not found");
            }
            if (channel.getWorkspaceId() != null && requested.getWorkspaceId() != null
                    && !channel.getWorkspaceId().equals(requested.getWorkspaceId())) {
                return R.fail(403, "Requested agent does not belong to this channel's workspace");
            }
            resolvedAgentId = agentId;
        }
        if (resolvedAgentId == null) {
            return R.ok(List.of());
        }

        // Resolve the KB scope: null = workspace-wide (every KB in the agent's
        // workspace); non-empty set = explicit allowlist. Set.of() (rows exist
        // but none enabled) means "agent is explicitly scoped to zero KBs" —
        // surface as empty so the picker shows nothing rather than falling
        // through to workspace-wide.
        java.util.Set<Long> boundKbIds = agentBindingResolver.getBoundKbIds(resolvedAgentId);
        Long workspaceId = channel.getWorkspaceId();
        java.util.Set<Long> effectiveKbIds;
        if (boundKbIds != null) {
            if (boundKbIds.isEmpty()) {
                return R.ok(List.of());
            }
            effectiveKbIds = boundKbIds;
        } else {
            // No explicit binding → fall back to every KB in the channel's
            // workspace. Matches the wiki-tool behavior (an unscoped agent
            // sees workspace-wide KBs).
            effectiveKbIds = wikiKbMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<vip.mate.wiki.model.WikiKnowledgeBaseEntity>()
                                    .eq(vip.mate.wiki.model.WikiKnowledgeBaseEntity::getWorkspaceId,
                                            workspaceId == null ? 1L : workspaceId))
                    .stream()
                    .map(vip.mate.wiki.model.WikiKnowledgeBaseEntity::getId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            if (effectiveKbIds.isEmpty()) {
                return R.ok(List.of());
            }
        }

        // Build the page query: KB scope + exclude hidden pageTypes + optional
        // keyword filter on slug/title. Use a single LIKE with OR so a visitor
        // typing "auth" matches either "auth-design" (slug) or "Auth Design" (title).
        String trimmedKeyword = keyword == null ? null : keyword.trim();
        boolean hasKeyword = trimmedKeyword != null && !trimmedKeyword.isEmpty();

        // Cap check: if no keyword and total candidate count exceeds the cap,
        // refuse — the caller must narrow with a keyword. Counting before
        // selecting avoids materializing a huge list into memory.
        // NOTE: the count wrapper is built WITHOUT ORDER BY — H2 in MySQL mode
        // rejects "ORDER BY slug" on a COUNT(*) query (column must appear in
        // GROUP BY). The select wrapper below adds ORDER BY slug.
        if (!hasKeyword) {
            long total = wikiPageMapper.selectCount(buildWikiPageFilterWrapper(effectiveKbIds, null));
            if (total > WEBCHAT_WIKI_PICKER_MAX_PAGES) {
                return R.fail(422, "Wiki page count (" + total
                        + ") exceeds picker cap (" + WEBCHAT_WIKI_PICKER_MAX_PAGES
                        + "); please provide a 'keyword' query parameter to narrow.");
            }
        }

        List<vip.mate.wiki.model.WikiPageEntity> pages = wikiPageMapper.selectList(
                buildWikiPageFilterWrapper(effectiveKbIds, hasKeyword ? trimmedKeyword : null)
                        .orderByAsc(vip.mate.wiki.model.WikiPageEntity::getSlug));
        if (pages.isEmpty()) {
            return R.ok(List.of());
        }

        // Hydrate KB names so the picker UI can show "<kb>: <page title>" and
        // the LLM can disambiguate when two KBs share a slug.
        java.util.Map<Long, String> kbNames = wikiKbMapper.selectBatchIds(effectiveKbIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        vip.mate.wiki.model.WikiKnowledgeBaseEntity::getId,
                        kb -> kb.getName() != null ? kb.getName() : "",
                        (a, b) -> a));

        return R.ok(pages.stream()
                .map(p -> WebChatWikiPageView.from(p, kbNames.get(p.getKbId())))
                .toList());
    }

    /**
     * Build the WHERE-clause portion of the wiki-page picker query: KB scope +
     * pageType-not-in-hidden + optional keyword LIKE on slug / title.
     * Returned without ORDER BY so the caller can layer sorting (select) or
     * nothing (count) on top — H2 in MySQL mode rejects ORDER BY on COUNT(*).
     */
    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<vip.mate.wiki.model.WikiPageEntity>
            buildWikiPageFilterWrapper(java.util.Set<Long> kbIds, String keyword) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<vip.mate.wiki.model.WikiPageEntity> w =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<vip.mate.wiki.model.WikiPageEntity>()
                        .in(vip.mate.wiki.model.WikiPageEntity::getKbId, kbIds)
                        .notIn(vip.mate.wiki.model.WikiPageEntity::getPageType, WEBCHAT_WIKI_HIDDEN_PAGE_TYPES);
        if (keyword != null && !keyword.isEmpty()) {
            String like = "%" + keyword + "%";
            w.and(qq -> qq.like(vip.mate.wiki.model.WikiPageEntity::getSlug, like)
                    .or().like(vip.mate.wiki.model.WikiPageEntity::getTitle, like));
        }
        return w;
    }

    /** Cap on how many empty (message_count = 0) threads one visitor may hold on a
     *  channel at once. Guards against pathologic clients churning placeholder
     *  sessions without ever sending a message. */
    private static final int MAX_EMPTY_SESSIONS_PER_VISITOR = 5;

    /**
     * Upper bound on the page count the wiki-page picker will return without a
     * keyword filter. Beyond this the caller MUST supply {@code keyword} —
     * returning 500 pages to a visitor picker is both bandwidth-wasteful and
     * unusable as a UI. Mirrors the slash-skill picker's "small list, search
     * when too big" stance.
     */
    private static final int WEBCHAT_WIKI_PICKER_MAX_PAGES = 100;

    /**
     * Page types hidden from the visitor picker. {@code synthesis} pages are
     * LLM-generated intermediate artifacts (compiled on demand by
     * {@code wiki_compile_page}); they aren't curated source material and
     * surfacing them to a downstream visitor is noise. Entity / concept /
     * source pages are human-readable references the visitor can meaningfully
     * point the LLM at.
     */
    private static final java.util.Set<String> WEBCHAT_WIKI_HIDDEN_PAGE_TYPES = java.util.Set.of("synthesis");

    /**
     * 显式创建一条访客会话线程（空会话）。
     * <p>
     * 与 {@code POST /stream} 的隐式 getOrCreate 互补：本端点先建一条 message_count=0
     * 的占位线程，调用方拿到 {@code sessionId/conversationId/visitorToken} 之后，再决定
     * 何时通过 {@code /stream} 发首条消息。鉴权为访客的<b>首次接触</b>：仅校验
     * {@code X-MC-Key}，不要求 {@code X-MC-Visitor-Token}，后端会签发并回传 token，
     * 调用方在后续 GET/PUT/DELETE 上必须回带。
     * <p>
     * 行为：
     * <ul>
     *   <li>幂等：{@code sessionId} 与该 visitor 已有线程冲突 → 直接返回现有线程，
     *       不报错、不覆盖 title。</li>
     *   <li>配额：单 (渠道, visitor) 未活跃空线程 ≤ {@value MAX_EMPTY_SESSIONS_PER_VISITOR}，
     *       超出返回 409。已存在的线程走幂等路径不受配额限制。</li>
     *   <li>title 非空时写入；为空时落默认 "新对话"，首条 user 消息仍会按现有规则截取。</li>
     * </ul>
     */
    @Operation(summary = "显式创建访客会话线程（空会话）")
    @PostMapping("/sessions")
    public R<Map<String, Object>> createSession(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestBody(required = false) WebChatCreateSessionRequest request) {

        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }

        // Resolve agent: explicit request.agentId overrides channel's bound agent,
        // but must belong to channel's workspace (mirrors /stream).
        final Long agentId;
        if (request != null && request.getAgentId() != null) {
            var requested = agentService.getAgent(request.getAgentId());
            if (requested == null) {
                return R.fail(400, "Requested agent not found");
            }
            if (channel.getWorkspaceId() != null && requested.getWorkspaceId() != null
                    && !channel.getWorkspaceId().equals(requested.getWorkspaceId())) {
                return R.fail(400, "Requested agent does not belong to this channel's workspace");
            }
            agentId = request.getAgentId();
        } else {
            agentId = channel.getAgentId();
            if (agentId == null) {
                return R.fail(400, "No agent configured for this WebChat channel");
            }
        }

        final String visitorId;
        final String sessionId;
        try {
            visitorId = normalizeVisitorId(request != null ? request.getVisitorId() : null);
            sessionId = normalizeSessionId(request != null ? request.getSessionId() : null);
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }

        String title = (request != null && request.getTitle() != null) ? request.getTitle().trim() : null;
        if (title != null && (title.isEmpty() || title.length() > 100)) {
            return R.fail(400, "title 不合法（1-100 字）");
        }

        String conversationId = deriveConversationId(channel.getId(), visitorId, sessionId);
        String owner = webchatUsername(visitorId);

        // Idempotency: existing thread is returned as-is. Title and every other
        // field are left untouched — a re-create call must not clobber a previously
        // set title. Existing rows are exempt from the empty-session quota.
        ConversationEntity existing = conversationService.findByConversationId(conversationId);
        if (existing != null && owner.equals(existing.getUsername())) {
            audit(channel, visitorId, "webchat.create-session", conversationId,
                    "{\"sessionId\":\"" + sessionId + "\",\"idempotent\":true}");
            return R.ok(buildCreateSessionResponse(existing, sessionId, channel.getId(), visitorId));
        }

        // Quota: count empty threads this visitor already holds on this channel.
        // loadVisitorSessions already scopes to (channel prefix ∩ visitor owner).
        long emptyCount = loadVisitorSessions(channel.getId(), visitorId).stream()
                .filter(s -> s.getMessageCount() == null || s.getMessageCount() == 0)
                .count();
        if (emptyCount >= MAX_EMPTY_SESSIONS_PER_VISITOR) {
            return R.fail(409, "未活跃会话数已达上限（" + MAX_EMPTY_SESSIONS_PER_VISITOR
                    + "），请先发送消息或删除旧会话");
        }

        ConversationEntity conv = conversationService.getOrCreateWebchatConversation(
                conversationId, agentId, owner, channel.getWorkspaceId(), sessionId, title);
        audit(channel, visitorId, "webchat.create-session", conversationId,
                "{\"sessionId\":\"" + sessionId + "\",\"idempotent\":false}");
        return R.ok(buildCreateSessionResponse(conv, sessionId, channel.getId(), visitorId));
    }

    private Map<String, Object> buildCreateSessionResponse(ConversationEntity conv, String sessionId,
                                                           Long channelId, String visitorId) {
        String visitorToken = computeVisitorToken(visitorTokenSecret, channelId, visitorId);
        // LinkedHashMap (not Map.of) because Map.of rejects null and we want a
        // stable key order for the response payload.
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("sessionId", sessionId != null ? sessionId : "");
        m.put("conversationId", conv.getConversationId());
        m.put("visitorToken", visitorToken);
        m.put("title", conv.getTitle() != null ? conv.getTitle() : "");
        m.put("createTime", conv.getCreateTime());
        return m;
    }

    /**
     * Audit a visitor-side write. Actor is {@code "webchat:<channelId>:<visitorId>"}
     * so audit searches can filter by channel / visitor. detailJson should be
     * a JSON object capturing whatever the operator would need to reconstruct
     * the call (sessionId, before/after state, etc).
     */
    private void audit(ChannelEntity channel, String visitorId, String action,
                       String conversationId, String detailJson) {
        String actor = "webchat:" + channel.getId() + ":" + visitorId;
        auditService.recordAs(actor, channel.getWorkspaceId(),
                action, "CONVERSATION", conversationId, null, detailJson);
    }

    /**
     * 列出某访客的会话线程
     * <p>
     * 仅返回属于本 Key + visitorId 的会话（按 conversationId 前缀过滤），
     * 不暴露裸 conversationId，调用方按 sessionId 寻址。
     */
    @Operation(summary = "列出访客会话线程")
    @GetMapping("/sessions")
    public R<List<WebChatSessionView>> listSessions(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        return R.ok(loadVisitorSessions(channel.getId(), visitorId, includeArchived));
    }

    /**
     * 分页 + 关键词搜索某访客的会话线程。
     * <p>访客的会话集是按 visitor 命名空间限定的（数量有界），故在内存里做关键词过滤与分页。
     * keyword 不区分大小写、匹配标题子串。
     * <p>鉴权链跟 {@link #listSessions} 完全一致,本方法只做"列表 → 关键词过滤 → 分页"的视图
     * 包装,所以直接委托 listSessions 后处理(避免重复 resolveChannel + verifyVisitorToken
     * 的鉴权代码)。
     */
    @Operation(summary = "分页查询访客会话线程")
    @GetMapping("/sessions/page")
    public R<Map<String, Object>> pageSessions(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        @SuppressWarnings("unchecked")
        R<List<WebChatSessionView>> base = (R<List<WebChatSessionView>>) (R<?>)
                listSessions(apiKey, visitorToken, visitorId, includeArchived);
        if (base.getCode() != 200) {
            return R.fail(base.getCode(), base.getMsg());
        }
        if (page < 1) page = 1;
        if (size < 1 || size > 200) size = 20;

        List<WebChatSessionView> all = base.getData();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase(java.util.Locale.ROOT);
            all = all.stream()
                    .filter(s -> s.getTitle() != null && s.getTitle().toLowerCase(java.util.Locale.ROOT).contains(kw))
                    .collect(Collectors.toList());
        }
        long total = all.size();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        List<WebChatSessionView> pageItems = all.subList(from, to);
        return R.ok(Map.of(
                "items", pageItems,
                "total", total,
                "page", page,
                "size", size
        ));
    }

    /**
     * 重命名某会话线程。标题非空、长度 ≤ 100。
     */
    @Operation(summary = "重命名会话线程")
    @PutMapping("/sessions/title")
    public R<Void> renameSession(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(required = false) String sessionId,
            @RequestBody Map<String, String> body) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        String sid;
        try {
            sid = normalizeSessionId(sessionId);
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }
        String conversationId = deriveConversationId(channel.getId(), visitorId, sid);
        if (!ownsConversation(conversationId, visitorId)) {
            return R.fail(404, "Session not found");
        }
        String title = body != null && body.get("title") != null ? body.get("title").trim() : "";
        if (title.isEmpty() || title.length() > 100) {
            return R.fail(400, "标题不合法（1-100 字）");
        }
        conversationService.renameConversation(conversationId, title);
        audit(channel, visitorId, "webchat.rename-session", conversationId,
                "{\"sessionId\":\"" + sid + "\",\"title\":\"" + title + "\"}");
        return R.ok();
    }

    /**
     * 置顶 / 取消置顶某会话线程。Pinned 线程在访客的 /sessions 列表里排在最前
     * (沿用 {@link ConversationService#listWebchatConversations} 的 pinned DESC 排序)。
     */
    @Operation(summary = "置顶 / 取消置顶会话线程")
    @PutMapping("/sessions/pinned")
    public R<Void> pinSession(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(required = false) String sessionId,
            @RequestBody Map<String, Object> body) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        String sid;
        try {
            sid = normalizeSessionId(sessionId);
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }
        String conversationId = deriveConversationId(channel.getId(), visitorId, sid);
        if (!ownsConversation(conversationId, visitorId)) {
            return R.fail(404, "Session not found");
        }
        Object v = body != null ? body.get("pinned") : null;
        if (!(v instanceof Boolean)) {
            return R.fail(400, "body must contain {pinned: true|false}");
        }
        conversationService.setPinned(conversationId, (Boolean) v);
        audit(channel, visitorId, "webchat.pin-session", conversationId,
                "{\"sessionId\":\"" + sid + "\",\"pinned\":" + v + "}");
        return R.ok();
    }

    /**
     * 归档 / 取消归档某会话线程。归档后线程仍在 DB(历史保留、按 sessionId 寻址、文件可下载),
     * 但默认从 /sessions 列表隐藏;调用方需传 {@code includeArchived=true} 才能看到。
     */
    @Operation(summary = "归档 / 取消归档会话线程")
    @PutMapping("/sessions/archive")
    public R<Void> archiveSession(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(required = false) String sessionId,
            @RequestBody Map<String, Object> body) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        String sid;
        try {
            sid = normalizeSessionId(sessionId);
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }
        String conversationId = deriveConversationId(channel.getId(), visitorId, sid);
        if (!ownsConversation(conversationId, visitorId)) {
            return R.fail(404, "Session not found");
        }
        Object v = body != null ? body.get("archived") : null;
        if (!(v instanceof Boolean)) {
            return R.fail(400, "body must contain {archived: true|false}");
        }
        conversationService.setArchived(conversationId, (Boolean) v);
        audit(channel, visitorId, "webchat.archive-session", conversationId,
                "{\"sessionId\":\"" + sid + "\",\"archived\":" + v + "}");
        return R.ok();
    }

    /**
     * Load this visitor's session threads (own namespace only), mapped to the
     * compact view. Sorted as {@code listConversations} returns them (pinned
     * desc, last-active desc). Shared by the list and paginated endpoints.
     * <p>
     * Enumeration is keyed by the visitor's username plus the channel-scoped
     * prefix ({@code webchat:<channelId>:}), so it isolates this channel's
     * threads from other channels sharing the same visitorId (#558). The
     * sessionId is read from the persisted {@code webchatSessionId} column
     * (set on creation) and only falls back to parsing the conversationId
     * for legacy rows created before that column existed.
     */
    private List<WebChatSessionView> loadVisitorSessions(Long channelId, String visitorId) {
        return loadVisitorSessions(channelId, visitorId, false);
    }

    /**
     * Overload that lets the caller opt into archived threads. By default
     * (used by /sessions listing and the empty-session quota check) archived
     * rows are filtered out — they still exist on disk and are addressable
     * by sessionId, but don't pollute the active listing and don't count
     * against the "≤ 5 empty threads" quota (the visitor already declared
     * they're done with them).
     */
    private List<WebChatSessionView> loadVisitorSessions(Long channelId, String visitorId,
                                                         boolean includeArchived) {
        String base = deriveConversationId(channelId, visitorId, null);
        String channelPrefix = "webchat:" + channelId + ":";
        String owner = webchatUsername(visitorId);
        // Query is scoped to this visitor's own rows only (no system rows), so
        // listing a visitor's threads doesn't load every IM/cron conversation.
        // The channel prefix is matched in-memory with a literal startsWith so a
        // '_' / '%' in the channelId can't act as a LIKE wildcard.
        return conversationService.listWebchatConversations(owner).stream()
                .filter(c -> c.getConversationId() != null
                        && c.getConversationId().startsWith(channelPrefix))
                .filter(c -> includeArchived
                        || c.getArchived() == null
                        || c.getArchived() == 0)
                .map(c -> {
                    String sid = recoverSessionId(c, base);
                    return new WebChatSessionView(sid, c.getTitle(), c.getLastActiveTime(),
                            c.getMessageCount(),
                            c.getPinned() != null ? c.getPinned() : 0,
                            c.getArchived() != null ? c.getArchived() : 0,
                            c.getStreamStatus() != null ? c.getStreamStatus() : "idle");
                })
                .collect(Collectors.toList());
    }

    /**
     * Recover a thread's sessionId. Prefers the persisted column; for legacy
     * rows (column null) falls back to parsing the non-hashed conversationId.
     * Returns null for the default (no-session) thread and for legacy hashed rows
     * whose sessionId can no longer be reconstructed.
     */
    private String recoverSessionId(vip.mate.workspace.conversation.model.ConversationEntity c, String base) {
        if (c.getWebchatSessionId() != null) {
            return c.getWebchatSessionId();
        }
        String cid = c.getConversationId();
        if (cid.equals(base)) {
            return null;
        }
        String prefix = base + ":";
        if (cid.startsWith(prefix)) {
            return cid.substring(prefix.length());
        }
        return null;
    }

    /**
     * 获取某会话线程的消息列表（支持分页）。
     * <p>不传 limit 时返回全部消息（向后兼容）；传 limit 返回最新 limit 条 + hasMore；
     * 传 beforeId + limit 时返回该 ID 之前的 limit 条（上拉加载更早消息）。
     */
    @Operation(summary = "获取会话消息（支持分页）")
    @GetMapping("/sessions/messages")
    public R<?> sessionMessages(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer limit) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        String sid;
        try {
            sid = normalizeSessionId(sessionId);
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }
        String conversationId = deriveConversationId(channel.getId(), visitorId, sid);
        if (!ownsConversation(conversationId, visitorId)) {
            return R.fail(404, "Session not found");
        }

        // Backward-compatible: no limit → full external (path-stripped) list.
        if (limit == null || limit <= 0) {
            return R.ok(conversationService.listMessageViewsExternal(conversationId));
        }

        // Paginated: mirror ConversationController#listMessages but with the
        // external view so visitors never see server-side file paths.
        List<MessageEntity> messages;
        boolean hasMore;
        if (beforeId != null) {
            messages = conversationService.listMessagesBefore(conversationId, beforeId, limit + 1);
            hasMore = messages.size() > limit;
            if (hasMore) {
                messages = messages.subList(messages.size() - limit, messages.size());
            }
        } else {
            long total = conversationService.countMessages(conversationId);
            messages = conversationService.listRecentMessages(conversationId, limit);
            hasMore = total > limit;
        }
        return R.ok(Map.of(
                "messages", conversationService.toExternalMessageViews(messages),
                "hasMore", hasMore
        ));
    }

    /**
     * 删除某会话线程
     */
    @Operation(summary = "删除会话线程")
    @DeleteMapping("/sessions")
    public R<Void> deleteSession(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(required = false) String sessionId) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        String sid;
        try {
            sid = normalizeSessionId(sessionId);
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }
        String conversationId = deriveConversationId(channel.getId(), visitorId, sid);
        if (!ownsConversation(conversationId, visitorId)) {
            return R.fail(404, "Session not found");
        }
        conversationService.deleteConversation(conversationId);
        audit(channel, visitorId, "webchat.delete-session", conversationId,
                "{\"sessionId\":\"" + sid + "\"}");
        return R.ok();
    }

    /**
     * 停止访客某线程正在进行中的 SSE 流。
     * <p>
     * 鉴权同其他会话管理端点(API Key + visitorToken + 会话归属)。内部调
     * {@link ChatStreamTracker#requestStop(String)}——靠 chatStream 注册时绑定的
     * Disposable 实际中断 Flux;返回 {@code stopped=false} 表示当前没有活跃流
     * (幂等,不报错)。
     * <p>
     * Approval sweep (ISSUE #413 P1-A4): deny any pending approvals on this
     * conversation so they do not hang for 30 minutes until the GC timeout.
     * The visitor username derived from visitorId is the actor -- it resolves
     * the "no MateClaw username" blocker noted in the old javadoc.
     * Each denied approval broadcasts a { tool_approval_resolved} SSE
     * event so the SDK clears its banner immediately.
     */
    @Operation(summary = "停止访客会话线程的进行中流")
    @PostMapping("/sessions/stop")
    public R<Map<String, Object>> stopSession(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(required = false) String sessionId) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        String sid;
        try {
            sid = normalizeSessionId(sessionId);
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }
        String conversationId = deriveConversationId(channel.getId(), visitorId, sid);
        // ownsConversation is the existence + ownership guard: an unknown sessionId
        // maps to a conversationId that either doesn't exist or belongs to someone
        // else — both return 404 so the caller can't probe the namespace.
        if (!ownsConversation(conversationId, visitorId)) {
            return R.fail(404, "Session not found");
        }
        boolean stopped = streamTracker.requestStop(conversationId);
        log.info("[WebChat] Stop requested: conversationId={}, visitor={}, stopped={}",
                conversationId, visitorId, stopped);
        audit(channel, visitorId, "webchat.stop-session", conversationId,
                "{\"sessionId\":\"" + sid + "\",\"stopped\":" + stopped + "}");
        // ISSUE #413 P1-A4: deny pending approvals so they do not linger for
        // 30 min waiting on a GC timeout. The visitor username is the actor,
        // mirroring how the web ChatController uses the logged-in username.
        int deniedCount = 0;
        try {
            java.util.List<ResolveOutcome> denied =
                    approvalService.denyAllByConversation(conversationId, webchatUsername(visitorId));
            deniedCount = denied.size();
            for (ResolveOutcome o : denied) {
                try {
                    streamTracker.broadcast(conversationId, "tool_approval_resolved",
                            objectMapper.writeValueAsString(java.util.Map.of(
                                    "pendingId", o.pendingId(),
                                    "decision", "denied",
                                    "toolName", o.toolName() != null ? o.toolName() : "")));
                } catch (Exception broadcastErr) {
                    log.debug("[WebChat] approval_resolved broadcast failed for {}: {}",
                            o.pendingId(), broadcastErr.getMessage());
                }
            }
        } catch (Exception sweepErr) {
            log.warn("[WebChat] approval sweep failed for {}: {}", conversationId, sweepErr.getMessage());
        }
        if (deniedCount > 0) {
            log.info("[WebChat] Denied {} pending approval(s) on stop for {}", deniedCount, conversationId);
        }
        return R.ok(Map.of("stopped", stopped));
    }

    /**
     * 拒绝一个待审批的工具调用 (ISSUE #413 P1-A2)。
     * <p>
     * 鉴权同其它会话管理端点 (API Key + visitorToken + 会话归属)。仅允许
     * 发起对话的访客拒绝自己会话的审批 —— 身份校验通过
     * {@code webchatUsername(visitorId)} 与 pending.userId 的等价比较
     * (对位 IM 渠道的 senderId == requester 校验)。
     * <p>
     * resolve 后立即广播 {@code tool_approval_resolved} SSE 事件，让 SDK
     * 实时清理审批 banner。返回同步 JSON (非 SSE)，因为 deny 不需要重放工具。
     *
     * @param pendingId the approval pendingId returned in the
     *                  {@code tool_approval_requested} event
     */
    @Operation(summary = "拒绝访客会话中的待审批工具调用")
    @PostMapping("/sessions/deny")
    public R<Map<String, Object>> denySession(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(required = false) String sessionId,
            @RequestParam String pendingId) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        String sid;
        try {
            sid = normalizeSessionId(sessionId);
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }
        String conversationId = deriveConversationId(channel.getId(), visitorId, sid);
        if (!ownsConversation(conversationId, visitorId)) {
            return R.fail(404, "Session not found");
        }
        // IDOR guard (review #415): the caller owns the conversation, but the
        // pendingId is client-supplied — cross-check that the pending actually
        // belongs to this conversation before resolving, otherwise a visitor
        // could resolve / replay another visitor's guarded tool call.
        // getPending(pendingId) gives the exact record (vs findPendingByConversation
        // which returns the earliest, wrong when several pendings coexist).
        var ownedOpt = approvalService.getPending(pendingId);
        if (ownedOpt.isEmpty()
                || !conversationId.equals(ownedOpt.get().getConversationId())) {
            return R.fail(404, "Pending approval not found for this session");
        }
        // Resolve and broadcast outside the persistence transaction: SSE is
        // not rollback-capable, so the broadcast must follow a committed DB write.
        String actor = webchatUsername(visitorId);
        ResolveOutcome outcome = approvalService.resolve(pendingId, actor, "denied");
        broadcastApprovalResolved(conversationId, outcome);
        audit(channel, visitorId, "webchat.deny-approval", conversationId,
                "{\"pendingId\":\"" + escapeJson(pendingId) + "\",\"resolved\":"
                        + outcome.dbSynced() + "}");
        return R.ok(Map.of("resolved", outcome.dbSynced(), "decision", outcome.decision()));
    }

    /**
     * 批准一个待审批的工具调用并重放 (ISSUE #413 P1-A2 + P1-A3)。
     * <p>
     * 与 deny 不同，approve 返回 SSE 流：原子消费审批记录后，用捕获的
     * toolCallPayload 重放工具调用，把工具结果回灌 agent 继续本轮对话。
     * 重放模式对位 web 渠道的 ChatController —— 复用
     * {@code chatWithReplayStream} + {@code restoreChatOrigin} 恢复原始
     * ChatOrigin (webchat origin 在 createPending 时已通过 ChatOriginHolder
     * 持久化到 approval 行)。
     * <p>
     * 重放期间可能再次触发审批 (一个工具批准后 agent 可能调用下一个受保护
     * 工具) —— 该场景由 {@code tool_approval_requested} 直推事件自然覆盖，
     * 无需特殊处理。事件投递走与 {@link #chatStream} 相同的 broadcast 路径。
     *
     * @param pendingId the approval pendingId returned in the
     *                  {@code tool_approval_requested} event
     */
    @Operation(summary = "批准访客会话中的待审批工具调用并重放")
    @PostMapping(value = "/sessions/approve", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter approveSession(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(required = false) String sessionId,
            @RequestParam String pendingId) {
        SseEmitter emitter = new Utf8SseEmitter(10 * 60 * 1000L);
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            sendErrorAndComplete(emitter, "Invalid API Key");
            return emitter;
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            sendErrorAndComplete(emitter, "Invalid or missing visitor token");
            return emitter;
        }
        String sid;
        try {
            sid = normalizeSessionId(sessionId);
        } catch (IllegalArgumentException ex) {
            sendErrorAndComplete(emitter, ex.getMessage());
            return emitter;
        }
        String conversationId = deriveConversationId(channel.getId(), visitorId, sid);
        if (!ownsConversation(conversationId, visitorId)) {
            sendErrorAndComplete(emitter, "Session not found");
            return emitter;
        }
        // IDOR guard (review #415): cross-check the client-supplied pendingId
        // actually belongs to this conversation before resolving, otherwise a
        // visitor could approve + replay another visitor's guarded tool call.
        var ownedApprovalOpt = approvalService.getPending(pendingId);
        if (ownedApprovalOpt.isEmpty()
                || !conversationId.equals(ownedApprovalOpt.get().getConversationId())) {
            sendErrorAndComplete(emitter, "Pending approval not found for this session");
            return emitter;
        }

        emitter.onCompletion(() -> log.debug("[WebChat] approve SSE completed: {}", conversationId));
        emitter.onTimeout(() -> {
            log.debug("[WebChat] approve SSE timeout: {}", conversationId);
            streamTracker.complete(conversationId);
        });
        emitter.onError(e -> {
            log.debug("[WebChat] approve SSE error: {} - {}", conversationId, e.getMessage());
            streamTracker.complete(conversationId);
        });

        String actor = webchatUsername(visitorId);
        sseExecutor.execute(() -> {
            // Register + attach the emitter FIRST so every downstream branch
            // (already-resolved, no-agent, error, replay) can broadcast a
            // terminal event the SDK actually receives. Doing this after
            // resolveAndConsume left the already-resolved / error paths
            // broadcasting into a subscriber-less tracker, so the SSE hung
            // to the 10-min timeout (review #415).
            streamTracker.register(conversationId);
            streamTracker.attach(conversationId, emitter);
            try {
                // Atomically consume the approval (DB + metadata + memory, single tx).
                ResolveOutcome consumed = approvalService.resolveAndConsume(pendingId, actor);
                if (consumed.consumedSnapshot() == null) {
                    // already resolved / not found — emit a terminal done so the
                    // SDK's stream listener closes cleanly instead of hanging.
                    broadcastApprovalResolved(conversationId, consumed);
                    streamTracker.broadcast(conversationId, "done",
                            "{\"status\":\"already_resolved\"}");
                    return;
                }

                // Notify the SDK the approval flipped (clears the banner) before
                // replay output starts streaming.
                broadcastApprovalResolved(conversationId, consumed);

                PendingApproval snapshot = consumed.consumedSnapshot();
                Long replayAgentId = snapshot.getAgentId() != null
                        ? parseLongOrNull(snapshot.getAgentId()) : null;
                if (replayAgentId == null) {
                    log.warn("[WebChat] approve: no agentId on consumed approval {}, cannot replay",
                            pendingId);
                    streamTracker.broadcast(conversationId, "done",
                            "{\"status\":\"error\",\"message\":\"No agent bound to approval\"}");
                    return;
                }

                // Restore the original ChatOrigin captured at createPending time.
                // Falls back to a fresh webchat origin when none was persisted
                // (defensive — mirrors ChatController:304-306).
                ChatOrigin replayOrigin =
                        approvalService.restoreChatOrigin(snapshot.getChatOrigin());
                if (replayOrigin == ChatOrigin.EMPTY) {
                    var agent = agentService.getAgent(replayAgentId);
                    Long wsId = agent != null ? agent.getWorkspaceId() : 1L;
                    replayOrigin = ChatOrigin.web(
                            conversationId, actor, wsId, null).withSender(null, "api", null);
                }

                // Neutral replay prompt (aligned with IM + web channels — naming a
                // tool here can mislead the LLM on fallthrough).
                String replayPrompt = "继续执行已批准的工具调用。";
                StringBuilder assistantReply = new StringBuilder();
                final int[] usage = {0, 0};
                final String[] modelInfo = {null, null};

                streamTracker.broadcast(conversationId, "message_start",
                        "{\"role\":\"assistant\"}");

                Disposable disposable = agentService.chatWithReplayStream(
                        replayAgentId, replayPrompt, conversationId,
                        snapshot.getToolCallPayload(), actor, replayOrigin)
                        .doOnNext(delta -> {
                            if (delta.isEvent() && "_usage_final".equals(delta.eventType())) {
                                Map<String, Object> data = delta.eventData();
                                usage[0] = ((Number) data.getOrDefault("promptTokens", 0)).intValue();
                                usage[1] = ((Number) data.getOrDefault("completionTokens", 0)).intValue();
                                usage[2] = ((Number) data.getOrDefault("cacheReadTokens", 0)).intValue();
                                usage[3] = ((Number) data.getOrDefault("cacheWriteTokens", 0)).intValue();
                                usage[4] = ((Number) data.getOrDefault("reasoningTokens", 0)).intValue();
                                Object model = data.get("runtimeModelName");
                                Object provider = data.get("runtimeProviderId");
                                if (model != null) modelInfo[0] = model.toString();
                                if (provider != null) modelInfo[1] = provider.toString();
                            }
                            if (delta.isEvent()) {
                                forwardVisitorEvent(conversationId, delta.eventType(), delta.eventData());
                            }
                            if (delta.content() != null && !delta.content().isEmpty()) {
                                assistantReply.append(delta.content());
                                if (!delta.persistenceOnly()) {
                                    streamTracker.broadcast(conversationId, "content_delta",
                                            "{\"text\":" + escapeJson(delta.content()) + "}");
                                }
                            }
                            if (delta.thinking() != null && !delta.thinking().isEmpty()
                                    && !delta.persistenceOnly()) {
                                streamTracker.broadcast(conversationId, "thinking_delta",
                                        "{\"text\":" + escapeJson(delta.thinking()) + "}");
                            }
                        })
                        .doOnComplete(() -> {
                            String reply = assistantReply.toString();
                            try {
                                if (!reply.isBlank()) {
                                    conversationService.saveMessage(
                                            conversationId, "assistant", reply, List.of(),
                                            "completed", usage[0], usage[1], usage[2], usage[3], usage[4], modelInfo[0], modelInfo[1], null);
                                }
                            } catch (Exception persistErr) {
                                log.warn("[WebChat] approve replay persist failed: {}", persistErr.getMessage());
                            }
                            streamTracker.broadcast(conversationId, "done",
                                    "{\"status\":\"completed\"}");
                            streamTracker.complete(conversationId);
                        })
                        .doOnError(e -> {
                            log.error("[WebChat] approve replay stream error: {}", e.getMessage());
                            streamTracker.broadcast(conversationId, "error",
                                    "{\"message\":" + escapeJson(e.getMessage()) + "}");
                            streamTracker.complete(conversationId);
                        })
                        .subscribe();
                streamTracker.setDisposable(conversationId, disposable);
            } catch (Exception e) {
                log.error("[WebChat] approve failed for {}: {}", conversationId, e.getMessage());
                try {
                    streamTracker.broadcast(conversationId, "error",
                            "{\"message\":" + escapeJson(e.getMessage()) + "}");
                } catch (Exception ignored) {}
                streamTracker.complete(conversationId);
            }
        });
        audit(channel, visitorId, "webchat.approve-approval", conversationId,
                "{\"pendingId\":\"" + escapeJson(pendingId) + "\",\"replay\":true}");
        return emitter;
    }

    /** Parse a Long leniently; null/blank/non-numeric return null. */
    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Broadcast a {@code tool_approval_resolved} event so the SDK clears its
     * approval banner in real time. Shared by approve / deny / stop-sweep.
     * (ISSUE #413 P1)
     */
    private void broadcastApprovalResolved(String conversationId, ResolveOutcome outcome) {
        try {
            streamTracker.broadcast(conversationId, "tool_approval_resolved",
                    objectMapper.writeValueAsString(Map.of(
                            "pendingId", outcome.pendingId(),
                            "decision", outcome.decision() != null ? outcome.decision() : "",
                            "toolName", outcome.toolName() != null ? outcome.toolName() : "")));
        } catch (Exception e) {
            log.debug("[WebChat] approval_resolved broadcast failed for {}: {}",
                    outcome.pendingId(), e.getMessage());
        }
    }

    /**
     * 重新生成最后一条助手回复。
     * <p>
     * 语义:找到会话最后一条 {@code role=user} 消息 → stop 当前流(如有)→ 删除最后一条
     * {@code role=assistant} 消息 → 用 last user message 重新启动 agent turn。
     * 实际启动复用 {@link #chatStream},它会重新 saveMessage user(新消息 id,内容相同)。
     * 这样不重复 100 行 SSE 代码,代价是用户消息多一条(语义上等同"重发")。
     * <p>
     * 没有任何 user 消息时返回 400(无内容可重新生成)。
     */
    @Operation(summary = "重新生成最后一条助手回复")
    @PostMapping(value = "/sessions/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerateSession(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(required = false) String sessionId) {
        SseEmitter emitter = new Utf8SseEmitter(10 * 60 * 1000L);
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            sendErrorAndComplete(emitter, "Invalid API Key");
            return emitter;
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            sendErrorAndComplete(emitter, "Invalid or missing visitor token");
            return emitter;
        }
        String sid;
        try {
            sid = normalizeSessionId(sessionId);
        } catch (IllegalArgumentException ex) {
            sendErrorAndComplete(emitter, ex.getMessage());
            return emitter;
        }
        String conversationId = deriveConversationId(channel.getId(), visitorId, sid);
        if (!ownsConversation(conversationId, visitorId)) {
            sendErrorAndComplete(emitter, "Session not found");
            return emitter;
        }

        // Stop any in-flight stream first so its doOnComplete doesn't race the
        // delete/save below. Single-node webchat means requestStop hits the
        // right disposable; multi-node is a separate epic.
        streamTracker.requestStop(conversationId);

        ConversationService.RegenerateSeed seed = conversationService.prepareRegenerate(conversationId);
        if (seed == null) {
            sendErrorAndComplete(emitter, "No user message to regenerate from");
            return emitter;
        }

        log.info("[WebChat] Regenerate: conversationId={}, visitor={}, seedMessageId={}",
                conversationId, visitorId, seed.seedMessageId());
        audit(channel, visitorId, "webchat.regenerate-session", conversationId,
                "{\"sessionId\":\"" + sid + "\",\"seedMessageId\":" + seed.seedMessageId() + "}");

        // Reuse chatStream: it'll resolve the agent again (cheap), re-derive
        // conversationId and start the agent turn. The seed user row is reused
        // as-is — internalSkipUserPersist stops chatStream from inserting a
        // duplicate user row. visitorId echoes through to keep the
        // visitor-scoped memory owner consistent.
        WebChatRequest req = new WebChatRequest();
        req.setMessage(seed.content());
        req.setVisitorId(visitorId);
        req.setSessionId(sid);
        req.setInternalSkipUserPersist(true);
        return chatStream(apiKey, req);
    }

    /**
     * 上传文件（入站）。访客先上传拿到 fileId，再在 /stream 的 attachmentIds 中引用。
     * <p>鉴权同会话接口：API Key + visitor token；conversationId 服务端派生。
     */
    @Operation(summary = "WebChat 上传文件")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, Object>> uploadFile(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(required = false) String sessionId,
            @RequestPart("file") MultipartFile file) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        // Upload requires an established visitor identity (the token is bound to it);
        // unlike /stream we never mint a fresh visitorId here.
        String vid;
        String sid;
        try {
            if (visitorId == null || visitorId.trim().isEmpty()) {
                return R.fail(400, "visitorId is required");
            }
            vid = normalizeVisitorId(visitorId);
            sid = normalizeSessionId(sessionId);
        } catch (IllegalArgumentException ex) {
            return R.fail(400, ex.getMessage());
        }
        String conversationId = deriveConversationId(channel.getId(), vid, sid);
        try {
            WebChatFileService.StagedFile stored = fileService.store(conversationId, file);
            audit(channel, vid, "webchat.upload-file", conversationId,
                    "{\"sessionId\":\"" + sid + "\",\"fileId\":\"" + stored.storedName()
                            + "\",\"size\":" + stored.size() + "}");
            return R.ok(Map.of(
                    "fileId", stored.storedName(),
                    "fileName", stored.originalName(),
                    "contentType", stored.contentType() != null ? stored.contentType() : "application/octet-stream",
                    "size", stored.size()
            ));
        } catch (WebChatFileService.UploadRejectedException ex) {
            return R.fail(400, ex.getMessage());
        } catch (IOException ex) {
            log.error("[WebChat] Upload failed conv={}: {}", conversationId, ex.getMessage());
            return R.fail(500, "Upload failed");
        }
    }

    /**
     * 下载文件（出站）。serves both visitor-uploaded files and agent-produced files
     * written under the conversation dir. 鉴权同上，路径在服务端派生目录内防穿越。
     */
    @Operation(summary = "WebChat 下载文件")
    @GetMapping("/files")
    public ResponseEntity<Resource> downloadFile(
            @RequestHeader("X-MC-Key") String apiKey,
            @RequestHeader(value = "X-MC-Visitor-Token", required = false) String visitorToken,
            @RequestParam String visitorId,
            @RequestParam(required = false) String sessionId,
            @RequestParam String storedName) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return ResponseEntity.status(401).build();
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return ResponseEntity.status(401).build();
        }
        String sid;
        try {
            sid = normalizeSessionId(sessionId);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
        String conversationId = deriveConversationId(channel.getId(), visitorId, sid);
        if (!ownsConversation(conversationId, visitorId)) {
            return ResponseEntity.status(404).build();
        }
        Path file = fileService.resolve(conversationId, storedName).orElse(null);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }

        String contentType;
        try {
            contentType = Files.probeContentType(file);
        } catch (IOException e) {
            contentType = null;
        }
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (contentType != null) {
            try {
                mediaType = MediaType.parseMediaType(contentType);
            } catch (Exception ignored) {
                // fall back to octet-stream
            }
        }
        // Only inline images; everything else downloads as an attachment. nosniff
        // stops the browser from re-interpreting the bytes as active content.
        boolean inlineImage = contentType != null && contentType.startsWith("image/");
        String encodedName = URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (inlineImage ? "inline" : "attachment") + "; filename*=UTF-8''" + encodedName)
                .body(new FileSystemResource(file));
    }

    /**
     * Build the user message's content parts: a text part for the message plus a
     * file/media part for each referenced attachment. Attachment metadata is
     * resolved server-side from the staging registry (the client only sends opaque
     * ids); an id that is unknown, expired, or belongs to another conversation is
     * silently dropped.
     */
    private List<MessageContentPart> buildUserParts(String conversationId, String message,
                                                    List<String> attachmentIds) {
        List<MessageContentPart> parts = new ArrayList<>();
        if (message != null && !message.isBlank()) {
            MessageContentPart text = new MessageContentPart();
            text.setType("text");
            text.setText(message);
            parts.add(text);
        }
        if (attachmentIds != null) {
            for (String fileId : attachmentIds) {
                fileService.consume(conversationId, fileId).ifPresent(sf -> {
                    MessageContentPart p = new MessageContentPart();
                    p.setType(WebChatFileService.partTypeFor(sf.contentType()));
                    p.setFileName(sf.originalName());
                    p.setContentType(sf.contentType());
                    p.setStoredName(sf.storedName());
                    p.setFileSize(sf.size());
                    // Relative download ref (caller adds auth headers + visitorId/sessionId).
                    p.setFileUrl("/api/v1/channels/webchat/files?storedName="
                            + URLEncoder.encode(sf.storedName(), StandardCharsets.UTF_8));
                    // Server path lets the agent's file tools read the upload; stripped from
                    // the external message view (listMessageViewsExternal).
                    fileService.resolve(conversationId, sf.storedName())
                            .ifPresent(path -> p.setPath(path.toString()));
                    parts.add(p);
                });
            }
        }
        return parts;
    }

    // ==================== 内部方法 ====================

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * 归一化调用方传入的 sessionId：空白 → null；非空必须满足白名单字符集，否则抛出。
     */
    private String normalizeSessionId(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (!SESSION_ID_PATTERN.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "Invalid sessionId (allowed: letters, digits, '-', '_', length 1-64)");
        }
        return s;
    }

    private static final Pattern VISITOR_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:\\-]{1,128}");

    /**
     * 归一化调用方传入的 visitorId：空白 → 新 UUID；非空必须满足白名单字符集，否则抛出。
     * 限制字符集既防注入/控制字符，也为派生的 conversationId / username 提供可预期的边界。
     */
    private String normalizeVisitorId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return UUID.randomUUID().toString();
        }
        String s = raw.trim();
        if (!VISITOR_ID_PATTERN.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "Invalid visitorId (allowed: letters, digits, '-', '_', '.', ':', length 1-128)");
        }
        return s;
    }

    /**
     * 由服务端拼装 conversationId，始终钳在 channel + visitor 命名空间内。
     * 绝不接受调用方传入的裸 conversationId。
     * <p>渠道身份用 {@code channelId}（稳定唯一的 DB 主键）体现，与 IM 渠道的
     * {@code {channelType}:{channelId}:{identifier}} 模式同构（#558）。不能用 apiKey 前缀：
     * 所有生成的 key 共享 11 位前缀 {@code mc_webchat_}，截取短于它的子串对每个渠道都是
     * 同一个常量 {@code mc_webch}，渠道维度隔离完全失效。
     * <p>{@code conversation_id} 列已拓宽为 VARCHAR(128)（V171），{@code webchat:<channelId>:<visitor>[:<session>]}
     * 在实际输入下不会超限，故不再需要哈希折叠。
     */
    static String deriveConversationId(Long channelId, String visitorId, String sessionId) {
        return "webchat:" + channelId + ":" + visitorId + (sessionId != null ? ":" + sessionId : "");
    }

    /**
     * 由 visitorId 派生 username（mate_conversation.username，VARCHAR(64)）。
     * 同样在超长时折叠为哈希，避免 username 溢出列宽。
     */
    static String webchatUsername(String visitorId) {
        String u = "webchat:" + visitorId;
        return u.length() <= 64 ? u : "webchat:#" + sha256Hex(visitorId).substring(0, 40);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 存在性守卫：会话存在且属于本 visitor 命名空间时返回 true，否则 404。
     * <p>注意：这<b>不是</b>鉴权边界——conversationId 由调用方自报的 visitorId 派生，
     * 等式两边同源，单凭它无法防越权。真正的鉴权由 {@link #verifyVisitorToken} 完成。
     */
    private boolean ownsConversation(String conversationId, String visitorId) {
        ConversationEntity conv = conversationService.findByConversationId(conversationId);
        return conv != null && webchatUsername(visitorId).equals(conv.getUsername());
    }

    /**
     * 用服务端密钥对 (channelId, visitorId) 做 HMAC-SHA256，签发不可伪造的 visitor token。
     * 载荷含 channelId，使 token 不能跨渠道复用。
     * <p>Token 默认 7 天后过期（{@link #VISITOR_TOKEN_TTL_SECONDS}）；过期时间作为后缀
     * 明文附加在 HMAC 之后（{@code <base64sig>.<expEpochSec>}），既参与签名也方便解析。
     * 过期后访客可通过 {@code /stream} 重新签发（{@code /stream} 不校验 token，只签发）。
     */
    static String computeVisitorToken(String secret, Long channelId, String visitorId) {
        return computeVisitorToken(secret, channelId, visitorId,
                java.time.Instant.now().getEpochSecond() + VISITOR_TOKEN_TTL_SECONDS);
    }

    /** Test/override hook: explicit expiration epoch second. */
    static String computeVisitorToken(String secret, Long channelId, String visitorId, long expiresAtEpochSecond) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String payload = channelId + ":" + visitorId + ":" + expiresAtEpochSecond;
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig) + "." + expiresAtEpochSecond;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /**
     * 校验调用方回传的 token 的<b>签名 + 过期</b>。不查撤销表(撤销是实例层职责,
     * 见 {@link #verifyVisitorToken})。Static 是为了让单测可以直接验证 HMAC 语义,
     * 不需要起 Spring context。
     */
    static boolean verifyVisitorTokenSignature(String secret, Long channelId, String visitorId, String presented) {
        if (presented == null || presented.isEmpty() || visitorId == null || channelId == null) {
            return false;
        }
        int dot = presented.lastIndexOf('.');
        if (dot <= 0 || dot == presented.length() - 1) {
            return false;
        }
        long exp;
        try {
            exp = Long.parseLong(presented.substring(dot + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        if (java.time.Instant.now().getEpochSecond() >= exp) {
            return false;
        }
        // Constant-time comparison of the full token (sig + ".exp"). HMAC covers
        // both channelId:visitorId and exp, so any tampering with exp invalidates sig.
        byte[] expected = computeVisitorToken(secret, channelId, visitorId, exp).getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 完整校验:签名 + 过期 + 撤销。任一不通过返回 false。实例方法,接入
     * {@link WebChatTokenRevocationService}。{@code /stream} 第一次接触不调用本方法
     * (只签发 token,不校验),所以被撤销的 visitor 仍能发起新会话——撤销只让旧的
     * 管理 token 失效,符合 issue #351 的设计。
     */
    boolean verifyVisitorToken(String secret, Long channelId, String visitorId, String presented) {
        if (!verifyVisitorTokenSignature(secret, channelId, visitorId, presented)) {
            return false;
        }
        if (tokenRevocationService != null && tokenRevocationService.isRevoked(channelId, visitorId)) {
            return false;
        }
        return true;
    }

    /**
     * 通过 API Key 查找 WebChat 渠道
     */
    private ChannelEntity resolveChannel(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        List<ChannelEntity> channels = channelService.listChannelsByType("webchat");
        for (ChannelEntity channel : channels) {
            if (!Boolean.TRUE.equals(channel.getEnabled())) continue;
            JsonNode config = parseConfig(channel.getConfigJson());
            String configuredApiKey = textOrDefault(config, "api_key", null);
            if (configuredApiKey != null && apiKey.equals(configuredApiKey)) {
                return channel;
            }
        }
        return null;
    }

    private JsonNode parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(configJson);
        } catch (Exception e) {
            log.warn("[WebChat] Failed to parse configJson: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private String textOrDefault(JsonNode node, String fieldName, String defaultValue) {
        if (node != null) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return defaultValue;
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * Forward a curated subset of agent lifecycle events to the visitor SSE
     * stream as visitor-friendly {@code phase} / {@code tool_start} /
     * {@code tool_end} / {@code plan} events. Internal event types
     * ({@code _usage_final}, {@code _routing_decision}, {@code iteration_*},
     * {@code perf_summary}, {@code feedback_event}, {@code finish_reason},
     * {@code plan_step_*}) are dropped — they leak graph internals and have
     * no visitor-facing value.
     *
     * <p>Tool arguments are deliberately <b>not</b> forwarded. The agent may
     * invoke tools with PII / sensitive arguments (file paths, user queries,
     * credentials); relaying those to a 3rd-party website frontend is a data
     * leak. The frontend gets only the tool name and renders a localized
     * label via its own lookup table.
     *
     * <p>Payloads are serialized via the injected {@link ObjectMapper} so
     * nested maps/lists are encoded correctly (the hand-rolled {@link #escapeJson}
     * helper is string-only).
     *
     * <p>Backward compat: visitors / SDKs that don't know these event types
     * silently ignore them per the SSE spec.
     */
    private void forwardVisitorEvent(String conversationId, String eventType, Map<String, Object> data) {
        if (eventType == null || data == null) return;
        Map<String, Object> payload;
        String sseName;
        switch (eventType) {
            case "phase":
                // Graph phase transition (planning / thinking / generating /
                // summarizing / ...). Lets the SDK show a typing indicator
                // before the first content_delta lands.
                sseName = "phase";
                payload = Map.of(
                        "phase", String.valueOf(data.getOrDefault("phase", "")),
                        "timestamp", System.currentTimeMillis());
                break;
            case "tool_call_started":
                // Tool invocation started. Args intentionally omitted — see javadoc.
                sseName = "tool_start";
                payload = Map.of(
                        "tool", String.valueOf(data.getOrDefault("toolName",
                                data.getOrDefault("tool", ""))));
                break;
            case "tool_call_completed":
                // Tool invocation finished. Result content intentionally omitted.
                sseName = "tool_end";
                payload = new java.util.LinkedHashMap<>();
                payload.put("tool", String.valueOf(data.getOrDefault("toolName",
                        data.getOrDefault("tool", ""))));
                Object success = data.get("success");
                payload.put("success", success != null ? success : Boolean.TRUE);
                break;
            case "plan_created":
                // Plan-Execute agents expose their step list. The SDK can render
                // a checklist; subsequent plan_step_* events are dropped (too
                // granular for a visitor view).
                sseName = "plan";
                payload = Map.of("steps", data.getOrDefault("steps", List.of()));
                break;
            default:
                // Curated allow-list: anything else is internal — silently drop.
                return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            streamTracker.broadcast(conversationId, sseName, json);
        } catch (Exception e) {
            log.debug("[WebChat] Failed to serialize visitor event {} for {}: {}",
                    eventType, conversationId, e.getMessage());
        }
    }

    private String escapeJson(String value) {
        if (value == null) return "null";
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    // ==================== 请求体 ====================

    @lombok.Data
    public static class WebChatRequest {
        private String message;
        private String visitorId;
        /** Optional: route this call to a specific agent instead of the channel's bound agent.
         *  Must belong to the channel's workspace.
         *  <p>Only applied when the (visitorId + sessionId) conversation is first created. Once that
         *  conversation exists, its agent is fixed: a different agentId on later requests is silently
         *  ignored. To talk to another agent, use a new sessionId (or a new visitorId). */
        private Long agentId;
        /** Optional: open a distinct conversation thread for the same visitor.
         *  Composed into the server-derived conversationId; never used as a raw conversationId. */
        private String sessionId;
        /** Optional: ids returned by POST /upload, referencing files this visitor uploaded
         *  for this conversation. Metadata is resolved server-side; unknown / foreign / expired
         *  ids are dropped. */
        private List<String> attachmentIds;
        /**
         * Internal-only regenerate flag: the seed user row is already
         * persisted, so {@code chatStream} must not insert a duplicate.
         * Excluded from JSON binding — never client-settable.
         */
        @JsonIgnore
        private boolean internalSkipUserPersist;
    }

    /** Compact view of one of a visitor's conversation threads. */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class WebChatSessionView {
        /** null for the visitor's default (no-session) thread. */
        private String sessionId;
        private String title;
        private LocalDateTime lastActiveTime;
        private Integer messageCount;
        /** 1 if the visitor pinned this thread, 0 otherwise. */
        private Integer pinned;
        /** 1 if the visitor archived this thread, 0 otherwise. */
        private Integer archived;
        /** {@code running} if a stream is in progress on this thread, else {@code idle}. */
        private String streamStatus;
    }

    /**
     * Display-level view of a skill surfaced to webchat visitors for the slash
     * picker UI. Carries only the fields a UI needs to render a row — id (for
     * logging / debugging), localised name, description, icon. Deliberately
     * omits SKILL.md content, config JSON, scan results and other internal
     * columns: those never leave the admin console.
     */
    @lombok.Data
    public static class WebChatSkillView {
        private Long id;
        /** Immutable slug the LLM takes as {@code load_skill(name=…)}; this is what the slash picker must splice into the directive text. */
        private String name;
        private String nameZh;
        private String nameEn;
        private String description;
        private String icon;

        static WebChatSkillView from(vip.mate.skill.model.SkillEntity s) {
            WebChatSkillView v = new WebChatSkillView();
            v.id = s.getId();
            v.name = s.getName();
            v.nameZh = s.getNameZh();
            v.nameEn = s.getNameEn();
            v.description = s.getDescription();
            v.icon = s.getIcon();
            return v;
        }
    }

    /**
     * Display-level projection of a wiki page for the visitor-facing
     * {@code [[slug]]} picker. Carries the slug the LLM consumes (via
     * {@code wiki_read_page(slug=…)}), the human-readable title/summary for
     * picker UI, and the KB id + name for disambiguation when an agent is
     * scoped to multiple KBs that may share a slug. Deliberately omits
     * content / embedding / sourceRawIds / outgoingLinks — those stay
     * admin-console-only.
     */
    @lombok.Data
    public static class WebChatWikiPageView {
        private Long kbId;
        private String kbName;
        /** Immutable slug — what the picker splices into the {@code [[slug]]} token; the LLM consumes it as {@code wiki_read_page(slug=…)}. */
        private String slug;
        private String title;
        private String summary;
        /** {@code entity} / {@code concept} / {@code source} / ... — never {@code synthesis} (filtered out upstream). Useful for picker grouping/icons. */
        private String pageType;

        static WebChatWikiPageView from(vip.mate.wiki.model.WikiPageEntity p, String kbName) {
            WebChatWikiPageView v = new WebChatWikiPageView();
            v.kbId = p.getKbId();
            v.kbName = kbName;
            v.slug = p.getSlug();
            v.title = p.getTitle();
            v.summary = p.getSummary();
            v.pageType = p.getPageType();
            return v;
        }
    }

    /** Body for {@code POST /sessions} — explicitly create an empty thread. */
    @lombok.Data
    public static class WebChatCreateSessionRequest {
        /** Optional; server mints a UUID when absent (same convention as /stream). */
        private String visitorId;
        /** Optional; server generates one when absent. Whitelisted charset, ≤ 64 chars. */
        private String sessionId;
        /** Optional; 1–100 chars when non-blank, otherwise left null so the first
         *  /stream message still derives the title (mirrors PUT /sessions/title rules). */
        private String title;
        /** Optional; override the channel's bound agent. Must belong to the channel's
         *  workspace. Only applied on first creation — once the thread exists, a
         *  different agentId is ignored. */
        private Long agentId;
    }
}
