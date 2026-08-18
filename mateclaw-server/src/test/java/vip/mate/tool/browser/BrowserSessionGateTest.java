package vip.mate.tool.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserSessionGateTest {

    @Test
    @DisplayName("operations for one browser session never overlap")
    void serializesSameSession() throws Exception {
        BrowserSessionGate gate = new BrowserSessionGate(16);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                try (BrowserSessionGate.Lease ignored = gate.enter("conversation-1")) {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    firstEntered.countDown();
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                    active.decrementAndGet();
                    return "first";
                }
            });
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

            var second = executor.submit(() -> {
                try (BrowserSessionGate.Lease ignored = gate.enter("conversation-1")) {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    active.decrementAndGet();
                    return "second";
                }
            });

            Thread.sleep(50);
            releaseFirst.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("second", second.get(2, TimeUnit.SECONDS));
        }

        assertEquals(1, maxActive.get());
    }

    @Test
    @DisplayName("one stripe serializes different sessions sharing a Playwright driver")
    void singleStripeSerializesDifferentSessions() throws Exception {
        BrowserSessionGate gate = new BrowserSessionGate(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> runMeasured(gate, "conversation-1", active, maxActive));
            var second = executor.submit(() -> runMeasured(gate, "conversation-2", active, maxActive));
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }

        assertEquals(1, maxActive.get());
    }

    private static String runMeasured(BrowserSessionGate gate, String key,
                                      AtomicInteger active, AtomicInteger maxActive) throws Exception {
        try (BrowserSessionGate.Lease ignored = gate.enter(key)) {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            Thread.sleep(25);
            active.decrementAndGet();
            return key;
        }
    }
}
