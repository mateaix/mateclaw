package vip.mate.tool.mcp.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.auth.service.AuthService;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.repository.McpServerMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpWorkspaceAccessTest {
    @Test void workspaceScopedCallbacksAreNotAdvertisedToForeignOrMissingWorkspaces() {
        var mapper = mock(McpServerMapper.class);
        var access = new McpWorkspaceAccess(mapper, mock(AuthService.class));
        var server = new McpServerEntity(); server.setId(42L); server.setWorkspaceId(2L); server.setEnabled(true);
        when(mapper.selectById(42L)).thenReturn(server);
        ToolCallback callback = callback();
        var tools = AgentToolSet.fromCallbacks(List.of(), List.of(callback));
        assertEquals(1, access.scope(tools, 2L).callbacks().size());
        assertTrue(access.scope(tools, 1L).callbacks().isEmpty());
        assertTrue(access.scope(tools, null).callbacks().isEmpty());
        server.setWorkspaceId(null);
        assertEquals(1, access.scope(tools, null).callbacks().size());
    }

    @Test void callbackRechecksOwnershipAndEnabledStateAfterMigration() {
        var mapper = mock(McpServerMapper.class);
        var access = new McpWorkspaceAccess(mapper, mock(AuthService.class));
        var server = new McpServerEntity(); server.setId(42L); server.setWorkspaceId(2L); server.setEnabled(true);
        when(mapper.selectById(42L)).thenReturn(server);
        var delegate = callback();
        when(delegate.call(eq("{}"), any(ToolContext.class))).thenReturn("ok");
        var guarded = new WorkspaceScopedMcpToolCallback(delegate, access);
        var context = new ToolContext(Map.of(ChatOrigin.CTX_KEY, ChatOrigin.web("conv", "user", 2L, null)));
        assertEquals("ok", guarded.call("{}", context));
        server.setWorkspaceId(3L);
        assertThrows(RuntimeException.class, () -> guarded.call("{}", context));
        assertThrows(RuntimeException.class, () -> guarded.call("{}"));
        server.setWorkspaceId(null);
        assertEquals("ok", guarded.call("{}", context));
        server.setEnabled(false);
        assertThrows(RuntimeException.class, () -> guarded.call("{}", context));
        verify(delegate, times(2)).call("{}", context);
    }

    private ToolCallback callback() {
        var cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(McpToolNameResolver.prefixedName(42L, "search")).description("search").inputSchema("{}").build());
        return cb;
    }
}
