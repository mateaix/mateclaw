package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private ChatStreamTracker newTracker() {
        ChatStreamTracker t = new ChatStreamTracker(new ObjectMapper());
        t.setIdleTimeoutMinutesForTesting(30);   // keep the idle bucket out of the way
        t.setOrphanGraceSecondsForTesting(2);    // tight grace for unit-test speed
        return t;
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
}
