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

    private void invokeApply(MemorySummarizationService svc, long agentId, String entriesJson) throws Exception {
        JsonNode node = mapper.readTree(entriesJson);
        Method m = MemorySummarizationService.class
                .getDeclaredMethod("applyStructuredEntries", Long.class, JsonNode.class);
        m.setAccessible(true);
        m.invoke(svc, agentId, node);
    }

    @Test
    @DisplayName("valid typed entries are routed to structured memory")
    void routesValidEntries() throws Exception {
        StructuredMemoryService structured = mock(StructuredMemoryService.class);
        MemorySummarizationService svc = newService(structured);

        invokeApply(svc, 1000000001L, """
                [
                  {"type": "project", "key": "project_codename", "content": "项目代号：云梯计划"},
                  {"type": "user", "key": "preferred_output_format", "content": "偏好表格输出"}
                ]
                """);

        verify(structured).remember(1000000001L, "project", "project_codename", "项目代号：云梯计划", "auto-summary");
        verify(structured).remember(1000000001L, "user", "preferred_output_format", "偏好表格输出", "auto-summary");
        verifyNoMoreInteractions(structured);
    }

    @Test
    @DisplayName("malformed or unknown-type entries are skipped")
    void skipsInvalidEntries() throws Exception {
        StructuredMemoryService structured = mock(StructuredMemoryService.class);
        MemorySummarizationService svc = newService(structured);

        invokeApply(svc, 1000000001L, """
                [
                  {"type": "secret", "key": "k", "content": "bad type"},
                  {"type": "project", "key": "", "content": "missing key"},
                  {"type": "project", "key": "ok_key", "content": ""},
                  {"type": "project", "key": "good", "content": "kept"}
                ]
                """);

        // Only the last, fully-valid entry is written.
        verify(structured).remember(1000000001L, "project", "good", "kept", "auto-summary");
        verifyNoMoreInteractions(structured);
    }

    @Test
    @DisplayName("null / non-array structured_entries is a no-op")
    void noopForNullOrNonArray() throws Exception {
        StructuredMemoryService structured = mock(StructuredMemoryService.class);
        MemorySummarizationService svc = newService(structured);

        invokeApply(svc, 1000000001L, "null");
        invokeApply(svc, 1000000001L, "\"not-an-array\"");
        invokeApply(svc, 1000000001L, "[]");

        verifyNoInteractions(structured);
    }
}
