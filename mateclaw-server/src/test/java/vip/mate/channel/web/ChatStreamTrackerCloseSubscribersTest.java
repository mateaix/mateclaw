package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ChatStreamTracker#closeSubscribers(String)} and its wiring into
 * the eviction path — issue #586. WebChat's {@code done}/{@code error} is the
 * logical end of the stream; subscriber SSE connections must actually close so
 * backend integrators reading "until the server closes" are not held for the
 * full 10-minute SseEmitter timeout. The eviction path must do the same so a
 * forcibly-reclaimed run does not leave subscribers in silence.
 *
 * <p>Completion is observed by the post-complete send() throwing
 * {@code IllegalStateException: ResponseBodyEmitter has already completed}
 * (the servlet container's onCompletion callback does not fire in a unit test
 * without an async request, so we assert on the emitter's own state instead).
 */
class ChatStreamTrackerCloseSubscribersTest {

    private ChatStreamTracker newTracker() {
        return new ChatStreamTracker(new ObjectMapper());
    }

    /** True when the emitter has been completed (a subsequent send() throws). */
    private static boolean isCompleted(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().data("probe"));
            return false;
        } catch (Exception e) {
            return e.getMessage() != null && e.getMessage().contains("already completed");
        }
    }

    @Test
    @DisplayName("closeSubscribers completes every attached emitter")
    void closesAllSubscribers() {
        ChatStreamTracker tracker = newTracker();
        String cid = "close-all";
        tracker.register(cid);

        SseEmitter emA = new SseEmitter();
        SseEmitter emB = new SseEmitter();
        tracker.attach(cid, emA);
        tracker.attach(cid, emB);

        tracker.closeSubscribers(cid);

        assertTrue(isCompleted(emA), "subscriber A must be completed");
        assertTrue(isCompleted(emB), "subscriber B must be completed");
        // subscribers list is cleared after close
        assertEquals(0, tracker.getAllSnapshot().getFirst().subscriberCount());
    }

    @Test
    @DisplayName("closeSubscribers is idempotent / safe when no run or no subscribers")
    void closeSubscribersSafeWhenEmpty() {
        ChatStreamTracker tracker = newTracker();
        // No run at all — must not throw.
        assertDoesNotThrow(() -> tracker.closeSubscribers("never-registered"));

        tracker.register("no-subs");
        tracker.closeSubscribers("no-subs"); // no subscribers — no-op, no throw
        // run still alive (closeSubscribers does NOT mark done)
        assertEquals(0, tracker.getAllSnapshot().getFirst().subscriberCount());
    }

    @Test
    @DisplayName("closeSubscribers does not mark the run done (run stays until complete())")
    void closeSubscribersDoesNotMarkDone() {
        ChatStreamTracker tracker = newTracker();
        String cid = "close-not-done";
        tracker.register(cid);
        tracker.incrementFlux(cid);
        SseEmitter em = new SseEmitter();
        tracker.attach(cid, em);

        tracker.closeSubscribers(cid);

        // The run is still registered — closeSubscribers only closes the SSE
        // connections, it does not finalize the run lifecycle. That remains
        // complete()'s job so the retention window for reconnect still applies.
        assertEquals(1, tracker.getAllSnapshot().size());
        assertTrue(tracker.streamExistsOnThisNode(cid));
    }

    @Test
    @DisplayName("Eviction closes subscriber emitters (not just disposes the Flux)")
    void evictionClosesSubscribers() {
        ChatStreamTracker tracker = new ChatStreamTracker(new ObjectMapper());
        tracker.setIdleTimeoutMinutesForTesting(5);
        String cid = "evict-close";
        tracker.register(cid);

        SseEmitter em = new SseEmitter();
        tracker.attach(cid, em);

        // Backdate so the idle-eviction path fires.
        tracker.backdateLastEventForTesting(cid, System.currentTimeMillis() - 6 * 60_000L);
        tracker.cleanupStaleRuns();

        assertTrue(isCompleted(em),
                "eviction must close the subscriber emitter so the client is not " +
                        "left hanging in silence until its own timeout");
        assertFalse(tracker.hasRunStateForTesting(cid));
    }

    @Test
    @DisplayName("One already-dead subscriber does not block the rest from being closed")
    void closeSubscribersResilientToDeadEmitter() {
        ChatStreamTracker tracker = newTracker();
        String cid = "resilient-close";
        tracker.register(cid);

        SseEmitter dead = new SseEmitter();
        // Force the dead emitter into a completed state so the complete() call
        // inside closeSubscribers() throws on it — proving the loop survives.
        dead.complete();
        SseEmitter live = new SseEmitter();
        tracker.attach(cid, dead);
        tracker.attach(cid, live);

        tracker.closeSubscribers(cid);

        assertTrue(isCompleted(live),
                "the live subscriber must still be closed even though a dead " +
                        "subscriber threw on complete()");
    }

    @Test
    @DisplayName("Emitter completion runs outside the RunState lock")
    void closeSubscribersCompletesOutsideStateLock() {
        ChatStreamTracker tracker = newTracker();
        String cid = "close-outside-lock";
        ChatStreamTracker.RunHandle handle = tracker.register(cid);
        AtomicBoolean concurrentAttachSucceeded = new AtomicBoolean();

        SseEmitter emitter = new SseEmitter() {
            @Override
            public void complete() {
                CompletableFuture<Boolean> attach = CompletableFuture.supplyAsync(
                        () -> tracker.attach(handle, new SseEmitter()));
                try {
                    concurrentAttachSucceeded.set(attach.get(1, TimeUnit.SECONDS));
                } catch (Exception ignored) {
                    concurrentAttachSucceeded.set(false);
                }
                super.complete();
            }
        };
        assertTrue(tracker.attach(handle, emitter));

        tracker.closeSubscribers(handle);

        assertTrue(concurrentAttachSucceeded.get(),
                "complete() must not run while closeSubscribers owns the state lock");
    }
}
