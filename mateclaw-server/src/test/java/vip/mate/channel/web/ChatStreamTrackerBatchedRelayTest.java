package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ChatStreamTracker#addBatchedEventRelay}: size-driven flush,
 * time-driven flush, and pass-through ordering for non-batched events.
 */
class ChatStreamTrackerBatchedRelayTest {

    private ChatStreamTracker newTracker() {
        return new ChatStreamTracker(new ObjectMapper());
    }

    private record Captured(String name, String json) {}

    /**
     * Spin until {@code condition} is true or {@code timeoutMs} elapses.
     * Polling instead of {@code Awaitility} to keep the test classpath
     * dependency-free (the project doesn't bundle Awaitility).
     */
    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("Buffer flushes when batch size threshold is hit")
    void flushAtBatchSize() {
        ChatStreamTracker tracker = newTracker();
        String src = "src-batch-size";
        tracker.register(src);
        List<Captured> captured = new CopyOnWriteArrayList<>();

        Runnable deregister = tracker.addBatchedEventRelay(src, "parent",
                3, 5_000L, // batch=3, flushMs large so the timer never fires
                (name, json) -> captured.add(new Captured(name, json)));
        try {
            tracker.broadcast(src, "tool_call_started", "{\"name\":\"a\"}");
            tracker.broadcast(src, "tool_call_completed", "{\"name\":\"a\",\"ok\":true}");
            assertTrue(captured.isEmpty(), "Below batch threshold — no flush yet");

            tracker.broadcast(src, "tool_call_started", "{\"name\":\"b\"}");
            // 3rd buffered event triggers immediate flush.
            assertTrue(waitUntil(() -> !captured.isEmpty(), 1_000),
                    "Flush should happen at batch threshold");
            List<Captured> snapshot = new ArrayList<>(captured);
            assertEquals(1, snapshot.size(),
                    "Single delegation_batch envelope expected at the size threshold");
            assertEquals("delegation_batch", snapshot.get(0).name());
            assertTrue(snapshot.get(0).json().contains("delegation_batch"));
        } finally {
            deregister.run();
        }
    }

    @Test
    @DisplayName("Buffer flushes at the elapsed-time boundary")
    void flushOnTimer() {
        ChatStreamTracker tracker = newTracker();
        String src = "src-batch-time";
        tracker.register(src);
        List<Captured> captured = new CopyOnWriteArrayList<>();

        Runnable deregister = tracker.addBatchedEventRelay(src, "parent",
                100, 200L,  // huge batch, short timer
                (name, json) -> captured.add(new Captured(name, json)));
        try {
            tracker.broadcast(src, "tool_call_started", "{\"name\":\"a\"}");
            tracker.broadcast(src, "tool_call_completed", "{\"name\":\"a\",\"ok\":true}");
            // Wait for the scheduler to fire (200ms + slack).
            assertTrue(waitUntil(() -> !captured.isEmpty(), 2_000),
                    "Time-driven flush expected within 2s");
            assertEquals("delegation_batch", captured.get(0).name(),
                    "Time-driven flush must produce a delegation_batch envelope");
        } finally {
            deregister.run();
        }
    }

    @Test
    @DisplayName("Pass-through events fire immediately and preserve ordering")
    void passThroughPreservesOrdering() {
        ChatStreamTracker tracker = newTracker();
        String src = "src-pass-through";
        tracker.register(src);
        List<Captured> captured = new CopyOnWriteArrayList<>();

        Runnable deregister = tracker.addBatchedEventRelay(src, "parent",
                100, 5_000L,  // size and time thresholds both far away
                (name, json) -> captured.add(new Captured(name, json)));
        try {
            // Two batchable events buffer up.
            tracker.broadcast(src, "tool_call_started", "{\"name\":\"a\"}");
            tracker.broadcast(src, "tool_call_completed", "{\"name\":\"a\",\"ok\":true}");
            // Pass-through event: must flush prior buffer, then fire itself.
            tracker.broadcast(src, "phase", "{\"phase\":\"reasoning\"}");

            assertTrue(waitUntil(() -> captured.size() >= 2, 2_000));
            // Order: delegation_batch (drained buffer) then phase.
            assertEquals("delegation_batch", captured.get(0).name(),
                    "Pass-through must drain buffered events first");
            assertEquals("phase", captured.get(1).name(),
                    "Pass-through event must follow the flushed batch");
        } finally {
            deregister.run();
        }
    }

    @Test
    @DisplayName("Deregister flushes any pending events before unsubscribing")
    void deregisterFlushesPending() {
        ChatStreamTracker tracker = newTracker();
        String src = "src-shutdown";
        tracker.register(src);
        AtomicInteger sawBatch = new AtomicInteger(0);
        Runnable deregister = tracker.addBatchedEventRelay(src, "parent",
                100, 60_000L,
                (name, json) -> {
                    if ("delegation_batch".equals(name)) sawBatch.incrementAndGet();
                });
        tracker.broadcast(src, "tool_call_started", "{}");
        tracker.broadcast(src, "tool_call_completed", "{}");
        deregister.run();
        assertEquals(1, sawBatch.get(),
                "Deregistration must drain pending events as one final batch");
    }
}
