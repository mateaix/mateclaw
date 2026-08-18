package vip.mate.memory.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionSearchToolIdSchemaTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("session_search publishes agentId as a string parameter so LLM tool calls preserve precision")
    void sessionSearchAgentIdSchemaIsString() throws Exception {
        SessionSearchTool tool = new SessionSearchTool(mock(SessionSearchService.class));

        String schema = callback(tool, "session_search").getToolDefinition().inputSchema();
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
