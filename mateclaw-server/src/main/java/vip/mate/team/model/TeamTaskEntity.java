package vip.mate.team.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A task on a team's shared board. Created by the lead (or an admin) with a
 * mandatory assignee, dispatched to that member for isolated execution, and
 * completed with a result summary. Supports dependency blocking, progress
 * reporting, an optional human-approval stage, and a dispatch circuit breaker.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_team_task")
public class TeamTaskEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long teamId;

    /** Human-readable sequential number, unique within the team. */
    private Integer taskNumber;

    private String subject;

    private String description;

    /** See {@link TeamTaskStatus} for the full state machine. */
    private String status;

    /** Higher value dispatches first among unblocked pending tasks. */
    private Integer priority;

    /** Task category: general / request / note. */
    private String taskType;

    /** Intended executor chosen at creation (required); never the team lead. */
    private Long assigneeAgentId;

    /** Agent currently executing; NULL until the task is claimed or assigned. */
    private Long ownerAgentId;

    /** Creating agent id when the task was created by an agent (NULL for humans). */
    private Long createdByAgentId;

    /** JSON array of prerequisite task ids (as strings). */
    private String blockedBy;

    /** When true, completion parks the task in in_review until a human approves. */
    private Boolean requireApproval;

    private Integer progressPercent;

    private String progressStep;

    /** Result summary set on completion. */
    @TableField(value = "result", updateStrategy = FieldStrategy.ALWAYS)
    private String result;

    /** Failure / cancellation / rejection reason. */
    private String reason;

    /** Dispatch attempts; auto-fails past the circuit-breaker cap. */
    private Integer dispatchCount;

    /** Execution lease expiry; an expired in_progress task is recoverable as stale. */
    @TableField(value = "lock_expires_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lockExpiresAt;

    /** Conversation in which the member executes this task. */
    private String conversationId;

    /** Lead conversation that originated the task; used to route the result back. */
    private String leadConversationId;

    /** User whose request triggered the task (scoping / board filtering). */
    private String username;

    /** Origin channel of the triggering request (web / dingtalk / ...). */
    private String channel;

    /** Custom JSON payload (attachments, origin routing, trace ids, ...). */
    private String metadata;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
