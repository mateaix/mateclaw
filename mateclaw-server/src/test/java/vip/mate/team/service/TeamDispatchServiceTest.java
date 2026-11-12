package vip.mate.team.service;

import vip.mate.decision.api.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vip.mate.agent.AgentService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.approval.PendingApproval;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.team.model.TeamTaskCommentEntity;
import vip.mate.team.model.TeamTaskStatus;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;

/**
 * Pins the dispatch loop's arbitration rules: one task per member per sweep,
 * busy members skipped, the conditional assign as the only winner gate, the
 * circuit breaker short-circuit, and outcome settling (auto-complete vs
 * respecting a state the member already set).
 */
class TeamDispatchServiceTest {

    private static final Long TEAM_ID = 10L;
    private static final Long WORKSPACE_ID = 77L;
    private static final Long MEMBER_A = 2L;
    private static final Long MEMBER_B = 3L;

    private TeamService teamService;
    private TeamTaskService taskService;
    private AgentService agentService;
    private ConversationService conversationService;
    private ChatStreamTracker streamTracker;
    private TeamAnnounceService announceService;
    private TeamEventChannel eventChannel;
    private ApprovalWorkflowService approvalService;
    private TeamDispatchService service;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        taskService = mock(TeamTaskService.class);
        agentService = mock(AgentService.class);
        conversationService = mock(ConversationService.class);
        streamTracker = mock(ChatStreamTracker.class);
        announceService = mock(TeamAnnounceService.class);
        eventChannel = mock(TeamEventChannel.class);
        approvalService = mock(ApprovalWorkflowService.class);
        service = new TeamDispatchService(teamService, taskService, agentService,
                conversationService, streamTracker, announceService, eventChannel, approvalService);
    }

    private TeamTaskEntity task(Long id, Long assignee) {
        TeamTaskEntity t = new TeamTaskEntity();
        t.setId(id);
        t.setTeamId(TEAM_ID);
        t.setTaskNumber(id.intValue());
        t.setSubject("task " + id);
        t.setStatus(TeamTaskStatus.PENDING);
        t.setAssigneeAgentId(assignee);
        t.setLeadConversationId("lead-conv");
        return t;
    }

    @Test
    void structuredBlockedResultFailsWithoutRetryOrReleasingDependents() {
        TeamTaskEntity running = task(71L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        running.setRequireApproval(true);
        when(taskService.getTask(71L)).thenReturn(running);
        var adapter = mock(WorkerDecisionAdapter.class);
        when(adapter.enabled()).thenReturn(true);
        var ticket = new DecisionTicket("blocked", DecisionMode.SHADOW, new DecisionValue.BooleanValue(false));
        when(adapter.judge(any(), anyString(), anyString()))
                .thenReturn(new WorkerDecisionAdapter.Judgment(ticket, false));
        service.setDecisionAdapter(adapter);
        String reply = "{\"status\":\"BLOCKED\",\"result\":\"\",\"evidence\":\"Missing total count\"}";
        service.settleOutcome(running, reply);
        verify(taskService).failTask(eq(71L), startsWith("BLOCKED:"), eq(ticket), any());
        verify(taskService, never()).completeTask(any(), any(), anyString(), any(), any());
        verify(taskService, never()).requeueUnusableResult(any(), anyString());
        verify(adapter).judge(running, "BLOCKED", reply);
    }

    @Test
    void enabledWorkerEnvelopeRequiresStructuredCompletionEvenWithoutTools() {
        var adapter = mock(WorkerDecisionAdapter.class);
        when(adapter.enabled()).thenReturn(true);
        service.setDecisionAdapter(adapter);
        String prompt = service.buildDispatchContent(task(72L, MEMBER_A));
        assertTrue(prompt.contains("COMPLETED|BLOCKED|FAILED"));
        assertTrue(prompt.contains("evidence"));
    }

    // ==================== sweep arbitration ====================

    @Test
    @DisplayName("one task per assignee per sweep — the second task for the same member waits")
    void onePerAssigneePerSweep() {
        TeamTaskEntity first = task(1L, MEMBER_A);
        TeamTaskEntity second = task(2L, MEMBER_A);
        TeamTaskEntity other = task(3L, MEMBER_B);
        // Second stub is empty so the async post-run re-sweep chain terminates.
        when(taskService.findDispatchable(TEAM_ID))
                .thenReturn(List.of(first, second, other))
                .thenReturn(List.of());
        when(taskService.hasActiveTask(eq(TEAM_ID), any())).thenReturn(false);
        when(taskService.assignTask(any(), any())).thenReturn(true);
        when(taskService.tryAcquireDispatch(any())).thenReturn(true);
        when(taskService.getTask(any())).thenAnswer(inv ->
                task(inv.getArgument(0), MEMBER_A));

        service.sweep(TEAM_ID);

        verify(taskService).assignTask(1L, MEMBER_A);
        verify(taskService, never()).assignTask(eq(2L), any());
        verify(taskService).assignTask(3L, MEMBER_B);
    }

    @Test
    @DisplayName("a member already executing a task is skipped entirely")
    void busyMemberSkipped() {
        when(taskService.findDispatchable(TEAM_ID)).thenReturn(List.of(task(1L, MEMBER_A)));
        when(taskService.hasActiveTask(TEAM_ID, MEMBER_A)).thenReturn(true);

        service.sweep(TEAM_ID);

        verify(taskService, never()).assignTask(any(), any());
    }

    @Test
    @DisplayName("losing the conditional assign means another sweep won — no dispatch")
    void assignRaceLostSkips() {
        when(taskService.findDispatchable(TEAM_ID)).thenReturn(List.of(task(1L, MEMBER_A)));
        when(taskService.hasActiveTask(TEAM_ID, MEMBER_A)).thenReturn(false);
        when(taskService.assignTask(1L, MEMBER_A)).thenReturn(false);

        service.sweep(TEAM_ID);

        verify(taskService, never()).tryAcquireDispatch(any());
    }

    @Test
    @DisplayName("a tripped circuit breaker skips the run but announces the auto-fail to the lead")
    void breakerStopsDispatch() {
        when(taskService.findDispatchable(TEAM_ID)).thenReturn(List.of(task(1L, MEMBER_A)));
        when(taskService.hasActiveTask(TEAM_ID, MEMBER_A)).thenReturn(false);
        when(taskService.assignTask(1L, MEMBER_A)).thenReturn(true);
        when(taskService.tryAcquireDispatch(1L)).thenReturn(false);
        TeamTaskEntity failed = task(1L, MEMBER_A);
        failed.setStatus(TeamTaskStatus.FAILED);
        when(taskService.getTask(1L)).thenReturn(failed);

        service.sweep(TEAM_ID);

        // The work must not vanish silently: the lead hears about the auto-fail.
        verify(announceService).announceTaskSettled(failed);
        verifyNoInteractions(agentService);
    }

    @Test
    void activeSemanticRejectionFailsWithoutAnotherAttempt() {
        var adapter = mock(WorkerDecisionAdapter.class); service.setDecisionAdapter(adapter); when(adapter.enabled()).thenReturn(true);
        when(adapter.active()).thenReturn(true);
        var running = WorkerDecisionAdapterTest.task();
        var snapshot = WorkerDecisionAdapter.Snapshot.capture(running);
        var ticket = new DecisionTicket("worker", DecisionMode.ACTIVE, new DecisionValue.BooleanValue(false));
        when(taskService.getTask(1L)).thenReturn(running);
        when(adapter.judge(running, "VALID", "analysis finished"))
                .thenReturn(new WorkerDecisionAdapter.Judgment(ticket, false));
        service.settleOutcome(running, "{\"status\":\"COMPLETED\",\"result\":\"analysis finished\",\"evidence\":\"measured\"}");
        verify(taskService).failTask(1L, "worker result did not satisfy task requirements", ticket, snapshot);
        verify(taskService, never()).requeueUnusableResult(any(), anyString(), any(), any());
        verify(taskService, never()).completeTask(any(), any(), anyString(), any(), any());
    }

    @Test
    void staleActiveReplyDoesNotAttachArtifactOrCompleteNewAttempt() {
        var adapter = mock(WorkerDecisionAdapter.class); service.setDecisionAdapter(adapter); when(adapter.enabled()).thenReturn(true);
        when(adapter.active()).thenReturn(true);
        var original = WorkerDecisionAdapterTest.task();
        var replacement = WorkerDecisionAdapterTest.task(); replacement.setDispatchCount(2);
        when(taskService.getTask(1L)).thenReturn(replacement);
        var ticket = new DecisionTicket("worker", DecisionMode.ACTIVE, new DecisionValue.BooleanValue(false));
        when(adapter.judge(eq(original), eq("STALE_ATTEMPT"), isNull()))
                .thenReturn(new WorkerDecisionAdapter.Judgment(ticket, false));
        service.settleOutcome(original, "[file](/api/v1/files/generated/abc)");
        verify(adapter).outcome(ticket, false, "STALE_ATTEMPT");
        verify(taskService, never()).addDeliverable(any(), any(), anyString(), anyString());
        verify(taskService, never()).addWorkerDeliverable(any(), any(), anyString(), anyString());
        verify(taskService, never()).completeTask(any(), any(), anyString(), any(), any());
    }

    // ==================== outcome settling ====================

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "stale", "pending", "blocked", "awaiting_approval"})
    void normalWorkerReturnDoesNotAnnounceUnsettledOrCancelledTask(String status) {
        TeamTaskEntity current = task(1L, MEMBER_A);
        current.setStatus(status);
        when(taskService.getTask(1L)).thenReturn(current);

        service.settleOutcome(current, "late worker reply");

        verifyNoInteractions(eventChannel, announceService);
        verify(taskService, never()).completeTask(any(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"incomplete", "error_fallback", "max_iterations_reached", "stopped", "evidence_insufficient", "action_unverified", "action_failed"})
    void incompleteRuntimeIsNotMisreportedAsInvalidWorkerJson(String finishReason) {
        var adapter = mock(WorkerDecisionAdapter.class);
        when(adapter.enabled()).thenReturn(true);
        var ticket = new DecisionTicket(null, DecisionMode.SHADOW, new DecisionValue.BooleanValue(false));
        when(adapter.judge(any(), anyString(), anyString())).thenReturn(new WorkerDecisionAdapter.Judgment(ticket, false));
        service.setDecisionAdapter(adapter);
        TeamTaskEntity assigned = task(1L, MEMBER_A); assigned.setStatus(TeamTaskStatus.IN_PROGRESS);
        when(taskService.getTask(1L)).thenReturn(assigned);
        when(agentService.chatWithUsage(eq(MEMBER_A), anyString(), anyString())).thenReturn(
            new AgentService.ChatResult("任务尚未完成：文字修正已达上限", 10, 5, "model", "provider", finishReason));
        service.runTask(TEAM_ID, assigned);
        verify(adapter).judge(eq(assigned), eq("WORKER_RUNTIME_NOT_COMPLETED"), anyString());
        verify(taskService).failTask(eq(1L), startsWith("WORKER_RUNTIME_NOT_COMPLETED:"), eq(ticket), any());
        verify(taskService, never()).completeTask(any(), any(), anyString(), any(), any());
        verify(adapter, never()).judge(any(), eq("INVALID_RESULT_CONTRACT"), anyString());
    }

    @Test
    void validJsonCannotOverrideAnIncompleteRuntimeEvenWhenDecisionEnvelopeIsDisabled() {
        TeamTaskEntity assigned = task(1L, MEMBER_A); assigned.setStatus(TeamTaskStatus.IN_PROGRESS);
        when(taskService.getTask(1L)).thenReturn(assigned);
        when(agentService.chatWithUsage(eq(MEMBER_A), anyString(), anyString())).thenReturn(
            new AgentService.ChatResult("{\"status\":\"COMPLETED\",\"result\":\"done\",\"evidence\":\"checked\"}",
                10, 5, "model", "provider", "incomplete"));
        service.runTask(TEAM_ID, assigned);
        verify(taskService).failTask(eq(1L), startsWith("WORKER_RUNTIME_NOT_COMPLETED:"));
        verify(taskService, never()).completeTask(any(), any(), anyString());
    }

    @Test
    void childStreamClosesBeforeLeadReceivesSuccessfulResult() {
        TeamTaskEntity assigned = task(1L, MEMBER_A);
        assigned.setStatus(TeamTaskStatus.IN_PROGRESS);
        TeamTaskEntity done = task(1L, MEMBER_A);
        done.setStatus(TeamTaskStatus.COMPLETED);
        when(taskService.getTask(1L)).thenReturn(assigned, done, done);
        when(taskService.completeTask(eq(1L), isNull(), anyString())).thenReturn(List.of());
        when(agentService.chatWithUsage(eq(MEMBER_A), anyString(), anyString()))
                .thenReturn(AgentService.ChatResult.contentOnly("all done"));

        service.runTask(TEAM_ID, assigned);

        var order = inOrder(conversationService, streamTracker, eventChannel, announceService);
        order.verify(conversationService).saveMessage(anyString(), eq("assistant"), eq("all done"));
        order.verify(streamTracker).complete(startsWith("team-task-"));
        order.verify(eventChannel).publishTaskEvent(any(), eq("team_task_completed"), any());
        order.verify(announceService).announceTaskSettled(done);
    }

    @Test
    void childStreamClosesBeforeLeadReceivesFailedResult() {
        TeamTaskEntity assigned = task(1L, MEMBER_A);
        assigned.setStatus(TeamTaskStatus.IN_PROGRESS);
        TeamTaskEntity failed = task(1L, MEMBER_A);
        failed.setStatus(TeamTaskStatus.FAILED);
        when(agentService.chatWithUsage(eq(MEMBER_A), anyString(), anyString()))
                .thenThrow(new IllegalStateException("model unavailable"));
        when(taskService.failTask(eq(1L), anyString())).thenReturn(true);
        when(taskService.getTask(1L)).thenReturn(failed);

        service.runTask(TEAM_ID, assigned);

        var order = inOrder(streamTracker, eventChannel, announceService);
        order.verify(streamTracker).complete(startsWith("team-task-"));
        order.verify(eventChannel).publishTaskEvent(any(), eq("team_task_failed"), any());
        order.verify(announceService).announceTaskSettled(failed);
    }

    @Test
    @DisplayName("an in_progress task is auto-completed with the member's final reply")
    void settleAutoCompletes() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        TeamTaskEntity done = task(1L, MEMBER_A);
        done.setStatus(TeamTaskStatus.COMPLETED);
        done.setResult("analysis finished");
        when(taskService.getTask(1L)).thenReturn(running, done);
        when(taskService.completeTask(eq(1L), isNull(), anyString())).thenReturn(List.of());

        service.settleOutcome(running, "analysis finished");

        verify(taskService).completeTask(1L, null, "analysis finished");
        verify(eventChannel).publishTaskEvent(any(), eq("team_task_completed"), any());
        verify(announceService).announceTaskSettled(done);
    }

    @Test
    @DisplayName("a fallback final answer is requeued instead of being reported as completed")
    void settleFallbackRequeues() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        running.setDispatchCount(1);
        when(taskService.getTask(1L)).thenReturn(running);
        when(taskService.requeueUnusableResult(1L, "member response generation failed"))
                .thenReturn(true);

        service.settleOutcome(running,
                "I inspected the task. Failed to generate a response, please retry.");

        verify(taskService).requeueUnusableResult(1L, "member response generation failed");
        verify(taskService, never()).completeTask(any(), any(), anyString());
        verify(announceService, never()).announceTaskSettled(any());
        verify(eventChannel).publishTaskEvent(any(), eq("team_task_retrying"), any());
    }

    @Test
    @DisplayName("a clarifying question is requeued instead of being reported as completed")
    void settleClarifyingQuestionRequeues() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        running.setDispatchCount(1);
        when(taskService.getTask(1L)).thenReturn(running);
        when(taskService.requeueUnusableResult(1L,
                "member asked for clarification instead of producing a result"))
                .thenReturn(true);

        service.settleOutcome(running, "您希望我如何处理？");

        verify(taskService).requeueUnusableResult(1L,
                "member asked for clarification instead of producing a result");
        verify(taskService, never()).completeTask(any(), any(), anyString());
        verify(announceService, never()).announceTaskSettled(any());
        verify(eventChannel).publishTaskEvent(any(), eq("team_task_retrying"), any());
    }

    @Test
    @DisplayName("an unusable third result fails instead of bypassing the circuit breaker")
    void settleFallbackFailsAfterDispatchBudget() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        running.setDispatchCount(TeamTaskService.MAX_DISPATCHES);
        TeamTaskEntity failed = task(1L, MEMBER_A);
        failed.setStatus(TeamTaskStatus.FAILED);
        failed.setReason("member response generation failed");
        when(taskService.getTask(1L)).thenReturn(running, failed);
        when(taskService.failTask(1L, "member response generation failed")).thenReturn(true);

        service.settleOutcome(running, "Failed to generate a response, please retry.");

        verify(taskService, never()).requeueUnusableResult(any(), anyString());
        verify(taskService).failTask(1L, "member response generation failed");
        verify(eventChannel).publishTaskEvent(any(), eq("team_task_failed"), any());
        verify(announceService).announceTaskSettled(failed);
    }

    @Test
    @DisplayName("a second empty member result fails without starting a third long run")
    void settleEmptyResultFailsAfterSingleRecoveryAttempt() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        running.setDispatchCount(TeamDispatchService.MAX_RESPONSE_FAILURE_DISPATCHES);
        TeamTaskEntity failed = task(1L, MEMBER_A);
        failed.setStatus(TeamTaskStatus.FAILED);
        failed.setReason("member produced no result");
        when(taskService.getTask(1L)).thenReturn(running, failed);
        when(taskService.failTask(1L, "member produced no result")).thenReturn(true);

        service.settleOutcome(running, "");

        verify(taskService, never()).requeueUnusableResult(any(), anyString());
        verify(taskService).failTask(1L, "member produced no result");
        verify(eventChannel).publishTaskEvent(any(), eq("team_task_failed"), any());
        verify(announceService).announceTaskSettled(failed);
    }

    @Test
    @DisplayName("automatic retry tells the worker why its previous attempt was rejected")
    void retryDispatchContentIncludesPreviousFailure() {
        TeamTaskEntity retry = task(1L, MEMBER_A);
        retry.setDispatchCount(2);
        retry.setReason("member produced no result");

        String content = service.buildDispatchContent(retry);

        assertTrue(content.contains("[Retry feedback]"));
        assertTrue(content.contains("member produced no result"));
        assertTrue(content.contains("do not repeat the same empty or fallback response"));
    }

    @Test
    @DisplayName("a declared deliverable task without an attachment is requeued")
    void settleMissingDeliverableRequeues() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        running.setDispatchCount(1);
        running.setMetadata("{\"deliverableRequired\":true}");
        when(taskService.getTask(1L)).thenReturn(running);
        when(taskService.listDeliverables(running)).thenReturn(List.of());
        when(taskService.requeueUnusableResult(1L, "required deliverable was not attached"))
                .thenReturn(true);

        service.settleOutcome(running, "handbook completed");

        verify(taskService).requeueUnusableResult(1L, "required deliverable was not attached");
        verify(taskService, never()).completeTask(any(), any(), anyString());
    }

    @Test
    @DisplayName("a returnDirect generated-file link satisfies a declared deliverable task")
    void settleGeneratedFileLinkAttachesDeliverable() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        running.setMetadata("{\"deliverableRequired\":true}");
        TeamTaskEntity completed = task(1L, MEMBER_A);
        completed.setStatus(TeamTaskStatus.COMPLETED);
        when(taskService.getTask(1L)).thenReturn(running, completed);
        when(taskService.listDeliverables(running)).thenReturn(List.of());
        when(taskService.completeTask(eq(1L), isNull(), anyString())).thenReturn(List.of());

        String reply = "文档已生成：[report.docx](/api/v1/files/generated/file-123)（链接 7 天内有效）。";
        service.settleOutcome(running, reply);

        verify(taskService).addDeliverable(1L, MEMBER_A, "report.docx",
                "/api/v1/files/generated/file-123");
        verify(taskService, never()).requeueUnusableResult(any(), anyString());
        verify(taskService).completeTask(1L, null, reply);
        verify(announceService).announceTaskSettled(completed);
    }

    @Test
    @DisplayName("a long-running checkpoint tracker stays active until its terminal round")
    void settleParksCheckpointTracker() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        running.setProgressPercent(1);
        when(taskService.getTask(1L)).thenReturn(running);
        when(taskService.checkpointTerminalTag(running)).thenReturn("R300");

        service.settleOutcome(running, "R001 tracker initialized");

        verify(taskService).updateProgress(1L, null, 1,
                "waiting for R300 checkpoint");
        verify(taskService, never()).completeTask(any(), any(), anyString());
        verify(announceService, never()).announceTaskSettled(any());
    }

    @Test
    @DisplayName("a tracker initialized after its terminal checkpoint completes immediately")
    void settleCompletesTrackerWhenTerminalEvidenceAlreadyExists() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        TeamTaskEntity completed = task(1L, MEMBER_A);
        completed.setStatus(TeamTaskStatus.COMPLETED);
        TeamTaskCommentEntity evidence = new TeamTaskCommentEntity();
        evidence.setContent("运行台账终点: [checkpoint:R300] acknowledged");
        when(taskService.getTask(1L)).thenReturn(running, completed);
        when(taskService.checkpointTerminalTag(running)).thenReturn("R300");
        when(taskService.listComments(1L)).thenReturn(List.of(evidence));
        when(taskService.completeTask(1L, null, "tracker initialized")).thenReturn(List.of());

        service.settleOutcome(running, "tracker initialized");

        verify(taskService).completeTask(1L, null, "tracker initialized");
        verify(announceService).announceTaskSettled(completed);
    }

    @Test
    @DisplayName("a task the member already failed via blocker is not completed on top")
    void settleRespectsMemberFailure() {
        TeamTaskEntity failed = task(1L, MEMBER_A);
        failed.setStatus(TeamTaskStatus.FAILED);
        failed.setReason("blocked: missing docs");
        when(taskService.getTask(1L)).thenReturn(failed);

        service.settleOutcome(failed, "irrelevant reply");

        verify(taskService, never()).completeTask(any(), any(), anyString());
        verify(eventChannel).publishTaskEvent(any(), eq("team_task_failed"), any());
    }

    // ==================== run tracking & interrupt ====================

    @Test
    @DisplayName("a member run is registered with the stream tracker and completed afterwards")
    void runTaskTracksChildConversation() {
        TeamTaskEntity assigned = task(1L, MEMBER_A);
        assigned.setStatus(TeamTaskStatus.IN_PROGRESS);
        TeamTaskEntity done = task(1L, MEMBER_A);
        done.setStatus(TeamTaskStatus.COMPLETED);
        when(taskService.getTask(1L)).thenReturn(assigned, done, done);
        when(taskService.completeTask(eq(1L), isNull(), anyString())).thenReturn(List.of());
        when(agentService.chatWithUsage(eq(MEMBER_A), anyString(), anyString()))
                .thenReturn(AgentService.ChatResult.contentOnly("all done"));

        service.runTask(TEAM_ID, assigned);

        // The child run must be trackable so requestStop() can interrupt it.
        verify(streamTracker).register(startsWith("team-task-"));
        verify(streamTracker).incrementFlux(startsWith("team-task-"));
        verify(streamTracker).complete(startsWith("team-task-"));
        // Both sides of the run persist, so the task card's transcript view has content.
        verify(conversationService).saveMessage(startsWith("team-task-"), eq("user"), anyString());
        verify(conversationService).saveMessage(startsWith("team-task-"), eq("assistant"), eq("all done"));
    }

    @Test
    @DisplayName("a worker tool approval parks the task instead of completing or retrying it")
    void runTaskParksPendingToolApproval() {
        TeamTaskEntity assigned = task(1L, MEMBER_A);
        assigned.setStatus(TeamTaskStatus.IN_PROGRESS);
        PendingApproval pending = new PendingApproval("pending-42", "worker", "system",
                "execute_shell_command", "{}", "shell command requires approval");
        pending.setSummary("shell command requires approval");
        when(agentService.chatWithUsage(eq(MEMBER_A), anyString(), anyString()))
                .thenReturn(AgentService.ChatResult.contentOnly("I need permission first."));
        when(approvalService.findPendingByConversation(startsWith("team-task-"))).thenReturn(pending);
        when(taskService.parkForToolApproval(1L, "pending-42", "shell command requires approval"))
                .thenReturn(true);

        service.runTask(TEAM_ID, assigned);

        verify(taskService).parkForToolApproval(1L, "pending-42", "shell command requires approval");
        verify(taskService, never()).completeTask(any(), any(), anyString());
        verify(taskService, never()).requeueUnusableResult(any(), anyString());
        verify(eventChannel).publishTaskEvent(any(), eq("team_task_awaiting_approval"), any());
    }

    @Test
    @DisplayName("member child conversation inherits the team's workspace")
    void runTaskCreatesChildConversationInTeamWorkspace() {
        AgentTeamEntity team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setWorkspaceId(WORKSPACE_ID);
        when(teamService.getTeam(TEAM_ID)).thenReturn(team);

        TeamTaskEntity assigned = task(1L, MEMBER_A);
        assigned.setStatus(TeamTaskStatus.IN_PROGRESS);
        TeamTaskEntity done = task(1L, MEMBER_A);
        done.setStatus(TeamTaskStatus.COMPLETED);
        when(taskService.getTask(1L)).thenReturn(assigned, done, done);
        when(taskService.completeTask(eq(1L), isNull(), anyString())).thenReturn(List.of());
        when(agentService.chatWithUsage(eq(MEMBER_A), anyString(), anyString()))
                .thenReturn(AgentService.ChatResult.contentOnly("all done"));

        service.runTask(TEAM_ID, assigned);

        verify(conversationService).createChildConversation(
                startsWith("team-task-"),
                eq(MEMBER_A),
                eq("system"),
                eq(WORKSPACE_ID),
                eq("lead-conv"),
                eq("team_worker"));
    }

    @Test
    @DisplayName("member dispatch envelope carries lead conversation upload paths")
    void runTaskCarriesLeadConversationUploadPaths() {
        TeamTaskEntity assigned = task(1L, MEMBER_A);
        assigned.setDescription("请根据刚上传的需求.docx拆解任务");
        assigned.setStatus(TeamTaskStatus.IN_PROGRESS);
        TeamTaskEntity done = task(1L, MEMBER_A);
        done.setStatus(TeamTaskStatus.COMPLETED);
        MessageEntity uploadTurn = new MessageEntity();
        uploadTurn.setRole("user");
        uploadTurn.setContentParts("[{\"type\":\"file\"}]");
        when(conversationService.listRecentMessages("lead-conv", TeamDispatchService.LEAD_ATTACHMENT_CONTEXT_MESSAGES))
                .thenReturn(List.of(uploadTurn));
        when(conversationService.renderMessageContent(uploadTurn, true))
                .thenReturn("请看附件\n[附件] 需求.docx（路径: /workspace/uploads/lead-conv/需求.docx）");
        when(taskService.getTask(1L)).thenReturn(assigned, done, done);
        when(taskService.completeTask(eq(1L), isNull(), anyString())).thenReturn(List.of());
        when(agentService.chatWithUsage(eq(MEMBER_A), anyString(), anyString()))
                .thenReturn(AgentService.ChatResult.contentOnly("all done"));

        service.runTask(TEAM_ID, assigned);

        var prompt = forClass(String.class);
        verify(agentService).chatWithUsage(eq(MEMBER_A), prompt.capture(), startsWith("team-task-"));
        assertTrue(prompt.getValue().contains("[Lead conversation attachments]"));
        assertTrue(prompt.getValue().contains("需求.docx"));
        assertTrue(prompt.getValue().contains("/workspace/uploads/lead-conv/需求.docx"));
    }

    @Test
    @DisplayName("an interrupted run whose task was cancelled produces no failed event")
    void interruptedCancelledRunStaysSilent() {
        TeamTaskEntity assigned = task(1L, MEMBER_A);
        assigned.setStatus(TeamTaskStatus.IN_PROGRESS);
        when(agentService.chatWithUsage(eq(MEMBER_A), anyString(), anyString()))
                .thenThrow(new RuntimeException("run interrupted"));
        // The guarded transition refuses: the task is already terminal (cancelled).
        when(taskService.failTask(eq(1L), anyString())).thenReturn(false);

        service.runTask(TEAM_ID, assigned);

        verify(eventChannel, never())
                .publishTaskEvent(any(), eq("team_task_failed"), any());
        verify(announceService, never()).announceTaskSettled(any());
        // Tracking still ends cleanly.
        verify(streamTracker).complete(startsWith("team-task-"));
    }

    @Test
    @DisplayName("a genuine member run error still fails the task and notifies the lead")
    void genuineRunErrorStillAnnounced() {
        TeamTaskEntity assigned = task(1L, MEMBER_A);
        assigned.setStatus(TeamTaskStatus.IN_PROGRESS);
        when(agentService.chatWithUsage(eq(MEMBER_A), anyString(), anyString()))
                .thenThrow(new RuntimeException("model unavailable"));
        when(taskService.failTask(eq(1L), anyString())).thenReturn(true);
        TeamTaskEntity failed = task(1L, MEMBER_A);
        failed.setStatus(TeamTaskStatus.FAILED);
        when(taskService.getTask(1L)).thenReturn(failed);

        service.runTask(TEAM_ID, assigned);

        verify(eventChannel).publishTaskEvent(any(), eq("team_task_failed"), any());
        verify(announceService).announceTaskSettled(failed);
    }

    @Test
    @DisplayName("interruptRun stops the attached member conversation and tolerates idle tasks")
    void interruptRunStopsAttachedConversation() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setConversationId("team-task-abc");
        when(streamTracker.requestStop("team-task-abc")).thenReturn(true);

        service.interruptRun(running);
        verify(streamTracker).requestStop("team-task-abc");

        // Never-dispatched task and null task are silent no-ops.
        service.interruptRun(task(2L, MEMBER_A));
        service.interruptRun(null);
        verifyNoMoreInteractions(streamTracker);
    }

    // ==================== prerequisite hand-off ====================

    @Test
    @DisplayName("the dispatch envelope carries prerequisite results and deliverables")
    void envelopeCarriesPrerequisiteResults() {
        TeamTaskEntity dependent = task(3L, MEMBER_B);
        dependent.setBlockedBy("[\"1\",\"2\"]");
        TeamTaskEntity done = task(1L, MEMBER_A);
        done.setStatus(TeamTaskStatus.COMPLETED);
        done.setResult("pricing collected: 3 competitors");
        when(taskService.getTask(1L)).thenReturn(done);
        when(taskService.getTask(2L)).thenReturn(null); // vanished blocker is skipped
        when(taskService.listDeliverables(done)).thenReturn(List.of(
                new TeamTaskService.Deliverable("prices.xlsx", "/api/v1/files/generated/x", null)));

        StringBuilder sb = new StringBuilder();
        service.appendPrerequisiteResults(sb, dependent);
        String section = sb.toString();

        assertTrue(section.contains("[Prerequisite results]"));
        assertTrue(section.contains("pricing collected: 3 competitors"));
        assertTrue(section.contains("prices.xlsx → /api/v1/files/generated/x"));
        assertTrue(section.contains("Inspect locally: ../generated-files/x"));
        assertTrue(section.contains("do not guess an HTTP port"));
        assertFalse(section.contains("#2"), "vanished blockers leave no trace");

        assertNull(TeamDispatchService.generatedFileInspectionPath("https://example.com/file"));
        assertNull(TeamDispatchService.generatedFileInspectionPath(
                "/api/v1/files/generated/../../secret"));

        // No blockers → no section at all.
        StringBuilder plain = new StringBuilder();
        service.appendPrerequisiteResults(plain, task(4L, MEMBER_A));
        assertEquals(0, plain.length());
    }

    @Test
    @DisplayName("a bounded task summary preserves the final outcome and a complete-output reference")
    void settleSummarizesLongReplyWithoutLosingFinalOutcome() {
        TeamTaskEntity running = task(1L, MEMBER_A);
        running.setStatus(TeamTaskStatus.IN_PROGRESS);
        when(taskService.getTask(1L)).thenReturn(running, running);
        when(taskService.completeTask(eq(1L), isNull(), anyString())).thenReturn(List.of());

        service.settleOutcome(running, "Opening findings\n"
                + "x".repeat(TeamDispatchService.MAX_RESULT_CHARS + 500) + "\nFinal verification passed");

        verify(taskService).completeTask(eq(1L), isNull(), argThat(r ->
                r.length() <= TeamDispatchService.MAX_RESULT_CHARS
                        && r.startsWith("Opening findings")
                        && r.endsWith("Final verification passed")
                        && r.contains("team_tasks(action=\"output\", taskId=\"1\")")));
    }

    @Test
    void completeWorkerReplyIsSavedBeforeTaskSummaryIsDerived() {
        TeamTaskEntity assigned = task(1L, MEMBER_A);
        assigned.setStatus(TeamTaskStatus.IN_PROGRESS);
        TeamTaskEntity done = task(1L, MEMBER_A);
        done.setStatus(TeamTaskStatus.COMPLETED);
        when(taskService.getTask(1L)).thenReturn(assigned, done, done);
        when(taskService.completeTask(eq(1L), isNull(), anyString())).thenReturn(List.of());
        String full = "Opening\n" + "x".repeat(10000) + "\nFinal verification";
        when(agentService.chatWithUsage(eq(MEMBER_A), anyString(), anyString()))
                .thenReturn(AgentService.ChatResult.contentOnly(full));

        service.runTask(TEAM_ID, assigned);

        var order = inOrder(conversationService, taskService);
        order.verify(conversationService).saveMessage(anyString(), eq("assistant"), eq(full));
        order.verify(taskService).completeTask(eq(1L), isNull(), argThat(result ->
                result.length() <= TeamDispatchService.MAX_RESULT_CHARS
                        && result.endsWith("Final verification")
                        && result.contains("action=\"output\"")));
    }

    @Test
    void prerequisiteContextKeepsFinalVerificationAndEveryIncludedTaskReference() {
        TeamTaskEntity dependent = task(20L, MEMBER_B);
        dependent.setBlockedBy("[1,2,3,4,5,6]");
        for (long id = 1; id <= 6; id++) {
            TeamTaskEntity prerequisite = task(id, MEMBER_A);
            prerequisite.setStatus(TeamTaskStatus.COMPLETED);
            prerequisite.setResult("opening " + id + "\n" + "analysis\n".repeat(2000)
                    + "\nverification " + id + " passed");
            when(taskService.getTask(id)).thenReturn(prerequisite);
        }

        StringBuilder context = new StringBuilder();
        service.appendPrerequisiteResults(context, dependent);
        String result = context.toString();

        assertTrue(result.length() <= TeamDispatchService.MAX_PREREQ_SECTION_CHARS);
        for (long id = 1; id <= 6; id++) {
            assertTrue(result.contains("taskId=\"" + id + "\""), "Missing task " + id);
            assertTrue(result.contains("verification " + id + " passed"), "Lost tail for " + id);
        }
    }

    @Test
    void manyPrerequisitesAndDeliverablesHaveExplicitRetrievalPathsInsideTotalBudget() {
        TeamTaskEntity dependent = task(50L, MEMBER_B);
        dependent.setBlockedBy("[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20]");
        for (long id = 1; id <= 20; id++) {
            TeamTaskEntity prerequisite = task(id, MEMBER_A);
            prerequisite.setStatus(TeamTaskStatus.COMPLETED);
            prerequisite.setSubject("Long subject ".repeat(50));
            prerequisite.setResult("Opening\n" + "evidence\n".repeat(2000) + "Final verified");
            when(taskService.getTask(id)).thenReturn(prerequisite);
            when(taskService.listDeliverables(prerequisite)).thenReturn(java.util.stream.IntStream.range(0, 10)
                    .mapToObj(index -> new TeamTaskService.Deliverable("report-" + index + ".docx",
                            "/api/v1/files/generated/complete-generated-id-" + index, null)).toList());
        }

        StringBuilder context = new StringBuilder();
        service.appendPrerequisiteResults(context, dependent);
        String result = context.toString();

        assertTrue(result.length() <= TeamDispatchService.MAX_PREREQ_SECTION_CHARS, result);
        assertTrue(result.contains("12 additional prerequisite(s) omitted"));
        assertTrue(result.contains("taskId=\"50\""));
        assertTrue(result.contains("deliverable(s) omitted"));
        for (long id = 1; id <= 8; id++) {
            assertTrue(result.contains("taskId=\"" + id + "\""));
            assertTrue(result.contains("Final verified"));
        }
    }
    @Test
    @SuppressWarnings("unchecked")
    void duplicateMemberRunnerRequeuesWithoutResettingDispatchBudget() {
        var busy=(java.util.Set<Long>)org.springframework.test.util.ReflectionTestUtils.getField(service,"runningMembers");
        busy.add(MEMBER_A);
        service.runTask(TEAM_ID,task(5L,MEMBER_A));
        verify(taskService).requeueUnusableResult(eq(5L),contains("already executing"));
        verify(taskService,never()).retryTask(anyLong());
        verifyNoInteractions(agentService);
    }

}
