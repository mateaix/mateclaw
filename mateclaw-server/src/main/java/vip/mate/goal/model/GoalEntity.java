package vip.mate.goal.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistent goal bound to one conversation.
 *
 * <p>Lifecycle and DB uniqueness are documented in the V120 migration and
 * the {@link GoalStatus} enum. Concurrency safety relies on:
 * <ul>
 *   <li>The DB-level unique index on the active-state subset (H2 predicate
 *       index / MySQL generated column).</li>
 *   <li>The {@link #version} column updated via explicit
 *       {@code WHERE version = ?} in the service layer's update wrappers.
 *       No global OptimisticLockerInnerInterceptor is wired, so the
 *       service must opt in per-write.</li>
 * </ul>
 *
 * <p>The {@code active_conv_key} generated column (MySQL) is intentionally
 * NOT mapped to a Java field — MyBatis Plus would otherwise try to write
 * to it on insert. H2 has no such column.
 */
@Data
@TableName("mate_agent_goal")
public class GoalEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** FK to mate_conversation.conversation_id. */
    private String conversationId;

    /** FK to mate_agent.id. */
    private Long agentId;

    /** FK to mate_workspace.id. */
    private Long workspaceId;

    /** Username of the goal creator; matches mate_user.username. */
    private String createdBy;

    /** Short title displayed on the avatar tooltip and timeline. */
    private String title;

    /** Long-form objective. Always non-null but may be short. */
    private String description;

    /** LLM-readable exit criteria; evaluator scores against this. Nullable. */
    @TableField(value = "exit_criteria", updateStrategy = FieldStrategy.ALWAYS)
    private String exitCriteria;

    /** Optional per-goal evaluator prompt override; nullable -> default. */
    @TableField(value = "success_check_prompt", updateStrategy = FieldStrategy.ALWAYS)
    private String successCheckPrompt;

    /**
     * One of the {@link GoalStatus} lowercase values. Persistence handled
     * by MyBatis Plus + @EnumValue annotation on the enum.
     */
    private GoalStatus status;

    /** Maximum evaluation turns before exhaustion. */
    private Integer turnBudget;

    /** Cumulative turns evaluated; bumped by GoalEvaluationNode. */
    private Integer turnsUsed;

    /** Single cap covering both agent-side and evaluator-side LLM calls. */
    private Integer llmCallBudget;

    /** Main-graph LLM calls observed via state.LLM_CALL_COUNT delta. */
    private Integer agentLlmCallsUsed;

    /** Evaluator LLM calls consumed by GoalEvaluationService. */
    private Integer evalLlmCallsUsed;

    /** Last evaluator gap text — shown in the hover tooltip. Nullable. */
    @TableField(value = "progress_summary", updateStrategy = FieldStrategy.ALWAYS)
    private String progressSummary;

    /** Last completion score 0.0..1.0. Nullable (no eval yet). */
    @TableField(value = "completion_score", updateStrategy = FieldStrategy.ALWAYS)
    private Double completionScore;

    @TableField(value = "last_evaluation_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastEvaluationAt;

    /** Whether the evaluator may inject a follow-up user prompt. */
    private Boolean autoFollowupEnabled;

    /** Minimum interval between two auto-followups for the same goal. */
    private Integer followupCooldownSeconds;

    @TableField(value = "last_followup_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastFollowupAt;

    /** Optimistic lock version; service updates must pass {@code WHERE version=?}. */
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** Convenience derived counter used by SSE event payloads. */
    public int totalLlmCallsUsed() {
        int a = agentLlmCallsUsed != null ? agentLlmCallsUsed : 0;
        int e = evalLlmCallsUsed != null ? evalLlmCallsUsed : 0;
        return a + e;
    }
}
