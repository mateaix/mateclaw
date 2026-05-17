package vip.mate.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.task.model.AsyncTaskEntity;
import vip.mate.task.model.AsyncTaskInfo;
import vip.mate.task.repository.AsyncTaskMapper;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 通用异步任务服务 — 管理长耗时任务的生命周期（提交、轮询、完成回写）
 * <p>
 * 可复用于视频生成、图片生成、音频生成等异步场景。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncTaskService implements ApplicationRunner {

    private final AsyncTaskMapper asyncTaskMapper;
    private final ChatStreamTracker streamTracker;

    /** Polling thread pool. Bumped from 2 to 8 in P0 — image+video+future generative
     *  tasks all share this pool, and per-task work (poll HTTP + DB write + file
     *  download in completion callbacks) is non-trivial; 2 threads saturate
     *  immediately under any concurrent load. */
    private final ScheduledExecutorService pollExecutor =
            Executors.newScheduledThreadPool(8, r -> {
                Thread t = new Thread(r, "async-task-poll");
                t.setDaemon(true);
                return t;
            });

    /** 活跃轮询任务，key = taskId */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activePolls = new ConcurrentHashMap<>();

    /** Reverse mapping taskId → conversationId so a {@link ConversationDeletedEvent}
     *  listener can cancel every poller belonging to the deleted conversation
     *  without scanning DB (the {@code mate_async_task} rows are gone by the
     *  time the after-commit event fires). Populated in {@link #startPolling};
     *  cleared in {@link #cancelPolling}. */
    private final ConcurrentHashMap<String, String> pollTaskToConv = new ConcurrentHashMap<>();

    /** Conversations whose deletion has fanned out to this service. Workers
     *  consult {@link #isConversationCanceled} before persisting anything tied
     *  to a conversation — the music virtual-thread worker, image/video poll
     *  completion handlers, and any future provider-level callback are
     *  asynchronous and may finish AFTER the conversation row + attachment
     *  directory have already been wiped. Without this gate they would
     *  recreate the directory + a dangling {@code mate_message} row.
     *  <p>
     *  Value = expiry epoch-ms. Entries older than {@link #CANCEL_RETENTION_MS}
     *  are reaped on each event and on each lookup so the map cannot grow
     *  without bound. The retention window is comfortably longer than
     *  {@link #MAX_POLL_DURATION_MINUTES} and the music worker's ~120s upstream
     *  HTTP timeout, so any in-flight worker for a deleted conversation will
     *  still see the cancel flag when it tries to write back. */
    private final ConcurrentHashMap<String, Long> canceledConversations = new ConcurrentHashMap<>();

    /** 30 minutes — covers MAX_POLL_DURATION_MINUTES (15) + music worker's
     *  ~2 min upstream blocking call with comfortable headroom. */
    private static final long CANCEL_RETENTION_MS = 30L * 60 * 1000;

    /** 每用户最多并行任务数 */
    private static final int MAX_ACTIVE_TASKS_PER_USER = 3;

    /** 默认轮询间隔（秒） */
    private static final int POLL_INTERVAL_SECONDS = 8;

    /** 最大轮询时长（分钟），超时自动标记失败 */
    private static final int MAX_POLL_DURATION_MINUTES = 15;

    /** 连续轮询失败次数上限，超出自动标记任务失败 */
    private static final int MAX_POLL_ERROR_COUNT = 5;

    /** 轮询连续错误计数 */
    private final ConcurrentHashMap<String, Integer> pollErrorCounts = new ConcurrentHashMap<>();

    // ==================== 任务创建 ====================

    /**
     * 创建一个异步任务记录
     *
     * @return 创建的任务实体
     */
    public AsyncTaskEntity createTask(String taskType, String conversationId,
                                       Long messageId, String providerName,
                                       String providerTaskId, String requestJson,
                                       String createdBy) {
        // 并发限制检查
        long activeCount = asyncTaskMapper.selectCount(
                new LambdaQueryWrapper<AsyncTaskEntity>()
                        .eq(AsyncTaskEntity::getCreatedBy, createdBy)
                        .in(AsyncTaskEntity::getStatus, List.of("pending", "running"))
        );
        if (activeCount >= MAX_ACTIVE_TASKS_PER_USER) {
            throw new IllegalStateException("已达到最大并行任务数（" + MAX_ACTIVE_TASKS_PER_USER + "），请等待现有任务完成");
        }

        AsyncTaskEntity entity = new AsyncTaskEntity();
        entity.setTaskId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        entity.setTaskType(taskType);
        entity.setStatus("pending");
        entity.setConversationId(conversationId);
        entity.setMessageId(messageId);
        entity.setProviderName(providerName);
        entity.setProviderTaskId(providerTaskId);
        entity.setRequestJson(requestJson);
        entity.setProgress(0);
        entity.setCreatedBy(createdBy);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        asyncTaskMapper.insert(entity);

        log.info("[AsyncTask] Created task {} (type={}, provider={}, providerTaskId={})",
                entity.getTaskId(), taskType, providerName, providerTaskId);
        return entity;
    }

    // ==================== One-shot Callable submission ====================

    /**
     * Submit a one-shot {@link Callable} that runs on this service's
     * {@code pollExecutor} and persists its outcome through the standard
     * {@code mate_async_task} lifecycle.
     * <p>
     * This is the local-work counterpart to {@link #startPolling}: instead of
     * polling an external provider, the worker runs {@code work.call()} in
     * the shared executor, writes the return value to {@code resultJson} (or
     * the exception message to {@code errorMessage}), and registers itself in
     * the same {@code activePolls} / {@code pollTaskToConv} bookkeeping so
     * {@link #onConversationDeleted} can cancel both kinds uniformly.
     * <p>
     * Race closure: the worker is scheduled at 0 ms but blocks on an internal
     * {@code CountDownLatch} until the calling thread has finished
     * registering both bookkeeping entries. Without this, the executor could
     * dequeue the worker, run its {@code finally} cleanup, and return — all
     * before {@code activePolls.put} runs on the calling thread — leaving a
     * ghost entry no later event drains.
     * <p>
     * Cancellation: while {@code work.call()} runs the worker has no
     * cooperative cancel signal beyond {@link #isConversationCanceled}; it
     * checks once before invoking the body and once after, so a parent
     * conversation deleted mid-run never lands as {@code succeeded}.
     * {@link #onConversationDeleted} additionally writes {@code failed}
     * synchronously for any non-terminal {@code agent_delegate} task so the
     * DB row never lingers in {@code running} after parent deletion.
     *
     * @param taskType        Discriminator written to {@code task_type}
     *                        (e.g. {@code "agent_delegate"}). Listeners
     *                        and the conversation-deleted DB write-back gate
     *                        on this value.
     * @param conversationId  Parent conversation ID. Written to
     *                        {@code conversation_id} so deleting the parent
     *                        conversation cascade-cancels the worker. Any
     *                        child / detached identifiers belong in
     *                        {@code requestJson}, not here.
     * @param messageId       Optional parent message ID.
     * @param requestJson     Caller-serialized request payload.
     * @param createdBy       Audit attribution; counts toward
     *                        {@code MAX_ACTIVE_TASKS_PER_USER}.
     * @param work            Body whose return value is persisted as
     *                        {@code resultJson}. A thrown exception lands as
     *                        {@code status=failed} with the exception
     *                        message recorded.
     * @return the created task entity (status = pending at return time).
     */
    public AsyncTaskEntity submitOneShot(String taskType, String conversationId,
                                          Long messageId, String requestJson,
                                          String createdBy, Callable<String> work) {
        AsyncTaskEntity entity = createTask(taskType, conversationId, messageId,
                "internal", null, requestJson, createdBy);
        final String taskId = entity.getTaskId();
        updateStatus(taskId, "running", 0, null, null);

        // schedule(0)-vs-put race closure: see method javadoc.
        CountDownLatch enrolled = new CountDownLatch(1);
        ScheduledFuture<?> future = pollExecutor.schedule(() -> {
            try {
                try {
                    enrolled.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    updateStatus(taskId, "failed", null, null, "worker interrupted before start");
                    return;
                }
                // Pre-call cancel check: parent conversation may have been
                // deleted between submitOneShot returning and the worker
                // being dequeued.
                if (isConversationCanceled(conversationId)) {
                    updateStatus(taskId, "failed", null, null, "conversation deleted before start");
                    return;
                }
                String result;
                try {
                    result = work.call();
                } catch (Exception e) {
                    log.warn("[AsyncTask] One-shot task {} failed: {}", taskId, e.getMessage());
                    updateStatus(taskId, "failed", null, null, e.getMessage());
                    return;
                }
                // Post-call cancel check: parent may have been deleted while
                // work was running; avoid resurrecting a succeeded row for a
                // conversation whose DB cascade already wiped it.
                if (isConversationCanceled(conversationId)) {
                    updateStatus(taskId, "failed", null, null, "conversation deleted during execution");
                } else {
                    updateStatus(taskId, "succeeded", 100, result, null);
                }
            } finally {
                activePolls.remove(taskId);
                pollTaskToConv.remove(taskId);
                // Generic terminal event so SSE listeners (parent
                // conversation of an async delegation, UI badges, …) can
                // react without polling the DB. Re-fetch so the event
                // payload reflects the row we just wrote. Wrapped in a
                // try-catch because a broadcast failure on a stale
                // conversation stream must not mask the task outcome.
                try {
                    AsyncTaskEntity finalEntity = findEntityByTaskId(taskId);
                    if (finalEntity != null) {
                        boolean success = "succeeded".equals(finalEntity.getStatus());
                        broadcastTaskEventWithData(finalEntity, "async_task_completed",
                                success, java.util.Map.of(),
                                success ? null : finalEntity.getErrorMessage());
                    }
                } catch (Exception broadcastErr) {
                    log.debug("[AsyncTask] Completion broadcast failed for task {}: {}",
                            taskId, broadcastErr.getMessage());
                }
            }
        }, 0, TimeUnit.MILLISECONDS);

        activePolls.put(taskId, future);
        if (conversationId != null) {
            pollTaskToConv.put(taskId, conversationId);
        }
        enrolled.countDown();
        log.info("[AsyncTask] Submitted one-shot task {} (type={}, conv={})",
                taskId, taskType, conversationId);
        return entity;
    }

    // ==================== 轮询管理 ====================

    /**
     * 启动对某个任务的定期轮询
     *
     * @param taskId         内部任务 ID
     * @param statusChecker  轮询函数：providerTaskId → 状态
     * @param onComplete     完成回调：(task, status) → void
     */
    public void startPolling(String taskId,
                              Function<String, TaskPollResult> statusChecker,
                              BiConsumer<AsyncTaskEntity, TaskPollResult> onComplete) {
        AsyncTaskEntity task = findEntityByTaskId(taskId);
        if (task == null) {
            log.warn("[AsyncTask] Cannot start polling: task {} not found", taskId);
            return;
        }

        // 更新状态为 running
        updateStatus(taskId, "running", null, null, null);

        LocalDateTime deadline = LocalDateTime.now().plusMinutes(MAX_POLL_DURATION_MINUTES);

        ScheduledFuture<?> future = pollExecutor.scheduleWithFixedDelay(() -> {
            try {
                // 超时检查
                if (LocalDateTime.now().isAfter(deadline)) {
                    log.warn("[AsyncTask] Task {} timed out after {} minutes", taskId, MAX_POLL_DURATION_MINUTES);
                    updateStatus(taskId, "failed", null, null, "任务超时（超过 " + MAX_POLL_DURATION_MINUTES + " 分钟）");
                    cancelPolling(taskId);
                    broadcastTaskEvent(task, "async_task_completed", false, null, "任务超时");
                    return;
                }

                TaskPollResult result = statusChecker.apply(task.getProviderTaskId());
                if (result == null) {
                    return;
                }

                // 轮询成功，重置错误计数
                pollErrorCounts.remove(taskId);

                // 更新进度
                if (result.progress() != null) {
                    updateStatus(taskId, "running", result.progress(), null, null);
                    broadcastProgress(task, result.progress());
                }

                // 终态处理
                if (result.isTerminal()) {
                    cancelPolling(taskId);
                    if (result.succeeded()) {
                        updateStatus(taskId, "succeeded", 100, result.resultJson(), null);
                    } else {
                        updateStatus(taskId, "failed", null, null, result.errorMessage());
                    }
                    // 刷新任务实体
                    AsyncTaskEntity freshTask = findEntityByTaskId(taskId);
                    onComplete.accept(freshTask, result);
                }
            } catch (Exception e) {
                int errorCount = pollErrorCounts.merge(taskId, 1, Integer::sum);
                log.error("[AsyncTask] Polling error for task {} ({}/{}): {}",
                        taskId, errorCount, MAX_POLL_ERROR_COUNT, e.getMessage(), e);
                if (errorCount >= MAX_POLL_ERROR_COUNT) {
                    log.error("[AsyncTask] Task {} exceeded max poll errors, marking as failed", taskId);
                    updateStatus(taskId, "failed", null, null,
                            "轮询连续失败 " + errorCount + " 次: " + e.getMessage());
                    cancelPolling(taskId);
                    pollErrorCounts.remove(taskId);
                    broadcastTaskEvent(task, "async_task_completed", false, null,
                            "轮询异常，任务已标记失败");
                }
            }
        }, 3, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);

        activePolls.put(taskId, future);
        if (task.getConversationId() != null) {
            pollTaskToConv.put(taskId, task.getConversationId());
        }
        log.info("[AsyncTask] Started polling for task {} (interval={}s, timeout={}min)",
                taskId, POLL_INTERVAL_SECONDS, MAX_POLL_DURATION_MINUTES);
    }

    private void cancelPolling(String taskId) {
        ScheduledFuture<?> future = activePolls.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
        pollTaskToConv.remove(taskId);
    }

    // ==================== Conversation-deleted fan-out ====================

    /**
     * Returns true if this conversation was deleted recently enough that any
     * still-running async worker (music virtual-thread, image/video poll
     * completion, …) must abort before writing a file or persisting a
     * message — see {@link #canceledConversations}.
     * <p>
     * Sweeps stale entries on read so the map stays small.
     */
    public boolean isConversationCanceled(String conversationId) {
        if (conversationId == null) return false;
        sweepCanceled();
        return canceledConversations.containsKey(conversationId);
    }

    @EventListener
    public void onConversationDeleted(ConversationDeletedEvent event) {
        String convId = event.conversationId();
        if (convId == null) return;

        canceledConversations.put(convId, System.currentTimeMillis() + CANCEL_RETENTION_MS);

        int cancelled = 0;
        for (Map.Entry<String, String> entry : pollTaskToConv.entrySet()) {
            if (convId.equals(entry.getValue())) {
                String taskId = entry.getKey();
                cancelPolling(taskId);
                // One-shot tasks (taskType "agent_delegate") have no separate
                // poll loop to observe the cancel and write the terminal row:
                // cancelPolling only nukes the Future. Without this explicit
                // write-back the DB row stays "running" forever. Polling
                // tasks (video / image / ...) keep their original behavior —
                // their own poll completion or startup-recovery path is what
                // writes the terminal status.
                AsyncTaskEntity t = findEntityByTaskId(taskId);
                if (t != null
                        && "agent_delegate".equals(t.getTaskType())
                        && !"succeeded".equals(t.getStatus())
                        && !"failed".equals(t.getStatus())) {
                    updateStatus(taskId, "failed", null, null, "conversation deleted");
                }
                cancelled++;
            }
        }
        if (cancelled > 0) {
            log.info("[AsyncTask] Cancelled {} active poller(s) for deleted conversation {}",
                    cancelled, convId);
        }
        sweepCanceled();
    }

    private void sweepCanceled() {
        long now = System.currentTimeMillis();
        canceledConversations.entrySet().removeIf(e -> e.getValue() < now);
    }

    // ==================== 状态更新 ====================

    public void updateStatus(String taskId, String status, Integer progress,
                              String resultJson, String errorMessage) {
        LambdaUpdateWrapper<AsyncTaskEntity> wrapper = new LambdaUpdateWrapper<AsyncTaskEntity>()
                .eq(AsyncTaskEntity::getTaskId, taskId)
                .set(AsyncTaskEntity::getStatus, status)
                .set(AsyncTaskEntity::getUpdateTime, LocalDateTime.now());
        if (progress != null) {
            wrapper.set(AsyncTaskEntity::getProgress, progress);
        }
        if (resultJson != null) {
            wrapper.set(AsyncTaskEntity::getResultJson, resultJson);
        }
        if (errorMessage != null) {
            wrapper.set(AsyncTaskEntity::getErrorMessage, errorMessage);
        }
        asyncTaskMapper.update(null, wrapper);
    }

    // ==================== 查询 ====================

    public AsyncTaskInfo getTaskInfo(String taskId) {
        AsyncTaskEntity entity = findEntityByTaskId(taskId);
        if (entity == null) {
            return null;
        }
        return toInfo(entity);
    }

    public List<AsyncTaskInfo> listActiveTasks(String conversationId) {
        List<AsyncTaskEntity> entities = asyncTaskMapper.selectList(
                new LambdaQueryWrapper<AsyncTaskEntity>()
                        .eq(AsyncTaskEntity::getConversationId, conversationId)
                        .in(AsyncTaskEntity::getStatus, List.of("pending", "running"))
                        .orderByDesc(AsyncTaskEntity::getCreateTime)
        );
        return entities.stream().map(this::toInfo).toList();
    }

    /** Returns the persisted task entity by its public {@code taskId}, or
     *  {@code null} if no row matches. Promoted from private to public so the
     *  conversation-deleted listener (and overrides in tests) can resolve a
     *  task's current state without going through the read-model
     *  {@link #getTaskInfo}. */
    public AsyncTaskEntity findEntityByTaskId(String taskId) {
        return asyncTaskMapper.selectOne(
                new LambdaQueryWrapper<AsyncTaskEntity>()
                        .eq(AsyncTaskEntity::getTaskId, taskId)
        );
    }

    private AsyncTaskInfo toInfo(AsyncTaskEntity entity) {
        return AsyncTaskInfo.builder()
                .taskId(entity.getTaskId())
                .taskType(entity.getTaskType())
                .status(entity.getStatus())
                .progress(entity.getProgress())
                .providerName(entity.getProviderName())
                .errorMessage(entity.getErrorMessage())
                .createTime(entity.getCreateTime())
                .build();
    }

    // ==================== SSE 广播 ====================

    private void broadcastProgress(AsyncTaskEntity task, int progress) {
        Map<String, Object> data = Map.of(
                "taskId", task.getTaskId(),
                "taskType", task.getTaskType(),
                "progress", progress,
                "providerName", Objects.toString(task.getProviderName(), "")
        );
        streamTracker.broadcastObject(task.getConversationId(), "async_task_progress", data);
    }

    public void broadcastTaskEvent(AsyncTaskEntity task, String eventName,
                                     boolean success, String videoUrl, String errorMessage) {
        broadcastTaskEvent(task, eventName, success, videoUrl, null, errorMessage);
    }

    public void broadcastTaskEvent(AsyncTaskEntity task, String eventName,
                                     boolean success, String videoUrl, String imageUrl, String errorMessage) {
        Map<String, Object> extra = new HashMap<>();
        if (videoUrl != null) extra.put("videoUrl", videoUrl);
        if (imageUrl != null) extra.put("imageUrl", imageUrl);
        broadcastTaskEventWithData(task, eventName, success, extra, errorMessage);
    }

    /**
     * Generic task-event broadcaster. Use this for any media kind where the URL
     * field name varies (audioUrl, modelUrl, ...) — pass it via {@code extraData}.
     * Named distinctly from {@link #broadcastTaskEvent} to avoid overload
     * ambiguity when callers pass {@code null} for the 4th argument.
     */
    public void broadcastTaskEventWithData(AsyncTaskEntity task, String eventName,
                                             boolean success, Map<String, Object> extraData,
                                             String errorMessage) {
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", task.getTaskId());
        data.put("taskType", task.getTaskType());
        data.put("success", success);
        if (extraData != null) data.putAll(extraData);
        if (errorMessage != null) data.put("errorMessage", errorMessage);
        streamTracker.broadcastObject(task.getConversationId(), eventName, data);
    }

    // ==================== 启动恢复 ====================

    @Override
    public void run(ApplicationArguments args) {
        List<AsyncTaskEntity> pendingTasks = asyncTaskMapper.selectList(
                new LambdaQueryWrapper<AsyncTaskEntity>()
                        .in(AsyncTaskEntity::getStatus, List.of("pending", "running"))
        );
        if (!pendingTasks.isEmpty()) {
            log.info("[AsyncTask] Found {} unfinished tasks on startup, marking as failed", pendingTasks.size());
            for (AsyncTaskEntity task : pendingTasks) {
                updateStatus(task.getTaskId(), "failed", null, null,
                        "服务重启导致任务中断，请重新提交");
            }
        }
    }

    // ==================== 生命周期 ====================

    @PreDestroy
    public void shutdown() {
        int count = activePolls.size();
        activePolls.values().forEach(f -> f.cancel(false));
        activePolls.clear();
        pollErrorCounts.clear();
        pollExecutor.shutdownNow();
        log.info("[AsyncTask] Shutdown complete, cancelled {} active polls", count);
    }

    // ==================== 轮询结果 ====================

    /**
     * Provider 轮询返回的结果
     */
    public record TaskPollResult(
            String state,        // pending / running / succeeded / failed
            Integer progress,    // 0-100, nullable
            String videoUrl,     // 成功时的视频 URL
            String coverImageUrl,// 可选封面图
            String imageUrl,     // 成功时的图片 URL（图片生成场景）
            String resultJson,   // 完成时的完整结果 JSON
            String errorMessage  // 失败时的错误信息
    ) {
        public boolean isTerminal() {
            return "succeeded".equals(state) || "failed".equals(state);
        }

        public boolean succeeded() {
            return "succeeded".equals(state);
        }

        public static TaskPollResult pending(Integer progress) {
            return new TaskPollResult("pending", progress, null, null, null, null, null);
        }

        public static TaskPollResult running(Integer progress) {
            return new TaskPollResult("running", progress, null, null, null, null, null);
        }

        public static TaskPollResult succeeded(String videoUrl, String coverImageUrl, String resultJson) {
            return new TaskPollResult("succeeded", 100, videoUrl, coverImageUrl, null, resultJson, null);
        }

        public static TaskPollResult imageSucceeded(String imageUrl, String resultJson) {
            return new TaskPollResult("succeeded", 100, null, null, imageUrl, resultJson, null);
        }

        public static TaskPollResult failed(String errorMessage) {
            return new TaskPollResult("failed", null, null, null, null, null, errorMessage);
        }
    }
}
