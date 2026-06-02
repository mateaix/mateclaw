package vip.mate.workspace.document.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区文件实体（Agent 级 Markdown 文档）
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_workspace_file")
public class WorkspaceFileEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联的 Agent ID */
    private Long agentId;

    /** 文件名（如 AGENTS.md、SOUL.md） */
    private String filename;

    /** Markdown 内容 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String content;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 是否启用为系统提示词 */
    private Boolean enabled;

    /** 排序顺序（越小越靠前） */
    private Integer sortOrder;

    /**
     * Memory subject this row belongs to, as a prefixed string
     * ("user:42", "feishu:ou_xxx", "api:&lt;endUserId&gt;"). Null for shared
     * config rows (AGENTS.md / SOUL.md / PROFILE.md) and legacy data.
     */
    private String ownerKey;

    /**
     * Visibility scope: PERSONAL (only the matching {@link #ownerKey} sees it),
     * TEAM (everyone using the agent), or GLOBAL (always visible). Defaults to
     * TEAM at the DB level so config files and legacy rows stay shared.
     */
    private String scope;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
