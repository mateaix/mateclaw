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
 * Persistent cache of page-to-page multi-signal relations.
 *
 * <p>One row per ordered (page_a_id, page_b_id) pair within a knowledge base.
 * The row materializes the aggregate score across registered signal strategies
 * plus optional taxonomy / confidence / evidence metadata when the planning
 * stage of the compile pipeline produced any.
 *
 * <p>Distinct from {@link WikiPageCitationEntity}, which models page-to-chunk
 * citations. The two tables coexist; nothing in this row supersedes citation
 * rows.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_relation")
public class WikiRelationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    private Long pageAId;

    private Long pageBId;

    /** Aggregate score (sum of weighted signal contributions). */
    private BigDecimal totalScore;

    /** Per-signal breakdown serialized as JSON, e.g. {@code {"direct_link":2.0,"shared_chunk":5.0}}. */
    private String signalsJson;

    /** Relation taxonomy: {@code mention | cite | supports | contradicts | extends}. */
    private String type;

    /** Confidence taxonomy: {@code EXTRACTED | INFERRED | AMBIGUOUS | UNVERIFIED}. */
    private String confidence;

    /** Verbatim quote or paraphrased rationale supporting the relation; ≤ 500 chars. */
    private String evidence;

    /** When evidence is a quote, the raw material id it was pulled from. */
    private Long evidenceRawId;

    /** Provenance tag: {@code llm-extracted | wikilink-context | manual}. */
    private String source;

    private LocalDateTime computedAt;

    /** Fingerprint of the inputs that produced {@link #totalScore}; used for cache invalidation. */
    private String computedHash;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
