package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vip.mate.channel.web.ConversationInputQueueStore;
import vip.mate.goal.model.GoalAttempt;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalRecoveryServiceTest {
    JdbcTemplate jdbc;
    GoalAttemptStore attempts;
    GoalContinuationStore continuations;
    ConversationInputQueueStore inputs;
    GoalService goals=mock(GoalService.class);
    GoalRunCoordinator coordinator;
    GoalRecoveryService recovery;
    GoalEntity goal;
    LocalDateTime now=LocalDateTime.of(2026,8,27,2,0);

    @BeforeEach void setup() {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:"+ UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V120__agent_goal.sql"),
                new ClassPathResource("db/migration/h2/V188__goal_continuation.sql"),
                new ClassPathResource("db/migration/h2/V189__goal_attempt_and_input_queue.sql"),
                new ClassPathResource("db/migration/h2/V198__goal_absolute_owner_leases.sql"),
                new ClassPathResource("db/migration/h2/V200__goal_approval_attempt_handoff.sql"),
                new ClassPathResource("db/migration/h2/V199__queued_input_account_identity.sql"),
                new ClassPathResource("db/migration/h2/V201__queued_input_selected_goal.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);attempts=new GoalAttemptStore(jdbc);continuations=new GoalContinuationStore(jdbc);
        inputs=new ConversationInputQueueStore(jdbc,new ObjectMapper());
        coordinator=new GoalRunCoordinator(continuations,attempts,goals,new vip.mate.goal.config.GoalProperties(),java.time.Clock.fixed(now.atZone(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneId.systemDefault()));
        recovery=new GoalRecoveryService(attempts,continuations,inputs,goals,new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        jdbc.update("""
                INSERT INTO mate_agent_goal(id,conversation_id,agent_id,workspace_id,created_by,title,
                description,status,persistent_execution,auto_followup_enabled,create_time,update_time)
                VALUES(1,'conv',2,3,'alice','goal','objective','active',TRUE,TRUE,?,?)
                """,now,now);
        goal=new GoalEntity();goal.setId(1L);goal.setConversationId("conv");goal.setAgentId(2L);
        goal.setWorkspaceId(3L);goal.setCreatedBy("alice");goal.setStatus(GoalStatus.ACTIVE);
        goal.setPersistentExecution(true);goal.setAutoFollowupEnabled(true);
        when(goals.getById(1L)).thenReturn(goal);
        continuations.discover(now);
    }

    @Test void classifiesCheckpointRecoveryMatrix() {
        assertEquals(GoalRecoveryService.RecoveryDecision.RETRY_SAFE,recovery.classify(attempt("claimed","safe",null)));
        assertEquals(GoalRecoveryService.RecoveryDecision.BLOCK_UNCERTAIN_SIDE_EFFECT,
                recovery.classify(attempt("tool_started","uncertain",null)));
        assertEquals(GoalRecoveryService.RecoveryDecision.RECONCILE_MESSAGE,
                recovery.classify(attempt("message_saved","resolved",42L)));
        assertEquals(GoalRecoveryService.RecoveryDecision.RESUME_FROM_EVIDENCE,
                recovery.classify(attempt("tool_completed","resolved",null)));
    }

    @Test void expiredSafeAttemptRequeuesAndReleasesClaimedInputWithParentLink() {
        var old=coordinator.claim(continuations.get(1L),goal,now);
        assertTrue(coordinator.markRunning(old,now));
        var queued=inputs.enqueue("conv",2L,"alice","follow up",List.of(),now);
        assertTrue(inputs.claimNext("conv",old.attempt().id(),now).isPresent());
        assertEquals(1,recovery.recoverExpired(now.plusSeconds(61).atZone(java.time.ZoneId.systemDefault()).toInstant()));
        assertEquals("retryable",attempts.get(old.attempt().id()).state());
        assertEquals("retry",continuations.get(1L).state());
        assertEquals(1,inputs.countQueued("conv"));
        var next=coordinator.claim(continuations.get(1L),goal,now.plusSeconds(61));
        assertEquals(old.attempt().id(),next.attempt().parentAttemptId());
        assertEquals(queued.id(),inputs.listQueued("conv").getFirst().id());
    }

    @Test void recoveryContextSurvivesDeferralUntilTheFirstExecutedSegment() {
        var old = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(old, now));
        var recoveryTime = now.plusSeconds(61);
        assertEquals(1, recovery.recoverExpired(recoveryTime.atZone(java.time.ZoneId.systemDefault()).toInstant()));
        var deferred = coordinator.claim(continuations.get(1L), goal, recoveryTime);
        assertEquals(old.attempt().id(), deferred.attempt().parentAttemptId());
        var later = now.plusSeconds(90);
        assertTrue(coordinator.settle(deferred, new vip.mate.goal.model.SegmentOutcome.Defer("followup_cooldown", later), recoveryTime));
        var resumed = coordinator.claim(continuations.get(1L), goal, later);
        assertEquals(deferred.attempt().id(), resumed.attempt().parentAttemptId(),
            "A pre-execution cooldown must not discard the pending recovery context");
        assertTrue(coordinator.markRunning(resumed, later));
        assertTrue(coordinator.checkpoint(resumed, "safe", "provider_started", null, later));
        assertTrue(coordinator.settle(resumed, new vip.mate.goal.model.SegmentOutcome.Continue("unfinished"), later));
        var next = coordinator.claim(continuations.get(1L), goal, continuations.get(1L).nextRunAt());
        assertNull(next.attempt().parentAttemptId(), "Ordinary continuation after execution is not a fresh recovery");
    }

    @Test void liveProjectionDoesNotAbortRecoveryOfOtherExpiredAttempts() {
        var live = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(live, now));
        jdbc.update("""
            INSERT INTO mate_agent_goal(id,conversation_id,agent_id,workspace_id,created_by,title,
                description,status,persistent_execution,auto_followup_enabled,create_time,update_time)
            VALUES(2,'conv2',2,3,'alice','second','objective','active',TRUE,TRUE,?,?)
            """, now, now);
        var second = new GoalEntity();
        org.springframework.beans.BeanUtils.copyProperties(goal, second);
        second.setId(2L); second.setConversationId("conv2");
        continuations.discover(now);
        var expired = coordinator.claim(continuations.get(2L), second, now);
        assertTrue(coordinator.markRunning(expired, now));
        long moment = now.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        // The first scan candidate has a still-live projection; a later one is eligible.
        jdbc.update("UPDATE mate_goal_attempt SET lease_until_epoch_second=? WHERE attempt_id=?", moment - 2, live.attempt().id());
        jdbc.update("UPDATE mate_goal_attempt SET lease_until_epoch_second=? WHERE attempt_id=?", moment - 1, expired.attempt().id());
        jdbc.update("UPDATE mate_goal_continuation SET lease_until_epoch_second=? WHERE goal_id=2", moment - 1);
        assertEquals(1, recovery.recoverExpired(java.time.Instant.ofEpochSecond(moment)));
        assertEquals("running", attempts.get(live.attempt().id()).state());
        assertEquals(live.attempt().id(), continuations.get(1L).currentAttemptId());
        assertEquals("retryable", attempts.get(expired.attempt().id()).state());
        assertEquals("retry", continuations.get(2L).state());
    }

    @Test void uncertainToolAttemptBlocksInsteadOfReplaying() {
        var old=coordinator.claim(continuations.get(1L),goal,now);
        assertTrue(coordinator.markRunning(old,now));
        assertTrue(coordinator.checkpoint(old,"uncertain","tool_started",null,now.plusSeconds(1)));
        assertEquals(1,recovery.recoverExpired(now.plusSeconds(61).atZone(java.time.ZoneId.systemDefault()).toInstant()));
        assertEquals("blocked",attempts.get(old.attempt().id()).state());
        assertEquals("blocked",continuations.get(1L).state());
        verify(goals).pause(1L,"alice");
    }

    @Test void recoveryFailureRollsBackAttemptAndContinuationTogether() {
        var old=coordinator.claim(continuations.get(1L),goal,now);
        assertTrue(coordinator.markRunning(old,now));
        assertTrue(coordinator.checkpoint(old,"uncertain","tool_started",null,now.plusSeconds(1)));
        doThrow(new IllegalStateException("fixture pause failure")).when(goals).pause(1L,"alice");
        assertThrows(IllegalStateException.class, () -> recovery.recoverExpired(now.plusSeconds(61).atZone(java.time.ZoneId.systemDefault()).toInstant()));
        assertEquals("running",attempts.get(old.attempt().id()).state());
        assertEquals("running",continuations.get(1L).state());
        assertEquals(old.attempt().id(),continuations.get(1L).currentAttemptId());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void legacyLeaseMigrationExpiresOwnersButPreservesRecoverySafety(boolean uncertain) {
        var old = coordinator.claim(continuations.get(1L), goal, now);
        assertTrue(coordinator.markRunning(old, now));
        if (uncertain) assertTrue(coordinator.checkpoint(old, "uncertain", "tool_started", null, now));
        // Recreate the pre-V198 schema while retaining real persisted attempts/checkpoints.
        jdbc.execute("DROP INDEX idx_goal_attempt_lease_epoch");
        jdbc.execute("DROP INDEX idx_goal_continuation_lease_epoch");
        jdbc.execute("ALTER TABLE mate_goal_attempt DROP COLUMN lease_until_epoch_second");
        jdbc.execute("ALTER TABLE mate_goal_continuation DROP COLUMN lease_until_epoch_second");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V198__goal_absolute_owner_leases.sql"))
                .execute(jdbc.getDataSource());
        assertFalse(coordinator.renew(old, now.plusSeconds(1)));
        assertEquals(1, recovery.recoverExpired(now.plusSeconds(1).atZone(java.time.ZoneId.systemDefault()).toInstant()));
        assertEquals(uncertain ? "blocked" : "retryable", attempts.get(old.attempt().id()).state());
        assertEquals(uncertain ? "blocked" : "retry", continuations.get(1L).state());
        if (uncertain) verify(goals).pause(1L, "alice");
        else {
            var fresh = coordinator.claim(continuations.get(1L), goal, now.plusSeconds(1));
            assertNotEquals(old.attempt().leaseToken(), fresh.attempt().leaseToken());
            assertEquals(old.attempt().id(), fresh.attempt().parentAttemptId());
        }
    }

    private GoalAttempt attempt(String checkpoint,String safety,Long messageId) {
        return new GoalAttempt("a",1L,"conv",null,"continuation","running","lease",now,
                null,messageId,safety,checkpoint,null,null,now,null,now,now);
    }
}
