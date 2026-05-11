package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import lombok.extern.slf4j.Slf4j;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.ExponentialBackoff;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 飞书渠道适配器
 * <p>
 * 飞书渠道实现：
 * - 接入模式：WebSocket 长连接（默认，无需公网 IP）或 Event Subscription（HTTP 回调）
 * - 发送方式：通过 Open API 发送消息
 * - 消息去重：基于 message_id 防止重复处理
 * - 引用消息：自动拉取 parent_id 对应的父消息内容并注入上下文
 * <p>
 * 配置项（configJson）：
 * - app_id: 飞书应用 App ID
 * - app_secret: 飞书应用 App Secret
 * - connection_mode: 接入模式 "websocket"（默认）或 "webhook"
 * - domain: "feishu"（默认）或 "lark"（国际版）
 * - encrypt_key: 事件加密密钥（webhook 模式必填）
 * - verification_token: 事件验证 Token（webhook 模式可选）
 * - enable_reaction: 是否在收到消息后添加表情反应（默认 true）
 * - enable_nickname_cache: 是否通过 Contact API 获取用户昵称（默认 true）
 * - media_download_enabled: 是否下载消息中的媒体文件（默认 false）
 * - enable_quoted_context: 是否拉取被引用消息内容注入到 prompt（默认 true）
 * - silent_disconnect_threshold_seconds: WebSocket 静默断连阈值（默认 1800，0 禁用）
 * - stale_event_threshold_seconds: 过滤旧事件阈值（默认 30，0 禁用）
 *
 * @author MateClaw Team
 */
@Slf4j
public class FeishuChannelAdapter extends AbstractChannelAdapter {

    public static final String CHANNEL_TYPE = "feishu";

    private HttpClient httpClient;
    private String tenantAccessToken;
    private long tokenExpireTime;

    /** 定时 Token 刷新任务 */
    private ScheduledFuture<?> tokenRefreshFuture;

    /** 消息去重：最近处理过的 message_id */
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    /** 昵称缓存：open_id → 显示名称 */
    private final ConcurrentHashMap<String, String> nicknameCache = new ConcurrentHashMap<>();
    private static final int NICKNAME_CACHE_MAX = 500;

    /** WebSocket 客户端（websocket 模式） */
    private volatile com.lark.oapi.ws.Client wsClient;

    /** WebSocket 连接线程 */
    private volatile Thread wsThread;

    /** WebSocket 静默断连看门狗：定期检查最近事件时间，超过阈值就强制重连 */
    private ScheduledFuture<?> silentDisconnectWatchdog;

    /** 是否已收到至少一个事件（用于避免新连接立即触发静默超时） */
    private volatile boolean hasReceivedFirstEvent = false;

    /** 看门狗检查间隔（秒） */
    private static final long WATCHDOG_INTERVAL_SECONDS = 60L;

    /** 静默断连默认阈值（秒）：30 分钟无事件就视为可疑 */
    private static final long DEFAULT_SILENT_THRESHOLD_SECONDS = 1800L;

    /** 旧事件过滤默认阈值（秒）：超过 30 秒的事件视为重连后回放 */
    private static final long DEFAULT_STALE_THRESHOLD_SECONDS = 30L;

    public FeishuChannelAdapter(ChannelEntity channelEntity,
                                ChannelMessageRouter messageRouter,
                                ObjectMapper objectMapper) {
        super(channelEntity, messageRouter, objectMapper);
        // 飞书 WebSocket 重连：2s→4s→8s→16s→30s，无限重试
        this.backoff = new ExponentialBackoff(2000, 30000, 2.0, -1);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void doStart() {
        String appId = getConfigString("app_id");
        String appSecret = getConfigString("app_secret");

        if (appId == null || appSecret == null) {
            throw new IllegalStateException("Feishu channel requires app_id and app_secret in configJson");
        }

        // HttpClient 两种模式都需要（发送消息、下载媒体、联系人 API）
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 获取初始 tenant_access_token
        refreshTenantAccessToken();

        // 定时刷新 Token：过期前 5 分钟自动刷新
        scheduleTokenRefresh();

        String connectionMode = getConfigString("connection_mode", "websocket");
        if ("websocket".equals(connectionMode)) {
            startWebSocket(appId, appSecret);
        } else {
            // webhook 模式下 encrypt_key 必须配置，否则 fail-fast 拒绝启动；
            // 没有加密密钥 + 无签名校验 = 任何人可伪造 webhook 请求触发 agent 消息
            String encryptKey = getConfigString("encrypt_key", null);
            if (encryptKey == null || encryptKey.isBlank()) {
                throw new IllegalStateException(
                        "Feishu channel in webhook mode requires encrypt_key in configJson " +
                        "(fail-closed to prevent unauthenticated webhook abuse). " +
                        "Configure encrypt_key on the Feishu Event Subscriptions page and mirror " +
                        "it in this channel's configJson, or switch connection_mode to websocket.");
            }
            log.info("[feishu] Webhook mode (encrypt_key configured), waiting for callbacks at /api/v1/channels/webhook/feishu");
        }

        log.info("[feishu] Feishu channel initialized: appId={}, mode={}, domain={}",
                appId, connectionMode, getConfigString("domain", "feishu"));
    }

    @Override
    protected void doStop() {
        // 取消定时 Token 刷新
        if (tokenRefreshFuture != null) {
            tokenRefreshFuture.cancel(false);
            tokenRefreshFuture = null;
        }

        // 关闭 WebSocket
        stopWebSocket();

        this.httpClient = null;
        this.tenantAccessToken = null;
        this.processedMessageIds.clear();
        this.nicknameCache.clear();
        this.quotedMessageCache.clear();
        log.info("[feishu] Feishu channel stopped");
    }

    @Override
    protected void doReconnect() {
        String appId = getConfigString("app_id");
        String appSecret = getConfigString("app_secret");
        String connectionMode = getConfigString("connection_mode", "websocket");

        // 重新建立 HTTP 客户端
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            refreshTenantAccessToken();
        } catch (Exception e) {
            log.warn("[feishu] Token refresh during reconnect failed: {}", e.getMessage());
        }

        if ("websocket".equals(connectionMode)) {
            log.info("[feishu] Reconnecting WebSocket...");
            stopWebSocket();
            // 同步连接：在当前重连线程中直接阻塞调用 start()
            // 连接成功 start() 会一直阻塞（不会返回到这里）
            // 连接失败 start() 抛异常，由 AbstractChannelAdapter.scheduleReconnect 捕获并触发 onReconnectFailed
            startWebSocketSync(appId, appSecret);
        }

        log.info("[feishu] Reconnect completed for: {}", channelEntity.getName());
    }

