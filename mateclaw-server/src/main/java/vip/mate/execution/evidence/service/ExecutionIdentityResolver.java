package vip.mate.execution.evidence.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ExecutionAttribution;
import vip.mate.execution.evidence.model.ExecutionIdentity;
import vip.mate.team.service.TeamWorkerConversationGovernanceService;

import java.util.Objects;
import java.time.LocalDateTime;

/** Resolves business linkage from persisted rows, never from tool arguments. */
@Service
public class ExecutionIdentityResolver {
    private final JdbcTemplate jdbc;
    private final TeamWorkerConversationGovernanceService teamGovernance;

    public ExecutionIdentityResolver(JdbcTemplate jdbc, TeamWorkerConversationGovernanceService teamGovernance) {
        this.jdbc = jdbc;
        this.teamGovernance = teamGovernance;
    }

    public ExecutionIdentity resolve(ChatOrigin origin, String invocationKey, String providerCallId, String toolName) {
        if (origin == null || origin.conversationId() == null) return null;
        var workspaces = jdbc.queryForList("SELECT workspace_id FROM mate_conversation WHERE conversation_id=? AND deleted=0",
                Long.class, origin.conversationId());
        if (workspaces.size() != 1 || workspaces.getFirst() == null) return null;
        Long workspaceId = workspaces.getFirst();
        if (origin.workspaceId() != null && !workspaceId.equals(origin.workspaceId())) return null;
        ExecutionAttribution source = origin.executionAttribution();
        Long goalId = source == null ? null : source.goalId();
        String goalAttemptId = source == null ? null : source.goalAttemptId();
        Long cronRunId = source == null ? null : source.cronRunId();
        String approvalId = source == null ? null : source.approvalId();
        if (goalId == null && cronRunId == null) {
            var activeGoals = jdbc.queryForList("SELECT id FROM mate_agent_goal WHERE conversation_id=? AND workspace_id=? AND status='active' AND deleted=0",
                    Long.class, origin.conversationId(), workspaceId);
            if (activeGoals.size() == 1) goalId = activeGoals.getFirst();
        }
        if (goalId != null && !exists("SELECT COUNT(*) FROM mate_agent_goal WHERE id=? AND conversation_id=? AND workspace_id=? AND deleted=0",
                goalId, origin.conversationId(), workspaceId)) return null;
        if (goalAttemptId != null && !exists("SELECT COUNT(*) FROM mate_goal_attempt WHERE attempt_id=? AND goal_id=? AND conversation_id=? AND lease_token=?",
                goalAttemptId, goalId, origin.conversationId(), source.ownerFence())) return null;
        if (cronRunId != null && !exists("SELECT COUNT(*) FROM mate_cron_job_run WHERE id=? AND conversation_id=?",
                cronRunId, origin.conversationId())) return null;
        if (approvalId != null && !exists("SELECT COUNT(*) FROM mate_tool_approval WHERE pending_id=? AND conversation_id=?",
                approvalId, origin.conversationId())) return null;
        var team = teamGovernance.resolve(origin.conversationId(), null, null).orElse(null);
        String fence = source != null && source.ownerFence() != null ? source.ownerFence() : invocationKey;
        String key = approvalId == null ? invocationKey : "approval:" + approvalId;
        return new ExecutionIdentity(workspaceId, origin.conversationId(), "native", goalAttemptId,
                key, key, 1, approvalId == null ? providerCallId : null, toolName, goalId, goalAttemptId,
                team == null ? null : team.runId(), team == null ? null : team.taskId(), cronRunId,
                approvalId, goalAttemptId != null ? fence : approvalId == null ? fence : "approval:" + approvalId);
    }

    /** An expired business owner may leave historical observations, but cannot publish a new terminal result. */
    public boolean isCurrent(ExecutionIdentity identity) {
        if (identity.goalAttemptId() != null && !exists("""
                SELECT COUNT(*) FROM mate_goal_attempt WHERE attempt_id=? AND goal_id=? AND conversation_id=?
                AND state IN ('claimed','running') AND lease_until>CURRENT_TIMESTAMP
                """, identity.goalAttemptId(), identity.goalId(), identity.conversationId())) return false;
        return identity.cronRunId() == null || exists("SELECT COUNT(*) FROM mate_cron_job_run WHERE id=? AND conversation_id=? AND status='running'",
                identity.cronRunId(), identity.conversationId());
    }

    /** Lock order: conversation, business owner, then execution attempt. Called inside the store transaction. */
    public boolean lockCurrentForUpdate(ExecutionIdentity identity, boolean terminal) {
        var conversations = jdbc.queryForList("SELECT workspace_id,deleted FROM mate_conversation WHERE conversation_id=? FOR UPDATE",
                identity.conversationId());
        if (conversations.size() != 1 || !Objects.equals(((Number) conversations.getFirst().get("workspace_id")).longValue(), identity.workspaceId())
                || ((Number) conversations.getFirst().get("deleted")).intValue() != 0) {
            throw new IllegalStateException("Execution conversation unavailable");
        }
        if (!terminal) return true;
        if (identity.goalAttemptId() != null) {
            var owners = jdbc.query("SELECT goal_id,conversation_id,lease_token,state,lease_until FROM mate_goal_attempt WHERE attempt_id=? FOR UPDATE",
                    (row, index) -> Objects.equals(row.getLong("goal_id"), identity.goalId())
                            && Objects.equals(row.getString("conversation_id"), identity.conversationId())
                            && Objects.equals(row.getString("lease_token"), identity.ownerFence())
                            && ("claimed".equals(row.getString("state")) || "running".equals(row.getString("state")))
                            && row.getTimestamp("lease_until") != null
                            && row.getTimestamp("lease_until").toLocalDateTime().isAfter(LocalDateTime.now()),
                    identity.goalAttemptId());
            if (owners.size() != 1 || !owners.getFirst()) return false;
        }
        if (identity.cronRunId() != null) {
            var owners = jdbc.query("SELECT conversation_id,status FROM mate_cron_job_run WHERE id=? FOR UPDATE",
                    (row, index) -> Objects.equals(row.getString("conversation_id"), identity.conversationId())
                            && "running".equals(row.getString("status")), identity.cronRunId());
            if (owners.size() != 1 || !owners.getFirst()) return false;
        }
        return true;
    }

    private boolean exists(String sql, Object... values) {
        return Objects.equals(1L, jdbc.queryForObject(sql, Long.class, values));
    }
}
