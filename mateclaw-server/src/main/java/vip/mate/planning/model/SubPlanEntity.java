package vip.mate.planning.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 子计划步骤实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_sub_plan")
public class SubPlanEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属计划 ID */
    private Long planId;

    /** 步骤序号（从0开始） */
    private Integer stepIndex;

    /** 步骤描述 */
    private String description;

    /** 步骤状态：pending / running / completed / failed */
    private String status;

    /**
     * Delegated agent id for this step. When non-null, the executor routes this
     * step to that specialist agent instead of the parent (plan) agent. Null =
     * run with the parent agent (original behavior). The frontend resolves the
     * display name from this id via the agent store.
     */
    private Long assignedAgentId;

    /** 步骤执行结果 */
    @TableField(value = "result", updateStrategy = FieldStrategy.ALWAYS)
    private String result;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
