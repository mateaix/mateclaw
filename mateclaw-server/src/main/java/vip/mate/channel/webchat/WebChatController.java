package vip.mate.channel.webchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
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
import vip.mate.workspace.conversation.vo.MessageVO;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

        String visitorId = request.getVisitorId() != null ? request.getVisitorId() : UUID.randomUUID().toString();

        // Optional sessionId lets one visitor hold multiple isolated threads. It is only ever
        // composed into the server-derived conversationId (kept under the key+visitor namespace),
        // never accepted as a raw conversationId — so a caller can't reach another tenant's history.
        final String effectiveSessionId;
        try {
            effectiveSessionId = normalizeSessionId(request.getSessionId());
        } catch (IllegalArgumentException ex) {
            sendErrorAndComplete(emitter, ex.getMessage());
            return emitter;
        }
        String conversationId = deriveConversationId(apiKey, visitorId, effectiveSessionId);
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
                var conv = conversationService.getOrCreateConversation(conversationId, resolvedAgentId, "webchat:" + visitorId, webWsId);

                // 保存用户消息
                conversationService.saveMessage(conversationId, "user", message, List.of());

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
                final int[] usage = {0, 0}; // [promptTokens, completionTokens]
                final String[] modelInfo = {null, null}; // [runtimeModel, runtimeProvider]

                // Attribute memory to this external visitor so each end-user
                // behind the shared webchat account is isolated. The same origin
                // resolves the owner key for both the read (recall) and write
                // (publish) paths below.
                vip.mate.agent.context.ChatOrigin webchatOrigin =
                        vip.mate.agent.context.ChatOrigin.web(conversationId, visitorId, webWsId, null)
                                .withSender(null, "api", null);
                String webchatOwnerKey = memoryOwnerResolver.resolve(webchatOrigin);

                agentService.chatStructuredStream(resolvedAgentId, message, conversationId, visitorId, null, webchatOrigin)
                        .doOnNext(delta -> {
                            if (delta.isEvent() && "_usage_final".equals(delta.eventType())) {
                                Map<String, Object> data = delta.eventData();
                                usage[0] = ((Number) data.getOrDefault("promptTokens", 0)).intValue();
                                usage[1] = ((Number) data.getOrDefault("completionTokens", 0)).intValue();
                                Object model = data.get("runtimeModelName");
                                Object provider = data.get("runtimeProviderId");
                                if (model != null) modelInfo[0] = model.toString();
                                if (provider != null) modelInfo[1] = provider.toString();
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
                                            "completed", usage[0], usage[1], modelInfo[0], modelInfo[1]);
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
            @RequestParam String visitorId) {
        ChannelEntity channel = resolveChannel(apiKey);
        if (channel == null) {
            return R.fail(401, "Invalid API Key");
        }
        if (!verifyVisitorToken(visitorTokenSecret, channel.getId(), visitorId, visitorToken)) {
            return R.fail(401, "Invalid or missing visitor token");
        }
        String base = deriveConversationId(apiKey, visitorId, null);
        String prefix = base + ":";
        List<WebChatSessionView> sessions = conversationService.listConversations("webchat:" + visitorId).stream()
                .filter(c -> c.getConversationId() != null
                        && (c.getConversationId().equals(base) || c.getConversationId().startsWith(prefix)))
                .map(c -> {
                    String cid = c.getConversationId();
                    String sid = cid.equals(base) ? null : cid.substring(prefix.length());
                    return new WebChatSessionView(sid, c.getTitle(), c.getLastActiveTime(), c.getMessageCount());
                })
                .collect(Collectors.toList());
        return R.ok(sessions);
    }

    /**
     * 获取某会话线程的消息列表
     */
    @Operation(summary = "获取会话消息")
    @GetMapping("/sessions/messages")
    public R<List<MessageVO>> sessionMessages(
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
        String conversationId = deriveConversationId(apiKey, visitorId, sid);
        if (!ownsConversation(conversationId, visitorId)) {
            return R.fail(404, "Session not found");
        }
        return R.ok(conversationService.listMessageViews(conversationId));
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
        String conversationId = deriveConversationId(apiKey, visitorId, sid);
        if (!ownsConversation(conversationId, visitorId)) {
            return R.fail(404, "Session not found");
        }
        conversationService.deleteConversation(conversationId);
        return R.ok();
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

    /**
     * 由服务端拼装 conversationId，始终钳在 key + visitor 命名空间内。
     * 绝不接受调用方传入的裸 conversationId。
     */
    private String deriveConversationId(String apiKey, String visitorId, String sessionId) {
        String base = "webchat:" + apiKey.substring(0, Math.min(8, apiKey.length())) + ":" + visitorId;
        return sessionId != null ? base + ":" + sessionId : base;
    }

    /**
     * 存在性守卫：会话存在且属于本 visitor 命名空间时返回 true，否则 404。
     * <p>注意：这<b>不是</b>鉴权边界——conversationId 由调用方自报的 visitorId 派生，
     * 等式两边同源，单凭它无法防越权。真正的鉴权由 {@link #verifyVisitorToken} 完成。
     */
    private boolean ownsConversation(String conversationId, String visitorId) {
        ConversationEntity conv = conversationService.findByConversationId(conversationId);
        return conv != null && ("webchat:" + visitorId).equals(conv.getUsername());
    }

    /**
     * 用服务端密钥对 (channelId, visitorId) 做 HMAC-SHA256，签发不可伪造的 visitor token。
     * 载荷含 channelId，使 token 不能跨渠道复用。
     */
    static String computeVisitorToken(String secret, Long channelId, String visitorId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((channelId + ":" + visitorId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /**
     * 常量时间校验调用方回传的 token：缺失/不匹配均返回 false。
     */
    static boolean verifyVisitorToken(String secret, Long channelId, String visitorId, String presented) {
        if (presented == null || presented.isEmpty() || visitorId == null || channelId == null) {
            return false;
        }
        byte[] expected = computeVisitorToken(secret, channelId, visitorId).getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
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
    }
}
