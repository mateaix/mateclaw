package vip.mate.tool.mcp.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrefixedNameToolCallbackTest {

    @Test
    @DisplayName("getToolDefinition().name() returns the prefixed name; description and schema pass through")
    void nameOverriddenOthersPassThrough() {
        ToolCallback inner = new RecordingCallback("search", "Search the web", "{\"type\":\"object\"}");
        PrefixedNameToolCallback wrapped = new PrefixedNameToolCallback("mcp_42_search_aaaaaa", inner);

        ToolDefinition td = wrapped.getToolDefinition();
        assertEquals("mcp_42_search_aaaaaa", td.name());
        assertEquals("Search the web", td.description());
        assertEquals("{\"type\":\"object\"}", td.inputSchema());
    }

    @Test
    @DisplayName("call(toolInput) delegates to the inner callback unchanged")
    void callDelegates() {
        RecordingCallback inner = new RecordingCallback("search", "", "{}");
        PrefixedNameToolCallback wrapped = new PrefixedNameToolCallback("mcp_42_search_aaaaaa", inner);

        String result = wrapped.call("{\"q\":\"hello\"}");
        assertEquals("called:{\"q\":\"hello\"}", result);
        assertEquals("{\"q\":\"hello\"}", inner.lastInput);
    }

    @Test
    @DisplayName("call(toolInput, ToolContext) delegates to the inner callback unchanged")
    void callWithContextDelegates() {
        RecordingCallback inner = new RecordingCallback("search", "", "{}");
        PrefixedNameToolCallback wrapped = new PrefixedNameToolCallback("mcp_42_search_aaaaaa", inner);
        ToolContext ctx = new ToolContext(java.util.Map.of("k", "v"));

        String result = wrapped.call("{}", ctx);
        assertEquals("called-with-ctx:{}", result);
        assertSame(ctx, inner.lastContext);
    }

    @Test
    @DisplayName("getToolMetadata passes through the inner metadata")
    void metadataPassesThrough() {
        ToolMetadata meta = DefaultToolMetadata.builder().returnDirect(true).build();
        ToolCallback inner = new RecordingCallback("search", "", "{}", meta);
        PrefixedNameToolCallback wrapped = new PrefixedNameToolCallback("mcp_42_search_aaaaaa", inner);
        assertSame(meta, wrapped.getToolMetadata());
    }

    @Test
    @DisplayName("getDelegate exposes the wrapped callback for downstream introspection")
    void getDelegate() {
        ToolCallback inner = new RecordingCallback("search", "", "{}");
        PrefixedNameToolCallback wrapped = new PrefixedNameToolCallback("mcp_42_search_aaaaaa", inner);
        assertSame(inner, wrapped.getDelegate());
    }

    @Test
    @DisplayName("blank prefixed name or null delegate is rejected")
    void rejectsBadInputs() {
        ToolCallback inner = new RecordingCallback("x", "", "{}");
        assertThrows(IllegalArgumentException.class,
                () -> new PrefixedNameToolCallback(null, inner));
        assertThrows(IllegalArgumentException.class,
                () -> new PrefixedNameToolCallback("", inner));
        assertThrows(IllegalArgumentException.class,
                () -> new PrefixedNameToolCallback("mcp_x", null));
    }

    /** Simple ToolCallback fake to avoid pulling Mockito for these checks. */
    static final class RecordingCallback implements ToolCallback {
        private final ToolDefinition definition;
        private final ToolMetadata metadata;
        String lastInput;
        ToolContext lastContext;

        RecordingCallback(String name, String description, String inputSchema) {
            this(name, description, inputSchema, null);
        }

        RecordingCallback(String name, String description, String inputSchema, ToolMetadata metadata) {
            this.definition = DefaultToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build();
            this.metadata = metadata;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return metadata != null ? metadata : ToolCallback.super.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            this.lastInput = toolInput;
            return "called:" + toolInput;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            this.lastInput = toolInput;
            this.lastContext = toolContext;
            return "called-with-ctx:" + toolInput;
        }
    }
}
