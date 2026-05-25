package vip.mate.skill.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.common.result.R;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.mcp.McpSkillBridge;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused unit coverage for the bridge merge in
 * {@link SkillController#listEnabled()} — ensures the agent picker's
 * skill-list endpoint shows MCP/ACP virtual skills, mirrors the shadow
 * rule used by the paginated {@code /skills} endpoint, and never 500s
 * when a bridge throws.
 */
class SkillControllerListEnabledTest {

    private SkillService skillService;
    private McpSkillBridge mcpSkillBridge;
    private AcpSkillBridge acpSkillBridge;
    private SkillController controller;

    @BeforeEach
    void setUp() {
        // Only the four collaborators reachable from listEnabled() need real
        // mocks; the rest are nulls because the method never touches them.
        skillService = mock(SkillService.class);
        mcpSkillBridge = mock(McpSkillBridge.class);
        acpSkillBridge = mock(AcpSkillBridge.class);
        controller = new SkillController(
                skillService,
                /* skillRuntimeService */ null,
                /* workspaceManager */ null,
                /* bundledSkillSyncer */ null,
                /* skillFileSyncer */ null,
                /* synthesisService */ null,
                /* dependencyChecker */ null,
                /* lessonsService */ null,
                /* agentSkillBindingMapper */ null,
                /* agentService */ null,
                /* agentBindingService */ null,
                mcpSkillBridge,
                acpSkillBridge,
                /* skillLifecycleService */ null,
                /* skillCuratorJob */ null,
                /* skillCuratorReportStore */ null);
        // listSkills() supplies realSkillNames() for shadow base — default
        // to empty so each test can override.
        when(skillService.listSkills(null)).thenReturn(List.of());
        when(skillService.listEnabledSkills(null)).thenReturn(List.of());
        when(mcpSkillBridge.listMcpDerivedSkillEntities()).thenReturn(List.of());
        when(acpSkillBridge.listAcpDerivedSkillEntities()).thenReturn(List.of());
    }

    @Test
    @DisplayName("listEnabled merges MCP virtual skills into the response")
    void includesMcpVirtualSkills() {
        SkillEntity mcp = skill("github", "mcp");
        when(mcpSkillBridge.listMcpDerivedSkillEntities()).thenReturn(List.of(mcp));

        R<List<SkillEntity>> response = controller.listEnabled(null);

        assertNotNull(response.getData());
        assertTrue(response.getData().stream().anyMatch(s -> "github".equals(s.getName())),
                "expected the MCP virtual skill 'github' in the response");
    }

    @Test
    @DisplayName("listEnabled merges ACP virtual skills into the response")
    void includesAcpVirtualSkills() {
        SkillEntity acp = skill("claude-code", "acp");
        when(acpSkillBridge.listAcpDerivedSkillEntities()).thenReturn(List.of(acp));

        R<List<SkillEntity>> response = controller.listEnabled(null);

        assertTrue(response.getData().stream().anyMatch(s -> "claude-code".equals(s.getName())));
    }

    @Test
    @DisplayName("a same-name real skill that is DISABLED still shadows the virtual MCP twin")
    void disabledRealSkillShadowsVirtualTwin() {
        // realSkillNames() pulls from listSkills() (all rows, regardless of
        // enabled). If listEnabled() derived its shadow base from listEnabledSkills()
        // (enabled-only) by mistake, the virtual would slip through here.
        SkillEntity disabledReal = skill("github", "custom");
        disabledReal.setEnabled(false);
        when(skillService.listSkills(null)).thenReturn(List.of(disabledReal));
        when(skillService.listEnabledSkills(null)).thenReturn(List.of());

        SkillEntity virtualMcp = skill("github", "mcp");
        when(mcpSkillBridge.listMcpDerivedSkillEntities()).thenReturn(List.of(virtualMcp));

        R<List<SkillEntity>> response = controller.listEnabled(null);

        // The real skill is disabled, so listEnabledSkills() returns nothing;
        // the virtual MCP must also be filtered to keep this endpoint in step
        // with the management page.
        assertEquals(0, response.getData().size(),
                "disabled real skill should still suppress the virtual twin in /enabled");
    }

    @Test
    @DisplayName("MCP bridge failure does not 500 the response")
    void mcpBridgeFailureSwallowed() {
        SkillEntity enabled = skill("web_search", "builtin");
        enabled.setEnabled(true);
        when(skillService.listEnabledSkills(null)).thenReturn(List.of(enabled));
        when(mcpSkillBridge.listMcpDerivedSkillEntities())
                .thenThrow(new RuntimeException("MCP bridge offline"));

        R<List<SkillEntity>> response = controller.listEnabled(null);

        assertEquals(1, response.getData().size());
        assertEquals("web_search", response.getData().get(0).getName());
    }

    @Test
    @DisplayName("ACP bridge failure does not 500 the response and MCP results still merge")
    void acpBridgeFailureSwallowedMcpStillMerged() {
        when(acpSkillBridge.listAcpDerivedSkillEntities())
                .thenThrow(new RuntimeException("ACP discovery failed"));
        SkillEntity mcp = skill("github", "mcp");
        when(mcpSkillBridge.listMcpDerivedSkillEntities()).thenReturn(List.of(mcp));

        R<List<SkillEntity>> response = controller.listEnabled(null);

        assertTrue(response.getData().stream().anyMatch(s -> "github".equals(s.getName())));
    }

    @Test
    @DisplayName("listEnabled excludes a disabled MCP virtual skill")
    void excludesDisabledVirtualMcpSkill() {
        // The bridge now surfaces disabled MCP servers too (so the Skills
        // page can show a toggled-off card); the enabled-only picker must
        // filter them back out.
        SkillEntity disabledMcp = skill("github", "mcp");
        disabledMcp.setEnabled(false);
        when(mcpSkillBridge.listMcpDerivedSkillEntities()).thenReturn(List.of(disabledMcp));

        R<List<SkillEntity>> response = controller.listEnabled(null);

        assertEquals(0, response.getData().size(),
                "a disabled MCP virtual skill must not appear in the enabled-only picker");
    }

    private static SkillEntity skill(String name, String type) {
        SkillEntity s = new SkillEntity();
        s.setName(name);
        s.setSkillType(type);
        s.setEnabled(true);
        return s;
    }
}
