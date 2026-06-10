package vip.mate.agent.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-conversation mutex inside
 * {@link ProgressLedgerService#upsert} — the load-mutate-save sequence
 * must serialise per conversation, otherwise N parallel
 * {@code progress_update} tool calls on the same conversation collapse
 * to last-writer-wins and silently drop entries.
 *
 * <p>Repro: in round-3 of the LLM-review test, the model pre-registered
 * 12 entries in a single batch of parallel tool calls; only 7-8 survived
 * to the DB, the rest were lost, and the agent later re-did completed
 * work because the snapshot it saw was missing the pending entries.
 *
 * <p>Uses an in-memory subclass of the service rather than mocking
 * Mybatis-Plus: the JSON I/O methods are protected for exactly this
 * purpose.
 */
class ProgressLedgerServiceConcurrencyTest {

    /**
     * Test double — overrides the two protected DB methods to read/write a
     * thread-safe in-memory map. The {@code upsert} logic (including the
     * per-conversation mutex under test) inherits unchanged from the
     * parent.
     */
    private static final class InMemoryProgressLedgerService extends ProgressLedgerService {
        private final Map<String, String> store = new ConcurrentHashMap<>();

        InMemoryProgressLedgerService() {
            super(null, new ObjectMapper().registerModule(new JavaTimeModule()));
        }

        @Override
        protected String loadLedgerJson(String conversationId) {
            return store.get(conversationId);
        }

        @Override
        protected void saveLedgerJson(String conversationId, String json) {
            store.put(conversationId, json);
        }
    }

    @Test
    @DisplayName("12 parallel upserts on one conversation all survive — no last-writer-wins drops.")
    void parallelUpsertsAllSurvive() throws Exception {
        ProgressLedgerService service = new InMemoryProgressLedgerService();
        String conv = "conv-race-1";

        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.upsert(conv, "step_" + idx, "Step " + idx, ProgressStatus.PENDING, null);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "all upsert threads must finish within 10s");
        pool.shutdown();

        assertEquals(0, failures.get(), "no thread should fail");
        ProgressLedger finalLedger = service.load(conv);
        assertEquals(n, finalLedger.size(),
                "all " + n + " parallel entries must survive; got " + finalLedger.size()
                        + " — keys=" + finalLedger.asMap().keySet());
        for (int i = 0; i < n; i++) {
            assertTrue(finalLedger.asMap().containsKey("step_" + i),
                    "expected key step_" + i + " in final ledger");
        }
    }

    @Test
    @DisplayName("Parallel upserts on DIFFERENT conversations do not contend.")
    void differentConversationsAreIndependent() throws Exception {
        ProgressLedgerService service = new InMemoryProgressLedgerService();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch done = new CountDownLatch(2);

        pool.submit(() -> {
            for (int i = 0; i < 5; i++) {
                service.upsert("conv-A", "a_" + i, "A " + i, ProgressStatus.DONE, null);
            }
            done.countDown();
        });
        pool.submit(() -> {
            for (int i = 0; i < 5; i++) {
                service.upsert("conv-B", "b_" + i, "B " + i, ProgressStatus.DONE, null);
            }
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(5, service.load("conv-A").size());
        assertEquals(5, service.load("conv-B").size());
    }

    @Test
    @DisplayName("Sequential updates on the same key advance status in order.")
    void sequentialStatusTransitions() {
        ProgressLedgerService service = new InMemoryProgressLedgerService();
        String conv = "conv-X";

        service.upsert(conv, "step_a", "Step A", ProgressStatus.PENDING, null);
        service.upsert(conv, "step_a", null, ProgressStatus.IN_PROGRESS, "working");
        service.upsert(conv, "step_a", null, ProgressStatus.DONE, "finished");

        ProgressLedger ledger = service.load(conv);
        assertEquals(1, ledger.size());
        ProgressEntry e = ledger.asMap().get("step_a");
        assertEquals(ProgressStatus.DONE, e.getStatus());
        // Label survives the null-label updates by falling back to existing value.
        assertEquals("Step A", e.getLabel());
        assertEquals("finished", e.getNote());
    }
}
