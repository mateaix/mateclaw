package vip.mate.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.ExponentialBackoff;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业微信智能机器人渠道适配器 — WebSocket 长连接模式
 * <p>
 * 基于企业微信「智能机器人」API 长连接协议（wecom-aibot-python-sdk 逆向）：
 * <ul>
 *   <li>WebSocket 连接 wss://openws.work.weixin.qq.com</li>
 *   <li>bot_id + secret 认证（aibot_subscribe 帧）</li>
 *   <li>30 秒心跳（ping 帧）</li>
 *   <li>aibot_msg_callback / aibot_event_callback 消息推送</li>
 *   <li>reply_stream 流式回复（覆盖更新"思考中..."）</li>
 *   <li>send_message 主动推送</li>
 * </ul>
 * <p>
 * 用户在企业微信后台创建「智能机器人」→ 选择「API 模式 → 配置长连接」
 * → 获得 bot_id 和 secret → 填入 MateClaw → 启动即可对话。
 * 无需公网 IP，无需回调 URL。
 * <p>
 * 配置项（configJson）：
 * <ul>
 *   <li>bot_id: 机器人 ID</li>
 *   <li>secret: 机器人 Secret</li>
 *   <li>welcome_text: 欢迎消息（可选）</li>
 *   <li>media_download_enabled: 是否下载媒体文件（默认 true）</li>
 *   <li>media_dir: 媒体文件保存目录（默认 data/media）</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
public class WeComChannelAdapter extends AbstractChannelAdapter {

    public static final String CHANNEL_TYPE = "wecom";

    /** 企业微信智能机器人 WebSocket 地址 */
    private static final String DEFAULT_WS_URL = "wss://openws.work.weixin.qq.com";

    /** 心跳间隔 30 秒 */
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;

    /** 连续未收到 pong 的最大次数（超过则认为连接已死） */
    private static final int MAX_MISSED_PONG = 2;

    /** 回复 ACK 等待超时 5 秒 */
    private static final long REPLY_ACK_TIMEOUT_MS = 5_000;

    /** 消息去重：最大记录数 */
    private static final int PROCESSED_IDS_MAX = 2000;

    // ==================== WebSocket 命令常量 ====================

    private static final String CMD_SUBSCRIBE = "aibot_subscribe";
    private static final String CMD_HEARTBEAT = "ping";
    private static final String CMD_RESPONSE = "aibot_respond_msg";
    private static final String CMD_RESPONSE_WELCOME = "aibot_respond_welcome_msg";
    /**
     * Update an interactive template card. Source-verified against the
     * aibot SDK at {@code aibot/types.py:81} (RESPONSE_UPDATE constant)
     * — used by {@link #updateTemplateCard} to replace a posted card
     * within the 5-second window WeCom enforces after a button click.
     */
    private static final String CMD_RESPONSE_UPDATE = "aibot_respond_update_msg";
    private static final String CMD_SEND_MSG = "aibot_send_msg";
    private static final String CMD_CALLBACK = "aibot_msg_callback";
    private static final String CMD_EVENT_CALLBACK = "aibot_event_callback";

    // ==================== 媒体上传命令常量 ====================

    private static final String CMD_UPLOAD_INIT = "aibot_upload_media_init";
    private static final String CMD_UPLOAD_CHUNK = "aibot_upload_media_chunk";
    private static final String CMD_UPLOAD_FINISH = "aibot_upload_media_finish";

    /** 上传分块大小：512KB */
    private static final int UPLOAD_CHUNK_SIZE = 512 * 1024;

    /** 上传 ACK 超时：30 秒（大文件上传需要更长超时） */
    private static final long UPLOAD_ACK_TIMEOUT_MS = 30_000;

    // ==================== 运行时状态 ====================

    private HttpClient httpClient;
    private volatile WebSocket webSocket;
    private volatile Thread wsThread;

    /** 心跳定时任务 */
    private volatile ScheduledFuture<?> heartbeatFuture;

    /** 连续未收到 pong 的计数 */
    private final AtomicInteger missedPongCount = new AtomicInteger(0);

    /** 消息去重集合 */
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    /** 回复 ACK 等待：reqId -> CompletableFuture（在 reqIdWorker 串行内 put，避免同 reqId 多次发送时撞 key） */
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pendingAcks = new ConcurrentHashMap<>();

    /**
     * Per-reqId 串行回复队列状态。Key=reqId，value 是 {@link ReplyQueueState}
     * 包装的 (queue, closed) 二元组，用来在 idle-close 与 late-offer 之间提供
     * compute-bin-lock 级原子化（RFC-32 §2.4.1 a-2 / R-5 修正）。
     *
     * <p>{@code closed} 是防御性标志位：worker 在 idle compute 退出时会把 entry
     * 从 map 删掉，所以正常路径上 enqueue 看不到一个"closed=true 还在 map 里"的
     * state；保留这个标志为未来重构兜底，避免任何破坏"close = remove entry"
     * 耦合的改动让 silent drop 复活。
     */
    private final ConcurrentHashMap<String, ReplyQueueState> replyQueues = new ConcurrentHashMap<>();

    /**
     * 回复队列 worker 池。volatile + 非 final 是 RFC-32 §2.4.1 a-1 / R-2 修正
     * 的一部分：stop/重连时需要 {@link ExecutorService#shutdownNow()} 中断
     * worker 阻塞中的 {@code queue.poll(60s)}，但 cached pool 一旦 shutdown
     * 就不能重用，所以必须能在 {@link #ensureReplyExecutor()} 里重建。
     */
    private volatile ExecutorService replyExecutor;

    /**
     * Lifecycle gate：控制 {@link #sendFrameWithAck} 是否接受新任务。
     *
     * <p>必须由 <b>transport-ready 信号</b> 触发置 true（即认证成功后的
     * {@link #markReady()}），而不是 executor-ready（{@link #ensureReplyExecutor()}）。
     * 否则会出现"executor 活的、accepting=true、但 webSocket=null"的窗口——
     * worker 调 {@link #sendFrame} 看到 {@code webSocket==null} 就 warn 后默默 return，
     * 让 caller 等 5s 假超时（RFC-32 §2.4.1 a-1 / R-7 修正）。
     */
    private final AtomicBoolean replyQueueAccepting = new AtomicBoolean(false);

    /**
     * Idle-timeout (ms) for the per-reqId worker's {@code queue.poll}.
     * Default 60s in production; tests in the same package may lower
     * this to the millisecond range to surface idle-close vs late-offer
     * races without waiting a real minute (RFC-32 §3.0 S-3 stress).
     *
     * <p><b>Package-private on purpose</b> — not exposed via getter or
     * setter; tests assign it directly. Production code never writes
     * to this field.
     */
    @SuppressWarnings("PackageVisibleField")
    volatile long workerIdleTimeoutMs = 60_000L;

    /**
     * Per-reqId 回复队列状态。
     *
     * @param queue  串行回复任务队列
     * @param closed 防御性 closed 标志（详见 {@link #replyQueues} 注释）
     */
    private record ReplyQueueState(
            LinkedBlockingQueue<ReplyTask> queue,
            AtomicBoolean closed
    ) {
        static ReplyQueueState fresh() {
            return new ReplyQueueState(new LinkedBlockingQueue<>(), new AtomicBoolean(false));
        }
    }

    /** WebSocket 消息碎片缓冲区 */
    private final StringBuilder wsBuffer = new StringBuilder();

    /** 请求 ID 计数器 */
    private final AtomicInteger reqIdCounter = new AtomicInteger(0);

    /** 记录消息中 reqId -> frame 的映射，用于 reply_stream 回复 */
    private final ConcurrentHashMap<String, Map<String, Object>> pendingFrames = new ConcurrentHashMap<>();

    /** 媒体上传串行锁（每个适配器实例同一时间只允许一个上传） */
    private final Semaphore uploadLock = new Semaphore(1);

    /** 回复上下文：replyToken -> (frameReqId, processingStreamId)，用于 sendContentParts 回写 */
    private final ConcurrentHashMap<String, WeComReplyContext> replyContexts = new ConcurrentHashMap<>();

    /**
     * Group-chat reply-slot fallback cache: {@code chatId → most recent
     * inbound frameReqId from that group}.
     * <p>
     * The WeCom AI Bot platform <strong>blocks {@code aibot_send_msg} in
     * group chats</strong> — proactive pushes (cron summaries,
     * image-generation completions, TTS audio, async-task forwards) must
     * ride {@code aibot_respond_msg} bound to <em>some</em> prior frame
     * id. Without this cache every group push silently failed:
     * {@code sendMessageToChat} fell through to {@code aibot_send_msg},
     * the platform rejected it, the user saw nothing.
     * <p>
     * Populated for group inbound frames only — single-chat
     * {@code aibot_send_msg} still works, so we don't need a cached
     * reqId there. {@link #pickGroupReplyReqId(String)} returns the
     * cached id (or null when there's never been a group inbound), and
     * the proactive send paths fall through to {@code aibot_send_msg}
     * when null.
     * <p>
     * Bounded LRU at {@link #LAST_CHAT_REQ_IDS_MAX_SIZE} via insertion-
     * order eviction — long-lived bots in many groups don't unbounded-grow.
     */
    private final ConcurrentHashMap<String, String> lastChatReqIds = new ConcurrentHashMap<>();

    /** Max chat-id entries to keep in {@link #lastChatReqIds} before evicting. */
    private static final int LAST_CHAT_REQ_IDS_MAX_SIZE = 1000;

    private record WeComReplyContext(String frameReqId, String processingStreamId) {}

    /**
     * Single-flight guard for failure signals. JDK WebSocket can fire onClose
     * AND onError for the same outage, plus connect-exception and heartbeat
     * timeout, all routing to the disconnect path. The first one wins; the
     * rest are deduped. Cleared at the start of each new connect attempt and
     * after auth_succeed in markReady().
     */
    private final AtomicBoolean disconnectInflight = new AtomicBoolean(false);

    /**
     * Approval-notification renderer. Held for symmetry with other channel
     * adapters; the WeCom override of {@link #sendApprovalNotice} delegates
     * card rendering to {@link #cardDispatcher} but still uses this service
     * to build the {@link vip.mate.channel.notification.ApprovalNotice}
     * data carrier. Null-tolerant: if Spring DI fails (test contexts), the
     * default text-approval fallback still works.
     */
    @SuppressWarnings("unused")  // consumed via card dispatcher's tool_guard kind in PR-1
    private final vip.mate.channel.notification.ApprovalNotificationService approvalNotificationService;

    /**
     * WeCom interactive-card dispatcher (PR-1).
     *
     * <p>Routes outbound approval notices to a {@code button_interaction}
     * card via tool_guard renderer, and inbound {@code template_card_event}
     * frames to the matching handler by task_id prefix. Null-tolerant for
     * test contexts (the {@link #sendApprovalNotice} override falls back
     * to the abstract-class text path when the dispatcher is missing).
     */
    private final vip.mate.channel.wecom.cards.WeComCardDispatcher cardDispatcher;

    /**
     * Refreshes the "🤔 思考中..." processing-stream chunk every 20s and
     * force-finishes after 180s, so WeCom's server-side stream slot
     * doesn't drop while a long-running agent task is still computing
     * (RFC-32 §2.1.2 / R-7 / B-5). Null-tolerant: if missing (test DI
     * gap), placeholder still appears once but is not refreshed.
     */
    private final WeComKeepaliveScheduler keepaliveScheduler;

    /**
     * In-memory cache of bytes generated by tools like
     * {@code DocxRenderTool} / {@code PptxRenderTool}. The agent emits a
     * {@code /api/v1/files/generated/{id}} URL referencing this cache; the
     * channel layer resolves that URL back to bytes and uploads them as a
     * native WeCom file message so the user actually receives a tappable
     * document instead of an unopenable link. Null-tolerant: if missing
     * (older constructor / test DI gap), URL stays inline as plain markdown
     * which renders as a non-interactive link in the bubble.
     */
    private final vip.mate.tool.document.GeneratedFileCache generatedFileCache;

    public WeComChannelAdapter(ChannelEntity channelEntity,
                               ChannelMessageRouter messageRouter,
                               ObjectMapper objectMapper,
                               vip.mate.channel.notification.ApprovalNotificationService approvalNotificationService,
                               vip.mate.channel.wecom.cards.WeComCardDispatcher cardDispatcher,
                               WeComKeepaliveScheduler keepaliveScheduler) {
        this(channelEntity, messageRouter, objectMapper, approvalNotificationService,
                cardDispatcher, keepaliveScheduler, null);
    }

