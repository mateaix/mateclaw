package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天流状态追踪器
 * <p>
 * 采用生产者-消费者解耦设计：将 SSE 事件的生产（Flux 订阅）与消费（SseEmitter 连接）解耦。
 * 一个后台 Flux 生产者持续产出事件，广播给所有 SseEmitter 订阅者并缓存到 buffer。
 * 新连接（重连）到来时，先回放 buffer，再接入实时流。
 *
 * <h2>Single-instance assumption</h2>
 * <p><strong>The {@link #runs} map is process-local memory.</strong> A reconnect
 * request can only re-attach to a {@code RunState} that lives on the <em>same</em>
 * JVM that originally created it. In a multi-node deployment behind a load
 * balancer, the LB MUST be configured for sticky session by {@code conversationId}
 * (Nginx {@code hash $arg_conversationId consistent;}, K8s Ingress
 * cookie-based affinity, AWS ALB target-group stickiness, etc.).
 *
 * <p>This is an explicit CE constraint — see
 * {@code rfcs/community/90-appendix/02-tech-debt-inventory.md §4.1} and
 * {@code rfc-054 §0}. Cross-node SSE relay (Redis Stream / NATS / Kafka) is
 * tracked under the EE roadmap.
 *
 * <p>Operator-facing diagnostics: callers should use
 * {@link #streamExistsOnThisNode(String)} when distinguishing "stream finished
 * normally" from "stream is on a different node" — both return {@code false}
 * from {@link #attach(String, SseEmitter)} but mean very different things to
 * the user.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class ChatStreamTracker {

    /** buffer 最大事件数，超出后丢弃最早的 thinking_delta 事件以释放空间 */
    private static final int MAX_BUFFER_SIZE = 16000;

    private final ObjectMapper objectMapper;

    /**
     * Maximum size, in bytes, of a single SSE event JSON payload before
     * {@link #broadcastChunked} splits the body into ordered
     * {@code tool_result_chunk} events.
     */
    static final int CHUNK_SIZE = 8192;

    // ===== Configurable knobs (mateclaw.stream.*) =====

    /**
     * Gate for chunked tool-result transport. When {@code false},
     * {@link #broadcastChunked} falls back to a single broadcast call so
     * environments that prefer the legacy single-event behavior can opt out.
     */
    @Value("${mateclaw.stream.chunked-tool-results:true}")
    private boolean chunkedToolResultsEnabled = true;

    /**
     * Gate for {@code iteration_start} / {@code iteration_end} events emitted
     * from graph nodes. Off-by-default deployments can suppress them without
     * touching node code.
     */
    @Value("${mateclaw.stream.iteration-events:true}")
    private boolean iterationEventsEnabled = true;

    /**
     * Heartbeat cadence (seconds) before the first model token arrives. Short
     * because pre-token gaps strand the UI on a blank "正在生成中" placeholder
     * with no visible activity.
     */
    @Value("${mateclaw.stream.heartbeat.pre-token-sec:2}")
    private int heartbeatPreTokenSec = 2;

    /**
     * Heartbeat cadence (seconds) once the model is actively streaming tokens —
     * deltas themselves keep the connection warm, so heartbeats relax.
     */
    @Value("${mateclaw.stream.heartbeat.streaming-sec:10}")
    private int heartbeatStreamingSec = 10;

    /**
     * Heartbeat cadence (seconds) while a tool call is in flight. Tools can
     * take longer than streaming chunks but should still tick faster than the
     * default proxy idle timeout.
     */
    @Value("${mateclaw.stream.heartbeat.tool-sec:5}")
    private int heartbeatToolSec = 5;

    public ChatStreamTracker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Test-only setters; production paths use Spring property binding. */
    void setChunkedToolResultsEnabled(boolean enabled) {
        this.chunkedToolResultsEnabled = enabled;
    }

    void setIterationEventsEnabled(boolean enabled) {
        this.iterationEventsEnabled = enabled;
    }

    public boolean isIterationEventsEnabled() {
        return iterationEventsEnabled;
    }

    /**
     * One buffered SSE event. The {@code id} is a per-conversation monotonic
     * sequence — the SSE protocol's standard {@code id:} line carries this
     * value so the client can echo it back via {@code lastEventId} when
     * reconnecting, allowing us to skip already-delivered events on replay.
     */
    record SseEvent(long id, String name, String json) {}

    /**
     * 中断类型：区分用户主动停止和用户在运行中追加新消息
     */
    public enum InterruptType {
        /** 用户点击 Stop，终止当前 turn，不自动续跑 */
        USER_STOP,
        /** 用户在执行中追加新消息，中断当前 turn 后自动续跑排队消息 */
        USER_INTERRUPT_WITH_FOLLOWUP
    }

    static final class RunState {
        final String conversationId;
        final List<SseEmitter> subscribers = new ArrayList<>();
        final List<SseEvent> buffer = new ArrayList<>();
        final Object lock = new Object();
        volatile boolean done;
        /**
         * Monotonic sequence used as the SSE protocol {@code id:} field.
         * Incremented inside {@code state.lock} as each event is buffered,
         * so the buffer is always in (id-asc) order. On reconnect, the
         * client echoes its last-seen id back via {@code lastEventId} and
         * we skip events whose id is &le; that value during replay —
         * eliminating the duplicate-delivery class of bugs.
         */
        long nextEventId = 0L;
        /** Flux 订阅的 Disposable，用于取消 LLM 流 */
        volatile Disposable disposable;
        /** 停止标志：requestStop() 设为 true，各图节点和 LLM 调用检查此标志以提前退出 */
        final AtomicBoolean stopRequested = new AtomicBoolean(false);
        /**
         * 当前活跃的 Flux 数量（原始流 + 审批 Replay 流共享同一个 RunState）。
         * complete() 仅在计数归零时才真正移除 RunState，防止 Replay 仍在运行时被原始流的完成误删。
         */
        volatile int activeFluxCount = 0;

        // ===== Interrupt + Queue 新增字段 =====

        /** 中断类型（null 表示未请求中断） */
        volatile InterruptType interruptType;

        /** 当前执行阶段（用于 heartbeat 和前端状态展示） */
        volatile String currentPhase = "thinking";

        /** 当前正在执行的工具名称 */
        volatile String runningToolName;

        /** 等待原因（审批等待时有值） */
        volatile String waitingReason;

        /** 排队的用户消息队列（支持多条排队消息，按序消费） */
        final java.util.Queue<QueuedInput> messageQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

        /**
         * Emergency save callback registered by the SSE chain owner (ChatController).
         * Invoked from {@link #onShutdown()} so the accumulated assistant content + tool_calls
         * are persisted before the JVM tears down — without this, a `mvn spring-boot:run`
         * restart wipes any in-flight turn and leaves only the user message in DB.
         * <p>
         * The callback must be idempotent (will not be called twice for the same run, but
         * may race with normal doOnComplete/doOnError; both paths must tolerate the other
         * having saved already).
         */
        volatile Runnable emergencySaveCallback;

        /** 心跳定时器 */
        volatile ScheduledFuture<?> heartbeatFuture;

        /**
         * Flips the first time any content/thinking delta is observed for this
         * run. Heartbeat scheduling watches this flag to switch from the short
         * pre-token cadence to the streaming cadence — pre-token gaps need
         * frequent keep-alives because the UI has no other signal of activity.
         */
        volatile boolean firstTokenReceived = false;

        /** 已广播的 pending approval ID 集合（用于幂等去重） */
        final java.util.Set<String> broadcastedApprovalIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /** 创建时间（用于 stale 检测和清理） */
        final long createdAt = System.currentTimeMillis();

        /**
         * Wall-clock millis of the most recent meaningful event on this run.
         * Updated whenever {@link #broadcast(String, String, String)} pushes a
         * non-heartbeat event so a watchdog can tell "actively producing"
         * apart from "alive but silent".
         */
        volatile long lastEventAt = System.currentTimeMillis();

        /** Bound agent identifier; null while not yet resolved. */
        volatile Long agentId;

        /** Username that owns this run; null for system-driven runs. */
        volatile String username;

        RunState(String conversationId) {
            this.conversationId = conversationId;
        }
    }

    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();

    /**
     * Conversations whose run was force-recycled by an admin. Maps to the
     * recycle timestamp so a scheduled cleanup can age entries out (TTL
     * matches {@link #DONE_RETENTION_MS} — long enough that any in-flight
     * doOnComplete / doOnError firing after the dispose still finds the
     * marker, short enough not to leak across sessions).
     * <p>
     * Read by the SSE doOn* handlers in ChatController to skip a duplicate
     * saveMessage when the recycle path already wrote the "[已被用户中止]"
     * placeholder. Without this, the agent's late-yielding doOnComplete
     * inserts a second assistant row carrying whatever the agent produced
     * after the user pressed stop — exactly the behavior the user does
     * <em>not</em> want when force-recycling.
     */
    private final ConcurrentHashMap<String, Long> recycledConversations = new ConcurrentHashMap<>();

    /** 事件 relay：子会话事件转发到父会话（用于 Agent 委派进度可见性） */
    private final ConcurrentHashMap<String, List<java.util.function.BiConsumer<String, String>>> eventRelays = new ConcurrentHashMap<>();

    /**
     * 注册事件 relay：将 sourceConversationId 的广播事件同时转发给 listener。
     * 返回一个 Runnable，调用后取消注册。
     */
    public Runnable addEventRelay(String sourceConversationId,
                                   java.util.function.BiConsumer<String, String> listener) {
        eventRelays.computeIfAbsent(sourceConversationId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(listener);
        log.debug("Event relay registered for conversation {}", sourceConversationId);
        return () -> {
            List<java.util.function.BiConsumer<String, String>> listeners = eventRelays.get(sourceConversationId);
            if (listeners != null) {
                listeners.remove(listener);
                if (listeners.isEmpty()) {
                    eventRelays.remove(sourceConversationId);
                }
            }
            log.debug("Event relay removed for conversation {}", sourceConversationId);
        };
    }

    /**
     * Batching variant of {@link #addEventRelay} for sub-conversation streams
     * whose tool-call chatter would flood the parent transcript. Tool start /
     * complete events accumulate into a buffer; lifecycle and error events
     * (subagent_*, error, tool_approval_requested, phase, done) bypass the
     * buffer but flush it first so ordering is preserved.
     * <p>
     * Buffered events are emitted as a single {@code delegation_batch}
     * envelope on the parent conversation listener:
     * <pre>
     * {
     *   "kind":   "delegation_batch",
     *   "scope":  "subagent",
     *   "events": [{ "event": "tool_call_started", "data": "&lt;json&gt;" }, ...]
     * }
     * </pre>
     *
     * @param sourceConversationId conversation to listen on
     * @param parentConversationId parent conversation context (currently
     *                              forwarded only as listener metadata; the
     *                              tracker itself does not target it)
     * @param batchSize             flush threshold by event count
     * @param flushMs               flush threshold by elapsed millis since
     *                              first buffered event
     * @return Runnable that deregisters the relay (and flushes any pending
     *         events first)
     */
    public Runnable addBatchedEventRelay(String sourceConversationId,
                                          String parentConversationId,
                                          int batchSize,
                                          long flushMs,
                                          java.util.function.BiConsumer<String, String> listener) {
        BatchedRelay relay = new BatchedRelay(parentConversationId, listener,
                Math.max(1, batchSize), Math.max(1, flushMs));
        java.util.function.BiConsumer<String, String> wrapper = relay::accept;
        eventRelays.computeIfAbsent(sourceConversationId,
                        k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(wrapper);
        log.debug("Batched relay registered for conversation {} -> parent={}",
                sourceConversationId, parentConversationId);
        return () -> {
            relay.shutdown();
            List<java.util.function.BiConsumer<String, String>> listeners =
                    eventRelays.get(sourceConversationId);
            if (listeners != null) {
                listeners.remove(wrapper);
                if (listeners.isEmpty()) {
                    eventRelays.remove(sourceConversationId);
                }
            }
            log.debug("Batched relay removed for {} -> parent={}",
                    sourceConversationId, parentConversationId);
        };
    }

    /**
     * Internal helper holding the batch buffer and the scheduled flush. Each
     * relay owns its own state but reuses {@link #heartbeatScheduler} for
     * timer ticks (sharing the daemon-thread scheduler avoids one-thread-per
     * -relay sprawl in long agent sessions).
     */
    private final class BatchedRelay {
        private final String parentConversationId;
        private final java.util.function.BiConsumer<String, String> downstream;
        private final int batchSize;
        private final long flushMs;
        private final List<Map<String, String>> buffer = new ArrayList<>();
        private final Object lock = new Object();
        private ScheduledFuture<?> pendingFlush;
        private volatile boolean closed;

        BatchedRelay(String parentConversationId,
                     java.util.function.BiConsumer<String, String> downstream,
                     int batchSize, long flushMs) {
            this.parentConversationId = parentConversationId;
            this.downstream = downstream;
            this.batchSize = batchSize;
            this.flushMs = flushMs;
        }

        void accept(String eventName, String json) {
            if (closed) return;
            // Pass-through (with prior flush to preserve ordering) for any
            // event that conveys lifecycle or critical state. Tool call
            // boundaries are the only batched class today; the explicit list
            // here is the source of truth.
            if (isPassThrough(eventName)) {
                flushNow();
                downstream.accept(eventName, json);
                return;
            }
            if (!"tool_call_started".equals(eventName)
                    && !"tool_call_completed".equals(eventName)) {
                downstream.accept(eventName, json);
                return;
            }

            boolean shouldFlush = false;
            synchronized (lock) {
                Map<String, String> entry = new java.util.LinkedHashMap<>();
                entry.put("event", eventName);
                entry.put("data", json);
                buffer.add(entry);
                if (buffer.size() >= batchSize) {
                    shouldFlush = true;
                } else if (pendingFlush == null || pendingFlush.isDone()) {
                    pendingFlush = heartbeatScheduler.schedule(this::flushNow,
                            flushMs, TimeUnit.MILLISECONDS);
                }
            }
            if (shouldFlush) {
                flushNow();
            }
        }

        private boolean isPassThrough(String eventName) {
            return "subagent_start".equals(eventName)
                    || "subagent_complete".equals(eventName)
                    || "error".equals(eventName)
                    || "tool_approval_requested".equals(eventName)
                    || "phase".equals(eventName)
                    // Plan lifecycle events from a child agent: flush buffered
                    // tool calls first so the parent timeline preserves order.
                    || "plan_created".equals(eventName)
                    || "plan_step_started".equals(eventName)
                    || "plan_step_completed".equals(eventName)
                    || "done".equals(eventName);
        }

        void flushNow() {
            List<Map<String, String>> snapshot;
            synchronized (lock) {
                if (buffer.isEmpty()) {
                    if (pendingFlush != null) {
                        pendingFlush.cancel(false);
                        pendingFlush = null;
                    }
                    return;
                }
                snapshot = new ArrayList<>(buffer);
                buffer.clear();
                if (pendingFlush != null) {
                    pendingFlush.cancel(false);
                    pendingFlush = null;
                }
            }
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("kind", "delegation_batch");
            envelope.put("scope", "subagent");
            if (parentConversationId != null && !parentConversationId.isEmpty()) {
                envelope.put("parent", parentConversationId);
            }
            envelope.put("events", snapshot);
            try {
                String json = objectMapper.writeValueAsString(envelope);
                downstream.accept("delegation_batch", json);
            } catch (Exception e) {
                log.warn("Batched relay flush failed: {}", e.getMessage());
            }
        }

        void shutdown() {
            closed = true;
            flushNow();
        }
    }

    /** 心跳调度线程池（守护线程） */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stream-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /**
     * 注册流状态（开始生成时调用）。
     * 幂等：如果已存在活跃的 RunState（Replay 与原始流共享场景），复用它而非覆盖。
     */
    public void register(String conversationId) {
        runs.computeIfAbsent(conversationId, RunState::new);
        // 如果已存在但 done=true（上一轮残留），替换为新的
        RunState state = runs.get(conversationId);
        if (state != null && state.done) {
            stopHeartbeat(conversationId);
            runs.put(conversationId, new RunState(conversationId));
        } else if (state != null) {
            // Reuse path: when complete() early-returns due to activeFluxCount > 0
            // (approval replay / interrupt / any leaked flux increment), the RunState
            // is kept with stopRequested still true from the previous turn. Left alone,
            // the next register() would reuse it and ReasoningNode would instantly
            // abort every new message with "Stop requested before LLM call".
            // Reset the flag here — new registration means new user intent, and any
            // still-live prior flux has already been cancelled via requestStop()'s
            // disposable.dispose(), so the flag is redundant for it.
            if (state.stopRequested.compareAndSet(true, false)) {
                log.info("[ChatStreamTracker] Reset stale stopRequested on register: {}", conversationId);
            }
        }
        // Clear the force-recycle marker on new registration — the recycle
        // tombstone is meant to suppress the late doOnComplete of the
        // *recycled* run only, not future turns on the same conversation. If
        // the user re-prompts ("继续", "重试", a new question, etc.) inside
        // the 5-min TTL, this turn must be allowed to save its assistant
        // message normally.
        if (recycledConversations.remove(conversationId) != null) {
            log.info("[ChatStreamTracker] Cleared recycle marker on new register: {}", conversationId);
        }
        startHeartbeat(conversationId);
        log.debug("Stream registered: {}", conversationId);
    }

    /**
     * 设置 Flux 订阅的 Disposable（流开始后立即调用）
     */
    public void setDisposable(String conversationId, Disposable disposable) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            state.disposable = disposable;
        }
    }

    /**
     * Register an emergency-save callback for this run, invoked from {@link #onShutdown()}
     * before the JVM tears down. The callback should snapshot the current accumulator
     * state and persist it as the assistant message (status="interrupted").
     */
    public void setEmergencySaveCallback(String conversationId, Runnable callback) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            state.emergencySaveCallback = callback;
        }
    }

    /**
     * 请求停止指定会话的流。
     * 取消 Flux 订阅（底层 HTTP 连接也会随之关闭），返回 true 表示确实停止了正在运行的流。
     */
    public boolean requestStop(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null || state.done) {
            return false;
        }
        // 设置停止标志，图节点和 LLM 调用会检查此标志以提前退出
        boolean firstRequest = !state.stopRequested.getAndSet(true);
        Disposable d = state.disposable;
        if (d != null && !d.isDisposed()) {
            d.dispose();
            log.info("Stream stopped via requestStop: {}", conversationId);
            return true;
        }
        return firstRequest;
    }

    /**
     * 检查指定会话是否已被请求停止。
     * 图节点在每次迭代入口处调用此方法，若返回 true 则抛出 CancellationException 中断执行。
     */
    public boolean isStopRequested(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null && state.stopRequested.get();
    }

    /**
     * Whether this conversation was force-recycled by an admin within the
     * recycle marker's TTL ({@link #DONE_RETENTION_MS}). The SSE doOn*
     * handlers consult this to skip a duplicate saveMessage when the recycle
     * path already wrote the placeholder. Survives {@code runs.remove(...)},
     * unlike {@link #isStopRequested(String)}.
     */
    public boolean isRecycled(String conversationId) {
        Long ts = recycledConversations.get(conversationId);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > DONE_RETENTION_MS) {
            recycledConversations.remove(conversationId);
            return false;
        }
        return true;
    }

    /**
     * 广播事件到所有订阅者并缓存到 buffer.
     * <p>
     * Two event categories survive {@code state.done=true}:
     * <ul>
     *   <li>{@code "done"} — the lifecycle marker itself. If a client missed
     *       this on a broken pipe and reconnects within the 5-minute retention
     *       window, replay surfaces it so the UI exits "生成中" state.</li>
     *   <li>{@code "async_task_*"} — task lifecycle events from
     *       {@code AsyncTaskService} (image/video/music generation). These
     *       routinely fire <em>after</em> the agent's reasoning turn finishes
     *       (long-running upstream calls). Without this carve-out the events
     *       are silently dropped and the UI never sees the audio/error.</li>
     * </ul>
     * For all other events, the prior {@code state==null || state.done}
     * early-return remains.
     */
    public void broadcast(String conversationId, String eventName, String jsonData) {
        RunState state = runs.get(conversationId);

        boolean isDone = "done".equals(eventName);
        boolean isAsyncTask = eventName != null && eventName.startsWith("async_task_");
        boolean isHeartbeat = "heartbeat".equals(eventName);

        // Stamp last activity for stuck detection. Heartbeats are excluded
        // because they fire on a timer regardless of model progress; counting
        // them would mask a wedged turn behind a healthy timestamp.
        if (state != null && !isHeartbeat) {
            state.lastEventAt = System.currentTimeMillis();
        }

        if (isDone || isAsyncTask) {
            if (state == null) return;
            synchronized (state.lock) {
                long id = ++state.nextEventId;
                SseEvent ev = new SseEvent(id, eventName, jsonData);
                state.buffer.add(ev);
                if (state.buffer.size() > MAX_BUFFER_SIZE) {
                    trimBuffer(state.buffer);
                }
                Iterator<SseEmitter> it = state.subscribers.iterator();
                while (it.hasNext()) {
                    SseEmitter emitter = it.next();
                    try {
                        emitter.send(SseEmitter.event().id(String.valueOf(id)).name(eventName).data(jsonData));
                        if (isDone) {
                            log.debug("Sent final 'done' event to subscriber for {}", conversationId);
                        }
                    } catch (IOException | IllegalStateException e) {
                        log.debug("Removing dead subscriber for {} while sending {} event: {}",
                                conversationId, eventName, e.getMessage());
                        it.remove();
                    }
                }
            }
            // done events do not flow through eventRelays; async_task_* should
            // also short-circuit since relays exist for delta-style streaming
            // events, not lifecycle markers.
            return;
        }

        // Heartbeat is ephemeral keep-alive — must reach subscribers even when
        // state.done=true (e.g. a reconnected emitter waiting for late
        // async_task_* events). Skip the buffer (heartbeats are not replayable).
        if (isHeartbeat) {
            if (state == null) return;
            synchronized (state.lock) {
                Iterator<SseEmitter> it = state.subscribers.iterator();
                while (it.hasNext()) {
                    SseEmitter emitter = it.next();
                    try {
                        emitter.send(SseEmitter.event().name(eventName).data(jsonData));
                    } catch (IOException | IllegalStateException e) {
                        log.debug("Removing dead subscriber for {} while sending heartbeat: {}",
                                conversationId, e.getMessage());
                        it.remove();
                    }
                }
            }
            return;
        }

        // 普通事件：检查流状态
        if (state == null || state.done) {
            return;
        }

        synchronized (state.lock) {
            long id = ++state.nextEventId;
            SseEvent event = new SseEvent(id, eventName, jsonData);
            state.buffer.add(event);
            // buffer 容量保护：超出上限时优先丢弃 thinking_delta（占比最大且非关键）
            if (state.buffer.size() > MAX_BUFFER_SIZE) {
                trimBuffer(state.buffer);
            }
            Iterator<SseEmitter> it = state.subscribers.iterator();
            while (it.hasNext()) {
                SseEmitter emitter = it.next();
                try {
                    emitter.send(SseEmitter.event().id(String.valueOf(id)).name(eventName).data(jsonData));
                } catch (IOException | IllegalStateException e) {
                    log.debug("Removing dead subscriber for {}: {}", conversationId, e.getMessage());
                    it.remove();
                }
            }
        }

        // 事件 relay：转发给注册的监听器（用于子会话→父会话进度传递）
        List<java.util.function.BiConsumer<String, String>> relays = eventRelays.get(conversationId);
        if (relays != null) {
            for (var relay : relays) {
                try {
                    relay.accept(eventName, jsonData);
                } catch (Exception e) {
                    log.debug("Event relay error for {}: {}", conversationId, e.getMessage());
                }
            }
        }
    }

    /**
     * 直推事件（Object 自动序列化为 JSON）。
     * <p>
     * 用于在 Node 内部直接向前端推送 SSE 事件，绕过 NodeOutput 管道。
     * 典型场景：审批请求在 awaitDecision() 阻塞前必须先送达前端。
     *
     * @param conversationId 会话 ID
     * @param eventName      SSE 事件名称（如 tool_approval_requested）
     * @param data           事件载荷，将被 Jackson 序列化为 JSON
     */
    public void broadcastObject(String conversationId, String eventName, Object data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Failed to serialize broadcast data for event {}: {}", eventName, e.getMessage());
            json = "{\"error\":\"serialization_failed\"}";
        }
        broadcast(conversationId, eventName, json);
    }

    /**
     * Broadcast {@code payload} as a single SSE event when its serialized form
     * fits within {@link #CHUNK_SIZE}; otherwise extract the long {@code result}
     * field and emit it as ordered {@code tool_result_chunk} events.
     * <p>
     * Each chunk carries:
     * <pre>
     * {
     *   "kind":  "tool_result",
     *   "scope": "parent",         // sub-agent producers will set "subagent"
     *   "ref":   "&lt;refKey&gt;",
     *   "seq":   &lt;0..N&gt;,
     *   "final": &lt;true|false&gt;,
     *   "delta": "&lt;text&gt;"
     * }
     * </pre>
     * The last chunk has {@code "final": true}; consumers reassemble by
     * concatenating {@code delta} in seq order keyed on {@code ref}. When the
     * payload's {@code result} field cannot be located (or chunked transport
     * is disabled), the entire envelope is sent unchanged.
     *
     * @param conversationId target conversation
     * @param eventName      SSE event name for the small-payload path
     * @param payload        envelope; the {@code result} field (or, failing
     *                       that, the {@code arguments} field) is split
     * @param refKey         identifier consumers use to group chunks; usually
     *                       {@code toolCallId} or the step index as a string
     */
    public void broadcastChunked(String conversationId, String eventName,
                                  Object payload, String refKey) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize chunked broadcast for event {}: {}",
                    eventName, e.getMessage());
            return;
        }

        if (!chunkedToolResultsEnabled || json.length() <= CHUNK_SIZE) {
            broadcast(conversationId, eventName, json);
            return;
        }

        // Find a long string field worth splitting; tool results live under
        // "result", approval payloads under "arguments". Falling back to the
        // whole envelope keeps the transport correct even for unknown shapes
        // (the consumer can still concatenate by ref+seq and decode itself).
        Map<String, Object> envelope = asMap(payload);
        String fieldKey = null;
        String longText = null;
        if (envelope != null) {
            Object resultField = envelope.get("result");
            Object argsField = envelope.get("arguments");
            if (resultField instanceof String s && s.length() > CHUNK_SIZE / 2) {
                fieldKey = "result";
                longText = s;
            } else if (argsField instanceof String s && s.length() > CHUNK_SIZE / 2) {
                fieldKey = "arguments";
                longText = s;
            }
        }

        if (longText == null) {
            // No splittable string field — emit unchanged and let the client
            // handle the larger envelope as best it can.
            broadcast(conversationId, eventName, json);
            return;
        }

        // 1. Send a header event with the long field replaced by an empty
        //    placeholder so consumers see the same envelope shape; the body
        //    arrives via the chunk events that follow.
        Map<String, Object> headerEnvelope = new java.util.LinkedHashMap<>(envelope);
        headerEnvelope.put(fieldKey, "");
        headerEnvelope.put("chunked", true);
        headerEnvelope.put("chunkRef", refKey != null ? refKey : "");
        try {
            String headerJson = objectMapper.writeValueAsString(headerEnvelope);
            broadcast(conversationId, eventName, headerJson);
        } catch (Exception e) {
            log.warn("Failed to serialize chunk header for {}: {}", eventName, e.getMessage());
            broadcast(conversationId, eventName, json);
            return;
        }

        // 2. Stream the body in fixed-size slices.
        int total = longText.length();
        int offset = 0;
        int seq = 0;
        // Reserve room in CHUNK_SIZE for the JSON envelope around the slice;
        // 256 bytes covers kind/scope/ref/seq/final + JSON escapes.
        final int sliceMax = Math.max(512, CHUNK_SIZE - 256);
        while (offset < total) {
            int end = Math.min(offset + sliceMax, total);
            String slice = longText.substring(offset, end);
            boolean isFinal = end >= total;
            Map<String, Object> chunk = new java.util.LinkedHashMap<>();
            chunk.put("kind", "tool_result");
            chunk.put("scope", "parent");
            chunk.put("ref", refKey != null ? refKey : "");
            chunk.put("seq", seq);
            chunk.put("final", isFinal);
            chunk.put("delta", slice);
            try {
                String chunkJson = objectMapper.writeValueAsString(chunk);
                broadcast(conversationId, "tool_result_chunk", chunkJson);
            } catch (Exception e) {
                log.warn("Failed to serialize tool_result_chunk seq={}: {}", seq, e.getMessage());
                return;
            }
            offset = end;
            seq++;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object payload) {
        if (payload instanceof Map<?, ?> m) {
            try {
                return (Map<String, Object>) m;
            } catch (ClassCastException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Diagnostic helper for the multi-node deployment edge case (issue #17):
     * tells the caller whether a {@link RunState} for this conversation
     * exists on <em>this</em> JVM at all (regardless of done state).
     *
     * <p>{@link #attach(String, SseEmitter)} returns {@code false} both when
     * the stream finished normally <em>and</em> when no state exists on this
     * node. Callers that need to distinguish those two cases (e.g. to send a
     * different SSE event to the client) should consult this method first.
     *
     * @return {@code true} when a RunState exists locally for this
     *         conversationId; {@code false} when it never existed here OR was
     *         already cleaned up after completion
     */
    public boolean streamExistsOnThisNode(String conversationId) {
        return runs.containsKey(conversationId);
    }

    /**
     * 将 emitter 附着到现有的运行中或刚刚完成的流。
     * 先回放 buffer 中的全部事件，再加入订阅者列表接收后续实时事件（仅当流仍在运行时）。
     * <p>
     * 兼容"流已完成"语义：如果 RunState 还在 map 里但 done=true，仍然回放 buffer
     * （包含 done 事件本身），让重连客户端拿到完成信号后正常退出"生成中"状态。
     * RunState 完成后会保留 DONE_RETENTION_MS（5 分钟），由 cleanupStaleRuns 异步清理；
     * 这段窗口期内任何刷新页面都能拿到 done 回放。
     *
     * @return true 如果成功附着或重放（订阅者已加入或事件已重放完毕），false 如果没有任何状态可恢复
     */
    public boolean attach(String conversationId, SseEmitter emitter) {
        return attach(conversationId, emitter, 0L);
    }

    /**
     * Reconnect-aware attach: replays only events whose id &gt;
     * {@code lastEventId}. Pass 0 to replay everything (fresh attach
     * behavior — same as the no-arg overload).
     *
     * <p>The id is the per-conversation monotonic sequence stamped on
     * each {@link SseEvent} when it was first emitted. Frontend tracks
     * the last id it processed and echoes it back via the request
     * body's {@code lastEventId} field, eliminating the duplicate-
     * delivery class of bugs (the symptom: thinking segments rendered
     * with the wrong iterationIndex because frontend processed the
     * same {@code iteration_start} twice).
     */
    public boolean attach(String conversationId, SseEmitter emitter, long lastEventId) {
        RunState state = runs.get(conversationId);
        if (state == null) {
            return false;
        }
        synchronized (state.lock) {
            // Replay buffer with id-based dedup. Each buffered event keeps its
            // original (1:1) id, so the skip condition is the simple
            // `id <= lastEventId`. trimBuffer no longer merges delta events,
            // so a single id always corresponds to a single contiguous run of
            // text — there's no straddling-range edge case.
            int replayed = 0;
            int skipped = 0;
            for (SseEvent event : state.buffer) {
                if (event.id() <= lastEventId) {
                    skipped++;
                    continue;
                }
                try {
                    emitter.send(SseEmitter.event().id(String.valueOf(event.id())).name(event.name()).data(event.json()));
                    replayed++;
                } catch (IOException | IllegalStateException e) {
                    log.warn("Failed to replay buffer to reconnecting client for {}: {}",
                            conversationId, e.getMessage());
                    return false;
                }
            }
            if (lastEventId > 0 && skipped > 0) {
                log.info("[SSE] Reconnect dedup for {}: skipped {} already-seen events, replayed {} new",
                        conversationId, skipped, replayed);
            }
            // Stream complete: buffer replayed (including the `done` event itself).
            // We DO NOT auto-complete the emitter here — keep it subscribed so any
            // late-arriving async_task_* events (image/video/music generation that
            // outlasts the agent's reasoning turn) reach the client live. Idle
            // emitters are pruned naturally when:
            //   - the next broadcast hits a broken pipe and removes the dead subscriber
            //   - cleanupStaleRuns() removes the RunState after DONE_RETENTION_MS (5 min)
            //   - the frontend explicitly disconnects (component unmount / navigation)
            // Without this, async_task_completed fired after `done` would be silently
            // dropped, leaving the chat UI stuck on the "正在生成中" placeholder.
            state.subscribers.add(emitter);
            if (state.done) {
                log.info("[SSE] Replayed {} buffered events; emitter stays subscribed for late async events: {}",
                        state.buffer.size(), conversationId);
                // Restart heartbeat so the proxy/Tomcat 60s idle timeout doesn't
                // close the reconnected emitter before the async_task_* event fires.
                // The scheduler self-stops once subscribers go empty (see startHeartbeat).
                startHeartbeat(conversationId);
                return true;
            }
        }
        log.info("[SSE] Client reconnected for conversation={}, replaying {} buffered events",
                conversationId, state.buffer.size());
        return true;
    }

    /**
     * 递增活跃 Flux 计数（每个 Flux 订阅开始时调用）。
     * 原始流和审批 Replay 流共享同一个 RunState，通过计数协调生命周期。
     */
    public void incrementFlux(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            synchronized (state.lock) {
                state.activeFluxCount++;
                log.debug("Flux count incremented: {} (count={})", conversationId, state.activeFluxCount);
            }
        }
    }

    /**
     * 完成结果：包含是否全部完成、排队消息快照
     */
    public record CompletionResult(boolean allDone, QueuedInput queuedInput) {}

    /**
     * 标记一个 Flux 完成。仅在所有 Flux 都完成时才真正移除 RunState。
     * <p>
     * 这解决了"原始流完成关闭 SSE，但 Replay 流仍在运行"的竞态问题。
     * <p>
     * <b>无副作用</b>：不消费排队消息。适用于不关心 queue 的路径（approval deny、setup error 等）。
     * 需要链式续跑的路径应使用 {@link #completeAndConsumeIfLast(String)}。
     *
     * @return true 如果这是最后一个 Flux（RunState 已被移除），false 如果仍有活跃 Flux
     */
    public boolean complete(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null) {
            return true;
        }
        synchronized (state.lock) {
            state.activeFluxCount = Math.max(0, state.activeFluxCount - 1);
            if (state.activeFluxCount > 0) {
                log.debug("Stream partially completed (no queue drain): {} (remaining flux={})",
                        conversationId, state.activeFluxCount);
                return false;
            }
        }
        // 所有 Flux 都已完成，停止心跳，标记 done 但**不立即移除 RunState**——
        // 留给 cleanupStaleRuns 在 DONE_RETENTION_MS 后异步清理。这段窗口期内
        // 客户端刷新页面 attach() 能从 buffer 回放 done 事件，UI 不会卡在
        // "生成中"。之前立即 runs.remove() 是 SSE 中途断开导致 done 永远丢的根源。
        stopHeartbeat(conversationId);
        state.done = true;
        log.debug("Stream fully completed (no queue drain): {} (kept in map for {}ms reconnect window)",
                conversationId, DONE_RETENTION_MS);
        return true;
    }

    /**
     * 原子地递减 activeFluxCount，仅在最后一个 Flux 完成时消费排队消息并移除 RunState。
     * <p>
     * 将「递减计数 → 消费 queue → 删除 RunState」三步收口到同一个临界区，
     * 避免非最后一个 flux 提前 consume 导致 queue 丢失，也避免 complete 后查不到 queue。
     *
     * @return CompletionResult(allDone, queuedInput)
     */
    public CompletionResult completeAndConsumeIfLast(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null) {
            return new CompletionResult(true, null);
        }
        QueuedInput consumed = null;
        synchronized (state.lock) {
            state.activeFluxCount = Math.max(0, state.activeFluxCount - 1);
            if (state.activeFluxCount > 0) {
                log.debug("Stream partially completed: {} (remaining flux={}, queuePreserved={})",
                        conversationId, state.activeFluxCount, !state.messageQueue.isEmpty());
                return new CompletionResult(false, null);
            }
            // 最后一个 Flux：在同一个锁内消费排队消息（取队首）
            consumed = state.messageQueue.poll();
        }
        // 锁外：停止心跳，标记 done。**不立即移除 RunState**——保留 DONE_RETENTION_MS
        // 让客户端可在窗口期内刷新页面通过 attach() 回放 done 事件。
        stopHeartbeat(conversationId);
        state.done = true;
        log.debug("Stream fully completed: {} (hasQueuedSnapshot={}, kept in map for {}ms reconnect window)",
                conversationId, consumed != null, DONE_RETENTION_MS);
        return new CompletionResult(true, consumed);
    }

    /**
     * 检查指定会话是否有正在运行的流
     */
    public boolean isRunning(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null && !state.done;
    }

    /**
     * 从订阅者列表中移除指定 emitter（连接断开/超时时调用）
     */
    public void detach(String conversationId, SseEmitter emitter) {
        RunState state = runs.get(conversationId);
        if (state == null) {
            return;
        }
        synchronized (state.lock) {
            state.subscribers.remove(emitter);
        }
        log.debug("Emitter detached from stream: {} (remaining={})",
                conversationId, state.subscribers.size());
    }

    // ===== Heartbeat =====

    /**
     * Pick the heartbeat cadence (seconds) that matches the run's current
     * phase. Pre-token gaps need fast keep-alives so the UI shows activity;
     * tool execution stretches slightly; mid-stream is rate-limited because
     * deltas already keep the connection warm.
     */
    private int currentHeartbeatIntervalSec(RunState state) {
        if (state.runningToolName != null && !state.runningToolName.isEmpty()) {
            return heartbeatToolSec;
        }
        return state.firstTokenReceived ? heartbeatStreamingSec : heartbeatPreTokenSec;
    }

    /**
     * 启动心跳定时器。在流注册后调用，定期向前端发送 heartbeat 事件。
     * 防止 useStream 的 60 秒无数据 timeout 误杀等待审批/长工具的流。
     */
    public void startHeartbeat(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null) return;
        // 避免重复启动
        if (state.heartbeatFuture != null && !state.heartbeatFuture.isDone()) return;

        int intervalSec = currentHeartbeatIntervalSec(state);
        state.heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                RunState s = runs.get(conversationId);
                if (s == null) {
                    stopHeartbeat(conversationId);
                    return;
                }
                // Continue heartbeating post-done as long as someone is still listening
                // (reconnected emitter waiting for late async_task_* events). Stop only
                // when the run is done AND the subscribers list is empty — otherwise the
                // 60s idle proxy timeout drops the reconnected emitter and async events
                // never reach the client live.
                if (s.done && s.subscribers.isEmpty()) {
                    stopHeartbeat(conversationId);
                    return;
                }
                String json;
                try {
                    json = objectMapper.writeValueAsString(Map.of(
                            "conversationId", conversationId,
                            "currentPhase", safe(s.currentPhase),
                            "waitingReason", safe(s.waitingReason),
                            "runningToolName", safe(s.runningToolName),
                            "queueLength", s.messageQueue.size(),
                            "timestamp", System.currentTimeMillis()
                    ));
                } catch (Exception e) {
                    json = "{\"conversationId\":\"" + conversationId + "\"}";
                }
                broadcast(conversationId, "heartbeat", json);
            } catch (Exception e) {
                log.debug("Heartbeat error for {}: {}", conversationId, e.getMessage());
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS);
    }

    /**
     * Mark that the first content/thinking token has been received for this
     * run and reschedule the heartbeat at the streaming cadence.
     * <p>
     * Called from the LLM streaming layer so the heartbeat relaxes once the
     * connection is naturally being kept warm by data deltas. Idempotent — a
     * second call is a no-op.
     */
    public void markFirstTokenReceived(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null) return;
        if (state.firstTokenReceived) return;
        state.firstTokenReceived = true;
        rescheduleHeartbeat(conversationId);
    }

    /**
     * Cancels the active heartbeat (if any) and starts a new one at the
     * cadence currently appropriate for the run state. Public so callers that
     * mutate {@code runningToolName} can request a tool-cadence heartbeat.
     */
    public void rescheduleHeartbeat(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null || state.done) return;
        if (state.heartbeatFuture != null) {
            state.heartbeatFuture.cancel(false);
            state.heartbeatFuture = null;
        }
        startHeartbeat(conversationId);
    }

    /**
     * 停止心跳定时器
     */
    public void stopHeartbeat(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state != null && state.heartbeatFuture != null) {
            state.heartbeatFuture.cancel(false);
            state.heartbeatFuture = null;
        }
    }

    // ===== Phase tracking =====

    /**
     * 更新当前执行阶段（用于 heartbeat 和前端状态展示）
     */
    public void updatePhase(String conversationId, String phase) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            state.currentPhase = phase;
        }
    }

    /**
     * 更新当前正在执行的工具名称
     */
    public void updateRunningTool(String conversationId, String toolName) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            String previous = state.runningToolName;
            state.runningToolName = toolName;
            // Heartbeat cadence depends on whether a tool is in flight; switch
            // cadences when the tool slot transitions in either direction.
            boolean wasRunning = previous != null && !previous.isEmpty();
            boolean nowRunning = toolName != null && !toolName.isEmpty();
            if (wasRunning != nowRunning) {
                rescheduleHeartbeat(conversationId);
            }
        }
    }

    /**
     * Read-only accessor for the currently running tool name on a conversation.
     * Returns {@code null} when no run state exists or no tool is in flight.
     * Used by external observers (heartbeat watchdog, status APIs) that need
     * to probe progress without mutating the run.
     */
    public String getRunningToolName(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null ? state.runningToolName : null;
    }

    /**
     * Read-only accessor for the current execution phase. Returns {@code null}
     * when no run state exists. Mirrors {@link #getRunningToolName(String)} so
     * external observers can read both fields without touching internals.
     */
    public String getCurrentPhase(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null ? state.currentPhase : null;
    }

    /**
     * 设置等待原因
     */
    public void setWaitingReason(String conversationId, String reason) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            state.waitingReason = reason;
        }
    }

    // ===== Interrupt with follow-up =====

    /**
     * 请求中断当前流并排队一条用户消息。
     * 与 requestStop 的区别：中断后自动续跑排队消息，而非停在原地。
     *
     * @return true 如果成功请求了中断
     */
    public boolean requestInterrupt(String conversationId, String queuedMessage, Long agentId, boolean persisted) {
        return requestInterrupt(conversationId, queuedMessage, agentId, persisted, null);
    }

    public boolean requestInterrupt(String conversationId, String queuedMessage, Long agentId,
                                    boolean persisted, List<MessageContentPart> contentParts) {
        RunState state = runs.get(conversationId);
        if (state == null || state.done) {
            return false;
        }

        // 在锁内完成入队和 Disposable 可用性判断，锁外执行 dispose/broadcast
        Disposable toDispose = null;
        boolean canInterrupt;
        synchronized (state.lock) {
            Disposable d = state.disposable;
            canInterrupt = d != null && !d.isDisposed();
            // 无论是否可中断，都入队（支持多条排队消息）
            state.messageQueue.offer(new QueuedInput(queuedMessage, agentId, persisted, contentParts));
            if (canInterrupt) {
                state.interruptType = InterruptType.USER_INTERRUPT_WITH_FOLLOWUP;
                state.stopRequested.set(true);
                toDispose = d;
            }
            // 不可中断时不设 interruptType / stopRequested
        }

        // 锁外执行 dispose 和 broadcast（这些可能阻塞或耗时）
        if (canInterrupt) {
            toDispose.dispose();
            log.info("Stream interrupted for follow-up: {} (queued: {})", conversationId,
                    queuedMessage != null ? queuedMessage.substring(0, Math.min(30, queuedMessage.length())) : "null");
            try {
                String json = objectMapper.writeValueAsString(Map.of(
                        "conversationId", conversationId,
                        "queuedMessage", queuedMessage != null ? queuedMessage : "",
                        "timestamp", System.currentTimeMillis()
                ));
                broadcast(conversationId, "turn_interrupt_requested", json);
            } catch (Exception e) {
                log.warn("Failed to broadcast turn_interrupt_requested: {}", e.getMessage());
            }
            return true;
        }

        log.info("Interrupt requested but Disposable unavailable, message queued only: {} (queued: {})",
                conversationId,
                queuedMessage != null ? queuedMessage.substring(0, Math.min(30, queuedMessage.length())) : "null");
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "conversationId", conversationId,
                    "queuedMessage", queuedMessage != null ? queuedMessage : "",
                    "timestamp", System.currentTimeMillis()
            ));
            broadcast(conversationId, "queued_input_accepted", json);
        } catch (Exception e) {
            log.warn("Failed to broadcast queued_input_accepted: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 将消息加入队列但不中断当前执行（用于不可中断阶段）。
     */
    public boolean enqueueMessage(String conversationId, String message, Long agentId, boolean persisted) {
        return enqueueMessage(conversationId, message, agentId, persisted, null);
    }

    public boolean enqueueMessage(String conversationId, String message, Long agentId, boolean persisted,
                                  List<MessageContentPart> contentParts) {
        RunState state = runs.get(conversationId);
        // Reject when there's no live producer to drain the queue:
        //   - state == null:  conversation truly gone (cleanup completed)
        //   - state.done:     stream's doOnComplete has already fired and
        //                     called completeAndConsumeIfLast — no later
        //                     consumer is guaranteed to invoke
        //                     startQueuedMessage. Accepting an enqueue here
        //                     would silently park the message in memory
        //                     until the 5-minute retention sweep deletes it.
        // Frontend treats `queued: false` as the cue to fall back to a fresh
        // send (after the stale isGenerating settles), eliminating the
        // race that previously merged messages into the prior turn.
        if (state == null || state.done) {
            return false;
        }
        state.messageQueue.offer(new QueuedInput(message, agentId, persisted, contentParts));
        // broadcast 在锁外
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "conversationId", conversationId,
                    "queuedMessage", message,
                    "timestamp", System.currentTimeMillis()
            ));
            broadcast(conversationId, "queued_input_accepted", json);
        } catch (Exception e) {
            log.warn("Failed to broadcast queued_input_accepted: {}", e.getMessage());
        }
        return true;
    }

    /**
     * 排队输入的原子快照（message + agentId + persisted + contentParts 一起返回，避免分离读取导致不一致）
     */
    public record QueuedInput(String message, Long agentId, boolean persisted,
                              List<MessageContentPart> contentParts) {
        public QueuedInput(String message, Long agentId, boolean persisted) {
            this(message, agentId, persisted, null);
        }
    }

    /**
     * 原子消费排队的输入（流完成/中断后调用）。
     * 从队列头部取出一条消息。
     */
    public QueuedInput consumeQueuedInput(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null) return null;
        return state.messageQueue.poll();
    }

    /**
     * @deprecated Use {@link #consumeQueuedInput(String)} instead.
     */
    @Deprecated
    public String consumeQueuedMessage(String conversationId) {
        QueuedInput input = consumeQueuedInput(conversationId);
        return input != null ? input.message() : null;
    }

    /**
     * @deprecated 多消息队列模式下，改为在入队时直接传入 persisted 参数。
     */
    @Deprecated
    public boolean markQueuedMessagePersisted(String conversationId) {
        // 向后兼容：无操作（persisted 已在入队时设定）
        return true;
    }

    /**
     * 获取中断类型
     */
    public InterruptType getInterruptType(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null ? state.interruptType : null;
    }

    /**
     * 清除中断状态
     */
    public void clearInterruptState(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state != null) {
            state.interruptType = null;
        }
    }

    /**
     * 检查是否有排队消息
     */
    public boolean hasQueuedMessage(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null && !state.messageQueue.isEmpty();
    }

    /**
     * 获取当前排队消息数量
     */
    public int getQueueSize(String conversationId) {
        RunState state = runs.get(conversationId);
        return state != null ? state.messageQueue.size() : 0;
    }

    // ===== Approval idempotency =====

    /**
     * 尝试标记一个 approval ID 为已广播。如果已经广播过则返回 false（幂等去重）。
     */
    public boolean markApprovalBroadcasted(String conversationId, String pendingId) {
        RunState state = runs.get(conversationId);
        if (state == null) return false;
        return state.broadcastedApprovalIds.add(pendingId);
    }

    // ===== Utility =====

    private static String safe(String s) {
        return s != null ? s : "";
    }

    /**
     * Trim the replay buffer to {@link #MAX_BUFFER_SIZE} entries while
     * preserving SSE-id semantics required by reconnect dedup.
     *
     * <p>We deliberately do NOT merge delta events even though it would
     * reduce entry count more aggressively. Merging concatenates a range
     * of original event ids into a single record; on reconnect a client
     * whose {@code lastEventId} falls inside the merged range would
     * either re-receive the head text (replay = duplicate) or lose the
     * tail text (skip = data loss). Both are correctness bugs, and the
     * dropping strategy below avoids them entirely — events kept in the
     * buffer always correspond 1:1 to the ids the client originally saw.
     *
     * <p>Strategy (must be called under {@code state.lock}):
     * <ol>
     *   <li>Drop earliest {@code thinking_delta} entries — thinking text
     *       is not part of the canonical answer; losing the head of a
     *       very long reasoning trace on reconnect is acceptable.</li>
     *   <li>If still over the cap, drop earliest {@code content_delta}
     *       entries. This loses visible answer text, but only after we've
     *       buffered &gt; {@link #MAX_BUFFER_SIZE} events — &gt;1 MB of
     *       output. Rare enough that we accept the trade-off rather
     *       than mangle reconnect semantics.</li>
     * </ol>
     */
    private static void trimBuffer(List<SseEvent> buffer) {
        if (buffer.size() <= MAX_BUFFER_SIZE) return;
        int target = buffer.size() - MAX_BUFFER_SIZE;

        // Pass 1: drop earliest thinking_delta entries.
        Iterator<SseEvent> it = buffer.iterator();
        while (it.hasNext() && target > 0) {
            SseEvent e = it.next();
            if ("thinking_delta".equals(e.name())) {
                it.remove();
                target--;
            }
        }

        // Pass 2: if still over the cap, drop earliest content_delta entries.
        if (target > 0) {
            it = buffer.iterator();
            while (it.hasNext() && target > 0) {
                SseEvent e = it.next();
                if ("content_delta".equals(e.name())) {
                    it.remove();
                    target--;
                }
            }
        }
        log.debug("Buffer trimmed: {} events remain", buffer.size());
    }

    // ==================== Stale RunState 清理 ====================

    /** 已完成的 RunState 保留时间（5 分钟） */
    private static final long DONE_RETENTION_MS = 5 * 60 * 1000;
    /** RunState 最大存活时间（30 分钟，防止挂起的流永远占内存） */
    private static final long MAX_LIFETIME_MS = 30 * 60 * 1000;

    /**
     * 定期清理过期的 RunState，防止内存泄漏。
     * - 已完成超过 5 分钟的 → 移除
     * - 存活超过 30 分钟的（无论是否完成）→ 强制移除
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 600_000)
    public void cleanupStaleRuns() {
        long now = System.currentTimeMillis();
        int evicted = 0;

        var iterator = runs.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            RunState state = entry.getValue();
            long age = now - state.createdAt;

            boolean shouldEvict = false;
            String reason = null;

            if (state.done && age > DONE_RETENTION_MS) {
                shouldEvict = true;
                reason = "completed and expired";
            } else if (age > MAX_LIFETIME_MS) {
                shouldEvict = true;
                reason = "exceeded max lifetime (" + (age / 1000) + "s)";
            }

            if (shouldEvict) {
                // 先清理资源再移除
                stopHeartbeat(entry.getKey());
                Disposable d = state.disposable;
                if (d != null && !d.isDisposed()) {
                    d.dispose();
                }
                iterator.remove();
                evicted++;
                log.warn("[SSE] Evicted stale RunState for conversation={}: {}",
                        entry.getKey(), reason);
            }
        }

        if (evicted > 0) {
            log.info("[SSE] Cleanup completed: evicted {} stale RunState entries, {} remaining",
                    evicted, runs.size());
        }

        // Age out the recycled-marker map alongside RunState cleanup. Same
        // 5-minute retention so a delayed doOnComplete still hits the marker
        // while we don't keep entries around forever.
        recycledConversations.entrySet().removeIf(e -> now - e.getValue() > DONE_RETENTION_MS);
    }

    /**
     * Flush in-flight runs before JVM shutdown.
     * <p>
     * Spring closes singleton beans in reverse construction order; ConversationService /
     * Hikari outlive ChatStreamTracker, so saveMessage from {@link #onShutdown()} still
     * has a working DB connection. Without this, a {@code mvn spring-boot:run} restart or
     * SIGTERM during a turn races against the Reactor cancellation: the doOnError /
     * doOnComplete saveMessage may not run before HikariPool shuts down, leaving the
     * conversation with only the user message and no assistant reply (the
     * "对话框里除了问题外什么也没留下" symptom seen in production logs at 07:23:02).
     * <p>
     * Behavior:
     * <ol>
     *   <li>Walk every active (not-done) RunState.</li>
     *   <li>Invoke its registered emergencySaveCallback synchronously — the callback
     *       (set by ChatController) snapshots the current accumulator and persists it
     *       as an "interrupted" assistant message.</li>
     *   <li>Dispose the Reactor disposable so the LLM stream terminates promptly.</li>
     * </ol>
     * The callback must tolerate normal doOnError/doOnComplete having raced and saved
     * already; the latest commit wins for that conversation.
     */
    @PreDestroy
    public void onShutdown() {
        int active = (int) runs.values().stream().filter(s -> !s.done).count();
        if (active == 0) {
            log.info("[ChatStreamTracker] Shutdown: no active runs to flush");
            return;
        }
        log.warn("[ChatStreamTracker] Shutdown: flushing {} active run(s) before JVM exit",
                active);
        for (Map.Entry<String, RunState> entry : runs.entrySet()) {
            RunState state = entry.getValue();
            if (state.done) continue;
            String cid = entry.getKey();
            try {
                Runnable callback = state.emergencySaveCallback;
                if (callback != null) {
                    log.info("[ChatStreamTracker] Emergency-saving in-flight run: {}", cid);
                    callback.run();
                } else {
                    log.warn("[ChatStreamTracker] No emergency-save callback for active run: {} " +
                            "(content may be lost)", cid);
                }
            } catch (Exception e) {
                log.error("[ChatStreamTracker] Emergency save failed for {}: {}",
                        cid, e.getMessage(), e);
            }
            try {
                Disposable d = state.disposable;
                if (d != null && !d.isDisposed()) {
                    d.dispose();
                }
            } catch (Exception e) {
                log.warn("[ChatStreamTracker] Disposable.dispose failed for {}: {}",
                        cid, e.getMessage());
            }
        }
    }

    // ===== Runtime snapshot surface (admin Live view) =====

    /**
     * Bind the resolved agent + owner to the active run so the runtime
     * snapshot can label cards without re-querying the conversation table.
     * Idempotent — overwrites are fine because both fields are observation-
     * only metadata.
     */
    public void bindRunMeta(String conversationId, Long agentId, String username) {
        RunState s = runs.get(conversationId);
        if (s == null) return;
        if (agentId != null) s.agentId = agentId;
        if (username != null) s.username = username;
    }

    /**
     * Immutable view of one in-flight run. Computed eagerly under the
     * RunState lock so the receiver sees a consistent picture even if the
     * underlying state mutates while it iterates.
     */
    public record RunSnapshot(
            String conversationId,
            Long agentId,
            String username,
            String currentPhase,
            String runningToolName,
            String waitingReason,
            boolean done,
            boolean stopRequested,
            boolean firstTokenReceived,
            int subscriberCount,
            int queueLen,
            int activeFluxCount,
            long createdAt,
            long lastEventAt,
            long ageMs,
            long msSinceLastEvent
    ) {}

    /**
     * Snapshot every active run. Used by the admin Live view to render the
     * global "what are my agents doing right now" view. Returned list is a
     * defensive copy — callers may freely sort / filter it.
     */
    public List<RunSnapshot> getAllSnapshot() {
        long now = System.currentTimeMillis();
        List<RunSnapshot> out = new ArrayList<>(runs.size());
        for (RunState s : runs.values()) {
            int subs;
            int queue;
            synchronized (s.lock) {
                subs = s.subscribers.size();
                queue = s.messageQueue.size();
            }
            out.add(new RunSnapshot(
                    s.conversationId,
                    s.agentId,
                    s.username,
                    s.currentPhase,
                    s.runningToolName,
                    s.waitingReason,
                    s.done,
                    s.stopRequested.get(),
                    s.firstTokenReceived,
                    subs,
                    queue,
                    s.activeFluxCount,
                    s.createdAt,
                    s.lastEventAt,
                    now - s.createdAt,
                    now - s.lastEventAt
            ));
        }
        return out;
    }

    /**
     * Force a wedged run to terminate. Used by the admin Live view's
     * "End it" action when the friendly stop has been observed not to take
     * effect (model wedged in a tool call beyond the timeout). Sequence
     * matches what {@link #onShutdown()} does for individual runs.
     *
     * @return true when a run was found and torn down; false if already gone
     */
    public boolean forceRecycle(String conversationId) {
        RunState state = runs.get(conversationId);
        if (state == null) return false;
        // Mark BEFORE dispose so a doOnComplete that fires the same millisecond
        // (the upstream agent flux had already buffered/completed concurrently
        // with the dispose call) sees the recycled flag and skips its save.
        recycledConversations.put(conversationId, System.currentTimeMillis());
        // Persist any partial assistant content first — dispose() only severs
        // the downstream subscription, the agent's worker thread keeps running
        // and may not yield for minutes. Without this, the conversation row
        // shows only the user message until the late doOnComplete fires.
        Runnable callback = state.emergencySaveCallback;
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception e) {
                log.warn("forceRecycle: emergency save failed for {}: {}", conversationId, e.getMessage());
            }
        }
        try {
            state.stopRequested.set(true);
            state.interruptType = InterruptType.USER_STOP;
            Disposable d = state.disposable;
            if (d != null && !d.isDisposed()) {
                d.dispose();
            }
        } catch (Exception e) {
            log.warn("forceRecycle: dispose failed for {}: {}", conversationId, e.getMessage());
        }
        try {
            state.done = true;
            stopHeartbeat(conversationId);
        } catch (Exception e) {
            log.warn("forceRecycle: heartbeat stop failed for {}: {}", conversationId, e.getMessage());
        }
        synchronized (state.lock) {
            for (SseEmitter em : state.subscribers) {
                try { em.complete(); } catch (Exception ignored) {}
            }
            state.subscribers.clear();
        }
        runs.remove(conversationId);
        log.info("forceRecycle: run {} torn down", conversationId);
        return true;
    }
}