    // ==================== WebSocket 长连接 ====================

    /**
     * 创建 WebSocket 客户端实例（不启动连接）
     */
    private com.lark.oapi.ws.Client createWsClient(String appId, String appSecret) {
        EventDispatcher eventDispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        if (!running.get()) return;

                        // 检查 app_id 匹配（防止多实例事件错路由）
                        if (event.getHeader() != null && event.getHeader().getAppId() != null
                                && !event.getHeader().getAppId().equals(appId)) {
                            log.debug("[feishu] Dropping misrouted event, app_id={} (expected {})",
                                    event.getHeader().getAppId(), appId);
                            return;
                        }

                        try {
                            handleWebSocketEvent(event);
                        } catch (Exception e) {
                            log.error("[feishu] Failed to handle WebSocket event: {}", e.getMessage(), e);
                        }
                    }
                })
                .build();

        return new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                .eventHandler(eventDispatcher)
                .autoReconnect(false)  // 由我们的 ExponentialBackoff 控制重连，不用 SDK 内置重连
                .domain("lark".equals(getConfigString("domain", "feishu"))
                        ? "https://open.larksuite.com"
                        : "https://open.feishu.cn")
                .build();
    }

    /**
     * 启动 WebSocket 长连接（异步，用于 doStart 首次启动）
     * 在守护线程中运行，避免阻塞主线程。连接失败时触发 onDisconnected → 退避重连
     */
    private void startWebSocket(String appId, String appSecret) {
        wsClient = createWsClient(appId, appSecret);

        wsThread = new Thread(() -> {
            try {
                log.info("[feishu] WebSocket connecting (long connection)...");
                wsClient.start();
                // start() blocks until disconnect; if it returns normally, it means disconnected
                if (running.get()) {
                    onDisconnected("WebSocket connection ended");
                }
            } catch (Exception e) {
                log.error("[feishu] WebSocket error: {}", e.getMessage(), e);
                if (running.get()) {
                    onDisconnected("WebSocket error: " + e.getMessage());
                }
            }
        }, "feishu-ws-" + channelEntity.getId());
        wsThread.setDaemon(true);
        wsThread.start();

        startSilentDisconnectWatchdog();
    }

    /**
     * 启动静默断连看门狗。
     * <p>
     * SDK 内部有 ping/pong 心跳，正常情况下连接断开会触发 start() 返回 → onDisconnected。
     * 但少数场景 TCP 层认为连接还在但事件不再流入（NAT 超时、半开连接、对端 hang 死），
     * SDK 也察觉不到。看门狗每 60s 检查一次「最近事件时间」，超过阈值就强制重连。
     * <p>
     * 静默判定有两个前置条件：
     * 1. 已经收到过至少一个事件（避免新连接立即触发误报）
     * 2. silent_disconnect_threshold_seconds &gt; 0（设为 0 可禁用看门狗）
     * <p>
     * 默认阈值 30 分钟。安静的渠道（一天没几条消息）建议调大到 1-2 小时；
     * 高频渠道可以调小到 5-10 分钟以更快发现问题。
     */
    private void startSilentDisconnectWatchdog() {
        long thresholdSec = getConfigLong("silent_disconnect_threshold_seconds",
                DEFAULT_SILENT_THRESHOLD_SECONDS);
        if (thresholdSec <= 0) {
            log.debug("[feishu] Silent disconnect watchdog disabled (threshold=0)");
            return;
        }
        long thresholdMs = thresholdSec * 1000L;

        cancelSilentDisconnectWatchdog();
        silentDisconnectWatchdog = ensureReconnectScheduler().scheduleAtFixedRate(() -> {
            if (!running.get() || wsClient == null) return;
            if (!hasReceivedFirstEvent) return; // 没收到首个事件前不算静默

            long silentMs = System.currentTimeMillis() - lastEventTimeMs.get();
            if (silentMs > thresholdMs) {
                log.warn("[feishu] Silent WebSocket detected: no events for {}s (threshold {}s), forcing reconnect",
                        silentMs / 1000, thresholdSec);
                onDisconnected("silent disconnect: no events for " + (silentMs / 1000) + "s");
            }
        }, WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("[feishu] Silent disconnect watchdog started (threshold {}s, check every {}s)",
                thresholdSec, WATCHDOG_INTERVAL_SECONDS);
    }

    private void cancelSilentDisconnectWatchdog() {
        if (silentDisconnectWatchdog != null) {
            silentDisconnectWatchdog.cancel(false);
            silentDisconnectWatchdog = null;
        }
    }

    /**
     * 启动 WebSocket 长连接（同步，用于 doReconnect 重连线程）
     * 在当前线程中阻塞调用 start()：
     * - 连接成功后 start() 会一直阻塞（收消息），不会返回
     * - 连接失败 start() 抛异常，由 scheduleReconnect 的 catch 捕获 → onReconnectFailed → 退避递增
     */
    private void startWebSocketSync(String appId, String appSecret) {
        wsClient = createWsClient(appId, appSecret);
        log.info("[feishu] WebSocket connecting (long connection)...");
        // 看门狗必须在 start() 之前启动，否则 start() 阻塞后这一行永远到不了，
        // 一旦断连后重连这条路径，watchdog 就再也不会被恢复
        startSilentDisconnectWatchdog();
        wsClient.start(); // 阻塞：成功则永驻，失败则抛异常
    }

    /**
     * 关闭 WebSocket 连接
     * SDK 的 start() 在线程中阻塞运行，通过中断线程来触发停止
     */
    private void stopWebSocket() {
        cancelSilentDisconnectWatchdog();
        hasReceivedFirstEvent = false;
        if (wsThread != null) {
            wsThread.interrupt();
            wsThread = null;
        }
        wsClient = null;
    }

    /**
     * 处理 WebSocket 事件：从 P2MessageReceiveV1 提取字段，调用统一入口
     */
    private void handleWebSocketEvent(P2MessageReceiveV1 event) {
        var eventBody = event.getEvent();
        if (eventBody == null || eventBody.getMessage() == null) {
            return;
        }

        var message = eventBody.getMessage();
        var sender = eventBody.getSender();

        // 任何事件到达都更新活跃时间戳，看门狗用它判断是否静默断连
        touchActivity();
        hasReceivedFirstEvent = true;

        // 旧事件过滤：SDK 在重连后可能回放历史事件，按 message.create_time 过滤
        // 远超 stale 阈值的消息（典型场景：连接断了 5 分钟后恢复，5 分钟前的消息再处理一次没意义）
        long staleThresholdMs = getConfigLong("stale_event_threshold_seconds",
                DEFAULT_STALE_THRESHOLD_SECONDS) * 1000L;
        if (staleThresholdMs > 0 && message.getCreateTime() != null) {
            try {
                long msgCreateTimeMs = Long.parseLong(message.getCreateTime());
                long ageMs = System.currentTimeMillis() - msgCreateTimeMs;
                if (ageMs > staleThresholdMs) {
                    log.info("[feishu] Dropping stale event: messageId={}, age={}s (threshold {}s)",
                            message.getMessageId(), ageMs / 1000, staleThresholdMs / 1000);
                    return;
                }
            } catch (NumberFormatException ignored) {
                // create_time 不是合法的毫秒数，跳过过滤而不是误丢
            }
        }

        String messageId = message.getMessageId();
        String messageType = message.getMessageType();
        String contentStr = message.getContent();
        String chatId = message.getChatId();
        String chatType = message.getChatType();
        String parentId = message.getParentId();

        String senderOpenId = null;
        if (sender != null && sender.getSenderId() != null) {
            senderOpenId = sender.getSenderId().getOpenId();
        }

        handleFeishuMessage(messageId, messageType, contentStr, chatId, chatType, senderOpenId, parentId, event);
    }

    // ==================== Token 管理 ====================

    /**
     * 定时刷新 Token：每隔 (expireSeconds - 300) 秒刷新一次
     */
    private void scheduleTokenRefresh() {
        // Token 默认有效期 7200s，提前 5 分钟刷新 => 周期 6900s
        long refreshIntervalSeconds = Math.max(300, 7200 - 300);
        tokenRefreshFuture = ensureReconnectScheduler().scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            try {
                refreshTenantAccessToken();
                log.debug("[feishu] Scheduled token refresh succeeded");
            } catch (Exception e) {
                log.warn("[feishu] Scheduled token refresh failed: {}, will retry on next interval",
                        e.getMessage());
                lastError = "Token refresh failed: " + e.getMessage();
            }
        }, refreshIntervalSeconds, refreshIntervalSeconds, TimeUnit.SECONDS);
        log.info("[feishu] Token auto-refresh scheduled every {}s", refreshIntervalSeconds);
    }

    /**
     * 获取/刷新 tenant_access_token
     */
    private void refreshTenantAccessToken() {
        String appId = getConfigString("app_id");
        String appSecret = getConfigString("app_secret");
        String apiBase = getApiBaseUrl();

        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "app_id", appId,
                    "app_secret", appSecret
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/open-apis/auth/v3/tenant_access_token/internal"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            Integer code = result.get("code") instanceof Number n ? n.intValue() : null;
            if (code != null && code != 0) {
                throw new RuntimeException("Feishu API error: code=" + code + ", msg=" + result.get("msg"));
            }

            this.tenantAccessToken = (String) result.get("tenant_access_token");
            Object expire = result.get("expire");
            int expireSeconds = expire instanceof Number n ? n.intValue() : 7200;
            this.tokenExpireTime = System.currentTimeMillis() + (expireSeconds - 300) * 1000L;

            log.info("[feishu] tenant_access_token refreshed, expires in {}s", expireSeconds);

        } catch (Exception e) {
            log.error("[feishu] Failed to refresh tenant_access_token: {}", e.getMessage(), e);
            throw new RuntimeException("Token refresh failed: " + e.getMessage(), e);
        }
    }

    private void ensureTokenValid() {
        if (tenantAccessToken == null || System.currentTimeMillis() >= tokenExpireTime) {
            try {
                refreshTenantAccessToken();
            } catch (Exception e) {
                log.warn("[feishu] On-demand token refresh failed: {}", e.getMessage());
            }
        }
    }

    // ==================== Domain 国际化 ====================

    /**
     * 获取 API 基础 URL
     * domain=feishu → https://open.feishu.cn
     * domain=lark  → https://open.larksuite.com
     */
    private String getApiBaseUrl() {
        String domain = getConfigString("domain", "feishu");
        return "lark".equals(domain)
                ? "https://open.larksuite.com"
                : "https://open.feishu.cn";
    }

    // ==================== Webhook 处理 ====================

    /**
     * 处理飞书 Event Subscription 回调
     * 由 ChannelWebhookController 调用
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleWebhook(Map<String, Object> payload) {
        // 处理 URL 验证请求
        String type = (String) payload.get("type");
        if ("url_verification".equals(type)) {
            String challenge = (String) payload.get("challenge");
            log.info("[feishu] URL verification challenge received");
            return Map.of("challenge", challenge != null ? challenge : "");
        }

        try {
            // 解析 v2 事件格式
            Map<String, Object> header = (Map<String, Object>) payload.get("header");
            Map<String, Object> event = (Map<String, Object>) payload.get("event");

            if (header == null || event == null) {
                log.warn("[feishu] Invalid event payload: missing header or event");
                return Map.of("code", 0);
            }

            String eventType = (String) header.get("event_type");
            if (!"im.message.receive_v1".equals(eventType)) {
                log.debug("[feishu] Ignoring event type: {}", eventType);
                return Map.of("code", 0);
            }

            // 解析消息
            Map<String, Object> message = (Map<String, Object>) event.get("message");
            if (message == null) {
                return Map.of("code", 0);
            }

            String messageId = (String) message.get("message_id");
            String messageType = (String) message.get("message_type");
            String contentStr = (String) message.get("content");
            String chatId = (String) message.get("chat_id");
            String chatType = (String) message.get("chat_type");
            String parentId = (String) message.get("parent_id");

            // 提取发送者 open_id
            Map<String, Object> sender = (Map<String, Object>) event.get("sender");
            String senderOpenId = null;
            if (sender != null) {
                Map<String, Object> senderIdObj = (Map<String, Object>) sender.get("sender_id");
                if (senderIdObj != null) {
                    senderOpenId = (String) senderIdObj.get("open_id");
                }
            }

            handleFeishuMessage(messageId, messageType, contentStr, chatId, chatType, senderOpenId, parentId, payload);

        } catch (Exception e) {
            log.error("[feishu] Failed to handle webhook: {}", e.getMessage(), e);
        }

        return Map.of("code", 0);
    }

    // ==================== 统一消息处理入口 ====================

    /**
     * 统一消息处理入口（Webhook 和 WebSocket 共用）
     *
     * @param messageId    消息 ID
     * @param messageType  消息类型（text/image/post/file/audio/media）
     * @param contentStr   消息内容 JSON 字符串
     * @param chatId       群组 ID（私聊为 null）
     * @param chatType     "p2p" 或 "group"
     * @param senderOpenId 发送者 open_id
     * @param parentId     被引用消息的 message_id（无引用时为 null）
     * @param rawPayload   原始负载（用于调试）
     */
    private void handleFeishuMessage(String messageId, String messageType, String contentStr,
                                      String chatId, String chatType, String senderOpenId,
                                      String parentId, Object rawPayload) {
        // 消息去重
        if (messageId != null && !processedMessageIds.add(messageId)) {
            log.debug("[feishu] Duplicate message_id: {}, skipping", messageId);
            return;
        }
        cleanupProcessedIds();

        // 添加消息反应（非阻塞，表示"已收到"）
        if (messageId != null && getConfigBoolean("enable_reaction", true)) {
            addReactionAsync(messageId, "THUMBSUP");
        }

        // 获取用户昵称
        String senderName = senderOpenId;
        if (senderOpenId != null && getConfigBoolean("enable_nickname_cache", true)) {
            senderName = getUserName(senderOpenId);
        }

        // 解析消息内容
        List<MessageContentPart> contentParts = new ArrayList<>();
        String textContent = extractContentParts(messageId, messageType, contentStr, contentParts);

        if (contentParts.isEmpty() && (textContent == null || textContent.isBlank())) {
            log.debug("[feishu] Empty message content, ignoring");
            return;
        }

        // 引用消息（用户在飞书里"引用"了之前的某条消息回复）：拉取被引用消息内容并注入上下文，
        // 让 agent 能理解 "解释一下" 这种缺主语的引用回复 —— 不然就只看到"解释一下"三个字。
        if (parentId != null && !parentId.isBlank() && getConfigBoolean("enable_quoted_context", true)) {
            String quotedText = fetchQuotedMessageText(parentId);
            if (quotedText != null && !quotedText.isBlank()) {
                String prefix = "[引用消息: " + quotedText + "]\n";
                textContent = prefix + (textContent != null ? textContent : "");
                // 同步加一个 text part 到最前面，让多模态消息也能看到引用上下文
                contentParts.add(0, MessageContentPart.text(prefix));
            }
        }

        // 生成短会话后缀
        boolean isGroup = "group".equals(chatType);
        String shortSuffix = generateShortSessionSuffix(chatId, senderOpenId, isGroup);

        ChannelMessage channelMessage = ChannelMessage.builder()
                .messageId(messageId)
                .channelType(CHANNEL_TYPE)
                .senderId(senderOpenId)
                .senderName(senderName)
                .chatId(isGroup ? shortSuffix : null)
                .content(textContent != null ? textContent : "")
                .contentType(messageType)
                .contentParts(contentParts)
                .inputMode("audio".equals(messageType) ? "voice" : "text")
                .timestamp(LocalDateTime.now())
                .rawPayload(rawPayload)
                .build();

        // replyToken ��留完整 chatId（��送消息需要完整 ID）
        channelMessage.setReplyToken(chatId);
        onMessage(channelMessage);
    }

    /**
     * 清理旧的去重记录：超过 1000 条时保留最近添加的（移除最早的一半）
     */
    private void cleanupProcessedIds() {
        if (processedMessageIds.size() > 1000) {
            int toRemove = processedMessageIds.size() / 2;
            var iterator = processedMessageIds.iterator();
            while (iterator.hasNext() && toRemove > 0) {
                iterator.next();
                iterator.remove();
                toRemove--;
            }
        }
    }

    // ==================== 消息反应 ====================

    /**
     * 非阻塞地给消息添加表情反应
     * 在新线程中执行，失败只 log.debug 不影响主流程
     */
    private void addReactionAsync(String messageId, String emojiType) {
        Thread reactionThread = new Thread(() -> {
            try {
                ensureTokenValid();
                String apiBase = getApiBaseUrl();
                String jsonBody = objectMapper.writeValueAsString(Map.of(
                        "reaction_type", Map.of("emoji_type", emojiType)
                ));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiBase + "/open-apis/im/v1/messages/" + messageId + "/reactions"))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("Authorization", "Bearer " + tenantAccessToken)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.debug("[feishu] Add reaction failed: status={}, body={}", response.statusCode(), response.body());
                } else {
                    log.debug("[feishu] Reaction {} added to message {}", emojiType, messageId);
                }
            } catch (Exception e) {
                log.debug("[feishu] Add reaction error: {}", e.getMessage());
            }
        }, "feishu-reaction");
        reactionThread.setDaemon(true);
        reactionThread.start();
    }

    // ==================== 联系人昵称 ====================

    /**
     * 通过 open_id 获取用户昵称
     * 优先查缓存 → 调用 Contact API → 降级返回 open_id 后缀
     */
    @SuppressWarnings("unchecked")
    private String getUserName(String openId) {
        if (openId == null || openId.isBlank()) return openId;

        // 1. 查缓存
        String cached = nicknameCache.get(openId);
        if (cached != null) return cached;

        // 2. 调用 Contact API
        try {
            ensureTokenValid();
            String apiBase = getApiBaseUrl();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/open-apis/contact/v3/users/" + openId + "?user_id_type=open_id"))
                    .header("Authorization", "Bearer " + tenantAccessToken)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                Integer code = result.get("code") instanceof Number n ? n.intValue() : null;
                if (code != null && code == 0) {
                    Map<String, Object> data = (Map<String, Object>) result.get("data");
                    if (data != null) {
                        Map<String, Object> user = (Map<String, Object>) data.get("user");
                        if (user != null) {
                            String name = firstNonBlank(
                                    (String) user.get("name"),
                                    (String) user.get("en_name")
                            );
                            if (name != null) {
                                // 缓存超限时清理最早的一半
                                if (nicknameCache.size() >= NICKNAME_CACHE_MAX) {
                                    int toRemove = nicknameCache.size() / 2;
                                    var iterator = nicknameCache.keySet().iterator();
                                    while (iterator.hasNext() && toRemove > 0) {
                                        iterator.next();
                                        iterator.remove();
                                        toRemove--;
                                    }
                                }
                                nicknameCache.put(openId, name);
                                return name;
                            }
                        }
                    }
                } else {
                    log.debug("[feishu] Contact API error for {}: code={}", openId, code);
                }
            }
        } catch (Exception e) {
            log.debug("[feishu] getUserName failed for {}: {}", openId, e.getMessage());
        }

        // 3. 降级：返回 open_id 后 6 位
        String fallback = openId.length() > 6 ? openId.substring(openId.length() - 6) : openId;
        return fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    // ==================== 引用消息上下文 ====================

    /** 引用消息内容缓存：parent_message_id → 文本摘要（避免同一条引用反复拉 API） */
    private final ConcurrentHashMap<String, String> quotedMessageCache = new ConcurrentHashMap<>();
    private static final int QUOTED_CACHE_MAX = 200;

    /**
     * 拉取被引用消息的文本摘要。
     * <p>
     * GET /open-apis/im/v1/messages/{message_id} 返回 items[0]，body.content 是一段 JSON 字符串，
     * 形如 {"text": "..."} 或 post 富文本。我们只取一个简短文本表示，给 agent 当上下文用，
     * 不还原完整富文本/媒体（成本高且 prompt 容易冗余）。
     */
    @SuppressWarnings("unchecked")
    private String fetchQuotedMessageText(String parentMessageId) {
        String cached = quotedMessageCache.get(parentMessageId);
        if (cached != null) return cached;

        try {
            ensureTokenValid();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getApiBaseUrl() + "/open-apis/im/v1/messages/" + parentMessageId))
                    .header("Authorization", "Bearer " + tenantAccessToken)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("[feishu] Fetch quoted message failed: status={}", response.statusCode());
                return null;
            }
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            Integer code = result.get("code") instanceof Number n ? n.intValue() : null;
            if (code == null || code != 0) {
                log.debug("[feishu] Fetch quoted message API error: code={}, msg={}", code, result.get("msg"));
                return null;
            }
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data == null) return null;
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
            if (items == null || items.isEmpty()) return null;
            Map<String, Object> item = items.get(0);
            String msgType = (String) item.get("msg_type");
            Map<String, Object> body = (Map<String, Object>) item.get("body");
            if (body == null) return null;
            String contentJson = (String) body.get("content");
            String summary = summarizeQuotedContent(msgType, contentJson);

            if (summary != null && !summary.isBlank()) {
                if (quotedMessageCache.size() >= QUOTED_CACHE_MAX) {
                    int toRemove = quotedMessageCache.size() / 2;
                    var iter = quotedMessageCache.keySet().iterator();
                    while (iter.hasNext() && toRemove > 0) {
                        iter.next(); iter.remove(); toRemove--;
                    }
                }
                quotedMessageCache.put(parentMessageId, summary);
            }
            return summary;
        } catch (Exception e) {
            log.debug("[feishu] fetchQuotedMessageText failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把引用消息的 body.content 折叠成一个简短文本表示，控制 prompt 注入大小。
     * 长文本截断到 200 字符以内，post 取段落首文本，图片/文件给类型占位。
     */
    @SuppressWarnings("unchecked")
    private String summarizeQuotedContent(String msgType, String contentJson) {
        if (contentJson == null) return null;
        try {
            Map<String, Object> obj = objectMapper.readValue(contentJson, Map.class);
            String text = switch (msgType != null ? msgType : "") {
                case "text" -> (String) obj.get("text");
                case "image" -> "[图片]";
                case "file" -> "[文件: " + obj.getOrDefault("file_name", "") + "]";
                case "audio" -> "[音频]";
                case "media" -> "[视频]";
                case "post" -> extractPostFirstText(obj);
                default -> "[" + msgType + "]";
            };
            if (text == null) return null;
            text = text.trim();
            if (text.length() > 200) text = text.substring(0, 200) + "…";
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractPostFirstText(Map<String, Object> postObj) {
        // post 结构：{"zh_cn": {"title": "...", "content": [[{tag:text, text:"..."}], ...]}}
        // 我们只取第一个语种的 title + 第一段的首个 text 元素，作为引用摘要
        for (Object langValue : postObj.values()) {
            if (!(langValue instanceof Map<?, ?> lang)) continue;
            String title = (String) ((Map<String, Object>) lang).get("title");
            List<List<Map<String, Object>>> content = (List<List<Map<String, Object>>>) ((Map<String, Object>) lang).get("content");
            StringBuilder sb = new StringBuilder();
            if (title != null && !title.isBlank()) sb.append(title).append("：");
            if (content != null) {
                outer:
                for (var paragraph : content) {
                    for (var inline : paragraph) {
                        if ("text".equals(inline.get("tag"))) {
                            sb.append(inline.get("text"));
                            break outer;
                        }
                    }
                }
            }
            return sb.toString();
        }
        return null;
    }

    // ==================== 会话 ID 优化 ====================

    /**
     * 生成更短的会话标识后缀
     * - 群聊：app_id 后 4 位 + "_" + chat_id 后 8 位
     * - 私聊：open_id 后 12 位
     */
    private String generateShortSessionSuffix(String chatId, String openId, boolean isGroup) {
        if (isGroup && chatId != null) {
            String appId = getConfigString("app_id", "");
            String appSuffix = appId.length() >= 4 ? appId.substring(appId.length() - 4) : appId;
            String chatSuffix = chatId.length() >= 8 ? chatId.substring(chatId.length() - 8) : chatId;
            return appSuffix + "_" + chatSuffix;
        }
        if (openId != null) {
            return openId.length() >= 12 ? openId.substring(openId.length() - 12) : openId;
        }
        if (chatId != null) {
            return chatId.length() >= 12 ? chatId.substring(chatId.length() - 12) : chatId;
        }
        return null;
    }

    // ==================== 消息内容解析 ====================

    /**
     * 解析飞书消息内容为 contentParts
     *
     * @param messageId   消息 ID（用于媒体下载）
     * @param messageType 消息类型
     * @param contentStr  消息内容 JSON 字符串
     * @param parts       输出的 content parts
     * @return 纯文本摘要
     */
    @SuppressWarnings("unchecked")
    private String extractContentParts(String messageId, String messageType, String contentStr,
                                        List<MessageContentPart> parts) {
        if (contentStr == null) return null;

        try {
            Map<String, Object> contentObj = objectMapper.readValue(contentStr, Map.class);

            return switch (messageType) {
                case "text" -> {
                    String text = (String) contentObj.get("text");
                    if (text != null && !text.isBlank()) {
                        parts.add(MessageContentPart.text(text));
                    }
                    yield text;
                }
                case "post" -> {
                    yield parsePostContent(messageId, contentObj, parts);
                }
                case "image" -> {
                    String imageKey = (String) contentObj.get("image_key");
                    if (imageKey != null) {
                        // Images need bytes for vision: the Feishu CDN URL
                        // requires tenant_access_token, so a downstream vision
                        // tool that only sees image_key cannot fetch the
                        // image. Default-on for images (separate from the
                        // file/audio/video gate) so vision works out of the
                        // box; admins can opt out with feishu_image_download_enabled=false.
                        String localPath = maybeDownloadImage(messageId, imageKey);
                        MessageContentPart part = MessageContentPart.image(imageKey, null);
                        if (localPath != null) {
                            part.setPath(localPath);
                        }
                        parts.add(part);
                    }
                    yield "[图片]";
                }
                case "file" -> {
                    String fileKey = (String) contentObj.get("file_key");
                    String fileName = (String) contentObj.get("file_name");
                    if (fileKey != null) {
                        String localPath = maybeDownloadResource(messageId, fileKey, "file", fileName);
                        MessageContentPart part = MessageContentPart.file(fileKey, fileName, null);
                        if (localPath != null) part.setPath(localPath);
                        parts.add(part);
                    }
                    yield "[文件: " + (fileName != null ? fileName : "") + "]";
                }
                case "audio" -> {
                    String fileKey = (String) contentObj.get("file_key");
                    if (fileKey != null) {
                        String localPath = maybeDownloadResource(messageId, fileKey, "file", null);
                        MessageContentPart part = MessageContentPart.audio(fileKey, null);
                        if (localPath != null) part.setPath(localPath);
                        parts.add(part);
                    }
                    yield "[音频]";
                }
                case "media" -> {
                    String fileKey = (String) contentObj.get("file_key");
                    String fileName = (String) contentObj.get("file_name");
                    if (fileKey != null) {
                        String localPath = maybeDownloadResource(messageId, fileKey, "file", fileName);
                        MessageContentPart part = MessageContentPart.video(fileKey, fileName);
                        if (localPath != null) part.setPath(localPath);
                        parts.add(part);
                    }
                    yield "[视频]";
                }
                default -> {
                    parts.add(MessageContentPart.text("[" + messageType + " 消息]"));
                    yield "[" + messageType + " 消息暂不支持处理]";
                }
            };
        } catch (Exception e) {
            log.warn("[feishu] Failed to parse message content: {}", e.getMessage());
            parts.add(MessageContentPart.text(contentStr));
            return contentStr;
        }
    }

    // ==================== Post 富文本解析 ====================

    /**
     * 解析飞书 post 富文本消息
     * <p>
     * 飞书 post 结构：
     * <pre>
     * {
     *   "zh_cn": {
     *     "title": "标题",
     *     "content": [                   ← 段落数组
     *       [                            ← 每个段落是行内元素数组
     *         {"tag": "text", "text": "内容"},
     *         {"tag": "a", "text": "链接", "href": "url"},
     *         {"tag": "at", "user_name": "名字", "user_id": "id"},
     *         {"tag": "img", "image_key": "key"},
     *         {"tag": "media", "file_key": "key"},
     *         {"tag": "code_block", "text": "code"},
     *         {"tag": "md", "text": "markdown"}
     *       ]
     *     ]
     *   }
     * }
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private String parsePostContent(String messageId, Map<String, Object> contentObj,
                                     List<MessageContentPart> parts) {
        // 取 zh_cn 或第一个可用的 locale 分支
        Map<String, Object> localeBranch = (Map<String, Object>) contentObj.get("zh_cn");
        if (localeBranch == null) {
            localeBranch = (Map<String, Object>) contentObj.get("en_us");
        }
        if (localeBranch == null && !contentObj.isEmpty()) {
            // 取第一个 locale
            for (Object val : contentObj.values()) {
                if (val instanceof Map) {
                    localeBranch = (Map<String, Object>) val;
                    break;
                }
            }
        }
        if (localeBranch == null) {
            return null;
        }

        StringBuilder text = new StringBuilder();

        // 标题
        String title = (String) localeBranch.get("title");
        if (title != null && !title.isBlank()) {
            text.append(title).append("\n");
        }

        // 段落内容
        List<List<Map<String, Object>>> paragraphs =
                (List<List<Map<String, Object>>>) localeBranch.get("content");
        if (paragraphs == null) return text.toString().trim();

        boolean mediaDownload = getConfigBoolean("media_download_enabled", false);

        for (int i = 0; i < paragraphs.size(); i++) {
            List<Map<String, Object>> paragraph = paragraphs.get(i);
            if (paragraph == null) continue;

            for (Map<String, Object> element : paragraph) {
                String tag = (String) element.get("tag");
                if (tag == null) continue;

                switch (tag) {
                    case "text" -> {
                        String t = (String) element.get("text");
                        if (t != null) text.append(t);
                    }
                    case "code_block", "md" -> {
                        String t = (String) element.get("text");
                        if (t != null) text.append(t);
                    }
                    case "a" -> {
                        String linkText = (String) element.get("text");
                        String href = (String) element.get("href");
                        if (linkText != null && href != null) {
                            text.append("[").append(linkText).append("](").append(href).append(")");
                        } else if (linkText != null) {
                            text.append(linkText);
                        } else if (href != null) {
                            text.append(href);
                        }
                    }
                    case "at" -> {
                        String userName = (String) element.get("user_name");
                        String userId = (String) element.get("user_id");
                        if (userName != null && !userName.isBlank()) {
                            text.append("@").append(userName);
                        } else if (userId != null) {
                            text.append("@").append(userId);
                        }
                    }
                    case "img" -> {
                        String imageKey = (String) element.get("image_key");
                        if (imageKey != null) {
                            // Same reasoning as the standalone image case: vision
                            // pipelines need bytes; image_key alone is opaque.
                            String localPath = maybeDownloadImage(messageId, imageKey);
                            MessageContentPart imgPart = MessageContentPart.image(imageKey, null);
                            if (localPath != null) imgPart.setPath(localPath);
                            parts.add(imgPart);
                            text.append("[图片]");
                        }
                    }
                    case "media" -> {
                        String fileKey = (String) element.get("file_key");
                        if (fileKey != null) {
                            String localPath = mediaDownload ? maybeDownloadResource(messageId, fileKey, "file", null) : null;
                            MessageContentPart mediaPart = MessageContentPart.file(fileKey, null, null);
                            if (localPath != null) mediaPart.setPath(localPath);
                            parts.add(mediaPart);
                            text.append("[媒体]");
                        }
                    }
                    default -> {
                        String t = (String) element.get("text");
                        if (t != null) text.append(t);
                    }
                }
            }

            // 段落间用换行分隔
            if (i < paragraphs.size() - 1) {
                text.append("\n");
            }
        }

        String result = text.toString().trim();
        if (!result.isEmpty()) {
            parts.add(0, MessageContentPart.text(result));
        }
        return result.isEmpty() ? null : result;
    }

    // ==================== 媒体文件下载 ====================

    /**
     * 如果 media_download_enabled 则下载资源，否则返回 null
     */
    private String maybeDownloadResource(String messageId, String fileKey, String type, String fileNameHint) {
        if (!getConfigBoolean("media_download_enabled", false)) {
            return null;
        }
        return downloadResource(messageId, fileKey, type, fileNameHint);
    }

    /**
     * Image-specific download: default ON so the vision/STT pipeline
     * downstream actually has bytes to analyze. Without the local file,
     * vision providers see only an opaque {@code image_key} and the
     * Feishu CDN URL needs tenant_access_token to fetch — neither of
     * which the model can resolve. Admins who want to suppress image
     * downloads (e.g. tighter privacy, no disk usage) can set
     * {@code feishu_image_download_enabled=false} on the channel config.
     */
    private String maybeDownloadImage(String messageId, String imageKey) {
        if (!getConfigBoolean("feishu_image_download_enabled", true)) {
            return null;
        }
        return downloadResource(messageId, imageKey, "image", null);
    }

    /**
     * 下载飞书消息资源（图片/文件）到本地
     *
     * @param messageId    消息 ID
     * @param fileKey      资源 key（image_key 或 file_key）
     * @param type         资源类型："image" 或 "file"
     * @param fileNameHint 文件名提示（可选）
     * @return 本地文件路径，失败返回 null
     */
    private String downloadResource(String messageId, String fileKey, String type, String fileNameHint) {
        try {
            ensureTokenValid();
            String apiBase = getApiBaseUrl();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/open-apis/im/v1/messages/" + messageId
                            + "/resources/" + fileKey + "?type=" + type))
                    .header("Authorization", "Bearer " + tenantAccessToken)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                log.debug("[feishu] Download resource failed: status={}", response.statusCode());
                return null;
            }

            // 构建目标目录
            Path mediaDir = Path.of(System.getProperty("user.home"), ".mateclaw", "media", "feishu");
            Files.createDirectories(mediaDir);

            // 安全文件名
            String safeKey = fileKey.replaceAll("[^a-zA-Z0-9_]", "");
            if (safeKey.isEmpty()) safeKey = "file";

            // 推断扩展名
            String ext = "bin";
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.contains("jpeg") || contentType.contains("jpg")) ext = "jpg";
            else if (contentType.contains("png")) ext = "png";
            else if (contentType.contains("gif")) ext = "gif";
            else if (contentType.contains("webp")) ext = "webp";
            else if (contentType.contains("pdf")) ext = "pdf";
            else if (fileNameHint != null && fileNameHint.contains(".")) {
                ext = fileNameHint.substring(fileNameHint.lastIndexOf('.') + 1);
            }

            Path filePath = mediaDir.resolve(messageId + "_" + safeKey + "." + ext);

            try (InputStream is = response.body()) {
                Files.copy(is, filePath, StandardCopyOption.REPLACE_EXISTING);
            }

            log.debug("[feishu] Downloaded resource to: {}", filePath);
            return filePath.toAbsolutePath().toString();

        } catch (Exception e) {
            log.debug("[feishu] Download resource failed: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 消息发送 ====================

    /**
     * Conservative per-message char ceiling. Feishu's documented limit is on
     * the encoded JSON body (~150KB), but UTF-8 Chinese is 3 bytes/char and
     * JSON escape adds further overhead, so a 4000-char chunk is comfortably
     * under any realistic limit and also gives readable IM chunking instead
     * of one wall of text. Split at paragraph / line boundaries when possible.
     */
    static final int MAX_TEXT_MESSAGE_CHARS = 4000;

    @Override
    public void sendMessage(String targetId, String content) {
        if (httpClient == null) {
            log.warn("[feishu] Channel not started, cannot send message");
            return;
        }
        if (content == null) {
            return;
        }

        ensureTokenValid();
        List<String> chunks = splitTextForFeishu(content, MAX_TEXT_MESSAGE_CHARS);
        if (chunks.size() > 1) {
            log.info("[feishu] Splitting message into {} chunks ({} chars total) before send",
                    chunks.size(), content.length());
        }
        for (String chunk : chunks) {
            sendOneTextChunk(targetId, chunk);
        }
    }

    private void sendOneTextChunk(String targetId, String content) {
        String apiBase = getApiBaseUrl();
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "receive_id", targetId,
                    "msg_type", "text",
                    "content", objectMapper.writeValueAsString(Map.of("text", content))
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/open-apis/im/v1/messages?receive_id_type=chat_id"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + tenantAccessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[feishu] Send message failed: status={}, body={}", response.statusCode(), response.body());
            } else {
                log.debug("[feishu] Message sent to chat_id={} ({} chars)", targetId, content.length());
            }

        } catch (Exception e) {
            log.error("[feishu] Failed to send message: {}", e.getMessage(), e);
        }
    }

    /**
     * Split a possibly-oversized message into chunks no larger than
     * {@code maxChars}, preferring paragraph (\n\n) then line (\n) then
     * whitespace boundaries. Hard-cuts as a last resort so we never silently
     * drop content.
     */
    static List<String> splitTextForFeishu(String content, int maxChars) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        if (content.length() <= maxChars) {
            return List.of(content);
        }
        List<String> chunks = new ArrayList<>();
        int idx = 0;
        int n = content.length();
        while (idx < n) {
            int end = Math.min(idx + maxChars, n);
            if (end < n) {
                int boundary = -1;
                int paragraphCut = content.lastIndexOf("\n\n", end);
                if (paragraphCut > idx + maxChars / 2) {
                    boundary = paragraphCut + 2;
                }
                if (boundary < 0) {
                    int lineCut = content.lastIndexOf('\n', end);
                    if (lineCut > idx + maxChars / 2) {
                        boundary = lineCut + 1;
                    }
                }
                if (boundary < 0) {
                    int spaceCut = content.lastIndexOf(' ', end);
                    if (spaceCut > idx + maxChars / 2) {
                        boundary = spaceCut + 1;
                    }
                }
                if (boundary > idx) {
                    end = boundary;
                }
            }
            chunks.add(content.substring(idx, end));
            idx = end;
        }
        return chunks;
    }

    @Override
    public void sendContentParts(String targetId, List<MessageContentPart> parts) {
        if (httpClient == null) {
            log.warn("[feishu] Channel not started, cannot send message");
            return;
        }

        ensureTokenValid();

        for (MessageContentPart part : parts) {
            if (part == null) continue;
            try {
                switch (part.getType()) {
                    case "text" -> sendMessage(targetId, part.getText() != null ? part.getText() : "");
                    case "image" -> {
                        if (part.getMediaId() != null) {
                            sendFeishuMedia(targetId, "image", Map.of("image_key", part.getMediaId()));
                        }
                    }
                    case "file" -> {
                        if (part.getMediaId() != null) {
                            sendFeishuMedia(targetId, "file", Map.of("file_key", part.getMediaId()));
                        }
                    }
                    default -> {
                        if (part.getText() != null) sendMessage(targetId, part.getText());
                    }
                }
            } catch (Exception e) {
                log.error("[feishu] Failed to send content part ({}): {}", part.getType(), e.getMessage());
            }
        }
    }

    private void sendFeishuMedia(String chatId, String msgType, Map<String, Object> content) {
        String apiBase = getApiBaseUrl();
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "receive_id", chatId,
                    "msg_type", msgType,
                    "content", objectMapper.writeValueAsString(content)
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/open-apis/im/v1/messages?receive_id_type=chat_id"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + tenantAccessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[feishu] Send {} failed: status={}, body={}", msgType, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("[feishu] Failed to send {}: {}", msgType, e.getMessage(), e);
        }
    }

    // ==================== 主动推送 ====================

    @Override
    public boolean supportsProactiveSend() {
        return true;
    }

    /**
     * 主动推送消息
     * <p>
     * targetId 可以是：
     * - chat_id（以 oc_ 开头）：发送到群聊
     * - open_id（以 ou_ 开头）：发送到个人
     * - 其他：默认按 chat_id 处理
     */
    @Override
    public void proactiveSend(String targetId, String content) {
        if (httpClient == null) {
            log.warn("[feishu] Channel not started, cannot proactive send");
            return;
        }

        ensureTokenValid();
        String apiBase = getApiBaseUrl();

        // 根据 targetId 前缀判断 receive_id_type
        String receiveIdType;
        if (targetId.startsWith("ou_")) {
            receiveIdType = "open_id";
        } else {
            receiveIdType = "chat_id";
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "receive_id", targetId,
                    "msg_type", "text",
                    "content", objectMapper.writeValueAsString(Map.of("text", content))
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/open-apis/im/v1/messages?receive_id_type=" + receiveIdType))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + tenantAccessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[feishu] Proactive send failed: status={}, body={}", response.statusCode(), response.body());
            } else {
                log.debug("[feishu] Proactive message sent to {} (type={})", targetId, receiveIdType);
            }
        } catch (Exception e) {
            log.error("[feishu] Failed to proactive send: {}", e.getMessage(), e);
        }
    }

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    /**
     * WebSocket mode opens a long-lived connection to Lark's gateway, which
     * caps concurrent connections per bot app (~2). In a multi-instance
     * deployment every node would race for that quota and reconnect-loop on
     * {@code 1000040350: the number of connections exceeded the limit}.
     * The leader gate ensures only one node holds the connection at a time.
     *
     * <p>Webhook mode is exempt: callbacks are HTTP-fanned by the load
     * balancer, so all nodes can safely subscribe.
     */
    @Override
    public boolean requiresSingleLeader() {
        return "websocket".equals(getConfigString("connection_mode", "websocket"));
    }
}