    public WeComChannelAdapter(ChannelEntity channelEntity,
                               ChannelMessageRouter messageRouter,
                               ObjectMapper objectMapper,
                               vip.mate.channel.notification.ApprovalNotificationService approvalNotificationService,
                               vip.mate.channel.wecom.cards.WeComCardDispatcher cardDispatcher,
                               WeComKeepaliveScheduler keepaliveScheduler,
                               vip.mate.tool.document.GeneratedFileCache generatedFileCache) {
        super(channelEntity, messageRouter, objectMapper);
        this.approvalNotificationService = approvalNotificationService;
        this.cardDispatcher = cardDispatcher;
        this.keepaliveScheduler = keepaliveScheduler;
        this.generatedFileCache = generatedFileCache;
        // Default to 8 bounded attempts (~4 minutes total at 2s..30s exponential)
        // so the UI eventually settles in ERROR instead of getting stuck in
        // RECONNECTING forever. User config still overrides (-1 = infinite).
        int maxAttempts = 8;
        Object val = config.get("max_reconnect_attempts");
        if (val instanceof Number n) {
            maxAttempts = n.intValue();
        } else if (val instanceof String s) {
            try { maxAttempts = Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        this.backoff = new ExponentialBackoff(2000, 30000, 2.0, maxAttempts);
    }

    // ==================== 生命周期 ====================

    @Override
    protected void doStart() {
        String botId = getConfigString("bot_id");
        String secret = getConfigString("secret");

        if (botId == null || botId.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException("WeCom bot channel requires bot_id and secret in configJson");
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Build the reply-queue worker pool BEFORE the WS handshake kicks off, so
        // any inbound auth_succeed → markReady → openReplyQueue path finds a live
        // executor to schedule against. The gate stays closed until markReady runs.
        ensureReplyExecutor();

        connectWebSocket(botId, secret);

        log.info("[wecom] WeCom bot channel initialized: botId={}, maxReconnectAttempts={}",
                botId.length() > 12 ? botId.substring(0, 12) + "..." : botId, backoff.getMaxAttempts());
    }

    @Override
    protected void doStop() {
        releaseConnectionResources("stopped");
        // doStop also clears history that survives reconnects (processedMessageIds)
        processedMessageIds.clear();
        log.info("[wecom] WeCom bot channel stopped");
    }

    @Override
    protected void doReconnect() {
        log.info("[wecom] Reconnecting WebSocket...");
        releaseConnectionResources("reconnecting");

        // releaseConnectionResources nulls httpClient — rebuild a fresh one.
        // This is the core fix: each reconnect starts from a clean SSL/I-O
        // surface, mirroring what manual stop+start has been doing in the field.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Re-arm the reply-queue worker pool BEFORE attempting the new
        // handshake. accepting flag stays false until the new connection's
        // auth_succeed fires markReady → openReplyQueue.
        ensureReplyExecutor();

        String botId = getConfigString("bot_id");
        String secret = getConfigString("secret");
        connectWebSocket(botId, secret);
    }

    /**
     * Release every per-connection resource so a fresh HttpClient + WebSocket
     * are always built next. Shared by doStop (channel teardown) and
     * doReconnect (auto-recovery cycle).
     *
     * <p>Why drop {@code httpClient} too: the JDK HttpClient caches SSL
     * sessions and keeps an async selector loop. After certain WS error paths
     * the SSL session can be poisoned, causing every subsequent handshake to
     * be RST'd by the server ("Remote host terminated the handshake"). Dropping
     * the client forces a clean rebuild — this is exactly what manual
     * "Disable + Enable" did to recover.
     */
    private void releaseConnectionResources(String reason) {
        // ============================================================================
        // RFC-32 §2.4.1 a-3 / R-6 + R-8 修正：必须按 step 0~4 顺序，不是尾部追加。
        // step 0 (replyQueueAccepting=false) 必须在 ws.close()/wsThread.join() 之前；
        // 否则在 ws teardown 期间还会有 keepalive / 最终回复 / proactiveSend 漏进 enqueue。
        // ============================================================================

        // ---- Step 0：先关 lifecycle gate，让任何后续 sendFrameWithAck 立刻 fast-fail ----
        replyQueueAccepting.set(false);

        // ---- 现有的 ws/heartbeat teardown（功能未变；插在 step 0 之后、step 1 之前） ----
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, reason)
                        .orTimeout(2, TimeUnit.SECONDS)
                        .exceptionally(ex -> null)
                        .join();
            } catch (Exception e) {
                log.debug("[wecom] release: ws close: {}", e.getMessage());
            }
            webSocket = null;
        }
        if (wsThread != null) {
            wsThread.interrupt();
            try {
                wsThread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            wsThread = null;
        }

        // ---- Step 1：第一次 drain replyQueues ----
        // forEach 是 weakly-consistent 迭代器，可能错过 step 0 之前刚提交但还没出 compute
        // 的 enqueue —— step 3 会再 drain 一次兜底。
        replyQueues.forEach((rid, state) -> {
            state.closed().set(true);
            ReplyTask t;
            while ((t = state.queue().poll()) != null) {
                if (!t.future().isDone()) {
                    t.future().completeExceptionally(new IllegalStateException("Channel " + reason));
                }
            }
        });

        // ---- Step 2：shutdownNow 中断 worker 阻塞中的 poll(60s) + 拒绝后续 submit ----
        ExecutorService oldExecutor = this.replyExecutor;
        if (oldExecutor != null) {
            oldExecutor.shutdownNow();
            this.replyExecutor = null;
        }

        // ---- Step 3：second drain，捕获 step 1 与 step 2 之间的窗口期残留 ----
        // 此刻 shutdownNow 已经把任何新 fresh state 的 worker 拒掉，drain 是它们唯一退路。
        replyQueues.forEach((rid, state) -> {
            state.closed().set(true);
            ReplyTask t;
            while ((t = state.queue().poll()) != null) {
                if (!t.future().isDone()) {
                    t.future().completeExceptionally(new IllegalStateException("Channel " + reason));
                }
            }
        });
        replyQueues.clear();

        // ---- Step 4：pendingAcks 残留 ----
        pendingAcks.forEach((k, f) -> {
            if (!f.isDone()) {
                f.completeExceptionally(new IllegalStateException("Channel " + reason));
            }
        });
        pendingAcks.clear();

        // ---- 其他 per-connection 状态 ----
        pendingFrames.clear();
        replyContexts.clear();
        streamLastContent.clear();
        if (keepaliveScheduler != null) {
            keepaliveScheduler.shutdownAll();
        }
        missedPongCount.set(0);

        this.httpClient = null;
    }

    // ====================================================================
    // RFC-32 §2.0.5 / §2.4.1 a-1: lifecycle gate plumbing
    // ====================================================================

    /**
     * (Re)build the worker pool. Called from {@link #doStart()} and
     * {@link #doReconnect()}. <b>Does not</b> touch the {@link #replyQueueAccepting}
     * gate — that flag is controlled by the transport-ready signal
     * ({@link #markReady()}). See §2.4.1 a-1 / R-7.
     */
    private void ensureReplyExecutor() {
        if (replyExecutor == null || replyExecutor.isShutdown()) {
            replyExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "wecom-reply");
                t.setDaemon(true);
                return t;
            });
        }
    }

    /**
     * Open the {@link #replyQueueAccepting} lifecycle gate. <b>Only</b>
     * called from {@link #markReady()} after auth_succeed. Until this
     * runs, every {@link #sendFrameWithAck} call fast-fails the caller's
     * future with {@link IllegalStateException}.
     */
    private void openReplyQueue() {
        replyQueueAccepting.set(true);
    }

    /**
     * Per-reqId serial worker. Started lazily by
     * {@link #sendFrameWithAck} when a fresh {@link ReplyQueueState} is
     * created. Exits when:
     * <ul>
     *   <li>queue is idle for 60s and atomically closes via compute
     *       (so any concurrent late-offer is observed and we stay alive)</li>
     *   <li>{@code running} flips to false</li>
     *   <li>worker thread is interrupted (e.g. by
     *       {@link ExecutorService#shutdownNow()})</li>
     * </ul>
     *
     * <p>The compute-based idle-close fixes the TOCTOU race called out
     * in RFC-32 §2.4.1 a-2 / R-5: enqueue's {@code compute} and
     * worker's idle-close {@code compute} share the same bin lock,
     * so offer and remove never interleave on the same key.
     */
    private void reqIdWorker(String reqId, ReplyQueueState state) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            ReplyTask task;
            try {
                task = state.queue().poll(workerIdleTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;  // fall through to drainStateExceptionally + return
            }

            if (task == null) {
                // Atomic close — serialized against sendFrameWithAck.compute on
                // the same reqId by ConcurrentHashMap's bin lock.
                ReplyQueueState afterClose = replyQueues.compute(reqId, (k, current) -> {
                    if (current != state) return current;                 // (c) replaced — defensive exit
                    if (!current.queue().isEmpty()) return current;       // (b) late offer — stay alive
                    current.closed().set(true);                            // (a) truly idle — close
                    return null;                                           // (a) remove entry
                });
                if (afterClose != state) return;  // (a) or (c) — exit
                continue;                          // (b) — keep going
            }

            try {
                pendingAcks.put(reqId, task.future());
                // orTimeout 5s 兜底，whenComplete 在完成时清 pendingAcks。
                // 用 (key, value) 双参 remove 避免误删后续 task 的注册。
                task.future().orTimeout(REPLY_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .whenComplete((r, ex) -> pendingAcks.remove(reqId, task.future()));
                sendFrame(task.frame());
                task.future().join();   // serialize: don't dequeue next until this is done
            } catch (CompletionException ce) {
                // join() 抛的是 orTimeout 注入的异常（典型：TimeoutException）——
                // task.future 已经 complete，无需手动 fail
                log.debug("[wecom] reply task ACK failed for reqId={}: {}", reqId, ce.getCause());
            } catch (Exception e) {
                // sendFrame 同步抛 → ACK 永远不会到 → 必须显式 fail，否则 caller future 永久 pending
                if (!task.future().isDone()) {
                    task.future().completeExceptionally(e);
                }
                pendingAcks.remove(reqId, task.future());
                log.debug("[wecom] reply task send failed for reqId={}: {}", reqId, e.getMessage());
            }
        }

        // running=false / interrupted: mark closed + drain leftover
        state.closed().set(true);
        drainStateExceptionally(reqId, state, "channel stopped");
    }

    /**
     * Drain remaining tasks in a {@link ReplyQueueState} and best-effort
     * remove the entry from {@link #replyQueues}. Used by worker exit
     * paths (running=false / interrupt). For {@link #releaseConnectionResources}
     * the drain is inlined (step 1 / step 3) to keep the ordering proof local.
     */
    private void drainStateExceptionally(String reqId, ReplyQueueState state, String reason) {
        ReplyTask t;
        while ((t = state.queue().poll()) != null) {
            if (!t.future().isDone()) {
                t.future().completeExceptionally(new IllegalStateException(reason));
            }
        }
        replyQueues.remove(reqId, state);
    }

    // ==================== WebSocket 连接 ====================

    /**
     * 在守护线程中建立 WebSocket 连接
     */
    private void connectWebSocket(String botId, String secret) {
        // Fresh attempt — allow new failure signals to register again.
        disconnectInflight.set(false);

        wsThread = new Thread(() -> {
            try {
                log.info("[wecom] WebSocket connecting to {}...", DEFAULT_WS_URL);

                CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .buildAsync(URI.create(DEFAULT_WS_URL), new WeComWebSocketListener());

                webSocket = wsFuture.get(20, TimeUnit.SECONDS);
                log.info("[wecom] WebSocket connected, sending auth...");

                // 发送认证帧
                sendAuth(botId, secret);

            } catch (Exception e) {
                log.error("[wecom] WebSocket connection failed: {}", e.getMessage(), e);
                handleFailure("WebSocket connection failed: " + e.getMessage());
            }
        }, "wecom-ws-" + channelEntity.getId());
        wsThread.setDaemon(true);
        wsThread.start();
    }

    /**
     * Single-flight failure handler. JDK WebSocket can fire onClose AND
     * onError for the same outage, plus connect-exception and heartbeat
     * timeout, all wanting to trigger reconnect. Without dedup this fans out
     * into 2-4 concurrent reconnect attempts, which collide on shared state
     * (httpClient, wsThread) and amplify failure into a storm.
     *
     * <p>Only the first signal of a given outage gets through; later signals
     * are logged at debug level and dropped. The flag is cleared at the start
     * of each new connect attempt and on auth success (markReady).
     */
    private void handleFailure(String reason) {
        if (!disconnectInflight.compareAndSet(false, true)) {
            log.debug("[wecom] handleFailure dedup: {}", reason);
            return;
        }
        if (!running.get()) {
            return;
        }
        onDisconnected(reason);
    }

    /**
     * Suppressed at the framework's call site. {@link AbstractChannelAdapter}
     * invokes this immediately after {@code doReconnect()} returns, but our
     * doReconnect only fire-and-forget schedules an async WS connect — the
     * connection isn't actually ready yet. Letting the framework reset
     * {@code backoff} here causes attempts to stall at #1 forever.
     *
     * <p>Real reset happens in {@link #markReady()} when the WeCom
     * {@code aibot_subscribe} auth response confirms the session is up.
     */
    @Override
    protected void onReconnectSuccess() {
        // intentionally empty
    }

    /**
     * Called when WeCom auth_succeed frame is received — the only point at
     * which the connection is genuinely usable. Resets backoff and clears
     * the failure dedup flag so the next outage (if any) can register.
     *
     * <p>RFC-080 follow-up: also cancels {@code reconnectFuture}. A stale
     * failure signal (e.g. the previous socket's async onError fired AFTER
     * connectWebSocket reset the dedup flag) can schedule a ghost reconnect
     * while this attempt is still in flight. Without canceling, that ghost
     * fires 2s later and tears down the freshly-authenticated connection,
     * producing the self-sustaining loop. Per-listener identity dedup catches
     * most cases; this is the belt-and-suspenders for any signal that still
     * gets through.
     */
    private void markReady() {
        super.onReconnectSuccess();
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
        disconnectInflight.set(false);
        // RFC-32 §2.4.1 a-1 / R-7: only NOW does sendFrameWithAck start
        // accepting tasks — auth_succeed has just been observed and the
        // WS is the canonical "transport ready" anchor.
        openReplyQueue();
    }

    /**
     * WebSocket 监听器：接收消息帧并分发处理。
     *
     * <p>RFC-080 follow-up: dedup by socket identity. The {@code disconnectInflight}
     * flag is per-channel and gets reset by {@link #connectWebSocket} as soon as a
     * new attempt starts. But the previous socket's onClose/onError can fire
     * asynchronously on a JDK HttpClient worker tens of milliseconds AFTER we have
     * already called sendClose+rebuilt and reset the flag. Without per-socket
     * dedup that stale signal slips through, schedules a ghost reconnect, and
     * tears down the freshly-established healthy connection 2s later — producing
     * the self-sustaining 2-second loop observed in the field.
     *
     * <p>Each listener instance compares the {@code WebSocket} argument against
     * {@link #webSocket} and ignores callbacks for sockets that have already
     * been replaced or released.
     */
    private class WeComWebSocketListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            log.debug("[wecom] WebSocket onOpen");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (webSocket != WeComChannelAdapter.this.webSocket) {
                return null;
            }
            wsBuffer.append(data);
            if (last) {
                String fullMessage = wsBuffer.toString();
                wsBuffer.setLength(0);
                handleWebSocketFrame(fullMessage);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (webSocket != WeComChannelAdapter.this.webSocket) {
                return null;
            }
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            wsBuffer.append(new String(bytes));
            if (last) {
                String fullMessage = wsBuffer.toString();
                wsBuffer.setLength(0);
                handleWebSocketFrame(fullMessage);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (webSocket != WeComChannelAdapter.this.webSocket) {
                log.debug("[wecom] stale onClose ignored: code={}, reason={}", statusCode, reason);
                return null;
            }
            log.warn("[wecom] WebSocket closed: code={}, reason={}", statusCode, reason);
            handleFailure("WebSocket closed: code=" + statusCode + ", reason=" + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (webSocket != WeComChannelAdapter.this.webSocket) {
                log.debug("[wecom] stale onError ignored: {}", error.getMessage());
                return;
            }
            log.error("[wecom] WebSocket error: {}", error.getMessage());
            handleFailure("WebSocket error: " + error.getMessage());
        }
    }

    // ==================== 帧处理 ====================

    /**
     * 处理收到的 WebSocket JSON 帧
     */
    @SuppressWarnings("unchecked")
    private void handleWebSocketFrame(String jsonStr) {
        try {
            Map<String, Object> frame = objectMapper.readValue(jsonStr, Map.class);
            String cmd = (String) frame.get("cmd");

            // 消息推送
            if (CMD_CALLBACK.equals(cmd)) {
                handleMessageCallback(frame);
                return;
            }

            // 事件推送
            if (CMD_EVENT_CALLBACK.equals(cmd)) {
                handleEventCallback(frame);
                return;
            }

            // 无 cmd 的帧：认证响应、心跳响应或回复 ACK
            Map<String, Object> headers = (Map<String, Object>) frame.getOrDefault("headers", Map.of());
            String reqId = (String) headers.getOrDefault("req_id", "");

            // 检查是否是回复消息的 ACK
            CompletableFuture<Map<String, Object>> ackFuture = pendingAcks.remove(reqId);
            if (ackFuture != null) {
                Integer errcode = frame.get("errcode") instanceof Number n ? n.intValue() : null;
                if (errcode != null && errcode != 0) {
                    ackFuture.completeExceptionally(new RuntimeException(
                            "Reply ACK error: errcode=" + errcode + ", errmsg=" + frame.get("errmsg")));
                } else {
                    ackFuture.complete(frame);
                }
                return;
            }

            // 认证响应
            if (reqId.startsWith(CMD_SUBSCRIBE)) {
                Integer errcode = frame.get("errcode") instanceof Number n ? n.intValue() : null;
                if (errcode != null && errcode != 0) {
                    log.error("[wecom] Authentication failed: errcode={}, errmsg={}", errcode, frame.get("errmsg"));
                    lastError = "Authentication failed: " + frame.get("errmsg");
                    // RFC-080 §8 follow-up: route auth_succeed errcode!=0 through
                    // the same single-flight failure path the transport-level
                    // failures use. Without this the WS stays open as a zombie
                    // (no heartbeat, no reconnect) and connectionState stays
                    // CONNECTED — the UI then shows "已连接" while no message
                    // can ever arrive. Letting handleFailure run flips the state
                    // to RECONNECTING and (after maxAttempts) to ERROR so the
                    // green dot stops lying.
                    handleFailure("Authentication failed: errcode=" + errcode
                            + ", errmsg=" + frame.get("errmsg"));
                    return;
                }
                log.info("[wecom] Authentication successful");
                missedPongCount.set(0);
                markReady();
                startHeartbeat();
                return;
            }

            // 心跳响应
            if (reqId.startsWith(CMD_HEARTBEAT)) {
                Integer errcode = frame.get("errcode") instanceof Number n ? n.intValue() : null;
                if (errcode != null && errcode != 0) {
                    log.warn("[wecom] Heartbeat ACK error: errcode={}", errcode);
                    return;
                }
                missedPongCount.set(0);
                log.debug("[wecom] Heartbeat ACK received");
                return;
            }

            log.debug("[wecom] Received unknown frame: {}", jsonStr.length() > 200 ? jsonStr.substring(0, 200) : jsonStr);

        } catch (Exception e) {
            log.error("[wecom] Failed to handle WebSocket frame: {}", e.getMessage(), e);
        }
    }

    // ==================== 认证 & 心跳 ====================

    private void sendAuth(String botId, String secret) {
        String reqId = generateReqId(CMD_SUBSCRIBE);
        Map<String, Object> frame = Map.of(
                "cmd", CMD_SUBSCRIBE,
                "headers", Map.of("req_id", reqId),
                "body", Map.of("bot_id", botId, "secret", secret)
        );
        sendFrame(frame);
        log.info("[wecom] Auth frame sent");
    }

    private void startHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        heartbeatFuture = ensureReconnectScheduler().scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            try {
                sendHeartbeat();
            } catch (Exception e) {
                log.warn("[wecom] Heartbeat send failed: {}", e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.debug("[wecom] Heartbeat started (interval={}ms)", HEARTBEAT_INTERVAL_MS);
    }

    private void sendHeartbeat() {
        if (missedPongCount.get() >= MAX_MISSED_PONG) {
            log.warn("[wecom] No heartbeat ACK for {} consecutive pings, connection considered dead",
                    missedPongCount.get());
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(false);
                heartbeatFuture = null;
            }
            handleFailure("Heartbeat timeout: " + missedPongCount.get() + " missed pongs");
            return;
        }

        missedPongCount.incrementAndGet();
        String reqId = generateReqId(CMD_HEARTBEAT);
        sendFrame(Map.of(
                "cmd", CMD_HEARTBEAT,
                "headers", Map.of("req_id", reqId)
        ));
        log.debug("[wecom] Heartbeat sent (missed={})", missedPongCount.get());
    }

    // ==================== 消息接收 ====================

    /**
     * 处理消息推送回调 (aibot_msg_callback)
     */
    @SuppressWarnings("unchecked")
    private void handleMessageCallback(Map<String, Object> frame) {
        try {
            Map<String, Object> body = (Map<String, Object>) frame.getOrDefault("body", Map.of());
            Map<String, Object> headers = (Map<String, Object>) frame.getOrDefault("headers", Map.of());
            String frameReqId = (String) headers.getOrDefault("req_id", "");

            String msgType = (String) body.getOrDefault("msgtype", "");
            Map<String, Object> fromMap = (Map<String, Object>) body.getOrDefault("from", Map.of());
            String senderId = (String) fromMap.getOrDefault("userid", "");
            String chatId = (String) body.getOrDefault("chatid", "");
            String chatType = (String) body.getOrDefault("chattype", "single");
            String msgId = (String) body.getOrDefault("msgid", "");

            // 补充 msgId（如果为空则用 senderId + send_time 合成）
            if (msgId.isBlank()) {
                msgId = senderId + "_" + body.getOrDefault("send_time", System.currentTimeMillis());
            }

            // 消息去重
            if (!msgId.isBlank() && !processedMessageIds.add(msgId)) {
                log.debug("[wecom] Duplicate msgId: {}, skipping", msgId);
                return;
            }
            // 去重集合超限清理
            if (processedMessageIds.size() > PROCESSED_IDS_MAX) {
                int toRemove = processedMessageIds.size() / 2;
                var it = processedMessageIds.iterator();
                while (it.hasNext() && toRemove > 0) { it.next(); it.remove(); toRemove--; }
            }

            // 保存 frame 用于 reply_stream
            pendingFrames.put(frameReqId, frame);

            List<MessageContentPart> contentParts = new ArrayList<>();
            String textContent = null;
            boolean hasVoice = false;

            switch (msgType) {
                case "text" -> {
                    Map<String, Object> textBody = (Map<String, Object>) body.getOrDefault("text", Map.of());
                    textContent = ((String) textBody.getOrDefault("content", "")).trim();
                    if (!textContent.isBlank()) {
                        contentParts.add(MessageContentPart.text(textContent));
                    }
                }
                case "image" -> {
                    Map<String, Object> imgBody = (Map<String, Object>) body.getOrDefault("image", Map.of());
                    String url = (String) imgBody.getOrDefault("url", "");
                    String aesKey = (String) imgBody.getOrDefault("aeskey", "");
                    String inboundConvId = inboundConversationId(senderId, chatId, chatType);
                    if (!url.isBlank()) {
                        contentParts.add(buildInboundImagePart(url, aesKey, msgId, "image.jpg", inboundConvId));
                    }
                    textContent = "[图片]";
                }
                case "voice" -> {
                    hasVoice = true;
                    Map<String, Object> voiceBody = (Map<String, Object>) body.getOrDefault("voice", Map.of());
                    String asrText = ((String) voiceBody.getOrDefault("content", "")).trim();
                    if (!asrText.isBlank()) {
                        contentParts.add(MessageContentPart.text(asrText));
                        textContent = asrText;
                    } else {
                        textContent = "[语音消息]";
                    }
                }
                case "file" -> {
                    Map<String, Object> fileBody = (Map<String, Object>) body.getOrDefault("file", Map.of());
                    String url = (String) fileBody.getOrDefault("url", "");
                    String aesKey = (String) fileBody.getOrDefault("aeskey", "");
                    // WeCom sometimes omits filename for forwarded files. Try a
                    // few fallback keys before giving up to "file.bin"; the
                    // magic-byte sniffer in downloadInboundMedia will fix the
                    // extension either way, but having something user-readable
                    // here keeps the bubble title meaningful.
                    String filename = (String) fileBody.getOrDefault("filename",
                            fileBody.getOrDefault("file_name",
                                    fileBody.getOrDefault("name", "file.bin")));
                    String fileConvId = inboundConversationId(senderId, chatId, chatType);
                    if (!url.isBlank()) {
                        MessageContentPart filePart = buildInboundFilePart(
                                url, aesKey, msgId, filename, fileConvId);
                        contentParts.add(filePart);
                        // Surface the corrected filename (with proper extension)
                        // back into the [文件: X] text marker the agent sees.
                        if (filePart.getFileName() != null && !filePart.getFileName().isBlank()) {
                            filename = filePart.getFileName();
                        }
                    }
                    textContent = "[文件: " + filename + "]";
                }
                case "mixed" -> {
                    Map<String, Object> mixedBody = (Map<String, Object>) body.getOrDefault("mixed", Map.of());
                    List<Map<String, Object>> items = (List<Map<String, Object>>) mixedBody.getOrDefault("msg_item", List.of());
                    StringBuilder textBuilder = new StringBuilder();
                    for (Map<String, Object> item : items) {
                        String itemType = (String) item.getOrDefault("msgtype", "");
                        if ("text".equals(itemType)) {
                            Map<String, Object> t = (Map<String, Object>) item.getOrDefault("text", Map.of());
                            String txt = ((String) t.getOrDefault("content", "")).trim();
                            if (!txt.isBlank()) {
                                textBuilder.append(txt).append('\n');
                            }
                        } else if ("image".equals(itemType)) {
                            // 与独立 image 消息对齐：下载 + AES 解密
                            Map<String, Object> img = (Map<String, Object>) item.getOrDefault("image", Map.of());
                            String url = (String) img.getOrDefault("url", "");
                            String aesKey = (String) img.getOrDefault("aeskey", "");
                            String mixedConvId = inboundConversationId(senderId, chatId, chatType);
                            if (!url.isBlank()) {
                                contentParts.add(buildInboundImagePart(
                                        url, aesKey, msgId, "mixed_image.jpg", mixedConvId));
                            }
                        } else if ("voice".equals(itemType)) {
                            Map<String, Object> v = (Map<String, Object>) item.getOrDefault("voice", Map.of());
                            String asrText = ((String) v.getOrDefault("content", "")).trim();
                            if (!asrText.isBlank()) {
                                hasVoice = true;
                                textBuilder.append(asrText).append('\n');
                            }
                        }
                    }
                    textContent = textBuilder.toString().trim();
                    if (!textContent.isBlank()) {
                        contentParts.add(0, MessageContentPart.text(textContent));
                    }
                }
                case "appmsg" -> {
                    // Forwarded WeCom complex messages: PDF / Word / Excel
                    // transfers, public-account article links, miniprogram
                    // cards. Without this branch every forwarded PDF /
                    // article / miniprogram fell into default and got
                    // silently dropped — bot looked dumb to users.
                    AppmsgContent appmsg = extractAppmsgContent(body, msgId, senderId, chatId, chatType);
                    if (appmsg.text() != null && !appmsg.text().isBlank()) {
                        textContent = appmsg.text();
                        contentParts.add(0, MessageContentPart.text(textContent));
                    }
                    contentParts.addAll(appmsg.attachedParts());
                }
                default -> {
                    log.debug("[wecom] Ignoring unsupported message type: {}", msgType);
                    return;
                }
            }

            // Apply any quoted-message context. The "quote" field appears at
            // body level alongside the new message regardless of outer
            // msgtype — without parsing it, the agent only sees the user's
            // current text and silently loses the conversational reference
            // ("user quoted the bot's previous image and asked '什么意思'"
            // arrives as bare "什么意思", agent goes off-topic).
            QuoteContext quote = extractQuoteContext(body, msgId, senderId, chatId, chatType);
            if (quote != null && !quote.isEmpty()) {
                String prefixedText = quote.prefix()
                        + (textContent != null && !textContent.isBlank() ? textContent : "");
                textContent = prefixedText;

                // Find an existing text part (text/voice cases produce one)
                // and overwrite it with the prefixed text. Also pull it to
                // the front so agent reading order starts with quote prefix.
                boolean updated = false;
                for (int i = 0; i < contentParts.size(); i++) {
                    if ("text".equals(contentParts.get(i).getType())) {
                        contentParts.set(i, MessageContentPart.text(prefixedText));
                        if (i != 0) {
                            MessageContentPart promoted = contentParts.remove(i);
                            contentParts.add(0, promoted);
                        }
                        updated = true;
                        break;
                    }
                }
                if (!updated) {
                    // image/file/mixed cases without a text part — insert one.
                    contentParts.add(0, MessageContentPart.text(prefixedText));
                }
                // Quoted media (image/file the user referenced) goes right
                // after the text prefix so the agent reads:
                //   prefix → quoted media → user's own media (if any)
                if (!quote.attachedParts().isEmpty()) {
                    contentParts.addAll(1, quote.attachedParts());
                }
                log.debug("[wecom] Applied quote context: prefixLen={}, attachedParts={}",
                        quote.prefix().length(), quote.attachedParts().size());
            }

            if (contentParts.isEmpty()) {
                if (textContent != null && !textContent.isBlank()) {
                    contentParts.add(MessageContentPart.text(textContent));
                } else {
                    return;
                }
            }

            // 发送"🤔 思考中..."处理指示器
            String processingStreamId = "";
            if (textContent != null && !textContent.isBlank()) {
                processingStreamId = generateReqId("stream");
                try {
                    replyStream(frameReqId, processingStreamId, "🤔 思考中...", false);
                } catch (Exception e) {
                    log.debug("[wecom] Failed to send processing indicator: {}", e.getMessage());
                }
            }

            boolean isGroup = "group".equals(chatType);
            String effectiveChatId = isGroup ? chatId : null;

            // Cache the group's most recent inbound frameReqId so future
            // proactive pushes into this group can ride aibot_respond_msg
            // (the AI bot platform blocks aibot_send_msg in groups —
            // without this cache async-task completions and cron summaries
            // silently fail to deliver).
            if (isGroup && chatId != null && !chatId.isBlank()
                    && frameReqId != null && !frameReqId.isBlank()) {
                rememberGroupReplyReqId(chatId, frameReqId);
            }

            // conversationId 格式：wecom:{userid} 或 wecom:group:{chatid}
            // 由 ChannelMessageRouter.buildConversationId() 根据 channelType + chatId/senderId 构建

            ChannelMessage channelMessage = ChannelMessage.builder()
                    .messageId(msgId)
                    .channelType(CHANNEL_TYPE)
                    .senderId(senderId)
                    .senderName(senderId)
                    .chatId(effectiveChatId)
                    .content(textContent != null ? textContent.trim() : "")
                    .contentType(msgType)
                    .contentParts(contentParts)
                    .inputMode(hasVoice ? "voice" : "text")
                    .timestamp(LocalDateTime.now())
                    .replyToken(isGroup ? chatId : senderId)
                    .rawPayload(Map.of(
                            "wecom_frame_req_id", frameReqId,
                            "wecom_processing_stream_id", processingStreamId,
                            "wecom_chat_type", chatType,
                            "wecom_chatid", chatId
                    ))
                    .build();

            // 保存回复上下文，供 sendContentParts / renderAndSend 使用
            String replyToken = isGroup ? chatId : senderId;
            replyContexts.put(replyToken, new WeComReplyContext(frameReqId, processingStreamId));

            // PR-1: launch keepalive for the processing stream so long-running agent
            // tasks (>60s) keep their stream slot alive — without this, WeCom's
            // server-side TTL drops the slot and the eventual real reply gets
            // silently rejected. RFC-32 §2.1.2 / R-7 / B-5.
            if (keepaliveScheduler != null
                    && processingStreamId != null && !processingStreamId.isBlank()) {
                try {
                    keepaliveScheduler.start(this, frameReqId, processingStreamId, replyToken);
                } catch (Exception e) {
                    log.debug("[wecom] keepalive start failed: {}", e.getMessage());
                }
            }

            log.info("[wecom] Received message: sender={}, chatType={}, msgType={}, textLen={}",
                    senderId.length() > 20 ? senderId.substring(0, 20) : senderId,
                    chatType, msgType,
                    textContent != null ? textContent.length() : 0);

            onMessage(channelMessage);

        } catch (Exception e) {
            log.error("[wecom] Failed to handle message callback: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理事件推送回调 (aibot_event_callback)
     */
    @SuppressWarnings("unchecked")
    private void handleEventCallback(Map<String, Object> frame) {
        try {
            Map<String, Object> body = (Map<String, Object>) frame.getOrDefault("body", Map.of());
            Map<String, Object> event = body.get("event") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String eventType = (String) event.getOrDefault("eventtype", "");

            if ("enter_chat".equals(eventType)) {
                String welcomeText = getConfigString("welcome_text", "");
                if (!welcomeText.isBlank()) {
                    try {
                        Map<String, Object> headers = (Map<String, Object>) frame.getOrDefault("headers", Map.of());
                        String reqId = (String) headers.getOrDefault("req_id", "");
                        replyWelcome(reqId, welcomeText);
                        log.info("[wecom] Welcome message sent");
                    } catch (Exception e) {
                        log.warn("[wecom] Failed to send welcome message: {}", e.getMessage());
                    }
                }
                return;
            }

            if ("template_card_event".equals(eventType)) {
                handleTemplateCardEvent(frame, body, event);
                return;
            }

            log.debug("[wecom] Ignoring event type: {}", eventType);
        } catch (Exception e) {
            log.error("[wecom] Failed to handle event callback: {}", e.getMessage(), e);
        }
    }

    /**
     * Route an inbound {@code template_card_event} (a button click on a
     * card we previously sent) to the correct
     * {@link vip.mate.channel.wecom.cards.WeComCardKind} based on the
     * task_id prefix. Each card kind owns its own validation +
     * resolved-state render + command-injection logic.
     *
     * <p><b>5-second window</b>: WeCom requires the
     * {@code aibot_respond_update_msg} for this event to be sent inside
     * 5s. Handlers therefore run synchronously here; the heavy work
     * (e.g. agent re-execution) is deferred to the router's normal
     * processMessage path via {@link #injectSyntheticMessage}.
     */
    @SuppressWarnings("unchecked")
    private void handleTemplateCardEvent(Map<String, Object> frame,
                                         Map<String, Object> body,
                                         Map<String, Object> event) {
        if (cardDispatcher == null) {
            log.debug("[wecom] template_card_event ignored: dispatcher not wired");
            return;
        }
        Map<String, Object> tce = event.get("template_card_event") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : (Map<String, Object>) event;  // some firmware nests directly under event
        String taskId = (String) tce.getOrDefault("task_id", "");
        if (taskId.isBlank()) {
            log.debug("[wecom] template_card_event missing task_id, ignoring");
            return;
        }

        var kindOpt = cardDispatcher.lookupByTaskId(taskId);
        if (kindOpt.isEmpty()) {
            log.warn("[wecom] No registered card kind matches task_id={}, ignoring", taskId);
            return;
        }

        Map<String, Object> fromBlock = body.get("from") instanceof Map<?, ?> fm
                ? (Map<String, Object>) fm
                : Map.of();
        try {
            kindOpt.get().handler().handle(this, frame, tce, fromBlock);
        } catch (Exception e) {
            log.error("[wecom] template_card_event handler ({}) threw: {}",
                    kindOpt.get().name(), e.getMessage(), e);
        }
    }

    // ==================== 消息发送 ====================

    /**
     * Render an approval notice as a WeCom {@code button_interaction}
     * card and post it via the active reply context, instead of the
     * abstract-class default text path.
     *
     * <p>Falls back to {@code super.sendApprovalNotice} (markdown text)
     * in three failure modes:
     * <ol>
     *   <li>No card dispatcher available (DI did not wire it — usually
     *       a test / hot-swap context)</li>
     *   <li>No active {@link WeComReplyContext} for {@code targetId} —
     *       proactive paths (cron without a recent inbound message)
     *       cannot post a card because WeCom AI Bots reject
     *       {@code aibot_send_msg + template_card}; fall back to text
     *       so the user still sees the approval</li>
     *   <li>{@link CardOversizedException} thrown by the renderer
     *       (button.key payload &gt; 1024 bytes)</li>
     * </ol>
     *
     * <p>The card is sent via {@link #replyTemplateCard} bound to the
     * inbound frame's {@code req_id} that
     * {@link #handleMessageCallback} stashed in {@link #replyContexts}.
     */
    @Override
    public boolean usesInteractiveApprovalCards() {
        // Same gate as sendApprovalNotice below — without the dispatcher
        // wired we fall back to the inherited text path, and the router
        // should treat unrelated follow-up messages as implicit deny
        // (existing behaviour).
        return cardDispatcher != null;
    }

    @Override
    public void sendApprovalNotice(String targetId,
            vip.mate.channel.notification.ApprovalNotice notice) {
        if (cardDispatcher == null) {
            super.sendApprovalNotice(targetId, notice);
            return;
        }
        WeComReplyContext ctx = replyContexts.get(targetId);
        if (ctx == null || ctx.frameReqId() == null || ctx.frameReqId().isBlank()) {
            // No bound reply context — fall back to text. Most common in
            // proactive paths (cron-triggered approvals) which WeCom AI
            // Bot rejects for cards anyway.
            super.sendApprovalNotice(targetId, notice);
            return;
        }
        var kindOpt = cardDispatcher.lookupByMessageType(
                vip.mate.channel.wecom.cards.tool_guard.ToolGuardCardKindFactory.MESSAGE_TYPE);
        if (kindOpt.isEmpty()) {
            super.sendApprovalNotice(targetId, notice);
            return;
        }
        try {
            Map<String, Object> card = kindOpt.get().renderer().render(notice);
            replyTemplateCard(ctx.frameReqId(), card);
        } catch (vip.mate.channel.cards.CardOversizedException oversized) {
            log.warn("[wecom] approval card oversized, falling back to text: {}", oversized.getMessage());
            super.sendApprovalNotice(targetId, notice);
        } catch (Exception e) {
            log.warn("[wecom] approval card render/send failed, falling back to text: {}", e.getMessage());
            super.sendApprovalNotice(targetId, notice);
        }
    }

    @Override
    public void sendMessage(String targetId, String content) {
        if (webSocket == null) {
            log.warn("[wecom] Channel not started, cannot send message");
            return;
        }

        // 检查是否有 pending frame（用于 reply_stream 覆盖"思考中..."）
        // sendMessage 被 renderAndSend 调用时，尝试用 reply_stream 覆盖
        // 但由于 rawPayload 信息在 ChannelMessageRouter 层已丢失，
        // 这里走 send_message 主动推送路径
        sendMessageToChat(targetId, content);
    }

    /**
     * 通过 WebSocket send_message 命令主动推送消息。
     * <p>
     * Group fallback: WeCom AI Bot platform rejects {@code aibot_send_msg}
     * in group chats. When {@code chatId} matches a known group (via
     * {@link #pickGroupReplyReqId}), ride a cached inbound reqId via
     * {@code aibot_respond_msg} instead. Single chats still use
     * {@code aibot_send_msg} (which the platform allows).
     */
    private void sendMessageToChat(String chatId, String content) {
        if (webSocket == null || content == null || content.isBlank()) return;
        Map<String, Object> textBody = Map.of(
                "msgtype", "markdown",
                "markdown", Map.of("content", content)
        );
        sendOutboundFrame(chatId, textBody);
    }

    /**
     * Send a body via either {@code aibot_respond_msg} (groups, using a
     * cached inbound reqId) or {@code aibot_send_msg} (single chats).
     * <p>
     * Centralised so {@link #sendMessageToChat} (text) and
     * {@link #sendMediaMessage} (image/file/voice/video) share the same
     * group-vs-single dispatch — without this, every new outbound path
     * had to remember the group rule, and several didn't.
     */
    private void sendOutboundFrame(String chatId, Map<String, Object> bodyWithMsgtype) {
        if (webSocket == null || chatId == null || chatId.isBlank()) return;
        try {
            String groupReplyReqId = pickGroupReplyReqId(chatId);
            if (groupReplyReqId != null) {
                // Group chat — ride aibot_respond_msg with the cached reqId.
                // The body for respond_msg does NOT include "chatid" — the
                // server infers the target from the original frame's reqId.
                Map<String, Object> frame = Map.of(
                        "cmd", CMD_RESPONSE,
                        "headers", Map.of("req_id", groupReplyReqId),
                        "body", bodyWithMsgtype
                );
                sendFrameWithAck(groupReplyReqId, frame);
                log.debug("[wecom] Group send via aibot_respond_msg: chatId={}, reqId={}",
                        chatId, groupReplyReqId);
            } else {
                // Single chat — aibot_send_msg accepts a chatid field.
                Map<String, Object> withChatId = new LinkedHashMap<>(bodyWithMsgtype);
                withChatId.put("chatid", chatId);
                String reqId = generateReqId(CMD_SEND_MSG);
                Map<String, Object> frame = Map.of(
                        "cmd", CMD_SEND_MSG,
                        "headers", Map.of("req_id", reqId),
                        "body", withChatId
                );
                sendFrameWithAck(reqId, frame);
            }
        } catch (Exception e) {
            log.error("[wecom] Failed to send outbound frame to {}: {}", chatId, e.getMessage(), e);
        }
    }

    /**
     * Record the most recent inbound frameReqId for a group chat. Bounded
     * LRU: when over {@link #LAST_CHAT_REQ_IDS_MAX_SIZE} we evict the
     * oldest insertion-order key (ConcurrentHashMap iteration is
     * insertion-order-ish for small maps and good enough — the cache
     * exists to bound memory, not to be perfectly LRU).
     */
    private void rememberGroupReplyReqId(String chatId, String frameReqId) {
        lastChatReqIds.put(chatId, frameReqId);
        while (lastChatReqIds.size() > LAST_CHAT_REQ_IDS_MAX_SIZE) {
            // Pop one entry — any will do for memory bounding.
            var it = lastChatReqIds.keySet().iterator();
            if (it.hasNext()) {
                String victim = it.next();
                lastChatReqIds.remove(victim);
            } else break;
        }
    }

    /**
     * Look up the cached reply reqId for a group chat. Returns null when
     * the chatId isn't a known group (single chats, or no inbound seen
     * yet) — callers fall back to {@code aibot_send_msg}.
     * <p>
     * Package-private for test access.
     */
    String pickGroupReplyReqId(String chatId) {
        if (chatId == null || chatId.isBlank()) return null;
        return lastChatReqIds.get(chatId);
    }

    /**
     * 覆写 renderAndSend：如果有 processing_stream_id 则用 reply_stream 覆盖"思考中..."
     */
    @Override
    public void renderAndSend(String targetId, String content) {
        // 消费回复上下文（如果有的话）
        WeComReplyContext ctx = replyContexts.remove(targetId);
        // Stop keepalive before we send the real reply: avoids racing the next
        // refresh tick against this finish=true chunk on the same stream.
        // No-op if force-finish already evicted the entry.
        if (keepaliveScheduler != null && ctx != null && ctx.processingStreamId() != null) {
            keepaliveScheduler.stop(ctx.processingStreamId());
        }

        // Sniff `/api/v1/files/generated/{id}` URLs out of the agent's text
        // BEFORE rendering. Each hit gets upgraded to a native WeCom file
        // message via the chunked upload API; the URL in the text is replaced
        // with a "📎 filename" marker so the bubble doesn't repeat itself.
        // Without this, a generated docx/pptx would arrive as a markdown link
        // the user can't open inside WeCom (no public access + JWT required).
        List<UploadJob> uploadJobs = new ArrayList<>();
        String rewrittenContent = sniffGeneratedFiles(content, uploadJobs);

        // 先进行正常的内容渲染（过滤 thinking、分割长文本）
        boolean filterThinking = getConfigBoolean("filter_thinking", true);
        boolean filterToolMessages = getConfigBoolean("filter_tool_messages", true);
        String format = getConfigString("message_format", "auto");
        int maxLen = vip.mate.channel.ChannelMessageRenderer.PLATFORM_LIMITS.getOrDefault(getChannelType(), 2048);

        List<String> segments = vip.mate.channel.ChannelMessageRenderer.renderForChannel(
                rewrittenContent, filterThinking, filterToolMessages, format, maxLen);

        boolean first = true;
        for (String rawSegment : segments) {
            // WeCom 专用：格式化 Markdown 表格，统一列宽后在企微渲染正确
            String segment = formatMarkdownTables(rawSegment);
            // 第一条分段用 processingStreamId 覆盖"思考中..."
            if (first && ctx != null && ctx.processingStreamId() != null
                    && !ctx.processingStreamId().isBlank()) {
                replyStream(ctx.frameReqId(), ctx.processingStreamId(), segment, true);
                first = false;
            } else {
                sendMessage(targetId, segment);
            }
        }

        // Upload + dispatch any generated files we sniffed out. Done after the
        // text bubble so the order in the IM client mirrors the markdown:
        // explanatory text first, then the actual file card the user can tap.
        // Use the original frameReqId once for the first attachment (so it
        // rides the reply path) and active-push for the rest.
        if (!uploadJobs.isEmpty()) {
            String frameReqId = ctx != null ? ctx.frameReqId() : null;
            for (int i = 0; i < uploadJobs.size(); i++) {
                UploadJob job = uploadJobs.get(i);
                String mediaId = uploadMedia(job.bytes(), job.fileName(), job.mediaType());
                if (mediaId == null) {
                    log.warn("[wecom] Generated-file upload failed: {} ({} bytes)",
                            job.fileName(), job.bytes().length);
                    continue;
                }
                // Only the first attachment can use the inbound frameReqId
                // reply slot; subsequent attachments must go via active-push.
                String replyReqId = (i == 0) ? frameReqId : null;
                sendMediaMessage(targetId, mediaId, job.mediaType(), replyReqId);
            }
        }
    }

    /** Carries one to-be-uploaded generated file from {@link #sniffGeneratedFiles}. */
    private record UploadJob(byte[] bytes, String fileName, String mediaType) {}

    /**
     * URL pattern for the in-memory generated-file cache served by
     * {@code GeneratedFileController}. Lives in the channel layer because
     * each adapter rewrites the URL to a channel-native attachment.
     */
    private static final java.util.regex.Pattern GENERATED_URL_PATTERN =
            java.util.regex.Pattern.compile("/api/v1/files/generated/([a-zA-Z0-9-]+)");

    /**
     * Scan the agent's text for {@code /api/v1/files/generated/{id}} URLs;
     * for each hit, look up the cached bytes and queue an {@link UploadJob}.
     * Replaces the URL in the returned text with a "📎 filename" marker so
     * the bubble shows the file name without dangling an unopenable link.
     * Cache misses (entry expired or never existed) leave the URL untouched
     * — the user can still try clicking from the Web mirror's history view.
     */
    private String sniffGeneratedFiles(String text, List<UploadJob> jobs) {
        if (text == null || text.isEmpty() || generatedFileCache == null) return text;
        java.util.regex.Matcher m = GENERATED_URL_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String id = m.group(1);
            var entry = generatedFileCache.get(id).orElse(null);
            if (entry != null) {
                String mediaType = isImageMime(entry.mimeType()) ? "image" : "file";
                jobs.add(new UploadJob(entry.bytes(), entry.filename(), mediaType));
                m.appendReplacement(out,
                        java.util.regex.Matcher.quoteReplacement("📎 " + entry.filename()));
            } else {
                // Cache miss has two real-world causes, both surfaced with
                // the same retry hint so the user just resubmits:
                //   1) LLM hallucinated a UUID-shaped string instead of
                //      calling a render tool — IDs like
                //      "a1b2c3d4-e5f6-7890-abcd-ef1234567890" with sequential
                //      hex are textbook fakes. {@code GeneratedFileCache}
                //      logs every real {@code put}, so its absence here is
                //      proof the file was never generated this turn.
                //   2) Cache entry expired (10-min TTL) before the IM
                //      client got around to clicking, or was wiped on
                //      JVM restart.
                // Without this replacement, users tap a markdown link that
                // returns 404 and the IM client saves the error body as a
                // ".docx" — they then "open" what is actually an HTML 404
                // page and report "file is corrupted".
                log.warn("[wecom] Generated-file cache miss for id={} — likely LLM skipped the render tool and wrote a fake URL (toolCallCount=0 in this turn). Bubble will show retry hint.",
                        id);
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                        "⚠️ 文件未真正生成（模型未调用文档生成工具），请重新发送请求"));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean isImageMime(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().startsWith("image/");
    }

    @Override
    public void sendContentParts(String targetId, List<MessageContentPart> parts) {
        WeComReplyContext ctx = replyContexts.remove(targetId);
        if (keepaliveScheduler != null && ctx != null && ctx.processingStreamId() != null) {
            keepaliveScheduler.stop(ctx.processingStreamId());
        }
        boolean sentText = false;
        boolean firstText = true;

        // Mirror renderAndSend's sniff so contentParts-mode replies also
        // upgrade /api/v1/files/generated/{id} URLs into native WeCom file
        // attachments. Text parts get the URL replaced with "📎 filename";
        // the actual bytes are queued for native upload after all parts are
        // dispatched (preserves the "text first, attachments after" ordering).
        List<UploadJob> uploadJobs = new ArrayList<>();

        for (MessageContentPart part : parts) {
            if (part == null) continue;
            try {
                switch (part.getType()) {
                    case "text" -> {
                        String txt = part.getText();
                        if (txt != null && !txt.isBlank()) {
                            String rewritten = sniffGeneratedFiles(txt, uploadJobs);
                            // 第一条文本用 processingStreamId 覆盖"思考中..."
                            if (firstText && ctx != null && ctx.processingStreamId() != null
                                    && !ctx.processingStreamId().isBlank()) {
                                replyStream(ctx.frameReqId(), ctx.processingStreamId(), rewritten, true);
                                firstText = false;
                            } else {
                                sendMessage(targetId, rewritten);
                            }
                            sentText = true;
                        }
                    }
                    case "refusal" -> {
                        // 模型拒绝回复（如内容策略限制），以文本形式发送
                        String refusalText = part.getText();
                        if (refusalText != null && !refusalText.isBlank()) {
                            sendMessage(targetId, "⚠️ " + refusalText);
                            sentText = true;
                        }
                    }
                    case "image" -> sendImagePart(targetId, part, ctx);
                    case "audio" -> sendAudioPart(targetId, part, ctx);
                    case "file" -> sendFilePart(targetId, part, ctx);
                    default -> {
                        if (part.getText() != null) {
                            sendMessage(targetId, part.getText());
                            sentText = true;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[wecom] Failed to send content part ({}): {}", part.getType(), e.getMessage());
                sendFallbackText(targetId, part);
            }
        }

        // 如果没有发送文本但有处理指示器，清除"思考中..."
        if (!sentText && ctx != null && ctx.processingStreamId() != null
                && !ctx.processingStreamId().isBlank()) {
            try {
                replyStream(ctx.frameReqId(), ctx.processingStreamId(), "✅ Done", true);
            } catch (Exception e) {
                log.debug("[wecom] Failed to clear processing indicator: {}", e.getMessage());
            }
        }

        // After all parts are dispatched, upload any generated files we
        // sniffed out of text parts. First attachment rides the inbound
        // frameReqId reply slot; subsequent ones go via active-push. Mirror
        // the ordering used in renderAndSend so the user sees text bubble
        // first, then the actual file card.
        if (!uploadJobs.isEmpty()) {
            String frameReqId = ctx != null ? ctx.frameReqId() : null;
            for (int i = 0; i < uploadJobs.size(); i++) {
                UploadJob job = uploadJobs.get(i);
                String mediaId = uploadMedia(job.bytes(), job.fileName(), job.mediaType());
                if (mediaId == null) {
                    log.warn("[wecom] Generated-file upload failed: {} ({} bytes)",
                            job.fileName(), job.bytes().length);
                    continue;
                }
                String replyReqId = (i == 0) ? frameReqId : null;
                sendMediaMessage(targetId, mediaId, job.mediaType(), replyReqId);
            }
        }
    }

    /**
     * 发送图片部分：压缩 → 大小预校验 → 上传 → 发送 media_id
     */
    private void sendImagePart(String targetId, MessageContentPart part, WeComReplyContext ctx) {
        byte[] imageBytes = resolveFileBytes(part);
        if (imageBytes == null) {
            sendFallbackText(targetId, part);
            return;
        }

        String fileName = part.getFileName() != null ? part.getFileName() : "image.jpg";
        imageBytes = WeComImageCompressor.compressIfNeeded(imageBytes, fileName);

        WeComUploadLimitDecision decision = applyWeComUploadLimits(
                imageBytes.length, "image", part.getContentType());
        if (decision.rejected()) {
            log.warn("[wecom] Image upload rejected: {} ({} bytes) — {}",
                    fileName, imageBytes.length, decision.rejectReason());
            sendMessageToChat(targetId, "⚠️ " + decision.rejectReason());
            return;
        }

        String mediaId = uploadMedia(imageBytes, fileName, decision.finalMediaType());
        if (mediaId != null) {
            String frameReqId = ctx != null ? ctx.frameReqId() : null;
            sendMediaMessage(targetId, mediaId, decision.finalMediaType(), frameReqId);
            if (decision.downgraded()) {
                sendMessageToChat(targetId, "ℹ️ " + decision.downgradeNote());
            }
        } else {
            sendFallbackText(targetId, part);
        }
    }

    /**
     * 发送文件部分：大小预校验 → 上传 → 发送 media_id
     */
    private void sendFilePart(String targetId, MessageContentPart part, WeComReplyContext ctx) {
        byte[] fileBytes = resolveFileBytes(part);
        if (fileBytes == null) {
            sendFallbackText(targetId, part);
            return;
        }

        String fileName = part.getFileName() != null ? part.getFileName() : "file.bin";
        WeComUploadLimitDecision decision = applyWeComUploadLimits(
                fileBytes.length, "file", part.getContentType());
        if (decision.rejected()) {
            log.warn("[wecom] File upload rejected: {} ({} bytes) — {}",
                    fileName, fileBytes.length, decision.rejectReason());
            sendMessageToChat(targetId, "⚠️ " + decision.rejectReason());
            return;
        }

        String mediaId = uploadMedia(fileBytes, fileName, decision.finalMediaType());
        if (mediaId != null) {
            String frameReqId = ctx != null ? ctx.frameReqId() : null;
            sendMediaMessage(targetId, mediaId, decision.finalMediaType(), frameReqId);
        } else {
            sendFallbackText(targetId, part);
        }
    }

    /**
     * 发送音频部分：读取字节 → 大小+格式预校验 → 上传 → 发送。
     * <p>
     * WeCom 原生语音消息要求 AMR 格式 + ≤ 2 MB。预校验里非 AMR 或超 2 MB
     * 自动降级为 file（文件卡片，可点击下载播放）+ 给用户一行说明
     * 提示——避免用户期待"语音气泡"但收到一个 .mp3 文件却不知道为啥。
     */
    private void sendAudioPart(String targetId, MessageContentPart part, WeComReplyContext ctx) {
        byte[] audioBytes = resolveFileBytes(part);
        if (audioBytes == null) {
            sendFallbackText(targetId, part);
            return;
        }

        String fileName = part.getFileName() != null ? part.getFileName() : "voice_reply.mp3";
        boolean isAmr = fileName.toLowerCase().endsWith(".amr");
        // Pre-decide native voice vs file based on extension; the limits
        // checker can still downgrade voice→file if size exceeds 2MB.
        String requestedType = isAmr ? "voice" : "file";
        String contentTypeHint = part.getContentType();
        if (contentTypeHint == null && isAmr) contentTypeHint = "audio/amr";

        WeComUploadLimitDecision decision = applyWeComUploadLimits(
                audioBytes.length, requestedType, contentTypeHint);
        if (decision.rejected()) {
            log.warn("[wecom] Audio upload rejected: {} ({} bytes) — {}",
                    fileName, audioBytes.length, decision.rejectReason());
            sendMessageToChat(targetId, "⚠️ " + decision.rejectReason());
            return;
        }

        String mediaId = uploadMedia(audioBytes, fileName, decision.finalMediaType());
        if (mediaId != null) {
            String frameReqId = ctx != null ? ctx.frameReqId() : null;
            sendMediaMessage(targetId, mediaId, decision.finalMediaType(), frameReqId);
            log.info("[wecom] Audio sent as {}: {} ({}KB)",
                    decision.finalMediaType(), fileName, audioBytes.length / 1024);
            if (decision.downgraded()) {
                sendMessageToChat(targetId, "ℹ️ " + decision.downgradeNote());
            }
        } else {
            sendFallbackText(targetId, part);
        }
    }

    /**
     * 从 MessageContentPart 解析文件字节：优先本地路径，其次 URL 下载
     */
    private byte[] resolveFileBytes(MessageContentPart part) {
        // 本地路径
        if (part.getPath() != null && !part.getPath().isBlank()) {
            try {
                Path p = Path.of(part.getPath());
                if (Files.exists(p)) {
                    return Files.readAllBytes(p);
                }
            } catch (Exception e) {
                log.debug("[wecom] Failed to read local file {}: {}", part.getPath(), e.getMessage());
            }
        }
        // URL 下载
        String url = part.getFileUrl();
        if (url != null && !url.isBlank() && httpClient != null) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    return response.body();
                }
            } catch (Exception e) {
                log.debug("[wecom] Failed to download file from {}: {}", url, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 降级发送：上传失败时退回 Markdown 文本
     */
    private void sendFallbackText(String targetId, MessageContentPart part) {
        switch (part.getType()) {
            case "image" -> {
                String url = part.getFileUrl() != null ? part.getFileUrl() : part.getMediaId();
                if (url != null) sendMessage(targetId, "![image](" + url + ")");
            }
            case "file" -> {
                String name = part.getFileName() != null ? part.getFileName() : "file";
                sendMessage(targetId, "[文件: " + name + "]");
            }
            case "audio" -> sendMessage(targetId, "[语音回复]");
            default -> { if (part.getText() != null) sendMessage(targetId, part.getText()); }
        }
    }

    // ==================== reply_stream 协议实现 ====================

    /**
     * 发送流式回复（reply_stream）
     * <p>
     * 通过 WebSocket 回复通道，使用相同 stream_id 可以覆盖更新已发送的消息。
     *
     * @param originalReqId 原始消息的 reqId（用于路由回复）
     * @param streamId      流式消息 ID（相同 ID 会覆盖之前的消息）
     * @param content       回复内容（支持 Markdown）
     * @param finish        是否结束流式消息
     */
    private void replyStream(String originalReqId, String streamId, String content, boolean finish) {
        replyStream(originalReqId, streamId, content, finish, null);
    }

    /**
     * Streaming reply with optional WeCom feedback id attached on the
     * final chunk (PR-2 hook installed in PR-0 so the protocol surface
     * is stable).
     *
     * <p>Per WeCom AI Bot protocol (verified against the langbot
     * reference implementation), {@code feedback.id} is only meaningful
     * on the chunk where {@code finish=true}. We accept the parameter
     * on every chunk for ergonomics but only emit the JSON field on
     * the finishing chunk to avoid surfacing it where the server
     * would ignore it.
     *
     * <p>Callers that don't need feedback collection pass {@code null}
     * for {@code feedbackId} (or use the legacy 4-arg overload).
     */
    private void replyStream(String originalReqId, String streamId, String content,
                             boolean finish, String feedbackId) {
        // PR-1 chunk dedup: skip the network round-trip when a non-final chunk
        // has the exact same content as the previous one for the same streamId.
        // Tool-call argument streaming in particular emits many redundant chunks
        // (each token re-flushes the partial JSON args) that would otherwise
        // flicker the IM client. The final chunk (finish=true) ALWAYS goes
        // through so WeCom closes the slot cleanly. RFC-32 §2.1.3.
        if (!finish) {
            String content_safe = content == null ? "" : content;
            String prev = streamLastContent.get(streamId);
            if (content_safe.equals(prev)) {
                return;
            }
            streamLastContent.put(streamId, content_safe);
        } else {
            // Final chunk consumes the dedup slot.
            streamLastContent.remove(streamId);
        }

        Map<String, Object> streamBody = new LinkedHashMap<>();
        streamBody.put("id", streamId);
        streamBody.put("finish", finish);
        streamBody.put("content", content);
        if (finish && feedbackId != null && !feedbackId.isBlank()) {
            streamBody.put("feedback", Map.of("id", feedbackId));
        }

        Map<String, Object> body = Map.of(
                "msgtype", "stream",
                "stream", streamBody
        );

        Map<String, Object> frame = Map.of(
                "cmd", CMD_RESPONSE,
                "headers", Map.of("req_id", originalReqId),
                "body", body
        );

        sendFrameWithAck(originalReqId, frame);
    }

    /**
     * Per-streamId last-content cache for chunk dedup. Bounded only by
     * the number of in-flight streams (a small handful in practice);
     * cleared on each finish=true chunk and on connection release.
     */
    private final ConcurrentHashMap<String, String> streamLastContent = new ConcurrentHashMap<>();

    // ==================== 上传大小预校验（WeCom 平台限制） ====================

    /** WeCom hard limits — verified empirically; sources differ slightly. */
    static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;   // 10 MB
    static final long VIDEO_MAX_BYTES = 10L * 1024 * 1024;   // 10 MB
    static final long VOICE_MAX_BYTES =  2L * 1024 * 1024;   // 2 MB
    static final long FILE_MAX_BYTES  = 20L * 1024 * 1024;   // 20 MB (absolute cap)
    static final Set<String> VOICE_SUPPORTED_MIMES = Set.of("audio/amr");

    /**
     * Decision result for {@link #applyWeComUploadLimits}.
     *
     * @param finalMediaType  effective media type after auto-downgrade
     *                        (image / video / voice could become "file"
     *                        when oversized or unsupported)
     * @param rejected        true → don't upload at all; surface
     *                        {@code rejectReason} to the user instead
     * @param rejectReason    human-readable reason when {@code rejected}
     * @param downgraded      true → uploaded as {@code finalMediaType}
     *                        but caller should append a notice to the
     *                        bubble explaining why it's not native
     * @param downgradeNote   user-friendly note when {@code downgraded}
     */
    record WeComUploadLimitDecision(String finalMediaType, boolean rejected,
                                    String rejectReason, boolean downgraded,
                                    String downgradeNote) {
        static WeComUploadLimitDecision pass(String mediaType) {
            return new WeComUploadLimitDecision(mediaType, false, null, false, null);
        }
    }

    /**
     * Pre-validate an outbound upload against WeCom's per-media-type limits
     * and decide whether to upload as-is, downgrade to {@code file}, or
     * reject outright. Mirrors the strategy proven in production by
     * comparable Java/Python WeCom integrations:
     * <ul>
     *   <li>Files over 20 MB → reject — even {@code msgtype=file} can't
     *       carry them.</li>
     *   <li>Image &gt; 10 MB → downgrade to file (still delivers, just as
     *       a tappable card instead of an inline preview).</li>
     *   <li>Video &gt; 10 MB → downgrade to file.</li>
     *   <li>Voice with non-AMR mime type → downgrade to file (WeCom
     *       voice msgtype only accepts AMR).</li>
     *   <li>Voice in AMR but &gt; 2 MB → downgrade to file.</li>
     * </ul>
     * Without this layer, oversized uploads would chunk-upload for up to
     * a minute before the platform server rejected them at the finish
     * step, with the user seeing nothing arrive in their chat.
     * <p>
     * Package-private for unit-test access.
     */
    static WeComUploadLimitDecision applyWeComUploadLimits(long fileSize, String mediaType,
                                                            String contentType) {
        String type = mediaType == null ? "file" : mediaType.toLowerCase();
        String mime = contentType == null ? "" : contentType.toLowerCase().trim();

        if (fileSize > FILE_MAX_BYTES) {
            double mb = fileSize / 1024.0 / 1024.0;
            return new WeComUploadLimitDecision(
                    type, true,
                    String.format(java.util.Locale.ROOT,
                            "文件大小 %.2fMB 超过企业微信 20MB 上限，无法发送。请压缩或拆分后再发。", mb),
                    false, null);
        }
        if ("image".equals(type) && fileSize > IMAGE_MAX_BYTES) {
            double mb = fileSize / 1024.0 / 1024.0;
            return new WeComUploadLimitDecision(
                    "file", false, null,
                    true,
                    String.format(java.util.Locale.ROOT,
                            "图片 %.2fMB 超过 10MB 限制，已转为文件形式发送", mb));
        }
        if ("video".equals(type) && fileSize > VIDEO_MAX_BYTES) {
            double mb = fileSize / 1024.0 / 1024.0;
            return new WeComUploadLimitDecision(
                    "file", false, null,
                    true,
                    String.format(java.util.Locale.ROOT,
                            "视频 %.2fMB 超过 10MB 限制，已转为文件形式发送", mb));
        }
        if ("voice".equals(type)) {
            if (!mime.isEmpty() && !VOICE_SUPPORTED_MIMES.contains(mime)) {
                return new WeComUploadLimitDecision(
                        "file", false, null, true,
                        "语音格式 " + mime + " 不支持（企微仅支持 AMR），已转为文件形式发送");
            }
            if (fileSize > VOICE_MAX_BYTES) {
                double mb = fileSize / 1024.0 / 1024.0;
                return new WeComUploadLimitDecision(
                        "file", false, null,
                        true,
                        String.format(java.util.Locale.ROOT,
                                "语音 %.2fMB 超过 2MB 限制，已转为文件形式发送", mb));
            }
        }
        return WeComUploadLimitDecision.pass(type);
    }

    /**
     * 发送欢迎消息
     */
    private void replyWelcome(String reqId, String text) {
        Map<String, Object> body = Map.of(
                "msgtype", "text",
                "text", Map.of("content", text)
        );
        Map<String, Object> frame = Map.of(
                "cmd", CMD_RESPONSE_WELCOME,
                "headers", Map.of("req_id", reqId),
                "body", body
        );
        sendFrameWithAck(reqId, frame);
    }

    /**
     * Send an interactive template card (e.g. button_interaction approval card).
     *
     * <p>Wraps the card payload in {@code msgtype=template_card} and routes via
     * the existing reply channel ({@code aibot_respond_msg}, bound to the inbound
     * frame's req_id). Source-verified against aibot SDK
     * {@code client.py:188-207 reply_template_card}.
     *
     * <p>Caller must have an active reply context for {@code reqId} — i.e. the
     * card is sent in response to a previously received message frame, not as a
     * proactive group push (which WeCom rejects for AI Bots, see RFC-32 G-12).
     *
     * @param reqId       the original inbound frame's {@code headers.req_id}
     * @param templateCard the WeCom template_card payload (card_type / task_id /
     *                    main_title / button_list / etc.)
     */
    public void replyTemplateCard(String reqId, Map<String, Object> templateCard) {
        Map<String, Object> body = Map.of(
                "msgtype", "template_card",
                "template_card", templateCard
        );
        Map<String, Object> frame = Map.of(
                "cmd", CMD_RESPONSE,
                "headers", Map.of("req_id", reqId),
                "body", body
        );
        sendFrameWithAck(reqId, frame);
    }

    /**
     * Update a previously-posted template card. Used by inbound
     * {@code template_card_event} handlers (e.g. tool-guard approval) to swap
     * the {@code button_interaction} card for a {@code text_notice} resolved
     * state once the user clicks a button.
     *
     * <p><b>5-second window</b>: per the aibot protocol, the response must be
     * sent within 5s of receiving the {@code template_card_event} frame —
     * otherwise the update is silently dropped. The handler path therefore
     * has to validate identity + render the new card synchronously (fast
     * DB lookup + map construction, well under 1ms) and only enqueue the
     * inject-command on the agent thread afterwards.
     *
     * <p>Source-verified against aibot SDK {@code client.py:260-284 update_template_card}.
     *
     * @param eventReqId the inbound {@code template_card_event} frame's req_id
     *                  (DIFFERENT from the original card-posting req_id)
     * @param templateCard the replacement card payload (same task_id as the
     *                    original card)
     */
    public void updateTemplateCard(String eventReqId, Map<String, Object> templateCard) {
        Map<String, Object> body = Map.of(
                "response_type", "update_template_card",
                "template_card", templateCard
        );
        Map<String, Object> frame = Map.of(
                "cmd", CMD_RESPONSE_UPDATE,
                "headers", Map.of("req_id", eventReqId),
                "body", body
        );
        sendFrameWithAck(eventReqId, frame);
    }

    /**
     * Keepalive refresh tick (called by {@link WeComKeepaliveScheduler}
     * every 20s). Sends {@code finish=false} on the existing stream so
     * WeCom's server-side TTL counter resets.
     *
     * <p>Public so the scheduler in this same package can invoke it; the
     * scheduler is itself a singleton bean and outside callers should
     * not be triggering refresh ticks.
     */
    public void replyStreamRefreshForKeepalive(String reqId, String streamId, String text) {
        replyStream(reqId, streamId, text, false);
    }

    /**
     * Force-finish the keepalive stream (180s ceiling reached). Sends
     * {@code finish=true} so WeCom closes the slot cleanly. The
     * scheduler immediately follows this with
     * {@link #invalidateReplyContext} so the eventual real reply takes
     * the fresh-stream path.
     */
    public void replyStreamFinishForKeepalive(String reqId, String streamId, String text) {
        replyStream(reqId, streamId, text, true);
    }

    /**
     * Drop the {@link WeComReplyContext} entry for a {@code targetId}
     * if (and only if) its current {@code processingStreamId} matches
     * the supplied {@code streamId}. Idempotent and safe to call from
     * any thread.
     *
     * <p>Used by {@link WeComKeepaliveScheduler} after force-finishing
     * a stuck stream — RFC-32 §2.1.2 invariant: the next
     * {@link #renderAndSend} call must NOT reuse a finished
     * {@code processingStreamId}.
     *
     * <p>The match-and-remove uses {@link
     * java.util.concurrent.ConcurrentHashMap#computeIfPresent} so a
     * concurrent {@code renderAndSend} that already swapped the
     * context for a fresh stream is left untouched.
     */
    public void invalidateReplyContext(String targetId, String streamId) {
        if (targetId == null || streamId == null) return;
        replyContexts.computeIfPresent(targetId, (k, ctx) -> {
            if (streamId.equals(ctx.processingStreamId())) {
                log.debug("[wecom] invalidateReplyContext: cleared {} (stream={})", targetId, streamId);
                return null;  // remove entry
            }
            return ctx;
        });
    }

    /**
     * Route a synthetic message into the standard
     * {@link ChannelMessageRouter} pipeline as if the user had typed it.
     *
     * <p>Bypasses {@link AbstractChannelAdapter#onMessage} so the
     * pre-flight bot-prefix filter and access-control check are SKIPPED
     * — appropriate for events that already represent an explicit user
     * intent (e.g. a button click on an approval card). The router still
     * runs its own approval validation in
     * {@link ChannelMessageRouter#processMessage}, so the identity check
     * for "only original requester can approve" still fires.
     *
     * <p>Currently used by tool-guard card handler. Package-private (no
     * modifier) so only sibling classes in the wecom package can inject;
     * external code must go through {@link ChannelAdapter#onMessage}.
     */
    public void injectSyntheticMessage(ChannelMessage message) {
        messageRouter.enqueue(message, this, channelEntity);
    }

    // ==================== 媒体上传协议 ====================

    /**
     * 通过 WebSocket 分块上传文件到企业微信
     * <p>
     * 三阶段协议：
     *   1. Init: 发送文件元数据 → 获得 upload_id
     *   2. Chunks: 发送 base64 编码的 512KB 分块
     *   3. Finish: 完成上传 → 获得 media_id
     *
     * @param fileBytes  文件内容
     * @param fileName   文件名
     * @param mediaType  媒体类型："image" / "file" / "voice" / "video"
     * @return media_id，失败返回 null
     */
    @SuppressWarnings("unchecked")
    private String uploadMedia(byte[] fileBytes, String fileName, String mediaType) {
        if (webSocket == null || fileBytes == null || fileBytes.length == 0) {
            return null;
        }
        // Pre-flight chunk count guard. WeCom's chunked upload protocol caps
        // out near 100 chunks (~50 MB at 512 KB / chunk) but we already
        // reject anything over FILE_MAX_BYTES (20 MB ≈ 40 chunks) before
        // reaching here, so this is a defence-in-depth log line rather
        // than a routine path.
        int totalChunks = (int) Math.ceil((double) fileBytes.length / UPLOAD_CHUNK_SIZE);
        if (totalChunks > 100) {
            log.warn("[wecom] Upload would require {} chunks (>100 cap), rejecting: {} ({} bytes)",
                    totalChunks, fileName, fileBytes.length);
            return null;
        }

        boolean acquired = false;
        try {
            acquired = uploadLock.tryAcquire(60, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[wecom] Upload lock timeout, another upload may be in progress");
                return null;
            }

            String md5 = md5Hex(fileBytes);

            // Phase 1: Init
            String initReqId = generateReqId(CMD_UPLOAD_INIT);
            Map<String, Object> initBody = new LinkedHashMap<>();
            initBody.put("type", mediaType);
            initBody.put("filename", fileName);
            initBody.put("total_size", fileBytes.length);
            initBody.put("total_chunks", totalChunks);
            initBody.put("md5", md5);

            Map<String, Object> initFrame = Map.of(
                    "cmd", CMD_UPLOAD_INIT,
                    "headers", Map.of("req_id", initReqId),
                    "body", initBody
            );
            Map<String, Object> initAck = sendFrameWithAckBlocking(initReqId, initFrame, UPLOAD_ACK_TIMEOUT_MS);
            Map<String, Object> initAckBody = (Map<String, Object>) initAck.getOrDefault("body", Map.of());
            String uploadId = (String) initAckBody.getOrDefault("upload_id", "");
            if (uploadId.isBlank()) {
                log.error("[wecom] Upload init failed: empty upload_id");
                return null;
            }
            log.debug("[wecom] Upload init: upload_id={}, chunks={}", uploadId.substring(0, Math.min(20, uploadId.length())), totalChunks);

            // Phase 2: Chunks
            for (int i = 0; i < totalChunks; i++) {
                int offset = i * UPLOAD_CHUNK_SIZE;
                int length = Math.min(UPLOAD_CHUNK_SIZE, fileBytes.length - offset);
                byte[] chunk = Arrays.copyOfRange(fileBytes, offset, offset + length);
                String base64Data = Base64.getEncoder().encodeToString(chunk);

                String chunkReqId = generateReqId(CMD_UPLOAD_CHUNK);
                Map<String, Object> chunkBody = new LinkedHashMap<>();
                chunkBody.put("upload_id", uploadId);
                chunkBody.put("chunk_index", i);
                // Field name MUST be "base64_data" — the WeCom AI bot upload
                // server reads the chunk bytes from this exact key. A previous
                // version sent "data" which the server silently dropped, so
                // metadata (filename/size) committed but the bytes never made
                // it to storage. Receivers then saw the file with the right
                // name/size but couldn't open it ("文件已损坏").
                chunkBody.put("base64_data", base64Data);

                Map<String, Object> chunkFrame = Map.of(
                        "cmd", CMD_UPLOAD_CHUNK,
                        "headers", Map.of("req_id", chunkReqId),
                        "body", chunkBody
                );
                sendFrameWithAckBlocking(chunkReqId, chunkFrame, UPLOAD_ACK_TIMEOUT_MS);
            }

            // Phase 3: Finish
            String finishReqId = generateReqId(CMD_UPLOAD_FINISH);
            Map<String, Object> finishFrame = Map.of(
                    "cmd", CMD_UPLOAD_FINISH,
                    "headers", Map.of("req_id", finishReqId),
                    "body", Map.of("upload_id", uploadId)
            );
            Map<String, Object> finishAck = sendFrameWithAckBlocking(finishReqId, finishFrame, UPLOAD_ACK_TIMEOUT_MS);
            Map<String, Object> finishAckBody = (Map<String, Object>) finishAck.getOrDefault("body", Map.of());
            String mediaId = (String) finishAckBody.getOrDefault("media_id", "");
            if (mediaId.isBlank()) {
                log.error("[wecom] Upload finish failed: empty media_id");
                return null;
            }

            log.info("[wecom] Upload completed: media_id={}, type={}, size={}KB",
                    mediaId.substring(0, Math.min(20, mediaId.length())), mediaType, fileBytes.length / 1024);
            return mediaId;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[wecom] Upload interrupted");
            return null;
        } catch (Exception e) {
            log.error("[wecom] Upload failed for {}: {}", fileName, e.getMessage(), e);
            return null;
        } finally {
            if (acquired) {
                uploadLock.release();
            }
        }
    }

    /**
     * 发送帧并阻塞等待 ACK 响应（用于上传协议需要读取返回值的场景）
     *
     * @return ACK 帧 Map
     * @throws RuntimeException 超时或错误
     */
    private Map<String, Object> sendFrameWithAckBlocking(String reqId, Map<String, Object> frame, long timeoutMs) {
        CompletableFuture<Map<String, Object>> ackFuture = new CompletableFuture<>();
        pendingAcks.put(reqId, ackFuture);
        sendFrame(frame);
        try {
            return ackFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingAcks.remove(reqId);
            throw new RuntimeException("Upload ACK timeout for reqId=" + reqId, e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Upload ACK error for reqId=" + reqId, e.getCause());
        } catch (InterruptedException e) {
            pendingAcks.remove(reqId);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Upload interrupted", e);
        }
    }

    /**
     * 使用 media_id 发送媒体消息
     *
     * @param targetId   目标 ID（userId 或 chatId）
     * @param mediaId    上传后获得的 media_id
     * @param mediaType  媒体类型（image / file / voice / video）
     * @param frameReqId 原始消息 frameReqId（非 null 时通过 reply 路径，null 时通过主动推送）
     */
    private void sendMediaMessage(String targetId, String mediaId, String mediaType, String frameReqId) {
        Map<String, Object> mediaBody = new LinkedHashMap<>();
        mediaBody.put("msgtype", mediaType);
        mediaBody.put(mediaType, Map.of("media_id", mediaId));

        if (frameReqId != null && !frameReqId.isBlank()) {
            // Caller has an explicit inbound frameReqId (the message we're
            // replying to is "now") — use that reply slot directly.
            Map<String, Object> frame = Map.of(
                    "cmd", CMD_RESPONSE,
                    "headers", Map.of("req_id", frameReqId),
                    "body", mediaBody
            );
            sendFrameWithAck(frameReqId, frame);
        } else {
            // Proactive push (cron summary, async-task forward, etc.) —
            // delegate to sendOutboundFrame so groups ride the cached
            // reqId via aibot_respond_msg (the AI bot platform rejects
            // aibot_send_msg in groups). Single chats fall through to
            // aibot_send_msg as before.
            sendOutboundFrame(targetId, mediaBody);
        }
    }

    // ==================== Markdown 表格格式化 ====================

    /**
     * 格式化 GFM Markdown 表格，使其在企业微信中对齐显示。
     * <p>
     * 企业微信要求表格列宽一致才能正确渲染。此方法解析表格，
     * 计算每列最大宽度，统一填充空格对齐。
     * 代码块内的表格不做处理。
     */
    static String formatMarkdownTables(String text) {
        if (text == null || !text.contains("|")) return text;

        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();
        int i = 0;
        boolean inCodeFence = false;

        while (i < lines.length) {
            String line = lines[i];
            String stripped = line.strip();

            // 跟踪代码块（``` 内的内容不处理）
            if (stripped.startsWith("```")) {
                inCodeFence = !inCodeFence;
                result.add(line);
                i++;
                continue;
            }
            if (inCodeFence) {
                result.add(line);
                i++;
                continue;
            }

            // 检测表格开始（含 | 的行）
            if (line.contains("|")) {
                List<String> tableLines = new ArrayList<>();
                while (i < lines.length && lines[i].contains("|")
                        && !lines[i].strip().startsWith("```")) {
                    tableLines.add(lines[i]);
                    i++;
                }
                if (!tableLines.isEmpty()) {
                    result.addAll(formatTable(tableLines));
                }
                continue;
            }

            result.add(line);
            i++;
        }
        return String.join("\n", result);
    }

    /**
     * 格式化单个 Markdown 表格
     */
    private static List<String> formatTable(List<String> lines) {
        if (lines.isEmpty()) return lines;

        // 检测第二行是否为分隔行（只含 -, :, |, 空格）
        boolean hasSeparator = lines.size() >= 2
                && lines.get(1).strip().matches("[\\s\\-:|]+");

        // 解析单元格（跳过分隔行，后面会重建）
        List<List<String>> rows = new ArrayList<>();
        for (int idx = 0; idx < lines.size(); idx++) {
            if (hasSeparator && idx == 1) continue;
            String[] cells = lines.get(idx).split("\\|", -1);
            List<String> trimmed = new ArrayList<>();
            for (String cell : cells) {
                trimmed.add(cell.strip());
            }
            // 去掉首尾空元素（由前导/尾随 | 产生）
            if (!trimmed.isEmpty() && trimmed.getFirst().isEmpty()) trimmed.removeFirst();
            if (!trimmed.isEmpty() && trimmed.getLast().isEmpty()) trimmed.removeLast();
            if (!trimmed.isEmpty()) rows.add(trimmed);
        }

        if (rows.isEmpty()) return lines;

        // 计算每列最大宽度
        int colCount = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[colCount];
        for (List<String> row : rows) {
            for (int j = 0; j < colCount; j++) {
                String cell = j < row.size() ? row.get(j) : "";
                widths[j] = Math.max(widths[j], cell.length());
            }
        }

        // 构建格式化结果
        List<String> formatted = new ArrayList<>();
        for (int idx = 0; idx < rows.size(); idx++) {
            List<String> row = rows.get(idx);
            StringBuilder sb = new StringBuilder("| ");
            for (int j = 0; j < colCount; j++) {
                String cell = j < row.size() ? row.get(j) : "";
                sb.append(padRight(cell, widths[j]));
                if (j < colCount - 1) sb.append(" | ");
            }
            sb.append(" |");
            formatted.add(sb.toString());

            // 头部行后插入分隔行
            if (idx == 0) {
                StringBuilder sep = new StringBuilder("| ");
                for (int j = 0; j < colCount; j++) {
                    sep.append("-".repeat(Math.max(3, widths[j])));
                    if (j < colCount - 1) sep.append(" | ");
                }
                sep.append(" |");
                formatted.add(sep.toString());
            }
        }
        return formatted;
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    // ==================== 帧发送基础设施 ====================

    /**
     * 发送 WebSocket 帧（fire and forget）
     */
    /**
     * Visible to package-level tests (RFC-32 §3.0 S-1/S-2 stress) so a
     * test subclass can override frame dispatch without monkey-patching
     * the private WS field. Production callers stay within this class.
     */
    void sendFrame(Map<String, Object> frame) {
        WebSocket ws = this.webSocket;
        if (ws == null) {
            log.warn("[wecom] WebSocket not connected, cannot send frame");
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(frame);
            ws.sendText(json, true);
        } catch (Exception e) {
            log.error("[wecom] Failed to send frame: {}", e.getMessage(), e);
        }
    }

    /**
     * Serially send a frame on the WS and wait (in a per-reqId worker)
     * for its ACK. Same {@code reqId} messages are guaranteed to be
     * dispatched in arrival order: the worker reads from the queue,
     * registers {@link #pendingAcks} only after the previous ACK
     * settled, sends, then blocks on the future until the ACK arrives
     * or {@link #REPLY_ACK_TIMEOUT_MS} elapses.
     *
     * <p>RFC-32 §2.4.1 a-2 / R-5/R-6/R-7 invariants this implements:
     * <ul>
     *   <li><b>Lifecycle gate</b>: outer + inner check on
     *       {@link #replyQueueAccepting}. If closed, the returned
     *       future is fast-failed with {@link IllegalStateException}
     *       — never registered, never enqueued.</li>
     *   <li><b>TOCTOU between idle-close and late-offer</b>: the
     *       offer happens INSIDE the {@code compute} lambda, sharing
     *       the bin lock with the worker's own {@code compute}-based
     *       idle-close. They serialize cleanly.</li>
     *   <li><b>Executor null/shutdown defense</b>: re-checked inside
     *       compute; submit wrapped in try/catch for
     *       {@link RejectedExecutionException}.</li>
     *   <li><b>offered[] flag</b>: any path that doesn't successfully
     *       offer falls through to {@code completeExceptionally}; no
     *       caller future ever hangs forever.</li>
     * </ul>
     *
     * <p>Returns the ACK future for callers that want to chain on
     * success (e.g. extract {@code body} fields from the ACK frame).
     * Existing fire-and-forget callers can ignore the return value;
     * timeout/error handling lives inside the worker.
     */
    @SuppressWarnings("UnusedReturnValue")
    private CompletableFuture<Map<String, Object>> sendFrameWithAck(String reqId, Map<String, Object> frame) {
        CompletableFuture<Map<String, Object>> ackFuture = new CompletableFuture<>();

        // ---- Outer lifecycle check (fast-fail, no allocations beyond the future) ----
        if (!replyQueueAccepting.get()) {
            ackFuture.completeExceptionally(
                    new IllegalStateException("WeCom channel not accepting reply tasks (lifecycle gate closed)"));
            return ackFuture;
        }

        ReplyTask task = new ReplyTask(frame, ackFuture);
        boolean[] offered = {false};

        try {
            replyQueues.compute(reqId, (k, existing) -> {
                // ---- Inner lifecycle check: gate may have flipped between outer check and bin lock ----
                if (!replyQueueAccepting.get()) {
                    return existing;  // do NOT modify map; offered[0] stays false → fail below
                }

                // ---- Reuse open state if present ----
                if (existing != null && !existing.closed().get()) {
                    existing.queue().offer(task);
                    offered[0] = true;
                    return existing;
                }

                // ---- Need to start a fresh state. Defend against late-shutdown ----
                ExecutorService exec = this.replyExecutor;
                if (exec == null || exec.isShutdown()) {
                    return existing;  // executor torn down by release; fail below
                }

                ReplyQueueState fresh = ReplyQueueState.fresh();
                fresh.queue().offer(task);
                try {
                    exec.submit(() -> reqIdWorker(k, fresh));
                    offered[0] = true;
                    return fresh;
                } catch (RejectedExecutionException ree) {
                    // Race with shutdownNow between isShutdown check and submit
                    return existing;  // do not write fresh; fail below
                }
            });
        } catch (Exception e) {
            // compute lambda surfaced something we didn't expect — never let this leak as
            // a hung future
            ackFuture.completeExceptionally(e);
            return ackFuture;
        }

        // ---- Final guarantee: any path that didn't offer must fail-fast ----
        if (!offered[0] && !ackFuture.isDone()) {
            ackFuture.completeExceptionally(
                    new IllegalStateException("WeCom channel transitioning, reply task rejected"));
        }
        return ackFuture;
    }

    // ==================== 媒体文件下载与 AES 解密 ====================

    /**
     * Conversation id for inbound media: matches the format
     * {@code ChannelMessageRouter.buildConversationId} produces from the same
     * (channelType, chatId, senderId) tuple. Pre-computing it here lets the
     * media-download helper write into the right per-conversation directory
     * <em>before</em> the {@link ChannelMessage} is built.
     *
     * <p>The router's identifier is {@code chatId} when present (group context)
     * and {@code senderId} otherwise (1:1) — there is no {@code group:} infix
     * because the router doesn't add one. An earlier divergence here ({@code
     * "wecom:group:" + chatId}) persisted media at
     * {@code data/chat-uploads/wecom:group:{chatId}/} and stamped that path
     * onto {@link MessageContentPart#getFileUrl()}, but the message's actual
     * conversationId in {@code mate_conversation} was {@code wecom:{chatId}}
     * (no {@code group:} infix). The {@code /api/v1/chat/files/...} endpoint
     * then ran {@code isConversationOwner("wecom:group:...")}, found no
     * matching row, returned 403, and the IM client rendered every
     * group-quoted image as a broken icon.
     */
    private static String inboundConversationId(String senderId, String chatId, String chatType) {
        boolean isGroup = "group".equals(chatType);
        return isGroup ? "wecom:" + chatId : "wecom:" + senderId;
    }

    // ==================== 引用消息（quote）解析 ====================

    /**
     * Quoted-message extraction result: a human-readable prefix string the
     * agent prompt prepends, plus any media (image / file) that was quoted
     * and needs to be available as a content part. Either field may be
     * empty; {@link #isEmpty()} returns true only when both are.
     */
    private record QuoteContext(String prefix, List<MessageContentPart> attachedParts) {
        boolean isEmpty() {
            return (prefix == null || prefix.isBlank()) && attachedParts.isEmpty();
        }
    }

    /**
     * Parse the {@code body.quote} field of an inbound WeCom AI Bot frame.
     * Quote payloads sit alongside the new message at body level —
     * independent of the outer {@code msgtype} — and may themselves carry
     * any of text / voice / image / file / mixed. We flatten everything
     * into:
     * <ul>
     *   <li>a single {@code prefix} string of the form
     *       {@code "[引用消息: <flattened summary>]\n"} that prepends to the
     *       agent's user prompt</li>
     *   <li>a list of {@link MessageContentPart}s for any quoted media so
     *       the vision / document tools can analyse the actually-quoted
     *       image or PDF, not just see "[图片]" in the prefix</li>
     * </ul>
     * Returns null when {@code body.quote} is absent / empty / malformed —
     * the caller treats that the same as "no quote context".
     */
    private QuoteContext extractQuoteContext(Map<String, Object> body, String msgId,
                                              String senderId, String chatId, String chatType) {
        Object raw = body.get("quote");
        if (!(raw instanceof Map<?, ?> map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> quote = (Map<String, Object>) map;
        String quoteType = (String) quote.getOrDefault("msgtype", "");
        if (quoteType == null || quoteType.isBlank()) return null;

        // Flatten: a "mixed" quote nests its own msg_item array; single-type
        // quotes act as a one-element list of themselves.
        List<Map<String, Object>> items;
        if ("mixed".equals(quoteType)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mixed = (Map<String, Object>) quote.getOrDefault("mixed", Map.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mi = (List<Map<String, Object>>) mixed.getOrDefault("msg_item", List.of());
            items = mi;
        } else {
            items = List.of(quote);
        }

        String inboundConvId = inboundConversationId(senderId, chatId, chatType);
        StringBuilder summary = new StringBuilder();
        List<MessageContentPart> attached = new ArrayList<>();

        for (Map<String, Object> item : items) {
            String itemType = (String) item.getOrDefault("msgtype", "");
            switch (itemType == null ? "" : itemType) {
                case "text" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> t = (Map<String, Object>) item.getOrDefault("text", Map.of());
                    String content = ((String) t.getOrDefault("content", "")).trim();
                    if (!content.isBlank()) {
                        appendQuoteSummary(summary, content);
                    }
                }
                case "voice" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> v = (Map<String, Object>) item.getOrDefault("voice", Map.of());
                    String asr = ((String) v.getOrDefault("content", "")).trim();
                    if (!asr.isBlank()) {
                        appendQuoteSummary(summary, "[语音] " + asr);
                    } else {
                        appendQuoteSummary(summary, "[语音消息]");
                    }
                }
                case "image" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> img = (Map<String, Object>) item.getOrDefault("image", Map.of());
                    String url = (String) img.getOrDefault("url", "");
                    String aesKey = (String) img.getOrDefault("aeskey", "");
                    if (!url.isBlank()) {
                        attached.add(buildInboundImagePart(url, aesKey, msgId,
                                "quoted_image.jpg", inboundConvId));
                    }
                    appendQuoteSummary(summary, "[图片]");
                }
                case "file" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> f = (Map<String, Object>) item.getOrDefault("file", Map.of());
                    String url = (String) f.getOrDefault("url", "");
                    String aesKey = (String) f.getOrDefault("aeskey", "");
                    String filename = (String) f.getOrDefault("filename",
                            f.getOrDefault("file_name", f.getOrDefault("name", "file.bin")));
                    if (!url.isBlank()) {
                        MessageContentPart part = buildInboundFilePart(url, aesKey, msgId,
                                filename, inboundConvId);
                        attached.add(part);
                        if (part.getFileName() != null && !part.getFileName().isBlank()) {
                            filename = part.getFileName();
                        }
                    }
                    appendQuoteSummary(summary, "[文件: " + filename + "]");
                }
                default -> {
                    // Unknown quote sub-type — surface the type tag so the
                    // agent knows something was quoted even if we can't
                    // unpack it.
                    if (itemType != null && !itemType.isBlank()) {
                        appendQuoteSummary(summary, "[" + itemType + "]");
                    }
                }
            }
        }

        if (summary.length() == 0 && attached.isEmpty()) {
            return null;
        }
        String prefix = "[引用消息: " + summary + "]\n";
        return new QuoteContext(prefix, attached);
    }

    private static void appendQuoteSummary(StringBuilder summary, String fragment) {
        if (summary.length() > 0) summary.append(' ');
        summary.append(fragment);
    }

    // ==================== appmsg 解析（PDF/Word/Excel/链接/小程序）====================

    /**
     * Decoded {@code msgtype=appmsg} payload — text marker the agent reads
     * + any attached media parts. Returned by {@link #extractAppmsgContent}
     * so the inbound switch can splice it into {@code textContent} and
     * {@code contentParts} uniformly.
     */
    record AppmsgContent(String text, List<MessageContentPart> attachedParts) {}

    /**
     * Parse a {@code msgtype=appmsg} body into a text marker + media parts.
     * <p>
     * Supports four variants observed in the WeCom platform:
     * <ul>
     *   <li>{@code appmsg.file} — forwarded PDF / Word / Excel; reuses
     *       {@link #buildInboundFilePart} so magic-byte sniff + ZIP
     *       refinement work identically to plain {@code msgtype=file}</li>
     *   <li>{@code appmsg.image} — forwarded image card</li>
     *   <li>{@code appmsg.miniprogram} — miniprogram card; surface the
     *       title so the agent at least knows what was shared</li>
     *   <li>{@code appmsg.url} — public-account article / external link;
     *       flatten title + description + URL into text</li>
     * </ul>
     * Unknown variants fall back to a {@code [appmsg]} marker so the
     * agent isn't completely blind.
     * <p>
     * Package-private for unit-test access.
     */
    AppmsgContent extractAppmsgContent(Map<String, Object> body, String msgId,
                                        String senderId, String chatId, String chatType) {
        @SuppressWarnings("unchecked")
        Map<String, Object> appmsg = (Map<String, Object>) body.getOrDefault("appmsg", Map.of());
        String title = ((String) appmsg.getOrDefault("title", "")).trim();
        String desc  = ((String) appmsg.getOrDefault("description", "")).trim();
        String linkUrl = ((String) appmsg.getOrDefault("url", "")).trim();
        String inboundConvId = inboundConversationId(senderId, chatId, chatType);

        Object fileObj = appmsg.get("file");
        Object imageObj = appmsg.get("image");
        Object miniObj = appmsg.get("miniprogram");

        List<MessageContentPart> attached = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        if (fileObj instanceof Map<?, ?> fileMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fileBody = (Map<String, Object>) fileMap;
            String fileUrl = (String) fileBody.getOrDefault("url", "");
            String aesKey = (String) fileBody.getOrDefault("aeskey", "");
            String filename = (String) fileBody.getOrDefault("filename",
                    fileBody.getOrDefault("file_name",
                            fileBody.getOrDefault("name",
                                    title.isBlank() ? "file.bin" : title)));
            if (!fileUrl.isBlank()) {
                MessageContentPart filePart = buildInboundFilePart(
                        fileUrl, aesKey, msgId, filename, inboundConvId);
                attached.add(filePart);
                if (filePart.getFileName() != null && !filePart.getFileName().isBlank()) {
                    filename = filePart.getFileName();
                }
            }
            text.append("[文件: ").append(filename).append("]");
        } else if (imageObj instanceof Map<?, ?> imgMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> imgBody = (Map<String, Object>) imgMap;
            String imgUrl = (String) imgBody.getOrDefault("url", "");
            String aesKey = (String) imgBody.getOrDefault("aeskey", "");
            if (!imgUrl.isBlank()) {
                attached.add(buildInboundImagePart(imgUrl, aesKey, msgId,
                        "appmsg_image.jpg", inboundConvId));
            }
            text.append("[图片").append(title.isBlank() ? "" : ": " + title).append("]");
        } else if (miniObj instanceof Map<?, ?> miniMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mini = (Map<String, Object>) miniMap;
            String miniTitle = ((String) mini.getOrDefault("title",
                    title.isBlank() ? "未命名小程序" : title)).trim();
            text.append("[小程序: ").append(miniTitle).append("]");
        } else if (!linkUrl.isBlank()) {
            text.append("[链接]");
            if (!title.isBlank()) text.append(' ').append(title);
            if (!desc.isBlank()) text.append('\n').append(desc);
            text.append('\n').append(linkUrl);
            // WeChat public-account articles (mp.weixin.qq.com) ship the
            // body behind a captcha-gated SSR page — the URL is opaque to
            // any LLM tool. Without this hint the model invents plausible
            // content from the title alone (observed: "本文讲了三个要点…"
            // hallucinations). The hint nudges the agent to ask the user
            // to paste the article text instead of guessing.
            if (isPublicAccountArticle(linkUrl)) {
                text.append('\n').append(PUBLIC_ACCOUNT_ARTICLE_HINT);
            }
        } else if (!title.isBlank()) {
            text.append("[appmsg: ").append(title).append("]");
        } else {
            text.append("[appmsg]");
        }
        return new AppmsgContent(text.toString(), attached);
    }

    /**
     * Hint appended to forwarded WeChat public-account articles. Worded as
     * a directive for the agent (not a user-visible message) — the agent's
     * reasoning prompt picks it up alongside the link itself, so the model
     * sees the directive in-band with the share.
     */
    static final String PUBLIC_ACCOUNT_ARTICLE_HINT =
            "（提示：该链接为公众号文章，正文需要用户在微信内打开后复制粘贴，"
            + "请优先请用户粘贴正文，不要凭标题猜测内容。）";

    /**
     * Public-account article links are hosted on {@code mp.weixin.qq.com}.
     * Compared to a generic URL host check, this is intentionally narrow —
     * other Tencent properties (e.g. video.qq.com) don't share the same
     * "title-only, body needs paste" property and shouldn't get the hint.
     */
    static boolean isPublicAccountArticle(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("://mp.weixin.qq.com/")
                || lower.startsWith("mp.weixin.qq.com/");
    }

    /**
     * Build a fully-populated image content part for inbound WeCom media.
     * <p>
     * When download is enabled and succeeds, the part carries
     * {@code path}, {@code fileUrl} (browser-servable), {@code fileName},
     * {@code storedName}, {@code fileSize}, and a precise {@code contentType}
     * — every field the chat bubble and the multimodal sidecar inspect. When
     * download is disabled or fails, the part falls back to URL-only fields
     * but still sets {@code fileName} so the bubble doesn't render
     * "未命名 / unknown".
     */
    private MessageContentPart buildInboundImagePart(String url, String aesKey, String msgId,
                                                      String fileNameHint, String conversationId) {
        if (getConfigBoolean("media_download_enabled", true)) {
            InboundMediaResult r = downloadInboundMedia(url, aesKey, msgId, fileNameHint, conversationId);
            if (r != null) {
                MessageContentPart part = new MessageContentPart();
                part.setType("image");
                part.setFileName(r.fileName());
                part.setStoredName(r.storedName());
                part.setPath(r.localPath());
                part.setFileUrl(r.fileUrl());
                part.setFileSize(r.fileSize());
                // Prefer the sniffed contentType (could be image/png) over a
                // hardcoded image/jpeg. Falls back to image/jpeg only when the
                // sniff was inconclusive.
                String ct = r.contentType();
                part.setContentType((ct != null && ct.startsWith("image/")) ? ct : "image/jpeg");
                // mediaId mirrors path so callers that prefer it still resolve
                // to the same on-disk file (matches Web upload's behaviour).
                part.setMediaId(r.localPath());
                return part;
            }
        }
        // Fallback: download disabled or failed. Browser preview will be broken
        // because the WeCom CDN URL carries a short-lived signature, but at
        // least the bubble shows "image.jpg" instead of "未命名 / unknown".
        MessageContentPart part = new MessageContentPart();
        part.setType("image");
        part.setFileName(fileNameHint);
        part.setFileUrl(url);
        part.setMediaId(url);
        part.setContentType("image/jpeg");
        return part;
    }

    /**
     * Build a fully-populated file content part for inbound WeCom media.
     * <p>
     * Mirrors {@link #buildInboundImagePart} but for non-image attachments
     * (PDF, DOCX, ZIP, etc.). The magic-byte sniffer inside
     * {@link #downloadInboundMedia} fixes generic {@code file.bin} hints to
     * the real extension so downstream tools (PDF text extractor, magika,
     * etc.) key off the correct mime.
     */
    private MessageContentPart buildInboundFilePart(String url, String aesKey, String msgId,
                                                     String fileNameHint, String conversationId) {
        if (getConfigBoolean("media_download_enabled", true)) {
            InboundMediaResult r = downloadInboundMedia(url, aesKey, msgId, fileNameHint, conversationId);
            if (r != null) {
                MessageContentPart part = new MessageContentPart();
                part.setType("file");
                part.setFileName(r.fileName());
                part.setStoredName(r.storedName());
                part.setPath(r.localPath());
                part.setFileUrl(r.fileUrl());
                part.setFileSize(r.fileSize());
                part.setContentType(r.contentType());
                part.setMediaId(r.localPath());
                return part;
            }
        }
        // Fallback when download is disabled or fails — at least keep the
        // original hint so the bubble doesn't say "file.bin" for a PDF.
        MessageContentPart part = new MessageContentPart();
        part.setType("file");
        part.setFileName(fileNameHint);
        part.setFileUrl(url);
        part.setMediaId(url);
        return part;
    }

    /**
     * Inbound-media download result. Carries every field the bubble renderer
     * and the multimodal sidecar need so callers don't have to re-derive
     * storedName / fileUrl from scratch.
     *
     * @param localPath   absolute filesystem path of the saved file
     * @param storedName  the on-disk filename (matches the last segment of localPath)
     * @param fileUrl     browser-servable URL: {@code /api/v1/chat/files/{convId}/{storedName}}
     * @param fileSize    byte length after decryption
     * @param fileName    human-readable display name (extension corrected by magic-byte sniff)
     * @param contentType MIME type derived from magic bytes (or {@code application/octet-stream})
     */
    record InboundMediaResult(String localPath, String storedName,
                              String fileUrl, long fileSize, String fileName,
                              String contentType) {}

    /** Magic-byte sniff result. */
    private record MagicSniff(String extension, String contentType) {
        static final MagicSniff UNKNOWN = new MagicSniff(".bin", "application/octet-stream");
    }

    /**
     * Best-effort MIME sniff from the first 12 bytes of a file. Covers the
     * formats users routinely forward to bots (PDF, Office, archives, common
     * image / audio / video). When nothing matches, returns
     * {@link MagicSniff#UNKNOWN} so the caller falls back to {@code .bin}.
     * <p>
     * This exists because WeCom's {@code aibot_msg_callback} {@code file}
     * body sometimes omits {@code filename} entirely (forwarded files in
     * particular), and shipping the agent a part labelled {@code file.bin}
     * makes downstream tools mis-route the content. Sniffing recovers a
     * useful extension so PDF tools fire on PDFs.
     */
    private static MagicSniff sniffMagic(byte[] head) {
        if (head == null || head.length < 4) return MagicSniff.UNKNOWN;
        // PDF: %PDF
        if (head[0] == 0x25 && head[1] == 0x50 && head[2] == 0x44 && head[3] == 0x46) {
            return new MagicSniff(".pdf", "application/pdf");
        }
        // PNG: 89 50 4E 47
        if (head[0] == (byte) 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47) {
            return new MagicSniff(".png", "image/png");
        }
        // JPEG: FF D8 FF
        if (head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF) {
            return new MagicSniff(".jpg", "image/jpeg");
        }
        // GIF: "GIF8"
        if (head[0] == 0x47 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x38) {
            return new MagicSniff(".gif", "image/gif");
        }
        // ZIP-based container: PK\x03\x04. Could be a plain ZIP, a JAR,
        // an OOXML document (DOCX/XLSX/PPTX), an ODF document (ODT/ODS/ODP),
        // or an EPUB. Magic-byte alone can't tell them apart — caller is
        // expected to follow up with refineZipKind(fullBytes) to pick a
        // specific type.
        if (head[0] == 0x50 && head[1] == 0x4B && head[2] == 0x03 && head[3] == 0x04) {
            return new MagicSniff(".zip", "application/zip");
        }
        // Legacy Office (DOC/XLS/PPT): D0 CF 11 E0 A1 B1 1A E1
        if (head.length >= 8
                && head[0] == (byte) 0xD0 && head[1] == (byte) 0xCF
                && head[2] == 0x11 && head[3] == (byte) 0xE0
                && head[4] == (byte) 0xA1 && head[5] == (byte) 0xB1
                && head[6] == 0x1A && head[7] == (byte) 0xE1) {
            return new MagicSniff(".doc", "application/msword");
        }
        // RTF: "{\rtf"
        if (head.length >= 5
                && head[0] == 0x7B && head[1] == 0x5C
                && head[2] == 0x72 && head[3] == 0x74 && head[4] == 0x66) {
            return new MagicSniff(".rtf", "application/rtf");
        }
        // 7z: 37 7A BC AF 27 1C
        if (head.length >= 6
                && head[0] == 0x37 && head[1] == 0x7A && head[2] == (byte) 0xBC
                && head[3] == (byte) 0xAF && head[4] == 0x27 && head[5] == 0x1C) {
            return new MagicSniff(".7z", "application/x-7z-compressed");
        }
        // RAR: "Rar!\x1A\x07"
        if (head.length >= 6
                && head[0] == 0x52 && head[1] == 0x61 && head[2] == 0x72
                && head[3] == 0x21 && head[4] == 0x1A && head[5] == 0x07) {
            return new MagicSniff(".rar", "application/x-rar-compressed");
        }
        // MP3: ID3v2 ("ID3") or MPEG sync 0xFFFB / 0xFFF3 / 0xFFF2
        if (head[0] == 0x49 && head[1] == 0x44 && head[2] == 0x33) {
            return new MagicSniff(".mp3", "audio/mpeg");
        }
        // MP4: "....ftyp" — bytes 4..7 == "ftyp"
        if (head.length >= 8
                && head[4] == 0x66 && head[5] == 0x74 && head[6] == 0x79 && head[7] == 0x70) {
            return new MagicSniff(".mp4", "video/mp4");
        }
        // OGG: "OggS"
        if (head[0] == 0x4F && head[1] == 0x67 && head[2] == 0x67 && head[3] == 0x53) {
            return new MagicSniff(".ogg", "audio/ogg");
        }
        return MagicSniff.UNKNOWN;
    }

    /**
     * Peek inside a ZIP container to distinguish OOXML (DOCX/XLSX/PPTX),
     * ODF (ODT/ODS/ODP), JAR, and EPUB from a plain ZIP. Reads the local
     * file headers in order via {@link ZipInputStream}; the discriminator
     * entry is almost always within the first few entries (OOXML places
     * {@code [Content_Types].xml} first, ODF places {@code mimetype} first),
     * so we cap iteration at 16 entries to bound CPU.
     * <p>
     * Returns the original {@code zipDefault} sniff (plain
     * {@code application/zip}) when no specific kind is detected — that's
     * the right answer for actual ZIPs and unknown archive formats.
     */
    private static MagicSniff refineZipKind(byte[] fileData, MagicSniff zipDefault) {
        if (fileData == null || fileData.length < 30) return zipDefault;
        String mimetypeContent = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(fileData))) {
            ZipEntry entry;
            int seen = 0;
            while ((entry = zis.getNextEntry()) != null && seen < 16) {
                String name = entry.getName();
                // OOXML — Office Open XML (Word/Excel/PowerPoint). Each format
                // has a distinct top-level directory; we match on prefix
                // because the entry order isn't guaranteed.
                if (name.startsWith("word/")) {
                    return new MagicSniff(".docx",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                }
                if (name.startsWith("xl/")) {
                    return new MagicSniff(".xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                }
                if (name.startsWith("ppt/")) {
                    return new MagicSniff(".pptx",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
                }
                // Visio (rare but worth catching)
                if (name.startsWith("visio/")) {
                    return new MagicSniff(".vsdx",
                            "application/vnd.ms-visio.drawing");
                }
                // ODF marker: a {@code mimetype} entry that contains the full
                // application/vnd.oasis.opendocument.* string — read its body
                // and decide once we have it.
                if ("mimetype".equals(name)) {
                    byte[] buf = zis.readAllBytes();
                    mimetypeContent = new String(buf, java.nio.charset.StandardCharsets.UTF_8).trim();
                }
                // JAR
                if ("META-INF/MANIFEST.MF".equals(name)) {
                    return new MagicSniff(".jar", "application/java-archive");
                }
                // EPUB always has META-INF/container.xml
                if ("META-INF/container.xml".equals(name)) {
                    return new MagicSniff(".epub", "application/epub+zip");
                }
                seen++;
            }
        } catch (Exception e) {
            log.debug("[wecom] refineZipKind failed (treating as plain zip): {}", e.getMessage());
            return zipDefault;
        }
        if (mimetypeContent != null) {
            if (mimetypeContent.contains("opendocument.text")) {
                return new MagicSniff(".odt", "application/vnd.oasis.opendocument.text");
            }
            if (mimetypeContent.contains("opendocument.spreadsheet")) {
                return new MagicSniff(".ods", "application/vnd.oasis.opendocument.spreadsheet");
            }
            if (mimetypeContent.contains("opendocument.presentation")) {
                return new MagicSniff(".odp", "application/vnd.oasis.opendocument.presentation");
            }
            if (mimetypeContent.contains("epub")) {
                return new MagicSniff(".epub", "application/epub+zip");
            }
        }
        return zipDefault;
    }

    /**
     * Strip a trailing extension from a filename. {@code "image.jpg" → "image"};
     * {@code "no_ext" → "no_ext"}; {@code "" → ""}.
     */
    private static String stripExtension(String name) {
        if (name == null || name.isBlank()) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        return name.substring(0, dot);
    }

    /**
     * Download + decrypt an inbound media attachment and stash it under
     * {@code data/chat-uploads/{conversationId}/} so the existing
     * {@code /api/v1/chat/files/...} endpoint can serve it back to the chat
     * bubble. Returns a fully-populated {@link InboundMediaResult} on success
     * or null on download/decrypt failure (callers fall back to URL-only).
     * <p>
     * Storing under chat-uploads rather than {@code data/media} means
     * {@link MessageContentPart#getPath()} resolves to a real file for the
     * vision sidecar AND {@code fileUrl} renders as a thumbnail in the Web
     * mirror — instead of the WeCom-signed CDN URL whose 5-minute query-string
     * signature expires before the browser can fetch it.
     */
    private InboundMediaResult downloadInboundMedia(String url, String aesKey, String msgId,
                                                    String fileNameHint, String conversationId) {
        try {
            // Mirror ChatController.uploadRoot ("data/chat-uploads") so the
            // serve endpoint at /api/v1/chat/files/{convId}/{storedName} works
            // without any extra wiring. The conversationId may contain ':'
            // (e.g. "wecom:XuZhanFu" or "wecom:group:abc"); Path resolution
            // tolerates this on macOS/Linux but Windows would reject the
            // colon — for now we keep parity with the existing chat-uploads
            // layout and revisit if Windows support comes up.
            Path uploadDir = Path.of("data", "chat-uploads", conversationId);
            Files.createDirectories(uploadDir);

            // 1. HTTP GET 下载文件
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] encryptedData = response.body().readAllBytes();

            byte[] fileData;
            // 2. AES 解密（如果提供了 aesKey）
            if (aesKey != null && !aesKey.isBlank()) {
                fileData = decryptAes256Cbc(encryptedData, aesKey);
            } else {
                fileData = encryptedData;
            }

            // 3. Magic-byte sniff to recover a real extension when WeCom
            //    didn't include filename in the body (forwarded files often
            //    arrive nameless — saving them as "file.bin" misroutes the
            //    agent because every PDF tool keys off the .pdf extension).
            byte[] head = new byte[Math.min(12, fileData.length)];
            System.arraycopy(fileData, 0, head, 0, head.length);
            MagicSniff sniff = sniffMagic(head);
            // ZIP container needs a deeper look — DOCX/XLSX/PPTX/ODF/EPUB/JAR
            // all share the PK\x03\x04 magic. Peek inside the first few
            // entries to pick the specific kind.
            if (".zip".equals(sniff.extension())) {
                sniff = refineZipKind(fileData, sniff);
            }

            // 4. Compose a URL-safe storedName. If the hint is generic
            //    (e.g. "file.bin"), prefer the sniffed extension.
            String urlHash = md5Hex(url).substring(0, 8);
            String hintRaw = (fileNameHint == null ? "media" : fileNameHint).trim();
            String safeName = hintRaw.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (safeName.isBlank()) safeName = "media";
            // "file.bin" is the WeCom-no-filename sentinel; if magic gave us
            // something better, replace the extension. Same when hint had no
            // extension at all.
            boolean hintIsGeneric = safeName.equals("file.bin") || safeName.equals("media")
                    || !safeName.contains(".");
            if (hintIsGeneric && !".bin".equals(sniff.extension())) {
                safeName = stripExtension(safeName) + sniff.extension();
            }
            String storedName = "wecom_" + urlHash + "_" + safeName;
            Path filePath = uploadDir.resolve(storedName);
            Files.write(filePath, fileData);

            String fileUrl = "/api/v1/chat/files/" + conversationId + "/" + storedName;
            log.info("[wecom] Inbound media saved: {} ({} bytes, sniffed={}), serve URL={}",
                    filePath, fileData.length, sniff.contentType(), fileUrl);
            return new InboundMediaResult(
                    filePath.toAbsolutePath().toString(),
                    storedName,
                    fileUrl,
                    fileData.length,
                    safeName,
                    sniff.contentType());
        } catch (Exception e) {
            log.error("[wecom] Failed to download inbound media: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 下载并解密企业微信媒体文件（旧版本，保留给 outbound / 其他场景使用）
     * <p>
     * AES-256-CBC 解密：base64 decode aesKey → IV = 前 16 字节 → PKCS#7 去填充
     *
     * @param url          文件下载 URL
     * @param aesKey       Base64 编码的 AES-256 密钥
     * @param msgId        消息 ID（用于生成文件名）
     * @param fileNameHint 文件名提示
     * @return 本地文件路径，失败返回 null
     */
    private String downloadAndDecryptMedia(String url, String aesKey, String msgId, String fileNameHint) {
        try {
            String mediaDir = getConfigString("media_dir", "data/media");
            Path mediaDirPath = Path.of(mediaDir);
            Files.createDirectories(mediaDirPath);

            // 1. HTTP GET 下载文件
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] encryptedData = response.body().readAllBytes();

            byte[] fileData;
            // 2. AES 解密（如果提供了 aesKey）
            if (aesKey != null && !aesKey.isBlank()) {
                fileData = decryptAes256Cbc(encryptedData, aesKey);
            } else {
                fileData = encryptedData;
            }

            // 3. 保存到本地
            String urlHash = md5Hex(url).substring(0, 8);
            String safeName = fileNameHint.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (safeName.isBlank()) safeName = "media";
            Path filePath = mediaDirPath.resolve("wecom_" + urlHash + "_" + safeName);
            Files.write(filePath, fileData);

            log.info("[wecom] Media downloaded: {} ({} bytes)", filePath, fileData.length);
            return filePath.toAbsolutePath().toString();

        } catch (Exception e) {
            log.error("[wecom] Failed to download media: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * AES-256-CBC 解密（对齐 wecom-aibot-python-sdk crypto_utils.py）
     * <p>
     * 1. Base64 decode aesKey（自动补齐 padding）
     * 2. IV = decoded key 前 16 字节
     * 3. AES-256-CBC 解密
     * 4. PKCS#7 去填充
     */
    private byte[] decryptAes256Cbc(byte[] encryptedData, String aesKeyBase64) throws Exception {
        // 补齐 Base64 padding
        int padCount = (4 - aesKeyBase64.length() % 4) % 4;
        String padded = aesKeyBase64 + "=".repeat(padCount);
        byte[] keyBytes = Base64.getDecoder().decode(padded);

        // IV = 前 16 字节
        byte[] iv = Arrays.copyOf(keyBytes, 16);

        // 确保数据是 16 字节的倍数
        int blockSize = 16;
        int remainder = encryptedData.length % blockSize;
        if (remainder != 0) {
            encryptedData = Arrays.copyOf(encryptedData, encryptedData.length + (blockSize - remainder));
        }

        // AES-256-CBC 解密（NoPadding — 手动去 PKCS#7）
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        byte[] decrypted = cipher.doFinal(encryptedData);

        // PKCS#7 去填充
        int padLen = decrypted[decrypted.length - 1] & 0xFF;
        if (padLen < 1 || padLen > 32 || padLen > decrypted.length) {
            throw new IllegalArgumentException("Invalid PKCS#7 padding value: " + padLen);
        }
        for (int i = decrypted.length - padLen; i < decrypted.length; i++) {
            if ((decrypted[i] & 0xFF) != padLen) {
                throw new IllegalArgumentException("Invalid PKCS#7 padding: bytes mismatch");
            }
        }
        return Arrays.copyOf(decrypted, decrypted.length - padLen);
    }

    // ==================== 主动推送 ====================

    @Override
    public boolean supportsProactiveSend() {
        return true;
    }

    @Override
    public void proactiveSend(String targetId, String content) {
        sendMessageToChat(targetId, content);
    }

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    /**
     * WeCom's AI-bot transport is WebSocket-only ({@code wss://openws.work.weixin.qq.com}
     * with {@code aibot_subscribe} authenticated by {@code bot_id + secret}) — there is
     * no HTTP webhook fallback. Multiple nodes subscribing with the same
     * credentials would each receive every {@code aibot_msg_callback} and race
     * to send {@code aibot_respond_msg}, producing duplicate replies and
     * eventual gateway rejection. The leader gate ensures only one node
     * holds the subscription at a time.
     */
    @Override
    public boolean requiresSingleLeader() {
        return true;
    }

    // ==================== 工具方法 ====================

    /**
     * 生成唯一请求 ID：{prefix}_{timestamp}_{counter}
     */
    private String generateReqId(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + reqIdCounter.incrementAndGet();
    }

    /**
     * MD5 哈希（hex 字符串）
     */
    private String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            return bytesToHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * MD5 哈希（字节数组输入）
     */
    private String md5Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input);
            return bytesToHex(hash);
        } catch (Exception e) {
            return "0";
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================== 回复队列内部类 ====================

    private record ReplyTask(Map<String, Object> frame, CompletableFuture<Map<String, Object>> future) {}
}
