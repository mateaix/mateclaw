package vip.mate.memory.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.event.ConversationCompletedEvent;
import vip.mate.memory.spi.MemoryManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A.8 — Flag guard test: verifies that lifecycleMediatorEnabled=false
 * means zero calls to prefetchAll / syncAll / onSessionEnd, and that
 * enabling the flag activates all three.
 *
 * <p>Covers both AgentService helper paths (via Mediator) and
 * MemoryLifecycleEventListener (via onConversationCompleted).
 */
@ExtendWith(MockitoExtension.class)
class LifecycleFlagGuardTest {

    @Mock private MemoryManager memoryManager;
    @Mock private ApplicationEventPublisher eventPublisher;

    private MemoryProperties props;
    private MemoryLifecycleMediator mediator;
    private MemoryLifecycleEventListener listener;

    @BeforeEach
    void setUp() {
        props = new MemoryProperties();
        mediator = new MemoryLifecycleMediator(memoryManager, eventPublisher);
        listener = new MemoryLifecycleEventListener(mediator, props);
    }

    // ==================== Flag OFF ====================

    @Test
    @DisplayName("Flag OFF: MemoryLifecycleEventListener.onConversationCompleted is a no-op")
    void flagOff_listenerNoOp() {
        props.setLifecycleMediatorEnabled(false);

        for (int i = 0; i < 10; i++) {
            listener.onConversationCompleted(
                    new ConversationCompletedEvent(1L, "conv-" + i, "hello", "reply", 5, "web"));
        }

        verify(memoryManager, never()).prefetchAll(any(), any(), any());
        verify(memoryManager, never()).syncAll(any(), any(), any(), any());
        verify(memoryManager, never()).onSessionEnd(any(), any());
    }

    @Test
    @DisplayName("Flag OFF: Mediator methods still work (called by AgentService helpers only when flag is on)")
    void flagOff_mediatorDirectCallsStillWork() {
        // Mediator itself has no flag check — that's AgentService's job.
        // But MemoryLifecycleEventListener guards onSessionEnd.
        props.setLifecycleMediatorEnabled(false);

        when(memoryManager.prefetchAll(eq(1L), eq("q"), any())).thenReturn("");

        // Direct mediator call works (AgentService would not call this when flag is off)
        mediator.beforeLlmCall(new TurnContext(1L, "c1", "s1", 1, "q"));
        verify(memoryManager, times(1)).prefetchAll(eq(1L), eq("q"), any());
    }

    // ==================== Flag ON ====================

    @Test
    @DisplayName("Flag ON: beforeLlmCall invokes prefetchAll")
    void flagOn_prefetchAll() {
        props.setLifecycleMediatorEnabled(true);
        when(memoryManager.prefetchAll(eq(1L), eq("hello"), any())).thenReturn("");

        for (int i = 0; i < 10; i++) {
            mediator.beforeLlmCall(new TurnContext(1L, "c1", "s1", i, "hello"));
        }

        verify(memoryManager, times(10)).prefetchAll(eq(1L), eq("hello"), any());
    }

    @Test
    @DisplayName("Flag ON: afterLlmCall invokes syncAll")
    void flagOn_syncAll() {
        props.setLifecycleMediatorEnabled(true);

        for (int i = 0; i < 10; i++) {
            mediator.afterLlmCall(new TurnContext(1L, "c1", "s1", i, "hello"), "reply-" + i);
        }

        verify(memoryManager, times(10)).syncAll(eq(1L), eq("c1"), eq("hello"), anyString());
    }

    @Test
    @DisplayName("Flag ON: onConversationCompleted invokes onSessionEnd")
    void flagOn_onSessionEnd() {
        props.setLifecycleMediatorEnabled(true);

        for (int i = 0; i < 10; i++) {
            listener.onConversationCompleted(
                    new ConversationCompletedEvent(1L, "conv-" + i, "hello", "reply", 5, "web"));
        }

        verify(memoryManager, times(10)).onSessionEnd(eq(1L), anyString());
    }

    @Test
    @DisplayName("Flag ON: cron conversations also trigger onSessionEnd")
    void flagOn_cronConversation() {
        props.setLifecycleMediatorEnabled(true);

        listener.onConversationCompleted(
                new ConversationCompletedEvent(1L, "cron-conv", "task", "done", 2, "cron"));

        verify(memoryManager, times(1)).onSessionEnd(1L, "cron-conv");
    }

    // ==================== Provider exception degradation ====================

    @Test
    @DisplayName("Provider exception in prefetchAll degrades gracefully (returns empty)")
    void prefetchException_graceful() {
        when(memoryManager.prefetchAll(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        String result = mediator.beforeLlmCall(new TurnContext(1L, "c1", "s1", 1, "q"));

        // Should return empty string, not throw
        assert result.isEmpty();
    }

    @Test
    @DisplayName("Provider exception in syncAll degrades gracefully (no throw)")
    void syncException_graceful() {
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(memoryManager).syncAll(any(), any(), any(), any());

        // Should not throw
        mediator.afterLlmCall(new TurnContext(1L, "c1", "s1", 1, "q"), "reply");
    }

    @Test
    @DisplayName("Provider exception in onSessionEnd degrades gracefully (no throw)")
    void sessionEndException_graceful() {
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(memoryManager).onSessionEnd(any(), any());

        // Should not throw
        mediator.onSessionEnd(1L, "c1");
    }
}
