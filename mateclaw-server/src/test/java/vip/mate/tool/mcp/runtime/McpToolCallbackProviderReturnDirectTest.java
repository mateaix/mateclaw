package vip.mate.tool.mcp.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the cross-form returnDirect match — without this, an existing
 * deployment with raw tool names in its returnDirect config would silently
 * lose the direct-return wrapping after Lane 0 starts handing back
 * prefix-wrapped callbacks. That regression would let sensitive payloads
 * (HR / medical / etc.) flow back through the LLM context, so the test is
 * load-bearing for the upgrade.
 */
class McpToolCallbackProviderReturnDirectTest {

    private McpClientManager clientManager;

    @BeforeEach
    void setUp() {
        clientManager = mock(McpClientManager.class);
    }

    @Test
    @DisplayName("legacy config (raw name): wrapped callback is treated as returnDirect")
    void rawNameInConfigStillMatches() {
        // Existing application.yml from before the prefix change:
        //   mateclaw.mcp.return-direct.tools: [query_employee_salary]
        McpReturnDirectProperties props = newProps("query_employee_salary");

        ToolCallback raw = stubCallback("query_employee_salary");
        ToolCallback prefixedWrap = new PrefixedNameToolCallback(
                McpToolNameResolver.prefixedName(42L, "query_employee_salary"), raw);
        when(clientManager.getAllToolCallbacks()).thenReturn(List.of(prefixedWrap));
        when(clientManager.getActiveCount()).thenReturn(1);

        McpToolCallbackProvider provider = new McpToolCallbackProvider(clientManager, props);
        ToolCallback[] out = provider.getToolCallbacks();

        assertEquals(1, out.length);
        assertTrue(out[0] instanceof ReturnDirectMcpToolCallback,
                "expected legacy raw-name match to wrap as ReturnDirectMcpToolCallback, got " + out[0].getClass());
    }

    @Test
    @DisplayName("new config (prefixed name): wrapped callback is treated as returnDirect")
    void prefixedNameInConfigMatches() {
        String prefixed = McpToolNameResolver.prefixedName(42L, "query_employee_salary");
        McpReturnDirectProperties props = newProps(prefixed);

        ToolCallback raw = stubCallback("query_employee_salary");
        ToolCallback prefixedWrap = new PrefixedNameToolCallback(prefixed, raw);
        when(clientManager.getAllToolCallbacks()).thenReturn(List.of(prefixedWrap));

        McpToolCallbackProvider provider = new McpToolCallbackProvider(clientManager, props);
        ToolCallback[] out = provider.getToolCallbacks();

        assertEquals(1, out.length);
        assertTrue(out[0] instanceof ReturnDirectMcpToolCallback);
    }

    @Test
    @DisplayName("non-matching name: callback is passed through, NOT wrapped")
    void nonMatchingNameLeftAlone() {
        McpReturnDirectProperties props = newProps("something_else");

        ToolCallback raw = stubCallback("query_employee_salary");
        ToolCallback prefixedWrap = new PrefixedNameToolCallback(
                McpToolNameResolver.prefixedName(42L, "query_employee_salary"), raw);
        when(clientManager.getAllToolCallbacks()).thenReturn(List.of(prefixedWrap));

        McpToolCallbackProvider provider = new McpToolCallbackProvider(clientManager, props);
        ToolCallback[] out = provider.getToolCallbacks();

        assertEquals(1, out.length);
        assertFalse(out[0] instanceof ReturnDirectMcpToolCallback);
        assertEquals(prefixedWrap, out[0]);
    }

    @Test
    @DisplayName("two servers expose the same raw name; raw config matches BOTH")
    void rawNameInConfigMatchesAcrossServers() {
        // Documented behavior of the legacy form: a raw token isolates
        // every server that exposes that tool name. This is intentional —
        // operators wanting per-server scoping switch to the prefixed form.
        McpReturnDirectProperties props = newProps("read_medical_record");

        ToolCallback rawA = stubCallback("read_medical_record");
        ToolCallback wrapA = new PrefixedNameToolCallback(
                McpToolNameResolver.prefixedName(42L, "read_medical_record"), rawA);
        ToolCallback rawB = stubCallback("read_medical_record");
        ToolCallback wrapB = new PrefixedNameToolCallback(
                McpToolNameResolver.prefixedName(43L, "read_medical_record"), rawB);
        when(clientManager.getAllToolCallbacks()).thenReturn(List.of(wrapA, wrapB));

        ToolCallback[] out = new McpToolCallbackProvider(clientManager, props).getToolCallbacks();

        assertEquals(2, out.length);
        assertTrue(out[0] instanceof ReturnDirectMcpToolCallback);
        assertTrue(out[1] instanceof ReturnDirectMcpToolCallback);
    }

    @Test
    @DisplayName("prefixed config of one server: only THAT server's callback wraps")
    void prefixedNameOnlyMatchesScopedServer() {
        String prefixedA = McpToolNameResolver.prefixedName(42L, "read_medical_record");
        McpReturnDirectProperties props = newProps(prefixedA);

        ToolCallback wrapA = new PrefixedNameToolCallback(prefixedA, stubCallback("read_medical_record"));
        ToolCallback wrapB = new PrefixedNameToolCallback(
                McpToolNameResolver.prefixedName(43L, "read_medical_record"),
                stubCallback("read_medical_record"));
        when(clientManager.getAllToolCallbacks()).thenReturn(List.of(wrapA, wrapB));

        ToolCallback[] out = new McpToolCallbackProvider(clientManager, props).getToolCallbacks();

        assertEquals(2, out.length);
        assertTrue(out[0] instanceof ReturnDirectMcpToolCallback,
                "scoped prefix should match server 42's callback");
        assertFalse(out[1] instanceof ReturnDirectMcpToolCallback,
                "scoped prefix should NOT match server 43's callback");
    }

    @Test
    @DisplayName("non-wrapped callback (no PrefixedNameToolCallback): match falls back to its single name")
    void nonWrappedCallbackWithMatchingName() {
        // Defensive: a callback might still flow through that isn't our
        // wrapper (e.g. a unit-test path). The match must work on the
        // callback's reported name without trying to extract a 'raw' that
        // doesn't exist.
        McpReturnDirectProperties props = newProps("plain_name");

        ToolCallback plain = stubCallback("plain_name");
        when(clientManager.getAllToolCallbacks()).thenReturn(List.of(plain));

        ToolCallback[] out = new McpToolCallbackProvider(clientManager, props).getToolCallbacks();
        assertEquals(1, out.length);
        assertTrue(out[0] instanceof ReturnDirectMcpToolCallback);
    }

    private static McpReturnDirectProperties newProps(String... toolNames) {
        McpReturnDirectProperties p = new McpReturnDirectProperties();
        p.setTools(Set.of(toolNames));
        return p;
    }

    private static ToolCallback stubCallback(String name) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name(name)
                .description("")
                .inputSchema("{}")
                .build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public ToolMetadata getToolMetadata() { return ToolCallback.super.getToolMetadata(); }
            @Override public String call(String toolInput) { return ""; }
            @Override public String call(String toolInput, ToolContext ctx) { return ""; }
        };
    }
}
