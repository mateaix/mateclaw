package vip.mate.memory.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.memory.spi.MemoryManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A.9 — Unit tests for MemoryLifecycleMediator covering:
 * normal path, provider exception degradation, and onSessionEnd for cron conversations.
 */
@ExtendWith(MockitoExtension.class)
class MemoryLifecycleMediatorTest {

    @Mock private MemoryManager memoryManager;
    @Mock private ApplicationEventPublisher eventPublisher;

    private MemoryLifecycleMediator mediator;

    @BeforeEach
    void setUp() {
        mediator = new MemoryLifecycleMediator(memoryManager, eventPublisher);
    }

    // ==================== Normal path ====================

    @Test
    @DisplayName("beforeLlmCall returns prefetchAll result and publishes TurnStartedEvent")
    void beforeLlmCall_normalPath() {
        when(memoryManager.prefetchAll(eq(1L), eq("hello"), any()))
                .thenReturn("<memory-context>some context</memory-context>");

        TurnContext ctx = new TurnContext(1L, "c1", "s1", 1, "hello");
        String result = mediator.beforeLlmCall(ctx);

        assertEquals("<memory-context>some context</memory-context>", result);
        verify(memoryManager).prefetchAll(eq(1L), eq("hello"), any());

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertTrue(eventCaptor.getValue() instanceof TurnStartedEvent);
        assertEquals(ctx, ((TurnStartedEvent) eventCaptor.getValue()).context());
    }

    @Test
    @DisplayName("beforeLlmCall returns empty string when prefetchAll returns empty")
    void beforeLlmCall_emptyPrefetch() {
        when(memoryManager.prefetchAll(any(), any(), any())).thenReturn("");

        String result = mediator.beforeLlmCall(new TurnContext(1L, "c1", "s1", 1, "q"));

        assertEquals("", result);
    }

    @Test
    @DisplayName("afterLlmCall calls syncAll and publishes TurnCompletedEvent")
    void afterLlmCall_normalPath() {
        TurnContext ctx = new TurnContext(1L, "c1", "s1", 1, "hello");
        mediator.afterLlmCall(ctx, "reply text");

        verify(memoryManager).syncAll(1L, "c1", "hello", "reply text");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertTrue(eventCaptor.getValue() instanceof TurnCompletedEvent);
        TurnCompletedEvent event = (TurnCompletedEvent) eventCaptor.getValue();
        assertEquals(ctx, event.context());
        assertEquals("reply text", event.assistantReply());
    }

    @Test
    @DisplayName("onSessionEnd delegates to memoryManager.onSessionEnd")
    void onSessionEnd_normalPath() {
        mediator.onSessionEnd(1L, "conv-123");

        verify(memoryManager).onSessionEnd(1L, "conv-123");
    }

    // ==================== Provider exception degradation ====================

    @Test
    @DisplayName("beforeLlmCall degrades to empty string when prefetchAll throws")
    void beforeLlmCall_exceptionDegrades() {
        when(memoryManager.prefetchAll(any(), any(), any()))
                .thenThrow(new RuntimeException("provider down"));

        String result = mediator.beforeLlmCall(new TurnContext(1L, "c1", "s1", 1, "q"));

        assertEquals("", result);
    }

    @Test
    @DisplayName("afterLlmCall swallows syncAll exceptions")
    void afterLlmCall_exceptionSwallowed() {
        doThrow(new RuntimeException("sync failed"))
                .when(memoryManager).syncAll(any(), any(), any(), any());

        // Should not throw
        mediator.afterLlmCall(new TurnContext(1L, "c1", "s1", 1, "q"), "reply");
    }

    @Test
    @DisplayName("onSessionEnd swallows exceptions")
    void onSessionEnd_exceptionSwallowed() {
        doThrow(new RuntimeException("session end failed"))
                .when(memoryManager).onSessionEnd(any(), any());

        // Should not throw
        mediator.onSessionEnd(1L, "c1");
    }

    // ==================== Cron conversations ====================

    @Test
    @DisplayName("onSessionEnd works the same for cron-triggered conversations")
    void onSessionEnd_cronConversation() {
        // onSessionEnd has no special handling for trigger source;
        // that distinction only matters in PostConversationMemoryListener.
        // The mediator processes all conversations equally.
        mediator.onSessionEnd(42L, "cron-conv-001");

        verify(memoryManager, times(1)).onSessionEnd(42L, "cron-conv-001");
    }

    // ==================== Reentrant / multi-turn ====================

    @Test
    @DisplayName("Multiple sequential turns do not interfere (Mediator is stateless)")
    void multipleTurns_noInterference() {
        when(memoryManager.prefetchAll(any(), any(), any())).thenReturn("");

        for (int i = 0; i < 5; i++) {
            TurnContext ctx = new TurnContext(1L, "c1", "s1", i, "msg-" + i);
            mediator.beforeLlmCall(ctx);
            mediator.afterLlmCall(ctx, "reply-" + i);
        }

        verify(memoryManager, times(5)).prefetchAll(eq(1L), any(), any());
        verify(memoryManager, times(5)).syncAll(eq(1L), eq("c1"), any(), any());
    }
}
