package vip.mate.planning.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行计划实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_plan")
public class PlanEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联的 Agent ID（字符串） */
    private String agentId;

    /** 产生该计划的对话/运行 ID（可空，历史行为 null）。用于把计划绑定到具体运行、支持跨员工/协同分组。 */
    private String conversationId;

    /** 任务目标 */
    private String goal;

    /** 计划状态：pending / running / completed / failed */
    private String status;

    /** 总步骤数 */
    private Integer totalSteps;

    /** 已完成步骤数 */
    private Integer completedSteps;

    /** 执行结果摘要 */
    @TableField(value = "summary", updateStrategy = FieldStrategy.ALWAYS)
    private String summary;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;

    /** 子计划列表（非数据库字段，查询时填充） */
    @TableField(exist = false)
    private List<SubPlanEntity> steps;
}
