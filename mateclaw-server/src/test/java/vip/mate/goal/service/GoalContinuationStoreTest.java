package vip.mate.goal.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GoalContinuationStoreTest {
    JdbcTemplate jdbc;
    GoalContinuationStore store;
    LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);

    @BeforeEach void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/h2/V120__agent_goal.sql"),
                new ClassPathResource("db/migration/h2/V188__goal_continuation.sql"),
                new ClassPathResource("db/migration/h2/V189__goal_attempt_and_input_queue.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        store = new GoalContinuationStore(jdbc);
    }

    void goal(long id, boolean persistent, String status) {
        jdbc.update("""
                INSERT INTO mate_agent_goal(id,conversation_id,agent_id,workspace_id,created_by,title,
                description,status,persistent_execution,auto_followup_enabled,create_time,update_time)
                VALUES(?, ?,1,1,'alice','goal','full objective',?,?,TRUE,?,?)
                """, id, "conv-" + id, status, persistent, now, now);
    }

    @Test void discoversOnlyEligibleGoalsAndSurvivesStoreRecreation() {
        goal(1, true, "active"); goal(2, false, "active"); goal(3, true, "paused");
        store.discover(now); store.discover(now);
        assertEquals(1, new GoalContinuationStore(jdbc).due(now, 10).size());
        assertEquals(1L, store.due(now, 10).getFirst().goalId());
    }

    @Test void claimIsExclusiveAndSettlementIsFenced() {
        goal(1, true, "active"); store.discover(now);
        assertTrue(store.claim(1L, "worker-a", now, now.plusSeconds(60)));
        assertFalse(store.claim(1L, "worker-b", now, now.plusSeconds(60)));
        assertFalse(store.settle(1L, "worker-b", "queued", now, 0, "wrong worker"));
        assertTrue(store.settle(1L, "worker-a", "retry", now.plusSeconds(10), 1, "network"));
        assertTrue(store.due(now, 10).isEmpty());
        assertEquals(1, store.due(now.plusSeconds(10), 10).size());
    }

    @Test void expiredLeaseCanBeRecoveredButOldWorkerCannotSettle() {
        goal(1, true, "active"); store.discover(now);
        assertTrue(store.claim(1L, "old", now, now.plusSeconds(60)));
        assertTrue(store.due(now.plusSeconds(59), 10).isEmpty());
        assertEquals("running", store.due(now.plusSeconds(61), 10).getFirst().state());
        assertTrue(store.claim(1L, "new", now.plusSeconds(61), now.plusSeconds(120)));
        assertFalse(store.renew(1L, "old", now.plusSeconds(180)));
        assertFalse(store.settle(1L, "old", "queued", now, 0, "stale"));
    }

    @Test void pauseOrCompletionBetweenDiscoveryAndClaimPreventsExecution() {
        goal(1, true, "active"); store.discover(now);
        jdbc.update("UPDATE mate_agent_goal SET status='completed' WHERE id=1");
        assertFalse(store.claim(1L, "worker", now, now.plusSeconds(60)));
        assertTrue(store.due(now, 10).isEmpty());
    }

    @Test void stopPersistsAndExplicitResumeRequeues() {
        goal(1, true, "active"); store.discover(now);
        store.suspendConversation("conv-1", "user_stopped");
        store.discover(now.plusDays(1));
        assertTrue(store.due(now.plusDays(1), 10).isEmpty());
        assertEquals("paused", store.get(1L).state());
        store.resume(1L, now.plusDays(1));
        assertEquals(1, store.due(now.plusDays(1), 10).size());
    }

    @Test void approvalWaitRequiresInteractiveReplayRatherThanTimerExpiry() {
        goal(1,true,"active");store.discover(now);
        store.claim(1L,"worker",now,now.plusSeconds(60));
        store.settle(1L,"worker","waiting_approval",now,0,"approval_required");
        assertTrue(store.due(now.plusDays(1),10).isEmpty());
        store.turnFinished("conv-1",now.plusDays(1));
        assertEquals(1,store.due(now.plusDays(1),10).size());
    }

    @Test void fastApprovalReplayCannotLoseWakeupBeforeWaitingStateIsWritten() {
        goal(1,true,"active");store.discover(now);
        store.claim(1L,"worker",now,now.plusSeconds(60));
        store.turnFinished("conv-1",now);
        store.settle(1L,"worker","waiting_approval",now,0,"approval_required");
        assertEquals("queued",store.get(1L).state());
        assertEquals(1,store.due(now,10).size());
    }

    @Test void freshSupervisorsContinueBeyondOldFollowupCapAndStopOnCompletion() {
        goal(1,true,"active");
        var goals=org.mockito.Mockito.mock(GoalService.class);
        var runner=org.mockito.Mockito.mock(GoalSegmentRunner.class);
        var coordinator=new GoalRunCoordinator(new GoalContinuationStore(jdbc),new GoalAttemptStore(jdbc),goals,
                new vip.mate.goal.config.GoalProperties());
        var recovery=org.mockito.Mockito.mock(GoalRecoveryService.class);
        var running=new vip.mate.agent.runtime.RunningConversationRegistry();
        var streams=new vip.mate.channel.web.ChatStreamTracker(new com.fasterxml.jackson.databind.ObjectMapper());
        var properties=new vip.mate.goal.config.GoalProperties();
        var entity=new vip.mate.goal.model.GoalEntity();
        entity.setId(1L);entity.setConversationId("conv-1");entity.setStatus(vip.mate.goal.model.GoalStatus.ACTIVE);
        entity.setPersistentExecution(true);entity.setAutoFollowupEnabled(true);entity.setTitle("twelve required steps");
        entity.setTurnBudget(0);entity.setLlmCallBudget(0);
        org.mockito.Mockito.when(goals.getById(1L)).thenReturn(entity);
        var count=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(runner.run(org.mockito.ArgumentMatchers.any(GoalRunCoordinator.ClaimedRun.class),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> {
                    if(count.incrementAndGet()==12) {
                        entity.setStatus(vip.mate.goal.model.GoalStatus.COMPLETED);
                        jdbc.update("UPDATE mate_agent_goal SET status='completed' WHERE id=1");
                    }
                    return new vip.mate.goal.model.SegmentOutcome.Continue("normal");
                });
        for(int i=0;i<15;i++) {
            // Recreate all scheduler state between segments, as after a server restart.
            var scheduler=new GoalContinuationSupervisor(new GoalContinuationStore(jdbc),goals,properties,
                    new GoalFollowupService(properties,new com.fasterxml.jackson.databind.ObjectMapper()),runner,running,streams,
                    coordinator,recovery,
                    java.time.Clock.fixed(now.plusSeconds(i*5L).toInstant(java.time.ZoneOffset.UTC),java.time.ZoneOffset.UTC),Runnable::run);
            scheduler.tick();
        }
        assertEquals(12,count.get());
        assertEquals("completed",store.get(1L).state());
    }
}
