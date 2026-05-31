package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService.StreamDelta;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.ExponentialBackoff;
import vip.mate.channel.StreamingChannelAdapter;
import vip.mate.channel.media.GeneratedFileScrubber;
import vip.mate.channel.media.MediaSource;
import vip.mate.channel.media.MediaUploadException;
import vip.mate.channel.media.MediaUploadRequest;
import vip.mate.channel.media.MediaUploadResult;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

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
 * - card_format: 卡片格式化模式 "auto"（默认）| "always" | "never"
 *               auto: 根据内容自动检测；always: 全部包卡片；never: 全部纯文本（降级/调试用）
 * - card_header: Markdown 卡片 header 文案，默认 "AI 助手"；设为空串可隐藏 header
 * - require_mention: 群聊中是否需要 @机器人 才响应（默认 false）
 *               true: 仅当消息中 @了机器人才处理；通过飞书 mentions 字段精确判断，无需配置 botPrefix
 *
 * @author MateClaw Team
 */
@Slf4j
public class FeishuChannelAdapter extends AbstractChannelAdapter implements StreamingChannelAdapter {

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

    /** Bot's own open_id, fetched once from /open-apis/bot/v3/info and cached. */
    private volatile String botOpenId;

    /** Serializes lazy bot-open-id fetches so concurrent group messages share one API roundtrip. */
    private final Object botOpenIdLock = new Object();

    /**
     * Last failure timestamp for {@code /open-apis/bot/v3/info}. While the call is in the
     * {@link #BOT_OPENID_FAILURE_BACKOFF_MS} negative-cache window, {@link #getBotOpenId()}
     * returns {@code null} fast so a Feishu outage doesn't trigger one synchronous retry
     * per inbound group message.
     */
    private volatile long botOpenIdLastFailureMs = 0L;

    /** Negative-cache window for {@link #getBotOpenId()} failures (60 s). */
    private static final long BOT_OPENID_FAILURE_BACKOFF_MS = 60_000L;

    /** SDK-backed uploader for image / file / audio / video parts. Nullable for legacy callers. */
    private final FeishuMediaUploader mediaUploader;

    /** Scrubs {@code /api/v1/files/generated/{id}} URLs into native attachments. Nullable for legacy callers. */
    private final GeneratedFileScrubber generatedFileScrubber;

    /** CardKit streaming-card manager. Nullable for legacy callers / tests. */
    private final FeishuStreamingCardManager streamingCardManager;

    /** Interactive-card dispatcher (approval cards etc.). Nullable for legacy callers / tests. */
    private final vip.mate.channel.feishu.cards.FeishuCardDispatcher cardDispatcher;

    /**
     * Per-channel SDK {@code Client} factory — drives inbound resource
     * downloads via {@code client.im().v1().messageResource().get(...)}
     * instead of the legacy hand-rolled {@code HttpClient} path. Nullable
     * for legacy callers / tests, in which case the adapter falls back
     * to the raw HTTP path (still works, just bypasses retries / token
     * refresh / domain switching that the SDK handles).
     */
    private final FeishuClientFactory clientFactory;

    /**
     * Process-local cache that exposes downloaded inbound media via
     * {@code /api/v1/files/generated/{id}} so the conversation memory
     * and admin UI can render the file without holding a tenant token.
     * Nullable for legacy callers / tests.
     */
    private final vip.mate.tool.document.GeneratedFileCache generatedFileCache;

    /**
     * STT service for transcribing inbound voice messages. Feishu, unlike
     * WeCom / DingTalk, does NOT include ASR text in the webhook payload —
     * its inbound audio carries only a {@code file_key}. So we download the
     * bytes (via {@link #downloadResource}) and run them through
     * {@link vip.mate.stt.SttService} here, prepending the transcript as a
     * text {@link MessageContentPart} so the agent reasons over actual
     * content instead of the bare {@code "[音频]"} placeholder.
     *
     * <p>Nullable for legacy callers / tests — STT is skipped entirely when
     * absent, and the message still goes through as audio-only (degraded
     * but not broken).
     */
    private final vip.mate.stt.SttService sttService;

    // ==================== Per-chat recent file cache ====================

    /**
     * Per-chat cache of recently downloaded file messages. When a file is
     * sent in a Feishu chat (even without @mention), it is downloaded and
     * cached here. When a follow-up text message arrives in the same chat,
     * the cached files are injected as content parts so the agent can see
     * and process them.
     */
    private static final long RECENT_FILE_TTL_MINUTES = 60;
    private static final int RECENT_FILE_MAX_PER_CHAT = 5;

    record RecentFileEntry(String fileName, String path, String fileUrl, String contentType) {}

    private final Cache<String, List<RecentFileEntry>> recentFileCache = Caffeine.newBuilder()
            .expireAfterWrite(RECENT_FILE_TTL_MINUTES, TimeUnit.MINUTES)
            .maximumSize(200)
            .build();

    public FeishuChannelAdapter(ChannelEntity channelEntity,
                                ChannelMessageRouter messageRouter,
                                ObjectMapper objectMapper) {
        this(channelEntity, messageRouter, objectMapper, null, null, null, null, null, null, null);
    }

    public FeishuChannelAdapter(ChannelEntity channelEntity,
                                ChannelMessageRouter messageRouter,
                                ObjectMapper objectMapper,
                                FeishuMediaUploader mediaUploader,
                                GeneratedFileScrubber generatedFileScrubber) {
        this(channelEntity, messageRouter, objectMapper, mediaUploader, generatedFileScrubber,
                null, null, null, null, null);
    }

    public FeishuChannelAdapter(ChannelEntity channelEntity,
                                ChannelMessageRouter messageRouter,
                                ObjectMapper objectMapper,
                                FeishuMediaUploader mediaUploader,
                                GeneratedFileScrubber generatedFileScrubber,
                                FeishuStreamingCardManager streamingCardManager) {
        this(channelEntity, messageRouter, objectMapper, mediaUploader,
                generatedFileScrubber, streamingCardManager, null, null, null, null);
    }

    public FeishuChannelAdapter(ChannelEntity channelEntity,
                                ChannelMessageRouter messageRouter,
                                ObjectMapper objectMapper,
                                FeishuMediaUploader mediaUploader,
                                GeneratedFileScrubber generatedFileScrubber,
                                FeishuStreamingCardManager streamingCardManager,
                                vip.mate.channel.feishu.cards.FeishuCardDispatcher cardDispatcher) {
        this(channelEntity, messageRouter, objectMapper, mediaUploader,
                generatedFileScrubber, streamingCardManager, cardDispatcher, null, null, null);
    }

    public FeishuChannelAdapter(ChannelEntity channelEntity,
                                ChannelMessageRouter messageRouter,
                                ObjectMapper objectMapper,
                                FeishuMediaUploader mediaUploader,
                                GeneratedFileScrubber generatedFileScrubber,
                                FeishuStreamingCardManager streamingCardManager,
                                vip.mate.channel.feishu.cards.FeishuCardDispatcher cardDispatcher,
                                FeishuClientFactory clientFactory,
                                vip.mate.tool.document.GeneratedFileCache generatedFileCache) {
        this(channelEntity, messageRouter, objectMapper, mediaUploader,
                generatedFileScrubber, streamingCardManager, cardDispatcher,
                clientFactory, generatedFileCache, null);
    }

