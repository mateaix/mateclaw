package vip.mate.wiki.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiPageTypePermissionService;
import vip.mate.wiki.service.WikiRawMaterialService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WikiToolIdSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("wiki tools publish agentId as a string parameter so LLM tool calls preserve precision")
    void wikiToolAgentIdSchemasAreString() throws Exception {
        WikiTool tool = new WikiTool(
                mock(WikiPageService.class),
                mock(WikiKnowledgeBaseService.class),
                mock(WikiRawMaterialService.class),
                mock(HybridRetriever.class),
                new ObjectMapper(),
                mock(WikiPageTypePermissionService.class));

        int checked = 0;
        for (ToolCallback callback : ToolCallbacks.from(tool)) {
            JsonNode root = MAPPER.readTree(callback.getToolDefinition().inputSchema());
            checked += assertIdParamIsString(root, callback, "agentId");
            checked += assertIdParamIsString(root, callback, "kbId");
            checked += assertIdParamIsString(root, callback, "rawId");
        }

        assertThat(checked).isGreaterThan(0);
    }

    private static int assertIdParamIsString(JsonNode root, ToolCallback callback, String property) {
        JsonNode type = root.at("/properties/" + property + "/type");
        if (!type.isMissingNode()) {
            assertThat(type.asText())
                        .as(callback.getToolDefinition().name())
                        .isEqualTo("string");
            return 1;
        }
        return 0;
    }
}
