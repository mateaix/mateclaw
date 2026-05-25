package vip.mate.skill.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.model.SkillEntity;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillControllerVirtualMergeTest {

    @Test
    @DisplayName("virtual MCP rows shadowed by real skills are not merged into list")
    void virtualRowsShadowedByRealSkillAreFiltered() {
        SkillEntity virtualMcp = skill("ckjia-shopping", "mcp");
        SkillEntity github = skill("github", "mcp");

        List<SkillEntity> filtered = SkillController.filterShadowedVirtualSkills(
                List.of(virtualMcp, github),
                Set.of("ckjia-shopping"));

        assertEquals(List.of(github), filtered);
    }

    @Test
    @DisplayName("virtual count excludes rows shadowed by real skills")
    void virtualCountExcludesShadowedRows() {
        SkillEntity virtualMcp = skill("ckjia-shopping", "mcp");
        SkillEntity github = skill("github", "mcp");

        long count = SkillController.countUnshadowedVirtualSkills(
                List.of(virtualMcp, github),
                Set.of("ckjia-shopping"));

        assertEquals(1L, count);
    }

    @Test
    @DisplayName("virtual rows are appended after the DB page window")
    void virtualRowsDoNotDisplaceFirstDbPage() {
        List<SkillEntity> dbRecords = List.of(
                skill("apple-notes", "builtin"),
                skill("arxiv", "builtin"));
        SkillEntity claudeCode = skill("claude-code", "acp");

        SkillController.VirtualPageMergeResult merged = SkillController.mergeVirtualTailPageRecords(
                dbRecords, List.of(claudeCode), 50, 1, 10);

        assertEquals(51L, merged.total());
        assertEquals(dbRecords, merged.records());
    }

    @Test
    @DisplayName("virtual rows fill the tail page after DB records are exhausted")
    void virtualRowsFillTailPage() {
        SkillEntity claudeCode = skill("claude-code", "acp");

        SkillController.VirtualPageMergeResult merged = SkillController.mergeVirtualTailPageRecords(
                List.of(), List.of(claudeCode), 50, 6, 10);

        assertEquals(51L, merged.total());
        assertEquals(List.of(claudeCode), merged.records());
    }

    private static SkillEntity skill(String name, String type) {
        SkillEntity s = new SkillEntity();
        s.setName(name);
        s.setSkillType(type);
        s.setEnabled(true);
        return s;
    }
}
