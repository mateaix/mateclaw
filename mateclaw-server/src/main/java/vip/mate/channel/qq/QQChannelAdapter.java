package vip.mate.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.ExponentialBackoff;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QQ 渠道适配器
 * <p>
 * QQ 渠道实现：
 * - WebSocket 长连接接收消息事件
 * - HTTP API 发送消息（C2C / Group / Guild / DM）
 * - Access Token 自动获取与缓存
 * - 心跳保活 + 自动重连（RESUME / IDENTIFY）
 * - 富媒体消息支持（图片、视频、音频、文件）
 * - URL 过滤（QQ API 拒绝明文 URL）
 * <p>
 * 配置项（configJson）：
 * - app_id: QQ Bot 的 AppID（必填）
 * - client_secret: QQ Bot 的 AppSecret（必填）
 * - markdown_enabled: 是否启用 Markdown 消息格式，默认 true
 * - max_reconnect_attempts: 最大重连次数，默认 100
 *
 * @author MateClaw Team
 */
@Slf4j
public class QQChannelAdapter extends AbstractChannelAdapter {

    public static final String CHANNEL_TYPE = "qq";

    // ==================== QQ WebSocket 协议常量 ====================

    private static final int OP_DISPATCH = 0;
    private static final int OP_HEARTBEAT = 1;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RESUME = 6;
    private static final int OP_RECONNECT = 7;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO = 10;
    private static final int OP_HEARTBEAT_ACK = 11;

    // Intents 位掩码
    private static final int INTENT_PUBLIC_GUILD_MESSAGES = 1 << 30;
    private static final int INTENT_DIRECT_MESSAGE = 1 << 12;
    private static final int INTENT_GROUP_AND_C2C = 1 << 25;

    private static final String DEFAULT_API_BASE = "https://api.sgroup.qq.com";
    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";

    // 快速断连检测
    private static final int QUICK_DISCONNECT_THRESHOLD_SECONDS = 5;
    private static final int MAX_QUICK_DISCONNECT_COUNT = 3;
    private static final long RATE_LIMIT_DELAY_MS = 60_000;

