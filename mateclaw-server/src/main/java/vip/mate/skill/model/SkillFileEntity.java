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
     * {@code scripts/}, {@code references/} or {@code templates/}. Forward slashes only.
     */
    private String filePath;

    /** UTF-8 text or base64-encoded attachment bytes, according to contentEncoding. */
    private String content;

    /** null on legacy rows means UTF-8. */
    private String contentEncoding;

    public boolean isBinary() {
        return "base64".equals(contentEncoding);
    }

    public byte[] contentBytes() {
        String value = content == null ? "" : content;
        return isBinary() ? java.util.Base64.getDecoder().decode(value)
                : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Length of the original file in bytes — kept so listings can sort/audit without loading the blob. */
    private Integer contentSize;

    /** SHA-256 of the original bytes; used by the syncer to skip no-op writes. */
    private String sha256;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
