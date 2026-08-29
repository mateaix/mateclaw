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
                new ClassPathResource("db/migration/h2/V189__goal_attempt_and_input_queue.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);attempts=new GoalAttemptStore(jdbc);continuations=new GoalContinuationStore(jdbc);
        inputs=new ConversationInputQueueStore(jdbc,new ObjectMapper());
        coordinator=new GoalRunCoordinator(continuations,attempts,goals,new vip.mate.goal.config.GoalProperties());
        recovery=new GoalRecoveryService(attempts,continuations,inputs,goals);
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
        assertEquals(1,recovery.recoverExpired(now.plusSeconds(61)));
        assertEquals("retryable",attempts.get(old.attempt().id()).state());
        assertEquals("retry",continuations.get(1L).state());
        assertEquals(1,inputs.countQueued("conv"));
        var next=coordinator.claim(continuations.get(1L),goal,now.plusSeconds(61));
        assertEquals(old.attempt().id(),next.attempt().parentAttemptId());
        assertEquals(queued.id(),inputs.listQueued("conv").getFirst().id());
    }

    @Test void uncertainToolAttemptBlocksInsteadOfReplaying() {
        var old=coordinator.claim(continuations.get(1L),goal,now);
        assertTrue(coordinator.markRunning(old,now));
        assertTrue(coordinator.checkpoint(old,"uncertain","tool_started",null,now.plusSeconds(1)));
        assertEquals(1,recovery.recoverExpired(now.plusSeconds(61)));
        assertEquals("blocked",attempts.get(old.attempt().id()).state());
        assertEquals("blocked",continuations.get(1L).state());
        verify(goals).pause(1L,"alice");
    }

    private GoalAttempt attempt(String checkpoint,String safety,Long messageId) {
        return new GoalAttempt("a",1L,"conv",null,"continuation","running","lease",now,
                null,messageId,safety,checkpoint,null,null,now,null,now,now);
    }
}
