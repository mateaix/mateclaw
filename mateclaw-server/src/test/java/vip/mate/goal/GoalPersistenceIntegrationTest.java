package vip.mate.goal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.exception.MateClawException;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.service.GoalService;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test that pins two load-bearing DB invariants:
 *
 * <ol>
 *   <li>{@code GoalStatus} persists as lowercase strings ({@code "active"}
 *       etc., NOT {@code "ACTIVE"}). The V120 predicate unique index
 *       compares {@code status = 'active'} as a literal — any uppercase
 *       write would silently defeat the uniqueness guarantee.</li>
 *   <li>The {@code uk_agent_goal_active_conv} unique index rejects a
 *       second active-row insert for the same conversation. Service-layer
 *       pre-check is a UX nicety; this is the source of truth.</li>
 * </ol>
 *
 * <p>Uses an in-memory H2 MySQL-compat database so Flyway runs V120
 * exactly as it would in dev. The {@code DATABASE_TO_LOWER=TRUE} flag is
 * standard across mateclaw's other Spring tests.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:goal_persistence_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.goal.enabled=false"
})
class GoalPersistenceIntegrationTest {

    @Autowired private GoalService goalService;
    @Autowired private JdbcTemplate jdbc;

    private GoalCreateRequest req(String convId, String title) {
        GoalCreateRequest r = new GoalCreateRequest();
        r.setConversationId(convId);
        r.setAgentId(1L);
        r.setWorkspaceId(1L);
        r.setTitle(title);
        r.setDescription("desc");
        return r;
    }

    @Test
    @DisplayName("GoalStatus values persist as lowercase literals — load-bearing for uk_agent_goal_active_conv")
    void status_persistsAsLowercaseString() {
        GoalEntity created = goalService.create(req("conv-status-1", "lower-case check"), "alice");
        String raw = jdbc.queryForObject(
                "SELECT status FROM mate_agent_goal WHERE id = ?",
                String.class, created.getId());
        assertEquals("active", raw,
                "GoalStatus must persist as lowercase 'active' — uppercase 'ACTIVE' would " +
                        "silently bypass the V120 predicate unique index uk_agent_goal_active_conv.");
    }

    @Test
    @DisplayName("Each terminal status also persists lowercase")
    void terminalStatuses_alsoPersistLowercase() {
        GoalEntity g = goalService.create(req("conv-status-terminal", "terminal check"), "alice");

        goalService.abandon(g.getId(), "alice");
        String s = jdbc.queryForObject(
                "SELECT status FROM mate_agent_goal WHERE id = ?",
                String.class, g.getId());
        assertEquals("abandoned", s);
    }

    @Test
    @DisplayName("Service rejects a second active goal on the same conversation (UX pre-check 409)")
    void servicePreCheck_blocksDuplicateActiveCreation() {
        goalService.create(req("conv-dup-1", "first"), "alice");
        MateClawException ex = assertThrows(MateClawException.class,
                () -> goalService.create(req("conv-dup-1", "second"), "alice"));
        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("DB unique index rejects a second active row even when service pre-check is bypassed")
    void uniqueIndex_isUltimateSourceOfTruth() {
        // First goal — via service so it gets a real ID + workspace + timestamps.
        goalService.create(req("conv-uq-1", "first"), "alice");

        // Second insertion — bypass the service entirely and write through
        // JdbcTemplate. Must hit DuplicateKeyException at the DB level.
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbc.update(
                    "INSERT INTO mate_agent_goal " +
                            "(id, conversation_id, agent_id, workspace_id, created_by, " +
                            " title, description, status, turn_budget, turns_used, " +
                            " llm_call_budget, agent_llm_calls_used, eval_llm_calls_used, " +
                            " auto_followup_enabled, followup_cooldown_seconds, " +
                            " version, deleted, create_time, update_time) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    99999L, "conv-uq-1", 1L, 1L, "alice",
                    "second", "desc", "active",
                    20, 0, 200, 0, 0,
                    false, 0,
                    0, 0, Timestamp.valueOf(now), Timestamp.valueOf(now));
            fail("Expected DuplicateKeyException from uk_agent_goal_active_conv");
        } catch (DuplicateKeyException expected) {
            // good
        }
    }

    @Test
    @DisplayName("A new active goal is allowed after the previous one entered a terminal state")
    void terminalGoal_releasesUniquenessSlot() {
        GoalEntity first = goalService.create(req("conv-recycle-1", "first"), "alice");
        goalService.abandon(first.getId(), "alice");

        // After abandon, the conversation should be free to host a new active goal.
        GoalEntity second = goalService.create(req("conv-recycle-1", "second"), "alice");
        assertNotNull(second);
        assertEquals(GoalStatus.ACTIVE, second.getStatus());
    }
}
