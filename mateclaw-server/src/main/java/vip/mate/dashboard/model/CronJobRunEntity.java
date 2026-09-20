package vip.mate.dashboard.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mate_cron_job_run")
public class CronJobRunEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long cronJobId;
    private String conversationId;
    /** running / completed / failed */
    private String status;
    /** scheduled / manual */
    private String triggerType;
    private LocalDateTime startedAt;
    /** Last durable liveness signal while the run is executing. */
    private LocalDateTime heartbeatAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
    private Integer tokenUsage;

    // ===== RFC-063r §2.9: delivery state machine =====

    /**
     * Delivery state machine — see RFC-063r §2.8.1:
     * {@code NONE} (web-origin) | {@code PENDING} (claimed) |
     * {@code DELIVERED} | {@code NOT_DELIVERED} (failure or stale-cleanup).
     * Orthogonal to {@link #status} (run main state); always-best-effort
     * policy means delivery failure never flips the main state to failed.
     */
    private String deliveryStatus;

    /** Resolved delivery target (IM userId / chat_id / Feishu webhook URL). */
    private String deliveryTarget;

    /** Delivery error reason — Hutool-truncated to 500 chars max. */
    private String deliveryError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