    // URL 匹配模式（QQ API 拒绝消息中包含 URL）
    private static final java.util.regex.Pattern URL_PATTERN =
            java.util.regex.Pattern.compile("https?://[^\\s]+|www\\.[^\\s]+", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern IMAGE_TAG_PATTERN =
            java.util.regex.Pattern.compile("\\[Image: (https?://[^\\]]+)\\]", java.util.regex.Pattern.CASE_INSENSITIVE);

    // ==================== 配置 ====================

    private String appId;
    private String clientSecret;
    private boolean markdownEnabled;

    // ==================== 运行时状态 ====================

    private HttpClient httpClient;

    /** Access Token 缓存 */
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;
    private final Object tokenLock = new Object();

    /** WebSocket 状态 */
    private volatile String sessionId;
    private final AtomicInteger lastSeq = new AtomicInteger(0);
    private volatile int reconnectAttempts = 0;
    private volatile long lastConnectTime = 0;
    private volatile int quickDisconnectCount = 0;

    /** 消息序号（QQ API 要求递增 msg_seq） */
    private final AtomicLong msgSeqCounter = new AtomicLong(1);

    /** WebSocket 连接线程 */
    private Thread wsThread;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    /** 心跳调度器 */
    private ScheduledExecutorService heartbeatScheduler;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile WebSocket currentWs;

    public QQChannelAdapter(ChannelEntity channelEntity,
                            ChannelMessageRouter messageRouter,
                            ObjectMapper objectMapper) {
        super(channelEntity, messageRouter, objectMapper);
        this.backoff = new ExponentialBackoff(1000, 60000, 2.0, 100);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void doStart() {
        this.appId = getConfigString("app_id");
        this.clientSecret = getConfigString("client_secret");
        if (appId == null || appId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("QQ channel requires app_id and client_secret in configJson");
        }

        this.markdownEnabled = getConfigBoolean("markdown_enabled", true);

        int maxAttempts = 100;
        try {
            String val = getConfigString("max_reconnect_attempts");
            if (val != null) maxAttempts = Integer.parseInt(val);
        } catch (NumberFormatException ignored) {}
        this.backoff = new ExponentialBackoff(1000, 60000, 2.0, maxAttempts);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        this.stopRequested.set(false);
        this.sessionId = null;
        this.lastSeq.set(0);
        this.reconnectAttempts = 0;
        this.quickDisconnectCount = 0;

        // 启动 WebSocket 连接线程
        wsThread = new Thread(this::runWsForever, "qq-ws-" + channelEntity.getId());
        wsThread.setDaemon(true);
        wsThread.start();

        log.info("[qq] QQ channel initialized (appId={})", appId);
    }

    @Override
    protected void doStop() {
        stopRequested.set(true);

        // 停止心跳
        stopHeartbeat();

        // 关闭 WebSocket
        if (currentWs != null) {
            try {
                currentWs.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception e) {
                log.debug("[qq] Error closing WebSocket: {}", e.getMessage());
            }
            currentWs = null;
        }

        // 中断 WebSocket 线程
        if (wsThread != null) {
            wsThread.interrupt();
            try {
                wsThread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            wsThread = null;
        }

        this.httpClient = null;
        log.info("[qq] QQ channel stopped");
    }

    @Override
    protected void doReconnect() {
        // WebSocket 线程自带重连逻辑，这里只需重启线程
        doStop();
        doStart();
    }

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    /**
     * The QQ bot gateway rejects duplicate {@code IDENTIFY} sessions for
     * the same app credentials. Multiple nodes connecting simultaneously
     * trip the connection cap and reconnect-loop forever. The leader gate
     * ensures only one node holds the WebSocket at a time.
     */
    @Override
    public boolean requiresSingleLeader() {
        return true;
    }

    // ==================== Access Token 管理 ====================

    /**
     * 获取 Access Token（带缓存，5 分钟刷新缓冲）
     */
    private String getAccessToken() {
        if (cachedToken != null && Instant.now().plusSeconds(300).isBefore(tokenExpiry)) {
            return cachedToken;
        }
        synchronized (tokenLock) {
            // 双重检查
            if (cachedToken != null && Instant.now().plusSeconds(300).isBefore(tokenExpiry)) {
                return cachedToken;
            }
            return refreshAccessToken();
        }
    }

    private String refreshAccessToken() {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "appId", appId,
                    "clientSecret", clientSecret
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Token request failed: status=" + response.statusCode());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            String token = (String) result.get("access_token");
            Object expiresIn = result.get("expires_in");
            if (token == null || token.isBlank()) {
                throw new RuntimeException("Empty access_token in response: " + response.body());
            }

            int ttl = 7200;
            if (expiresIn instanceof Number n) {
                ttl = n.intValue();
            } else if (expiresIn instanceof String s) {
                ttl = Integer.parseInt(s);
            }

            this.cachedToken = token;
            this.tokenExpiry = Instant.now().plusSeconds(ttl);
            log.debug("[qq] Access token refreshed, expires in {}s", ttl);
            return token;
        } catch (Exception e) {
            log.error("[qq] Failed to refresh access token: {}", e.getMessage());
            throw new RuntimeException("Token refresh failed: " + e.getMessage(), e);
        }
    }

    // ==================== WebSocket 连接管理 ====================

    /**
     * WebSocket 主循环：持续连接，断开后自动重连
     */
    private void runWsForever() {
        while (!stopRequested.get() && running.get()) {
            // 快速断连检测：如果频繁断连，加大等待时间
            if (quickDisconnectCount >= MAX_QUICK_DISCONNECT_COUNT) {
                log.warn("[qq] Too many quick disconnects ({}), waiting {}ms before retry",
                        quickDisconnectCount, RATE_LIMIT_DELAY_MS);
                sleep(RATE_LIMIT_DELAY_MS);
                quickDisconnectCount = 0;
            }

            try {
                wsConnectOnce();
            } catch (Exception e) {
                if (stopRequested.get()) break;
                log.warn("[qq] WebSocket connection error: {}", e.getMessage());
            }

            if (stopRequested.get()) break;

            // 计算重连延迟
            reconnectAttempts++;
            int maxAttempts = backoff.getMaxAttempts();
            if (maxAttempts > 0 && reconnectAttempts >= maxAttempts) {
                log.error("[qq] Max reconnect attempts ({}) exhausted", maxAttempts);
                connectionState.set(ConnectionState.ERROR);
                lastError = "Max reconnect attempts exhausted";
                break;
            }

            long delay = Math.min(1000L * Math.min(reconnectAttempts, 60), 60000);
            log.info("[qq] Reconnecting in {}ms (attempt #{})", delay, reconnectAttempts);
            connectionState.set(ConnectionState.RECONNECTING);
            sleep(delay);
        }
        log.info("[qq] WebSocket loop exited");
    }

    /**
     * 单次 WebSocket 连接
     */
    private void wsConnectOnce() throws Exception {
        // 1. 获取 Gateway URL
        String token = getAccessToken();
        String gatewayUrl = fetchGatewayUrl(token);
        log.info("[qq] Connecting to gateway: {}", gatewayUrl);

        lastConnectTime = System.currentTimeMillis();

        // 2. 建立 WebSocket 连接
        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        StringBuilder messageBuffer = new StringBuilder();

        WebSocket ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(gatewayUrl), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        messageBuffer.append(data);
                        if (last) {
                            String fullMessage = messageBuffer.toString();
                            messageBuffer.setLength(0);
                            handleWsMessage(fullMessage, webSocket);
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        log.info("[qq] WebSocket closed: code={}, reason={}", statusCode, reason);
                        closeFuture.complete(null);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        log.warn("[qq] WebSocket error: {}", error.getMessage());
                        closeFuture.completeExceptionally(error);
                    }
                }).join();

        currentWs = ws;

        try {
            // 等待连接关闭
            closeFuture.get();
        } catch (Exception e) {
            if (!stopRequested.get()) {
                log.warn("[qq] WebSocket closed unexpectedly: {}", e.getMessage());
            }
        } finally {
            stopHeartbeat();
            currentWs = null;

            // 检测快速断连
            long connected = System.currentTimeMillis() - lastConnectTime;
            if (connected < QUICK_DISCONNECT_THRESHOLD_SECONDS * 1000L) {
                quickDisconnectCount++;
                log.warn("[qq] Quick disconnect detected ({}/{})", quickDisconnectCount, MAX_QUICK_DISCONNECT_COUNT);
            } else {
                quickDisconnectCount = 0;
            }
        }
    }

