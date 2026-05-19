package vip.mate.workflow.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Stable workflow identity. The current draft is stored inline (1:1 with the
 * workflow row) so PK uniqueness automatically guarantees a single draft;
 * published snapshots live in {@code mate_workflow_revision}.
 */
@Data
@TableName("mate_workflow")
public class WorkflowEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    private String name;

    private String description;

    private Boolean enabled;

    /** Inline draft graph_json; null when there is no active draft. */
    @TableField(value = "draft_json", updateStrategy = FieldStrategy.ALWAYS)
    private String draftJson;

    @TableField(value = "draft_schema_version", updateStrategy = FieldStrategy.ALWAYS)
    private String draftSchemaVersion;

    @TableField(value = "draft_updated_by", updateStrategy = FieldStrategy.ALWAYS)
    private Long draftUpdatedBy;

    @TableField(value = "draft_updated_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime draftUpdatedAt;

    /** Pointer to the most recently published revision; null if never published. */
    @TableField(value = "latest_revision_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long latestRevisionId;

    /**
     * Latest published revision's graph JSON. Not a persisted column — it is
     * populated on the editor-facing read so a published workflow (whose inline
     * draft is cleared at publish time) still has a graph for the editor to
     * render instead of an empty canvas.
     */
    @TableField(exist = false)
    private String publishedGraphJson;

    /**
     * Human-facing version number of the latest published revision (1, 2, 3…).
     * Not a persisted column — populated on read so the UI shows "v3" instead
     * of leaking the latestRevisionId snowflake. Null when never published.
     */
    @TableField(exist = false)
    private Integer latestRevisionNumber;

    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // The `deleted` column stays on the table for schema compatibility but
    // is no longer logical-deleted — see contributing.md, the project moved
    // to hard-delete project-wide. deleteById() now performs a real DELETE,
    // and the unique key on (workspace_id, name, deleted) no longer collides
    // when a name is recreated and re-deleted.
    private Integer deleted;
}
