package vip.mate.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.task.model.AsyncTaskEntity;
import vip.mate.task.repository.AsyncTaskMapper;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Contract for {@link AsyncTaskService#submitOneShot} — the one-shot Callable
 * entry point that lets a caller hand off arbitrary work to {@code pollExecutor}
 * and have the standard {@code mate_async_task} lifecycle (running → terminal
 * + automatic cancel-on-parent-conversation-deleted + bookkeeping cleanup) take
 * over.
 * <p>
 * The fixture installs an anonymous subclass that overrides {@code createTask},
 * {@code updateStatus} and {@code findEntityByTaskId} with in-memory equivalents.
 * Reason: the production methods round-trip through a MyBatis-Plus
 * LambdaUpdateWrapper that obscures the {@code (status, progress, resultJson,
 * errorMessage)} tuple under MPGENVAL placeholders. Asserting on a
 * test-controlled state map is faster, less brittle, and lets the suite focus
 * on what {@code submitOneShot} actually does — schedule the worker, register
 * bookkeeping, observe cancellation, and clean up.
 *
 * <h3>Covered paths</h3>
 * <ol>
 *   <li>Success — Callable returns, status lands on succeeded with resultJson.</li>
 *   <li>Exception — Callable throws, status lands on failed with errorMessage.</li>
 *   <li>Conversation deletion mid-run — listener writes failed + worker's
 *       second cancel-check also writes failed; both messages match.</li>
 *   <li>schedule/put race stress — 200 zero-cost tasks all succeed and drain
 *       both bookkeeping maps to empty (catches any ghost entry).</li>
 *   <li>Latch ordering — worker observes itself enrolled in both maps the
 *       moment Callable.call() begins (the latch invariant).</li>
 * </ol>
 */
class AsyncTaskServiceOneShotTest {

    private AsyncTaskMapper mapper;
    private ChatStreamTracker tracker;

    /** Test-side per-taskId snapshot — populated by the fixture's overrides
     *  of createTask + updateStatus instead of going through MyBatis-Plus. */
    private static final class TaskState {
        String taskId;
        String taskType;
        String conversationId;
        String status;
        Integer progress;
        String resultJson;
        String errorMessage;
    }

    private final ConcurrentMap<String, TaskState> states = new ConcurrentHashMap<>();
    private AsyncTaskService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AsyncTaskMapper.class);
        tracker = mock(ChatStreamTracker.class);
        states.clear();
        service = new AsyncTaskService(mapper, tracker) {
            @Override
            public AsyncTaskEntity createTask(String taskType, String conversationId, Long messageId,
                                              String providerName, String providerTaskId,
                                              String requestJson, String createdBy) {
                String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                AsyncTaskEntity e = new AsyncTaskEntity();
                e.setTaskId(taskId);
                e.setTaskType(taskType);
                e.setStatus("pending");
                e.setConversationId(conversationId);
                e.setMessageId(messageId);
                e.setProviderName(providerName);
                e.setProviderTaskId(providerTaskId);
                e.setRequestJson(requestJson);
                e.setCreatedBy(createdBy);
                TaskState s = new TaskState();
                s.taskId = taskId;
                s.taskType = taskType;
                s.conversationId = conversationId;
                s.status = "pending";
                states.put(taskId, s);
                return e;
            }

            @Override
            public void updateStatus(String taskId, String status, Integer progress,
                                      String resultJson, String errorMessage) {
                states.compute(taskId, (k, old) -> {
                    TaskState s = old != null ? old : new TaskState();
                    s.taskId = taskId;
                    s.status = status;
                    if (progress != null) s.progress = progress;
                    if (resultJson != null) s.resultJson = resultJson;
                    if (errorMessage != null) s.errorMessage = errorMessage;
                    return s;
                });
            }

            @Override
            public AsyncTaskEntity findEntityByTaskId(String taskId) {
                TaskState s = states.get(taskId);
                if (s == null) return null;
                AsyncTaskEntity e = new AsyncTaskEntity();
                e.setTaskId(s.taskId);
                e.setTaskType(s.taskType);
                e.setStatus(s.status);
                e.setConversationId(s.conversationId);
                return e;
            }
        };
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @DisplayName("Success path: status = succeeded, resultJson captures Callable return, maps drain")
    void successPath() throws Exception {
        AsyncTaskEntity entity = service.submitOneShot(
                "agent_delegate", "conv-success", null, "{}", "user-1",
                () -> "ok");

        awaitDone(entity.getTaskId(), 5_000);

        TaskState s = states.get(entity.getTaskId());
        assertThat(s.status).isEqualTo("succeeded");
        assertThat(s.resultJson).isEqualTo("ok");
        assertThat(s.progress).isEqualTo(100);
        assertActiveMapsEmpty();
    }

    @Test
    @DisplayName("Exception path: status = failed, errorMessage carries Callable's message")
    void exceptionPath() throws Exception {
        AsyncTaskEntity entity = service.submitOneShot(
                "agent_delegate", "conv-fail", null, "{}", "user-1",
                () -> { throw new RuntimeException("boom-msg"); });

        awaitDone(entity.getTaskId(), 5_000);

        TaskState s = states.get(entity.getTaskId());
        assertThat(s.status).isEqualTo("failed");
        assertThat(s.errorMessage).isNotNull().contains("boom-msg");
        assertActiveMapsEmpty();
    }

    @Test
    @DisplayName("Conversation deleted while running: terminal status is failed with deletion message")
    void cancelPathViaConversationDeleted() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerCanProceed = new CountDownLatch(1);

        String convId = "conv-cancel";
        AsyncTaskEntity entity = service.submitOneShot(
                "agent_delegate", convId, null, "{}", "user-1",
                () -> {
                    workerStarted.countDown();
                    // Hold the worker inside work.call() until the test fires
                    // the deletion event. Future.cancel(false) — what the
                    // listener calls — does NOT interrupt, so this await won't
                    // unblock until the test's explicit countDown below.
                    workerCanProceed.await(5, TimeUnit.SECONDS);
                    return "should-not-be-applied";
                });

        assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Listener runs synchronously on the test thread: it cancels the
        // future, looks up the (still-running) entity, sees taskType
        // agent_delegate and writes failed + "conversation deleted". This
        // closes the contract gap that bare cancelPolling left open before
        // this PR (DB row would otherwise stay running forever).
        service.onConversationDeleted(new ConversationDeletedEvent(convId));

        // Release the worker so it observes isConversationCanceled = true at
        // the post-call cancel check and writes the during-execution variant
        // (also matches "conversation deleted").
        workerCanProceed.countDown();
        awaitDone(entity.getTaskId(), 5_000);

        TaskState s = states.get(entity.getTaskId());
        assertThat(s.status).isEqualTo("failed");
        assertThat(s.errorMessage).isNotNull().contains("conversation deleted");
        assertActiveMapsEmpty();
    }

    @Test
    @DisplayName("Terminal status broadcasts async_task_completed on the parent conversation")
    void broadcastsCompletionEvent() throws Exception {
        AsyncTaskEntity successEntity = service.submitOneShot(
                "agent_delegate", "conv-broadcast-ok", null, "{}", "user-1",
                () -> "ok");
        AsyncTaskEntity failEntity = service.submitOneShot(
                "agent_delegate", "conv-broadcast-fail", null, "{}", "user-1",
                () -> { throw new RuntimeException("boom"); });

        awaitDone(successEntity.getTaskId(), 5_000);
        awaitDone(failEntity.getTaskId(), 5_000);

        // Success path → event with success=true, no errorMessage.
        verify(tracker, timeout(2_000)).broadcastObject(
                eq("conv-broadcast-ok"), eq("async_task_completed"), any());
        // Failure path → event with success=false, errorMessage carries
        // the Callable's exception message. Broadcast routes to the parent
        // conversation_id stored on the entity, which IS the parent for
        // agent-delegate one-shots per AsyncTaskService.submitOneShot.
        verify(tracker, timeout(2_000)).broadcastObject(
                eq("conv-broadcast-fail"), eq("async_task_completed"), any());
    }

    @Test
    @DisplayName("schedule/put race stress: 200 zero-cost tasks all succeed and bookkeeping drains")
    void scheduleAndPutRaceStress() throws Exception {
        int iterations = 200;
        AsyncTaskEntity[] entities = new AsyncTaskEntity[iterations];
        for (int i = 0; i < iterations; i++) {
            entities[i] = service.submitOneShot(
                    "agent_delegate", "conv-race-" + i, null, "{}", "user-1",
                    () -> "ok");
        }
        for (AsyncTaskEntity e : entities) {
            awaitDone(e.getTaskId(), 15_000);
        }
        // All terminal statuses must be succeeded.
        for (AsyncTaskEntity e : entities) {
            assertThat(states.get(e.getTaskId()).status)
                    .as("task %s succeeded", e.getTaskId())
                    .isEqualTo("succeeded");
        }
        // Belt-and-suspenders: any ghost (future.isDone() but key still in
        // map) would keep one or both of these non-empty.
        awaitMapsDrained(5_000);
        assertActiveMapsEmpty();
    }

    @Test
    @DisplayName("Latch ordering: Callable.call() observes its taskId enrolled in both maps")
    void enrolledLatchOrdering() throws Exception {
        ConcurrentHashMap<String, ?> activePolls = getInternalMap("activePolls");
        ConcurrentHashMap<String, ?> pollTaskToConv = getInternalMap("pollTaskToConv");

        AtomicBoolean observedActive = new AtomicBoolean();
        AtomicBoolean observedConvLink = new AtomicBoolean();
        AtomicReference<String> observedKey = new AtomicReference<>();
        CountDownLatch probed = new CountDownLatch(1);

        // The Callable acts as a probe: if the latch invariant holds, the
        // calling thread has already put this task into both bookkeeping
        // maps by the time the worker runs (put happens before countDown,
        // the worker awaits countDown). The probe cannot read its own taskId
        // — submitOneShot only hands it back to the caller *after* releasing
        // the latch — so it instead checks that each map holds exactly the
        // one entry this single-task test created, and records the enrolled
        // key for an after-the-fact identity check. If the latch were
        // removed, the worker could race ahead and observe empty maps.
        Callable<String> work = () -> {
            observedActive.set(activePolls.size() == 1);
            observedConvLink.set(pollTaskToConv.size() == 1);
            observedKey.set(activePolls.keySet().stream().findFirst().orElse(null));
            probed.countDown();
            return "ok";
        };

        AsyncTaskEntity entity = service.submitOneShot(
                "agent_delegate", "conv-latch", null, "{}", "user-1", work);

        assertThat(probed.await(5, TimeUnit.SECONDS))
                .as("probe must run within timeout")
                .isTrue();
        awaitDone(entity.getTaskId(), 5_000);

        assertThat(observedActive)
                .as("activePolls must hold the task when work.call() begins")
                .isTrue();
        assertThat(observedConvLink)
                .as("pollTaskToConv must hold the task when work.call() begins")
                .isTrue();
        assertThat(observedKey)
                .as("the key enrolled in activePolls must be this task's id")
                .hasValue(entity.getTaskId());
        assertActiveMapsEmpty();
    }

    // ---------- helpers ----------

    /** Waits until the task has reached a terminal status AND the worker's
     *  finally cleanup has cleared this taskId from both bookkeeping maps. */
    private void awaitDone(String taskId, long timeoutMs) throws InterruptedException {
        awaitUntil(() -> {
            TaskState s = states.get(taskId);
            if (s == null) return false;
            if (!"succeeded".equals(s.status) && !"failed".equals(s.status)) return false;
            return !getInternalMap("activePolls").containsKey(taskId)
                    && !getInternalMap("pollTaskToConv").containsKey(taskId);
        }, timeoutMs, "task " + taskId + " never reached cleaned-up terminal state");
    }

    private void awaitMapsDrained(long timeoutMs) throws InterruptedException {
        awaitUntil(() -> getInternalMap("activePolls").isEmpty()
                        && getInternalMap("pollTaskToConv").isEmpty(),
                timeoutMs, "activePolls / pollTaskToConv never drained");
    }

    private void awaitUntil(BooleanSupplier cond, long timeoutMs, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError(message + " (waited " + timeoutMs + "ms)");
    }

    private void assertActiveMapsEmpty() {
        assertThat(getInternalMap("activePolls"))
                .as("activePolls should be empty after all workers finish")
                .isEmpty();
        assertThat(getInternalMap("pollTaskToConv"))
                .as("pollTaskToConv should be empty after all workers finish")
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, ?> getInternalMap(String name) {
        return (ConcurrentHashMap<String, ?>) ReflectionTestUtils.getField(service, name);
    }
}
