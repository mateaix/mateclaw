package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the orphan-run policy added for issue #587: when a run's only
 * subscriber disconnects (webchat SSE timeout/error) while the agent Flux is
 * still running, the run becomes invisible + unreachable and must be reclaimed
 * after a grace window instead of burning tokens until the 30-min idle sweep.
 *
 * <p>Composes with the detach() fix from #587 defect 1: detach() arms the
 * orphan clock when the subscriber list empties; attach()/closeSubscribers()
 * clear it.
 */
class ChatStreamTrackerOrphanPolicyTest {

    private static final class RecordingDisposable implements Disposable {
        private final AtomicBoolean disposed = new AtomicBoolean();
        private final boolean throwOnDispose;

        private RecordingDisposable() {
            this(false);
        }

        private RecordingDisposable(boolean throwOnDispose) {
            this.throwOnDispose = throwOnDispose;
        }

        @Override
        public void dispose() {
            disposed.set(true);
            if (throwOnDispose) {
                throw new IllegalStateException("dispose failed");
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }
    }

    private ChatStreamTracker newTracker() {
        ChatStreamTracker t = new ChatStreamTracker(new ObjectMapper());
        t.setIdleTimeoutMinutesForTesting(30);   // keep the idle bucket out of the way
        t.setOrphanGraceSecondsForTesting(2);    // tight grace for unit-test speed
        return t;
    }

    private static CompletableFuture<Void> startPausedCleanup(
            ChatStreamTracker tracker,
            String conversationId,
            CountDownLatch claimed,
            CountDownLatch release) {
        tracker.setEmergencySaveCallback(conversationId, () -> {
            claimed.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("cleanup release latch timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });
        tracker.backdateOrphanForTesting(
                conversationId, System.currentTimeMillis() - 3_000L);
        return CompletableFuture.runAsync(tracker::cleanupStaleRuns);
    }

    @Test
    @DisplayName("Orphan run (no subscribers past grace) is evicted")
    void orphanRunEvictedAfterGrace() {
        ChatStreamTracker tracker = newTracker();
        String cid = "orphan-evict";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        // No subscriber ever attached — simulate the clock by backdating, which
        // is exactly what detach() would have set when the last subscriber left.
        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L); // 3s > 2s grace

        tracker.cleanupStaleRuns();

        assertFalse(tracker.hasRunStateForTesting(cid),
                "orphan run past the grace window must be evicted");
    }

    @Test
    @DisplayName("Orphan run within grace survives")
    void orphanRunWithinGraceSurvives() {
        ChatStreamTracker tracker = newTracker();
        String cid = "orphan-fresh";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        // Just became orphaned — 1s, within the 2s grace.
        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 1_000L);
        tracker.cleanupStaleRuns();

        assertTrue(tracker.hasRunStateForTesting(cid),
                "orphan run inside the grace window must survive — tolerates a brief disconnect");
    }

    @Test
    @DisplayName("Orphan eviction fires emergencySaveCallback before dispose")
    void orphanEvictionFiresEmergencySave() {
        ChatStreamTracker tracker = newTracker();
        String cid = "orphan-save";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        AtomicInteger saveCount = new AtomicInteger();
        tracker.setEmergencySaveCallback(cid, saveCount::incrementAndGet);

        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L);
        tracker.cleanupStaleRuns();

        assertEquals(1, saveCount.get(),
                "partial assistant content must be flushed via the emergency " +
                        "save before the orphan run is disposed");
        assertFalse(tracker.hasRunStateForTesting(cid));
    }

    @Test
    @DisplayName("A done run is never treated as an orphan")
    void doneRunNotOrphan() {
        ChatStreamTracker tracker = newTracker();
        String cid = "done-not-orphan";
        tracker.register(cid);
        tracker.incrementFlux(cid);
        tracker.complete(cid); // mark done

        // Even with an orphan clock backdated past grace, a done run must not
        // hit the orphan branch (it's finalized via the done path / retention).
        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L);
        tracker.cleanupStaleRuns();

