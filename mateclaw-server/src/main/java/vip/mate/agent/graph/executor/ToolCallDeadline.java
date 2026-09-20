package vip.mate.agent.graph.executor;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Interrupts cooperative callbacks in place, preserving their thread-local context. */
final class ToolCallDeadline {
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("tool-deadline-", 0).factory());

    static {
        TIMER.setRemoveOnCancelPolicy(true);
    }

    private final Thread owner = Thread.currentThread();
    private boolean active = true;
    private boolean expired;

    private synchronized void expire() {
        if (active) {
            expired = true;
            owner.interrupt();
        }
    }

    private synchronized boolean finish() {
        active = false;
        return expired;
    }

    static <T> T call(String toolName, long timeoutMs, Supplier<T> callback) throws TimeoutException {
        ToolCallDeadline deadline = new ToolCallDeadline();
        ScheduledFuture<?> timer = TIMER.schedule(deadline::expire, Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        try {
            T result = callback.get();
            if (deadline.finish()) throw timeout(toolName, timeoutMs);
            return result;
        } catch (RuntimeException failure) {
            if (deadline.finish()) {
                TimeoutException timeout = timeout(toolName, timeoutMs);
                timeout.initCause(failure);
                throw timeout;
            }
            throw failure;
        } finally {
            // Synchronize with the timer before this thread can execute another
            // tool. Never leave a late watchdog interrupt on a reused worker.
            boolean expired = deadline.finish();
            timer.cancel(false);
            if (expired) Thread.interrupted();
        }
    }

    private static TimeoutException timeout(String name, long timeoutMs) {
        return new TimeoutException("Tool " + name + " timed out after " + timeoutMs + "ms");
    }
}
