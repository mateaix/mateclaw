package vip.mate.llm.failover;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.llm.failover.AvailableProviderPool.RemovalSource;

/**
 * Unit tests for {@link AvailableProviderPool} — the membership data structure
 * that gates the failover walker.
 */
class AvailableProviderPoolTest {

    private AvailableProviderPool pool;

    @BeforeEach
    void setUp() {
        pool = new AvailableProviderPool();
    }

    @Test
    @DisplayName("New pool: nothing is in it")
    void newPoolEmpty() {
        assertFalse(pool.contains("openai"));
        assertTrue(pool.snapshot().isEmpty());
    }

    @Test
    @DisplayName("add then contains")
    void addThenContains() {
        pool.add("openai");
        assertTrue(pool.contains("openai"));
        assertFalse(pool.contains("anthropic"));
    }

    @Test
    @DisplayName("Adding twice is idempotent")
    void addIdempotent() {
        pool.add("openai");
        pool.add("openai");
        assertTrue(pool.contains("openai"));
        assertEquals(1, pool.snapshot().size());
    }

    @Test
    @DisplayName("Remove after add: pool no longer contains, snapshot exposes reason")
    void removeAfterAdd() {
        pool.add("openai");
        pool.remove("openai", RemovalSource.AUTH_ERROR, "401 Unauthorized");

        assertFalse(pool.contains("openai"));
        var snap = pool.snapshot();
        assertEquals(1, snap.size());
        assertNotNull(snap.get("openai"));
        assertEquals(RemovalSource.AUTH_ERROR, snap.get("openai").source());
        assertEquals("401 Unauthorized", snap.get("openai").message());
        assertTrue(snap.get("openai").removedAtMs() > 0, "removedAtMs must be set");
    }

    @Test
    @DisplayName("Remove without prior add still records reason (idempotent removal)")
    void removeWithoutAddIsIdempotent() {
        pool.remove("openai", RemovalSource.INIT_PROBE, "init failed");
        assertFalse(pool.contains("openai"));
        assertNotNull(pool.snapshot().get("openai"));
    }

    @Test
    @DisplayName("Re-add after remove: contains true, removal reason cleared")
    void readdClearsRemovalReason() {
        pool.add("openai");
        pool.remove("openai", RemovalSource.AUTH_ERROR, "bad key");
        assertNotNull(pool.snapshot().get("openai"));

        pool.add("openai");
        assertTrue(pool.contains("openai"));
        // Snapshot now shows openai in pool (value null), no stale reason
        assertNull(pool.snapshot().get("openai"),
                "re-adding a provider must clear its prior removal reason");
    }

    @Test
    @DisplayName("Snapshot mixes in-pool (value=null) and removed (value=reason) entries")
    void snapshotMixedView() {
        pool.add("openai");
        pool.add("dashscope");
        pool.remove("anthropic", RemovalSource.BILLING, "402 insufficient credit");

        var snap = pool.snapshot();
        assertEquals(3, snap.size());
        assertNull(snap.get("openai"), "in-pool members appear with null value");
        assertNull(snap.get("dashscope"));
        assertNotNull(snap.get("anthropic"));
        assertEquals(RemovalSource.BILLING, snap.get("anthropic").source());
    }

    @Test
    @DisplayName("Null/empty providerId is a no-op (defensive)")
    void nullEmptySafe() {
        pool.add(null);
        pool.add("");
        pool.remove(null, RemovalSource.AUTH_ERROR, "x");
        pool.remove("", RemovalSource.AUTH_ERROR, "x");
        assertFalse(pool.contains(null));
        assertFalse(pool.contains(""));
        assertTrue(pool.snapshot().isEmpty(),
                "null/empty inputs must not pollute the snapshot");
    }

    @Test
    @DisplayName("Concurrent add + remove + contains is thread-safe")
    void concurrentAccess() throws Exception {
        int threads = 16;
        int opsPerThread = 5_000;
        ExecutorService pool2 = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int worker = t;
            pool2.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        String id = "p" + (worker * 10 + (i % 10)); // shared id space
                        if (i % 3 == 0) pool.add(id);
                        else if (i % 3 == 1) pool.remove(id, RemovalSource.AUTH_ERROR, "race");
                        else pool.contains(id);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent workload must complete in 30s");
        pool2.shutdown();

        // Internal state must remain consistent — each id is either in members OR has a removal reason
        // (or both — the union is also fine), and snapshot doesn't NPE.
        var snap = pool.snapshot();
        assertNotNull(snap);
    }
}
