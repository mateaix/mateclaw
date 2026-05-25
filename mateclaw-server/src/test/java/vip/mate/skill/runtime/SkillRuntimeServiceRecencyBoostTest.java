package vip.mate.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.skill.runtime.model.ResolvedSkill;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the freshly-installed-skill boost
 * ({@link SkillRuntimeService#isRecentlyInstalled}).
 *
 * <p>Issue context: a brand-new skill (e.g. tencent-meeting-mcp uploaded
 * minutes ago) has zero usage stats and so falls behind ~40 existing skills
 * in the prompt-catalog ranker. With qwen-turbo's 8-entry budget the agent
 * never sees it and tells the user "no such skill". The boost lifts skills
 * created within the configured window to the top of the secondary sort
 * so the user can actually find what they just installed.
 */
class SkillRuntimeServiceRecencyBoostTest {

    private static ResolvedSkill skill(String name, LocalDateTime createTime, boolean builtin) {
        return ResolvedSkill.builder()
                .id(name.hashCode() & 0x7fffffffL)
                .name(name)
                .builtin(builtin)
                .createTime(createTime)
                .build();
    }

    @Test
    @DisplayName("skill installed inside the window is recent")
    void freshSkillIsRecent() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(SkillRuntimeService.NEW_SKILL_BOOST_WINDOW);
        ResolvedSkill fresh = skill("tencent-meeting-mcp", now.minusHours(2), false);

        assertTrue(SkillRuntimeService.isRecentlyInstalled(fresh, cutoff));
    }

    @Test
    @DisplayName("skill installed before the window is not recent")
    void oldSkillIsNotRecent() {
        LocalDateTime cutoff = LocalDateTime.now().minus(SkillRuntimeService.NEW_SKILL_BOOST_WINDOW);
        ResolvedSkill old = skill("legacy", cutoff.minusDays(30), false);

        assertFalse(SkillRuntimeService.isRecentlyInstalled(old, cutoff));
    }

    @Test
    @DisplayName("builtin skills are never boosted (the user didn't install them)")
    void builtinIsNotRecent() {
        LocalDateTime cutoff = LocalDateTime.now().minus(SkillRuntimeService.NEW_SKILL_BOOST_WINDOW);
        // Even if create_time happens to fall inside the window (e.g. fresh DB seed),
        // a builtin row was not a user install and shouldn't claim a top slot.
        ResolvedSkill recentBuiltin = skill("file_reader", LocalDateTime.now().minusHours(1), true);

        assertFalse(SkillRuntimeService.isRecentlyInstalled(recentBuiltin, cutoff));
    }

    @Test
    @DisplayName("missing createTime → not recent (virtual MCP/ACP rows)")
    void missingCreateTimeIsNotRecent() {
        LocalDateTime cutoff = LocalDateTime.now().minus(SkillRuntimeService.NEW_SKILL_BOOST_WINDOW);
        ResolvedSkill virt = ResolvedSkill.builder().id(1L).name("virt").build();

        assertFalse(SkillRuntimeService.isRecentlyInstalled(virt, cutoff));
    }

    @Test
    @DisplayName("null skill is safe to query")
    void nullSkillIsSafe() {
        assertFalse(SkillRuntimeService.isRecentlyInstalled(null,
                LocalDateTime.now().minus(SkillRuntimeService.NEW_SKILL_BOOST_WINDOW)));
    }

    @Test
    @DisplayName("default window is 7 days — long enough to span a weekend")
    void defaultWindowIsAWeek() {
        // Sanity-pin so future tweaks have to deliberately update the test.
        // The window matters: too short and a Friday installer is invisible
        // by Monday; too long and the boost slot crowds out useful skills.
        assertEquals(7, SkillRuntimeService.NEW_SKILL_BOOST_WINDOW.toDays());
    }
}
