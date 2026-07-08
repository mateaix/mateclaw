package vip.mate.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.lessons.SkillLessonsService;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.mcp.McpSkillBridge;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.usage.SkillUsageService;

import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Move 2 &amp; Move 3 — coverage for the new catalog segments:
 * <ol>
 *   <li>Move 2: a 4th {@code Constraints} column in the catalog table,
 *       populated only for bound skills whose manifest declares
 *       {@code constraints[]}.</li>
 *   <li>Move 3: a {@code ### Bound skill allowed tools} block after the
 *       table, listing each bound skill's effective tool allowlist.</li>
 * </ol>
 *
 * <p>Existing tests in {@link SkillRuntimeServicePromptBudgetTest} use a
 * {@code resolved()} helper that builds a {@link ResolvedSkill} without a
 * manifest, so neither the Constraints column nor the allowed-tools block
 * is exercised. This class fills that gap.
 */
class SkillRuntimeServiceConstraintsAndToolsTest {

    @Test
    @DisplayName("Move 2: bound skill with manifest constraints renders a Constraints cell")
    void boundSkillWithConstraintsRendersConstraintsColumn() {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        SkillEntity entity = entity(99L, "ckjia-shopping", "builtin");
        when(skillService.listEnabledSkills()).thenReturn(List.of(entity));
        SkillManifest manifest = SkillManifest.builder()
                .constraints(List.of("Always confirm before writing", "Use markdown links"))
                .build();
        ResolvedSkill bound = resolvedWithManifest(entity, manifest);
        when(resolver.resolve(entity)).thenReturn(bound);
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of());
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        String prompt = runtime.buildSkillPromptEnhancement(Set.of(99L), null, 8192);

        // The table header has the Constraints column
        assertTrue(prompt.contains("| Skill | Status | Description | Constraints |"),
                "catalog table must declare the Constraints column; prompt was: " + prompt);
        // Both constraints survive in the cell
        assertTrue(prompt.contains("Always confirm before writing"),
                "first constraint must render in the Constraints cell; prompt was: " + prompt);
        assertTrue(prompt.contains("Use markdown links"),
                "second constraint must render in the Constraints cell; prompt was: " + prompt);
    }

    @Test
    @DisplayName("Move 2: non-bound (recommended) skill omits constraints even when manifest has them")
    void nonBoundSkillOmitsConstraints() {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        SkillEntity entity = entity(7L, "pdf-builtin", "builtin");
        when(skillService.listEnabledSkills()).thenReturn(List.of(entity));
        // Manifest has constraints, but the agent does NOT bind this skill
        SkillManifest manifest = SkillManifest.builder()
                .constraints(List.of("Never delete source files"))
                .build();
        ResolvedSkill skill = resolvedWithManifest(entity, manifest);
        when(resolver.resolve(entity)).thenReturn(skill);
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of());
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        // boundSkillIds = null → "recommended" branch, no skill is bound
        String prompt = runtime.buildSkillPromptEnhancement(null, null, 8192);

        assertTrue(prompt.contains("| Skill | Status | Description | Constraints |"),
                "header still declares Constraints column; prompt was: " + prompt);
        assertFalse(prompt.contains("Never delete source files"),
                "non-bound skill constraints must NOT render; prompt was: " + prompt);
    }

    @Test
    @DisplayName("Move 2: long constraints are truncated to the per-cell budget")
    void longConstraintsAreTruncated() {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        SkillEntity entity = entity(42L, "long-constraints-skill", "builtin");
        when(skillService.listEnabledSkills()).thenReturn(List.of(entity));
        // Build a constraint well past CONSTRAINTS_SUMMARY_LIMIT (80 chars)
        String longConstraint = "A".repeat(120);
        SkillManifest manifest = SkillManifest.builder()
                .constraints(List.of(longConstraint))
                .build();
        ResolvedSkill bound = resolvedWithManifest(entity, manifest);
        when(resolver.resolve(entity)).thenReturn(bound);
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of());
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        String prompt = runtime.buildSkillPromptEnhancement(Set.of(42L), null, 8192);

        // The truncation marker appears
        assertTrue(prompt.contains("..."),
                "truncated constraints must end with '...'; prompt was: " + prompt);
        // The full 120-char string is NOT present
        assertFalse(prompt.contains("A".repeat(120)),
                "long constraint must be truncated, not rendered whole; prompt was: " + prompt);
        // The truncated prefix IS present (80 chars)
        assertTrue(prompt.contains("A".repeat(80)),
                "truncated prefix (80 'A's) must be in the cell; prompt was: " + prompt);
    }

    @Test
    @DisplayName("Move 2: pipe characters in constraints are escaped to protect the table layout")
    void pipeInConstraintsIsEscaped() {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        SkillEntity entity = entity(11L, "pipe-skill", "builtin");
        when(skillService.listEnabledSkills()).thenReturn(List.of(entity));
        SkillManifest manifest = SkillManifest.builder()
                .constraints(List.of("Use option A | option B"))
                .build();
        ResolvedSkill bound = resolvedWithManifest(entity, manifest);
        when(resolver.resolve(entity)).thenReturn(bound);
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of());
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        String prompt = runtime.buildSkillPromptEnhancement(Set.of(11L), null, 8192);

        assertTrue(prompt.contains("Use option A \\| option B"),
                "pipe must be escaped as \\|; prompt was: " + prompt);
    }

    @Test
    @DisplayName("Move 3: bound skill with allowedTools renders the 'Bound skill allowed tools' block")
    void boundSkillWithAllowedToolsRendersBlock() {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        SkillEntity entity = entity(99L, "ckjia-shopping", "builtin");
        when(skillService.listEnabledSkills()).thenReturn(List.of(entity));
        SkillManifest manifest = SkillManifest.builder()
                .allowedTools(List.of("read_file", "execute_code"))
                .build();
        ResolvedSkill bound = resolvedWithManifest(entity, manifest);
        when(resolver.resolve(entity)).thenReturn(bound);
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of());
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        String prompt = runtime.buildSkillPromptEnhancement(Set.of(99L), null, 8192);

        assertTrue(prompt.contains("### Bound skill allowed tools"),
                "allowed-tools block header must render; prompt was: " + prompt);
        assertTrue(prompt.contains("`ckjia-shopping`: `read_file`, `execute_code`"),
                "allowed-tools line must list skill + tools; prompt was: " + prompt);
    }

    @Test
    @DisplayName("Move 3: bound skill with no allowedTools omits the block entirely")
    void boundSkillWithoutAllowedToolsOmitsBlock() {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        SkillEntity entity = entity(99L, "doc-only-skill", "builtin");
        when(skillService.listEnabledSkills()).thenReturn(List.of(entity));
        // Manifest exists (so Constraints column could render) but has no
        // allowedTools — the skill is documentation-only.
        SkillManifest manifest = SkillManifest.builder()
                .constraints(List.of("Read-only"))
                .build();
        ResolvedSkill bound = resolvedWithManifest(entity, manifest);
        when(resolver.resolve(entity)).thenReturn(bound);
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of());
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        String prompt = runtime.buildSkillPromptEnhancement(Set.of(99L), null, 8192);

        assertFalse(prompt.contains("### Bound skill allowed tools"),
                "no allowedTools → block must be omitted; prompt was: " + prompt);
        // Constraints still render
        assertTrue(prompt.contains("Read-only"),
                "constraints still render even without allowedTools; prompt was: " + prompt);
    }

    @Test
    @DisplayName("Move 3: non-bound skill with allowedTools does NOT render the block")
    void nonBoundSkillWithAllowedToolsOmitsBlock() {
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        SkillEntity entity = entity(7L, "pdf-builtin", "builtin");
        when(skillService.listEnabledSkills()).thenReturn(List.of(entity));
        SkillManifest manifest = SkillManifest.builder()
                .allowedTools(List.of("read_file"))
                .build();
        ResolvedSkill skill = resolvedWithManifest(entity, manifest);
        when(resolver.resolve(entity)).thenReturn(skill);
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of());
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        // boundSkillIds = null → no skill is bound
        String prompt = runtime.buildSkillPromptEnhancement(null, null, 8192);

        assertFalse(prompt.contains("### Bound skill allowed tools"),
                "non-bound skill must not render the block; prompt was: " + prompt);
    }

    @Test
    @DisplayName("Move 1+2+3: static catalog (render(Set.of())) omits both bound-only segments")
    void staticCatalogRenderedWithEmptyBoundSetOmitsBoundSegments() {
        // This mirrors how ReasoningNode now calls render(Set.of()) —
        // the static catalog must NOT contain bound-only segments
        // (Constraints cells, allowed-tools block), because no skill is
        // bound in the static-render pass.
        SkillService skillService = mock(SkillService.class);
        SkillPackageResolver resolver = mock(SkillPackageResolver.class);
        SkillLessonsService lessonsService = mock(SkillLessonsService.class);
        McpSkillBridge mcpBridge = mock(McpSkillBridge.class);
        AcpSkillBridge acpBridge = mock(AcpSkillBridge.class);
        SkillUsageService usageService = mock(SkillUsageService.class);

        SkillEntity entity = entity(99L, "skill-with-everything", "builtin");
        when(skillService.listEnabledSkills()).thenReturn(List.of(entity));
        SkillManifest manifest = SkillManifest.builder()
                .constraints(List.of("Always confirm"))
                .allowedTools(List.of("read_file"))
                .build();
        ResolvedSkill skill = resolvedWithManifest(entity, manifest);
        when(resolver.resolve(entity)).thenReturn(skill);
        when(mcpBridge.listMcpDerivedResolvedSkills()).thenReturn(List.of());
        when(acpBridge.listAcpDerivedResolvedSkills()).thenReturn(List.of());
        when(usageService.recentLoadedSkillNames(null, 8)).thenReturn(Set.of());
        when(usageService.frequentlyLoadedSkillNames(8)).thenReturn(Set.of());

        SkillRuntimeService runtime = new SkillRuntimeService(
                skillService, resolver, lessonsService, mcpBridge, acpBridge, usageService);

        // boundSkillIds = null simulates the static-render pass (no skill
        // is "bound" from the cache-stability perspective)
        String prompt = runtime.buildSkillPromptEnhancement(null, null, 8192);

        // Header still present (it's part of the table layout)
        assertTrue(prompt.contains("| Skill | Status | Description | Constraints |"),
                "header still declares the column; prompt was: " + prompt);
        // But the bound-only content is absent
        assertFalse(prompt.contains("Always confirm"),
                "bound-only constraints must not render in static pass; prompt was: " + prompt);
        assertFalse(prompt.contains("### Bound skill allowed tools"),
                "bound-only allowed-tools block must not render in static pass; prompt was: " + prompt);
    }

    // ============================ helpers ============================

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

    private static ResolvedSkill resolvedWithManifest(SkillEntity entity, SkillManifest manifest) {
        // passesActiveGate requires hasAnyActiveFeature() when a manifest is
        // present — without an activeFeatures entry the skill is filtered out
        // by refreshActiveSkills() and the catalog comes back empty.
        Set<String> activeFeatures = new LinkedHashSet<>();
        activeFeatures.add("default");
        return ResolvedSkill.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .enabled(Boolean.TRUE.equals(entity.getEnabled()))
                .runtimeAvailable(true)
                .dependencyReady(true)
                .securityBlocked(false)
                .manifest(manifest)
                .activeFeatures(activeFeatures)
                .build();
    }
}
