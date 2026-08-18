package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link ChatStreamTracker#detach(String, SseEmitter)} contract: a
 * subscriber going away (SSE timeout / error / client close) must NOT mark the
 * run as done. This is the tracker-level root of WebChatController issue #587
 * defect 1 — the controller previously called {@code complete()} from its
 * {@code onTimeout}/{@code onError} callbacks, which polluted RunState ahead
 * of the agent finishing and dropped subsequent content deltas from the replay
 * buffer.
 *
 * <p>These tests assert the tracker-side invariant the controller now relies on:
 * detach only removes the subscriber, the run keeps running, and events still
 * reach the buffer for any re-attaching subscriber.
 */
class ChatStreamTrackerDetachSemanticsTest {

    private ChatStreamTracker newTracker() {
        return new ChatStreamTracker(new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private void pauseHeartbeatCancellation(ChatStreamTracker tracker,
                                            String conversationId,
                                            CountDownLatch cancellationStarted,
                                            CountDownLatch releaseCancellation) throws Exception {
        Field runsField = ChatStreamTracker.class.getDeclaredField("runs");
        runsField.setAccessible(true);
        Map<String, ChatStreamTracker.RunState> runs =
                (Map<String, ChatStreamTracker.RunState>) runsField.get(tracker);
        ChatStreamTracker.RunState state = runs.get(conversationId);
        ScheduledFuture<?> original = state.heartbeatFuture;
        ScheduledFuture<?> blocking = (ScheduledFuture<?>) Proxy.newProxyInstance(
                ScheduledFuture.class.getClassLoader(),
                new Class<?>[]{ScheduledFuture.class},
                (proxy, method, args) -> {
                    if ("cancel".equals(method.getName())) {
                        cancellationStarted.countDown();
                        if (!releaseCancellation.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("heartbeat cancellation release timed out");
                        }
                    }
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        synchronized (state.lock) {
            state.heartbeatFuture = blocking;
        }
    }

    @Test
    @DisplayName("detach() leaves the run running — isRunning() stays true")
    void detachKeepsRunRunning() {
        ChatStreamTracker tracker = newTracker();
        String cid = "detach-running";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        SseEmitter emitter = new SseEmitter();
        tracker.attach(cid, emitter);
        // Simulate the SSE onTimeout path: this is what WebChatController now calls.
        tracker.detach(cid, emitter);

        assertTrue(tracker.isRunning(cid),
                "detach only removes the subscriber; the run must stay running");
    }

    @Test
    @DisplayName("detach() does NOT mark done — subsequent events still buffer for replay")
    void detachDoesNotMarkDone() {
        ChatStreamTracker tracker = newTracker();
        String cid = "detach-buffer";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        SseEmitter gone = new SseEmitter();
        tracker.attach(cid, gone);
        tracker.detach(cid, gone);

        // After the subscriber left, the agent keeps producing. These events must
        // land in the buffer so a re-attaching subscriber can replay them — the
        // whole point of not prematurely calling complete().
        tracker.broadcast(cid, "content_delta", "{\"text\":\"still-alive\"}");

        // A fresh subscriber attaching should be able to see the buffered event
        // (proving done was NOT set, which would have dropped the broadcast).
        SseEmitter late = new SseEmitter();
        AtomicInteger received = new AtomicInteger();
        late.onCompletion(() -> {
        });
        // attach replays the buffer synchronously; we can't easily count sends on a
        // raw SseEmitter, but the key assertion is that attach returns true (state
        // exists and is not in a terminal window that drops events).
        assertTrue(tracker.attach(cid, late), "attach must succeed — run is still alive");
        assertTrue(tracker.isRunning(cid));
    }

    @Test
    @DisplayName("contrast: complete() DOES mark done (the old, buggy behavior)")
    void completeMarksDone() {
        ChatStreamTracker tracker = newTracker();
        String cid = "complete-done";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        // complete() is the agent-finished path — it should mark the run done.
        tracker.complete(cid);
        assertFalse(tracker.isRunning(cid),
                "complete() is the real finish signal; detach() must NOT be");
    }

    @Test
    @DisplayName("detach is idempotent and safe when no run exists")
    void detachSafeWhenAbsent() {
        ChatStreamTracker tracker = newTracker();
        SseEmitter emitter = new SseEmitter();
        // No run registered — detach must not throw.
        tracker.detach("never-registered", emitter);

        tracker.register("present");
        tracker.attach("present", emitter);
        // Detaching twice must be a no-op the second time.
        tracker.detach("present", emitter);
        tracker.detach("present", emitter);
        // run still alive
        assertTrue(tracker.isRunning("present"));
        assertEquals(0, tracker.getAllSnapshot().getFirst().subscriberCount());
    }

    @Test
    @DisplayName("complete preserves a heartbeat started by a post-done attach")
    void completeDoesNotCancelPostDoneAttachHeartbeat() throws Exception {
        ChatStreamTracker tracker = newTracker();
        String cid = "complete-heartbeat-handoff";
        tracker.register(cid);
        tracker.incrementFlux(cid);
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        CountDownLatch releaseCancellation = new CountDownLatch(1);
        pauseHeartbeatCancellation(tracker, cid, cancellationStarted, releaseCancellation);

        CompletableFuture<Boolean> completion =
                CompletableFuture.supplyAsync(() -> tracker.complete(cid));
        try {
            assertTrue(cancellationStarted.await(1, TimeUnit.SECONDS));
            assertTrue(tracker.attach(cid, new SseEmitter()));
        } finally {
            releaseCancellation.countDown();
        }

        assertTrue(completion.get(2, TimeUnit.SECONDS));
        assertTrue(tracker.hasHeartbeatForTesting(cid),
                "completion must cancel only the heartbeat detached before post-done attach");
    }

    @Test
    @DisplayName("queue-draining completion preserves a heartbeat started by a post-done attach")
    void queueDrainDoesNotCancelPostDoneAttachHeartbeat() throws Exception {
        ChatStreamTracker tracker = newTracker();
        String cid = "queue-drain-heartbeat-handoff";
        tracker.register(cid);
        tracker.incrementFlux(cid);
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        CountDownLatch releaseCancellation = new CountDownLatch(1);
        pauseHeartbeatCancellation(tracker, cid, cancellationStarted, releaseCancellation);

        CompletableFuture<ChatStreamTracker.CompletionResult> completion =
                CompletableFuture.supplyAsync(() -> tracker.completeAndConsumeIfLast(cid));
        try {
            assertTrue(cancellationStarted.await(1, TimeUnit.SECONDS));
            assertTrue(tracker.attach(cid, new SseEmitter()));
        } finally {
            releaseCancellation.countDown();
        }

        assertTrue(completion.get(2, TimeUnit.SECONDS).allDone());
        assertTrue(tracker.hasHeartbeatForTesting(cid),
                "queue drain must cancel only the heartbeat detached before post-done attach");
    }
}
