package vip.mate.tool.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import vip.mate.agent.AgentToolSet;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.repository.ToolMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolServiceRuntimeNamesTest {

    static class ImageGenerateTool {
        @Tool(description = "text to image")
        public String image_generate() {
            return "";
        }
    }

    @Test
    @DisplayName("listTools enriches DB class/bean rows with runtime function names")
    void listToolsEnrichesRuntimeNames() {
        ToolMapper mapper = mock(ToolMapper.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolEntity row = new ToolEntity();
        row.setName("ImageGenerateTool");
        row.setBeanName("imageGenerateTool");

        ImageGenerateTool bean = new ImageGenerateTool();
        AgentToolSet set = AgentToolSet.fromCallbacks(
                List.of(bean),
                List.of(ToolCallbacks.from(bean)),
                Map.of(bean, "imageGenerateTool")::get);
        when(registry.listToolEntities()).thenReturn(List.of(row));
        when(registry.getAllToolBeanSetForAdmin()).thenReturn(set);

        ToolService service = new ToolService(mapper, registry);

        ToolEntity result = service.listTools().get(0);

        assertEquals(List.of("image_generate"), result.getRuntimeNames());
    }

    @Test
    @DisplayName("runtime names are still enriched for disabled tool rows")
    void disabledRowsStillGetRuntimeNames() {
        ToolMapper mapper = mock(ToolMapper.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolEntity row = new ToolEntity();
        row.setName("ImageGenerateTool");
        row.setBeanName("imageGenerateTool");
        row.setEnabled(false);

        ImageGenerateTool bean = new ImageGenerateTool();
        AgentToolSet adminSet = AgentToolSet.fromCallbacks(
                List.of(bean),
                List.of(ToolCallbacks.from(bean)),
                Map.of(bean, "imageGenerateTool")::get);
        when(registry.listToolEntities()).thenReturn(List.of(row));
        when(registry.getAllToolBeanSetForAdmin()).thenReturn(adminSet);

        ToolService service = new ToolService(mapper, registry);

        ToolEntity result = service.listTools().get(0);

        assertEquals(List.of("image_generate"), result.getRuntimeNames());
    }
}
