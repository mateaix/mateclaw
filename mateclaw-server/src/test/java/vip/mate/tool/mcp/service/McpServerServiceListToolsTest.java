package vip.mate.tool.mcp.service;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.model.McpToolDescriptor;
import vip.mate.tool.mcp.repository.McpServerMapper;
import vip.mate.tool.mcp.runtime.McpClientManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link McpServerService#listToolsByServer(Long)},
 * the new endpoint that lets the admin UI see what tools an MCP server
 * has actually surfaced to the runtime.
 *
 * <p>Critical contracts under test:
 * <ul>
 *   <li>Existence check must happen first — a deleted server id must
 *       surface as a {@code MateClawException("err.mcp.not_found")} which
 *       the global handler maps to HTTP 200 + {@code code=500} (project's
 *       "HTTP 200 + biz code" convention; see McpServerController javadoc).
 *       The point is that "no tools" must not be confused with "no such server".</li>
 *   <li>An existing-but-empty cache returns {@code []}, not an error
 *       (server may be disconnected, in error state, or simply have no
 *       tools — UI should render "no tools yet" not an error toast).</li>
 *   <li>Field mapping from the SDK record to the DTO is verbatim — name,
 *       description, inputSchema all pass through.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class McpServerServiceListToolsTest {

    @Mock
    private McpServerMapper mcpServerMapper;

    @Mock
    private McpClientManager mcpClientManager;

    @InjectMocks
    private McpServerService service;

    private static McpServerEntity server(Long id) {
        McpServerEntity e = new McpServerEntity();
        e.setId(id);
        e.setName("test-server-" + id);
        return e;
    }

    @Test
    @DisplayName("missing server id throws MateClawException — distinguishes not-found from empty-tools")
    void missingServerThrows() {
        when(mcpServerMapper.selectById(99L)).thenReturn(null);

        assertThrows(MateClawException.class,
                () -> service.listToolsByServer(99L));

        // Don't even consult the cache for a non-existent server.
        verify(mcpClientManager, never()).getServerTools(99L);
    }

    @Test
    @DisplayName("empty tools cache returns [] — not an error")
    void emptyCacheReturnsEmptyList() {
        when(mcpServerMapper.selectById(7L)).thenReturn(server(7L));
        when(mcpClientManager.getServerTools(7L)).thenReturn(List.of());

        List<McpToolDescriptor> result = service.listToolsByServer(7L);

        assertTrue(result.isEmpty());
    }

    /** Tool record signature (mcp-core 1.1.0): name, title, description,
     *  inputSchema, outputSchema (Map), annotations, meta (Map). Tests pass
     *  null for the fields they don't exercise — the SDK accepts that. */
    private static McpSchema.Tool tool(String name, String description, McpSchema.JsonSchema inputSchema) {
        return new McpSchema.Tool(name, null, description, inputSchema, null, null, null);
    }

    /** Convenience for an "object" JSON schema with the given properties map. */
    private static McpSchema.JsonSchema objectSchema(Map<String, Object> properties) {
        return new McpSchema.JsonSchema("object", properties, null, null, null, null);
    }

    @Test
    @DisplayName("populated cache maps every Tool record verbatim into the DTO")
    void populatedCacheMappedVerbatim() {
        when(mcpServerMapper.selectById(7L)).thenReturn(server(7L));
        McpSchema.JsonSchema echoSchema = objectSchema(Map.of(
                "text", Map.of("type", "string")));
        McpSchema.JsonSchema sumSchema = objectSchema(Map.of(
                "a", Map.of("type", "number"),
                "b", Map.of("type", "number")));
        when(mcpClientManager.getServerTools(7L)).thenReturn(List.of(
                tool("echo", "Echoes the input back", echoSchema),
                tool("sum", "Adds two numbers", sumSchema)
        ));

        List<McpToolDescriptor> result = service.listToolsByServer(7L);

        assertEquals(2, result.size());
        assertEquals("echo", result.get(0).name());
        assertEquals("Echoes the input back", result.get(0).description());
        assertEquals(echoSchema, result.get(0).inputSchema());
        assertEquals("sum", result.get(1).name());
        assertEquals(sumSchema, result.get(1).inputSchema());
    }

    @Test
    @DisplayName("tools with null description still flow through the mapping")
    void nullDescriptionPreserved() {
        when(mcpServerMapper.selectById(7L)).thenReturn(server(7L));
        when(mcpClientManager.getServerTools(7L)).thenReturn(List.of(
                tool("ping", null, objectSchema(Map.of()))
        ));

        List<McpToolDescriptor> result = service.listToolsByServer(7L);

        assertEquals("ping", result.get(0).name());
        // null description survives; DTO @JsonInclude(NON_NULL) drops it from
        // the wire payload but the Java value is preserved through the mapping.
        assertTrue(result.get(0).description() == null);
    }
}
