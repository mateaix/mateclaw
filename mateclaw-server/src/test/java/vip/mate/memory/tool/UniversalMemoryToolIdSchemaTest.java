package vip.mate.memory.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.workspace.document.WorkspaceFileService;

import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UniversalMemoryToolIdSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("remember publishes agentId as a string parameter so LLM tool calls preserve precision")
    void rememberAgentIdSchemaIsString() throws Exception {
        UniversalMemoryTool tool = new UniversalMemoryTool(
                mock(WorkspaceFileService.class),
                mock(ApplicationEventPublisher.class),
                mock(MemoryOwnerResolver.class),
                mock(MemoryProperties.class));

        JsonNode root = MAPPER.readTree(callback(tool, "remember").getToolDefinition().inputSchema());

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
