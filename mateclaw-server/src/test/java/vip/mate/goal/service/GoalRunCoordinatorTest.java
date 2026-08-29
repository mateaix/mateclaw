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
                new ClassPathResource("db/migration/h2/V189__goal_attempt_and_input_queue.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);continuations=new GoalContinuationStore(jdbc);attempts=new GoalAttemptStore(jdbc);
        coordinator=new GoalRunCoordinator(continuations,attempts,goals,properties);
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
}
