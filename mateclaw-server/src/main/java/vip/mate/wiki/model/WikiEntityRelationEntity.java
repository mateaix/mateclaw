package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Directed subject → predicate → object triple between two canonical entities;
 * the edges of the entity-level knowledge graph.
 *
 * <p>Distinct from {@link WikiRelationEntity}, which scores page-to-page edges.
 * A row here is one fact triple connecting two {@link WikiEntityEntity} nodes.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_entity_relation")
public class WikiEntityRelationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    /** Head entity. */
    private Long subjectEntityId;

    /** Free-text relation label, e.g. {@code works_for}, {@code located_in}. */
    private String predicate;

    /** Tail entity. */
    private Long objectEntityId;

    /** Short justification quote (≤ 500 chars enforced in Java layer). */
    private String evidence;

    /** 0..1 extraction confidence. */
    private BigDecimal confidence;

    /** Provenance tag: llm-extracted | inferred | manual. */
    private String source;

    /** Source chunk the triple was extracted from, when known. */
    private Long evidenceChunkId;

    /** Fingerprint of the inputs that produced this row; used for cache invalidation. */
    private String computedHash;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
