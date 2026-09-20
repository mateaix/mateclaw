package vip.mate.team.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One moment in a team task's lifecycle (created, dispatched, progress,
 * comment, deliverable, settlement, approval actions). Append-only side
 * channel rendered as the task's collaboration timeline; recording failures
 * never affect the task itself.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_team_task_event")
public class TeamTaskEventEntity {

    // event_type values; kept as plain constants (no enum) so new moments can
    // be recorded without a schema or code migration.
    public static final String CREATED = "created";
    public static final String DISPATCHED = "dispatched";
    public static final String PROGRESS = "progress";
    public static final String COMMENT = "comment";
    public static final String BLOCKER = "blocker";
    public static final String DELIVERABLE = "deliverable";
    public static final String COMPLETED = "completed";
    public static final String IN_REVIEW = "in_review";
    public static final String AWAITING_APPROVAL = "awaiting_approval";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String RETRIED = "retried";
    public static final String STALE = "stale";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Denormalized team id for team-level activity queries. */
    private Long teamId;

    private Long taskId;

    private String eventType;

    /** Actor kind: agent / user / system. */
    private String actorType;

    /** Agent id or username depending on actorType; null for system moments. */
    private String actorId;

    /** Human-readable one-liner: progress step, failure reason, file name… */
    private String detail;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
