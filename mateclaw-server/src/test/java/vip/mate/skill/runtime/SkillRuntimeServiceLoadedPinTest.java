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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that skills loaded this run (the {@code LOADED_SKILLS} signal) are
 * pinned to the top of the runtime catalog so a multi-iteration loop stops
 * re-loading the same skill.
 */
class SkillRuntimeServiceLoadedPinTest {

    @Test
    @DisplayName("a skill loaded this run floats to the top, ahead of the budget-truncated default order")
    void loadedSkillIsPinnedToTop() {
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

        // Control: without the pin, skill-10 is past the 8-entry budget and not shown.
        String baseline = runtime.buildSkillPromptEnhancement(null, null, 8192);
        assertFalse(baseline.contains("skill-10"),
                "control: skill-10 should be truncated out of the default 8-of-12 catalog");

        // With skill-10 loaded this run, it should be pinned to the top.
        String pinned = runtime.buildSkillPromptEnhancement(
                null, null, 8192, null, null, Set.of("skill-10"));

        assertTrue(pinned.contains("skill-10"),
                "skill loaded this run must appear in the catalog even past the budget; prompt was: " + pinned);
        assertTrue(pinned.indexOf("skill-10") < pinned.indexOf("skill-01"),
                "skill loaded this run must be pinned ahead of the default-order entries; prompt was: " + pinned);
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
}
