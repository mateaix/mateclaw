package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * LLMs frequently mangle tool names: emit {@code WebSearch} or
 * {@code web_search_tool} when the registry knows {@code web_search}, or
 * {@code Read_File} when the registry knows {@code read_file}. Without
 * normalization those calls return "Tool not found" and the agent loses a
 * turn — and worse, the guard's deny rules (keyed on canonical names) get
 * silently bypassed because the guard never sees a matching name.
 */
class ToolExecutionExecutorNameNormalizationTest {

    private ToolCallback callbackNamed(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(def.description()).thenReturn(name);
        when(def.inputSchema()).thenReturn("{}");
        when(cb.getToolDefinition()).thenReturn(def);
        when(cb.call(anyString(), any())).thenReturn("ok:" + name);
        when(cb.call(anyString())).thenReturn("ok:" + name);
        return cb;
    }

    private ToolExecutionExecutor newExecutor(ToolCallback... callbacks) {
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(callbacks));
        ToolGuard alwaysAllow = (n, a) -> ToolGuardResult.allow();
        return new ToolExecutionExecutor(toolSet, alwaysAllow, null, null);
    }

    @Test
    @DisplayName("normalizeToolName: CamelCase → snake_case")
    void normalize_camelCase() {
        assertEquals("web_search", ToolExecutionExecutor.normalizeToolName("WebSearch"));
        assertEquals("web_search", ToolExecutionExecutor.normalizeToolName("webSearch"));
        assertEquals("read_file", ToolExecutionExecutor.normalizeToolName("ReadFile"));
        assertEquals("browser_use", ToolExecutionExecutor.normalizeToolName("BrowserUse"));
    }

    @Test
    @DisplayName("normalizeToolName: trailing _tool / Tool / _function suffix stripped")
    void normalize_suffixStrip() {
        assertEquals("web_search", ToolExecutionExecutor.normalizeToolName("web_search_tool"));
        assertEquals("web_search", ToolExecutionExecutor.normalizeToolName("WebSearchTool"));
        assertEquals("read_file", ToolExecutionExecutor.normalizeToolName("read_file_function"));
    }

    @Test
    @DisplayName("normalizeToolName: separator collapse + lowercase")
    void normalize_separators() {
        assertEquals("read_file", ToolExecutionExecutor.normalizeToolName("Read_File"));
        assertEquals("read_file", ToolExecutionExecutor.normalizeToolName("read-file"));
        assertEquals("read_file", ToolExecutionExecutor.normalizeToolName("read.file"));
        assertEquals("read_file", ToolExecutionExecutor.normalizeToolName("read   file"));
        assertEquals("read_file", ToolExecutionExecutor.normalizeToolName("__read__file__"));
    }

    @Test
    @DisplayName("normalizeToolName: idempotent on already-canonical names")
    void normalize_idempotent() {
        assertEquals("web_search", ToolExecutionExecutor.normalizeToolName("web_search"));
        assertEquals("read_file", ToolExecutionExecutor.normalizeToolName("read_file"));
    }

    @Test
    @DisplayName("normalizeToolName: handles null/empty")
    void normalize_edgeCases() {
        assertEquals("", ToolExecutionExecutor.normalizeToolName(null));
        assertEquals("", ToolExecutionExecutor.normalizeToolName(""));
        assertEquals("", ToolExecutionExecutor.normalizeToolName("   "));
    }

    @Test
    @DisplayName("resolveToolName: exact match returns input unchanged (hot path)")
    void resolve_exactMatchUnchanged() {
        ToolExecutionExecutor executor = newExecutor(callbackNamed("web_search"));
        assertEquals("web_search", executor.resolveToolName("web_search"));
    }

    @Test
    @DisplayName("resolveToolName: CamelCase emission resolves to snake_case canonical")
    void resolve_camelToSnake() {
        ToolExecutionExecutor executor = newExecutor(callbackNamed("web_search"));
        assertEquals("web_search", executor.resolveToolName("WebSearch"));
        assertEquals("web_search", executor.resolveToolName("webSearch"));
    }

    @Test
    @DisplayName("resolveToolName: _tool / Tool suffix resolves to canonical")
    void resolve_suffixStripped() {
        ToolExecutionExecutor executor = newExecutor(callbackNamed("web_search"));
        assertEquals("web_search", executor.resolveToolName("web_search_tool"));
        assertEquals("web_search", executor.resolveToolName("WebSearchTool"));
    }

    @Test
    @DisplayName("resolveToolName: unknown name returns input unchanged so 'tool not found' fires correctly")
    void resolve_unknownReturnsInput() {
        ToolExecutionExecutor executor = newExecutor(callbackNamed("web_search"));
        assertEquals("totally_made_up", executor.resolveToolName("totally_made_up"));
    }

    @Test
    @DisplayName("end-to-end: model emits 'WebSearch', registered as 'web_search', tool actually executes")
    void endToEnd_camelCaseDispatch() {
        ToolExecutionExecutor executor = newExecutor(callbackNamed("web_search"));

        var result = executor.execute(
                List.of(new AssistantMessage.ToolCall("call_1", "function", "WebSearch", "{}")),
                "conv", "agent", false, "user", null);

        assertEquals(1, result.responses().size());
        assertEquals("ok:web_search", result.responses().get(0).responseData(),
                "Mangled name should resolve and dispatch to the registered tool");
    }

    @Test
    @DisplayName("end-to-end: '_tool' suffix is stripped and the call dispatches")
    void endToEnd_toolSuffixStripped() {
        ToolExecutionExecutor executor = newExecutor(callbackNamed("read_file"));

        var result = executor.execute(
                List.of(new AssistantMessage.ToolCall("call_2", "function", "read_file_tool", "{}")),
                "conv", "agent", false, "user", null);

        assertEquals("ok:read_file", result.responses().get(0).responseData());
    }
}
