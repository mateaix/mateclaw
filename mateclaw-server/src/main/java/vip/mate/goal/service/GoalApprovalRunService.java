package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ExecutionAttribution;
import vip.mate.exception.MateClawException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** A consumed approval may start one new owner; it never revives its original lease. */
@Service
public class GoalApprovalRunService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final GoalJsonAcceptanceService acceptance;
    private final ManagedGoalJsonService artifacts;
    private final GoalContinuationStore continuations;
    private final GoalAttemptStore attempts;
    private final GoalRunCoordinator coordinator;
    private final GoalService goals;

    public GoalApprovalRunService(JdbcTemplate jdbc, ObjectMapper json, GoalJsonAcceptanceService acceptance,
                                  ManagedGoalJsonService artifacts, GoalContinuationStore continuations,
                                  GoalAttemptStore attempts, GoalRunCoordinator coordinator, GoalService goals) {
        this.jdbc=jdbc; this.json=json; this.acceptance=acceptance; this.artifacts=artifacts;
        this.continuations=continuations; this.attempts=attempts; this.coordinator=coordinator; this.goals=goals;
    }

    public record ReplayRun(GoalRunCoordinator.ClaimedRun run, ChatOrigin origin) { }

    /** Persist the selected interactive Goal on the approval's origin snapshot. */
    public ChatOrigin captureSelectedGoal(ChatOrigin origin) {
        if (origin == null || origin.cronOrigin() || origin.requesterUserId() == null
                || origin.conversationId() == null || origin.agentId() == null || origin.workspaceId() == null
                || (origin.selectedGoalId() != null && origin.selectedGoalId() > 0)
                || (origin.executionAttribution() != null
                    && origin.executionAttribution().goalAttemptId() != null)) return origin;
        var selected = jdbc.queryForList("""
                SELECT id FROM mate_agent_goal
                WHERE conversation_id=? AND agent_id=? AND workspace_id=?
                  AND json_acceptance_required=TRUE AND status IN ('active','paused') AND deleted=0
                """, Long.class, origin.conversationId(), origin.agentId(), origin.workspaceId());
        if (selected.size() > 1) throw rejected();
        // Zero records an explicit non-managed snapshot. Null is reserved for
        // approvals persisted by older binaries that had no capture field.
        if (selected.isEmpty()) return origin.withSelectedGoalId(0L);
        return origin.withSelectedGoalId(selected.getFirst());
    }

    /** A queued selection snapshot, including explicit zero, must still match before execution starts. */
    public boolean queuedSelectionStillCurrent(ChatOrigin origin) {
        if (origin == null || origin.selectedGoalId() == null || origin.selectedGoalId() < 0
                || origin.requesterUserId() == null || origin.requesterId() == null
                || origin.conversationId() == null || origin.agentId() == null || origin.workspaceId() == null)
            return false;
        try {
            return acceptance.withAuthenticatedUser(origin.requesterUserId(), origin.requesterId(), current -> {
                if (origin.selectedGoalId() == 0) {
                    Integer selected = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM mate_agent_goal
                            WHERE conversation_id=? AND agent_id=? AND workspace_id=?
                              AND json_acceptance_required=TRUE AND status IN ('active','paused') AND deleted=0
                            """, Integer.class, origin.conversationId(), origin.agentId(), origin.workspaceId());
                    return selected != null && selected == 0;
                }
                var scope = acceptance.authorizedGoal(origin.selectedGoalId(), current, true);
                return scope.required() && java.util.List.of("active", "paused").contains(scope.status())
                        && Objects.equals(scope.conversationId(), origin.conversationId())
                        && Objects.equals(scope.workspaceId(), origin.workspaceId())
                        && Objects.equals(scope.agentId(), origin.agentId());
            });
        } catch (vip.mate.exception.MateClawException stale) {
            return false;
        }
    }

    public boolean requiresHandoff(ChatOrigin origin) {
        var link = origin == null ? null : origin.executionAttribution();
        if (link == null || link.goalId() == null || link.approvalId() == null) return false;
        var required = jdbc.queryForList("SELECT json_acceptance_required FROM mate_agent_goal WHERE id=? AND deleted=0",
                Boolean.class, link.goalId());
        return required.isEmpty() || Boolean.TRUE.equals(required.getFirst());
    }

    /** Selected interactive Goals must retain their original authenticated requester at approval time. */
    public boolean requiresCurrentApprover(ChatOrigin origin) {
        if (requiresHandoff(origin)) return true;
        if (origin != null && origin.selectedGoalId() != null && origin.selectedGoalId() > 0) return true;
        if (origin == null || origin.conversationId() == null || origin.agentId() == null) return false;
        if (legacyTerminalSelection(origin)) return true;
        Integer selected = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mate_agent_goal
                WHERE conversation_id=? AND agent_id=? AND json_acceptance_required=TRUE
                  AND status IN ('active','paused') AND deleted=0
                """, Integer.class, origin.conversationId(), origin.agentId());
        return selected != null && selected > 0;
    }

    /** Revalidate the captured Goal before an approval can be consumed. */
    public void validateCapturedForApproval(ChatOrigin origin, String username, boolean approve) {
        if (approve && legacyTerminalSelection(origin)) throw rejected();
        var link = origin == null ? null : origin.executionAttribution();
        Long goalId = origin == null ? null : origin.selectedGoalId();
        if (link != null && link.goalId() != null && link.approvalId() != null
                && (link.goalAttemptId() == null || link.ownerFence() == null)) throw rejected();
        boolean scheduled = link != null && link.goalAttemptId() != null;
        if (goalId != null && goalId == 0L) goalId = null;
        if (goalId == null && link != null) goalId = link.goalId();
        if (goalId == null) return;
        var scope = acceptance.authorizedGoal(goalId, username, true);
        if (!scope.required() || (approve && !(scheduled ? "active".equals(scope.status())
                : java.util.List.of("active", "paused").contains(scope.status())))
                || !Objects.equals(scope.conversationId(), origin.conversationId())
                || !Objects.equals(scope.workspaceId(), origin.workspaceId())
                || !Objects.equals(scope.agentId(), origin.agentId())) throw rejected();
    }

    /** Without a persisted origin, a managed approval cannot safely execute after an upgrade. */
    public boolean hasManagedGoalHistory(String conversationId, String agentId) {
        if (conversationId == null) return false;
        Long agent = null;
        try { if (agentId != null) agent = Long.parseLong(agentId); }
        catch (NumberFormatException invalid) { /* unknown agent: check the whole conversation */ }
        Integer count = agent == null
                ? jdbc.queryForObject("""
                    SELECT COUNT(*) FROM mate_agent_goal
                    WHERE conversation_id=? AND json_acceptance_required=TRUE
                    """, Integer.class, conversationId)
                : jdbc.queryForObject("""
                    SELECT COUNT(*) FROM mate_agent_goal
                    WHERE conversation_id=? AND agent_id=? AND json_acceptance_required=TRUE
                    """, Integer.class, conversationId, agent);
        return count != null && count > 0;
    }

    /** Old pending rows lacked a selection snapshot; ambiguity near a terminal Goal fails closed. */
    private boolean legacyTerminalSelection(ChatOrigin origin) {
        if (origin == null || origin.selectedGoalId() != null || origin.requesterUserId() == null
                || origin.conversationId() == null || origin.agentId() == null || origin.workspaceId() == null)
            return false;
        var link = origin.executionAttribution();
        if (link == null || link.approvalId() == null) return false;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mate_agent_goal g
                JOIN mate_tool_approval p ON p.pending_id=? AND p.deleted=0
                WHERE g.conversation_id=? AND g.agent_id=? AND g.workspace_id=?
                  AND g.json_acceptance_required=TRUE
                  AND (g.status IN ('completed','abandoned','exhausted') OR g.deleted<>0)
                """, Integer.class, link.approvalId(), origin.conversationId(), origin.agentId(), origin.workspaceId());
        return count != null && count > 0;
    }

    @Transactional
    public ReplayRun claim(ChatOrigin requested, String toolCallPayload) {
        ExecutionAttribution link = requested == null ? null : requested.executionAttribution();
        if (link == null || link.goalId() == null || link.goalAttemptId() == null
                || link.ownerFence() == null || link.approvalId() == null
                || link.cronRunId() != null || requested.cronOrigin()) throw rejected();
        // Preserve the normal user -> conversation -> Goal lock order.
        var owners = jdbc.queryForList("SELECT username FROM mate_conversation WHERE conversation_id=? AND deleted=0",
                String.class, requested.conversationId());
        if (owners.size()!=1) throw rejected();
        var scope = acceptance.authorizedGoal(link.goalId(), owners.getFirst(), true);
        if (!scope.required() || !"active".equals(scope.status())) throw rejected();
        if (!Objects.equals(scope.conversationId(), requested.conversationId())
                || !Objects.equals(scope.workspaceId(), requested.workspaceId())
                || !Objects.equals(scope.agentId(), requested.agentId())) throw rejected();
        var candidate = continuations.getForUpdate(link.goalId());
        if (candidate != null && "running".equals(candidate.state())
                && Objects.equals(candidate.currentAttemptId(), link.goalAttemptId())
                && Objects.equals(candidate.leaseOwner(), link.ownerFence())
                && continuations.matchesFence(link.goalId(), link.ownerFence(), link.goalAttemptId(),
                        candidate.revision(), Instant.now().getEpochSecond())
                && attempts.hasLiveFence(link.goalAttemptId(), link.ownerFence(), Instant.now().getEpochSecond())) {
            throw new SettlementPending();
        }
        if (candidate == null || !"waiting_approval".equals(candidate.state())) throw rejected();
        var parent = attempts.getForUpdate(link.goalAttemptId());
        if (parent == null || !Objects.equals(parent.goalId(), link.goalId())
                || !Objects.equals(parent.conversationId(), requested.conversationId())
                || !Objects.equals(parent.leaseToken(), link.ownerFence()) || !"succeeded".equals(parent.state())) throw rejected();
        var approvals = jdbc.query("""
                SELECT conversation_id,agent_id,status,tool_call_payload,chat_origin
                FROM mate_tool_approval WHERE pending_id=? AND deleted=0 FOR UPDATE
                """, (r,i) -> new Approval(r.getString("conversation_id"), r.getString("agent_id"),
                r.getString("status"), r.getString("tool_call_payload"), r.getString("chat_origin")), link.approvalId());
        if (approvals.size()!=1) throw rejected();
        var approval = approvals.getFirst();
        if (!"CONSUMED".equals(approval.status()) || !Objects.equals(toolCallPayload, approval.payload())
                || !Objects.equals(requested.conversationId(), approval.conversationId())
                || !Objects.equals(String.valueOf(requested.agentId()), approval.agentId())) throw rejected();
        ChatOrigin persisted;
        try { persisted = json.readValue(approval.origin(), ChatOrigin.class); }
        catch (Exception error) { throw rejected(); }
        if (persisted == null || persisted.executionAttribution() == null
                || persisted.cronOrigin() || persisted.executionAttribution().cronRunId() != null
                || !Objects.equals(persisted.executionAttribution().goalId(), link.goalId())
                || !Objects.equals(persisted.executionAttribution().goalAttemptId(), link.goalAttemptId())
                || !Objects.equals(persisted.executionAttribution().ownerFence(), link.ownerFence())
                || !Objects.equals(persisted.agentId(), requested.agentId())
                || !Objects.equals(persisted.workspaceId(), requested.workspaceId())
                || !Objects.equals(persisted.conversationId(), requested.conversationId())) throw rejected();
        if (!jdbc.queryForList("SELECT attempt_id FROM mate_goal_attempt WHERE approval_pending_id=? FOR UPDATE",
                String.class, link.approvalId()).isEmpty()) throw rejected();
        Instant instant = Instant.now();
        LocalDateTime now = LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
        long untilEpoch = instant.getEpochSecond()+60;
        String token = UUID.randomUUID().toString();
        if (!continuations.claimApproval(link.goalId(), parent.id(), token, now, untilEpoch)) throw rejected();
        var claimed = continuations.get(link.goalId());
        var attempt = attempts.create(link.goalId(), requested.conversationId(), parent.id(), "approval",
                token, GoalLeaseTime.local(untilEpoch), null, now, untilEpoch);
        jdbc.update("UPDATE mate_goal_attempt SET approval_pending_id=? WHERE attempt_id=?", link.approvalId(), attempt.id());
        if (!continuations.bindAttempt(link.goalId(), token, attempt.id(), claimed.revision())) throw rejected();
        var run = new GoalRunCoordinator.ClaimedRun(candidate, goals.getById(link.goalId()), attempt, claimed.revision()+1);
        if (!coordinator.markRunning(run, now)) throw rejected();
        ChatOrigin origin = persisted.withBaseUrl(requested.baseUrl()).withExecutionAttribution(
                new ExecutionAttribution(link.goalId(), attempt.id(), null, link.approvalId(), token));
        // Recheck current conversation scope and both new lease rows before committing the claim.
        ManagedGoalJsonService.verifyLease(artifacts.runtimeGoal(origin));
        return new ReplayRun(run, origin);
    }

    private record Approval(String conversationId, String agentId, String status, String payload, String origin) { }
    static final class SettlementPending extends MateClawException {
        SettlementPending() { super(409, "The original Goal attempt is still settling its approval"); }
    }
    private static MateClawException rejected() {
        return new MateClawException(409, "Approved Goal execution cannot acquire a current owner; resume from current Goal state");
    }
}
