package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenEstimatorToolsTest {

    private ToolCallback callback(String name, String description, String inputSchema) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(def.description()).thenReturn(description);
        when(def.inputSchema()).thenReturn(inputSchema);
        when(cb.getToolDefinition()).thenReturn(def);
        return cb;
    }

    @Test
    @DisplayName("null / empty collection returns 0")
    void emptyZero() {
        assertEquals(0, TokenEstimator.estimateToolsTokens(null));
        assertEquals(0, TokenEstimator.estimateToolsTokens(List.of()));
    }

    @Test
    @DisplayName("single tool: name + description + schema + per-tool overhead all included")
    void singleTool() {
        ToolCallback cb = callback("web_search",
                "Search the web for recent information",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}");
        int tokens = TokenEstimator.estimateToolsTokens(List.of(cb));
        // > the per-tool overhead alone (proves description + schema were summed in)
        assertTrue(tokens > TokenEstimator.PER_TOOL_OVERHEAD,
                "Should include description and schema, got " + tokens);
        // sanity bound: this small tool shouldn't blow past 100 tokens
        assertTrue(tokens < 100, "Bound check, got " + tokens);
    }

    @Test
    @DisplayName("many tools accumulate — N tools cost ~N x single-tool cost")
    void manyToolsAccumulate() {
        ToolCallback cb = callback("read_file",
                "Read a file from the workspace",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}");
        int one = TokenEstimator.estimateToolsTokens(List.of(cb));
        int five = TokenEstimator.estimateToolsTokens(List.of(cb, cb, cb, cb, cb));
        assertEquals(one * 5, five, "Five identical tools should cost five times one");
    }

    @Test
    @DisplayName("MCP-sized tool with verbose schema costs hundreds of tokens — proves the gap is real")
    void mcpSizedTool() {
        // Realistic MCP tool: long description + nested schema with many properties
        String bigDescription = "Execute a SQL query against the connected PostgreSQL database. " +
                "Returns rows as a JSON array. Supports SELECT, INSERT, UPDATE, DELETE statements. " +
                "Bound parameters must be passed as a separate array; do not concatenate user input.";
        String bigSchema = "{\"type\":\"object\",\"properties\":{" +
                "\"sql\":{\"type\":\"string\",\"description\":\"The SQL statement to execute\"}," +
                "\"params\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Bound parameters\"}," +
                "\"timeout_ms\":{\"type\":\"integer\",\"description\":\"Statement timeout in ms\",\"minimum\":0,\"maximum\":60000}," +
                "\"read_only\":{\"type\":\"boolean\",\"description\":\"Reject statements that modify data\"}" +
                "},\"required\":[\"sql\"]}";
        ToolCallback cb = callback("postgres_query", bigDescription, bigSchema);

        int tokens = TokenEstimator.estimateToolsTokens(List.of(cb));
        assertTrue(tokens > 100,
                "A real MCP tool's schema cost should clearly exceed 100 tokens, got " + tokens);
    }

    @Test
    @DisplayName("callbacks that throw on getToolDefinition() are skipped, not propagated")
    void brokenCallbackSwallowed() {
        ToolCallback bad = mock(ToolCallback.class);
        when(bad.getToolDefinition()).thenThrow(new RuntimeException("provider error"));
        ToolCallback good = callback("ok", "ok", "{}");

        int tokens = TokenEstimator.estimateToolsTokens(List.of(bad, good));
        // good tool still contributes; bad one contributes 0
        assertTrue(tokens > 0,
                "Broken callback should be skipped, good one should still count, got " + tokens);
    }
}
