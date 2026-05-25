package vip.mate.tool.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.runtime.McpToolNameResolver;
import vip.mate.tool.mcp.service.McpServerService;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.model.ToolEntity;
// imports above intentionally minimal; java.util.* used inline where needed

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AvailableToolServiceTest {

    private ToolService toolService;
    private McpServerService mcpServerService;
    private AvailableToolService service;

    @BeforeEach
    void setUp() {
        toolService = mock(ToolService.class);
        mcpServerService = mock(McpServerService.class);
        service = new AvailableToolService(toolService, mcpServerService);
        when(toolService.listEnabledTools()).thenReturn(List.of());
        when(mcpServerService.listEnabled()).thenReturn(List.of());
    }

    @Test
    @DisplayName("listAvailable mixes builtin and MCP tools")
    void mixesBuiltinAndMcp() {
        when(toolService.listEnabledTools()).thenReturn(List.of(builtin("web_search", "Search the web")));
        when(mcpServerService.listEnabled()).thenReturn(List.of(connectedServer(42L, "github", "create_issue")));

        List<AvailableToolDTO> out = service.listAvailable();

        assertEquals(2, out.size());
        Set<String> sources = out.stream().map(AvailableToolDTO::getSource).collect(Collectors.toSet());
        assertEquals(Set.of("builtin", "mcp"), sources);
    }

    @Test
    @DisplayName("MCP entry name equals McpToolNameResolver.prefixedName(serverId, raw)")
    void mcpNameMatchesResolver() {
        when(mcpServerService.listEnabled()).thenReturn(List.of(connectedServer(42L, "github", "create_issue")));

        AvailableToolDTO mcp = service.listAvailable().get(0);

        assertEquals(McpToolNameResolver.prefixedName(42L, "create_issue"), mcp.getName());
        assertEquals("create_issue", mcp.getRawName());
        assertEquals("mcp:42", mcp.getGroupId());
        assertEquals("MCP · github", mcp.getGroup());
        assertTrue(mcp.isAvailable());
        assertFalse(mcp.isStale());
    }

    @Test
    @DisplayName("disconnected MCP server marks tools stale but keeps them in the response")
    void staleFlagSetWhenDisconnected() {
        McpServerEntity disconnected = connectedServer(42L, "github", "create_issue");
        disconnected.setLastStatus("disconnected");
        when(mcpServerService.listEnabled()).thenReturn(List.of(disconnected));

        List<AvailableToolDTO> out = service.listAvailable();

        assertEquals(1, out.size());
        assertTrue(out.get(0).isStale());
        // stale entries are still bindable from the picker's perspective —
        // runtime will silently filter them when the callback isn't there.
        assertTrue(out.get(0).isAvailable());
    }

    @Test
    @DisplayName("two MCP servers exposing the same raw name produce distinct prefixed names, both bindable")
    void crossServerSameRawIsNotACollision() {
        when(mcpServerService.listEnabled()).thenReturn(List.of(
                connectedServer(42L, "github", "search"),
                connectedServer(43L, "filesystem", "search")));

        List<AvailableToolDTO> out = service.listAvailable();

        assertEquals(2, out.size());
        Set<String> names = out.stream().map(AvailableToolDTO::getName).collect(Collectors.toSet());
        assertTrue(names.contains(McpToolNameResolver.prefixedName(42L, "search")));
        assertTrue(names.contains(McpToolNameResolver.prefixedName(43L, "search")));
        assertEquals(2, names.size());
        for (AvailableToolDTO dto : out) {
            assertTrue(dto.isAvailable(), "expected bindable, got: " + dto);
        }
    }

    @Test
    @DisplayName("duplicate raw names within a server flag the second entry as unavailable")
    void duplicateRawNameSecondMarkedUnavailable() {
        // Two cached entries with the same raw name — pretend the upstream
        // surfaces a duplicate (defensive): the picker should disable the
        // second occurrence so the user can't bind a name that resolves to
        // nothing at runtime.
        McpServerEntity server = serverWithCacheJson(42L, "github",
                "[{\"name\":\"search\",\"description\":\"\",\"inputSchema\":{}}," +
                 "{\"name\":\"search\",\"description\":\"\",\"inputSchema\":{}}]");
        when(mcpServerService.listEnabled()).thenReturn(List.of(server));

        List<AvailableToolDTO> out = service.listAvailable();

        assertEquals(2, out.size());
        assertTrue(out.get(0).isAvailable());
        assertFalse(out.get(1).isAvailable());
        assertEquals("DUPLICATE_RAW_NAME", out.get(1).getUnavailableReason());
        // Two rows share the same prefixed `name`; rowId must differ so
        // the Vue picker doesn't reuse DOM state across them.
        assertNotEquals(out.get(0).getRowId(), out.get(1).getRowId(),
                "rowId must distinguish duplicate-raw entries");
    }

    @Test
    @DisplayName("hash-colliding raw pair: second entry is unavailable with HASH_COLLISION reason")
    void hashCollisionSecondMarkedUnavailable() {
        // Pair pre-mined by birthday search — same prefixed name, different raw.
        String[] pair = findHashCollidingPair(42L);
        String cacheJson = "[" +
                "{\"name\":\"" + pair[0] + "\",\"description\":\"\",\"inputSchema\":{}}," +
                "{\"name\":\"" + pair[1] + "\",\"description\":\"\",\"inputSchema\":{}}" +
                "]";
        McpServerEntity server = serverWithCacheJson(42L, "github", cacheJson);
        when(mcpServerService.listEnabled()).thenReturn(List.of(server));

        List<AvailableToolDTO> out = service.listAvailable();

        assertEquals(2, out.size());
        // Both rows carry the same prefixed name (that's the whole point of
        // a hash collision) but only the first is bindable.
        assertEquals(out.get(0).getName(), out.get(1).getName());
        assertTrue(out.get(0).isAvailable());
        assertFalse(out.get(1).isAvailable());
        assertNotNull(out.get(1).getUnavailableReason());
        assertTrue(out.get(1).getUnavailableReason().startsWith("HASH_COLLISION:"),
                "got reason: " + out.get(1).getUnavailableReason());
        // rowId must differ even though name is identical.
        assertNotEquals(out.get(0).getRowId(), out.get(1).getRowId());
    }

    /** Birthday-search a colliding raw-name pair (same slug + same hash6). */
    private static String[] findHashCollidingPair(long serverId) {
        String anchor = "xxxxxxxxxxxxxxxxxxxx"; // 20-char slug filler
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        for (int i = 0; i < 1_000_000; i++) {
            String raw = anchor + i;
            String hash = McpToolNameResolver.hash6(raw);
            String prior = seen.put(hash, raw);
            if (prior != null) {
                // sanity: confirm the FULL prefixed name is identical
                if (McpToolNameResolver.prefixedName(serverId, prior)
                        .equals(McpToolNameResolver.prefixedName(serverId, raw))) {
                    return new String[]{prior, raw};
                }
            }
        }
        throw new IllegalStateException("Could not find a colliding pair in 1M tries");
    }

    @Test
    @DisplayName("MCP server with empty cache contributes nothing to the picker")
    void emptyCacheContributesNothing() {
        McpServerEntity server = connectedServer(42L, "github");
        server.setToolsCacheJson("[]");
        when(mcpServerService.listEnabled()).thenReturn(List.of(server));

        List<AvailableToolDTO> out = service.listAvailable();
        assertEquals(0, out.size());
    }

    @Test
    @DisplayName("malformed cache JSON does not 500 the picker; the server contributes nothing")
    void malformedCacheGracefullySkipped() {
        McpServerEntity server = connectedServer(42L, "github");
        server.setToolsCacheJson("{not valid json}");
        when(mcpServerService.listEnabled()).thenReturn(List.of(server));

        List<AvailableToolDTO> out = service.listAvailable();
        assertNotNull(out);
        assertEquals(0, out.size());
    }

    private static ToolEntity builtin(String name, String description) {
        ToolEntity t = new ToolEntity();
        t.setName(name);
        t.setDescription(description);
        t.setEnabled(true);
        return t;
    }

    private static McpServerEntity connectedServer(long id, String name, String... rawTools) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rawTools.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(rawTools[i])
                    .append("\",\"description\":\"\",\"inputSchema\":{}}");
        }
        sb.append("]");
        return serverWithCacheJson(id, name, sb.toString());
    }

    private static McpServerEntity serverWithCacheJson(long id, String name, String cacheJson) {
        McpServerEntity s = new McpServerEntity();
        s.setId(id);
        s.setName(name);
        s.setEnabled(true);
        s.setLastStatus("connected");
        s.setToolsCacheJson(cacheJson);
        return s;
    }
}
