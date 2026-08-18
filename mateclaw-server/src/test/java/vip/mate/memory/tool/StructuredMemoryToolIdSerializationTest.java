package vip.mate.memory.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.memory.service.StructuredMemoryService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredMemoryToolIdSerializationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("recall_structured returns agentId as a JSON string to preserve snowflake precision")
    void recallStructuredSerializesAgentIdAsString() {
        StructuredMemoryService service = mock(StructuredMemoryService.class);
        when(service.recall(anyLong(), nullable(String.class), nullable(String.class), anyString()))
                .thenReturn(List.of());
        StructuredMemoryTool tool = new StructuredMemoryTool(
                service,
                new MemoryOwnerResolver(),
                new MemoryProperties());

        String json = tool.recall_structured("2079862124134313986", "reference", "meeting", null);

        assertThat(json).contains("\"agentId\": \"2079862124134313986\"");
        assertThat(json).doesNotContain("\"agentId\": 2079862124134313986");
    }

    @Test
    @DisplayName("recall_structured publishes agentId as a string parameter so LLM tool calls preserve precision")
    void recallStructuredAgentIdSchemaIsString() throws Exception {
        StructuredMemoryTool tool = new StructuredMemoryTool(
                mock(StructuredMemoryService.class),
                new MemoryOwnerResolver(),
                new MemoryProperties());

        String schema = callback(tool, "recall_structured").getToolDefinition().inputSchema();
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
