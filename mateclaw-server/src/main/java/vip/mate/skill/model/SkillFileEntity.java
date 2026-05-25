package vip.mate.skill.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One file inside a skill bundle (an entry under {@code scripts/} or
 * {@code references/}).
 * <p>
 * The database is the canonical store. {@code SkillFileSyncer} mirrors
 * each row to the local workspace cache so {@code SkillScriptTool} and
 * other directory-aware consumers see the file on disk regardless of
 * which node accepted the original upload.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_skill_file")
public class SkillFileEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Owning skill (FK to {@code mate_skill.id}). */
    private Long skillId;

    /**
     * Path relative to the skill workspace root, always starting with
     * {@code scripts/} or {@code references/}. Forward slashes only.
     */
    private String filePath;

    /** UTF-8 text content. Per-file size bounded by ZipSkillFetcher (1MB). */
    private String content;

    /** Length of {@link #content} in bytes — kept so listings can sort/audit without loading the blob. */
    private Integer contentSize;

    /** SHA-256 of {@link #content}; used by the syncer to skip no-op writes. */
    private String sha256;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
