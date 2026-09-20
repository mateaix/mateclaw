package vip.mate.team.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.runtime.ConversationTurnGate;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.approval.PendingApproval;
import vip.mate.approval.ResolveOutcome;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.workspace.conversation.ConversationService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamWorkerInterventionServiceTest {

    private final TeamTaskService taskService = mock(TeamTaskService.class);
    private final TeamWorkerConversationGovernanceService governance =
            mock(TeamWorkerConversationGovernanceService.class);
    private final ApprovalWorkflowService approvalService = mock(ApprovalWorkflowService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final ConversationService conversationService = mock(ConversationService.class);
    private final ChatStreamTracker streamTracker = mock(ChatStreamTracker.class);
    private final TeamDispatchService dispatchService = mock(TeamDispatchService.class);
    private final TeamAnnounceService announceService = mock(TeamAnnounceService.class);
    private final TeamEventChannel eventChannel = mock(TeamEventChannel.class);
    private final TeamWorkerReplayPersistenceService replayPersistenceService =
            mock(TeamWorkerReplayPersistenceService.class);
    private TeamWorkerInterventionService service;

    @BeforeEach
    void setUp() {
        service = new TeamWorkerInterventionService(taskService, governance, approvalService,
                agentService, conversationService, new ConversationTurnGate(), streamTracker,
                dispatchService, announceService, eventChannel, replayPersistenceService);
    }

    @Test
    void approvalReplaysInCanonicalConversationAndSettlesOriginalTask() {
        TeamTaskEntity task = task(TeamTaskStatus.AWAITING_APPROVAL);
        PendingApproval pending = pending("pending-42");
        when(taskService.getTask(101L)).thenReturn(task);
        when(governance.resolve("worker-101", 11L, 101L)).thenReturn(Optional.of(context()));
        when(approvalService.getPending("pending-42")).thenReturn(Optional.of(pending));
        when(approvalService.claimForReplay("pending-42", "alice"))
                .thenReturn(ResolveOutcome.resolved(pending, "approved", true, 1));
        when(approvalService.getReplayClaim("pending-42")).thenReturn(Optional.of(pending));
        when(approvalService.consumeReplayClaim("pending-42", "alice"))
                .thenReturn(ResolveOutcome.consumed(pending, true, 1));
        when(taskService.resumeAfterToolApproval(101L, "pending-42")).thenReturn(true);
        when(taskService.stageToolReplayResult(101L, "pending-42", "tool completed")).thenReturn(true);
        when(approvalService.restoreChatOrigin(null)).thenReturn(ChatOrigin.EMPTY);
        when(agentService.chatWithReplayWithUsage(eq(201L), any(), eq("worker-101"),
                eq("{\"name\":\"shell\"}"), eq(ChatOrigin.EMPTY.withApprovalId("pending-42"))))
                .thenReturn(AgentService.ChatResult.contentOnly("tool completed"));

        service.approve(7L, 101L, "pending-42", "alice");

        verify(conversationService).removeApprovalPlaceholders("worker-101");
        verify(replayPersistenceService).persist(101L, "pending-42", "worker-101",
                "tool completed", AgentService.ChatResult.contentOnly("tool completed"));
        verify(dispatchService).settleOutcome(task, "tool completed");
        verify(approvalService).consumeReplayClaim("pending-42", "alice");
    }

    @Test
    void canonicalLinkMismatchRejectsBeforeApprovalMutation() {
        TeamTaskEntity task = task(TeamTaskStatus.AWAITING_APPROVAL);
        when(taskService.getTask(101L)).thenReturn(task);
        when(governance.resolve("worker-101", 11L, 101L)).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.approve(7L, 101L, "pending-42", "alice"));

        assertTrue(error.getMessage().contains("worker conversation"));
        verify(approvalService, never()).consumeReplayClaim(any(), any());
    }

    @Test
    void denialSettlesWithoutExecutingTheTool() {
        TeamTaskEntity task = task(TeamTaskStatus.AWAITING_APPROVAL);
        PendingApproval pending = pending("pending-42");
        when(taskService.getTask(101L)).thenReturn(task);
        when(governance.resolve("worker-101", 11L, 101L)).thenReturn(Optional.of(context()));
        when(approvalService.getPending("pending-42")).thenReturn(Optional.of(pending));
        when(approvalService.resolve("pending-42", "alice", "denied"))
                .thenReturn(ResolveOutcome.resolved(pending, "denied", true, 1));
        when(taskService.denyToolApproval(101L, "pending-42", "alice")).thenReturn(true);

        service.deny(7L, 101L, "pending-42", "alice");

        verify(agentService, never()).chatWithReplayWithUsage(any(), any(), any(), any(), any());
        verify(taskService).denyToolApproval(101L, "pending-42", "alice");
        verify(announceService).announceTaskSettled(task);
    }

    @Test
    void feedbackContinuesOriginalConversationAndCannotBypassPendingApproval() {
        TeamTaskEntity completed = task(TeamTaskStatus.COMPLETED);
        when(taskService.getTask(101L)).thenReturn(completed);
        when(governance.resolve("worker-101", 11L, 101L)).thenReturn(Optional.of(context()));
        when(taskService.resumeForWorkerFeedback(101L)).thenReturn(true);
        when(agentService.chatWithUsage(eq(201L), eq("tighten the summary"), eq("worker-101"), any()))
                .thenReturn(AgentService.ChatResult.contentOnly("revised summary"));

        service.feedback(7L, 101L, "tighten the summary", "alice");

        verify(conversationService).saveMessage("worker-101", "user", "tighten the summary");
        verify(conversationService).saveMessage("worker-101", "assistant", "revised summary",
                null, "completed", 0, 0, null, null);
        verify(dispatchService).settleOutcome(completed, "revised summary");

        when(approvalService.findPendingByConversation("worker-101"))
                .thenReturn(pending("pending-next"));
        assertThrows(IllegalStateException.class,
                () -> service.feedback(7L, 101L, "run another command", "alice"));
    }

    @Test
    void replayFailureReparksApprovedPayloadForSafeRetry() {
        TeamTaskEntity task = task(TeamTaskStatus.AWAITING_APPROVAL);
        PendingApproval pending = pending("pending-42");
        when(taskService.getTask(101L)).thenReturn(task);
        when(governance.resolve("worker-101", 11L, 101L)).thenReturn(Optional.of(context()));
        when(approvalService.getPending("pending-42")).thenReturn(Optional.of(pending));
        when(approvalService.claimForReplay("pending-42", "alice"))
                .thenReturn(ResolveOutcome.resolved(pending, "approved", true, 1));
        when(approvalService.getReplayClaim("pending-42")).thenReturn(Optional.of(pending));
        when(taskService.resumeAfterToolApproval(101L, "pending-42")).thenReturn(true);
        when(approvalService.restoreChatOrigin(null)).thenReturn(ChatOrigin.EMPTY);
        when(agentService.chatWithReplayWithUsage(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("provider timeout"));

        assertThrows(IllegalStateException.class,
                () -> service.approve(7L, 101L, "pending-42", "alice"));

        verify(taskService).parkToolReplayUncertain(eq(101L), eq("pending-42"),
                org.mockito.ArgumentMatchers.contains("provider timeout"));
        verify(taskService, never()).failTask(eq(101L), any());
        verify(approvalService, never()).consumeReplayClaim(any(), any());
    }

    @Test
    void onlyTheInstanceHoldingTheTaskReplayLeaseExecutesTheTool() {
        TeamTaskEntity task = task(TeamTaskStatus.AWAITING_APPROVAL);
        PendingApproval pending = pending("pending-42");
        when(taskService.getTask(101L)).thenReturn(task);
        when(governance.resolve("worker-101", 11L, 101L)).thenReturn(Optional.of(context()));
        when(approvalService.getPending("pending-42")).thenReturn(Optional.of(pending));
        when(approvalService.claimForReplay("pending-42", "alice"))
                .thenReturn(ResolveOutcome.resolved(pending, "approved", true, 1));
        when(approvalService.getReplayClaim("pending-42")).thenReturn(Optional.of(pending));
        when(taskService.resumeAfterToolApproval(101L, "pending-42")).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> service.approve(7L, 101L, "pending-42", "alice"));

        verify(agentService, never()).chatWithReplayWithUsage(any(), any(), any(), any(), any());
    }

    @Test
    void uncertainReplayOutcomeCannotBeExecutedAgainAutomatically() {
        TeamTaskEntity task = task(TeamTaskStatus.AWAITING_APPROVAL);
        when(taskService.getTask(101L)).thenReturn(task);
        when(governance.resolve("worker-101", 11L, 101L)).thenReturn(Optional.of(context()));
        when(taskService.isToolReplayOutcomeUncertain(task)).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> service.approve(7L, 101L, "pending-42", "alice"));

        verify(approvalService, never()).claimForReplay(any(), any());
        verify(agentService, never()).chatWithReplayWithUsage(any(), any(), any(), any(), any());
    }

    @Test
    void stagedReplayFinalizationDoesNotExecuteToolAgain() {
        TeamTaskEntity task = task(TeamTaskStatus.AWAITING_APPROVAL);
        task.setMetadata("{\"toolApproval\":{\"pendingId\":\"pending-42\","
                + "\"replayResult\":\"tool completed\",\"messagePersisted\":true}}");
        PendingApproval pending = pending("pending-42");
        when(taskService.getTask(101L)).thenReturn(task);
        when(governance.resolve("worker-101", 11L, 101L)).thenReturn(Optional.of(context()));
        when(approvalService.getPending("pending-42")).thenReturn(Optional.of(pending));
        when(taskService.stagedToolReplayResult(task)).thenReturn("tool completed");
        when(taskService.isToolReplayMessagePersisted(task)).thenReturn(true);
        when(approvalService.consumeReplayClaim("pending-42", "alice"))
                .thenReturn(ResolveOutcome.consumed(pending, true, 1));
        when(taskService.resumeAfterToolApproval(101L, "pending-42")).thenReturn(true);

        service.approve(7L, 101L, "pending-42", "alice");

        verify(agentService, never()).chatWithReplayWithUsage(any(), any(), any(), any(), any());
        verify(conversationService, never()).removeApprovalPlaceholders(anyString());
        verify(dispatchService).settleOutcome(task, "tool completed");
    }

    @Test
    void stagedReplayCannotBeReportedAsDeniedAfterToolExecution() {
        TeamTaskEntity task = task(TeamTaskStatus.AWAITING_APPROVAL);
        task.setMetadata("{\"toolApproval\":{\"pendingId\":\"pending-42\","
                + "\"replayResult\":\"tool completed\"}}");
        when(taskService.getTask(101L)).thenReturn(task);
        when(governance.resolve("worker-101", 11L, 101L)).thenReturn(Optional.of(context()));
        when(taskService.stagedToolReplayResult(task)).thenReturn("tool completed");

        assertThrows(IllegalStateException.class,
                () -> service.deny(7L, 101L, "pending-42", "alice"));

        verify(approvalService, never()).resolve(any(), any(), anyString());
        verify(taskService, never()).denyToolApproval(any(), any(), any());
    }

    @Test
    void claimedReplayCanBeStoppedAfterExecutionFailure() {
        TeamTaskEntity task = task(TeamTaskStatus.AWAITING_APPROVAL);
        PendingApproval claimed = pending("pending-42");
        claimed.setStatus("approved");
        when(taskService.getTask(101L)).thenReturn(task);
        when(governance.resolve("worker-101", 11L, 101L)).thenReturn(Optional.of(context()));
        when(approvalService.getPending("pending-42")).thenReturn(Optional.of(claimed));
        when(approvalService.consumeReplayClaim("pending-42", "alice"))
                .thenReturn(ResolveOutcome.consumed(claimed, true, 1));
        when(taskService.abortClaimedToolReplay(101L, "pending-42", "alice"))
                .thenReturn(true);

        service.deny(7L, 101L, "pending-42", "alice");

        verify(taskService).abortClaimedToolReplay(101L, "pending-42", "alice");
        verify(taskService, never()).denyToolApproval(any(), any(), any());
        verify(eventChannel).publishTaskEvent(task, "team_task_tool_replay_aborted",
                java.util.Map.of("pendingId", "pending-42"));
    }

    @Test
    void duplicateDecisionReturnsCurrentTaskProjection() {
        TeamTaskEntity completed = task(TeamTaskStatus.COMPLETED);
        when(taskService.getTask(101L)).thenReturn(completed);
        when(governance.resolve("worker-101", 11L, 101L)).thenReturn(Optional.of(context()));

        service.approve(7L, 101L, "pending-42", "alice");
        service.deny(7L, 101L, "pending-42", "alice");

        verify(approvalService, never()).getPending(any());
    }

    private static TeamTaskEntity task(String status) {
        TeamTaskEntity task = new TeamTaskEntity();
        task.setId(101L);
        task.setTeamId(7L);
        task.setRunId(11L);
        task.setTaskNumber(3);
        task.setStatus(status);
        task.setAssigneeAgentId(201L);
        task.setConversationId("worker-101");
        task.setMetadata("{\"toolApproval\":{\"pendingId\":\"pending-42\"}}");
        return task;
    }

    private static TeamWorkerConversationContext context() {
        return new TeamWorkerConversationContext(true, "team_worker", "worker-101",
                11L, 101L, 7L, "lead-11", 201L);
    }

    private static PendingApproval pending(String id) {
        PendingApproval pending = new PendingApproval(id, "worker-101", "owner",
                "shell", "{}", "needs approval");
        pending.setAgentId("201");
        pending.setToolCallPayload("{\"name\":\"shell\"}");
        return pending;
    }
}
