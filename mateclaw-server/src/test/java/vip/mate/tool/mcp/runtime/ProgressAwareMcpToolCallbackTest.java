package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.mockito.ArgumentCaptor;
import vip.mate.agent.context.ChatOrigin;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * White-box tests for {@link ProgressAwareMcpToolCallback}.
 *
 * <p>Covers the two code paths:
 * <ol>
 *   <li>progressToken present in ToolContext → direct McpSyncClient.callTool() with injected meta</li>
 *   <li>progressToken absent → delegates to inner callback (backward-compatible)</li>
 * </ol>
 */
class ProgressAwareMcpToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallback delegate;
    private McpSyncClient mcpClient;
    private ProgressAwareMcpToolCallback wrapper;

    @BeforeEach
    void setUp() {
        delegate = mock(ToolCallback.class);
        mcpClient = mock(McpSyncClient.class);
        when(delegate.getToolDefinition()).thenReturn(
                DefaultToolDefinition.builder().name("search").description("desc").inputSchema("{}").build());
        when(delegate.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        wrapper = new ProgressAwareMcpToolCallback(delegate, mcpClient, "search", MAPPER);
    }

    @Test
    @DisplayName("getToolDefinition delegates to inner callback")
    void delegatesGetToolDefinition() {
        assertEquals("search", wrapper.getToolDefinition().name());
        verify(delegate).getToolDefinition();
    }

    @Test
    @DisplayName("getToolMetadata delegates to inner callback")
    void delegatesGetToolMetadata() {
        assertNotNull(wrapper.getToolMetadata());
        verify(delegate).getToolMetadata();
    }

    @Test
    @DisplayName("call(toolInput) without ToolContext delegates to inner")
    void callWithoutToolContextDelegates() {
        when(delegate.call("{}")).thenReturn("result");
        assertEquals("result", wrapper.call("{}"));
        verify(delegate).call("{}");
        verifyNoInteractions(mcpClient);
    }

    @Test
    @DisplayName("call with ToolContext but without progressToken delegates to inner")
    void callWithoutProgressTokenDelegates() {
        ToolContext ctx = new ToolContext(Map.of());
        when(delegate.call("{}", ctx)).thenReturn("delegated");
        assertEquals("delegated", wrapper.call("{}", ctx));
        verify(delegate).call("{}", ctx);
        verifyNoInteractions(mcpClient);
    }

    @Test
    @DisplayName("call with null ToolContext delegates to inner")
    void callWithNullToolContextDelegates() {
        when(delegate.call("{}", null)).thenReturn("null_ctx");
        assertEquals("null_ctx", wrapper.call("{}", (ToolContext) null));
        verify(delegate).call("{}", (ToolContext) null);
        verifyNoInteractions(mcpClient);
    }

    @Test
    @DisplayName("call with progressToken in ToolContext calls McpSyncClient directly with meta injected")
    void callWithProgressTokenUsesMcpClient() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "pt-uuid-123"));
        McpSchema.TextContent textContent = new McpSchema.TextContent("mcp result");
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(List.of(textContent), false);
        when(mcpClient.callTool(any())).thenReturn(result);

        String output = wrapper.call("{\"q\":\"hello\"}", ctx);

        assertEquals("mcp result", output);
        verify(mcpClient).callTool(any(McpSchema.CallToolRequest.class));
        verify(delegate, never()).call(any(), any());
    }

    @Test
    @DisplayName("progressToken present but blank → delegates (edge case)")
    void blankProgressTokenDelegates() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "   "));
        when(delegate.call("{}", ctx)).thenReturn("fallback");
        assertEquals("fallback", wrapper.call("{}", ctx));
        verify(delegate).call("{}", ctx);
        verifyNoInteractions(mcpClient);
    }

    @Test
    @DisplayName("McpSyncClient throws → falls back to delegate")
    void mcpClientThrowsFallsBackToDelegate() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok"));
        when(mcpClient.callTool(any())).thenThrow(new RuntimeException("connection lost"));
        when(delegate.call(eq("{}"), any(ToolContext.class))).thenReturn("fallback result");

        String output = wrapper.call("{}", ctx);

        assertEquals("fallback result", output);
        verify(mcpClient).callTool(any());
        verify(delegate).call(eq("{}"), any());
    }

    @Test
    @DisplayName("callTool succeeds with multi-text content concatenated")
    void multiTextContentConcatenated() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok"));
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(List.of(
                new McpSchema.TextContent("part1"),
                new McpSchema.TextContent("part2")), false);
        when(mcpClient.callTool(any())).thenReturn(result);

        assertEquals("part1part2", wrapper.call("{}", ctx));
    }

    @Test
    @DisplayName("getDelegate returns inner callback (for ReturnDirect / IdentityForward detection)")
    void getDelegateReturnsInner() {
        assertSame(delegate, wrapper.getDelegate());
    }

    @Test
    @DisplayName("parseArguments handles null input")
    void parseArgumentsHandlesNull() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok"));
        when(mcpClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("ok")), false));
        assertEquals("ok", wrapper.call(null, ctx));
    }

    @Test
    @DisplayName("parseArguments handles blank input")
    void parseArgumentsHandlesBlank() {
        ToolContext ctx = new ToolContext(Map.of(
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok"));
        when(mcpClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("ok")), false));
        assertEquals("ok", wrapper.call("   ", ctx));
    }

    // ==================== _meta identity injection (#459 follow-up) ====================

    /**
     * Build a wrapper whose delegate is an IdentityForwardingToolCallback wrapping
     * a raw callback — mirroring McpClientManager.wrapServerCallbacks ordering.
     */
    private ProgressAwareMcpToolCallback wrapperWithIdentity(
            McpIdentityForwardService identitySvc, String audience) {
        IdentityForwardingToolCallback idFwd = new IdentityForwardingToolCallback(
                delegate, identitySvc, audience);
        return new ProgressAwareMcpToolCallback(idFwd, mcpClient, "search", MAPPER);
    }

    @Test
    @DisplayName("identity-forward armed: direct callTool with mateclaw_user in _meta (no progress token needed)")
    void identityForwardUsesCallToolWithMeta() {
        McpIdentityForwardService svc = mock(McpIdentityForwardService.class);
        when(svc.resolveMeta(any(), any()))
                .thenReturn(Map.of(McpIdentityForwardProperties.META_USER_KEY, "authenticated:42"));

        ProgressAwareMcpToolCallback idWrapper = wrapperWithIdentity(svc, "my-api");
        ToolContext ctx = new ToolContext(Map.of(
                ChatOrigin.CTX_KEY, ChatOrigin.web("c1", "alice", 1L, null, null, 42L)));

        when(mcpClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("ok")), false));

        String result = idWrapper.call("{\"q\":\"hi\"}", ctx);

        assertEquals("ok", result);
        ArgumentCaptor<McpSchema.CallToolRequest> captor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(mcpClient).callTool(captor.capture());
        // _meta must carry the identity key
        assertEquals("authenticated:42",
                captor.getValue().meta().get(McpIdentityForwardProperties.META_USER_KEY));
        // Arguments must NOT carry the old __mateclaw_user__ key (identity is in _meta now)
        assertNull(captor.getValue().arguments().get(McpIdentityForwardProperties.META_USER_KEY));
        verify(delegate, never()).call(any(), any());
    }

    @Test
    @DisplayName("identity + progress token: _meta merges both keys")
    void identityAndProgressMergeInMeta() {
        McpIdentityForwardService svc = mock(McpIdentityForwardService.class);
        when(svc.resolveMeta(any(), any()))
                .thenReturn(Map.of(McpIdentityForwardProperties.META_TOKEN_KEY, "eyJhbG..."));

        ProgressAwareMcpToolCallback idWrapper = wrapperWithIdentity(svc, "my-api");
        ToolContext ctx = new ToolContext(Map.of(
                ChatOrigin.CTX_KEY, ChatOrigin.web("c1", "alice", 1L, null, null, 42L),
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "pt-1"));

        when(mcpClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("ok")), false));

        idWrapper.call("{\"q\":\"hi\"}", ctx);

        ArgumentCaptor<McpSchema.CallToolRequest> captor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(mcpClient).callTool(captor.capture());
        Map<String, Object> meta = captor.getValue().meta();
        assertEquals("pt-1", meta.get("progressToken"));
        assertEquals("eyJhbG...", meta.get(McpIdentityForwardProperties.META_TOKEN_KEY));
    }

    @Test
    @DisplayName("identity delegate present but no identity (cron): delegates (no direct callTool)")
    void identityDelegateButNoIdentityDelegates() {
        McpIdentityForwardService svc = mock(McpIdentityForwardService.class);
        when(svc.resolveMeta(any(), any())).thenReturn(Map.of());

        ProgressAwareMcpToolCallback idWrapper = wrapperWithIdentity(svc, "my-api");
        ToolContext ctx = new ToolContext(Map.of());

        when(delegate.call("{}", ctx)).thenReturn("delegated");
        assertEquals("delegated", idWrapper.call("{}", ctx));
        verify(delegate).call("{}", ctx);
        verifyNoInteractions(mcpClient);
    }

    @Test
    @DisplayName("identity forward + callTool throws → falls back to delegate (identity dropped, logged)")
    void identityForwardFallsBackOnException() {
        McpIdentityForwardService svc = mock(McpIdentityForwardService.class);
        when(svc.resolveMeta(any(), any()))
                .thenReturn(Map.of(McpIdentityForwardProperties.META_USER_KEY, "authenticated:42"));

        ProgressAwareMcpToolCallback idWrapper = wrapperWithIdentity(svc, "my-api");
        ToolContext ctx = new ToolContext(Map.of(
                ChatOrigin.CTX_KEY, ChatOrigin.web("c1", "alice", 1L, null, null, 42L)));

        when(mcpClient.callTool(any())).thenThrow(new RuntimeException("conn lost"));
        when(delegate.call(eq("{}"), any(ToolContext.class))).thenReturn("fallback");

        assertEquals("fallback", idWrapper.call("{}", ctx));
        verify(mcpClient).callTool(any());
        verify(delegate).call(eq("{}"), any(ToolContext.class));
    }
}
