package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.context.ChatOrigin;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    // ── Progress path end-to-end: identity MUST reach the CallToolRequest ──
    // Regression for the security-critical branch where ProgressAwareMcpToolCallback
    // calls mcpClient.callTool directly (bypassing IdentityForwardingToolCallback.call).
    // If the chain shape ever changes so identity injection is skipped here, every
    // long-running MCP tool call on an opt-in server would lose on-behalf-of identity.

    @Test
    @DisplayName("progress path injects __mateclaw_user__ into the outgoing CallToolRequest")
    void progressPathInjectsPlaintextIdentity() throws Exception {
        McpSyncClient client = mock(McpSyncClient.class);
        // A minimal non-null CallToolResult so the wrapper's serializeResult works.
        org.mockito.Mockito.when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(List.of(), false));
        ToolCallback cb = stub("long_task");
        ObjectMapper mapper = new ObjectMapper();

        // Real identity service in plaintext mode, opted in for this server.
        McpIdentityForwardProperties props = new McpIdentityForwardProperties();
        props.setServers(java.util.Set.of("my-server"));
        McpIdentityForwardService idSvc = new McpIdentityForwardService(props);

        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(77L,
                new ToolCallback[]{cb}, idSvc, "my-server", "my-server", client, mapper);

        assertEquals(1, wrapped.size());
        // Authenticated web user → the request must carry __mateclaw_user__ = "authenticated:42".
        ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);
        ToolContext toolCtx = new ToolContext(Map.of(
                ChatOrigin.CTX_KEY, origin,
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok-1"));

        wrapped.get(0).call("{\"q\":\"hi\"}", toolCtx);

        // Capture the CallToolRequest handed to the client and assert the identity arg.
        org.mockito.ArgumentCaptor<McpSchema.CallToolRequest> captor =
                org.mockito.ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(captor.capture());
        McpSchema.CallToolRequest req = captor.getValue();
        Object injected = req.arguments() != null ? req.arguments().get(McpIdentityForwardProperties.USER_ARG) : null;
        assertNotNull(injected, "progress path must inject __mateclaw_user__");
        assertEquals("authenticated:42", injected);
        // And the LLM-supplied args survive (only the reserved key is added).
        assertEquals("hi", req.arguments().get("q"));
        // The progress token is also present.
        assertEquals("tok-1", req.meta().get("progressToken"));
    }

    @Test
    @DisplayName("progress path injects __mateclaw_token__ in token mode")
    void progressPathInjectsTokenIdentity() throws Exception {
        java.security.KeyPair kp;
        try {
            kp = java.security.KeyPairGenerator.getInstance("RSA").genKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        McpSyncClient client = mock(McpSyncClient.class);
        org.mockito.Mockito.when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(List.of(), false));
        ToolCallback cb = stub("long_task");
        ObjectMapper mapper = new ObjectMapper();

        McpIdentityForwardProperties props = new McpIdentityForwardProperties();
        props.setServers(java.util.Set.of("my-server"));
        props.getToken().setEnabled(true);
        props.getToken().setIssuer("mateclaw");
        props.getToken().setTtlSeconds(60);
        props.getToken().setPrivateKeyPem(
                java.util.Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        McpIdentityForwardService idSvc = new McpIdentityForwardService(props);

        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(77L,
                new ToolCallback[]{cb}, idSvc, "my-server", "my-server", client, mapper);

        ChatOrigin origin = ChatOrigin.web("c1", "alice", 1L, null, null, 42L);
        ToolContext toolCtx = new ToolContext(Map.of(
                ChatOrigin.CTX_KEY, origin,
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok-2"));

        wrapped.get(0).call("{\"q\":\"hi\"}", toolCtx);

        org.mockito.ArgumentCaptor<McpSchema.CallToolRequest> captor =
                org.mockito.ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(captor.capture());
        Object token = captor.getValue().arguments().get(McpIdentityForwardProperties.TOKEN_ARG);
        assertNotNull(token, "progress path must inject __mateclaw_token__ in token mode");
        // Token verifies with the public key and carries the typed claims.
        io.jsonwebtoken.Claims claims = io.jsonwebtoken.Jwts.parser()
                .verifyWith(kp.getPublic()).requireAudience("my-server").build()
                .parseSignedClaims((String) token).getPayload();
        assertEquals("42", claims.getSubject());
        assertEquals(McpIdentityForwardService.TRUST_AUTHENTICATED, claims.get("trust", String.class));
    }

    @Test
    @DisplayName("progress path with no identity (cron) injects nothing, still calls with progress token")
    void progressPathNoIdentityInjectsNothing() throws Exception {
        McpSyncClient client = mock(McpSyncClient.class);
        org.mockito.Mockito.when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(List.of(), false));
        ToolCallback cb = stub("long_task");
        ObjectMapper mapper = new ObjectMapper();

        McpIdentityForwardProperties props = new McpIdentityForwardProperties();
        props.setServers(java.util.Set.of("my-server"));
        McpIdentityForwardService idSvc = new McpIdentityForwardService(props);

        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(77L,
                new ToolCallback[]{cb}, idSvc, "my-server", "my-server", client, mapper);

        // Cron origin → no identity; progress token still rides along.
        ChatOrigin origin = ChatOrigin.cron("c1", 1L, null, 9L, null);
        ToolContext toolCtx = new ToolContext(Map.of(
                ChatOrigin.CTX_KEY, origin,
                ProgressAwareMcpToolCallback.MCP_PROGRESS_TOKEN_KEY, "tok-3"));

        wrapped.get(0).call("{\"q\":\"hi\"}", toolCtx);

        org.mockito.ArgumentCaptor<McpSchema.CallToolRequest> captor =
                org.mockito.ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(captor.capture());
        McpSchema.CallToolRequest req = captor.getValue();
        assertFalse(req.arguments().containsKey(McpIdentityForwardProperties.USER_ARG),
                "no identity arg when origin is cron");
        assertEquals("tok-3", req.meta().get("progressToken"));
    }
}
