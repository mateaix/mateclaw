package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallDeadlineTest {
    @Test
    void timeoutInterruptsCallbackAndDoesNotPoisonNextCall() throws Exception {
        ThreadLocal<String> context = new ThreadLocal<>();
        context.set("conversation");
        try {
            assertThrows(TimeoutException.class, () -> ToolCallDeadline.call("extract_document_text", 30, () -> {
                assertEquals("conversation", context.get());
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "partial result must not be treated as success";
            }));
            assertFalse(Thread.currentThread().isInterrupted());
            assertEquals("next", ToolCallDeadline.call("next", 1000, () -> "next"));
        } finally {
            context.remove();
        }
    }

    @Test
    void successfulCallbackCancelsItsWatchdog() throws Exception {
        assertEquals("ok", ToolCallDeadline.call("fast", 30, () -> "ok"));
        Thread.sleep(80);
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
