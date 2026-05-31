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
 * A dependency edge from an experience page to a fact page it relies on.
 * The reverse index ({@code depends_on_page_id}) drives stale propagation:
 * when a fact page changes, the experience pages depending on it are marked
 * stale. Stored by page id (never slug) so renames cannot break the edge.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_page_dependency")
public class WikiPageDependencyEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Knowledge base both pages belong to (cross-KB dependencies are rejected). */
    private Long kbId;

    /** The dependent (experience) page. */
    private Long pageId;

    /** The fact page being depended on. */
    private Long dependsOnPageId;

    /** Dependency kind; {@code fact} for the fact→experience relation. */
    private String dependencyType;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
