package vip.mate.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.memory.MemoryProperties;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.document.WorkspaceFileService;

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The conversation summarizer routes typed facts it extracts into structured
 * memory (the query-conditioned recall channel), so project/reference facts kept
 * out of the always-on MEMORY.md still become recallable instead of being
 * stranded in daily notes. Valid entries are written; malformed ones are skipped.
 */
class MemorySummarizationStructuredRoutingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private MemorySummarizationService newService(StructuredMemoryService structured) {
        return new MemorySummarizationService(
                mock(ConversationService.class),
                mock(WorkspaceFileService.class),
                mock(ModelConfigService.class),
                mock(AgentGraphBuilder.class),
                mock(MemoryProperties.class),
                mapper,
                structured);
    }

    private void invokeApply(MemorySummarizationService svc, long agentId, String ownerKey, String entriesJson)
            throws Exception {
        JsonNode node = mapper.readTree(entriesJson);
        Method m = MemorySummarizationService.class
                .getDeclaredMethod("applyStructuredEntries", Long.class, JsonNode.class, String.class);
        m.setAccessible(true);
        m.invoke(svc, agentId, node, ownerKey);
    }

    @Test
    @DisplayName("only durable typed entries are routed to structured memory")
    void routesValidEntries() throws Exception {
        StructuredMemoryService structured = mock(StructuredMemoryService.class);
        MemorySummarizationService svc = newService(structured);

        invokeApply(svc, 1000000001L, "owner-1", """
                [
                  {"type":"project","key":"project_codename","content":"项目代号：云梯计划",
                   "scope":"project","stability":"ongoing","confidence":0.9,"evidence_count":1,
                   "expires_at":null,"explicitly_persistent":false},
                  {"type":"user","key":"preferred_output_format","content":"以后默认使用表格输出",
                   "scope":"user","stability":"durable","confidence":0.95,"evidence_count":1,
                   "expires_at":null,"explicitly_persistent":true},
                  {"type":"user","key":"preferred_word_count","content":"本次回答至少 3000 字",
                   "scope":"turn","stability":"transient","confidence":0.95,"evidence_count":1,
                   "expires_at":null,"explicitly_persistent":false}
                ]
                """);

        verify(structured).remember(eq(1000000001L), argThat(candidate ->
                candidate.type().equals("project") && candidate.key().equals("project_codename")),
                eq("auto-summary"), eq("owner-1"));
        verify(structured).remember(eq(1000000001L), argThat(candidate ->
                candidate.type().equals("user") && candidate.key().equals("preferred_output_format")),
                eq("auto-summary"), eq("owner-1"));
        verifyNoMoreInteractions(structured);
    }

    @Test
    @DisplayName("malformed or unknown-type entries are skipped")
    void skipsInvalidEntries() throws Exception {
        StructuredMemoryService structured = mock(StructuredMemoryService.class);
        MemorySummarizationService svc = newService(structured);

        invokeApply(svc, 1000000001L, "owner-1", """
                [
                  {"type":"secret","key":"k","content":"bad type","scope":"global","stability":"durable","confidence":1,"evidence_count":1,"explicitly_persistent":true},
                  {"type":"project","key":"","content":"missing key","scope":"project","stability":"ongoing","confidence":1,"evidence_count":1,"explicitly_persistent":false},
                  {"type":"project","key":"ok_key","content":"","scope":"project","stability":"ongoing","confidence":1,"evidence_count":1,"explicitly_persistent":false},
                  {"type":"project","key":"good","content":"kept","scope":"project","stability":"ongoing","confidence":0.9,"evidence_count":1,"expires_at":null,"explicitly_persistent":false}
                ]
                """);

        // Only the last, fully-valid entry is written.
        verify(structured).remember(eq(1000000001L), argThat(candidate -> candidate.key().equals("good")),
                eq("auto-summary"), eq("owner-1"));
        verifyNoMoreInteractions(structured);
    }

    @Test
    @DisplayName("null / non-array structured_entries is a no-op")
    void noopForNullOrNonArray() throws Exception {
        StructuredMemoryService structured = mock(StructuredMemoryService.class);
        MemorySummarizationService svc = newService(structured);

        invokeApply(svc, 1000000001L, "owner-1", "null");
        invokeApply(svc, 1000000001L, "owner-1", "\"not-an-array\"");
        invokeApply(svc, 1000000001L, "owner-1", "[]");

        verifyNoInteractions(structured);
    }
}
