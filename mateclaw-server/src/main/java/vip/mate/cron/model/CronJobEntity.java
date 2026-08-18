package vip.mate.cron.model;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务实体
 *
 * @author MateClaw Team
 */
@Data
@TableName(value = "mate_cron_job", autoResultMap = true)
public class CronJobEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Workspace ID this cron job belongs to (RFC-083 / V62; existing rows default to 1). */
    private Long workspaceId;

    /** 任务名称 */
    private String name;

    /** 5 字段 cron 表达式（分 时 日 月 周） */
    private String cronExpression;

    /** 时区 */
    private String timezone;

    /** 关联 Agent ID */
    private Long agentId;

    /**
     * 任务类型：
     * <ul>
     *   <li>{@code text} — single-turn LLM chat (uses {@code triggerMessage})</li>
     *   <li>{@code agent} — Plan-Execute (uses {@code requestBody})</li>
     *   <li>{@code reminder} — direct push of {@code triggerMessage}, no LLM call</li>
     * </ul>
     */
    private String taskType;

    /** 触发消息（task_type=text 或 reminder 时使用） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String triggerMessage;

    /** 执行目标（task_type=agent 时使用） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String requestBody;

    /** 是否启用 */
    private Boolean enabled;

    /** 下次执行时间 */
    private LocalDateTime nextRunTime;

    /** 上次执行时间 */
    private LocalDateTime lastRunTime;

    /**
     * RFC-063r §2.9: originating channel binding. Null when this job was
     * created from the web (no proactive delivery target). The single
     * indexed column lets ops query "all jobs delivering to channel X".
     *
     * <p>{@code FieldStrategy.ALWAYS} so clearing the binding from the edit
     * form actually writes NULL — the default NOT_NULL strategy drops the
     * column from the UPDATE and the old channel silently survives.
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long channelId;

    /**
     * RFC-063r §2.9: delivery target detail (targetId / threadId / accountId)
     * persisted as JSON via MyBatis Plus JacksonTypeHandler so future fields
     * don't require schema migrations.
     */
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private DeliveryConfig deliveryConfig;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;

    /**
     * RFC-063r §2.14: read-model field — populated by
     * {@code CronJobMapper.selectListWithDeliveryStatus()} via a subquery
     * against {@code mate_cron_job_run}. Not part of the writable schema.
     */
    @TableField(exist = false)
    private String lastDeliveryStatus;

    /** RFC-063r §2.14: matching error column for the most-recent run. */
    @TableField(exist = false)
    private String lastDeliveryError;
}
