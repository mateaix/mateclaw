package vip.mate.tool.disclosure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.AgentToolSet;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.mcp.model.McpServerEntity;
import vip.mate.tool.mcp.service.McpServerService;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.tool.service.ToolService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolDisclosureServiceTest {

    /** Fixture beans whose @Tool function names drive tier resolution. */
    static class Tools {
        @Tool(description = "text to image")
        public String image_generate() { return ""; }

        @Tool(description = "a plain core tool")
        public String my_core_tool() { return ""; }
    }

    /** Fixture whose class simple name is {@code ImageGenerateTool} and function
     *  name is {@code image_generate} — mirrors the real builtin's name skew so
     *  the class-name → function-name bridge can be tested. */
    static class ImageGenerateTool {
        @Tool(description = "text to image")
        public String image_generate() { return ""; }
    }

    /** Global tool set the bridge resolves DB class/bean names against. */
    private static AgentToolSet globalSet() {
        Object t1 = new Tools();
        Object t2 = new ImageGenerateTool();
        List<org.springframework.ai.tool.ToolCallback> cbs = new ArrayList<>();
        cbs.addAll(List.of(ToolCallbacks.from(t1)));
        cbs.addAll(List.of(ToolCallbacks.from(t2)));
        Map<Object, String> beanNames = Map.of(t1, "tools", t2, "imageGenerateTool");
        return AgentToolSet.fromCallbacks(List.of(t1, t2), cbs, beanNames::get);
    }

    private static ToolEntity toolRow(String name, String type, String tier) {
        ToolEntity t = new ToolEntity();
        t.setName(name);
        t.setToolType(type);
        t.setDisclosureTier(tier);
        return t;
    }

    private static McpServerEntity server(Long id, String name, String tier) {
        McpServerEntity s = new McpServerEntity();
        s.setId(id);
        s.setName(name);
        s.setDisclosureTier(tier);
        return s;
    }

    private static AvailableToolDTO mcpDto(String name, Long serverId) {
        return AvailableToolDTO.builder().source("mcp").providerId(serverId).name(name).build();
    }

    private DefaultToolDisclosureService service(List<ToolEntity> tools,
                                                List<McpServerEntity> servers,
                                                List<AvailableToolDTO> available) {
        ToolService ts = mock(ToolService.class);
        McpServerService ms = mock(McpServerService.class);
        AvailableToolService as = mock(AvailableToolService.class);
        ToolRegistry tr = mock(ToolRegistry.class);
        lenient().when(ts.listTools()).thenReturn(tools);
        lenient().when(ms.listAll()).thenReturn(servers);
        lenient().when(as.listAvailable()).thenReturn(available);
        lenient().when(tr.getEnabledToolSet()).thenReturn(globalSet());
        return new DefaultToolDisclosureService(ts, ms, as, tr);
    }

    @Test
    @DisplayName("meta-tools enable_tool / load_skill are always core")
    void metaToolsAlwaysCore() {
        var svc = service(List.of(toolRow("enable_tool", "builtin", "extension")), List.of(), List.of());
        assertEquals(DisclosureTier.CORE, svc.resolveTierByName("enable_tool"));
        assertEquals(DisclosureTier.CORE, svc.resolveTierByName("load_skill"));
    }

    @Test
    @DisplayName("generative tools default to extension even without a DB row")
    void generativeDefaultsExtension() {
        var svc = service(List.of(), List.of(), List.of());
        assertEquals(DisclosureTier.EXTENSION, svc.resolveTierByName("image_generate"));
        assertEquals(DisclosureTier.EXTENSION, svc.resolveTierByName("browser_use"));
    }

    @Test
    @DisplayName("unknown tools default to core (conservative)")
    void unknownDefaultsCore() {
        var svc = service(List.of(), List.of(), List.of());
        assertEquals(DisclosureTier.CORE, svc.resolveTierByName("memory_recall"));
    }

    @Test
    @DisplayName("mate_tool.disclosure_tier overrides the code default")
    void dbRowOverrides() {
        var svc = service(List.of(toolRow("my_core_tool", "builtin", "extension")), List.of(), List.of());
        assertEquals(DisclosureTier.EXTENSION, svc.resolveTierByName("my_core_tool"));
    }

    @Test
    @DisplayName("DB tier stored by Java class name bridges to the runtime function name")
    void dbTierBridgesClassNameToFunctionName() {
        // mate_tool.name = class name; resolveTier is queried by function name.
        var hidden = service(List.of(toolRow("ImageGenerateTool", "builtin", "extension")), List.of(), List.of());
        assertEquals(DisclosureTier.EXTENSION, hidden.resolveTierByName("image_generate"));

        // Admin un-hides it by setting the row to core; the DB value must win over
        // the code-level extension default.
        var unhidden = service(List.of(toolRow("ImageGenerateTool", "builtin", "core")), List.of(), List.of());
        assertEquals(DisclosureTier.CORE, unhidden.resolveTierByName("image_generate"));
    }

    @Test
    @DisplayName("MCP tool tier follows its owning server")
    void mcpFollowsServer() {
        var extSvc = service(List.of(), List.of(server(7L, "github", "extension")),
                List.of(mcpDto("mcp_github_create_issue", 7L)));
        assertEquals(DisclosureTier.EXTENSION, extSvc.resolveTierByName("mcp_github_create_issue"));

        var coreSvc = service(List.of(), List.of(server(7L, "github", "core")),
                List.of(mcpDto("mcp_github_create_issue", 7L)));
        assertEquals(DisclosureTier.CORE, coreSvc.resolveTierByName("mcp_github_create_issue"));
    }

    @Test
    @DisplayName("MCP tool whose server has no tier set defaults to core (visible)")
    void mcpDefaultsCoreWhenServerTierUnset() {
        var svc = service(List.of(), List.of(server(7L, "github", null)),
                List.of(mcpDto("mcp_github_create_issue", 7L)));
        assertEquals(DisclosureTier.CORE, svc.resolveTierByName("mcp_github_create_issue"));
    }

    @Test
    @DisplayName("split partitions into active (core + enabled) and the full extension catalog")
    void splitPartitions() {
        var svc = service(List.of(), List.of(), List.of());
        AgentToolSet set = AgentToolSet.fromCallbacks(List.of(new Tools()),
                List.of(ToolCallbacks.from(new Tools())));

        var noneEnabled = svc.split(set, Set.of());
        assertEquals(List.of("my_core_tool"), names(noneEnabled.activeCallbacks()));
        assertEquals(List.of("image_generate"), names(noneEnabled.extensionCatalog()));

        var imgEnabled = svc.split(set, Set.of("image_generate"));
        assertTrue(names(imgEnabled.activeCallbacks()).contains("image_generate"));
        assertTrue(names(imgEnabled.activeCallbacks()).contains("my_core_tool"));
        assertEquals(List.of("image_generate"), names(imgEnabled.extensionCatalog()));
    }

    @Test
    @DisplayName("legacy mode advertises everything and renders no catalog")
    void legacyMode() {
        var svc = service(List.of(), List.of(), List.of());
        ReflectionTestUtils.setField(svc, "disclosureMode", "legacy");
        AgentToolSet set = AgentToolSet.fromCallbacks(List.of(new Tools()),
                List.of(ToolCallbacks.from(new Tools())));

        var split = svc.split(set, Set.of());
        assertEquals(2, split.activeCallbacks().size());
        assertTrue(split.extensionCatalog().isEmpty());
        assertEquals("", svc.renderExtensionCatalog(set, 8192));
        assertEquals(DisclosureTier.CORE, svc.resolveTierByName("image_generate"));
    }

    @Test
    @DisplayName("renderExtensionCatalog lists extension tools under a heading")
    void rendersCatalog() {
        var svc = service(List.of(), List.of(), List.of());
        AgentToolSet set = AgentToolSet.fromCallbacks(List.of(new Tools()),
                List.of(ToolCallbacks.from(new Tools())));
        String catalog = svc.renderExtensionCatalog(set, 8192);
        assertTrue(catalog.contains("## Extension Tools"));
        assertTrue(catalog.contains("image_generate"));
        assertTrue(catalog.contains("enable_tool"));
        assertFalse(catalog.contains("my_core_tool"), "core tools must not appear in the extension catalog");
    }

    private static List<String> names(List<ToolCallback> cbs) {
        return cbs.stream().map(c -> c.getToolDefinition().name()).toList();
    }
}
