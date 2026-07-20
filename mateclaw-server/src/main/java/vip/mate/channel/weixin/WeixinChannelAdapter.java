package vip.mate.channel.weixin;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.ExponentialBackoff;
import vip.mate.channel.media.InboundMediaDownloader;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.weixin.error.TokenExpiredException;
import vip.mate.common.security.SecretEquals;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信个人号渠道适配器 — 基于 iLink Bot HTTP API
 * <p>
 * 微信个人号渠道实现（基于 iLink Bot HTTP API）：
 * <ul>
 *   <li>HTTP 长轮询接收消息（getupdates，服务端最长 35s）</li>
 *   <li>HTTP POST 发送消息（sendmessage）</li>
 *   <li>Bearer Token 认证（可通过 QR 码扫码登录获取）</li>
 *   <li>支持 text(1), image(2), voice/ASR(3), file(4), video(5) 消息类型</li>
 *   <li>基于 context_token 的消息去重和主动推送</li>
 * </ul>
 * <p>
 * 会话 ID 规则：
 * <ul>
 *   <li>私聊：weixin:{fromUserId}</li>
 *   <li>群聊：weixin:group:{groupId}</li>
 * </ul>
 * <p>
 * configJson 配置项：
 * <ul>
 *   <li>bot_token: iLink Bot Token（扫码登录获取）</li>
 *   <li>base_url: API 基础地址（默认 https://ilinkai.weixin.qq.com）</li>
 *   <li>media_download_enabled: 是否下载媒体文件（默认 true）</li>
 *   <li>media_dir: 媒体文件保存目录（默认 data/media）</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
public class WeixinChannelAdapter extends AbstractChannelAdapter {

    public static final String CHANNEL_TYPE = "weixin";

    /** 消息去重最大记录数 */
    private static final int PROCESSED_IDS_MAX = 2000;

    // ==================== 运行时状态 ====================

    private ILinkClient client;

    /** 长轮询线程 */
    private volatile Thread pollThread;

    /** 停止信号 */
    private final AtomicBoolean stopSignal = new AtomicBoolean(false);

    /** 长轮询游标 */
    private volatile String cursor = "";

    /**
     * RFC-024 Change 5：pollLoop 错误重试专用退避器。
     * 3s 起步、60s 上限、1.8 倍递增、±20% jitter、无限重试。
     * 成功一次 getUpdates 即 reset()。
     */
    private final ExponentialBackoff pollBackoff =
            new ExponentialBackoff(3000, 60000, 1.8, -1, 0.2);

    /**
     * RFC-024 Change 4：pollLoop watchdog。虚拟线程调度，每 30s 检查一次活跃度。
     * 由 {@link #startWatchdog()} 启动，{@link #stopWatchdog()} 关闭。
     */
    private volatile ScheduledExecutorService watchdogScheduler;
    private volatile ScheduledFuture<?> watchdogTask;

    /**
     * pollLoop 卡死判定阈值（毫秒）。getUpdates 最长 45s 就该回包一次；
     * 超过此值说明客户端或代理层有问题，主动置 ERROR 让 HealthMonitor 重启。
     * 默认 90s（45s × 2 缓冲）。
     */
    private static final long POLL_STUCK_THRESHOLD_MS = 90_000;
    private static final long WATCHDOG_INTERVAL_MS = 30_000;

