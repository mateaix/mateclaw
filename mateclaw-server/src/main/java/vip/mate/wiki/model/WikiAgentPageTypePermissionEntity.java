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
 * Per-agent, per-KB, per-pageType permission for wiki tool access.
 * <p>
 * A {@code page_type='*'} row is the agent's KB-wide default; an exact
 * {@code page_type} row is more specific and wins over {@code '*'}. When an
 * agent has no rows for a KB at all, access falls back to the KB-level
 * default read policy (see {@code WikiKbConfig#getDefaultReadPolicy()}).
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_agent_page_type_permission")
public class WikiAgentPageTypePermissionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Agent the rule applies to. */
    private Long agentId;

    /** Knowledge base the rule applies to. */
    private Long kbId;

    /** Page type name, or {@code *} for the agent's KB-wide default. */
    private String pageType;

    /** Whether the agent may read pages of this type. */
    private Integer canRead;

    /** Whether the agent may create pages of this type. */
    private Integer canCreate;

    /** Whether the agent may update pages of this type. */
    private Integer canUpdate;

    /** Whether the agent may delete pages of this type. */
    private Integer canDelete;

    /** Write resolution: {@code deny} / {@code approval_required} / {@code allow}. */
    private String writePolicy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
