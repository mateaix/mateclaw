package vip.mate.skill.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.model.SkillEntity;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.runtime.McpClientManager;
import vip.mate.tool.mcp.runtime.McpToolNameResolver;
import vip.mate.tool.mcp.service.McpServerService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asserts the manifest writes prefixed callback names into
 * {@code allowedTools} (so {@code ResolvedSkill.getEffectiveAllowedTools()}
 * returns names that {@link vip.mate.tool.mcp.runtime.McpClientManager} also
 * registers) and that the cache-first / live-fallback ordering holds.
 */
class McpSkillBridgeManifestTest {

    private McpServerService mcpServerService;
    private McpClientManager mcpClientManager;
    private McpSkillBridge bridge;

    @BeforeEach
    void setUp() {
        mcpServerService = mock(McpServerService.class);
        mcpClientManager = mock(McpClientManager.class);
        bridge = new McpSkillBridge(mcpServerService, mcpClientManager, new ObjectMapper());
    }

    @Test
    @DisplayName("manifest emits prefixed tool names matching the resolver output")
    void allowedToolsArePrefixed() {
        McpServerEntity server = newServer(42L, "github");
        server.setToolsCacheJson(toolsJson("create_issue", "list_issues"));
        when(mcpServerService.listAll()).thenReturn(List.of(server));

        SkillEntity entity = bridge.listMcpDerivedSkillEntities().get(0);

        // The synthesized SkillEntity carries manifest_json — parse it back
        // and check allowedTools contains the prefixed names.
        String manifestJson = entity.getManifestJson();
        assertTrue(manifestJson.contains("\"" + McpToolNameResolver.prefixedName(42L, "create_issue") + "\""),
                "expected prefixed create_issue in manifest, got: " + manifestJson);
        assertTrue(manifestJson.contains("\"" + McpToolNameResolver.prefixedName(42L, "list_issues") + "\""),
                "expected prefixed list_issues in manifest, got: " + manifestJson);
    }

    @Test
    @DisplayName("manifest reads from tools_cache_json when present, never hits the live runtime")
    void readsFromCacheFirst() {
        McpServerEntity server = newServer(42L, "github");
        server.setToolsCacheJson(toolsJson("create_issue"));
        when(mcpServerService.listAll()).thenReturn(List.of(server));

        bridge.listMcpDerivedSkillEntities();

        verify(mcpClientManager, never()).getServerTools(anyLong());
    }

    @Test
    @DisplayName("manifest falls back to live runtime when cache is absent")
    void fallsBackToLiveWhenCacheMissing() {
        McpServerEntity server = newServer(42L, "github");
        server.setToolsCacheJson(null); // first-ever connect just happened, cache not yet written
        when(mcpServerService.listAll()).thenReturn(List.of(server));
        when(mcpClientManager.getServerTools(42L)).thenReturn(List.of(
                fakeTool("create_issue"),
                fakeTool("list_issues")));

        SkillEntity entity = bridge.listMcpDerivedSkillEntities().get(0);

        verify(mcpClientManager, times(1)).getServerTools(42L);
        assertTrue(entity.getManifestJson().contains(McpToolNameResolver.prefixedName(42L, "create_issue")));
    }

    @Test
    @DisplayName("disconnected server with empty cache yields an empty allowedTools — no exceptions")
    void disconnectedAndEmptyCacheIsHandled() {
        McpServerEntity server = newServer(42L, "github");
        server.setToolsCacheJson("");
        server.setLastStatus("disconnected");
        when(mcpServerService.listAll()).thenReturn(List.of(server));
        when(mcpClientManager.getServerTools(42L)).thenReturn(List.of());

        SkillEntity entity = bridge.listMcpDerivedSkillEntities().get(0);

        // The manifest should still serialize successfully — the picker can
        // still show the skill in stale mode. Jackson may omit the empty
        // allowedTools list entirely, so just assert no prefixed names
        // leaked in (which would indicate a stale-cache regression).
        assertEquals("github", entity.getName());
        assertTrue(!entity.getManifestJson().contains("mcp_42_"),
                "no prefixed tool name expected, got: " + entity.getManifestJson());
    }

    @Test
    @DisplayName("virtual id encoding round-trips for Snowflake-magnitude server ids (regression)")
    void virtualIdRoundTripsForLargeSnowflakeIds() {
        // 2054864660577071106 is a real Snowflake observed in the wild;
        // the previous BASE + serverId scheme overflowed signed long for
        // ids of this magnitude, producing negative virtual ids that
        // failed isVirtualMcpSkillId and broke the skill detail lookup.
        long[] cases = {1L, 1_000_001L, 2_054_864_660_577_071_106L, (1L << 61), (1L << 62) - 1L};
        for (long sid : cases) {
            McpServerEntity server = newServer(sid, "anything");
            long vid = McpSkillBridge.virtualIdFor(server);
            assertTrue(McpSkillBridge.isVirtualMcpSkillId(vid),
                    "vid for serverId=" + sid + " should be classified as MCP virtual: got 0x"
                            + Long.toHexString(vid));
            assertEquals(sid, McpSkillBridge.extractMcpServerId(vid),
                    "extract did not round-trip for serverId=" + sid);
        }
    }

