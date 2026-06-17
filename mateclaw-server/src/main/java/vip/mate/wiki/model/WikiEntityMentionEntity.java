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
 * Links a canonical {@link WikiEntityEntity} to a single source occurrence.
 *
 * <p>One row per (entity, chunk) occurrence. {@link #pageId} is back-filled
 * from the chunk's citing pages so the entity layer connects to the page
 * layer: entity → mention → chunk → citing page.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_entity_mention")
public class WikiEntityMentionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    /** The resolved canonical entity. */
    private Long entityId;

    /** Source chunk the mention was found in. */
    private Long chunkId;

    /** A wiki page that cites {@link #chunkId}, when known; null otherwise. */
    private Long pageId;

    /** The exact text as it appeared in the source. */
    private String surfaceForm;

    /** Character offset of the mention within the chunk, when known. */
    private Integer charOffset;

    /** 0..1 extraction confidence. */
    private BigDecimal confidence;

    /** Short surrounding quote (≤ 500 chars enforced in Java layer). */
    private String evidence;

    /** Provenance tag: llm-extracted | manual. */
    private String source;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
