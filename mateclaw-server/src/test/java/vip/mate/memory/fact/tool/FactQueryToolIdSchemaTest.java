package vip.mate.memory.fact.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.fact.query.FactQueryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FactQueryToolIdSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("fact tools publish agentId as a string parameter so LLM tool calls preserve precision")
    void factToolAgentIdSchemasAreString() throws Exception {
        FactQueryTool tool = new FactQueryTool(mock(FactQueryService.class), mock(MemoryProperties.class));

        assertAgentIdIsString(tool, "fact_probe");
        assertAgentIdIsString(tool, "fact_list_contradictions");
    }

    private static void assertAgentIdIsString(Object tool, String name) throws Exception {
        JsonNode root = MAPPER.readTree(callback(tool, name).getToolDefinition().inputSchema());

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
