package vip.mate.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.model.SkillEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillCatalogSorterTest {

    @Test
    @DisplayName("recommended order keeps ready builtins before external virtual skills")
    void recommendedOrderKeepsReadyBuiltinsBeforeExternalVirtualSkills() {
        SkillEntity claude = skill("claude-code", "acp", true, "PASSED");
        SkillEntity appleNotes = skill("apple-notes", "builtin", true, "PASSED");
        SkillEntity dynamic = skill("team-runbook", "dynamic", true, "PASSED");
        SkillEntity blocked = skill("unsafe", "builtin", true, "FAILED");
        SkillEntity disabled = skill("disabled-core", "builtin", false, "PASSED");

        List<SkillEntity> sorted = SkillCatalogSorter.sortEntities(
                List.of(claude, disabled, blocked, dynamic, appleNotes),
                SkillCatalogSort.RECOMMENDED);

        assertEquals(List.of(appleNotes, dynamic, claude, disabled, blocked), sorted);
    }

    @Test
    @DisplayName("name order is stable across sources")
    void nameOrderIsStableAcrossSources() {
        SkillEntity zed = skill("zed", "acp", true, "PASSED");
        SkillEntity alpha = skill("alpha", "builtin", true, "PASSED");

        List<SkillEntity> sorted = SkillCatalogSorter.sortEntities(
                List.of(zed, alpha),
                SkillCatalogSort.NAME);

        assertEquals(List.of(alpha, zed), sorted);
    }

    private static SkillEntity skill(String name, String type, boolean enabled, String scanStatus) {
        SkillEntity s = new SkillEntity();
        s.setName(name);
        s.setDescription("Description for " + name);
        s.setSkillType(type);
        s.setEnabled(enabled);
        s.setSecurityScanStatus(scanStatus);
        return s;
    }
}
