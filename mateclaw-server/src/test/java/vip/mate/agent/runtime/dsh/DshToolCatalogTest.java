package vip.mate.agent.runtime.dsh;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DshToolCatalogTest {
    @Test
    void projectsToolDefinitionsAndDeduplicatesByRuntimeName() {
        ToolCallback first = callback("read", "read file", "{\"type\":\"object\"}");
        ToolCallback duplicate = callback("read", "duplicate", "{}");
        ToolCallback second = callback("search", "search files", "{}");

        List<DshToolDescriptor> descriptors = DshToolCatalog.fromCallbacks(List.of(first, duplicate, second));

        assertEquals(2, descriptors.size());
        assertEquals("read", descriptors.get(0).name());
        assertEquals("read file", descriptors.get(0).description());
        assertEquals("search", descriptors.get(1).name());
    }

    private static ToolCallback callback(String name, String description, String schema) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description(description).inputSchema(schema).build());
        return callback;
    }
}
