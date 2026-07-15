package vip.mate.memory.fact.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Fact projection entity — read-only derived view of canonical memory.
 * Derived columns (subject, predicate, object_value, confidence, trust, category)
 * are rebuilt by FactProjectionBuilder.
 * Accumulated columns (last_used_at, use_count) are only written by FactQueryService.bumpUseCount.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_fact")
public class FactEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long agentId;

    /** Canonical source reference, e.g. "structured/user.md#preferred_language" */
    private String sourceRef;

    /** Category: user_pref, project, tool, general */
    private String category;

    private String subject;

    private String predicate;

    @TableField("object_value")
    private String objectValue;

    /** Extraction confidence [0..1] */
    private Double confidence;

    /** Trust score derived from feedback + time decay */
    private Double trust;

    // --- Accumulated columns (preserved across rebuilds) ---

    private LocalDateTime lastUsedAt;

    private Integer useCount;

    /** pattern | llm */
    private String extractedBy;

    /** Memory subject this fact belongs to (e.g. "user:42"); null for shared/legacy rows. */
    private String ownerKey;

    /** Visibility scope: PERSONAL / TEAM / GLOBAL. Defaults to TEAM at the DB level. */
    private String scope;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
