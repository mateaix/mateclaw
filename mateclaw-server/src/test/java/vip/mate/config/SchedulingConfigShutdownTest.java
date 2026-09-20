package vip.mate.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SchedulingConfigShutdownTest {
    private ThreadPoolTaskScheduler scheduler() {
        var scheduler = (ThreadPoolTaskScheduler) new SchedulingConfig().taskScheduler();
        scheduler.initialize();
        return scheduler;
    }

    @Test
    void futureTaskDoesNotHoldGracefulShutdownOpen() throws Exception {
        var scheduler = scheduler();
        var ran = new AtomicBoolean();
        var pending = scheduler.schedule(() -> ran.set(true), Instant.now().plusSeconds(3600));
        try (var closer = Executors.newSingleThreadExecutor()) {
            try {
                var closed = closer.submit(scheduler::destroy);
                assertDoesNotThrow(() -> closed.get(2, TimeUnit.SECONDS),
                        "unstarted delayed tasks must not consume the graceful shutdown window");
                assertTrue(pending.isCancelled());
                assertFalse(ran.get());
            } finally {
                scheduler.getScheduledExecutor().shutdownNow();
            }
        }
    }

    @Test
    void alreadyRunningTaskStillFinishesWithoutInterruption() throws Exception {
        var scheduler = scheduler();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var completed = new AtomicBoolean();
        scheduler.schedule(() -> {
            started.countDown();
            try {
                if (release.await(5, TimeUnit.SECONDS)) completed.set(true);
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        }, Instant.now());
        try (var closer = Executors.newSingleThreadExecutor()) {
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                var closed = closer.submit(scheduler::destroy);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!scheduler.getScheduledExecutor().isShutdown() && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertTrue(scheduler.getScheduledExecutor().isShutdown());
                assertFalse(closed.isDone(), "shutdown must wait for the running task");
                release.countDown();
                closed.get(2, TimeUnit.SECONDS);
                assertTrue(completed.get());
                assertFalse(interrupted.get());
            } finally {
                release.countDown();
                scheduler.getScheduledExecutor().shutdownNow();
            }
        }
    }
}
