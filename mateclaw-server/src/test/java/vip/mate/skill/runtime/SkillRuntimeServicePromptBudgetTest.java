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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillRuntimeServicePromptBudgetTest {

    @Test
    @DisplayName("unbound prompt renders a small catalog and skips lessons")
    void unboundPromptUsesSmallCatalogAndSkipsLessons() {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        List<SkillEntity> entities = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(i -> entity((long) i, "skill-%02d".formatted(i), "builtin"))
                .toList();
        when(skillService.listEnabledSkills()).thenReturn(entities);
        for (SkillEntity entity : entities) {
            when(resolver.resolve(entity)).thenReturn(resolved(entity));
        }
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of());
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        String prompt = runtime.buildSkillPromptEnhancement(null, null, 8192);

        assertTrue(prompt.contains("skill-01"));
        assertTrue(prompt.contains("skill-08"));
        assertFalse(prompt.contains("skill-09"));
        assertTrue(prompt.contains("Showing 8 of 12"));
        assertFalse(prompt.contains("Lessons learned"));
        verify(lessonsService, never()).readLessonsBody(any());
    }

    @Test
    @DisplayName("bound prompt pins bound skill and only reads its lessons")
    void boundPromptPinsBoundSkillAndOnlyReadsItsLessons() {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        SkillEntity first = entity(1L, "apple-notes", "builtin");
        SkillEntity bound = entity(99L, "ckjia-shopping", "builtin");
        when(skillService.listEnabledSkills()).thenReturn(List.of(first, bound));
        ResolvedSkill firstResolved = resolved(first);
        ResolvedSkill boundResolved = resolved(bound);
        when(resolver.resolve(first)).thenReturn(firstResolved);
        when(resolver.resolve(bound)).thenReturn(boundResolved);
        when(lessonsService.readLessonsBody(boundResolved)).thenReturn("Use markdown links for products.");
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        String prompt = runtime.buildSkillPromptEnhancement(Set.of(99L), null, 8192);

        assertTrue(prompt.indexOf("ckjia-shopping") < prompt.indexOf("Lessons learned"));
        assertTrue(prompt.contains("Use markdown links for products."));
        verify(lessonsService).readLessonsBody(boundResolved);
        verify(lessonsService, never()).readLessonsBody(firstResolved);
    }

    @Test
    @DisplayName("recently loaded skill lessons are included for the same agent")
    void recentLoadedSkillLessonsAreIncludedForAgent() {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        SkillEntity recent = entity(7L, "browser-cdp", "builtin");
        when(skillService.listEnabledSkills()).thenReturn(List.of(recent));
        ResolvedSkill recentResolved = resolved(recent);
        when(resolver.resolve(recent)).thenReturn(recentResolved);
        when(usageService.recentLoadedSkillNames(42L, 8)).thenReturn(Set.of("browser-cdp"));
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());
        when(lessonsService.readLessonsBody(recentResolved)).thenReturn("Prefer inspecting the live page.");

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        String prompt = runtime.buildSkillPromptEnhancement(null, null, 8192, 42L);

        assertTrue(prompt.contains("Prefer inspecting the live page."));
        verify(lessonsService).readLessonsBody(recentResolved);
    }

    @Test
    @DisplayName("bound prompt 包含被显式勾选的 MCP 虚拟 skill（虚拟 skill 不丢 catalog 行）")
    void boundPromptIncludesVirtualMcpSkill() {
        // Regression for: an agent that explicitly binds an MCP-derived
        // virtual skill (via /skills/enabled picker) used to get its
        // tools — via AgentBindingService.getEffectiveToolNames —
        // but lost the corresponding `## Skills` catalog row, because
        // the bound branch sourced only real mate_skill entries.
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        // No real skill rows — the agent only ever bound the virtual one.
        when(skillService.listEnabledSkills()).thenReturn(List.of());
        long virtualMcpId = McpSkillBridge.VIRTUAL_ID_BASE + 7L;
        ResolvedSkill virtualMcp = ResolvedSkill.builder()
                .id(virtualMcpId)
                .name("mcp-virtual-skill")
                .description("Bridged from an enabled MCP server")
                .enabled(true)
                .runtimeAvailable(true)
                .dependencyReady(true)
                .securityBlocked(false)
                .build();
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of(virtualMcp));
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        String prompt = runtime.buildSkillPromptEnhancement(Set.of(virtualMcpId), null, 8192);

        assertTrue(prompt.contains("mcp-virtual-skill"),
                "bound MCP virtual skill must appear in the rendered catalog; "
                        + "prompt was: " + prompt);
    }

    @Test
    @DisplayName("workspace filter hides other workspaces' skills, keeps builtin global")
    void workspaceFilterHidesOtherWorkspaceSkills() {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        SkillEntity builtin = entity(1L, "pdf-builtin", "builtin");
        SkillEntity ownWorkspace = entity(2L, "ws1-skill", "dynamic");
        SkillEntity otherWorkspace = entity(3L, "ws2-skill", "dynamic");
        when(skillService.listEnabledSkills()).thenReturn(List.of(builtin, ownWorkspace, otherWorkspace));
        when(resolver.resolve(builtin)).thenReturn(scopedResolved(builtin, true, null));
        when(resolver.resolve(ownWorkspace)).thenReturn(scopedResolved(ownWorkspace, false, 1L));
        when(resolver.resolve(otherWorkspace)).thenReturn(scopedResolved(otherWorkspace, false, 2L));
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of());
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        // Agent lives in workspace 1.
        String prompt = runtime.buildSkillPromptEnhancement(null, null, 8192, null, 1L);

        assertTrue(prompt.contains("pdf-builtin"), "builtin skill must stay globally visible");
        assertTrue(prompt.contains("ws1-skill"), "the agent's own workspace skill must be visible");
        assertFalse(prompt.contains("ws2-skill"), "another workspace's skill must not leak into the prompt");
    }

    private static ResolvedSkill scopedResolved(SkillEntity entity, boolean builtin, Long workspaceId) {
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

    private static ResolvedSkill resolved(SkillEntity entity) {
        return ResolvedSkill.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .enabled(Boolean.TRUE.equals(entity.getEnabled()))
                .runtimeAvailable(true)
                .dependencyReady(true)
                .securityBlocked(false)
                .build();
    }
}
