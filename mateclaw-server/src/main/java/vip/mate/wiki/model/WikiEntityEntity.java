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
 * Canonical named-entity node extracted and de-duplicated from source chunks.
 *
 * <p>Mention-granularity counterpart to {@link WikiPageEntity} (which models
 * document/topic granularity). Entities link to their source occurrences via
 * {@link WikiEntityMentionEntity} and to one another via
 * {@link WikiEntityRelationEntity}, forming an entity-level knowledge graph
 * beneath the page graph cached in {@link WikiRelationEntity}.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_entity")
public class WikiEntityEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    /** Display name chosen for the merged entity. */
    private String canonicalName;

    /** Case/whitespace-folded key used for exact-match de-duplication. */
    private String normalizedKey;

    /** Entity taxonomy: person | organization | location | event | product | concept | other. */
    private String type;

    /** JSON array of surface forms merged into this entity. */
    private String aliasesJson;

    /** One-line summary synthesized from the mentions. */
    private String description;

    /** 0..1 importance score derived from mention frequency / distribution. */
    private BigDecimal salience;

    /** Number of mentions resolved to this entity. */
    private Integer mentionCount;

    /** Float32 little-endian name/description vector used for near-duplicate merge. */
    private byte[] embedding;

    /** Model name that produced {@link #embedding}; used for re-embed detection. */
    private String embeddingModel;

    /** Fingerprint of the inputs that produced this row; used for cache invalidation. */
    private String computedHash;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
