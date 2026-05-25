package vip.mate.cron.delivery;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RFC-063r §2.6.1: Template-Method invariants — SQL CAS claim, marker
 * methods after success / failure, exception propagation.
 */
class AbstractCronResultDeliveryTest {

    private CronJobRunMapper runMapper;
    private CronJobEntity job;
    private CronJobRunEntity run;

    /**
     * Pre-warm MyBatis Plus's lambda → column cache. Without this the
     * production code's {@code new LambdaUpdateWrapper<CronJobRunEntity>()}
     * throws "can not find lambda cache" — the cache is normally populated
     * during Spring context init, which we skip in unit tests.
     */
    @BeforeAll
    static void initMpLambdaCache() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), CronJobRunEntity.class);
    }

    @BeforeEach
    void setUp() {
        runMapper = mock(CronJobRunMapper.class);
        job = new CronJobEntity();
        job.setId(1L);
        run = new CronJobRunEntity();
        run.setId(42L);
        run.setStatus("succeeded");
    }

    @Test
    void deliver_claimsSuccessfully_marksDelivered() {
        // First update = the claim CAS, returns 1 (won the race)
        // Second update = the markDelivered, returns 1
        when(runMapper.update(any(), any(Wrapper.class))).thenReturn(1, 1);

        AbstractCronResultDelivery strategy = new AbstractCronResultDelivery(runMapper) {
            @Override public boolean supports(CronJobEntity j) { return true; }
            @Override
            protected DeliveryOutcome doDeliver(CronJobEntity j, AssistantMessage r, CronJobRunEntity run) {
                return DeliveryOutcome.delivered("user-x");
            }
        };

        DeliveryOutcome outcome = strategy.deliver(job, new AssistantMessage("hi"), run);

        assertEquals(DeliveryOutcome.Status.DELIVERED, outcome.status());
        assertEquals("user-x", outcome.target());
        verify(runMapper, times(2)).update(any(), any(Wrapper.class)); // claim + markDelivered
    }

    @Test
    void deliver_claimAlreadyTaken_returnsSkippedAndDoesNotInvokeDoDeliver() {
        // Claim returns 0 → another listener already won the CAS
        when(runMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        AtomicReference<Boolean> doDeliverInvoked = new AtomicReference<>(false);
        AbstractCronResultDelivery strategy = new AbstractCronResultDelivery(runMapper) {
            @Override public boolean supports(CronJobEntity j) { return true; }
            @Override
            protected DeliveryOutcome doDeliver(CronJobEntity j, AssistantMessage r, CronJobRunEntity run) {
                doDeliverInvoked.set(true);
                return DeliveryOutcome.delivered("never");
            }
        };

        DeliveryOutcome outcome = strategy.deliver(job, new AssistantMessage("hi"), run);

        assertEquals(DeliveryOutcome.Status.SKIPPED, outcome.status());
        assertEquals("already-claimed-by-other-instance", outcome.reason());
        assertFalse(doDeliverInvoked.get(), "doDeliver must not run after a failed CAS claim");
        verify(runMapper, times(1)).update(any(), any(Wrapper.class)); // only the failed claim
    }

    @Test
    void deliver_doDeliverThrows_marksNotDeliveredAndRethrows() {
        // Claim returns 1, then markNotDelivered returns 1
        when(runMapper.update(any(), any(Wrapper.class))).thenReturn(1, 1);

        RuntimeException oops = new RuntimeException("Slack 503 Service Unavailable");
        AbstractCronResultDelivery strategy = new AbstractCronResultDelivery(runMapper) {
            @Override public boolean supports(CronJobEntity j) { return true; }
            @Override
            protected DeliveryOutcome doDeliver(CronJobEntity j, AssistantMessage r, CronJobRunEntity run) {
                throw oops;
            }
        };

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> strategy.deliver(job, new AssistantMessage("hi"), run));
        assertSame(oops, thrown, "exception must propagate verbatim so the listener can audit it");
        verify(runMapper, times(2)).update(any(), any(Wrapper.class)); // claim + markNotDelivered
    }

    @Test
    void claimRun_concurrentInvocations_onlyOneSucceeds() throws Exception {
        // Simulates the cluster scenario: the SQL CAS guarantees exactly one
        // listener instance wins. Mock the mapper so the FIRST update() call
        // returns 1, all subsequent return 0 — matches DB semantics.
        Set<Integer> winnerThreadIds = java.util.Collections.synchronizedSet(new HashSet<>());
        AtomicReference<Boolean> firstClaim = new AtomicReference<>(true);
        when(runMapper.update(any(), any(Wrapper.class))).thenAnswer(inv -> {
            // First caller wins, others lose
            return firstClaim.compareAndSet(true, false) ? 1 : 0;
        });

        AbstractCronResultDelivery strategy = new AbstractCronResultDelivery(runMapper) {
            @Override public boolean supports(CronJobEntity j) { return true; }
            @Override
            protected DeliveryOutcome doDeliver(CronJobEntity j, AssistantMessage r, CronJobRunEntity run) {
                winnerThreadIds.add((int) Thread.currentThread().threadId());
                return DeliveryOutcome.delivered("winner");
            }
        };

        int threadCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threadCount);
        try {
            var futures = IntStream.range(0, threadCount).mapToObj(i -> pool.submit(() -> {
                start.await();
                return strategy.deliver(job, new AssistantMessage("hi"), run);
            })).toList();
            start.countDown();

            int delivered = 0;
            int skipped = 0;
            for (var f : futures) {
                try {
                    DeliveryOutcome o = f.get();
                    if (o.status() == DeliveryOutcome.Status.DELIVERED) delivered++;
                    else skipped++;
                } catch (ExecutionException ignored) {
                    // doDeliver throws are OK; counted as not-delivered
                }
            }

            assertEquals(1, delivered,
                    "Exactly one winner under concurrent claim — RFC-063r §2.6.1 invariant");
            assertEquals(threadCount - 1, skipped, "All others must observe SKIPPED");
            assertEquals(1, winnerThreadIds.size(),
                    "doDeliver must execute on exactly one thread");
        } finally {
            pool.shutdownNow();
        }
    }
}
