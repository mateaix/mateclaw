package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.memory.service.MemoryRecallTracker;
import vip.mate.workspace.document.WorkspaceFileService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceMemoryToolIdSerializationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("search_workspace_memory returns agentId as a JSON string to preserve snowflake precision")
    void searchWorkspaceMemorySerializesAgentIdAsString() {
        WorkspaceFileService files = mock(WorkspaceFileService.class);
        when(files.searchSnippets(anyLong(), anyString(), anySet(), anyInt(), anyString()))
                .thenReturn(List.of());
        WorkspaceMemoryTool tool = new WorkspaceMemoryTool(
                files,
                mock(MemoryRecallTracker.class),
                new MemoryOwnerResolver(),
                new MemoryProperties());

        String json = tool.search_workspace_memory("2079862124134313986", "meeting", "all", 10, null);

        assertThat(json).contains("\"agentId\": \"2079862124134313986\"");
        assertThat(json).doesNotContain("\"agentId\": 2079862124134313986");
    }

    @Test
    @DisplayName("search_workspace_memory publishes agentId as a string parameter so LLM tool calls preserve precision")
    void searchWorkspaceMemoryAgentIdSchemaIsString() throws Exception {
        WorkspaceMemoryTool tool = new WorkspaceMemoryTool(
                mock(WorkspaceFileService.class),
                mock(MemoryRecallTracker.class),
                new MemoryOwnerResolver(),
                new MemoryProperties());

        String schema = callback(tool, "search_workspace_memory").getToolDefinition().inputSchema();
        JsonNode root = MAPPER.readTree(schema);

        assertThat(root.at("/properties/agentId/type").asText()).isEqualTo("string");
    }

    private static ToolCallback callback(Object tool, String name) {
        for (ToolCallback callback : ToolCallbacks.from(tool)) {
            if (name.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        throw new AssertionError("Missing tool callback: " + name);
    }
}