    @Test
    @DisplayName("MCP and ACP virtual id spaces never overlap, real Snowflake ids classify as neither")
    void virtualIdSpacesAreDisjoint() {
        long serverId = 2_054_864_660_577_071_106L; // Snowflake magnitude
        McpServerEntity server = newServer(serverId, "anything");
        long mcpVid = McpSkillBridge.virtualIdFor(server);

        assertTrue(McpSkillBridge.isVirtualMcpSkillId(mcpVid));
        // An MCP virtual id must NOT be misread as ACP.
        assertTrue(!vip.mate.skill.acp.AcpSkillBridge.isVirtualAcpSkillId(mcpVid),
                "MCP vid 0x" + Long.toHexString(mcpVid) + " leaked into the ACP range");
        // Real Snowflake ids (positive, top bits clear) must be neither.
        assertTrue(!McpSkillBridge.isVirtualMcpSkillId(serverId));
        assertTrue(!vip.mate.skill.acp.AcpSkillBridge.isVirtualAcpSkillId(serverId));
    }

    @Test
    @DisplayName("CJK-only server names slug to a stable id-based fallback instead of an all-dash collision")
    void cjkOnlyNameFallsBackToIdSlug() {
        McpServerEntity a = newServer(42L, "知识图谱对象数据查询服务");
        McpServerEntity b = newServer(43L, "客户档案信息查询服务");
        a.setToolsCacheJson(toolsJson("search"));
        b.setToolsCacheJson(toolsJson("search"));
        when(mcpServerService.listAll()).thenReturn(List.of(a, b));

        List<SkillEntity> entities = bridge.listMcpDerivedSkillEntities();

        assertEquals("mcp-42", entities.get(0).getName(),
                "all-CJK name should fall back to id-based slug");
        assertEquals("mcp-43", entities.get(1).getName(),
                "second all-CJK name must not collide with the first");
        assertTrue(entities.get(0).getManifestJson().contains("\"id\":\"mcp-42\""),
                "manifest id should mirror the fallback slug, got: " + entities.get(0).getManifestJson());
    }

    @Test
    @DisplayName("ASCII server names keep their existing slug — no regression for English names")
    void asciiNamePreservesExistingSlug() {
        McpServerEntity server = newServer(42L, "GitHub");
        server.setToolsCacheJson(toolsJson("create_issue"));
        when(mcpServerService.listAll()).thenReturn(List.of(server));

        SkillEntity entity = bridge.listMcpDerivedSkillEntities().get(0);

        assertEquals("github", entity.getName());
    }

    @Test
    @DisplayName("two servers exposing the same raw tool name produce distinct prefixed names")
    void twoServersSameRawNameDistinct() {
        McpServerEntity a = newServer(42L, "github");
        a.setToolsCacheJson(toolsJson("search"));
        McpServerEntity b = newServer(43L, "filesystem");
        b.setToolsCacheJson(toolsJson("search"));
        when(mcpServerService.listAll()).thenReturn(List.of(a, b));

        List<SkillEntity> entities = bridge.listMcpDerivedSkillEntities();

        Set<String> prefixed = Set.of(
                McpToolNameResolver.prefixedName(42L, "search"),
                McpToolNameResolver.prefixedName(43L, "search"));
        assertEquals(2, prefixed.size());
        assertTrue(entities.get(0).getManifestJson().contains(McpToolNameResolver.prefixedName(42L, "search")));
        assertTrue(entities.get(1).getManifestJson().contains(McpToolNameResolver.prefixedName(43L, "search")));
    }

    private static McpServerEntity newServer(long id, String name) {
        McpServerEntity s = new McpServerEntity();
        s.setId(id);
        s.setName(name);
        s.setEnabled(true);
        s.setTransport("stdio");
        s.setCommand("/usr/bin/echo");
        s.setLastStatus("connected");
        return s;
    }

    private static String toolsJson(String... names) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(names[i])
                    .append("\",\"description\":\"\",\"inputSchema\":{}}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static McpSchema.Tool fakeTool(String name) {
        return new McpSchema.Tool(
                name,
                /* title */ name,
                "Test tool",
                /* inputSchema */ null,
                /* outputSchema */ null,
                /* annotations */ null,
                /* meta */ null);
    }
}
