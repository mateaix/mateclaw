package vip.mate.skill.lessons;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.skill.runtime.SkillRuntimeService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SkillLessonsToolIdSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("record_lesson publishes agentId as a string parameter so LLM tool calls preserve precision")
    void recordLessonAgentIdSchemaIsString() throws Exception {
        SkillLessonsTool tool = new SkillLessonsTool(mock(SkillRuntimeService.class), mock(SkillLessonsService.class));

        JsonNode root = MAPPER.readTree(callback(tool, "record_lesson").getToolDefinition().inputSchema());

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