    /**
     * 获取 WebSocket Gateway URL
     */
    private String fetchGatewayUrl(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEFAULT_API_BASE + "/gateway"))
                .header("Authorization", "QQBot " + token)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Gateway request failed: status=" + response.statusCode() + ", body=" + response.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        String url = (String) result.get("url");
        if (url == null || url.isBlank()) {
            throw new RuntimeException("Empty gateway URL in response");
        }
        return url;
    }

    // ==================== WebSocket 消息处理 ====================

    @SuppressWarnings("unchecked")
    private void handleWsMessage(String message, WebSocket ws) {
        try {
            Map<String, Object> payload = objectMapper.readValue(message, Map.class);
            int op = ((Number) payload.getOrDefault("op", -1)).intValue();
            Object data = payload.get("d");
            Number seqNum = (Number) payload.get("s");
            String eventType = (String) payload.get("t");

            // 更新序列号
            if (seqNum != null) {
                lastSeq.set(seqNum.intValue());
            }

            switch (op) {
                case OP_HELLO -> handleHello((Map<String, Object>) data, ws);
                case OP_DISPATCH -> handleDispatch(eventType, data);
                case OP_HEARTBEAT_ACK -> log.trace("[qq] Heartbeat ACK received");
                case OP_RECONNECT -> {
                    log.info("[qq] Server requested reconnect");
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
                }
                case OP_INVALID_SESSION -> {
                    boolean resumable = data instanceof Boolean b && b;
                    log.warn("[qq] Invalid session, resumable={}", resumable);
                    if (!resumable) {
                        sessionId = null;
                        lastSeq.set(0);
                    }
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "invalid_session");
                }
                default -> log.debug("[qq] Unhandled op: {}", op);
            }
        } catch (Exception e) {
            log.error("[qq] Error handling WS message: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理 HELLO：启动心跳，发送 IDENTIFY 或 RESUME
     */
    @SuppressWarnings("unchecked")
    private void handleHello(Map<String, Object> data, WebSocket ws) {
        int heartbeatInterval = ((Number) data.getOrDefault("heartbeat_interval", 45000)).intValue();
        log.info("[qq] Received HELLO, heartbeat_interval={}ms", heartbeatInterval);

        // 启动心跳
        startHeartbeat(ws, heartbeatInterval);

        // 发送 IDENTIFY 或 RESUME
        if (sessionId != null && lastSeq.get() > 0) {
            sendResume(ws);
        } else {
            sendIdentify(ws);
        }
    }

    /**
     * 发送 IDENTIFY
     */
    private void sendIdentify(WebSocket ws) {
        try {
            String token = getAccessToken();
            int intents = INTENT_PUBLIC_GUILD_MESSAGES | INTENT_DIRECT_MESSAGE | INTENT_GROUP_AND_C2C;

            Map<String, Object> identify = Map.of(
                    "op", OP_IDENTIFY,
                    "d", Map.of(
                            "token", "QQBot " + token,
                            "intents", intents,
                            "shard", List.of(0, 1)
                    )
            );

            String json = objectMapper.writeValueAsString(identify);
            ws.sendText(json, true);
            log.info("[qq] IDENTIFY sent (intents={})", intents);
        } catch (Exception e) {
            log.error("[qq] Failed to send IDENTIFY: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送 RESUME（断线恢复）
     */
    private void sendResume(WebSocket ws) {
        try {
            String token = getAccessToken();
            Map<String, Object> resume = Map.of(
                    "op", OP_RESUME,
                    "d", Map.of(
                            "token", "QQBot " + token,
                            "session_id", sessionId,
                            "seq", lastSeq.get()
                    )
            );

            String json = objectMapper.writeValueAsString(resume);
            ws.sendText(json, true);
            log.info("[qq] RESUME sent (session={}, seq={})", sessionId, lastSeq.get());
        } catch (Exception e) {
            log.error("[qq] Failed to send RESUME: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理 DISPATCH 事件
     * <p>
     * `data` payload shape varies by event: object for messages/READY, empty string for RESUMED.
     */
    @SuppressWarnings("unchecked")
    private void handleDispatch(String eventType, Object data) {
        if (eventType == null) return;

        switch (eventType) {
            case "RESUMED" -> {
                // RESUMED carries no payload (`d` is an empty string); don't read from it.
                reconnectAttempts = 0;
                connectionState.set(ConnectionState.CONNECTED);
                lastError = null;
                log.info("[qq] RESUMED successfully");
            }
            case "READY" -> {
                if (!(data instanceof Map<?, ?> map)) {
                    log.warn("[qq] READY event has non-object data: {}", data);
                    return;
                }
                Map<String, Object> readyData = (Map<String, Object>) map;
                sessionId = (String) readyData.get("session_id");
                reconnectAttempts = 0;
                quickDisconnectCount = 0;
                connectionState.set(ConnectionState.CONNECTED);
                lastError = null;
                log.info("[qq] READY received, session_id={}", sessionId);
            }
            case "C2C_MESSAGE_CREATE" -> dispatchMessage("c2c", eventType, data);
            case "GROUP_AT_MESSAGE_CREATE" -> dispatchMessage("group", eventType, data);
            case "AT_MESSAGE_CREATE" -> dispatchMessage("guild", eventType, data);
            case "DIRECT_MESSAGE_CREATE" -> dispatchMessage("dm", eventType, data);
            default -> log.debug("[qq] Unhandled event: {}", eventType);
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatchMessage(String messageType, String eventType, Object data) {
        if (!(data instanceof Map<?, ?> map)) {
            log.warn("[qq] {} event has non-object data, skipping: {}", eventType, data);
            return;
        }
        handleMessageEvent(messageType, (Map<String, Object>) map);
    }

    /**
     * 处理消息事件（C2C / Group / Guild / DM）
     */
    @SuppressWarnings("unchecked")
    private void handleMessageEvent(String messageType, Map<String, Object> data) {
        try {
            // 提取发送者 ID
            String senderId = extractSenderId(messageType, data);
            if (senderId == null || senderId.isBlank()) {
                log.warn("[qq] Cannot determine sender ID for {}: {}", messageType, data);
                return;
            }

            // 提取消息内容
            String content = (String) data.get("content");
            if (content != null) {
                content = content.trim();
            }

            // 消息 ID
            String messageId = (String) data.get("id");

            // 构建 contentParts
            List<MessageContentPart> contentParts = new ArrayList<>();

            // 文本内容
            if (content != null && !content.isBlank()) {
                contentParts.add(MessageContentPart.text(content));
            }

            // 附件（图片、视频、音频、文件）
            List<Map<String, Object>> attachments = (List<Map<String, Object>>) data.get("attachments");
            if (attachments != null) {
                for (Map<String, Object> att : attachments) {
                    String attContentType = (String) att.get("content_type");
                    String url = (String) att.get("url");
                    String filename = (String) att.get("filename");

                    if (attContentType == null) attContentType = "";
                    if (url != null && !url.startsWith("http")) {
                        url = "https://" + url;
                    }

                    if (attContentType.startsWith("image/")) {
                        contentParts.add(MessageContentPart.image(url, filename));
                    } else if (attContentType.startsWith("video/")) {
                        contentParts.add(MessageContentPart.video(url, filename));
                    } else if (attContentType.startsWith("audio/")) {
                        contentParts.add(MessageContentPart.audio(url, filename));
                    } else {
                        contentParts.add(MessageContentPart.file(url, filename, attContentType));
                    }

                    if ((content == null || content.isBlank()) && filename != null) {
                        content = "[" + (attContentType.startsWith("image/") ? "图片" : "文件") + ": " + filename + "]";
                    }
                }
            }

            if (contentParts.isEmpty()) {
                log.debug("[qq] Empty message, ignoring");
                return;
            }

            // 构建 replyToken（格式: messageType:targetId:msgId）
            String replyToken = buildReplyToken(messageType, senderId, data, messageId);

            // chatId：群/频道消息用群/频道 ID，私聊为 null
            String chatId = null;
            if ("group".equals(messageType)) {
                chatId = (String) data.get("group_openid");
            } else if ("guild".equals(messageType) || "dm".equals(messageType)) {
                chatId = (String) data.get("channel_id");
            }

            ChannelMessage channelMessage = ChannelMessage.builder()
                    .messageId(messageId)
                    .channelType(CHANNEL_TYPE)
                    .senderId(senderId)
                    .senderName(extractSenderName(messageType, data))
                    .chatId(chatId)
                    .content(content != null ? content : "")
                    .contentType(determineContentType(contentParts))
                    .contentParts(contentParts)
                    .timestamp(LocalDateTime.now())
                    .replyToken(replyToken)
                    .rawPayload(data)
                    .build();

            onMessage(channelMessage);

        } catch (Exception e) {
            log.error("[qq] Failed to handle {} message: {}", messageType, e.getMessage(), e);
        }
    }

    /**
     * 提取发送者 ID（不同消息类型字段不同）
     */
    @SuppressWarnings("unchecked")
    private String extractSenderId(String messageType, Map<String, Object> data) {
        return switch (messageType) {
            case "c2c" -> {
                // C2C: author.user_openid 或 user_openid
                Map<String, Object> author = (Map<String, Object>) data.get("author");
                if (author != null && author.get("user_openid") != null) {
                    yield (String) author.get("user_openid");
                }
                yield (String) data.get("user_openid");
            }
            case "group" -> {
                // Group: author.member_openid 或 member_openid
                Map<String, Object> author = (Map<String, Object>) data.get("author");
                if (author != null && author.get("member_openid") != null) {
                    yield (String) author.get("member_openid");
                }
                yield (String) data.get("member_openid");
            }
            case "guild", "dm" -> {
                // Guild/DM: author.id
                Map<String, Object> author = (Map<String, Object>) data.get("author");
                yield author != null ? (String) author.get("id") : null;
            }
            default -> null;
        };
    }

    /**
     * 提取发送者名称
     */
    @SuppressWarnings("unchecked")
    private String extractSenderName(String messageType, Map<String, Object> data) {
        Map<String, Object> author = (Map<String, Object>) data.get("author");
        if (author == null) return null;
        String username = (String) author.get("username");
        return username != null ? username : (String) author.get("nickname");
    }

    /**
     * 构建回复 Token
     * <p>
     * 格式: messageType:targetId:originalMsgId
     * 发送回复时解析此 token 确定目标和回复的消息 ID
     */
    private String buildReplyToken(String messageType, String senderId,
                                   Map<String, Object> data, String messageId) {
        String targetId;
        switch (messageType) {
            case "c2c" -> targetId = senderId;
            case "group" -> targetId = (String) data.get("group_openid");
            case "guild" -> targetId = (String) data.get("channel_id");
            case "dm" -> targetId = (String) data.get("guild_id");
            default -> targetId = senderId;
        }
        return messageType + ":" + targetId + ":" + (messageId != null ? messageId : "");
    }

    private String determineContentType(List<MessageContentPart> parts) {
        for (MessageContentPart p : parts) {
            if (!"text".equals(p.getType())) return p.getType();
        }
        return "text";
    }

    // ==================== 心跳 ====================

    private void startHeartbeat(WebSocket ws, int intervalMs) {
        stopHeartbeat();
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qq-heartbeat-" + channelEntity.getId());
            t.setDaemon(true);
            return t;
        });

        heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                int seq = lastSeq.get();
                String hb = objectMapper.writeValueAsString(Map.of(
                        "op", OP_HEARTBEAT,
                        "d", seq > 0 ? seq : null
                ));
                ws.sendText(hb, true);
                log.trace("[qq] Heartbeat sent (seq={})", seq);
            } catch (Exception e) {
                log.warn("[qq] Failed to send heartbeat: {}", e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }

    // ==================== 消息发送 ====================

    @Override
    public void sendMessage(String targetId, String content) {
        if (httpClient == null) {
            log.warn("[qq] Channel not started, cannot send message");
            return;
        }

        // 解析 replyToken 格式: messageType:target:originalMsgId
        String[] parts = targetId.split(":", 3);
        if (parts.length < 2) {
            log.warn("[qq] Invalid replyToken format: {}", targetId);
            return;
        }

        String messageType = parts[0];
        String target = parts[1];
        String originalMsgId = parts.length > 2 ? parts[2] : null;

        // 提取图片 URL（[Image: URL] 标签）
        List<String> imageUrls = new ArrayList<>();
        var matcher = IMAGE_TAG_PATTERN.matcher(content);
        while (matcher.find()) {
            imageUrls.add(matcher.group(1));
        }
        String textContent = IMAGE_TAG_PATTERN.matcher(content).replaceAll("").trim();

        // 发送文本
        if (!textContent.isBlank()) {
            sendTextWithFallback(messageType, target, textContent, originalMsgId);
        }

        // 发送图片
        for (String imageUrl : imageUrls) {
            sendImage(messageType, target, imageUrl, originalMsgId);
        }
    }

    /**
     * 发送文本消息（带 Markdown 降级和 URL 过滤回退）
     */
    private void sendTextWithFallback(String messageType, String target,
                                      String text, String originalMsgId) {
        try {
            // 尝试 Markdown 或纯文本
            if (markdownEnabled && !"guild".equals(messageType) && !"dm".equals(messageType)) {
                try {
                    dispatchText(messageType, target, text, originalMsgId, true);
                    return;
                } catch (Exception e) {
                    log.debug("[qq] Markdown send failed, falling back to plain text: {}", e.getMessage());
                }
            }

            // 纯文本
            try {
                dispatchText(messageType, target, text, originalMsgId, false);
            } catch (Exception e) {
                // URL 过滤后重试
                String sanitized = sanitizeQQText(text);
                if (!sanitized.equals(text) && !sanitized.isBlank()) {
                    log.debug("[qq] Retrying with URL-sanitized text");
                    dispatchText(messageType, target, sanitized, originalMsgId, false);
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            log.error("[qq] Failed to send text (type={}, target={}): {}", messageType, target, e.getMessage());
        }
    }

    /**
     * 根据消息类型分派文本消息到对应 API
     */
    private void dispatchText(String messageType, String target, String text,
                              String originalMsgId, boolean markdown) throws Exception {
        String token = getAccessToken();
        long seq = msgSeqCounter.getAndIncrement();

        Map<String, Object> body = new LinkedHashMap<>();
        if (markdown) {
            body.put("markdown", Map.of("content", text));
            body.put("msg_type", 2);
        } else {
            body.put("content", text);
            body.put("msg_type", 0);
        }
        body.put("msg_seq", seq);
        if (originalMsgId != null && !originalMsgId.isBlank()) {
            body.put("msg_id", originalMsgId);
        }

        String apiUrl = switch (messageType) {
            case "c2c" -> DEFAULT_API_BASE + "/v2/users/" + target + "/messages";
            case "group" -> DEFAULT_API_BASE + "/v2/groups/" + target + "/messages";
            case "guild" -> DEFAULT_API_BASE + "/channels/" + target + "/messages";
            case "dm" -> DEFAULT_API_BASE + "/dms/" + target + "/messages";
            default -> throw new IllegalArgumentException("Unknown message type: " + messageType);
        };

        String jsonBody = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Authorization", "QQBot " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Send message failed: status=" + response.statusCode() + ", body=" + response.body());
        }
    }

    /**
     * 发送图片（通过富媒体上传 API）
     */
    private void sendImage(String messageType, String target, String imageUrl, String originalMsgId) {
        // Guild/DM 不支持富媒体 API，跳过
        if ("guild".equals(messageType) || "dm".equals(messageType)) {
            log.debug("[qq] Rich media not supported for {}, skipping image", messageType);
            return;
        }

        try {
            String token = getAccessToken();
            long seq = msgSeqCounter.getAndIncrement();

            // Step 1: 上传文件获取 file_info
            String uploadUrl = switch (messageType) {
                case "c2c" -> DEFAULT_API_BASE + "/v2/users/" + target + "/files";
                case "group" -> DEFAULT_API_BASE + "/v2/groups/" + target + "/files";
                default -> throw new IllegalArgumentException("Unsupported media type: " + messageType);
            };

            Map<String, Object> uploadBody = Map.of(
                    "file_type", 1,   // 1=图片
                    "url", imageUrl,
                    "srv_send_msg", false
            );

            String uploadJson = objectMapper.writeValueAsString(uploadBody);
            HttpRequest uploadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .header("Authorization", "QQBot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(uploadJson))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
            if (uploadResponse.statusCode() != 200) {
                log.warn("[qq] Image upload failed: status={}, body={}", uploadResponse.statusCode(), uploadResponse.body());
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> uploadResult = objectMapper.readValue(uploadResponse.body(), Map.class);
            String fileInfo = (String) uploadResult.get("file_info");
            if (fileInfo == null || fileInfo.isBlank()) {
                log.warn("[qq] No file_info in upload response");
                return;
            }

            // Step 2: 发送富媒体消息
            String sendUrl = switch (messageType) {
                case "c2c" -> DEFAULT_API_BASE + "/v2/users/" + target + "/messages";
                case "group" -> DEFAULT_API_BASE + "/v2/groups/" + target + "/messages";
                default -> throw new IllegalArgumentException("Unsupported: " + messageType);
            };

            Map<String, Object> sendBody = new LinkedHashMap<>();
            sendBody.put("msg_type", 7);
            sendBody.put("media", Map.of("file_info", fileInfo));
            sendBody.put("msg_seq", seq);
            if (originalMsgId != null && !originalMsgId.isBlank()) {
                sendBody.put("msg_id", originalMsgId);
            }

            String sendJson = objectMapper.writeValueAsString(sendBody);
            HttpRequest sendRequest = HttpRequest.newBuilder()
                    .uri(URI.create(sendUrl))
                    .header("Authorization", "QQBot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(sendJson))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> sendResponse = httpClient.send(sendRequest, HttpResponse.BodyHandlers.ofString());
            if (sendResponse.statusCode() != 200) {
                log.warn("[qq] Image send failed: status={}, body={}", sendResponse.statusCode(), sendResponse.body());
            }

        } catch (Exception e) {
            log.error("[qq] Failed to send image: {}", e.getMessage(), e);
        }
    }

    /**
     * 过滤 QQ 不允许的 URL
     */
    private String sanitizeQQText(String text) {
        return URL_PATTERN.matcher(text).replaceAll("[链接已过滤]");
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

    // ==================== 工具方法 ====================

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
