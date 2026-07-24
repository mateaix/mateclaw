package vip.mate.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.lessons.SkillLessonsService;
import vip.mate.skill.mcp.McpSkillBridge;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.usage.SkillUsageService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Execution-side workspace isolation for {@link SkillRuntimeService}: the
 * runtime resolves skills by name for the agent's load / read / execute path.
 * Two skills sharing a name in different workspaces (possible since skills are
 * workspace-scoped) must resolve independently — an agent in one workspace must
 * never load or read the other workspace's same-named skill. Builtin and
 * global (null-workspace, e.g. MCP virtual) skills stay visible everywhere.
 */
class SkillRuntimeServiceWorkspaceExecutionTest {

    private SkillRuntimeService runtimeWith(List<ResolvedSkill> resolvedSkills, List<SkillEntity> entities) {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        when(skillService.listEnabledSkills()).thenReturn(entities);
        for (int i = 0; i < entities.size(); i++) {
            when(resolver.resolve(entities.get(i))).thenReturn(resolvedSkills.get(i));
        }
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of());
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        return new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);
    }

    @Test
    @DisplayName("same-named skills in different workspaces resolve independently")
    void sameNameDifferentWorkspacesResolveIndependently() {
        SkillEntity builtin = entity(1L, "bar", "builtin");
        SkillEntity fooWs1 = entity(2L, "foo", "dynamic");
        SkillEntity fooWs2 = entity(3L, "foo", "dynamic");
        SkillRuntimeService runtime = runtimeWith(
                List.of(resolved(builtin, true, null), resolved(fooWs1, false, 1L), resolved(fooWs2, false, 2L)),
                List.of(builtin, fooWs1, fooWs2));

        // Each workspace resolves its OWN foo — never the other's.
        assertEquals(2L, runtime.findActiveSkill("foo", 1L).getId(),
                "workspace 1 must resolve its own foo (id=2)");
        assertEquals(3L, runtime.findActiveSkill("foo", 2L).getId(),
                "workspace 2 must resolve its own foo (id=3), not workspace 1's");
        // Builtin stays global.
        assertNotNull(runtime.findActiveSkill("bar", 2L), "builtin bar must be visible in every workspace");
    }

    @Test
    @DisplayName("unresolved (null) workspace sees only builtin + global, never a workspace skill")
    void nullWorkspaceSeesOnlyBuiltinAndGlobal() {
        SkillEntity builtin = entity(1L, "bar", "builtin");
        SkillEntity fooWs1 = entity(2L, "foo", "dynamic");
        SkillRuntimeService runtime = runtimeWith(
                List.of(resolved(builtin, true, null), resolved(fooWs1, false, 1L)),
                List.of(builtin, fooWs1));

        assertNull(runtime.findActiveSkill("foo", null),
                "a null execution workspace must NOT resolve any workspace-owned skill");
        assertNotNull(runtime.findActiveSkill("bar", null),
                "builtin stays visible even when the execution workspace is unresolved");
    }

    @Test
    @DisplayName("getActiveSkills(workspaceId) excludes other workspaces' skills")
    void getActiveSkillsScopedExcludesOtherWorkspace() {
        SkillEntity builtin = entity(1L, "bar", "builtin");
        SkillEntity fooWs1 = entity(2L, "foo", "dynamic");
        SkillEntity bazWs2 = entity(3L, "baz", "dynamic");
        SkillRuntimeService runtime = runtimeWith(
                List.of(resolved(builtin, true, null), resolved(fooWs1, false, 1L), resolved(bazWs2, false, 2L)),
                List.of(builtin, fooWs1, bazWs2));

        List<Long> ids = runtime.getActiveSkills(1L).stream().map(ResolvedSkill::getId).toList();
        assertTrue(ids.contains(1L), "builtin visible");
        assertTrue(ids.contains(2L), "own workspace skill visible");
        assertFalse(ids.contains(3L), "other workspace's skill must be excluded");
    }

    @Test
    @DisplayName("matchesWorkspace: builtin/global always visible, real skill only in its own workspace")
    void matchesWorkspaceRules() {
        ResolvedSkill builtin = ResolvedSkill.builder().id(1L).name("b").builtin(true).build();
        ResolvedSkill global = ResolvedSkill.builder().id(2L).name("g").workspaceId(null).build();
        ResolvedSkill ws1 = ResolvedSkill.builder().id(3L).name("s").workspaceId(1L).build();

        assertTrue(SkillRuntimeService.matchesWorkspace(builtin, 2L), "builtin visible everywhere");
        assertTrue(SkillRuntimeService.matchesWorkspace(builtin, null), "builtin visible even for null ws");
        assertTrue(SkillRuntimeService.matchesWorkspace(global, 2L), "null-workspace (virtual) visible everywhere");
        assertTrue(SkillRuntimeService.matchesWorkspace(ws1, 1L), "own workspace matches");
        assertFalse(SkillRuntimeService.matchesWorkspace(ws1, 2L), "other workspace excluded");
        assertFalse(SkillRuntimeService.matchesWorkspace(ws1, null), "unresolved workspace never sees a real skill");
    }

    private static SkillEntity entity(Long id, String name, String type) {
        SkillEntity entity = new SkillEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDescription("Description for " + name);
        entity.setSkillType(type);
        entity.setEnabled(true);
        entity.setSecurityScanStatus("PASSED");
        return entity;
    }

    private static ResolvedSkill resolved(SkillEntity entity, boolean builtin, Long workspaceId) {
        return ResolvedSkill.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .enabled(true)
                .runtimeAvailable(true)
                .dependencyReady(true)
                .securityBlocked(false)
                .builtin(builtin)
                .workspaceId(workspaceId)
                .build();
    }
}
