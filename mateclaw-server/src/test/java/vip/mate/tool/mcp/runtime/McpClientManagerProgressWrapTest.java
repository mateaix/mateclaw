package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Black-box regression suite verifying that the new
 * {@link McpClientManager#wrapServerCallbacks(long, ToolCallback[], McpIdentityForwardService,
 * String, String, McpSyncClient, ObjectMapper)} overload:
 * <ol>
 *   <li>does <b>not</b> break the existing null-mcpClient path</li>
 *   <li>wraps with {@link ProgressAwareMcpToolCallback} when mcpClient is provided</li>
 * </ol>
 */
class McpClientManagerProgressWrapTest {

    private static ToolCallback stub(String name) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name(name).description("").inputSchema("{}").build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public ToolMetadata getToolMetadata() { return ToolCallback.super.getToolMetadata(); }
            @Override public String call(String toolInput) { return name + ":" + toolInput; }
            @Override public String call(String toolInput, ToolContext toolContext) { return call(toolInput); }
        };
    }

    // ── Backward-compat: null McpSyncClient (same as before) ──

    @Test
    @DisplayName("null McpSyncClient → no ProgressAwareMcpToolCallback wrapping")
    void nullMcpClientNoProgressWrap() {
        ToolCallback cb = stub("search");
        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(99L,
                new ToolCallback[]{cb}, null, null, null, null, null);

        assertEquals(1, wrapped.size());
        assertInstanceOf(PrefixedNameToolCallback.class, wrapped.get(0));
        PrefixedNameToolCallback p = (PrefixedNameToolCallback) wrapped.get(0);
        // Inner should be the original stub, NOT a ProgressAwareMcpToolCallback
        assertFalse(p.getDelegate() instanceof ProgressAwareMcpToolCallback,
                "should NOT wrap when mcpClient is null");
    }

    // ── With McpSyncClient → wraps ──

    @Test
    @DisplayName("non-null McpSyncClient wraps with ProgressAwareMcpToolCallback")
    void withMcpClientWrapsProgress() {
        McpSyncClient client = mock(McpSyncClient.class);
        ToolCallback cb = stub("long_task");
        ObjectMapper mapper = new ObjectMapper();

        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(88L,
                new ToolCallback[]{cb}, null, null, null, client, mapper);

        assertEquals(1, wrapped.size());
        assertInstanceOf(PrefixedNameToolCallback.class, wrapped.get(0));
        PrefixedNameToolCallback p = (PrefixedNameToolCallback) wrapped.get(0);
        assertInstanceOf(ProgressAwareMcpToolCallback.class, p.getDelegate(),
                "should wrap with ProgressAwareMcpToolCallback when mcpClient is provided");
        ProgressAwareMcpToolCallback prog = (ProgressAwareMcpToolCallback) p.getDelegate();
        assertEquals(cb, prog.getDelegate(), "original callback preserved as delegate");
    }

    @Test
    @DisplayName("ProgressAwareMcpToolCallback sits outside IdentityForward but inside PrefixedName (correct chain)")
    void chainOrder() {
        McpSyncClient client = mock(McpSyncClient.class);
        ToolCallback cb = stub("private_data");
        ObjectMapper mapper = new ObjectMapper();

        // identitySvc=null → no identity wrapping
        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(77L,
                new ToolCallback[]{cb}, null, null, "my-server", client, mapper);

        assertEquals(1, wrapped.size());
        assertInstanceOf(PrefixedNameToolCallback.class, wrapped.get(0));
        PrefixedNameToolCallback p = (PrefixedNameToolCallback) wrapped.get(0);
        assertInstanceOf(ProgressAwareMcpToolCallback.class, p.getDelegate());

        ProgressAwareMcpToolCallback prog = (ProgressAwareMcpToolCallback) p.getDelegate();
        // IdentityForwarding is NOT wrapped because identitySvc=null; the raw stub IS the delegate
        assertSame(cb, prog.getDelegate());
    }

    @Test
    @DisplayName("existing two-arg wrapServerCallbacks overload still compiles and works")
    void twoArgOverloadStillWorks() {
        ToolCallback cb = stub("read_file");
        // This is the original API used by McpClientManagerWrapTest — must still work
        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(66L,
                new ToolCallback[]{cb});

        assertEquals(1, wrapped.size());
        assertInstanceOf(PrefixedNameToolCallback.class, wrapped.get(0));
    }
}
