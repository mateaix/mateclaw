package vip.mate.interop.a2a;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aTaskStoreTest {

    @Test
    void putIfAbsentRejectsDuplicateTaskIdInSameTenant() {
        MutableClock clock = new MutableClock();
        A2aTaskStore store = new A2aTaskStore(10, Duration.ofMinutes(5), clock);
        A2aTask first = A2aTask.submitted("task-1", "ctx-1", "tenant-a");
        A2aTask duplicate = A2aTask.submitted("task-1", "ctx-2", "tenant-a");

        assertTrue(store.putIfAbsent("tenant-a", first));
        assertFalse(store.putIfAbsent("tenant-a", duplicate));

        assertEquals("ctx-1", store.get("tenant-a", "task-1").orElseThrow().contextId());
    }

    @Test
    void duplicateJsonRpcIdReturnsStoredSnapshot() {
        MutableClock clock = new MutableClock();
        A2aTaskStore store = new A2aTaskStore(10, Duration.ofMinutes(5), clock);
        Map<String, Object> snapshot = Map.of("taskId", "task-1", "status", "submitted");

        assertTrue(store.rememberRpcSnapshot("tenant-a", "rpc-1", snapshot));
        assertFalse(store.rememberRpcSnapshot("tenant-a", "rpc-1", Map.of("taskId", "other")));

        assertEquals(snapshot, store.rpcSnapshot("tenant-a", "rpc-1").orElseThrow());
    }

    @Test
    void sweepRemovesExpiredTerminalTasksAndKeepsActiveTasks() {
        MutableClock clock = new MutableClock();
        A2aTaskStore store = new A2aTaskStore(10, Duration.ofSeconds(30), clock);
        A2aTask done = A2aTask.submitted("done", "ctx", "tenant").withStatus("completed", "ok", true);
        A2aTask active = A2aTask.submitted("active", "ctx", "tenant").withStatus("working", null, false);

        assertTrue(store.putIfAbsent("tenant", done));
        assertTrue(store.putIfAbsent("tenant", active));
        clock.advance(Duration.ofSeconds(31));

        assertEquals(1, store.sweepExpired());
        assertTrue(store.get("tenant", "done").isEmpty());
        assertTrue(store.get("tenant", "active").isPresent());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-19T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