    public FeishuChannelAdapter(ChannelEntity channelEntity,
                                ChannelMessageRouter messageRouter,
                                ObjectMapper objectMapper,
                                FeishuMediaUploader mediaUploader,
                                GeneratedFileScrubber generatedFileScrubber,
                                FeishuStreamingCardManager streamingCardManager,
                                vip.mate.channel.feishu.cards.FeishuCardDispatcher cardDispatcher,
                                FeishuClientFactory clientFactory,
                                vip.mate.tool.document.GeneratedFileCache generatedFileCache,
                                vip.mate.stt.SttService sttService) {
        super(channelEntity, messageRouter, objectMapper);
        this.mediaUploader = mediaUploader;
        this.generatedFileScrubber = generatedFileScrubber;
        this.streamingCardManager = streamingCardManager;
        this.cardDispatcher = cardDispatcher;
        this.clientFactory = clientFactory;
        this.generatedFileCache = generatedFileCache;
        this.sttService = sttService;
        // Feishu WebSocket reconnect: 2s→4s→8s→16s→30s, infinite retry
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

        // Prefetch the bot's own open_id once the token is valid so the first
        // require_mention check on the WebSocket dispatch thread doesn't pay
        // the 5 s API latency. Failure is non-fatal — getBotOpenId() handles
        // it and the negative cache keeps subsequent retries cheap.
        getBotOpenId();

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
        // Reset bot-open-id state under the same lock getBotOpenId uses, so a
        // dispatch thread mid-fetch sees a consistent (cleared) view rather than
        // a torn write that could re-cache a stale id.
        synchronized (botOpenIdLock) {
            this.botOpenId = null;
            this.botOpenIdLastFailureMs = 0L;
        }
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

        // Re-prefetch bot open_id after reconnect: same dispatch-thread latency
        // concern as doStart, plus picks up a rotated app identity if any.
        getBotOpenId();

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
                // Silently ignore all other IM events to avoid HandlerNotFoundException.
                // When the SDK receives an event without a registered handler, it throws
                // HandlerNotFoundException → SDK catches it and sends a 500 response →
                // the Feishu server may close the WebSocket connection. The exception is
                // swallowed inside the SDK so it never appears in application logs.
                .onP2MessageReactionCreatedV1(new ImService.P2MessageReactionCreatedV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2MessageReactionCreatedV1 event) {}
                })
                .onP2MessageReactionDeletedV1(new ImService.P2MessageReactionDeletedV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2MessageReactionDeletedV1 event) {}
                })
                .onP2MessageReadV1(new ImService.P2MessageReadV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2MessageReadV1 event) {}
                })
                .onP2MessageRecalledV1(new ImService.P2MessageRecalledV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2MessageRecalledV1 event) {}
                })
                .onP2ChatMemberBotAddedV1(new ImService.P2ChatMemberBotAddedV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2ChatMemberBotAddedV1 event) {}
                })
                .onP2ChatMemberBotDeletedV1(new ImService.P2ChatMemberBotDeletedV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2ChatMemberBotDeletedV1 event) {}
                })
                .onP2ChatMemberUserAddedV1(new ImService.P2ChatMemberUserAddedV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2ChatMemberUserAddedV1 event) {}
                })
                .onP2ChatMemberUserDeletedV1(new ImService.P2ChatMemberUserDeletedV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2ChatMemberUserDeletedV1 event) {}
                })
                .onP2ChatMemberUserWithdrawnV1(new ImService.P2ChatMemberUserWithdrawnV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2ChatMemberUserWithdrawnV1 event) {}
                })
                .onP2ChatUpdatedV1(new ImService.P2ChatUpdatedV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2ChatUpdatedV1 event) {}
                })
                .onP2ChatDisbandedV1(new ImService.P2ChatDisbandedV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2ChatDisbandedV1 event) {}
                })
                .onP2ChatAccessEventBotP2pChatEnteredV1(new ImService.P2ChatAccessEventBotP2pChatEnteredV1Handler() {
                    @Override
                    public void handle(com.lark.oapi.service.im.v1.model.P2ChatAccessEventBotP2pChatEnteredV1 event) {}
                })
                // Interactive card button clicks (Schema 2.0) — routed through cardDispatcher.
                // The returned P2CardActionTriggerResponse is how Schema-2.0
                // cards update in-place; PATCH /im/v1/messages/{id} is a
                // silent no-op for V2 cards and must not be used.
                .onP2CardActionTrigger(new com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler() {
                    @Override
                    public com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse handle(
                            com.lark.oapi.event.cardcallback.model.P2CardActionTrigger event) {
                        if (!running.get()) return null;
                        try {
                            return handleCardActionTrigger(event);
                        } catch (Exception e) {
                            log.error("[feishu] Failed to handle card action: {}", e.getMessage(), e);
                            return null;
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
     * 关闭 WebSocket 连接。
     * <p>
     * 必须主动关闭底层 WebSocket 连接并触发 SDK 的内部清理（停止 pingLoop、
     * 释放 ExecutorService）。仅置空引用会导致旧连接的 pingLoop 和线程池
     * 持续运行，造成文件描述符和线程泄漏，最终使新连接无法建立。
     * <p>
     * SDK 2.7.0 起暴露了 public {@code close()} 入口，内部调用 protected
     * {@code disconnect()} 完成 {@code conn.close(1000) → executor.shutdown() →
     * 字段清零} 的全套清理。直接调用即可，无需反射。
     */
    private void stopWebSocket() {
        cancelSilentDisconnectWatchdog();
        hasReceivedFirstEvent = false;
        if (wsThread != null) {
            wsThread.interrupt();
            wsThread = null;
        }
        if (wsClient != null) {
            try {
                wsClient.close();
            } catch (Exception e) {
                log.warn("[feishu] WebSocket close failed: {}", e.getMessage());
            }
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

        boolean isBotMentioned = isBotMentionedInEvent(message.getMentions());
        handleFeishuMessage(messageId, messageType, contentStr, chatId, chatType, senderOpenId, parentId, isBotMentioned, event);
    }

    // ==================== @提及检测 ====================

    private boolean isBotMentionedInEvent(com.lark.oapi.service.im.v1.model.MentionEvent[] mentions) {
        return eventMentionsContainBot(mentions, getBotOpenId());
    }

    private boolean isBotMentionedInWebhookMessage(Map<String, Object> message) {
        Object mentionsObj = message.get("mentions");
        if (!(mentionsObj instanceof List<?> list)) return false;
        return webhookMentionsContainBot(list, getBotOpenId());
    }

    /** Package-private for testing: 判断 SDK mentions 数组中是否包含指定 open_id */
    static boolean eventMentionsContainBot(com.lark.oapi.service.im.v1.model.MentionEvent[] mentions,
                                           String botOpenId) {
        if (mentions == null || mentions.length == 0 || botOpenId == null) return false;
        for (var mention : mentions) {
            if (mention.getId() != null && botOpenId.equals(mention.getId().getOpenId())) return true;
        }
        return false;
    }

    /** Package-private for testing: 判断 Webhook mentions 列表中是否包含指定 open_id */
    static boolean webhookMentionsContainBot(List<?> mentions, String botOpenId) {
        if (mentions == null || botOpenId == null) return false;
        for (Object item : mentions) {
            if (!(item instanceof Map<?, ?> mention)) continue;
            Object idObj = mention.get("id");
            if (!(idObj instanceof Map<?, ?> id)) continue;
            if (botOpenId.equals(id.get("open_id"))) return true;
        }
        return false;
    }

    /**
     * Package-private for testing: returns true when a group message should be dropped
     * because {@code require_mention} is on, the bot was not mentioned, AND we know our
     * own open_id (so we trust the negative answer).
     *
     * <p>When {@code botOpenId} is {@code null} the bot identity is unavailable — either
     * the {@code /open-apis/bot/v3/info} fetch hasn't succeeded yet, or it failed and is
     * in the negative-cache window. In that case the gate falls open so a transient
     * Feishu API outage doesn't silence the bot in every group it's in. The accompanying
     * warn-level log makes the degraded mode visible.
     */
    static boolean isGroupNonMentionDrop(boolean isGroup,
                                         boolean requireMention,
                                         boolean isBotMentioned,
                                         String botOpenId) {
        return isGroup && requireMention && !isBotMentioned && botOpenId != null;
    }

    /**
     * Fetches the bot's own open_id once and caches it. Returns {@code null}
     * when the identity is unavailable; callers (currently the
     * {@code require_mention} gate) treat {@code null} as "identity unknown"
     * and fall open so a transient API outage doesn't silence the bot.
     *
     * <p>Concurrent callers share a single API roundtrip via
     * {@link #botOpenIdLock}. On failure, {@link #botOpenIdLastFailureMs} is
     * stamped so callers within the next {@link #BOT_OPENID_FAILURE_BACKOFF_MS}
     * ms return {@code null} immediately instead of triggering a fresh 5 s
     * synchronous fetch per inbound message.
     */
    private String getBotOpenId() {
        String cached = botOpenId;
        if (cached != null) return cached;
        if (withinFailureBackoff()) return null;
        synchronized (botOpenIdLock) {
            // Re-check under the lock — another thread may have populated the
            // cache or stamped a fresh failure while we were waiting.
            if (botOpenId != null) return botOpenId;
            if (withinFailureBackoff()) return null;
            try {
                ensureTokenValid();
                String apiBase = getApiBaseUrl();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiBase + "/open-apis/bot/v3/info"))
                        .header("Authorization", "Bearer " + tenantAccessToken)
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                Map<?, ?> body = objectMapper.readValue(response.body(), Map.class);
                Map<?, ?> bot = (Map<?, ?>) body.get("bot");
                if (bot != null && bot.get("open_id") instanceof String openId && !openId.isBlank()) {
                    botOpenId = openId;
                    log.info("[feishu] Bot open_id fetched and cached: {}", openId);
                    return openId;
                }
                // 2xx with no bot.open_id field → treat as transient failure.
                botOpenIdLastFailureMs = System.currentTimeMillis();
                log.warn("[feishu] /open-apis/bot/v3/info returned no bot.open_id; require_mention gate falls open for {}s",
                        BOT_OPENID_FAILURE_BACKOFF_MS / 1000);
            } catch (Exception e) {
                botOpenIdLastFailureMs = System.currentTimeMillis();
                log.warn("[feishu] Failed to fetch bot open_id (require_mention gate falls open for {}s): {}",
                        BOT_OPENID_FAILURE_BACKOFF_MS / 1000, e.getMessage());
            }
            return null;
        }
    }

    private boolean withinFailureBackoff() {
        long last = botOpenIdLastFailureMs;
        return last != 0L && System.currentTimeMillis() - last < BOT_OPENID_FAILURE_BACKOFF_MS;
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

            boolean isBotMentioned = isBotMentionedInWebhookMessage(message);
            handleFeishuMessage(messageId, messageType, contentStr, chatId, chatType, senderOpenId, parentId, isBotMentioned, payload);

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
                                      String parentId, boolean isBotMentioned, Object rawPayload) {
        // Per-chat recent file cache: always download file messages (even
        // without @mention) so they can be auto-associated with follow-up
        // text messages in the same chat.
        boolean isGroup = "group".equals(chatType);
        boolean isFileMessage = "file".equals(messageType) || "image".equals(messageType)
                || "audio".equals(messageType) || "media".equals(messageType);
        // Compute conversationId once — used as cache key for both write (cacheRecentFile)
        // and read (injectRecentFiles), and as the directory name under data/chat-uploads/.
        // It MUST equal the id ChannelMessageRouter derives for this chat: the routed
        // ChannelMessage carries chatId = (isGroup ? shortSuffix : null), so the router
        // resolves it to feishu:{shortSuffix} for groups and feishu:{senderId} for DMs.
        // ChatUploadResolver locates attachments under data/chat-uploads/{that id}/, and the
        // prompt only exposes the file name (not its path) to the model — so if this id does
        // not match, ReadFileTool / DocumentExtractTool cannot find the cached file.
        String shortSuffix = generateShortSessionSuffix(chatId, senderOpenId, isGroup);
        String conversationId = buildConversationId(shortSuffix, senderOpenId, isGroup);

        if (isFileMessage) {
            cacheRecentFile(messageId, messageType, contentStr, conversationId);
        }

        // require_mention 群聊过滤：群聊中必须 @机器人才响应。
        // 当 botOpenId 为 null 时（API 抖动 / 尚未拉取成功），失败回退到放行 —
        // 避免飞书 /open-apis/bot/v3/info 短暂不可用时整个群机器人变哑巴。
        boolean requireMention = getConfigBoolean("require_mention", false);
        if (isGroupNonMentionDrop(isGroup, requireMention, isBotMentioned, botOpenId)) {
            log.debug("[feishu] require_mention=true but bot not mentioned, dropping messageId={}", messageId);
            return;
        }
        if (isGroup && requireMention && !isBotMentioned) {
            // botOpenId is null here — identity unknown, gate falls open.
            log.warn("[feishu] require_mention=true but bot open_id unavailable; allowing messageId={}", messageId);
        }

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

        // Auto-associate recent files: inject file/image parts from the
        // per-chat cache so the agent can see files sent earlier in the
        // same conversation (user sends file → asks about it in text).
        if (!isFileMessage && conversationId != null) {
            textContent = injectRecentFiles(conversationId, contentParts, textContent);
        }

        // shortSuffix already computed above (kept consistent with conversationId).
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
     * Acknowledge a successful agent reply by reacting to the inbound
     * message with a "DONE" (✅) emoji. Gated by {@code enable_done_reaction}
     * (default true) — operators that prefer a quiet UI can disable it
     * without losing the existing inbound "THUMBSUP" ack.
     */
    @Override
    public void onAgentCompleted(ChannelMessage inboundMessage) {
        if (inboundMessage == null) return;
        String messageId = inboundMessage.getMessageId();
        if (messageId == null || messageId.isBlank()) return;
        if (!getConfigBoolean("enable_done_reaction", true)) return;
        addReactionAsync(messageId, "DONE");
    }

    // ==================== Approval card ====================

    /**
     * Card-button approval is the canonical path for Feishu when the
     * dispatcher is wired. Tells {@code ChannelMessageRouter} not to
     * auto-cancel pending approvals on subsequent user messages —
     * users decide via clicking, not by typing {@code /approve}.
     * Returns false only in legacy / test contexts where the
     * dispatcher isn't injected (the inherited text-flow path applies).
     */
    @Override
    public boolean usesInteractiveApprovalCards() {
        return cardDispatcher != null;
    }

    /**
     * Render the approval notice as a Schema-2.0 interactive button card
     * so the user can approve / deny in-channel without bouncing to the
     * web UI. Falls back to the inherited markdown-text path when the
     * dispatcher isn't wired (legacy constructors / test rigs), the
     * card oversizes, or the platform refuses to deliver.
     */
    @Override
    public void sendApprovalNotice(String targetId,
            vip.mate.channel.notification.ApprovalNotice notice) {
        if (cardDispatcher == null || notice == null || targetId == null) {
            super.sendApprovalNotice(targetId, notice);
            return;
        }
        var kindOpt = cardDispatcher.lookupByName(
                vip.mate.channel.feishu.cards.tool_guard.ToolGuardCardKindFactory.KIND_NAME);
        if (kindOpt.isEmpty()) {
            super.sendApprovalNotice(targetId, notice);
            return;
        }
        try {
            Map<String, Object> cardJson = kindOpt.get().renderer().render(notice);
            boolean sent = sendCard(targetId, cardJson);
            if (!sent) {
                log.warn("[feishu-toolguard] sendCard returned false; falling back to text");
                super.sendApprovalNotice(targetId, notice);
            }
        } catch (vip.mate.channel.cards.CardOversizedException e) {
            log.warn("[feishu-toolguard] approval card oversized ({}); falling back to text", e.getMessage());
            super.sendApprovalNotice(targetId, notice);
        } catch (Exception e) {
            log.error("[feishu-toolguard] render/send approval card failed; falling back to text: {}",
                    e.getMessage(), e);
            super.sendApprovalNotice(targetId, notice);
        }
    }

    /**
     * Dispatch a {@code P2CardActionTrigger} (button click on an
     * interactive card) to the matching {@code FeishuCardKind} via the
     * dispatcher and propagate the kind's response back to Feishu so
     * the card can update in-place. Returns null when no dispatcher is
     * wired or no kind matches the action prefix — Feishu leaves the
     * original card unchanged in that case.
     */
    private com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse handleCardActionTrigger(
            com.lark.oapi.event.cardcallback.model.P2CardActionTrigger event) {
        if (cardDispatcher == null || event == null || event.getEvent() == null) {
            return null;
        }
        com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData data = event.getEvent();
        com.lark.oapi.event.cardcallback.model.CallBackAction action = data.getAction();
        if (action == null || action.getValue() == null) {
            return null;
        }
        Object actionField = action.getValue().get("action");
        String actionStr = actionField != null ? actionField.toString() : null;
        var kindOpt = cardDispatcher.lookupByAction(actionStr);
        if (kindOpt.isEmpty()) {
            log.debug("[feishu] No card kind registered for action={}", actionStr);
            return null;
        }
        return kindOpt.get().handler().handle(this, data);
    }

    /**
     * Re-enter the router as if the given message arrived from this
     * channel. Used by the tool-guard card handler to re-emit the
     * button click as a synthetic {@code /approve <pendingId>} or
     * {@code /deny <pendingId>} so the router runs its canonical
     * text-approve path ({@code resolveAndConsume + replay}).
     *
     * <p>External callers must go through {@link #onMessage}; this is
     * the in-package fast lane that skips the WS / webhook decode.
     */
    public void injectSyntheticMessage(ChannelMessage message) {
        messageRouter.enqueue(message, this, channelEntity);
    }

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

    /**
     * Compute the conversationId that {@link ChannelMessageRouter} would
     * derive for this chat, so we can save inbound files to the matching
     * {@code data/chat-uploads/} directory.
     *
     * <p>The router derives the id from the routed {@link ChannelMessage},
     * whose {@code chatId} is {@code (isGroup ? shortSuffix : null)} and whose
     * {@code senderId} is the full open id. Mirror that exactly:
     * {@code groups → feishu:{shortSuffix}}, {@code DMs → feishu:{senderOpenId}}.
     */
    static String buildConversationId(String shortSuffix, String senderOpenId, boolean isGroup) {
        // The routed ChannelMessage carries chatId = (isGroup ? shortSuffix : null);
        // the router then falls back to senderId when that chatId is null. Mirror both
        // steps so the storage id matches the runtime id in every case (including the
        // degenerate group-with-no-suffix path).
        String routedChatId = isGroup ? shortSuffix : null;
        String identifier = routedChatId != null ? routedChatId : senderOpenId;
        return identifier != null ? CHANNEL_TYPE + ":" + identifier : null;
    }

    // ==================== Per-chat recent file cache ====================

    /**
     * Download an inbound file message and cache its metadata in the
     * per-chat recent-file cache. The file is saved to
     * {@code data/chat-uploads/{conversationId}/} so existing tools
     * ({@code ReadFileTool}, {@code DocumentExtractTool}) can find it
     * via {@code ChatUploadResolver}, and it gets cleaned up when the
     * conversation is deleted.
     */
    private void cacheRecentFile(String messageId, String messageType, String contentStr,
                                  String conversationId) {
        try {
            Map<String, Object> contentObj = objectMapper.readValue(contentStr, Map.class);

            String fileKey = null;
            String fileName = null;
            String type; // SDK type: "image" or "file"

            switch (messageType) {
                case "image" -> {
                    fileKey = (String) contentObj.get("image_key");
                    type = "image";
                }
                case "file" -> {
                    fileKey = (String) contentObj.get("file_key");
                    fileName = (String) contentObj.get("file_name");
                    type = "file";
                }
                case "audio" -> {
                    fileKey = (String) contentObj.get("file_key");
                    type = "file";
                }
                case "media" -> {
                    fileKey = (String) contentObj.get("file_key");
                    fileName = (String) contentObj.get("file_name");
                    type = "file";
                }
                default -> {
                    return;
                }
            }

            if (fileKey == null) return;

            // Download file bytes
            DownloadedResource dl = "image".equals(messageType)
                    ? maybeDownloadImage(messageId, fileKey)
                    : maybeDownloadResource(messageId, fileKey, type, fileName);
            if (dl == null) return;

            // Save to data/chat-uploads/{conversationId}/
            Path uploadDir = Path.of("data", "chat-uploads", conversationId);
            Files.createDirectories(uploadDir);
            String rawName = (dl.fileName() != null && !dl.fileName().isBlank())
                    ? dl.fileName() : fileKey;
            String safeName = Path.of(rawName).getFileName().toString()
                    .replaceAll("[^a-zA-Z0-9._-]", "_");
            if (safeName.isBlank()) safeName = "file";
            String storedName = System.currentTimeMillis() + "_" + safeName;
            Path dest = uploadDir.resolve(storedName);
            Files.copy(Path.of(dl.path()), dest, StandardCopyOption.REPLACE_EXISTING);

            String contentType = dl.contentType() != null ? dl.contentType() : "application/octet-stream";
            RecentFileEntry entry = new RecentFileEntry(safeName, dest.toAbsolutePath().toString(),
                    dl.fileUrl(), contentType);

            // Append to per-conversation cache (cap at RECENT_FILE_MAX_PER_CHAT)
            recentFileCache.asMap().compute(conversationId, (k, existing) -> {
                List<RecentFileEntry> list = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
                list.add(entry);
                if (list.size() > RECENT_FILE_MAX_PER_CHAT) {
                    list = list.subList(list.size() - RECENT_FILE_MAX_PER_CHAT, list.size());
                }
                return list;
            });

            log.info("[feishu] Cached recent file for conversation={}: {} ({} bytes, {})",
                    conversationId, entry.fileName(), Files.size(dest), contentType);

        } catch (Exception e) {
            log.debug("[feishu] Failed to cache recent file: {}", e.getMessage());
        }
    }

    /**
     * Inject recent files from the per-chat cache into the current
     * message's content parts, so the agent can see files that were
     * sent earlier in the same conversation.
     *
     * @return updated textContent with file descriptions appended
     */
    private String injectRecentFiles(String conversationId, List<MessageContentPart> parts, String textContent) {
        List<RecentFileEntry> recent = recentFileCache.getIfPresent(conversationId);
        if (recent == null || recent.isEmpty()) return textContent;

        // Collect paths already in parts to avoid duplicates
        Set<String> existingPaths = new java.util.HashSet<>();
        for (MessageContentPart p : parts) {
            if (p != null && p.getPath() != null) existingPaths.add(p.getPath());
        }

        StringBuilder text = new StringBuilder(textContent != null ? textContent : "");
        for (RecentFileEntry entry : recent) {
            if (existingPaths.contains(entry.path())) continue;

            MessageContentPart part = new MessageContentPart();
            if (entry.contentType() != null && entry.contentType().startsWith("image/")) {
                part.setType("image");
            } else {
                part.setType("file");
            }
            part.setFileName(entry.fileName());
            part.setPath(entry.path());
            if (entry.fileUrl() != null) part.setFileUrl(entry.fileUrl());
            part.setContentType(entry.contentType());
            parts.add(part);

            if (!text.isEmpty()) text.append('\n');
            text.append("[用户发送了文件: ").append(entry.fileName()).append("]");
        }
        return text.toString();
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
                        DownloadedResource dl = maybeDownloadImage(messageId, imageKey);
                        MessageContentPart part = MessageContentPart.image(imageKey, null);
                        applyDownload(part, dl);
                        parts.add(part);
                    }
                    yield "[图片]";
                }
                case "file" -> {
                    String fileKey = (String) contentObj.get("file_key");
                    String fileName = (String) contentObj.get("file_name");
                    if (fileKey != null) {
                        DownloadedResource dl = maybeDownloadResource(messageId, fileKey, "file", fileName);
                        MessageContentPart part = MessageContentPart.file(fileKey, fileName, null);
                        applyDownload(part, dl);
                        parts.add(part);
                    }
                    yield "[文件: " + (fileName != null ? fileName : "") + "]";
                }
                case "audio" -> {
                    String fileKey = (String) contentObj.get("file_key");
                    if (fileKey != null) {
                        // Feishu voice messages are always opus (no extension on the
                        // wire) — give downloadResource the hint so the on-disk file
                        // ends in .opus and SttService gets a usable MIME for routing.
                        DownloadedResource dl = maybeDownloadResource(messageId, fileKey, "file", "voice.opus");
                        MessageContentPart part = MessageContentPart.audio(fileKey, null);
                        applyDownload(part, dl);
                        // STT hop: inject the transcript as a sibling text part BEFORE
                        // the audio part so ChannelMessageRouter.buildPromptFromParts
                        // sees real content instead of just "[音频]". WeCom / DingTalk
                        // skip this step because their webhooks already include ASR
                        // text; Feishu does not.
                        String transcript = transcribeInboundAudio(dl);
                        if (transcript != null && !transcript.isBlank()) {
                            parts.add(MessageContentPart.text(transcript));
                        }
                        parts.add(part);
                    }
                    yield "[音频]";
                }
                case "media" -> {
                    String fileKey = (String) contentObj.get("file_key");
                    String fileName = (String) contentObj.get("file_name");
                    if (fileKey != null) {
                        DownloadedResource dl = maybeDownloadResource(messageId, fileKey, "file", fileName);
                        MessageContentPart part = MessageContentPart.video(fileKey, fileName);
                        applyDownload(part, dl);
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

        // Same default-on behaviour as the standalone file/audio/video branch — see maybeDownloadResource.
        boolean mediaDownload = getConfigBoolean("media_download_enabled", true);

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
                            DownloadedResource dl = maybeDownloadImage(messageId, imageKey);
                            MessageContentPart imgPart = MessageContentPart.image(imageKey, null);
                            applyDownload(imgPart, dl);
                            parts.add(imgPart);
                            text.append("[图片]");
                        }
                    }
                    case "media" -> {
                        String fileKey = (String) element.get("file_key");
                        if (fileKey != null) {
                            DownloadedResource dl = mediaDownload
                                    ? maybeDownloadResource(messageId, fileKey, "file", null)
                                    : null;
                            MessageContentPart mediaPart = MessageContentPart.file(fileKey, null, null);
                            applyDownload(mediaPart, dl);
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

    // ==================== Inbound media download ====================

    /**
     * Result of a successful inbound-resource download.
     *
     * @param path        absolute path on disk under
     *                    {@code ~/.mateclaw/media/feishu/} — fed to
     *                    {@link MessageContentPart#setPath(String)} so
     *                    vision / file-reading tools can consume bytes
     * @param fileUrl     {@code /api/v1/files/generated/{id}} URL backed
     *                    by {@link vip.mate.tool.document.GeneratedFileCache}
     *                    (10-min TTL) so the admin UI and conversation
     *                    memory can render the file without a tenant
     *                    token; {@code null} when the cache wasn't wired
     *                    in (e.g. legacy tests)
     * @param fileName    server-side filename (extension inferred from
     *                    content-type, falls back to {@code fileNameHint}
     *                    or the SDK-returned name)
     * @param contentType MIME type from the download response headers
     */
    // Package-private for unit tests — pin the field copy contract.
    record DownloadedResource(String path, String fileUrl,
                              String fileName, String contentType) {}

    /**
     * Download a {@code file} / {@code audio} / {@code video} inbound
     * resource, but only when the per-channel
     * {@code media_download_enabled} flag is on. Default is now <b>true</b>
     * so a user uploading a file to the bot lands as a real file the
     * agent can read — set it to {@code false} on the channel config to
     * opt out (e.g. tighter privacy, no disk usage).
     *
     * <p>Mirrors the WeCom / DingTalk pattern: text-only adapters dropping
     * file bytes was the "did you receive my doc?" gap reported in
     * production.
     */
    private DownloadedResource maybeDownloadResource(String messageId, String fileKey, String type, String fileNameHint) {
        if (!getConfigBoolean("media_download_enabled", true)) {
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
    private DownloadedResource maybeDownloadImage(String messageId, String imageKey) {
        if (!getConfigBoolean("feishu_image_download_enabled", true)) {
            return null;
        }
        return downloadResource(messageId, imageKey, "image", null);
    }

    /**
     * Copy a {@link DownloadedResource}'s fields onto a
     * {@link MessageContentPart}. {@code null} input is a no-op so the
     * caller can keep the part as a bare {@code file_key} placeholder
     * when download was disabled / failed.
     */
    /**
     * Read the downloaded audio bytes from disk and run them through
     * {@link vip.mate.stt.SttService}. Returns the transcribed text, or
     * {@code null} when STT is not wired, disabled, the download didn't
     * produce a usable path, or every provider failed.
     *
     * <p>Best-effort by design — STT failure must not block the agent
     * from seeing the audio part. The user gets the "[音频]" placeholder
     * and any agent that's tooled-up can still investigate.
     */
    // Package-private for unit tests.
    String transcribeInboundAudio(DownloadedResource dl) {
        if (sttService == null || dl == null || dl.path() == null) {
            return null;
        }
        try {
            byte[] audioBytes = Files.readAllBytes(Path.of(dl.path()));
            if (audioBytes.length == 0) {
                log.debug("[feishu-stt] empty audio file at {}, skipping STT", dl.path());
                return null;
            }
            String fileName = dl.fileName() != null ? dl.fileName() : "voice.opus";
            String contentType = dl.contentType() != null ? dl.contentType() : "audio/opus";
            // language=null → SttService falls back to the system-settings
            // UI language hint; works for both Chinese and English without
            // needing per-channel config.
            Map<String, Object> result = sttService.transcribe(
                    audioBytes, fileName, contentType, null);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                log.warn("[feishu-stt] transcription failed: {}", result.get("error"));
                return null;
            }
            String text = (String) result.get("text");
            if (text == null || text.isBlank()) {
                log.debug("[feishu-stt] transcription returned empty text");
                return null;
            }
            log.info("[feishu-stt] transcribed {} bytes → {} chars",
                    audioBytes.length, text.length());
            return text;
        } catch (Exception e) {
            log.warn("[feishu-stt] transcription threw: {}", e.getMessage());
            return null;
        }
    }

    // Package-private for unit tests.
    static void applyDownload(MessageContentPart part, DownloadedResource dl) {
        if (dl == null || part == null) return;
        if (dl.path() != null) part.setPath(dl.path());
        if (dl.fileUrl() != null) part.setFileUrl(dl.fileUrl());
        if (dl.fileName() != null && (part.getFileName() == null || part.getFileName().isBlank())) {
            part.setFileName(dl.fileName());
        }
        if (dl.contentType() != null) part.setContentType(dl.contentType());
    }

    /**
     * Pull an inbound resource (image / file / audio / video) from
     * Feishu and persist it both to disk and (best-effort) to the
     * {@link vip.mate.tool.document.GeneratedFileCache}.
     *
     * <p>Implementation prefers the official {@code oapi-sdk}
     * ({@code client.im().v1().messageResource().get(...)}) when a
     * {@link FeishuClientFactory} was wired in — gets token refresh,
     * domain switching (feishu / lark), retries, and the same
     * {@code Authorization} contract the rest of the SDK uses for free.
     * Falls back to the legacy hand-rolled {@code HttpClient} path
     * when no factory is available (3-arg legacy ctor / unit tests).
     *
     * @param messageId    Feishu message id ({@code om_…}) — required
     *                     by the resource endpoint to scope the file
     * @param fileKey      resource key ({@code img_…} for images,
     *                     {@code file_…} for everything else)
     * @param type         SDK type discriminator: {@code "image"} for
     *                     images, {@code "file"} for file/audio/video
     * @param fileNameHint optional original filename (used for
     *                     extension inference when the response has no
     *                     useful Content-Type)
     * @return a {@link DownloadedResource} on success, {@code null} on
     *         any failure (logged at debug) — caller treats {@code null}
     *         as "skip, just pass the opaque key through"
     */
    private DownloadedResource downloadResource(String messageId, String fileKey, String type, String fileNameHint) {
        // ---- Prefer SDK path: token refresh, retries, domain handled by the SDK
        if (clientFactory != null && channelEntity != null && channelEntity.getId() != null) {
            try {
                com.lark.oapi.Client client = clientFactory.client(channelEntity.getId());
                com.lark.oapi.service.im.v1.model.GetMessageResourceReq req =
                        com.lark.oapi.service.im.v1.model.GetMessageResourceReq.newBuilder()
                                .messageId(messageId)
                                .fileKey(fileKey)
                                .type(type)
                                .build();
                com.lark.oapi.service.im.v1.model.GetMessageResourceResp resp =
                        client.im().v1().messageResource().get(req);
                if (!resp.success() || resp.getData() == null) {
                    log.debug("[feishu] SDK resource.get failed: code={}, msg={}",
                            resp.getCode(), resp.getMsg());
                    return null;
                }
                byte[] bytes = resp.getData().toByteArray();
                // SDK exposes Content-Disposition's filename via getFileName();
                // prefer it over the hint when present.
                String sdkFileName = resp.getFileName();
                String preferredHint = (sdkFileName != null && !sdkFileName.isBlank())
                        ? sdkFileName : fileNameHint;
                // No Content-Type accessor on GetMessageResourceResp — infer
                // from filename extension (good enough for cache MIME and
                // for vision pipelines that key on extension).
                String contentType = inferMimeFromName(preferredHint);
                return persistAndCache(messageId, fileKey, bytes, preferredHint, contentType);
            } catch (Exception e) {
                log.debug("[feishu] SDK resource.get threw, falling back to raw HTTP: {}", e.getMessage());
                // Fall through to raw HTTP — keeps the inbound path resilient
                // when the SDK is temporarily unhappy (e.g. invalid req shape
                // on a new event type the SDK doesn't yet model).
            }
        }

        // ---- Legacy fallback: hand-rolled HTTP. Kept so the 3-arg ctor
        // and unit tests that don't wire a factory still get bytes.
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

            // BodyHandlers.ofInputStream does NOT auto-close the response body —
            // callers must consume or close it on every path, including non-2xx
            // and early-return-after-content-type. Use try-with-resources around
            // the entire post-send block so failed downloads don't leak file
            // descriptors during sustained traffic.
            try (InputStream is = response.body()) {
                if (response.statusCode() != 200) {
                    log.debug("[feishu] Download resource failed: status={}", response.statusCode());
                    return null;
                }
                String contentType = response.headers().firstValue("Content-Type")
                        .orElse(inferMimeFromName(fileNameHint));
                byte[] bytes = is.readAllBytes();
                return persistAndCache(messageId, fileKey, bytes, fileNameHint, contentType);
            }

        } catch (Exception e) {
            log.debug("[feishu] Download resource failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Write {@code bytes} to {@code ~/.mateclaw/media/feishu/} and,
     * if {@link #generatedFileCache} is wired, also push them into the
     * cache so the UI gets a {@code /api/v1/files/generated/{id}} URL.
     * Disk persist is always attempted; cache push is best-effort.
     */
    private DownloadedResource persistAndCache(String messageId, String fileKey,
                                               byte[] bytes, String fileNameHint,
                                               String contentType) throws java.io.IOException {
        Path mediaDir = Path.of(System.getProperty("user.home"), ".mateclaw", "media", "feishu");
        Files.createDirectories(mediaDir);

        String safeKey = fileKey.replaceAll("[^a-zA-Z0-9_]", "");
        if (safeKey.isEmpty()) safeKey = "file";

        String ext = extensionFor(contentType, fileNameHint);
        String resolvedFileName = (fileNameHint != null && !fileNameHint.isBlank())
                ? fileNameHint
                : (safeKey + "." + ext);

        Path filePath = mediaDir.resolve(messageId + "_" + safeKey + "." + ext);
        Files.write(filePath, bytes);
        log.debug("[feishu] Downloaded resource to: {} ({} bytes, {})",
                filePath, bytes.length, contentType);

        // Best-effort cache push so the UI / agent can reference the file
        // via a tokenless URL. Failure here doesn't kill the inbound path —
        // the local path on its own is still useful for vision tools.
        String fileUrl = null;
        if (generatedFileCache != null) {
            try {
                String id = generatedFileCache.put(bytes, resolvedFileName, contentType);
                fileUrl = "/api/v1/files/generated/" + id;
            } catch (Exception cacheEx) {
                log.debug("[feishu] cache put failed: {}", cacheEx.getMessage());
            }
        }
        return new DownloadedResource(filePath.toAbsolutePath().toString(),
                fileUrl, resolvedFileName, contentType);
    }

    /** Map content-type / file-name hint to a short extension for the on-disk filename. */
    // Package-private for unit tests.
    static String extensionFor(String contentType, String fileNameHint) {
        if (contentType != null) {
            String ct = contentType.toLowerCase(java.util.Locale.ROOT);
            if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg";
            if (ct.contains("png")) return "png";
            if (ct.contains("gif")) return "gif";
            if (ct.contains("webp")) return "webp";
            if (ct.contains("pdf")) return "pdf";
            if (ct.contains("opus")) return "opus";
            if (ct.contains("mpeg") || ct.contains("mp3")) return "mp3";
            if (ct.contains("mp4")) return "mp4";
            if (ct.contains("wordprocessing") || ct.contains("msword")) return "docx";
            if (ct.contains("spreadsheet") || ct.contains("excel")) return "xlsx";
            if (ct.contains("presentation") || ct.contains("powerpoint")) return "pptx";
        }
        if (fileNameHint != null && fileNameHint.contains(".")) {
            String hint = fileNameHint.substring(fileNameHint.lastIndexOf('.') + 1)
                    .toLowerCase(java.util.Locale.ROOT);
            if (!hint.isBlank()) return hint;
        }
        return "bin";
    }

    /**
     * Best-effort MIME guess from a filename — the SDK download response
     * doesn't expose Content-Type, so we fall back to the extension. Good
     * enough for the cache (used for the {@code Content-Type} header the
     * UI sets when re-serving) and for vision providers (most key on
     * extension anyway).
     */
    // Package-private for unit tests.
    static String inferMimeFromName(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".opus")) return "audio/opus";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        return "application/octet-stream";
    }

    // ==================== 消息发送 ====================

    /**
     * Maps a Feishu target identifier to the corresponding
     * {@code receive_id_type} query parameter expected by the Open API.
     * <p>
     * Targets prefixed with {@code "ou_"} are user open_ids; everything else
     * (group chat_ids start with {@code "oc_"}, but any non-{@code ou_} value
     * is treated as a chat_id by default) is sent with
     * {@code receive_id_type=chat_id}. Keeping this routing in one place
     * keeps {@link #sendOneTextChunk}, {@link #sendCard},
     * {@link #sendFeishuMedia} and {@link #proactiveSend} from drifting —
     * silently misrouting the text-fallback after a failed card send to an
     * individual was the previous regression.
     */
    private static String resolveReceiveIdType(String targetId) {
        return targetId != null && targetId.startsWith("ou_") ? "open_id" : "chat_id";
    }

    /**
     * Conservative per-message char ceiling. Feishu's documented limit is on
     * the encoded JSON body (~150KB), but UTF-8 Chinese is 3 bytes/char and
     * JSON escape adds further overhead, so a 4000-char chunk is comfortably
     * under any realistic limit and also gives readable IM chunking instead
     * of one wall of text. Split at paragraph / line boundaries when possible.
     */
    static final int MAX_TEXT_MESSAGE_CHARS = 4000;

    // ==================== StreamingChannelAdapter ====================

    /**
     * Stream consumption strategy:
     * <ul>
     *   <li>{@code card_streaming_enabled=true} (default) + manager wired →
     *       create a {@code cardkit/v1} streaming card, throttle-update its
     *       markdown element on every delta, finalise on completion. The
     *       receiver sees text appearing character-by-character like a
     *       chat-app's typing animation.</li>
     *   <li>Manager missing OR card creation fails OR config opts out →
     *       fall back to {@link #processStreamAsText}: accumulate every
     *       chunk and send one final regular message.</li>
     * </ul>
     */
    @Override
    public String processStream(Flux<StreamDelta> stream, ChannelMessage message, String conversationId) {
        if (!isCardStreamingEnabled() || streamingCardManager == null
                || channelEntity == null) {
            return processStreamAsText(stream, message);
        }

        String receiveId = pickReceiveId(message);
        if (receiveId == null) {
            log.warn("[feishu-stream] No usable receive id on message; falling back to text mode");
            return processStreamAsText(stream, message);
        }
        String receiveIdType = resolveReceiveIdType(receiveId);

        String sessionKey = streamingCardManager.createAndDeliver(
                channelEntity.getId(), receiveIdType, receiveId, null);
        if (sessionKey == null) {
            log.info("[feishu-stream] Card create/deliver failed; falling back to text mode");
            return processStreamAsText(stream, message);
        }

        StringBuilder accumulator = new StringBuilder();
        try {
            stream.doOnNext(delta -> {
                        if (delta.content() != null) {
                            accumulator.append(delta.content());
                            streamingCardManager.appendContent(sessionKey, delta.content(), false);
                        }
                    })
                    .doOnError(err -> {
                        log.error("[feishu-stream] stream error: sessionKey={}, err={}",
                                sessionKey, err.getMessage());
                        streamingCardManager.failCard(sessionKey, err.getMessage());
                    })
                    .blockLast(Duration.ofMinutes(5));

            String finalContent = accumulator.toString();
            if (finalContent.isBlank()) {
                finalContent = "（无回复内容）";
            }
            // Strip any /api/v1/files/generated/{id} URLs out of the card
            // text (replacing each with a "📎 filename" marker) AND send
            // each cache-resolved file as a native Feishu attachment. The
            // streaming card can only render markdown — without this hop
            // the user sees a broken-looking download link instead of the
            // actual file. Cache-miss URLs fall back to the user-facing
            // retry hint that GeneratedFileScrubber emits.
            String renderedContent = scrubAndSendAttachments(receiveId, finalContent);
            streamingCardManager.finishCard(sessionKey, renderedContent);
            log.info("[feishu-stream] Card streaming completed: sessionKey={}, contentLen={}",
                    sessionKey, renderedContent.length());
            return finalContent;

        } catch (Exception e) {
            log.error("[feishu-stream] Card streaming failed: sessionKey={}, err={}",
                    sessionKey, e.getMessage(), e);
            streamingCardManager.failCard(sessionKey, e.getMessage());

            // Tag returned content with the "[错误] " prefix so
            // ChannelMessageRouter.isErrorReply flips status='error' on the
            // persisted row and BaseAgent.sanitizeForLlm filters it out of
            // the next turn's history. Without this, partial streaming
            // output (e.g. LLM 400'd mid-stream) would re-enter the prompt
            // as a valid assistant turn and re-trigger the same 400.
            String partial = accumulator.toString();
            String errorPrefix = "[错误] Feishu CardKit streaming failed: " + e.getMessage();
            if (!partial.isBlank()) {
                return errorPrefix + "\n\n（已生成的部分内容，已忽略）\n" + partial;
            }
            throw new RuntimeException(errorPrefix, e);
        }
    }

    /**
     * Streaming fallback — accumulate all deltas, then send through the
     * existing {@link #sendMessage} path so the message goes out as a
     * regular text bubble (auto-upgraded to a non-streaming card by the
     * existing {@code card_format} logic when content looks card-worthy).
     */
    private String processStreamAsText(Flux<StreamDelta> stream, ChannelMessage message) {
        StringBuilder accumulator = new StringBuilder();
        stream.doOnNext(delta -> {
                    if (delta.content() != null) {
                        accumulator.append(delta.content());
                    }
                })
                .blockLast(Duration.ofMinutes(5));
        String finalContent = accumulator.toString();
        if (!finalContent.isBlank()) {
            String replyTarget = message.getReplyToken() != null
                    ? message.getReplyToken()
                    : (message.getChatId() != null ? message.getChatId() : message.getSenderId());
            if (replyTarget != null) {
                // Same scrub-and-upload hop as the streaming card finish path —
                // a generated-file URL in plain text would otherwise reach the
                // user as a markdown link that opens to nothing useful in IM.
                String renderedContent = scrubAndSendAttachments(replyTarget, finalContent);
                sendMessage(replyTarget, renderedContent);
            }
        }
        return finalContent;
    }

    /**
     * Run {@link GeneratedFileScrubber} over outbound text. For every
     * {@code /api/v1/files/generated/{id}} URL the cache resolves, send
     * the bytes as a native Feishu attachment to {@code targetId} via
     * the existing upload path. Returns the rewritten text (URLs replaced
     * with {@code "📎 filename"} markers for hits, retry hints for
     * cache-misses) so the caller can use it for the text bubble / card.
     *
     * <p>No-op when scrubber / uploader / channel entity is missing —
     * returns input unchanged so legacy callers (3-arg ctor, tests)
     * continue to behave like the pre-scrubber adapter. The cost of the
     * regex scan on a normal reply with no generated URLs is one
     * Matcher.find() that returns false, negligible.
     */
    // Package-private so tests can call directly without driving the full
    // streaming pipeline. Production callers are processStream and
    // processStreamAsText, both internal.
    String scrubAndSendAttachments(String targetId, String content) {
        if (content == null || content.isEmpty()
                || generatedFileScrubber == null
                || mediaUploader == null
                || channelEntity == null
                || channelEntity.getId() == null) {
            return content;
        }
        GeneratedFileScrubber.ScrubResult scrubbed = generatedFileScrubber.scrub(content);
        Long channelId = channelEntity.getId();
        for (GeneratedFileScrubber.AttachmentHit hit : scrubbed.attachments()) {
            uploadAndSendAttachment(targetId, channelId,
                    new MediaSource.Bytes(hit.bytes()),
                    hit.fileName(),
                    hit.mediaType(),
                    hit.mimeType(),
                    null);
        }
        return scrubbed.rewrittenText();
    }

    /** Resolve the best id to receive a streaming card — prefer reply token, then chat, then sender. */
    private static String pickReceiveId(ChannelMessage message) {
        if (message == null) return null;
        if (message.getReplyToken() != null && !message.getReplyToken().isBlank()) {
            return message.getReplyToken();
        }
        if (message.getChatId() != null && !message.getChatId().isBlank()) {
            return message.getChatId();
        }
        if (message.getSenderId() != null && !message.getSenderId().isBlank()) {
            return message.getSenderId();
        }
        return null;
    }

    private boolean isCardStreamingEnabled() {
        // Default true — streaming cards are the better UX when CardKit is
        // available. Operators can flip card_streaming_enabled=false in
        // configJson to fall back to the text path (useful for debugging
        // or when targeting an old Feishu tenant that hasn't rolled out
        // CardKit v1 universally).
        return getConfigBoolean("card_streaming_enabled", true);
    }

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

        String cardFormat = getConfigString("card_format", "auto");

        if ("never".equals(cardFormat)) {
            splitTextForFeishu(content, MAX_TEXT_MESSAGE_CHARS)
                    .forEach(c -> sendOneTextChunk(targetId, c));
            return;
        }

        FeishuCardFormatter.ContentFormat fmt = FeishuCardFormatter.detect(content);

        boolean preferCard = "always".equals(cardFormat) || fmt != FeishuCardFormatter.ContentFormat.PLAIN_TEXT;
        if (preferCard) {
            String cardHeader = getConfigString("card_header", FeishuCardFormatter.DEFAULT_MARKDOWN_HEADER);
            boolean sent = sendCard(targetId, FeishuCardFormatter.render(content, fmt, cardHeader));
            if (sent) return;
            // Card path bailed (oversized payload, null guard, or network error) —
            // fall through to text so the user doesn't get nothing.
        }
        splitTextForFeishu(content, MAX_TEXT_MESSAGE_CHARS)
                .forEach(c -> sendOneTextChunk(targetId, c));
    }

    private void sendOneTextChunk(String targetId, String content) {
        String apiBase = getApiBaseUrl();
        String receiveIdType = resolveReceiveIdType(targetId);
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
                log.warn("[feishu] Send message failed: status={}, body={}", response.statusCode(), response.body());
            } else {
                log.debug("[feishu] Message sent to {}={} ({} chars)", receiveIdType, targetId, content.length());
            }

        } catch (Exception e) {
            log.error("[feishu] Failed to send message: {}", e.getMessage(), e);
        }
    }

    /**
     * Feishu rejects interactive messages whose {@code content} payload exceeds
     * roughly 30 KB once stringified. We compare the serialized card against a
     * slightly tighter ceiling and fall back to plain text rather than letting
     * the request error out at the API.
     */
    static final int MAX_CARD_CONTENT_BYTES = 30_000;

    /**
     * Posts an Interactive Card. Returns {@code true} when the card was sent
     * (HTTP 2xx), {@code false} when a pre-flight check rejected the call
     * (null inputs, channel stopped, payload oversized) or the HTTP request
     * itself failed — letting {@link #sendMessage(String, String)} fall back
     * to plain text instead of going silent.
     *
     * <p>{@code targetId} format follows {@link #resolveReceiveIdType}:
     * {@code "ou_"} prefix → open_id (1:1), anything else → chat_id (group).
     */
    public boolean sendCard(String targetId, Map<String, Object> cardJson) {
        if (httpClient == null) {
            log.warn("[feishu] Channel not started, cannot send card");
            return false;
        }
        if (targetId == null || cardJson == null) {
            log.warn("[feishu] sendCard called with null target or card");
            return false;
        }
        ensureTokenValid();
        String apiBase = getApiBaseUrl();
        String receiveIdType = resolveReceiveIdType(targetId);
        try {
            String cardContent = objectMapper.writeValueAsString(cardJson);
            int cardBytes = cardContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (cardBytes > MAX_CARD_CONTENT_BYTES) {
                log.warn("[feishu] Card content {} bytes exceeds {} byte limit, falling back to text",
                        cardBytes, MAX_CARD_CONTENT_BYTES);
                return false;
            }
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "receive_id", targetId,
                    "msg_type", "interactive",
                    "content", cardContent
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/open-apis/im/v1/messages?receive_id_type=" + receiveIdType))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + tenantAccessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[feishu] Send card failed: status={}, body={}", response.statusCode(), response.body());
                return false;
            }
            log.debug("[feishu] Card sent to {} (type={})", targetId, receiveIdType);
            return true;
        } catch (Exception e) {
            log.error("[feishu] Failed to send card: {}", e.getMessage(), e);
            return false;
        }
    }

    public void updateCard(String messageId, Map<String, Object> cardJson) {
        if (httpClient == null) {
            log.warn("[feishu] Channel not started, cannot update card");
            return;
        }
        if (messageId == null || cardJson == null) {
            log.warn("[feishu] updateCard called with null messageId or card");
            return;
        }
        ensureTokenValid();
        String apiBase = getApiBaseUrl();
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "msg_type", "interactive",
                    "content", objectMapper.writeValueAsString(cardJson)
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/open-apis/im/v1/messages/" + messageId))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + tenantAccessToken)
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[feishu] Update card failed: status={}, body={}", response.statusCode(), response.body());
            } else {
                log.debug("[feishu] Card updated: messageId={}", messageId);
            }
        } catch (Exception e) {
            log.error("[feishu] Failed to update card: {}", e.getMessage(), e);
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
        if (parts == null || parts.isEmpty()) return;

        ensureTokenValid();

        // Carry an explicit channelId for media uploads. Available on
        // the entity since CRUD; only null in degenerate test stubs.
        Long channelId = channelEntity != null ? channelEntity.getId() : null;

        for (MessageContentPart part : parts) {
            if (part == null) continue;
            try {
                switch (part.getType()) {
                    case "text" -> handleTextPart(targetId, channelId, part);
                    case "refusal" -> {
                        String refusal = part.getText();
                        if (refusal != null && !refusal.isBlank()) {
                            sendMessage(targetId, "⚠️ " + refusal);
                        }
                    }
                    case "image" -> handleMediaPart(targetId, channelId, part, "image", "image.jpg");
                    case "file" -> handleMediaPart(targetId, channelId, part, "file", "file.bin");
                    case "audio" -> handleMediaPart(targetId, channelId, part, "audio", "voice_reply.opus");
                    case "video" -> handleMediaPart(targetId, channelId, part, "video", "video.mp4");
                    default -> {
                        if (part.getText() != null) sendMessage(targetId, part.getText());
                    }
                }
            } catch (Exception e) {
                log.error("[feishu] Failed to send content part ({}): {}", part.getType(), e.getMessage());
            }
        }
    }

    /**
     * Handle one text part: if the scrubber is wired, rewrite any
     * {@code /api/v1/files/generated/{id}} URL to a {@code "📎 filename"}
     * marker and upload the cached bytes as a native attachment; if not,
     * fall back to sending the text as-is (legacy behavior).
     */
    private void handleTextPart(String targetId, Long channelId, MessageContentPart part) {
        String text = part.getText() != null ? part.getText() : "";
        if (generatedFileScrubber == null || mediaUploader == null || channelId == null) {
            sendMessage(targetId, text);
            return;
        }
        GeneratedFileScrubber.ScrubResult scrubbed = generatedFileScrubber.scrub(text);
        sendMessage(targetId, scrubbed.rewrittenText());
        for (GeneratedFileScrubber.AttachmentHit hit : scrubbed.attachments()) {
            uploadAndSendAttachment(targetId, channelId,
                    new MediaSource.Bytes(hit.bytes()),
                    hit.fileName(),
                    hit.mediaType(),
                    hit.mimeType(),
                    null);
        }
    }

    /**
     * Handle one image / file / audio / video part. If {@code mediaId} is
     * already a Feishu key (image_key / file_key) — e.g. echoed back
     * from an earlier inbound message — send directly. Otherwise build a
     * {@link MediaSource} from {@code path} / {@code fileUrl} /
     * {@code text}(base64 not yet supported here) and upload via the SDK
     * uploader first.
     */
    private void handleMediaPart(String targetId, Long channelId, MessageContentPart part,
                                 String mediaType, String defaultFileName) {
        // 1. mediaId is already a Feishu key → send straight away
        String existingKey = part.getMediaId();
        if (existingKey != null && (existingKey.startsWith("img_") || existingKey.startsWith("file_"))) {
            String keyField = "image".equals(mediaType) ? "image_key" : "file_key";
            sendFeishuMedia(targetId, mediaType, Map.of(keyField, existingKey));
            return;
        }
        if (mediaUploader == null || channelId == null) {
            sendFallbackText(targetId, part, mediaType);
            return;
        }
        MediaSource source = resolveSource(part);
        if (source == null) {
            sendFallbackText(targetId, part, mediaType);
            return;
        }
        String fileName = part.getFileName() != null ? part.getFileName() : defaultFileName;
        uploadAndSendAttachment(targetId, channelId, source, fileName,
                mediaType, part.getContentType(), null);
    }

    /**
     * Resolve a {@link MessageContentPart}'s payload source — prefer
     * already-on-disk path, fall back to fileUrl. Returns {@code null}
     * when neither is usable (e.g. the part only carries a placeholder).
     */
    private static MediaSource resolveSource(MessageContentPart part) {
        if (part.getPath() != null && !part.getPath().isBlank()) {
            java.nio.file.Path p = java.nio.file.Path.of(part.getPath());
            if (java.nio.file.Files.exists(p)) {
                return new MediaSource.LocalPath(p);
            }
        }
        String url = part.getFileUrl();
        if (url != null && !url.isBlank()) {
            return new MediaSource.RemoteUrl(url);
        }
        return null;
    }

    /**
     * Upload via the SDK-backed uploader and dispatch the resulting
     * key as a Feishu media message. Surfaces any rejection /
     * downgrade note as a follow-up text bubble so users understand
     * why a bubble isn't native.
     */
    // Package-private + non-final so tests can override and capture upload
    // attempts without standing up a real Feishu HTTP client.
    void uploadAndSendAttachment(String targetId, Long channelId,
                                  MediaSource source, String fileName,
                                  String mediaType, String contentType,
                                  Integer durationMillis) {
        try {
            MediaUploadResult result = mediaUploader.upload(new MediaUploadRequest(
                    channelId, source, fileName, mediaType, contentType, durationMillis));
            String finalType = result.finalMediaType();
            String keyField = "image".equals(finalType) ? "image_key" : "file_key";
            sendFeishuMedia(targetId, finalType, Map.of(keyField, result.mediaId()));
            if (result.downgradeNote() != null) {
                sendMessage(targetId, "ℹ️ " + result.downgradeNote());
            }
        } catch (MediaUploadException e) {
            log.warn("[feishu] Upload rejected for {} ({}): {}", fileName, mediaType, e.getMessage());
            sendMessage(targetId, "⚠️ " + e.getMessage());
        } catch (Exception e) {
            log.error("[feishu] Upload failed for {} ({}): {}", fileName, mediaType, e.getMessage(), e);
            sendMessage(targetId, "⚠️ 附件 " + fileName + " 发送失败：" + e.getMessage());
        }
    }

    /**
     * Last-ditch text representation when neither a Feishu key nor a
     * usable source could be derived from the part. Keeps the bubble
     * informative instead of swallowing the message.
     */
    private void sendFallbackText(String targetId, MessageContentPart part, String mediaType) {
        StringBuilder sb = new StringBuilder("⚠️ 无法发送 ");
        sb.append(mediaType);
        if (part.getFileName() != null) sb.append("：").append(part.getFileName());
        if (part.getFileUrl() != null) sb.append(" (").append(part.getFileUrl()).append(")");
        sendMessage(targetId, sb.toString());
    }

    private void sendFeishuMedia(String targetId, String msgType, Map<String, Object> content) {
        String apiBase = getApiBaseUrl();
        String receiveIdType = resolveReceiveIdType(targetId);
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "receive_id", targetId,
                    "msg_type", msgType,
                    "content", objectMapper.writeValueAsString(content)
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/open-apis/im/v1/messages?receive_id_type=" + receiveIdType))
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
        String receiveIdType = resolveReceiveIdType(targetId);

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
