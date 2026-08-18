package vip.mate.skill.lifecycle.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A restore point for the skill library, captured before a mutating curator
 * sweep.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_skill_snapshot")
public class SkillSnapshotEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Owning workspace; snapshots are never shared across tenants. */
    private Long workspaceId;

    /** Why the snapshot was taken — {@code pre-sweep}, {@code pre-restore}, or a manual note. */
    private String reason;

    /** Number of skills captured, so a listing need not parse the payload. */
    private Integer skillCount;

    /** JSON array of the captured skill rows. */
    @TableField(value = "payload", updateStrategy = FieldStrategy.ALWAYS)
    private String payload;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
