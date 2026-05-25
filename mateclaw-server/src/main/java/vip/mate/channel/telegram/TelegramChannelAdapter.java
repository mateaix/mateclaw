package vip.mate.channel.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.ExponentialBackoff;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Telegram 渠道适配器
 * <p>
 * 支持两种接入模式：
 * - Long-Polling（默认）：通过 getUpdates 轮询，无需公网 IP，适合开发和内网部署
 * - Webhook：配置 webhook_url 后自动切换，需要公网可访问的 URL
 * <p>
 * 参考 MateClaw 实现，增强了：
 * - 持续 Typing 指示器（每 4 秒发送一次，直到回复完成）
 * - 指数退避重连（2s→30s，无限重试）
 * - Markdown 解析失败时自动降级为纯文本
 * <p>
 * 配置项（configJson）：
 * - bot_token: Telegram Bot Token（从 @BotFather 获取，必填）
 * - webhook_url: Webhook 地址（可选，配置后切换为 Webhook 模式）
 * - show_typing: 是否显示"正在输入"状态，默认 true
 * - polling_timeout: Long-Polling 超时秒数，默认 20
 *
 * @author MateClaw Team
 */
@Slf4j
public class TelegramChannelAdapter extends AbstractChannelAdapter {

    public static final String CHANNEL_TYPE = "telegram";

    private HttpClient httpClient;
    private String botToken;
    private String apiBaseUrl;

    /** Long-Polling 线程 */
    private volatile Thread pollingThread;
    private volatile boolean polling;

    /** getUpdates offset，用于确认已处理的 update */
    private final AtomicLong updateOffset = new AtomicLong(0);

    /** 活跃的 Typing 任务：chatId -> ScheduledFuture */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> typingTasks = new ConcurrentHashMap<>();
    private ScheduledExecutorService typingScheduler;

    /** Typing 指示器发送间隔（秒） */
    private static final int TYPING_INTERVAL_S = 4;
    /** Typing 最大持续时间（秒） */
    private static final int TYPING_TIMEOUT_S = 180;

    public TelegramChannelAdapter(ChannelEntity channelEntity,
                                  ChannelMessageRouter messageRouter,
                                  ObjectMapper objectMapper) {
        super(channelEntity, messageRouter, objectMapper);
        // Telegram: 2s→4s→8s→16s→30s 指数退避，无限重试
        this.backoff = new ExponentialBackoff(2000, 30000, 2.0, -1);
    }

    @Override
    protected void doStart() {
        this.botToken = getConfigString("bot_token");
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalStateException("Telegram channel requires bot_token in configJson");
        }

