package vip.mate.channel.webchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mate.channel.web.Utf8SseEmitter;
import vip.mate.agent.AgentService;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.common.result.R;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        Long agentId = channel.getAgentId();
        if (agentId == null) {
            sendErrorAndComplete(emitter, "No agent configured for this WebChat channel");
            return emitter;
        }

        String visitorId = request.getVisitorId() != null ? request.getVisitorId() : UUID.randomUUID().toString();
        String conversationId = "webchat:" + apiKey.substring(0, Math.min(8, apiKey.length())) + ":" + visitorId;
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
                var webAgent = agentService.getAgent(agentId);
                Long webWsId = webAgent != null ? webAgent.getWorkspaceId() : 1L;
                var conv = conversationService.getOrCreateConversation(conversationId, agentId, "webchat:" + visitorId, webWsId);

                // 保存用户消息
                conversationService.saveMessage(conversationId, "user", message, List.of());

                // 初始化 SSE 流跟踪
                streamTracker.register(conversationId);
                streamTracker.attach(conversationId, emitter);

                // Accumulate the assistant reply so it can be persisted on stream completion.
                // Pattern mirrors ChatController: always accumulate, only broadcast when the
                // delta is not a persistence-only echo of content already streamed by inner nodes.
                StringBuilder assistantReply = new StringBuilder();
                // Token usage + model attribution: capture _usage_final event emitted at stream end
                final int[] usage = {0, 0}; // [promptTokens, completionTokens]
                final String[] modelInfo = {null, null}; // [runtimeModel, runtimeProvider]

                agentService.chatStructuredStream(agentId, message, conversationId, visitorId)
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
                                        agentId, conversationId, message, reply, "webchat");
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

    // ==================== 内部方法 ====================

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
    }
}
