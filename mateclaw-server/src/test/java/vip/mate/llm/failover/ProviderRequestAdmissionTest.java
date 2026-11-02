package vip.mate.llm.failover;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ProviderRequestAdmissionTest {
    @Test
    void differentProvidersProceedAndQueueTimeoutDoesNotDamageHealth() throws Exception {
        var props = new ProviderHealthProperties();
        props.setAdmissionTimeoutMs(20);
        var tracker = new ProviderHealthTracker(props);
        try (var first = tracker.acquireRequest("a", () -> false);
             var independent = tracker.acquireRequest("b", () -> false)) {
            assertNotNull(first);
            assertNotNull(independent);
            assertNull(tracker.acquireRequest("a", () -> false));
            assertTrue(tracker.snapshot().isEmpty());
        }
        try (var reused = tracker.acquireRequest("a", () -> false)) {
            assertNotNull(reused, "Closing the first stream must release its permit");
        }
    }

    @Test
    void queuedCancellationDoesNotStealOrLeakCapacity() throws Exception {
        var tracker = new ProviderHealthTracker(new ProviderHealthProperties());
        var cancel = new AtomicBoolean();
        try (var workers = Executors.newSingleThreadExecutor();
             var first = tracker.acquireRequest("a", () -> false)) {
            var entered = new CountDownLatch(1);
            var future = workers.submit(() -> {
                entered.countDown();
                return tracker.acquireRequest("a", cancel::get);
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            cancel.set(true);
            var failure = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
        }
        try (var reused = tracker.acquireRequest("a", () -> false)) {
            assertNotNull(reused);
        }
    }
}
