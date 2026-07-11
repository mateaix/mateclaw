package vip.mate.tool.mcp.runtime;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.tool.mcp.event.McpConnectionLostEvent;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for issue #317: a stale {@code listTools()} (the upstream MCP
 * server restarted while we held the connection) must NOT drop the whole server
 * — that is what made the agent fall back to non-MCP tools. Instead the manager
 * serves the last known-good callbacks and asks the service layer to reconnect.
 */
class McpClientManagerSnapshotTest {

    @Test
    @DisplayName("stale listTools serves cached snapshot and requests a reconnect")
    @SuppressWarnings("unchecked")
    void staleListToolsServesSnapshotAndRequestsReconnect() throws Exception {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        McpClientManager manager = new McpClientManager(publisher,
                new McpIdentityForwardService(new McpIdentityForwardProperties()),
                null, null);

        // A client whose connection went stale: every listTools() throws.
        McpSyncClient deadClient = mock(McpSyncClient.class);
        when(deadClient.listTools()).thenThrow(new RuntimeException("session not found"));

        long serverId = 77L;
        List<ToolCallback> snapshot = List.of(new PrefixedNameToolCallback("mcp_77_search_abc123", stub("search")));

        // Pre-seed the private state as if a previous successful collection ran.
        ((Map<Long, McpSyncClient>) field(manager, "clients")).put(serverId, deadClient);
        ((Map<Long, List<ToolCallback>>) field(manager, "lastGoodCallbacks")).put(serverId, snapshot);

        List<ToolCallback> result = manager.getAllToolCallbacks();

        // The cached snapshot is served verbatim — the server is NOT dropped.
        assertEquals(1, result.size());
        assertSame(snapshot.get(0), result.get(0));

        // A reconnect was requested for exactly this server.
        verify(publisher, times(1)).publishEvent(any(McpConnectionLostEvent.class));
    }

    private static Object field(McpClientManager manager, String name) throws Exception {
        Field f = McpClientManager.class.getDeclaredField(name);
        f.setAccessible(true);
        Object value = f.get(manager);
        if (value == null) {
            ConcurrentHashMap<Object, Object> created = new ConcurrentHashMap<>();
            f.set(manager, created);
            return created;
        }
        return value;
    }

    private static ToolCallback stub(String rawName) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(rawName).description("").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                return "";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return "";
            }
        };
    }
}
