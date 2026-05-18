package vip.mate.skill.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.runtime.McpClientManager;
import vip.mate.tool.mcp.service.McpServerService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asserts that MCP-derived virtual skills carry a synthesized SKILL.md body
 * instead of the empty string. Without it, {@code readSkillFile("SKILL.md")}
 * returns "content not available" and the agent cannot reason about the
 * MCP server's tools.
 */
class McpSkillBridgeContentTest {

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
    @DisplayName("resolved skill carries a non-empty SKILL.md listing the server's tools")
    void resolvedSkillHasSynthesizedContent() {
        McpServerEntity server = newServer(42L, "github");
        server.setToolsCacheJson(toolsJson(
                "create_issue", "Open a new issue in a repository",
                "list_issues", "List issues filtered by state and labels"));
        when(mcpServerService.listAll()).thenReturn(List.of(server));

        ResolvedSkill resolved = bridge.listMcpDerivedResolvedSkills().get(0);

        String content = resolved.getContent();
        assertFalse(content == null || content.isBlank(), "SKILL.md content must not be empty");
        assertTrue(content.contains("github"), "content should name the MCP server");
        assertTrue(content.contains("create_issue"), "content should list the create_issue tool");
        assertTrue(content.contains("list_issues"), "content should list the list_issues tool");
        assertTrue(content.contains("Open a new issue in a repository"),
                "content should carry the upstream tool description");
    }

    @Test
    @DisplayName("virtual SkillEntity carries skillContent so the detail drawer can render it")
    void entityHasSynthesizedSkillContent() {
        McpServerEntity server = newServer(42L, "github");
        server.setToolsCacheJson(toolsJson("create_issue", "Open a new issue"));
        when(mcpServerService.listAll()).thenReturn(List.of(server));

        SkillEntity entity = bridge.listMcpDerivedSkillEntities().get(0);

        assertFalse(entity.getSkillContent() == null || entity.getSkillContent().isBlank(),
                "skillContent must be populated for MCP-derived skills");
        assertTrue(entity.getSkillContent().contains("create_issue"));
    }

    @Test
    @DisplayName("a server with no known tools still gets a content body that explains the gap")
    void emptyToolListStillProducesContent() {
        McpServerEntity server = newServer(42L, "github");
        server.setToolsCacheJson("");
        server.setLastStatus("disconnected");
        when(mcpServerService.listAll()).thenReturn(List.of(server));
        when(mcpClientManager.getServerTools(42L)).thenReturn(List.of());

        ResolvedSkill resolved = bridge.listMcpDerivedResolvedSkills().get(0);

        String content = resolved.getContent();
        assertFalse(content == null || content.isBlank(),
                "content must not be empty even when the tool list is unavailable");
        assertTrue(content.contains("MCP Connections"),
                "content should point the user at the MCP Connections page");
    }

    @Test
    @DisplayName("tool descriptions are clamped to a single prompt-friendly line")
    void longDescriptionsAreClampedToOneLine() {
        McpServerEntity server = newServer(42L, "github");
        String longDesc = "x".repeat(400);
        server.setToolsCacheJson(toolsJson("create_issue", longDesc));
        when(mcpServerService.listAll()).thenReturn(List.of(server));

        ResolvedSkill resolved = bridge.listMcpDerivedResolvedSkills().get(0);

        String toolLine = resolved.getContent().lines()
                .filter(l -> l.startsWith("- **create_issue**"))
                .findFirst()
                .orElseThrow();
        assertTrue(toolLine.length() < longDesc.length(),
                "an over-long description should be truncated, got: " + toolLine.length());
        assertTrue(toolLine.endsWith("…"), "truncated descriptions should end with an ellipsis");
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

    /** Builds a tools_cache_json array from alternating name/description pairs. */
    private static String toolsJson(String... nameDescPairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < nameDescPairs.length; i += 2) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"name\":\"").append(nameDescPairs[i])
                    .append("\",\"description\":\"").append(nameDescPairs[i + 1])
                    .append("\",\"inputSchema\":{}}");
        }
        sb.append("]");
        return sb.toString();
    }

    @Test
    @DisplayName("two name/description pairs round-trip into the catalog")
    void multipleToolsRenderAsCatalogRows() {
        McpServerEntity server = newServer(7L, "filesystem");
        server.setToolsCacheJson(toolsJson(
                "read_file", "Read the contents of a file",
                "write_file", "Write content to a file"));
        when(mcpServerService.listAll()).thenReturn(List.of(server));

        String content = bridge.listMcpDerivedResolvedSkills().get(0).getContent();

        long rows = content.lines().filter(l -> l.startsWith("- **")).count();
        assertEquals(2, rows, "each MCP tool should be one catalog row");
    }

    @Test
    @DisplayName("toggleVirtualSkill forwards to mcpServerService.toggle and reflects the new state")
    void toggleVirtualSkillForwardsToServer() {
        McpServerEntity disabled = newServer(42L, "github");
        disabled.setEnabled(false);
        long virtualId = McpSkillBridge.virtualIdFor(disabled);

        McpServerEntity enabledAfter = newServer(42L, "github");
        enabledAfter.setEnabled(true);
        when(mcpServerService.toggle(42L, true)).thenReturn(enabledAfter);

        SkillEntity result = bridge.toggleVirtualSkill(virtualId, true);

        verify(mcpServerService).toggle(42L, true);
        assertEquals("github", result.getName());
        assertEquals(Boolean.TRUE, result.getEnabled());
    }

    @Test
    @DisplayName("a disabled MCP server still surfaces as a (disabled) virtual skill row")
    void disabledServerStillListedAsDisabledSkill() {
        McpServerEntity server = newServer(42L, "github");
        server.setEnabled(false);
        when(mcpServerService.listAll()).thenReturn(List.of(server));

        List<SkillEntity> entities = bridge.listMcpDerivedSkillEntities();

        assertEquals(1, entities.size(),
                "disabled MCP servers must still appear so the toggle can be flipped back on");
        assertEquals(Boolean.FALSE, entities.get(0).getEnabled());
    }
}