        // done run is kept for DONE_RETENTION_MS (5 min) — still here right after.
        assertTrue(tracker.hasRunStateForTesting(cid),
                "a done run must not be evicted as an orphan — it's already finalized");
    }

    @Test
    @DisplayName("A run with a live subscriber is never an orphan")
    void runWithSubscriberNotOrphan() {
        ChatStreamTracker tracker = newTracker();
        String cid = "has-sub";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        SseEmitter em = new SseEmitter();
        tracker.attach(cid, em); // attaches a subscriber -> clears orphan clock

        // Backdate would-be orphan clock — but a subscriber is present, so the
        // orphan branch must not fire regardless.
        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L);
        tracker.cleanupStaleRuns();

        assertTrue(tracker.hasRunStateForTesting(cid),
                "a run with a live subscriber must never be evicted as an orphan");
    }

    @Test
    @DisplayName("detach() arms the orphan clock; re-attach clears it")
    void detachArmsClockAttachClears() {
        ChatStreamTracker tracker = newTracker();
        String cid = "detach-rearm";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        SseEmitter em = new SseEmitter();
        tracker.attach(cid, em);
        // Detach the only subscriber -> clock armed, run still running.
        tracker.detach(cid, em);
        assertTrue(tracker.isRunning(cid),
                "run must still be running after the only subscriber detaches");

        // A fresh subscriber re-attaches within grace -> clock cleared, survives.
        SseEmitter reattached = new SseEmitter();
        assertTrue(tracker.attach(cid, reattached));
        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L); // would-be past grace
        tracker.cleanupStaleRuns();
        assertTrue(tracker.hasRunStateForTesting(cid),
                "re-attach clears the orphan clock even if it was backdated");
    }

    @Test
    @DisplayName("Attach is rejected after cleanup atomically claims an orphan")
    void attachRejectedAfterEvictionClaim() throws Exception {
        ChatStreamTracker tracker = newTracker();
        String cid = "orphan-claim";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> cleanup = startPausedCleanup(tracker, cid, claimed, release);

        assertTrue(claimed.await(1, TimeUnit.SECONDS));
        try {
            assertFalse(tracker.attach(cid, new SseEmitter()),
                    "attach must not report success after eviction is claimed");
        } finally {
            release.countDown();
            cleanup.get(2, TimeUnit.SECONDS);
        }
        assertFalse(tracker.hasRunStateForTesting(cid));
    }

    @Test
    @DisplayName("Old cleanup cannot remove or close a replacement run")
    void claimedCleanupDoesNotTouchReplacementRun() throws Exception {
        ChatStreamTracker tracker = newTracker();
        String cid = "orphan-replacement";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> cleanup = startPausedCleanup(tracker, cid, claimed, release);

        assertTrue(claimed.await(1, TimeUnit.SECONDS));
        SseEmitter replacementEmitter = new SseEmitter();
        try {
            tracker.register(cid);
            tracker.incrementFlux(cid);
            assertTrue(tracker.attach(cid, replacementEmitter));
            assertTrue(tracker.hasHeartbeatForTesting(cid));
        } finally {
            release.countDown();
            cleanup.get(2, TimeUnit.SECONDS);
        }

        assertTrue(tracker.hasRunStateForTesting(cid));
        assertTrue(tracker.isRunning(cid));
        assertTrue(tracker.hasHeartbeatForTesting(cid));
        assertDoesNotThrow(() -> replacementEmitter.send(
                SseEmitter.event().name("probe").data("still-open")));
    }

    @Test
    @DisplayName("A stale emitter callback cannot orphan a replacement run")
    void staleDetachDoesNotArmReplacementOrphanClock() {
        ChatStreamTracker tracker = newTracker();
        String cid = "stale-detach";
        tracker.register(cid);
        tracker.incrementFlux(cid);

        SseEmitter oldEmitter = new SseEmitter();
        assertTrue(tracker.attach(cid, oldEmitter));
        tracker.complete(cid);
        tracker.register(cid);
        tracker.incrementFlux(cid);

        // A delayed onCompletion/onTimeout callback belongs to the prior
        // generation. It must not arm the fresh state's orphan clock merely
        // because that new state has not attached its own emitter yet.
        tracker.detach(cid, oldEmitter);
        tracker.setOrphanGraceSecondsForTesting(-1);
        tracker.cleanupStaleRuns();

        assertTrue(tracker.hasRunStateForTesting(cid));
        assertTrue(tracker.isRunning(cid));
    }

    @Test
    @DisplayName("Register atomically refreshes a reused run before cleanup can claim it")
    void registerRefreshesReusedRunLifecycle() {
        ChatStreamTracker tracker = newTracker();
        String cid = "register-refresh";
        tracker.register(cid);
        tracker.incrementFlux(cid);
        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L);

        ChatStreamTracker.RunHandle handle = tracker.register(cid);
        tracker.cleanupStaleRuns();

        assertTrue(tracker.hasRunStateForTesting(cid));
        assertTrue(tracker.attach(handle, new SseEmitter()));
    }

    @Test
    @DisplayName("Late callbacks from an old handle cannot mutate a replacement run")
    void oldHandleCannotMutateReplacementRun() throws Exception {
        ChatStreamTracker tracker = newTracker();
        String cid = "old-handle";
        ChatStreamTracker.RunHandle oldHandle = tracker.register(cid);
        tracker.incrementFlux(cid);

        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> cleanup = startPausedCleanup(tracker, cid, claimed, release);
        assertTrue(claimed.await(1, TimeUnit.SECONDS));

        ChatStreamTracker.RunHandle replacementHandle = tracker.register(cid);
        SseEmitter replacementEmitter = new SseEmitter();
        try {
            assertTrue(tracker.attach(replacementHandle, replacementEmitter));
        } finally {
            release.countDown();
            cleanup.get(2, TimeUnit.SECONDS);
        }

        int bufferBefore = tracker.bufferSizeForTesting(cid);
        assertFalse(tracker.attach(oldHandle, new SseEmitter()));
        tracker.broadcast(oldHandle, "content_delta", "{\"text\":\"stale\"}");
        tracker.closeSubscribers(oldHandle);
        tracker.complete(oldHandle);

        assertEquals(bufferBefore, tracker.bufferSizeForTesting(cid));
        assertTrue(tracker.isRunning(cid));
        assertTrue(tracker.hasHeartbeatForTesting(cid));
        assertDoesNotThrow(() -> replacementEmitter.send(
                SseEmitter.event().name("probe").data("still-open")));
    }

    @Test
    @DisplayName("Cleanup and stale handles cannot touch a replacement disposable")
    void cleanupDisposesOnlyClaimedStateDisposable() throws Exception {
        ChatStreamTracker tracker = newTracker();
        String cid = "disposable-generation";
        ChatStreamTracker.RunHandle oldHandle = tracker.register(cid);
        tracker.incrementFlux(cid);
        RecordingDisposable oldDisposable = new RecordingDisposable();
        tracker.setDisposable(oldHandle, oldDisposable);

        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> cleanup = startPausedCleanup(tracker, cid, claimed, release);
        assertTrue(claimed.await(1, TimeUnit.SECONDS));

        ChatStreamTracker.RunHandle replacementHandle = tracker.register(cid);
        RecordingDisposable replacementDisposable = new RecordingDisposable();
        RecordingDisposable staleLateDisposable = new RecordingDisposable();
        AtomicInteger replacementSaveCount = new AtomicInteger();
        AtomicInteger staleSaveCount = new AtomicInteger();
        tracker.setDisposable(replacementHandle, replacementDisposable);
        tracker.setDisposable(oldHandle, staleLateDisposable);
        tracker.setEmergencySaveCallback(replacementHandle, replacementSaveCount::incrementAndGet);
        tracker.setEmergencySaveCallback(oldHandle, staleSaveCount::incrementAndGet);

        release.countDown();
        cleanup.get(2, TimeUnit.SECONDS);

        assertTrue(oldDisposable.isDisposed());
        assertFalse(replacementDisposable.isDisposed());
        assertFalse(staleLateDisposable.isDisposed());
        assertTrue(tracker.hasRunStateForTesting(cid));

        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L);
        tracker.cleanupStaleRuns();

        assertEquals(1, replacementSaveCount.get());
        assertEquals(0, staleSaveCount.get());
        assertTrue(replacementDisposable.isDisposed());
        assertFalse(staleLateDisposable.isDisposed());
    }

    @Test
    @DisplayName("A disposable registered after Stop is requested is cancelled immediately")
    void lateDisposableIsCancelledWhenStopWonTheRace() {
        ChatStreamTracker tracker = newTracker();
        String cid = "late-stop-disposable";
        ChatStreamTracker.RunHandle handle = tracker.register(cid);
        tracker.incrementFlux(cid);

        assertTrue(tracker.requestStop(cid));

        RecordingDisposable lateDisposable = new RecordingDisposable();
        tracker.setDisposable(handle, lateDisposable);

        assertTrue(lateDisposable.isDisposed());
    }

    @Test
    @DisplayName("A throwing disposable cannot leave an evicting tombstone mapped")
    void throwingDisposableStillRemovesClaimedState() {
        ChatStreamTracker tracker = newTracker();
        String cid = "throwing-disposable";
        ChatStreamTracker.RunHandle handle = tracker.register(cid);
        tracker.incrementFlux(cid);
        tracker.setDisposable(handle, new RecordingDisposable(true));
        tracker.backdateOrphanForTesting(cid, System.currentTimeMillis() - 3_000L);

        assertDoesNotThrow(tracker::cleanupStaleRuns);

        assertFalse(tracker.hasRunStateForTesting(cid));
    }

    @Test
    @DisplayName("Broadcast send failure arms orphan cleanup when the last subscriber is removed")
    @SuppressWarnings("unchecked")
    void sendFailureArmsOrphanCleanup() throws Exception {
        ChatStreamTracker tracker = newTracker();
        String cid = "send-failure-orphan";
        ChatStreamTracker.RunHandle handle = tracker.register(cid);
        tracker.incrementFlux(cid);
        SseEmitter failingEmitter = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("client disconnected");
            }
        };
        assertTrue(tracker.attach(handle, failingEmitter));

        tracker.broadcast(handle, "content_delta", "{\"text\":\"still-running\"}");

        Field runsField = ChatStreamTracker.class.getDeclaredField("runs");
        runsField.setAccessible(true);
        Map<String, ChatStreamTracker.RunState> runs =
                (Map<String, ChatStreamTracker.RunState>) runsField.get(tracker);
        ChatStreamTracker.RunState state = runs.get(cid);
        synchronized (state.lock) {
            assertNotNull(state.subscribersZeroSince,
                    "removing the final dead subscriber must arm the orphan clock");
            state.subscribersZeroSince = System.currentTimeMillis() - 3_000L;
        }

        tracker.cleanupStaleRuns();

        assertFalse(tracker.hasRunStateForTesting(cid));
    }

    @Test
    @DisplayName("Exact-handle detach arms orphan cleanup before an emitter was attached")
    @SuppressWarnings("unchecked")
    void exactDetachArmsOrphanWithoutRemovingEmitter() throws Exception {
        ChatStreamTracker tracker = newTracker();
        String cid = "pre-attach-exact-detach";
        ChatStreamTracker.RunHandle handle = tracker.register(cid);
        tracker.incrementFlux(cid);

        tracker.detach(handle, new SseEmitter());

        Field runsField = ChatStreamTracker.class.getDeclaredField("runs");
        runsField.setAccessible(true);
        Map<String, ChatStreamTracker.RunState> runs =
                (Map<String, ChatStreamTracker.RunState>) runsField.get(tracker);
        ChatStreamTracker.RunState state = runs.get(cid);
        synchronized (state.lock) {
            assertNotNull(state.subscribersZeroSince,
                    "an exact disconnect must arm even when attach never added the emitter");
            state.subscribersZeroSince = System.currentTimeMillis() - 3_000L;
        }

        tracker.cleanupStaleRuns();

        assertFalse(tracker.hasRunStateForTesting(cid));
    }
}
