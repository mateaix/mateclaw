package vip.mate.memory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.memory.MemoryProperties;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies the nightly structured-memory consolidation gating and safety
 * invariants: it skips when disabled, below the min-entries gate, when the LLM
 * declines or returns unparseable output, and when the result would grow the
 * entry count — and only writes a genuinely reduced set.
 */
class StructuredMemoryConsolidationServiceTest {

    private static final long AGENT_ID = 1000000001L;

    private StructuredMemoryService memory;
    private ModelConfigService modelConfigService;
    private AgentGraphBuilder agentGraphBuilder;
    private MemoryProperties props;

    private StructuredMemoryConsolidationService newService(String llmReply) {
        memory = mock(StructuredMemoryService.class);
        modelConfigService = mock(ModelConfigService.class);
        agentGraphBuilder = mock(AgentGraphBuilder.class);
        props = new MemoryProperties();
        props.setStructuredConsolidationMinEntries(8);

        // One always-on type, one shared bucket — a single bucket per agent.
        when(memory.alwaysOnTypes()).thenReturn(List.of("user"));
        when(memory.consolidatableOwnerKeys(eq(AGENT_ID), eq("user"))).thenReturn(java.util.Arrays.asList((String) null));

        if (llmReply != null) {
            ChatModel model = mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenReturn(
                    new ChatResponse(List.of(new Generation(new AssistantMessage(llmReply)))));
            when(agentGraphBuilder.buildRuntimeChatModel(any())).thenReturn(model);
        }
        return new StructuredMemoryConsolidationService(memory, modelConfigService, agentGraphBuilder, props);
    }

    @Test
    @DisplayName("disabled flag skips entirely, never touching memory")
    void disabledSkips() {
        StructuredMemoryConsolidationService svc = newService(null);
        props.setStructuredConsolidationEnabled(false);

        StructuredMemoryConsolidationService.ConsolidationStats stats = svc.consolidateAgent(AGENT_ID);

        assertEquals(0, stats.ownersConsolidated);
        verify(memory, never()).readTypeRaw(anyLong(), anyString(), any());
        verify(memory, never()).replaceTypeEntries(anyLong(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("buckets below the min-entries gate are skipped without an LLM call")
    void minEntriesSkips() {
        StructuredMemoryConsolidationService svc = newService(null);
        when(memory.readTypeRaw(AGENT_ID, "user", null)).thenReturn("small");
        when(memory.countEntries("small")).thenReturn(5); // < 8

        StructuredMemoryConsolidationService.ConsolidationStats stats = svc.consolidateAgent(AGENT_ID);

        assertEquals(1, stats.skippedSmall);
        assertEquals(0, stats.ownersConsolidated);
        verify(memory, never()).replaceTypeEntries(anyLong(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("unparseable LLM output is skipped, leaving the bucket untouched")
    void invalidJsonSkips() {
        StructuredMemoryConsolidationService svc = newService("this is not json at all");
        when(memory.readTypeRaw(AGENT_ID, "user", null)).thenReturn("body");
        when(memory.countEntries("body")).thenReturn(10);

        StructuredMemoryConsolidationService.ConsolidationStats stats = svc.consolidateAgent(AGENT_ID);

        assertEquals(1, stats.ownersConsolidated);
        assertEquals(0, stats.updated);
        verify(memory, never()).replaceTypeEntries(anyLong(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("shouldUpdate=false is respected — no write")
    void shouldUpdateFalseSkips() {
        StructuredMemoryConsolidationService svc =
                newService("{\"shouldUpdate\":false,\"entries\":[],\"reason\":\"already concise\"}");
        when(memory.readTypeRaw(AGENT_ID, "user", null)).thenReturn("body");
        when(memory.countEntries("body")).thenReturn(10);

        StructuredMemoryConsolidationService.ConsolidationStats stats = svc.consolidateAgent(AGENT_ID);

        assertEquals(0, stats.updated);
        verify(memory, never()).replaceTypeEntries(anyLong(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("a result that grows the entry count is rejected")
    void growthRejected() {
        String reply = "{\"shouldUpdate\":true,\"entries\":["
                + "{\"key\":\"a\",\"content\":\"x\"},{\"key\":\"b\",\"content\":\"y\"},{\"key\":\"c\",\"content\":\"z\"}"
                + "],\"reason\":\"split\"}";
        StructuredMemoryConsolidationService svc = newService(reply);
        when(memory.readTypeRaw(AGENT_ID, "user", null)).thenReturn("body");
        when(memory.countEntries("body")).thenReturn(2); // 3 produced > 2 existing
        props.setStructuredConsolidationMinEntries(2);

        StructuredMemoryConsolidationService.ConsolidationStats stats = svc.consolidateAgent(AGENT_ID);

        assertEquals(0, stats.updated);
        verify(memory, never()).replaceTypeEntries(anyLong(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("a genuine reduction is written back")
    void successReplaces() {
        String reply = "{\"shouldUpdate\":true,\"entries\":["
                + "{\"key\":\"reply_style\",\"content\":\"concise\"},{\"key\":\"language\",\"content\":\"chinese\"}"
                + "],\"reason\":\"merged duplicates\"}";
        StructuredMemoryConsolidationService svc = newService(reply);
        when(memory.readTypeRaw(AGENT_ID, "user", null)).thenReturn("body");
        when(memory.countEntries("body")).thenReturn(10);

        StructuredMemoryConsolidationService.ConsolidationStats stats = svc.consolidateAgent(AGENT_ID);

        assertEquals(1, stats.updated);
        assertEquals(10, stats.entriesBefore);
        assertEquals(2, stats.entriesAfter);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LinkedHashMap<String, String>> captor = ArgumentCaptor.forClass(LinkedHashMap.class);
        verify(memory).replaceTypeEntries(eq(AGENT_ID), eq("user"), isNull(), captor.capture(), eq("consolidation"));
        assertEquals(2, captor.getValue().size());
        assertTrue(captor.getValue().containsKey("reply_style"));
    }
}