        this.apiBaseUrl = "https://api.telegram.org/bot" + botToken;

        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));

        // 代理配置：覆盖所有 Telegram Bot API 请求（polling / webhook / send / typing）
        String httpProxy = getConfigString("http_proxy");
        if (httpProxy != null && !httpProxy.isBlank()) {
            try {
                URI proxyUri = URI.create(httpProxy);
                String proxyHost = proxyUri.getHost();
                int proxyPort = proxyUri.getPort();
                if (proxyHost != null && proxyPort > 0) {
                    clientBuilder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
                    log.info("[telegram] Using HTTP proxy: {}:{}", proxyHost, proxyPort);
                } else {
                    log.warn("[telegram] Invalid http_proxy (missing host or port): '{}'", httpProxy);
                }
            } catch (Exception e) {
                log.warn("[telegram] Invalid http_proxy '{}', falling back to direct: {}", httpProxy, e.getMessage());
            }
        }

        this.httpClient = clientBuilder.build();

        this.typingScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "telegram-typing-" + channelEntity.getId());
            t.setDaemon(true);
            return t;
        });

        if (resolveWebhookMode()) {
            String webhookUrl = getConfigString("webhook_url");
            registerWebhook(webhookUrl);
            log.info("[telegram] Telegram channel initialized (Webhook mode)");
            log.info("[telegram] Webhook URL: {}", webhookUrl);
        } else {
            // Long-Polling 模式（��认）
            deleteWebhook(); // 确保清除旧的 webhook
            startPolling();
            log.info("[telegram] Telegram channel initialized (Long-Polling mode)");
        }
    }

    @Override
    protected void doStop() {
        stopPolling();
        stopAllTyping();
        if (typingScheduler != null) {
            typingScheduler.shutdownNow();
            typingScheduler = null;
        }
        this.httpClient = null;
        this.botToken = null;
        this.apiBaseUrl = null;
        log.info("[telegram] Telegram channel stopped");
    }

    /**
     * 重连时根据模式执行对应操作
     */
    @Override
    protected void doReconnect() {
        log.info("[telegram] Reconnecting...");
        if (resolveWebhookMode()) {
            registerWebhookOrThrow(getConfigString("webhook_url"));
            log.info("[telegram] Webhook re-registered successfully");
        } else {
            stopPolling();
            startPolling();
            log.info("[telegram] Polling restarted");
        }
    }

    /**
     * 判断是否使用 Webhook 模式。
     * <p>
     * 兼容旧配置：如果 connection_mode 未设置，根据 webhook_url 是否存在来推断。
     * - connection_mode=webhook + webhook_url 非空 → Webhook
     * - connection_mode=polling → Polling
     * - connection_mode 缺失 + webhook_url 非空 → Webhook（兼容旧配置）
     * - 其余 → Polling
     *
     * <p>Package-private so {@link #requiresSingleLeader()} can mirror the
     * same predicate — the two answers must stay in lockstep, otherwise a
     * single change to mode detection here would silently mis-classify the
     * channel for multi-node coordination.
     */
    boolean resolveWebhookMode() {
        String connectionMode = getConfigString("connection_mode");
        String webhookUrl = getConfigString("webhook_url");
        boolean hasWebhookUrl = webhookUrl != null && !webhookUrl.isBlank();

        if (connectionMode != null) {
            // 显式指定了 connection_mode，按其值决定
            return "webhook".equals(connectionMode) && hasWebhookUrl;
        }
        // 未设置 connection_mode（旧配置）：有 webhook_url 则走 Webhook，否则 Polling
        return hasWebhookUrl;
    }

    // ==================== Long-Polling ====================

    private void startPolling() {
        this.polling = true;
        this.pollingThread = new Thread(this::pollingLoop, "telegram-polling-" + channelEntity.getId());
        this.pollingThread.setDaemon(true);
        this.pollingThread.start();
    }

    private void stopPolling() {
        this.polling = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
            pollingThread = null;
        }
    }

    /**
     * Long-Polling 主循环
     * <p>
     * 参考 MateClaw 的 _polling_cycle：
     * - 使用 long poll（timeout=20s），Telegram 服务器在有新消息时立即返回
     * - 失败时通过 AbstractChannelAdapter 的指数退避重连
     */
    @SuppressWarnings("unchecked")
    private void pollingLoop() {
        int pollingTimeout = 20;
        try {
            pollingTimeout = Integer.parseInt(getConfigString("polling_timeout", "20"));
        } catch (NumberFormatException ignored) {}

        log.info("[telegram] Polling loop started (timeout={}s)", pollingTimeout);

        while (polling && running.get()) {
            try {
                Map<String, Object> params = new java.util.LinkedHashMap<>();
                params.put("timeout", pollingTimeout);
                params.put("allowed_updates", List.of("message", "edited_message"));
                long offset = updateOffset.get();
                if (offset > 0) {
                    params.put("offset", offset);
                }

                String jsonBody = objectMapper.writeValueAsString(params);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiBaseUrl + "/getUpdates"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        // 请求超时 = polling 超时 + 10s 网络余量
                        .timeout(Duration.ofSeconds(pollingTimeout + 10))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 401) {
                    log.error("[telegram] Invalid bot token (401 Unauthorized), stopping polling");
                    polling = false;
                    connectionState.set(ConnectionState.ERROR);
                    lastError = "Invalid bot token";
                    return;
                }

                if (response.statusCode() != 200) {
                    throw new RuntimeException("getUpdates failed: status=" + response.statusCode());
                }

                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                if (!Boolean.TRUE.equals(result.get("ok"))) {
                    throw new RuntimeException("getUpdates returned ok=false: " + result.get("description"));
                }

                // 连接正常
                if (connectionState.get() != ConnectionState.CONNECTED) {
                    connectionState.set(ConnectionState.CONNECTED);
                    lastError = null;
                    backoff.reset();
                }

                List<Map<String, Object>> updates = (List<Map<String, Object>>) result.get("result");
                if (updates != null && !updates.isEmpty()) {
                    for (Map<String, Object> update : updates) {
                        Number updateId = (Number) update.get("update_id");
                        if (updateId != null) {
                            updateOffset.set(updateId.longValue() + 1);
                        }
                        processUpdate(update);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("[telegram] Polling interrupted");
                break;
            } catch (Exception e) {
                if (!polling || !running.get()) break;
                log.warn("[telegram] Polling error: {}", e.getMessage());
                onDisconnected("Polling error: " + e.getMessage());
                // 退避等待后重试
                try {
                    long delay = backoff.nextDelayMs();
                    log.info("[telegram] Retrying in {}ms", delay);
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("[telegram] Polling loop ended");
    }

    // ==================== Webhook ====================

    /**
     * 注册 Webhook，失败时触发重连
     */
    private void registerWebhook(String webhookUrl) {
        try {
            registerWebhookOrThrow(webhookUrl);
            log.info("[telegram] Webhook registered: {}", webhookUrl);
        } catch (Exception e) {
            log.error("[telegram] Webhook registration failed: {}", e.getMessage());
            onDisconnected("Webhook registration failed: " + e.getMessage());
        }
    }

    private void registerWebhookOrThrow(String webhookUrl) {
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of("url", webhookUrl));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/setWebhook"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("setWebhook failed: status=" + response.statusCode() + ", body=" + response.body());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                throw new RuntimeException("setWebhook returned ok=false: " + result.get("description"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Webhook registration failed: " + e.getMessage(), e);
        }
    }

    /**
     * 删除 Webhook（切换到 Long-Polling 前必须调用）
     */
    private void deleteWebhook() {
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of("drop_pending_updates", false));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/deleteWebhook"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.debug("[telegram] Webhook deleted (switching to Long-Polling)");
            }
        } catch (Exception e) {
            log.debug("[telegram] Failed to delete webhook (may not exist): {}", e.getMessage());
        }
    }

    // ==================== 消息处理 ====================

    /**
     * 处理 Telegram Webhook 回调（Webhook 模式使用）
     */
    public void handleWebhook(Map<String, Object> payload) {
        try {
            processUpdate(payload);
        } catch (Exception e) {
            log.error("[telegram] Failed to handle webhook: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理单个 Update（Long-Polling 和 Webhook 共用）
     */
    @SuppressWarnings("unchecked")
    private void processUpdate(Map<String, Object> update) {
        Map<String, Object> message = (Map<String, Object>) update.get("message");
        if (message == null) {
            // 也尝试处理 edited_message
            message = (Map<String, Object>) update.get("edited_message");
        }
        if (message == null) {
            log.debug("[telegram] No message in update, ignoring");
            return;
        }

        // 发送者
        Map<String, Object> from = (Map<String, Object>) message.get("from");
        String senderId = from != null ? String.valueOf(from.get("id")) : "unknown";
        String senderName = from != null ? (String) from.get("first_name") : null;

        // 会话
        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        String chatId = chat != null ? String.valueOf(chat.get("id")) : senderId;
        String chatType = chat != null ? (String) chat.get("type") : "private";

        Integer messageId = (Integer) message.get("message_id");

        // 构建 contentParts
        List<MessageContentPart> contentParts = new ArrayList<>();
        String textContent = (String) message.get("text");
        // RFC-025 Change 4: caption 净化 — .epub / .mobi 等附件的二进制元数据会被
        // 某些 Telegram Bot API 版本塞进 caption，非 UTF-8 字节序列直接进 LLM prompt
        // 会让 token 数爆涨、成本不可控
        String caption = sanitizeInboundText((String) message.get("caption"));
        boolean hasVoice = message.get("voice") != null;

        if (textContent != null && !textContent.isBlank()) {
            contentParts.add(MessageContentPart.text(textContent));
        }

        // 图片：photo 是尺寸数组，取最大尺寸（最后一个）
        List<Map<String, Object>> photos = (List<Map<String, Object>>) message.get("photo");
        if (photos != null && !photos.isEmpty()) {
            Map<String, Object> bestPhoto = photos.get(photos.size() - 1);
            String fileId = (String) bestPhoto.get("file_id");
            if (fileId != null) {
                contentParts.add(MessageContentPart.image(fileId, null));
            }
            if (textContent == null) textContent = caption != null ? caption : "[图片]";
        }

        // 文件
        Map<String, Object> document = (Map<String, Object>) message.get("document");
        if (document != null) {
            String fileId = (String) document.get("file_id");
            String fileName = (String) document.get("file_name");
            String mimeType = (String) document.get("mime_type");
            if (fileId != null) {
                contentParts.add(MessageContentPart.file(fileId, fileName, mimeType));
            }
            if (textContent == null) textContent = caption != null ? caption : "[文件: " + (fileName != null ? fileName : "") + "]";
        }

        // 语音
        Map<String, Object> voice = (Map<String, Object>) message.get("voice");
        if (voice != null) {
            String fileId = (String) voice.get("file_id");
            if (fileId != null) {
                contentParts.add(MessageContentPart.audio(fileId, "voice.ogg"));
            }
            if (textContent == null) textContent = "[语音]";
        }

        // 视频
        Map<String, Object> video = (Map<String, Object>) message.get("video");
        if (video != null) {
            String fileId = (String) video.get("file_id");
            String fileName = (String) video.get("file_name");
            if (fileId != null) {
                contentParts.add(MessageContentPart.video(fileId, fileName));
            }
            if (textContent == null) textContent = caption != null ? caption : "[视频]";
        }

        // caption 作为文本内容补充
        if (caption != null && !caption.isBlank() && message.get("text") == null) {
            contentParts.add(0, MessageContentPart.text(caption));
        }

        if (contentParts.isEmpty()) {
            return;
        }

        ChannelMessage channelMessage = ChannelMessage.builder()
                .messageId(messageId != null ? String.valueOf(messageId) : null)
                .channelType(CHANNEL_TYPE)
                .senderId(senderId)
                .senderName(senderName)
                .chatId("private".equals(chatType) ? null : chatId)
                .content(textContent != null ? textContent : "")
                .contentType(determineContentType(contentParts))
                .contentParts(contentParts)
                .inputMode(hasVoice ? "voice" : "text")
                .timestamp(LocalDateTime.now())
                .replyToken(chatId)
                .rawPayload(update)
                .build();

        onMessage(channelMessage);
    }

    private String determineContentType(List<MessageContentPart> parts) {
        for (MessageContentPart p : parts) {
            if (!"text".equals(p.getType())) return p.getType();
        }
        return "text";
    }

    // ==================== 消息发送 ====================

    @Override
    public void sendMessage(String targetId, String content) {
        if (httpClient == null || botToken == null) {
            log.warn("[telegram] Channel not started, cannot send message");
            return;
        }

        // 启动持续 Typing 指示
        if (getConfigBoolean("show_typing", true)) {
            startTyping(targetId);
        }

        try {
            // 先尝试 Markdown 格式发送
            boolean sent = trySendText(targetId, content, "Markdown");
            if (!sent) {
                // Markdown 解析失败，降级为纯文本
                log.debug("[telegram] Markdown failed, retrying as plain text");
                trySendText(targetId, content, null);
            }
        } finally {
            stopTyping(targetId);
        }
    }

    /**
     * 尝试发送文本消息
     *
     * @return true 如果发送成功或遇到非 parse_mode 相关的错误（不应重试）
     */
    @SuppressWarnings("unchecked")
    private boolean trySendText(String targetId, String content, String parseMode) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("chat_id", targetId);
            body.put("text", content);
            if (parseMode != null) {
                body.put("parse_mode", parseMode);
            }

            String jsonBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }

            // 仅当 400 + parse_mode 且 description 明确指向解析错误时才降级重试
            if (response.statusCode() == 400 && parseMode != null) {
                boolean isParseError = false;
                try {
                    Map<String, Object> errResult = objectMapper.readValue(response.body(), Map.class);
                    String desc = String.valueOf(errResult.getOrDefault("description", ""));
                    // Telegram 返回类似 "Bad Request: can't parse entities" 或 "can't parse message text"
                    isParseError = desc.contains("can't parse");
                } catch (Exception ignored) {}

                if (isParseError) {
                    log.debug("[telegram] Markdown parse error, will retry as plain text: {}", response.body());
                    return false;
                }
            }

            log.warn("[telegram] Send message failed: status={}, body={}", response.statusCode(), response.body());
            return true; // 非解析错误，不再重试

        } catch (Exception e) {
            log.error("[telegram] Failed to send message: {}", e.getMessage(), e);
            return true; // 网络错误，不再重试
        }
    }

    @Override
    public void sendContentParts(String targetId, List<MessageContentPart> parts) {
        if (httpClient == null || botToken == null) {
            log.warn("[telegram] Channel not started, cannot send message");
            return;
        }

        if (getConfigBoolean("show_typing", true)) {
            startTyping(targetId);
        }

        try {
            for (MessageContentPart part : parts) {
                if (part == null) continue;
                try {
                    switch (part.getType()) {
                        case "text" -> {
                            if (part.getText() != null && !part.getText().isBlank()) {
                                sendMessage(targetId, part.getText());
                            }
                        }
                        case "image" -> {
                            if (part.getMediaId() != null) {
                                sendTelegramMedia(targetId, "sendPhoto", "photo", part.getMediaId());
                            } else if (part.getFileUrl() != null) {
                                sendTelegramMedia(targetId, "sendPhoto", "photo", part.getFileUrl());
                            }
                        }
                        case "file" -> {
                            if (part.getMediaId() != null) {
                                sendTelegramMedia(targetId, "sendDocument", "document", part.getMediaId());
                            }
                        }
                        case "audio" -> {
                            if (part.getMediaId() != null) {
                                sendTelegramMedia(targetId, "sendVoice", "voice", part.getMediaId());
                            }
                        }
                        case "video" -> {
                            if (part.getMediaId() != null) {
                                sendTelegramMedia(targetId, "sendVideo", "video", part.getMediaId());
                            }
                        }
                        default -> {
                            if (part.getText() != null) sendMessage(targetId, part.getText());
                        }
                    }
                } catch (Exception e) {
                    log.error("[telegram] Failed to send content part ({}): {}", part.getType(), e.getMessage());
                }
            }
        } finally {
            stopTyping(targetId);
        }
    }

    /**
     * 通过 Telegram Bot API 发送媒体消息
     */
    private void sendTelegramMedia(String chatId, String method, String mediaField, String mediaValue) {
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "chat_id", chatId,
                    mediaField, mediaValue
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/" + method))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[telegram] {} failed: status={}, body={}", method, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("[telegram] Failed to {}: {}", method, e.getMessage(), e);
        }
    }

    // ==================== Typing 指示器 ====================

    /**
     * 启动持续 Typing 指示（每 4 秒发送一次，最长 180 秒）
     * <p>
     * 参考 MateClaw 的 _typing_loop 实现。
     * Telegram 的 typing 状态持续约 5 秒，所以每 4 秒重发一次。
     */
    private void startTyping(String chatId) {
        if (typingScheduler == null || typingScheduler.isShutdown()) return;

        // 先取消已存在的同一 chatId 的 typing 任务
        stopTyping(chatId);

        // 立即发送一次
        sendTypingAction(chatId);

        // 每 4 秒重发
        long startTime = System.currentTimeMillis();
        ScheduledFuture<?> future = typingScheduler.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() - startTime > TYPING_TIMEOUT_S * 1000L) {
                stopTyping(chatId);
                return;
            }
            sendTypingAction(chatId);
        }, TYPING_INTERVAL_S, TYPING_INTERVAL_S, TimeUnit.SECONDS);

        typingTasks.put(chatId, future);
    }

    /**
     * 停止 Typing 指示
     */
    private void stopTyping(String chatId) {
        ScheduledFuture<?> future = typingTasks.remove(chatId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void stopAllTyping() {
        typingTasks.forEach((id, future) -> future.cancel(false));
        typingTasks.clear();
    }

    private void sendTypingAction(String chatId) {
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "chat_id", chatId,
                    "action", "typing"
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/sendChatAction"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("[telegram] Failed to send typing action: {}", e.getMessage());
        }
    }

    // ==================== 主动推送 ====================

    @Override
    public boolean supportsProactiveSend() {
        return true;
    }

    @Override
    public void proactiveSend(String targetId, String content) {
        sendMessage(targetId, content);
    }

    /**
     * RFC-063r §2.10: forum-thread-aware proactive send. When
     * {@link vip.mate.channel.DeliveryOptions#threadId()} is set (Telegram
     * forum {@code message_thread_id}), include it in the {@code sendMessage}
     * call so the cron result lands in the correct thread of a forum group.
     * Falls back to the legacy chat-level send when null.
     */
    @Override
    public void proactiveSend(String targetId, String content,
                              vip.mate.channel.DeliveryOptions options) {
        if (options == null || options.threadId() == null || options.threadId().isBlank()) {
            sendMessage(targetId, content);
            return;
        }
        if (httpClient == null || botToken == null) {
            log.warn("[telegram] Channel not started, cannot send proactive message");
            return;
        }
        Integer threadId;
        try {
            threadId = Integer.valueOf(options.threadId());
        } catch (NumberFormatException nfe) {
            log.warn("[telegram] Invalid message_thread_id '{}'; sending to main chat", options.threadId());
            sendMessage(targetId, content);
            return;
        }
        try {
            // Try Markdown first, fall back to plain on parse error — same
            // contract as sendMessage but with message_thread_id added.
            if (!sendThreadedText(targetId, threadId, content, "Markdown")) {
                sendThreadedText(targetId, threadId, content, null);
            }
        } catch (Exception e) {
            log.error("[telegram] proactiveSend(threadId={}) failed: {}", threadId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean sendThreadedText(String targetId, Integer threadId, String content, String parseMode) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("chat_id", targetId);
            body.put("message_thread_id", threadId);
            body.put("text", content);
            if (parseMode != null) {
                body.put("parse_mode", parseMode);
            }
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            if (response.statusCode() == 400 && parseMode != null) {
                try {
                    Map<String, Object> errResult = objectMapper.readValue(response.body(), Map.class);
                    String desc = String.valueOf(errResult.getOrDefault("description", ""));
                    if (desc.contains("can't parse")) {
                        return false;
                    }
                } catch (Exception ignored) {}
            }
            log.warn("[telegram] Threaded send failed: status={}, body={}", response.statusCode(), response.body());
            return true;
        } catch (Exception e) {
            log.error("[telegram] Threaded send error: {}", e.getMessage(), e);
            return true;
        }
    }

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    /**
     * Long-polling mode runs a {@code getUpdates(offset=…)} loop that
     * acknowledges each delivered update; multiple nodes polling the same
     * bot token would steal updates from each other (whichever node calls
     * {@code getUpdates} next consumes the queue, and the others get
     * nothing). The leader gate ensures only one node polls at a time.
     *
     * <p>Webhook mode is exempt: Telegram POSTs to a public URL fanned
     * by the load balancer, so every node may safely receive callbacks.
     */
    @Override
    public boolean requiresSingleLeader() {
        return !resolveWebhookMode();
    }

    /** RFC-025 Change 4 入站文本净化上限（防止 caption 含超长二进制撑爆 prompt）。 */
    private static final int INBOUND_TEXT_MAX = 4096;

    /**
     * 净化入站文本（caption / text 可选复用）：
     * <ul>
     *   <li>剥掉控制字符与非可打印字节，保留常见空白、中日韩文字</li>
     *   <li>硬封顶 {@value #INBOUND_TEXT_MAX} 字符，超出追加 truncation 标记</li>
     * </ul>
     * <p>RFC-025 Change 4：应对 .epub / .mobi 等附件把二进制元数据塞到 caption 的场景。</p>
     */
    static String sanitizeInboundText(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        // 策略：剥掉控制字符（\p{Cc}）但保留 \t \r \n；再剥零宽/BIDI 等格式字符（\p{Cf}）。
        // 其它所有 Unicode 可见字符（含 CJK 全角标点、emoji、西欧字母、阿拉伯数字等）均保留。
        // 相比白名单法：对全角 / 异域文字 / 新 emoji 都零误杀；仅打掉真正会爆 token 的控制字节。
        String cleaned = raw.replaceAll("[\\p{Cc}&&[^\\t\\r\\n]]", "")
                .replaceAll("\\p{Cf}", "");
        if (cleaned.length() <= INBOUND_TEXT_MAX) return cleaned;
        return cleaned.substring(0, INBOUND_TEXT_MAX) + " ...[truncated]";
    }
}
