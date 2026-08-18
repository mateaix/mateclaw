package vip.mate.skill.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the curation policy flag. The codes are persisted values and the
 * curator's candidate query is built from {@link SkillOrigin#curatorManagedCodes()},
 * so a change here silently changes which skills get archived.
 */
class SkillOriginTest {

    @Test
    @DisplayName("persisted codes are stable")
    void codesAreStable() {
        assertEquals("user", SkillOrigin.USER.code());
        assertEquals("agent", SkillOrigin.AGENT.code());
        assertEquals("routine", SkillOrigin.ROUTINE.code());
    }

    @Test
    @DisplayName("user-authored skills are off-limits to autonomous curation")
    void userSkillsAreNotCuratorManaged() {
        assertFalse(SkillOrigin.USER.curatorManaged());
    }

    @Test
    @DisplayName("autonomously-written skills are curator-managed")
    void autonomousSkillsAreCuratorManaged() {
        assertTrue(SkillOrigin.AGENT.curatorManaged());
        assertTrue(SkillOrigin.ROUTINE.curatorManaged());
    }

    @Test
    @DisplayName("the curator candidate filter covers exactly the autonomous origins")
    void managedCodesMatchTheFlag() {
        for (SkillOrigin origin : SkillOrigin.values()) {
            assertEquals(origin.curatorManaged(),
                    SkillOrigin.curatorManagedCodes().contains(origin.code()),
                    origin + " must appear in curatorManagedCodes() iff it is curator-managed");
        }
    }
}
