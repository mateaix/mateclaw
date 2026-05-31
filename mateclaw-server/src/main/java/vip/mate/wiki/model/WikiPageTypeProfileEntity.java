package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A KB-scoped pageType profile: the set of page types a knowledge base
 * recognises, plus each type's field schema, per-stage LLM instructions and
 * Markdown template, serialized into {@link #configJson}.
 *
 * <p>The built-in default profile is provided as a code constant and is NOT
 * stored in this table, so {@link #kbId} is always non-null. A virtual
 * generated column on the table enforces at most one enabled profile per KB.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_page_type_profile")
public class WikiPageTypeProfileEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Owning knowledge base (never null — built-in default is not stored). */
    private Long kbId;

    /** Profile name, e.g. {@code default}, {@code regulation}. */
    private String name;

    /** Profile version, bumped on each saved edit. */
    private Integer version;

    /** Full pageType configuration as JSON. */
    private String configJson;

    /** {@code 1} = the active profile for the KB. */
    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
