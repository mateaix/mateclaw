package vip.mate.wiki.hotcache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.memory.event.ConversationCompletedEvent;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotCacheEventListenerTest {

    private HotCacheUpdateScheduler scheduler;
    private WikiKnowledgeBaseService kbService;
    private HotCacheEventListener listener;

    @BeforeEach
    void setUp() {
        scheduler = mock(HotCacheUpdateScheduler.class);
        kbService = mock(WikiKnowledgeBaseService.class);
        listener = new HotCacheEventListener(scheduler, kbService);
    }

    private static WikiKnowledgeBaseEntity kb(Long id) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(id);
        kb.setName("kb-" + id);
        return kb;
    }

    private static ConversationCompletedEvent event(Long agentId) {
        return new ConversationCompletedEvent(agentId, "conv-1", "hi", "hello", 2, "web");
    }

    @Test
    @DisplayName("agent has KBs → schedule rebuild for the first one with reason CONVERSATION_END")
    void schedulesForPrimaryKb() {
        when(kbService.listByAgentId(7L)).thenReturn(List.of(kb(100L), kb(200L)));

        listener.onConversationEnd(event(7L));

        verify(scheduler).scheduleRebuild(100L, HotCacheUpdateReason.CONVERSATION_END);
        verify(scheduler, never()).scheduleRebuild(eq(200L), any());
    }

    @Test
    @DisplayName("agent has no KBs → no rebuild scheduled")
    void noKbs_noOp() {
        when(kbService.listByAgentId(7L)).thenReturn(List.of());

        listener.onConversationEnd(event(7L));

        verify(scheduler, never()).scheduleRebuild(any(), any());
    }

    @Test
    @DisplayName("null agentId → no rebuild scheduled, no KB lookup")
    void nullAgent_noOp() {
        listener.onConversationEnd(event(null));

        verify(scheduler, never()).scheduleRebuild(any(), any());
        verify(kbService, never()).listByAgentId(any());
    }

    @Test
    @DisplayName("kbService throws → no rebuild scheduled, exception swallowed")
    void resolverThrows_noOp() {
        when(kbService.listByAgentId(eq(7L))).thenThrow(new RuntimeException("db down"));

        listener.onConversationEnd(event(7L));

        verify(scheduler, never()).scheduleRebuild(any(), any());
    }
}
