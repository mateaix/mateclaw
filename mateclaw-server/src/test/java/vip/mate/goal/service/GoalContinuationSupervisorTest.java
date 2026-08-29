package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.agent.runtime.RunningConversationRegistry;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalAttempt;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.model.SegmentOutcome;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoalContinuationSupervisorTest {
    GoalContinuationStore store = mock(GoalContinuationStore.class);
    GoalService goals = mock(GoalService.class);
    GoalSegmentRunner runner = mock(GoalSegmentRunner.class);
    GoalRunCoordinator coordinator = mock(GoalRunCoordinator.class);
    GoalRecoveryService recovery = mock(GoalRecoveryService.class);
    RunningConversationRegistry running = mock(RunningConversationRegistry.class);
    ChatStreamTracker streams = mock(ChatStreamTracker.class);
    GoalProperties properties = new GoalProperties();
    LocalDateTime now = LocalDateTime.of(2026,8,26,12,0);
    GoalEntity goal = new GoalEntity();
    GoalContinuationStore.Continuation candidate;
    GoalRunCoordinator.ClaimedRun claimed;
    GoalContinuationSupervisor supervisor;

    @BeforeEach void setup() {
        goal.setId(1L); goal.setConversationId("conv"); goal.setStatus(GoalStatus.ACTIVE);
        goal.setPersistentExecution(true); goal.setAutoFollowupEnabled(true);
        goal.setTitle("full goal"); goal.setTurnBudget(0); goal.setLlmCallBudget(0);
        candidate=new GoalContinuationStore.Continuation(1L,"conv","queued",now,null,null,0,"",null,0);
        GoalAttempt attempt=new GoalAttempt("attempt",1L,"conv",null,"continuation","claimed","lease",
                now.plusSeconds(60),null,null,"safe","claimed",null,null,null,null,now,now);
        claimed=new GoalRunCoordinator.ClaimedRun(candidate,goal,attempt,2);
        when(goals.getById(1L)).thenReturn(goal);
        when(store.due(any(), anyInt())).thenReturn(List.of(candidate));
        when(coordinator.claim(candidate,goal,now)).thenReturn(claimed);
        when(coordinator.markRunning(claimed,now)).thenReturn(true);
        when(coordinator.settle(eq(claimed),any(),any())).thenReturn(true);
        when(runner.run(eq(claimed),anyString(),anyBoolean())).thenReturn(new SegmentOutcome.Continue("normal"));
        supervisor = new GoalContinuationSupervisor(store, goals, properties,
                new GoalFollowupService(properties,new ObjectMapper()), runner, running, streams,coordinator,recovery,
                Clock.fixed(now.toInstant(ZoneOffset.UTC),ZoneOffset.UTC), Runnable::run);
    }

    @Test void incompleteGoalIsRescheduledAcrossMultipleSegments() {
        supervisor.tick(); supervisor.tick(); supervisor.tick();
        verify(runner,times(3)).run(eq(claimed),contains("full goal"),eq(false));
        verify(coordinator,times(3)).settle(eq(claimed),isA(SegmentOutcome.Continue.class),eq(now));
    }

    @Test void configuredConcurrencyLimitsSubmittedSegments() {
        properties.setMaxConcurrentSegments(1);
        GoalEntity second = new GoalEntity();
        second.setId(2L); second.setConversationId("conv-2"); second.setStatus(GoalStatus.ACTIVE);
        second.setPersistentExecution(true); second.setAutoFollowupEnabled(true);
        second.setTitle("second goal"); second.setTurnBudget(0); second.setLlmCallBudget(0);
        var secondCandidate = new GoalContinuationStore.Continuation(
                2L,"conv-2","queued",now,null,null,0,"",null,0);
        GoalAttempt secondAttempt = new GoalAttempt("attempt-2",2L,"conv-2",null,"continuation",
                "claimed","lease-2",now.plusSeconds(60),null,null,"safe","claimed",
                null,null,null,null,now,now);
        var secondClaimed = new GoalRunCoordinator.ClaimedRun(secondCandidate,second,secondAttempt,2);
        when(goals.getById(2L)).thenReturn(second);
        when(store.due(any(), anyInt())).thenReturn(List.of(candidate,secondCandidate));
        when(coordinator.claim(secondCandidate,second,now)).thenReturn(secondClaimed);
        List<Runnable> submitted = new ArrayList<>();
        supervisor = new GoalContinuationSupervisor(store, goals, properties,
                new GoalFollowupService(properties,new ObjectMapper()), runner, running, streams,
                coordinator,recovery,Clock.fixed(now.toInstant(ZoneOffset.UTC),ZoneOffset.UTC),submitted::add);

        supervisor.tick();

        assertEquals(1,submitted.size());
        verify(coordinator).claim(candidate,goal,now);
        verify(coordinator,never()).claim(secondCandidate,second,now);
    }

    @Test void busyFirstCandidateDoesNotStarveAnotherDueGoalAtConcurrencyOne() {
        properties.setMaxConcurrentSegments(1);
        GoalEntity second = new GoalEntity();
        second.setId(2L); second.setConversationId("conv-2"); second.setStatus(GoalStatus.ACTIVE);
        second.setPersistentExecution(true); second.setAutoFollowupEnabled(true);
        second.setTitle("second goal"); second.setTurnBudget(0); second.setLlmCallBudget(0);
        var secondCandidate = new GoalContinuationStore.Continuation(
                2L,"conv-2","queued",now,null,null,0,"",null,0);
        GoalAttempt secondAttempt = new GoalAttempt("attempt-2",2L,"conv-2",null,"continuation",
                "claimed","lease-2",now.plusSeconds(60),null,null,"safe","claimed",
                null,null,null,null,now,now);
        var secondClaimed = new GoalRunCoordinator.ClaimedRun(secondCandidate,second,secondAttempt,2);
        when(goals.getById(2L)).thenReturn(second);
        when(store.due(any(), anyInt())).thenAnswer(invocation -> {
            int limit=invocation.getArgument(1);
            return List.of(candidate,secondCandidate).subList(0,Math.min(limit,2));
        });
        when(running.isActive("conv")).thenReturn(true);
        when(coordinator.claim(secondCandidate,second,now)).thenReturn(secondClaimed);
        when(coordinator.markRunning(secondClaimed,now)).thenReturn(true);
        when(coordinator.settle(eq(secondClaimed),any(),any())).thenReturn(true);
        when(runner.run(eq(secondClaimed),anyString(),anyBoolean()))
                .thenReturn(new SegmentOutcome.Continue("normal"));

        supervisor.tick();

        verify(coordinator).claim(secondCandidate,second,now);
    }

    @Test void cooldownIsDurablyDeferredWithoutCallingModel() {
        goal.setFollowupCooldownSeconds(60); goal.setLastFollowupAt(now.minusSeconds(10));
        supervisor.tick();
        verifyNoInteractions(runner);
        verify(coordinator).settle(eq(claimed),argThat(outcome -> outcome instanceof SegmentOutcome.Defer defer
                && now.plusSeconds(50).equals(defer.nextRunAt())),eq(now));
    }

    @Test void neverStartsAlongsideUserTurnOrQueuedInput() {
        when(running.isActive("conv")).thenReturn(true);
        supervisor.tick();
        verify(coordinator,never()).claim(any(),any(),any());
        verifyNoInteractions(runner);
    }

    @Test void completionIsSettledThroughCoordinator() {
        when(runner.run(eq(claimed),anyString(),anyBoolean())).thenAnswer(inv -> {
            goal.setStatus(GoalStatus.COMPLETED); return new SegmentOutcome.Continue("normal");
        });
        supervisor.tick(); supervisor.tick();
        verify(runner).run(eq(claimed),anyString(),anyBoolean());
        verify(coordinator).settle(eq(claimed),isA(SegmentOutcome.Continue.class),eq(now));
    }

    @Test void shutdownCancellationLeavesLeaseForRestartRecovery() {
        when(runner.run(eq(claimed),anyString(),anyBoolean())).thenAnswer(inv -> {
            supervisor.close(); return new SegmentOutcome.Cancelled("stopped");
        });
        supervisor.tick();
        verify(runner).cancelAll();
        verify(coordinator,never()).settle(any(),any(),any());
    }

    @Test void transientFailureGetsRetryAndPermanentErrorBlocks() {
        properties.setProviderFailureGlobalBackoffSeconds(0);
        when(runner.run(eq(claimed),anyString(),anyBoolean()))
                .thenThrow(new java.io.UncheckedIOException(new java.io.IOException("connection reset")));
        supervisor.tick();
        verify(coordinator).settle(eq(claimed),isA(SegmentOutcome.Retry.class),eq(now));
        reset(runner,coordinator);
        when(coordinator.claim(candidate,goal,now)).thenReturn(claimed);
        when(coordinator.markRunning(claimed,now)).thenReturn(true);
        when(coordinator.settle(eq(claimed),any(),any())).thenReturn(true);
        doThrow(new IllegalArgumentException("invalid configuration")).when(runner)
                .run(eq(claimed),anyString(),anyBoolean());
        supervisor.tick();
        verify(coordinator).settle(eq(claimed),isA(SegmentOutcome.Blocked.class),eq(now));
    }

    @Test void retryableProviderFailureStopsNewClaimsDuringGlobalBackoff() {
        properties.setProviderFailureGlobalBackoffSeconds(300);
        when(runner.run(eq(claimed),anyString(),anyBoolean()))
                .thenThrow(new java.io.UncheckedIOException(new java.io.IOException("provider unavailable")));

        supervisor.tick();
        supervisor.tick();

        verify(coordinator,times(1)).claim(candidate,goal,now);
        verify(coordinator,times(1)).settle(eq(claimed),isA(SegmentOutcome.Retry.class),eq(now));
    }

    @Test void retryOutcomeFromUnavailableEvaluationAlsoStartsGlobalBackoff() {
        properties.setProviderFailureGlobalBackoffSeconds(300);
        when(runner.run(eq(claimed),anyString(),anyBoolean()))
                .thenReturn(new SegmentOutcome.Retry("evaluation","evaluation_unavailable"));

        supervisor.tick();
        supervisor.tick();

        verify(coordinator,times(1)).claim(candidate,goal,now);
    }

    @Test void approvalAndStopNeverBecomeAutomaticRetry() {
        when(runner.run(eq(claimed),anyString(),anyBoolean()))
                .thenReturn(new SegmentOutcome.AwaitApproval("approval_required"));
        supervisor.tick();
        verify(coordinator).settle(eq(claimed),isA(SegmentOutcome.AwaitApproval.class),eq(now));
        reset(runner,coordinator);
        when(coordinator.claim(candidate,goal,now)).thenReturn(claimed);
        when(coordinator.markRunning(claimed,now)).thenReturn(true);
        when(coordinator.settle(eq(claimed),any(),any())).thenReturn(true);
        when(runner.run(eq(claimed),anyString(),anyBoolean())).thenReturn(new SegmentOutcome.Cancelled("stopped"));
        supervisor.tick();
        verify(coordinator).settle(eq(claimed),isA(SegmentOutcome.Cancelled.class),eq(now));
    }
}
