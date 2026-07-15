package vip.mate.memory.fact.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Contradiction between two facts, detected during Dream consolidation.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_fact_contradiction")
public class FactContradictionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long agentId;

    private Long factAId;

    private Long factBId;

    private String description;

    /** null | KEEP_A | KEEP_B | MERGE | IGNORE */
    private String resolution;

    private LocalDateTime resolvedAt;

    private String resolvedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
