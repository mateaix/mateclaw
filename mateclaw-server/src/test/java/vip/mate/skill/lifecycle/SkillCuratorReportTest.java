package vip.mate.skill.lifecycle;

import org.junit.jupiter.api.Test;
import vip.mate.skill.model.SkillEntity;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the report builder — in particular the planned / applied count
 * split so a dry-run preview still shows what <em>would</em> happen.
 */
class SkillCuratorReportTest {

    private final LocalDateTime now = LocalDateTime.now();

    private SkillEntity skill(long id, String name, LocalDateTime lastActivity) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName(name);
        s.setLifecycleState("active");
        s.setLastActivityAt(lastActivity);
        s.setCreateTime(lastActivity);
        return s;
    }

    @Test
    void dryRunReportKeepsPlannedCountsButZeroApplied() {
        SkillCuratorReport report = SkillCuratorReport.builder()
                .runAt(now)
                .dryRun(true)
                .config(30, 90, "AGENT_CREATED")
                .add(skill(1L, "tmp-helper", now.minusDays(40)), LifecycleTransition.TO_STALE)
                .add(skill(2L, "old-grep", now.minusDays(95)), LifecycleTransition.TO_ARCHIVED)
                .scanned(2)
                .plannedCounts(1, 1, 0)
                .appliedCounts(0, 0, 0)
                .build();

        assertTrue(report.isDryRun());
        assertEquals(1, report.getPlanned().stale());
        assertEquals(1, report.getPlanned().archived());
        assertEquals(0, report.getApplied().stale());
        assertEquals(0, report.getApplied().archived());
        assertEquals(2, report.getTransitions().size());
        // Convenience accessors report what actually happened — zero for a dry-run.
        assertEquals(0, report.markedStale());
        assertEquals(0, report.archived());
    }

    @Test
    void appliedReportCountsMatchPlannedOnCleanRun() {
        SkillCuratorReport report = SkillCuratorReport.builder()
                .runAt(now)
                .dryRun(false)
                .config(30, 90, "AGENT_CREATED")
                .scanned(3)
                .plannedCounts(2, 1, 0)
                .appliedCounts(2, 1, 0)
                .build();

        assertFalse(report.isDryRun());
        assertEquals(2, report.markedStale());
        assertEquals(1, report.archived());
        assertEquals(0, report.reactivated());
    }

    @Test
    void transitionRowCarriesDaysIdleFromAnchor() {
        SkillCuratorReport report = SkillCuratorReport.builder()
                .runAt(now)
                .config(30, 90, "AGENT_CREATED")
                .add(skill(7L, "stale-thing", now.minusDays(42)), LifecycleTransition.TO_STALE)
                .build();

        SkillCuratorReport.TransitionRow row = report.getTransitions().get(0);
        assertEquals(7L, row.skillId());
        assertEquals("active", row.from());
        assertEquals("stale", row.to());
        assertEquals(42L, row.daysIdle());
    }

    @Test
    void runIdIsDerivedFromRunTimestamp() {
        LocalDateTime fixed = LocalDateTime.of(2026, 5, 19, 2, 0, 0);
        SkillCuratorReport report = SkillCuratorReport.builder().runAt(fixed).build();
        assertEquals("20260519-020000", report.getRunId());
    }
}
