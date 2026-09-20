package vip.mate.goal.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.model.SegmentOutcome;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.parallel.Isolated
class GoalRunCoordinatorTest {
    JdbcTemplate jdbc;
    GoalContinuationStore continuations;
    GoalAttemptStore attempts;
    GoalService goals=mock(GoalService.class);
    GoalProperties properties=new GoalProperties();
    GoalRunCoordinator coordinator;
    LocalDateTime now=LocalDateTime.of(2026,8,27,1,0);
    GoalEntity goal;

    @BeforeEach void setup() {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:"+ UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/h2/V120__agent_goal.sql"),
                new ClassPathResource("db/migration/h2/V188__goal_continuation.sql"),
                new ClassPathResource("db/migration/h2/V189__goal_attempt_and_input_queue.sql"),
                new ClassPathResource("db/migration/h2/V198__goal_absolute_owner_leases.sql"),
                new ClassPathResource("db/migration/h2/V200__goal_approval_attempt_handoff.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);continuations=new GoalContinuationStore(jdbc);attempts=new GoalAttemptStore(jdbc);
        coordinator=new GoalRunCoordinator(continuations,attempts,goals,properties,java.time.Clock.fixed(now.atZone(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneId.systemDefault()));
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

    @Test void claimBindsAttemptAndStaleSettlementCannotOverwriteNewProjection() {
        var claim=coordinator.claim(continuations.get(1L),goal,now);
        assertNotNull(claim);
        assertEquals("claimed",attempts.get(claim.attempt().id()).state());
        assertEquals(claim.attempt().id(),continuations.get(1L).currentAttemptId());
        assertTrue(coordinator.markRunning(claim,now));
        assertTrue(coordinator.settle(claim,new SegmentOutcome.Continue("unfinished"),now));
        assertEquals("queued",continuations.get(1L).state());
        assertEquals("succeeded",attempts.get(claim.attempt().id()).state());
        assertFalse(coordinator.settle(claim,new SegmentOutcome.Complete("late"),now.plusSeconds(1)));
    }

    @Test void retryAndBlockedOutcomesHaveExplicitTerminalAttemptStates() {
        var retry=coordinator.claim(continuations.get(1L),goal,now);
        assertTrue(coordinator.markRunning(retry,now));
        assertTrue(coordinator.settle(retry,new SegmentOutcome.Retry("provider","timeout"),now));
        assertEquals("retryable",attempts.get(retry.attempt().id()).state());
        var due=continuations.get(1L);
        var blocked=coordinator.claim(due,goal,due.nextRunAt());
        assertTrue(coordinator.markRunning(blocked,due.nextRunAt()));
        assertTrue(coordinator.settle(blocked,new SegmentOutcome.Blocked("tool","review"),due.nextRunAt()));
        assertEquals("blocked",attempts.get(blocked.attempt().id()).state());
        assertEquals("blocked",continuations.get(1L).state());
    }

    @Test void continuationUsesTheLargerOfGlobalAndGoalCooldowns() {
        properties.setMinimumContinuationIntervalSeconds(300);
        goal.setFollowupCooldownSeconds(0);
        var first=coordinator.claim(continuations.get(1L),goal,now);
        assertTrue(coordinator.markRunning(first,now));
        assertTrue(coordinator.settle(first,new SegmentOutcome.Continue("unfinished"),now));
        assertEquals(now.plusSeconds(300),continuations.get(1L).nextRunAt());

        LocalDateTime secondStart=now.plusSeconds(300);
        goal.setFollowupCooldownSeconds(600);
        var second=coordinator.claim(continuations.get(1L),goal,secondStart);
        assertTrue(coordinator.markRunning(second,secondStart));
        assertTrue(coordinator.settle(second,new SegmentOutcome.Continue("unfinished"),secondStart));
        assertEquals(secondStart.plusSeconds(600),continuations.get(1L).nextRunAt());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"2026-11-01T05:59:50Z", "2026-11-01T06:00:10Z"})
    void leaseDurationStaysSixtyRealSecondsAcrossDstRollback(String timestamp) {
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            var instant = java.time.Instant.parse(timestamp);
            var reference = new java.util.concurrent.atomic.AtomicReference<>(instant);
            var clock = new java.time.Clock() {
                public java.time.ZoneId getZone() { return java.time.ZoneId.of("America/New_York"); }
                public java.time.Clock withZone(java.time.ZoneId zone) { return java.time.Clock.fixed(instant(), zone); }
                public java.time.Instant instant() { return reference.get(); }
            };
            var timed = new GoalRunCoordinator(continuations, attempts, goals, properties, clock);
            var local = java.time.LocalDateTime.ofInstant(instant, clock.getZone());
            var run = timed.claim(continuations.get(1L), goal, local);
            assertNotNull(run);
            assertEquals(instant.plusSeconds(60).getEpochSecond(), jdbc.queryForObject(
                    "SELECT lease_until_epoch_second FROM mate_goal_attempt WHERE attempt_id=?", Long.class, run.attempt().id()));
            reference.set(instant.plusSeconds(10));
            assertTrue(timed.renew(run, java.time.LocalDateTime.ofInstant(reference.get(), clock.getZone())));
            assertEquals(reference.get().plusSeconds(60).getEpochSecond(), jdbc.queryForObject(
                    "SELECT lease_until_epoch_second FROM mate_goal_continuation WHERE goal_id=1", Long.class));
            reference.set(reference.get().plusSeconds(61));
            var expiredLocal = java.time.LocalDateTime.ofInstant(reference.get(), clock.getZone());
            assertFalse(timed.renew(run, expiredLocal));
            var recovery = new GoalRecoveryService(attempts, continuations,
                    new vip.mate.channel.web.ConversationInputQueueStore(jdbc, new com.fasterxml.jackson.databind.ObjectMapper()), goals,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            assertEquals(1, recovery.recoverExpired(reference.get()));
            assertEquals("retry", continuations.get(1L).state());
            var next = timed.claim(continuations.get(1L), goal, expiredLocal);
            assertNotNull(next);
            assertNotEquals(run.attempt().leaseToken(), next.attempt().leaseToken());
        } finally { java.util.TimeZone.setDefault(previous); }
    }

    @Test void delayedTickCannotRenewUsingItsPreLockTimestamp() {
        var reference = new java.util.concurrent.atomic.AtomicReference<>(now.atZone(java.time.ZoneId.systemDefault()).toInstant());
        var clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneId.systemDefault(); }
            public java.time.Clock withZone(java.time.ZoneId zone) { return java.time.Clock.fixed(instant(), zone); }
            public java.time.Instant instant() { return reference.get(); }
        };
        var timed = new GoalRunCoordinator(continuations, attempts, goals, properties, clock);
        var run = timed.claim(continuations.get(1L), goal, now);
        assertTrue(timed.markRunning(run, now));
        reference.set(reference.get().plusSeconds(61));
        assertFalse(timed.renew(run, now));
        assertFalse(timed.checkpoint(run, "resolved", "tool_completed", null, now));
        assertFalse(timed.settle(run, new SegmentOutcome.Complete("delayed"), now));
    }

    @Test void selectedJsonGoalCannotSettleCompletedFromSegmentClaimAlone() {
        goal.setJsonAcceptanceRequired(true);
        var run=coordinator.claim(continuations.get(1L),goal,now);
        assertNotNull(run);
        assertTrue(coordinator.markRunning(run,now));
        assertTrue(coordinator.settle(run,new SegmentOutcome.Complete("model claim"),now));
        assertEquals("retry",continuations.get(1L).state());
        assertEquals("retryable",attempts.get(run.attempt().id()).state());
        assertEquals("json_completion_not_committed",continuations.get(1L).reason());
    }

}
