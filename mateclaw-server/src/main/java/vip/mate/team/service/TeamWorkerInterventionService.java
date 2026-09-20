package vip.mate.team.service;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.runtime.ConversationTurnGate;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.approval.PendingApproval;
import vip.mate.approval.ResolveOutcome;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.team.model.TeamTaskEntity;
import vip.mate.workspace.conversation.ConversationService;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/** Controlled write path for a delegated worker conversation. */
@Service
@RequiredArgsConstructor
public class TeamWorkerInterventionService {

    static final String REPLAY_PROMPT = "继续执行已批准的工具调用。";

    private final TeamTaskService taskService;
    private final TeamWorkerConversationGovernanceService governanceService;
    private final ApprovalWorkflowService approvalService;
    private final AgentService agentService;
    private final ConversationService conversationService;
    private final ConversationTurnGate turnGate;
    private final ChatStreamTracker streamTracker;
    private final TeamDispatchService dispatchService;
    private final TeamAnnounceService announceService;
    private final TeamEventChannel eventChannel;
    private final TeamWorkerReplayPersistenceService replayPersistenceService;

    public TeamTaskEntity approve(Long teamId, Long taskId, String pendingId, String requester) {
        Intervention intervention = requireIntervention(teamId, taskId);
        if (!vip.mate.team.model.TeamTaskStatus.AWAITING_APPROVAL.equals(
                intervention.task().getStatus())) {
            return intervention.task();
        }
        if (taskService.isToolReplayOutcomeUncertain(intervention.task())) {
            throw new IllegalStateException(
                    "tool replay outcome is uncertain; stop it or verify the side effect manually");
        }
        PendingApproval pending = requireReplayApproval(intervention, pendingId);
        ScheduledFuture<?> heartbeat = null;
        try (ConversationTurnGate.Permit permit = reserve(intervention.conversationId())) {
            requireMemberIdle(intervention);
            String reply = taskService.stagedToolReplayResult(intervention.task());
            if (reply == null) {
                PendingApproval claimedPending = claimReplay(
                        intervention, pendingId, requester, pending);
                if (!taskService.resumeAfterToolApproval(taskId, pendingId)) {
                    throw new IllegalStateException(
                            "worker task changed while replay was being claimed");
                }
                heartbeat = dispatchService.startLeaseHeartbeat(taskId);
                conversationService.removeApprovalPlaceholders(intervention.conversationId());
                ChatOrigin origin = approvalService.restoreChatOrigin(claimedPending.getChatOrigin()).withApprovalId(claimedPending.getPendingId());
                AgentService.ChatResult result;
                try {
                    result = turnGate.withPermit(permit, () -> agentService.chatWithReplayWithUsage(
                            intervention.agentId(), REPLAY_PROMPT, intervention.conversationId(),
                            claimedPending.getToolCallPayload(), origin));
                } catch (RuntimeException error) {
                    taskService.parkToolReplayUncertain(taskId, pendingId,
                            "Approved tool replay failed and its outcome is uncertain: "
                                    + safeMessage(error));
                    throw error;
                }
                reply = result == null ? "" : result.content();
                if (!taskService.stageToolReplayResult(taskId, pendingId, reply)) {
                    throw new IllegalStateException("tool replay completed but its result could not be staged");
                }
                replayPersistenceService.persist(taskId, pendingId,
                        intervention.conversationId(), reply, result);
            } else if (!taskService.isToolReplayMessagePersisted(intervention.task())
                    && !reply.isBlank()) {
                replayPersistenceService.persist(taskId, pendingId,
                        intervention.conversationId(), reply, null);
            }
            ResolveOutcome consumed = approvalService.consumeReplayClaim(pendingId, requester);
            if (!consumed.isConsumed()) {
                throw new IllegalStateException("approved tool replay could not be finalized");
            }
            if (!taskService.resumeAfterToolApproval(taskId, pendingId)) {
                throw new IllegalStateException("worker task changed while replay was being finalized");
            }
            settleOrPark(intervention, reply);
            return taskService.getTask(taskId);
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }

    @Transactional
    public TeamTaskEntity deny(Long teamId, Long taskId, String pendingId, String requester) {
        Intervention intervention = requireIntervention(teamId, taskId);
        if (!vip.mate.team.model.TeamTaskStatus.AWAITING_APPROVAL.equals(
                intervention.task().getStatus())) {
            return intervention.task();
        }
        if (taskService.stagedToolReplayResult(intervention.task()) != null) {
            throw new IllegalStateException("approved tool already executed; finalize its result instead");
        }
        PendingApproval approval = requireReplayApproval(intervention, pendingId);
        try (ConversationTurnGate.Permit ignored = reserve(intervention.conversationId())) {
            conversationService.removeApprovalPlaceholders(intervention.conversationId());
            String event;
            if ("approved".equals(approval.getStatus())) {
                if (!taskService.abortClaimedToolReplay(taskId, pendingId, requester)) {
                    throw new IllegalStateException("worker task changed while replay was being stopped");
                }
                ResolveOutcome consumed = approvalService.consumeReplayClaim(pendingId, requester);
                if (!consumed.isConsumed()) {
                    throw new IllegalStateException("claimed tool replay could not be stopped");
                }
                event = "team_task_tool_replay_aborted";
            } else {
                ResolveOutcome outcome = approvalService.resolve(pendingId, requester, "denied");
                if (outcome.isAlreadyResolved()) {
                    throw new IllegalStateException("tool approval is no longer pending");
                }
                if (!taskService.denyToolApproval(taskId, pendingId, requester)) {
                    throw new IllegalStateException("worker task changed while approval was being denied");
                }
                event = "team_task_tool_denied";
            }
            TeamTaskEntity settled = taskService.getTask(taskId);
            eventChannel.publishTaskEvent(settled, event, Map.of("pendingId", pendingId));
            announceService.announceTaskSettled(settled);
            dispatchService.requestDispatch(teamId);
            return settled;
        }
    }

    public TeamTaskEntity feedback(Long teamId, Long taskId, String message, String requester) {
        String feedback = message == null ? "" : message.strip();
        if (feedback.isEmpty()) {
            throw new IllegalArgumentException("feedback is required");
        }
        if (feedback.length() > 4000) {
            throw new IllegalArgumentException("feedback must be at most 4000 characters");
        }
        Intervention intervention = requireIntervention(teamId, taskId);
        if (approvalService.findPendingByConversation(intervention.conversationId()) != null) {
            throw new IllegalStateException("resolve the pending tool approval before sending feedback");
        }
        try (ConversationTurnGate.Permit permit = reserve(intervention.conversationId())) {
            requireMemberIdle(intervention);
            if (!taskService.resumeForWorkerFeedback(taskId)) {
                throw new IllegalStateException("worker task changed before feedback could start");
            }
            conversationService.saveMessage(intervention.conversationId(), "user", feedback);
            var agent = agentService.getAgent(intervention.agentId());
            Long workspaceId = agent == null ? null : agent.getWorkspaceId();
            ChatOrigin origin = ChatOrigin.web(
                    intervention.conversationId(), requester, workspaceId, null);
            AgentService.ChatResult result;
            try {
                result = turnGate.withPermit(permit, () -> agentService.chatWithUsage(
                        intervention.agentId(), feedback, intervention.conversationId(), origin));
            } catch (RuntimeException error) {
                taskService.failTask(taskId, "worker feedback failed: " + safeMessage(error));
                throw error;
            }
            String reply = persistAssistant(intervention.conversationId(), result);
            settleOrPark(intervention, reply);
            return taskService.getTask(taskId);
        }
    }

    private Intervention requireIntervention(Long teamId, Long taskId) {
        TeamTaskEntity task = taskService.getTask(taskId);
        if (task == null || !teamId.equals(task.getTeamId()) || task.getRunId() == null
                || task.getConversationId() == null || task.getConversationId().isBlank()) {
            throw new IllegalArgumentException("worker conversation not found for this task");
        }
        TeamWorkerConversationContext context = governanceService.resolve(
                        task.getConversationId(), task.getRunId(), taskId)
                .filter(candidate -> teamId.equals(candidate.teamId())
                        && Objects.equals(task.getAssigneeAgentId(), candidate.agentId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "worker conversation not found for this task"));
        return new Intervention(task, context.conversationId(), context.agentId());
    }

    private PendingApproval requireReplayApproval(Intervention intervention, String pendingId) {
        requireCurrentPendingId(intervention, pendingId);
        return approvalService.getPending(pendingId)
                .filter(pending -> intervention.conversationId().equals(pending.getConversationId()))
                .filter(pending -> "pending".equals(pending.getStatus())
                        || "approved".equals(pending.getStatus()))
                .or(() -> approvalService.getReplayClaim(pendingId)
                        .filter(pending -> intervention.conversationId()
                                .equals(pending.getConversationId())))
                .orElseThrow(() -> new IllegalStateException(
                        "tool approval is no longer pending or claimed"));
    }

    private void requireCurrentPendingId(Intervention intervention, String pendingId) {
        if (pendingId == null || pendingId.isBlank()) {
            throw new IllegalArgumentException("pending approval id is required");
        }
        String currentPendingId = null;
        try {
            var metadata = JSONUtil.parseObj(intervention.task().getMetadata());
            var approval = metadata.getJSONObject("toolApproval");
            currentPendingId = approval == null ? null : approval.getStr("pendingId");
        } catch (RuntimeException ignored) {
            // Missing or malformed task metadata means the client cannot prove
            // that this approval is the one the task is parked on.
        }
        if (!pendingId.equals(currentPendingId)) {
            throw new IllegalStateException("tool approval is no longer current for this task");
        }
    }

    private PendingApproval claimReplay(Intervention intervention, String pendingId,
                                        String requester, PendingApproval pending) {
        if ("pending".equals(pending.getStatus())) {
            ResolveOutcome claimed = approvalService.claimForReplay(pendingId, requester);
            if (claimed.isAlreadyResolved()) {
                throw new IllegalStateException("tool approval was resolved concurrently");
            }
        }
        return approvalService.getReplayClaim(pendingId)
                .filter(candidate -> intervention.conversationId()
                        .equals(candidate.getConversationId()))
                .orElseThrow(() -> new IllegalStateException(
                        "approved tool replay claim could not be recovered"));
    }

    private void requireMemberIdle(Intervention intervention) {
        if (taskService.hasActiveTask(intervention.task().getTeamId(), intervention.agentId())) {
            throw new IllegalStateException("worker agent is already executing another team task");
        }
    }

    private ConversationTurnGate.Permit reserve(String conversationId) {
        ConversationTurnGate.Permit permit = turnGate.tryAcquire(conversationId);
        if (permit == null || streamTracker.isRunning(conversationId)) {
            if (permit != null) {
                permit.close();
            }
            throw new IllegalStateException("worker conversation is already running");
        }
        return permit;
    }

    private String persistAssistant(String conversationId, AgentService.ChatResult result) {
        String reply = result == null ? "" : result.content();
        if (reply != null && !reply.isBlank()) {
            conversationService.saveMessage(conversationId, "assistant", reply, null, "completed",
                    result.promptTokens(), result.completionTokens(),
                    result.runtimeModel(), result.runtimeProvider());
        }
        return reply;
    }

    private void settleOrPark(Intervention intervention, String reply) {
        PendingApproval next = approvalService.findPendingByConversation(intervention.conversationId());
        if (next != null) {
            String summary = next.getSummary() == null || next.getSummary().isBlank()
                    ? next.getReason() : next.getSummary();
            taskService.parkForToolApproval(intervention.task().getId(), next.getPendingId(), summary);
            eventChannel.publishTaskEvent(taskService.getTask(intervention.task().getId()),
                    "team_task_awaiting_approval", Map.of("pendingId", next.getPendingId()));
            return;
        }
        dispatchService.settleOutcome(intervention.task(), reply);
        dispatchService.requestDispatch(intervention.task().getTeamId());
    }

    private static String safeMessage(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record Intervention(TeamTaskEntity task, String conversationId, Long agentId) {
    }
}