    /** 消息去重集合（LRU） */
    private final LinkedHashMap<String, Boolean> processedIds = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > PROCESSED_IDS_MAX;
        }
    };

    /** 用户最新 context_token 缓存（用于主动推送） */
    private final ConcurrentHashMap<String, String> userContextTokens = new ConcurrentHashMap<>();

    /** context_token 持久化文件路径 */
    private Path contextTokensFile;

    /** bot_token 持久化文件路径 */
    private Path botTokenFile;

    /** 文件名扩展名列表（用于过滤纯文件名文本，避免误触发 Agent） */
    private static final Set<String> FILENAME_EXTENSIONS = Set.of(
            ".txt", ".doc", ".docx", ".pdf", ".jpg", ".jpeg", ".png", ".gif",
            ".mp4", ".avi", ".mov", ".mp3", ".wav", ".zip", ".rar",
            ".xlsx", ".xls", ".ppt", ".pptx", ".csv", ".json", ".xml"
    );

    // ==================== 输入中提示 ====================

    /** 输入提示 ticket 缓存：userId -> (ticket, expireTime) */
    private final ConcurrentHashMap<String, TypingTicketEntry> typingTickets = new ConcurrentHashMap<>();

    /** 输入提示刷新任务：userId -> ScheduledFuture */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> typingTasks = new ConcurrentHashMap<>();

    /** 输入提示调度器 */
    private ScheduledExecutorService typingScheduler;

    /** Typing ticket 缓存 24 小时 */
    private static final long TYPING_TICKET_TTL_MS = 24 * 60 * 60 * 1000L;

    /** 输入提示刷新间隔 5 秒 */
    private static final long TYPING_REFRESH_INTERVAL_MS = 5_000;

    private record TypingTicketEntry(String ticket, long expireAt) {
        boolean isValid() { return !ticket.isBlank() && System.currentTimeMillis() < expireAt; }
    }

    // ==================== 文件上传 ====================

    /** 用于文件 URL 下载的 HttpClient */
    private HttpClient uploadHttpClient;

    /**
     * Workspace/agent-aware upload-root resolver, set by the production factory.
     * Null in unit tests (the legacy {@code data/chat-uploads} default applies).
     */
    private vip.mate.workspace.core.service.ChatUploadLocationResolver chatUploadLocationResolver;

    /**
     * Channel-shared scrubber that converts agent-emitted
     * {@code /api/v1/files/generated/{id}} URLs into native WeChat attachments.
     * Nullable for legacy callers / unit tests — when null, the URL passes
     * through unchanged (legacy text-only behaviour).
     */
    private final vip.mate.channel.media.GeneratedFileScrubber generatedFileScrubber;

    public WeixinChannelAdapter(ChannelEntity channelEntity,
                                ChannelMessageRouter messageRouter,
                                ObjectMapper objectMapper) {
        super(channelEntity, messageRouter, objectMapper);
        this.generatedFileScrubber = null;
    }

    /**
     * Full constructor used by the production factory (ChannelManager). The
     * trailing {@code chatUploadLocationResolver} enables workspace/agent-aware
     * attachment storage; {@code null} keeps the legacy {@code data/chat-uploads}
     * behaviour. The {@code generatedFileScrubber} upgrades
     * {@code /api/v1/files/generated/{id}} URLs in agent replies into native
     * WeChat file/image attachments.
     */
    public WeixinChannelAdapter(ChannelEntity channelEntity,
                                ChannelMessageRouter messageRouter,
                                ObjectMapper objectMapper,
                                vip.mate.workspace.core.service.ChatUploadLocationResolver chatUploadLocationResolver,
                                vip.mate.channel.media.GeneratedFileScrubber generatedFileScrubber) {
        super(channelEntity, messageRouter, objectMapper);
        this.chatUploadLocationResolver = chatUploadLocationResolver;
        this.generatedFileScrubber = generatedFileScrubber;
    }

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    // ==================== 生命周期 ====================

    @Override
    protected void doStart() {
        // 初始化持久化路径
        String dataDir = getConfigString("data_dir", "data/weixin");
        Path dataDirPath = Path.of(dataDir, String.valueOf(channelEntity.getId()));
        botTokenFile = dataDirPath.resolve("bot_token.txt");
        contextTokensFile = dataDirPath.resolve("context_tokens.json");

        // bot_token 优先级：config > 持久化文件
        String botToken = getConfigString("bot_token", "");
        if (botToken.isBlank()) {
            botToken = loadBotTokenFromFile();
        }
        String baseUrl = getConfigString("base_url", ILinkClient.DEFAULT_BASE_URL);

        if (botToken.isBlank()) {
            throw new RuntimeException("weixin: bot_token is required. Please scan QR code to obtain one.");
        }

        client = new ILinkClient(botToken, baseUrl, objectMapper);
        uploadHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        typingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "weixin-typing-" + channelEntity.getId());
            t.setDaemon(true);
            return t;
        });

        // 加载持久化的 context_tokens（用于重启后主动推送）
        loadContextTokens();

        // 持久化 bot_token（QR 登录后或首次启动时保存）
        saveBotTokenToFile(botToken);

        // 启动长轮询线程
        stopSignal.set(false);
        cursor = "";
        pollBackoff.reset();                   // RFC-024 Change 5: 每次启动从 3s 起步
        touchActivity();                       // RFC-024 Change 4: watchdog 基准点
        pollThread = new Thread(this::pollLoop, "weixin-poll-" + channelEntity.getId());
        pollThread.setDaemon(true);
        pollThread.start();

        // RFC-024 Change 4: 启动 pollLoop watchdog
        startWatchdog();

        log.info("[weixin] Channel started: {} (token={}..., cached_contexts={})",
                channelEntity.getName(),
                botToken.substring(0, Math.min(12, botToken.length())),
                userContextTokens.size());
    }

    @Override
    protected void doStop() {
        stopSignal.set(true);

        // RFC-024 Change 4: 关闭 watchdog（在中断 pollThread 之前，避免最后一次 tick 误判）
        stopWatchdog();

        // 持久化 context_tokens（重启后可恢复主动推送能力）
        saveContextTokens();

        // 停止所有输入提示任务
        typingTasks.values().forEach(f -> f.cancel(false));
        typingTasks.clear();
        typingTickets.clear();
        if (typingScheduler != null) {
            typingScheduler.shutdownNow();
            typingScheduler = null;
        }

        if (pollThread != null) {
            pollThread.interrupt();
            try {
                pollThread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pollThread = null;
        }
        client = null;
        uploadHttpClient = null;
        log.info("[weixin] Channel stopped: {}", channelEntity.getName());
    }

    // ==================== 长轮询循环 ====================

    private void pollLoop() {
        log.info("[weixin] Poll thread started");
        while (!stopSignal.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Map<String, Object> data = client.getUpdates(cursor);

                // RFC-024 Change 1: getUpdates 成功返回（哪怕没消息）= 连接活跃；
                // 让 ChannelHealthMonitor 能准确识别"连接还在线"而非依赖用户发消息
                touchActivity();
                // RFC-024 Change 5: 成功即清零退避计数，下次故障仍从 3s 起步
                pollBackoff.reset();

                // 更新游标
                Object newCursor = data.get("get_updates_buf");
                if (newCursor != null) {
                    cursor = newCursor.toString();
                }

                // 处理消息
                Object msgsObj = data.get("msgs");
                if (msgsObj instanceof List<?> msgs) {
                    for (Object msgObj : msgs) {
                        if (msgObj instanceof Map<?, ?> msg) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> msgMap = (Map<String, Object>) msg;
                                handleInboundMessage(msgMap);
                            } catch (Exception e) {
                                log.error("[weixin] Failed to handle message: {}", e.getMessage(), e);
                            }
                        }
                    }
                }

                // ret=-1 是正常的长轮询超时（无新消息）
                Object retObj = data.get("ret");
                int ret = retObj instanceof Number n ? n.intValue() : -1;
                if (ret != 0 && (msgsObj == null || ((List<?>) msgsObj).isEmpty())) {
                    if (ret != -1) {
                        log.warn("[weixin] getUpdates non-zero ret={}, retry in 3s", ret);
                        Thread.sleep(3000);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (TokenExpiredException te) {
                // RFC-024 Change 3: token 已过期 → 停止轮询、标记 ERROR，让 HealthMonitor 接手
                // 不在 catch Exception 里被吞，避免"无限重试 + 日志淹没但用户不知道要重扫码"
                log.error("[weixin] bot_token expired (HTTP {}) during {}; stopping poll loop — channel needs re-scan",
                        te.getHttpStatus(), te.getOperation());
                connectionState.set(ConnectionState.ERROR);
                lastError = "bot_token expired, please re-scan QR code";
                break;
            } catch (Exception e) {
                if (!stopSignal.get()) {
                    // RFC-024 Change 5: 指数退避 + jitter（替代固定 5s），防止连锁故障时雷群效应
                    long delay = pollBackoff.nextDelayMs();
                    log.error("[weixin] Poll error (attempt {}), retry in {}ms: {}",
                            pollBackoff.getAttempts(), delay, e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.info("[weixin] Poll thread stopped");
    }

    // ==================== RFC-024 Change 4: pollLoop watchdog ====================

    /**
     * 启动 pollLoop 监视器：每 30s 检查一次"距离上次活跃是否超过 {@value #POLL_STUCK_THRESHOLD_MS}ms"。
     *
     * <p>getUpdates 最多 45s 就会返回（服务端 hold 35s + 少量网络延迟）；若超过 90s 没有活动，
     * 意味着 HTTP 客户端的长连接被代理 / NAT 静默 FIN 掉、pollLoop 卡在 read 上了。
     * 此时主动把 state 置 ERROR，{@code ChannelHealthMonitor} 下一轮（1 分钟内）会重启本渠道，
     * 缩短用户感知的"僵死时间"。</p>
     *
     * <p>用虚拟线程 ScheduledExecutorService，开销极小；与 pollLoop 完全独立，失败隔离。</p>
     */
    private void startWatchdog() {
        watchdogScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("weixin-watchdog-" + channelEntity.getId()).factory());
        watchdogTask = watchdogScheduler.scheduleAtFixedRate(
                this::watchdogTick,
                WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void watchdogTick() {
        if (stopSignal.get()) return;
        if (connectionState.get() != ConnectionState.CONNECTED) return;   // 已 ERROR，等 HealthMonitor
        long sinceLast = System.currentTimeMillis() - lastEventTimeMs.get();
        if (sinceLast > POLL_STUCK_THRESHOLD_MS) {
            log.warn("[weixin] Watchdog: poll thread appears stuck ({}s since last activity); " +
                    "setting ERROR state for HealthMonitor to restart", sinceLast / 1000);
            connectionState.set(ConnectionState.ERROR);
            lastError = "poll thread stuck, last activity " + (sinceLast / 1000) + "s ago";
        }
    }

    private void stopWatchdog() {
        if (watchdogTask != null) {
            watchdogTask.cancel(false);
            watchdogTask = null;
        }
        if (watchdogScheduler != null) {
            watchdogScheduler.shutdownNow();
            watchdogScheduler = null;
        }
    }

    /**
     * RFC-024 Change 2：微信是长轮询，代理/NAT 的 idle timeout 通常 2–5 分钟；
     * 这里报告 5 分钟作为 stale 阈值，配合 {@code ChannelHealthMonitor} 1 分钟扫描，
     * 断连后最多 5 分钟内被自动重启，而非原先的 60 分钟。
     */
    @Override
    public Duration stalenessThreshold() {
        return Duration.ofMinutes(5);
    }

    // ==================== 入站消息处理 ====================

    @SuppressWarnings("unchecked")
    private void handleInboundMessage(Map<String, Object> msg) {
        String fromUserId = getStr(msg, "from_user_id");
        String toUserId = getStr(msg, "to_user_id");
        String contextToken = getStr(msg, "context_token");
        String groupId = getStr(msg, "group_id");
        int msgType = msg.get("message_type") instanceof Number n ? n.intValue() : 0;

        // 只处理用户→机器人消息 (message_type == 1)
        if (msgType != 1) {
            return;
        }

        // 去重
        String dedupKey = !contextToken.isBlank() ? contextToken
                : fromUserId + "_" + getStr(msg, "msg_id");
        synchronized (processedIds) {
            if (processedIds.containsKey(dedupKey)) {
                log.debug("[weixin] Duplicate message skipped: {}", dedupKey.substring(0, Math.min(40, dedupKey.length())));
                return;
            }
            processedIds.put(dedupKey, Boolean.TRUE);
        }

        // 解析消息内容
        List<MessageContentPart> contentParts = new ArrayList<>();
        List<String> textParts = new ArrayList<>();
        boolean hasVoice = false;

        List<Map<String, Object>> itemList = (List<Map<String, Object>>) msg.getOrDefault("item_list", List.of());
        boolean mediaDownloadEnabled = getConfigBoolean("media_download_enabled", true);
        // Inbound conversation id (see class doc): private "weixin:{user}",
        // group "weixin:group:{group}". Downloaded media is stored under
        // data/chat-uploads/{convId} so the /api/v1/chat/files endpoint can
        // serve it back to the chat bubble / Web mirror.
        String inboundConvId = !groupId.isBlank()
                ? "weixin:group:" + groupId
                : "weixin:" + fromUserId;

        for (Map<String, Object> item : itemList) {
            int itemType = item.get("type") instanceof Number n ? n.intValue() : 0;

            switch (itemType) {
                case 1 -> {
                    // Text — 过滤纯文件名文本，避免文件名误触发 Agent
                    Map<String, Object> textItem = (Map<String, Object>) item.getOrDefault("text_item", Map.of());
                    String text = getStr(textItem, "text").strip();
                    if (!text.isEmpty() && !isFilenameOnly(text)) {
                        textParts.add(text);
                    }
                }
                case 2 -> {
                    // Image
                    if (mediaDownloadEnabled) {
                        InboundMediaDownloader.DownloadedMedia dl =
                                downloadMediaItem(item, "image_item", null, inboundConvId);
                        if (dl != null) {
                            MessageContentPart part = new MessageContentPart();
                            part.setType("image");
                            part.setPath(dl.localPath().toString());
                            part.setStoredName(dl.storedName());
                            part.setFileUrl(dl.fileUrl());
                            part.setMediaId(dl.localPath().toString());
                            part.setFileName(dl.fileName());
                            // Use the sniffed MIME (image/png, image/webp, image/heic, …)
                            // so vision gateways get an accurate Content-Type. Fall back
                            // to a concrete jpeg only when sniffing was inconclusive.
                            part.setContentType(dl.isImage() ? dl.contentType() : "image/jpeg");
                            part.setFileSize(dl.fileSize());
                            contentParts.add(part);
                        } else {
                            // 下载失败，尝试构建 CDN URL
                            String cdnUrl = buildCdnUrl(item, "image_item");
                            if (cdnUrl != null) {
                                contentParts.add(MessageContentPart.image(cdnUrl, cdnUrl));
                            } else {
                                textParts.add("[图片: 下载失败]");
                            }
                        }
                    } else {
                        // 未启用下载，但仍然传递 CDN URL（供多模态分析）
                        String cdnUrl = buildCdnUrl(item, "image_item");
                        if (cdnUrl != null) {
                            contentParts.add(MessageContentPart.image(cdnUrl, cdnUrl));
                        } else {
                            textParts.add("[图片]");
                        }
                    }
                }
                case 3 -> {
                    // Voice — 使用 ASR 语音识别文本
                    // iLink API 的 ASR 文本可能在两个位置：
                    //   路径1: voice_item.text_item.text（嵌套结构）
                    //   路径2: voice_item.text（直接结构）
                    hasVoice = true;
                    Map<String, Object> voiceItem = (Map<String, Object>) item.getOrDefault("voice_item", Map.of());
                    String asrText = "";

                    // 路径1: voice_item → text_item → text
                    Object textItemObj = voiceItem.get("text_item");
                    if (textItemObj instanceof Map<?,?> textItemMap) {
                        asrText = getStr((Map<String, Object>) textItemMap, "text").strip();
                    }

                    // 路径2: voice_item → text（直接字段，部分版本 API 的兜底结构）
                    if (asrText.isEmpty()) {
                        asrText = getStr(voiceItem, "text").strip();
                    }

                    // 路径3: voice_item → content（与 WeCom 一致的字段名）
                    if (asrText.isEmpty()) {
                        asrText = getStr(voiceItem, "content").strip();
                    }

                    log.debug("[weixin] Voice item payload: {}", voiceItem);

                    if (!asrText.isEmpty()) {
                        textParts.add(asrText);
                        log.info("[weixin] Voice ASR text: {}", asrText.length() > 50
                                ? asrText.substring(0, 50) + "..." : asrText);
                    } else {
                        // ASR 为空：可能是语音过短、噪音、或 iLink API 字段变更
                        // 尝试下载语音文件保存到本地（供后续调试 / 自有 STT 使用）
                        if (mediaDownloadEnabled) {
                            InboundMediaDownloader.DownloadedMedia dl =
                                    downloadMediaItem(item, "voice_item", null, inboundConvId);
                            if (dl != null) {
                                // Persist as an audio content part even without ASR text
                                MessageContentPart audioPart = new MessageContentPart();
                                audioPart.setType("audio");
                                audioPart.setPath(dl.localPath().toString());
                                audioPart.setStoredName(dl.storedName());
                                audioPart.setFileUrl(dl.fileUrl());
                                audioPart.setMediaId(dl.localPath().toString());
                                audioPart.setFileName(dl.fileName());
                                audioPart.setContentType(dl.contentType());
                                audioPart.setFileSize(dl.fileSize());
                                contentParts.add(audioPart);
                                log.info("[weixin] Voice audio downloaded (no ASR): {}", dl.localPath());
                            }
                        }
                        textParts.add("[语音消息]");
                        log.warn("[weixin] Voice message with no ASR result. voice_item keys: {}, full: {}",
                                voiceItem.keySet(), voiceItem);
                    }
                }
                case 4 -> {
                    // File
                    Map<String, Object> fileItemMap = (Map<String, Object>) item.getOrDefault("file_item", Map.of());
                    String fileName = getStr(fileItemMap, "file_name");
                    if (fileName.isBlank()) fileName = "file.bin";
                    if (mediaDownloadEnabled) {
                        InboundMediaDownloader.DownloadedMedia dl =
                                downloadMediaItem(item, "file_item", fileName, inboundConvId);
                        if (dl != null) {
                            MessageContentPart part = new MessageContentPart();
                            part.setType("file");
                            part.setPath(dl.localPath().toString());
                            part.setStoredName(dl.storedName());
                            part.setFileUrl(dl.fileUrl());
                            part.setMediaId(dl.localPath().toString());
                            part.setFileName(dl.fileName());
                            part.setContentType(dl.contentType());
                            part.setFileSize(dl.fileSize());
                            contentParts.add(part);
                        } else {
                            textParts.add("[文件: " + fileName + " 下载失败]");
                        }
                    } else {
                        textParts.add("[文件: " + fileName + "]");
                    }
                }
                case 5 -> {
                    // Video
                    if (mediaDownloadEnabled) {
                        InboundMediaDownloader.DownloadedMedia dl =
                                downloadMediaItem(item, "video_item", null, inboundConvId);
                        if (dl != null) {
                            MessageContentPart part = new MessageContentPart();
                            part.setType("video");
                            part.setPath(dl.localPath().toString());
                            part.setStoredName(dl.storedName());
                            part.setFileUrl(dl.fileUrl());
                            part.setMediaId(dl.localPath().toString());
                            part.setFileName(dl.fileName());
                            part.setContentType(dl.isVideo() ? dl.contentType() : "video/mp4");
                            part.setFileSize(dl.fileSize());
                            contentParts.add(part);
                        } else {
                            // 尝试构建 CDN URL
                            String cdnUrl = buildCdnUrl(item, "video_item");
                            if (cdnUrl != null) {
                                MessageContentPart part = new MessageContentPart();
                                part.setType("video");
                                part.setFileUrl(cdnUrl);
                                part.setContentType("video/*");
                                contentParts.add(part);
                            } else {
                                textParts.add("[视频: 下载失败]");
                            }
                        }
                    } else {
                        textParts.add("[视频]");
                    }
                }
                default -> textParts.add("[不支持的消息类型: " + itemType + "]");
            }
        }

        // 组装文本
        String textContent = String.join("\n", textParts).strip();
        if (!textContent.isEmpty()) {
            contentParts.addFirst(MessageContentPart.text(textContent));
        }
        if (contentParts.isEmpty()) {
            return;
        }

        // 缓存 context_token（用于主动推送）并定期持久化
        if (!fromUserId.isBlank() && !contextToken.isBlank()) {
            String prev = userContextTokens.put(fromUserId, contextToken);
            // token 变更时才持久化（减少 I/O）
            // RFC-025 Change 2: 常数时间比较，作为秘钥类字符串比较的模板统一
            if (!SecretEquals.equals(contextToken, prev)) {
                saveContextTokens();
            }
        }

        // 构建统一消息
        boolean isGroup = !groupId.isBlank();
        String chatId = isGroup ? groupId : null;
        // replyToken 存储 contextToken + fromUserId，格式: contextToken|fromUserId
        String replyToken = contextToken + "|" + fromUserId;

        ChannelMessage channelMessage = ChannelMessage.builder()
                .messageId(getStr(msg, "msg_id"))
                .channelType(CHANNEL_TYPE)
                .senderId(fromUserId)
                .senderName(fromUserId) // iLink API 不提供昵称
                .chatId(chatId)
                .content(textContent)
                .contentType(contentParts.size() == 1 && "text".equals(contentParts.getFirst().getType()) ? "text" : "mixed")
                .contentParts(contentParts)
                .inputMode(hasVoice ? "voice" : "text")
                .timestamp(LocalDateTime.now())
                .replyToken(replyToken)
                .rawPayload(msg)
                .build();

        log.info("[weixin] Recv: from={} group={} text_len={}",
                fromUserId.length() > 20 ? fromUserId.substring(0, 20) : fromUserId,
                groupId.length() > 20 ? groupId.substring(0, 20) : groupId,
                textContent.length());

        // 启动"输入中..."提示
        startTyping(fromUserId, contextToken);

        onMessage(channelMessage);
    }

    // ==================== 媒体下载 ====================

    /**
     * Download an inbound media item via the shared media pipeline (retry +
     * backoff, magic-byte type detection, dedup-named persistence). The iLink
     * AES key extraction stays here because it is protocol-specific; the
     * decrypted bytes are handed to {@link InboundMediaDownloader}.
     *
     * @return the stored, type-detected file, or {@code null} on failure
     */
    @SuppressWarnings("unchecked")
    private InboundMediaDownloader.DownloadedMedia downloadMediaItem(
            Map<String, Object> item, String itemKey, String filenameHint, String conversationId) {
        Map<String, Object> mediaItem = (Map<String, Object>) item.getOrDefault(itemKey, Map.of());
        Map<String, Object> media = (Map<String, Object>) mediaItem.getOrDefault("media", Map.of());
        String encryptQueryParam = getStr(media, "encrypt_query_param");

        // image_item carries a top-level hex aeskey; other items use media.aes_key
        final String aesKey;
        String aeskeyHex = getStr(mediaItem, "aeskey");
        if (!aeskeyHex.isBlank()) {
            aesKey = Base64.getEncoder().encodeToString(hexToBytes(aeskeyHex));
        } else {
            aesKey = getStr(media, "aes_key");
        }

        if (encryptQueryParam.isBlank()) {
            log.warn("[weixin] No encrypt_query_param for media download");
            return null;
        }

        Path uploadDir = (chatUploadLocationResolver != null)
                ? chatUploadLocationResolver.resolveConversationDir(conversationId)
                : Path.of("data", "chat-uploads", ChatUploadLocationResolver.sanitizeSegment(conversationId));
        return InboundMediaDownloader.download(
                        () -> client.downloadMedia("", aesKey, encryptQueryParam),
                        filenameHint,
                        uploadDir,
                        "weixin",
                        encryptQueryParam,
                        storedName -> "/api/v1/chat/files/" + conversationId + "/" + storedName)
                .orElse(null);
    }

    // ==================== 发送消息 ====================

    @Override
    public void sendMessage(String targetId, String content) {
        if (client == null || content == null || content.isBlank()) {
            return;
        }
        try {
            // targetId 格式: contextToken|userId
            String[] parts = targetId.split("\\|", 2);
            String contextToken = parts.length > 0 ? parts[0] : "";
            String toUserId = parts.length > 1 ? parts[1] : "";

            if (toUserId.isBlank() || contextToken.isBlank()) {
                log.warn("[weixin] Cannot send: missing userId or contextToken in targetId");
                return;
            }

            // 发送前停止输入提示，发送后重新启动（模拟连续输入）
            stopTyping(toUserId);
            client.sendText(toUserId, content, contextToken);
        } catch (Exception e) {
            log.error("[weixin] Failed to send message: {}", e.getMessage(), e);
        }
    }

    @Override
    public void sendContentParts(String targetId, List<MessageContentPart> parts) {
        if (client == null || parts == null || parts.isEmpty()) {
            return;
        }

        String[] split = targetId.split("\\|", 2);
        String contextToken = split.length > 0 ? split[0] : "";
        String toUserId = split.length > 1 ? split[1] : "";

        if (toUserId.isBlank() || contextToken.isBlank()) {
            log.warn("[weixin] sendContentParts: missing userId or contextToken");
            return;
        }

        // 停止输入提示
        stopTyping(toUserId);

        // 文本 part 里可能携带 /api/v1/files/generated/{id} URL，需先 scrub。
        // 收集所有命中的附件，待文本全部发完后再统一推送（保持"文本在前，附件在后"的顺序）
        List<vip.mate.channel.media.GeneratedFileScrubber.AttachmentHit> deferredAttachments =
                new java.util.ArrayList<>();

        for (MessageContentPart part : parts) {
            if (part == null) continue;
            try {
                switch (part.getType()) {
                    case "text" -> {
                        if (part.getText() != null && !part.getText().isBlank()) {
                            String textToSend = part.getText();
                            if (generatedFileScrubber != null) {
                                vip.mate.channel.media.GeneratedFileScrubber.ScrubResult scrubbed =
                                        generatedFileScrubber.scrub(part.getText());
                                textToSend = scrubbed.rewrittenText();
                                deferredAttachments.addAll(scrubbed.attachments());
                            }
                            if (!textToSend.isBlank()) {
                                client.sendText(toUserId, textToSend, contextToken);
                            }
                        }
                    }
                    case "image" -> sendImagePart(toUserId, contextToken, part);
                    case "audio" -> sendAudioPart(toUserId, contextToken, part);
                    case "file" -> sendFilePart(toUserId, contextToken, part);
                    case "video" -> sendVideoPart(toUserId, contextToken, part);
                    default -> {
                        if (part.getText() != null && !part.getText().isBlank()) {
                            client.sendText(toUserId, part.getText(), contextToken);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[weixin] Failed to send content part ({}): {}", part.getType(), e.getMessage());
                // 降级为文本
                sendFallbackText(targetId, part);
            }
        }

        // 推送文本 part 里 scrub 出来的原生附件
        sendAttachmentHits(toUserId, contextToken, deferredAttachments);
    }

    @Override
    public void renderAndSend(String targetId, String content) {
        // 停止输入提示
        String[] split = targetId.split("\\|", 2);
        String contextToken = split.length > 0 ? split[0] : "";
        String toUserId = split.length > 1 ? split[1] : "";
        if (!toUserId.isBlank()) {
            stopTyping(toUserId);
        }

        // 扫描 /api/v1/files/generated/{id} URL → 替换为 "📎 filename" 标记 + 收集附件字节
        // 缺失此步会让 LLM 回复里的 URL 作为纯文本发到微信，用户无法点击下载
        List<vip.mate.channel.media.GeneratedFileScrubber.AttachmentHit> attachments = List.of();
        String rewrittenContent = content;
        if (generatedFileScrubber != null && content != null && !content.isBlank()) {
            vip.mate.channel.media.GeneratedFileScrubber.ScrubResult scrubbed =
                    generatedFileScrubber.scrub(content);
            rewrittenContent = scrubbed.rewrittenText();
            attachments = scrubbed.attachments();
            if (!attachments.isEmpty()) {
                log.info("[weixin] renderAndSend: scrubbed {} attachment(s) from content",
                        attachments.size());
            }
        }

        // 调用父类默认渲染逻辑
        boolean filterThinking = getConfigBoolean("filter_thinking", true);
        boolean filterToolMessages = getConfigBoolean("filter_tool_messages", true);
        String format = getConfigString("message_format", "auto");
        int maxLen = vip.mate.channel.ChannelMessageRenderer.PLATFORM_LIMITS.getOrDefault(getChannelType(), 2048);

        List<String> segments = vip.mate.channel.ChannelMessageRenderer.renderForChannel(
                rewrittenContent, filterThinking, filterToolMessages, format, maxLen);
        for (String segment : segments) {
            sendMessage(targetId, segment);
        }

        // 文本发完后，把缓存里的字节作为原生附件推送（与 WeCom/Feishu 行为对齐）
        sendAttachmentHits(toUserId, contextToken, attachments);
    }

    /**
     * 把 {@link GeneratedFileScrubber} 抓取到的附件字节，通过 iLink 原生
     * file/image 通道发送给微信用户。图片走 {@link ILinkClient#sendImage}，
     * 其他类型走 {@link ILinkClient#sendFile}（保留原始 fileName）。
     * 单个附件失败不阻断后续附件，仅记录 error 日志。
     */
    private void sendAttachmentHits(String toUserId, String contextToken,
                                    List<vip.mate.channel.media.GeneratedFileScrubber.AttachmentHit> attachments) {
        if (attachments == null || attachments.isEmpty() || client == null
                || toUserId == null || toUserId.isBlank()
                || contextToken == null || contextToken.isBlank()) {
            log.info("[weixin] sendAttachmentHits skipped: attachments={}, clientReady={}, toUserPresent={}, contextTokenPresent={}",
                    attachments == null ? 0 : attachments.size(), client != null,
                    toUserId != null && !toUserId.isBlank(),
                    contextToken != null && !contextToken.isBlank());
            return;
        }
        log.info("[weixin] sendAttachmentHits begin: count={}, toUser={}, contextTokenPresent={}",
                attachments.size(), toUserId.substring(0, Math.min(12, toUserId.length())),
                !contextToken.isBlank());
        for (vip.mate.channel.media.GeneratedFileScrubber.AttachmentHit hit : attachments) {
            try {
                log.info("[weixin] sendAttachmentHit: mediaType={}, fileName={}, mimeType={}, bytes={}",
                        hit.mediaType(), hit.fileName(), hit.mimeType(),
                        hit.bytes() == null ? 0 : hit.bytes().length);
                if ("image".equals(hit.mediaType())) {
                    client.sendImage(toUserId, hit.bytes(), contextToken);
                    log.info("[weixin] Generated image sent to {}: {} ({}bytes)",
                            toUserId.substring(0, Math.min(12, toUserId.length())),
                            hit.fileName(), hit.bytes().length);
                } else {
                    client.sendFile(toUserId, hit.bytes(), hit.fileName(), contextToken);
                    log.info("[weixin] Generated file sent to {}: {} ({}bytes)",
                            toUserId.substring(0, Math.min(12, toUserId.length())),
                            hit.fileName(), hit.bytes().length);
                }
            } catch (Exception e) {
                log.error("[weixin] Failed to send generated attachment {} to {}: {}",
                        hit.fileName(), toUserId.substring(0, Math.min(12, toUserId.length())),
                        e.getMessage(), e);
            }
        }
    }

    // ==================== 媒体上传发送 ====================

    private void sendImagePart(String toUserId, String contextToken, MessageContentPart part) throws Exception {
        byte[] imageBytes = resolveFileBytes(part);
        if (imageBytes == null) {
            sendFallbackText(contextToken + "|" + toUserId, part);
            return;
        }
        client.sendImage(toUserId, imageBytes, contextToken);
        log.info("[weixin] Image sent to {}: {}bytes", toUserId.substring(0, Math.min(12, toUserId.length())), imageBytes.length);
    }

    private void sendFilePart(String toUserId, String contextToken, MessageContentPart part) throws Exception {
        byte[] fileBytes = resolveFileBytes(part);
        if (fileBytes == null) {
            sendFallbackText(contextToken + "|" + toUserId, part);
            return;
        }
        String fileName = part.getFileName() != null ? part.getFileName() : "file.bin";
        client.sendFile(toUserId, fileBytes, fileName, contextToken);
        log.info("[weixin] File sent to {}: {} ({}bytes)", toUserId.substring(0, Math.min(12, toUserId.length())), fileName, fileBytes.length);
    }

    private void sendVideoPart(String toUserId, String contextToken, MessageContentPart part) throws Exception {
        byte[] videoBytes = resolveFileBytes(part);
        if (videoBytes == null) {
            sendFallbackText(contextToken + "|" + toUserId, part);
            return;
        }
        client.sendVideo(toUserId, videoBytes, contextToken);
        log.info("[weixin] Video sent to {}: {}bytes", toUserId.substring(0, Math.min(12, toUserId.length())), videoBytes.length);
    }

    /**
     * 发送音频部分：以文件形式发送 MP3（用户可点击播放）
     */
    private void sendAudioPart(String toUserId, String contextToken, MessageContentPart part) throws Exception {
        byte[] audioBytes = resolveFileBytes(part);
        if (audioBytes == null) {
            sendFallbackText(contextToken + "|" + toUserId, part);
            return;
        }
        String fileName = part.getFileName() != null ? part.getFileName() : "voice_reply.mp3";
        client.sendVoice(toUserId, audioBytes, fileName, contextToken);
        log.info("[weixin] Audio sent to {}: {} ({}KB)",
                toUserId.substring(0, Math.min(12, toUserId.length())),
                fileName, audioBytes.length / 1024);
    }

    /**
     * 从 MessageContentPart 解析文件字节：优先本地路径，其次 URL 下载
     */
    private byte[] resolveFileBytes(MessageContentPart part) {
        if (part.getPath() != null && !part.getPath().isBlank()) {
            try {
                Path p = Path.of(part.getPath());
                if (Files.exists(p)) {
                    return Files.readAllBytes(p);
                }
            } catch (Exception e) {
                log.debug("[weixin] Failed to read local file {}: {}", part.getPath(), e.getMessage());
            }
        }
        String url = part.getFileUrl();
        if (url != null && !url.isBlank() && uploadHttpClient != null) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET().build();
                HttpResponse<byte[]> resp = uploadHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200) {
                    return resp.body();
                }
            } catch (Exception e) {
                log.debug("[weixin] Failed to download from {}: {}", url, e.getMessage());
            }
        }
        return null;
    }

    private void sendFallbackText(String targetId, MessageContentPart part) {
        switch (part.getType()) {
            case "image" -> sendMessage(targetId, "[图片]");
            case "audio" -> sendMessage(targetId, "[语音回复]");
            case "file" -> sendMessage(targetId, "[文件: " + (part.getFileName() != null ? part.getFileName() : "file") + "]");
            case "video" -> sendMessage(targetId, "[视频]");
            default -> { if (part.getText() != null) sendMessage(targetId, part.getText()); }
        }
    }

    // ==================== 输入中提示 ====================

    /**
     * 启动输入中提示（每 5 秒刷新一次）
     */
    private void startTyping(String userId, String contextToken) {
        if (client == null || userId.isBlank()) return;

        // 先停止旧的
        stopTyping(userId);

        try {
            String ticket = getTypingTicket(userId, contextToken);
            if (ticket == null || ticket.isBlank()) {
                log.debug("[weixin] No typing ticket for user {}", userId.substring(0, Math.min(12, userId.length())));
                return;
            }

            // 立即发送一次
            client.sendTyping(userId, ticket, 1);

            // 定时刷新
            if (typingScheduler != null && !typingScheduler.isShutdown()) {
                ScheduledFuture<?> future = typingScheduler.scheduleAtFixedRate(() -> {
                    try {
                        if (client != null) {
                            client.sendTyping(userId, ticket, 1);
                        }
                    } catch (Exception e) {
                        log.debug("[weixin] Typing refresh failed: {}", e.getMessage());
                    }
                }, TYPING_REFRESH_INTERVAL_MS, TYPING_REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                typingTasks.put(userId, future);
            }

            log.debug("[weixin] Typing started for {}", userId.substring(0, Math.min(12, userId.length())));
        } catch (Exception e) {
            log.debug("[weixin] Failed to start typing: {}", e.getMessage());
        }
    }

    /**
     * 停止输入中提示
     */
    private void stopTyping(String userId) {
        ScheduledFuture<?> future = typingTasks.remove(userId);
        if (future != null) {
            future.cancel(false);
        }

        // 发送停止状态
        TypingTicketEntry entry = typingTickets.get(userId);
        if (entry != null && entry.isValid() && client != null) {
            try {
                client.sendTyping(userId, entry.ticket(), 2);
                log.debug("[weixin] Typing stopped for {}", userId.substring(0, Math.min(12, userId.length())));
            } catch (Exception e) {
                log.debug("[weixin] Failed to stop typing: {}", e.getMessage());
            }
        }
    }

    /**
     * 获取或缓存 typing ticket（24 小时 TTL）
     */
    private String getTypingTicket(String userId, String contextToken) {
        TypingTicketEntry cached = typingTickets.get(userId);
        if (cached != null && cached.isValid()) {
            return cached.ticket();
        }

        try {
            Map<String, Object> configResp = client.getConfig(userId, contextToken);
            int ret = configResp.get("ret") instanceof Number n ? n.intValue() : -1;
            if (ret != 0) {
                log.debug("[weixin] getConfig ret={} for typing ticket", ret);
                return null;
            }
            String ticket = (String) configResp.getOrDefault("typing_ticket", "");
            if (!ticket.isBlank()) {
                typingTickets.put(userId, new TypingTicketEntry(ticket, System.currentTimeMillis() + TYPING_TICKET_TTL_MS));
            }
            return ticket;
        } catch (Exception e) {
            log.debug("[weixin] Failed to get typing ticket: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 主动推送 ====================

    @Override
    public boolean supportsProactiveSend() {
        return true;
    }

    @Override
    public void proactiveSend(String targetId, String content) {
        if (client == null || content == null || content.isBlank()) {
            return;
        }
        try {
            // targetId 可以是 userId 或 weixin:userId
            String userId = targetId;
            if (userId.startsWith("weixin:group:")) {
                userId = userId.substring("weixin:group:".length());
            } else if (userId.startsWith("weixin:")) {
                userId = userId.substring("weixin:".length());
            }

            String contextToken = userContextTokens.get(userId);
            if (contextToken == null || contextToken.isBlank()) {
                log.warn("[weixin] No cached context_token for user {}, cannot proactive send", userId);
                return;
            }

            client.sendText(userId, content, contextToken);
            log.info("[weixin] Proactive message sent to {}: {}chars", userId, content.length());
        } catch (Exception e) {
            log.error("[weixin] Proactive send failed: {}", e.getMessage(), e);
        }
    }

    // ==================== QR 码登录（供 Controller 调用） ====================

    /**
     * 获取 QR 码登录信息
     *
     * @return 包含 qrcode, qrcode_img_content 等字段
     */
    public Map<String, Object> getQrCode() throws Exception {
        String baseUrl = getConfigString("base_url", ILinkClient.DEFAULT_BASE_URL);
        ILinkClient tempClient = new ILinkClient("", baseUrl, objectMapper);
        return tempClient.getBotQrcode();
    }

    /**
     * 查询 QR 码扫码状态
     *
     * @param qrcode QR 码标识
     * @return 状态信息
     */
    public Map<String, Object> getQrCodeStatus(String qrcode) throws Exception {
        String baseUrl = getConfigString("base_url", ILinkClient.DEFAULT_BASE_URL);
        ILinkClient tempClient = new ILinkClient("", baseUrl, objectMapper);
        return tempClient.getQrcodeStatus(qrcode);
    }

    // ==================== 工具方法 ====================

    /**
     * 从消息 item 中构建 CDN 下载 URL（不下载，仅构建 URL 供多模态分析使用）
     */
    @SuppressWarnings("unchecked")
    private String buildCdnUrl(Map<String, Object> item, String itemKey) {
        try {
            Map<String, Object> mediaItem = (Map<String, Object>) item.getOrDefault(itemKey, Map.of());
            Map<String, Object> media = (Map<String, Object>) mediaItem.getOrDefault("media", Map.of());
            String encryptQueryParam = getStr(media, "encrypt_query_param");
            if (encryptQueryParam.isBlank()) return null;

            String cdnBase = "https://novac2c.cdn.weixin.qq.com/c2c";
            return cdnBase + "/download?encrypted_query_param="
                    + java.net.URLEncoder.encode(encryptQueryParam, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("[weixin] Failed to build CDN URL: {}", e.getMessage());
            return null;
        }
    }

    private static String getStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    // ==================== Token 持久化 ====================

    /**
     * 从文件加载 bot_token（启动时如果 config 中无 token，尝试从文件恢复）
     */
    private String loadBotTokenFromFile() {
        if (botTokenFile == null) return "";
        try {
            if (Files.exists(botTokenFile)) {
                String token = Files.readString(botTokenFile).strip();
                if (!token.isBlank()) {
                    log.info("[weixin] Loaded bot_token from {}", botTokenFile);
                    return token;
                }
            }
        } catch (Exception e) {
            log.debug("[weixin] Failed to read bot_token file: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 持久化 bot_token 到文件（QR 登录后或首次启动时保存）
     */
    private void saveBotTokenToFile(String token) {
        if (botTokenFile == null || token == null || token.isBlank()) return;
        try {
            Files.createDirectories(botTokenFile.getParent());
            Files.writeString(botTokenFile, token);
            log.info("[weixin] Bot token saved to {}", botTokenFile);
        } catch (Exception e) {
            log.warn("[weixin] Failed to save bot_token file: {}", e.getMessage());
        }
    }

    /**
     * 从文件加载 context_tokens（启动时恢复主动推送能力）
     */
    @SuppressWarnings("unchecked")
    private void loadContextTokens() {
        if (contextTokensFile == null) return;
        try {
            if (Files.exists(contextTokensFile)) {
                String json = Files.readString(contextTokensFile);
                Map<String, String> data = objectMapper.readValue(json,
                        objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, String.class));
                if (data != null && !data.isEmpty()) {
                    userContextTokens.putAll(data);
                    log.info("[weixin] Loaded {} context_tokens from {}", data.size(), contextTokensFile);
                }
            }
        } catch (Exception e) {
            log.debug("[weixin] Failed to load context_tokens: {}", e.getMessage());
        }
    }

    /**
     * 持久化 context_tokens 到文件（停止时保存 + token 变更时保存）
     */
    private void saveContextTokens() {
        if (contextTokensFile == null || userContextTokens.isEmpty()) return;
        try {
            Files.createDirectories(contextTokensFile.getParent());
            Files.writeString(contextTokensFile,
                    objectMapper.writeValueAsString(new HashMap<>(userContextTokens)));
        } catch (Exception e) {
            log.debug("[weixin] Failed to save context_tokens: {}", e.getMessage());
        }
    }

    // ==================== 文件名过滤 ====================

    /**
     * 判断文本是否仅为文件名（如 "photo.jpg"、"report.pdf"）。
     * 微信发送文件时会同时发一条文本消息包含文件名，这不应触发 Agent 回复。
     */
    private static boolean isFilenameOnly(String text) {
        if (text == null || text.isBlank()) return false;
        // 文件名不应包含换行（多行文本不是纯文件名）
        if (text.contains("\n")) return false;
        String lower = text.strip().toLowerCase();
        return FILENAME_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
