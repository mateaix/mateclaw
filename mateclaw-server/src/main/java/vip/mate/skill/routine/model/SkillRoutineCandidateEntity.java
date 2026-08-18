package vip.mate.skill.routine.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A cluster of conversations that opened with substantially the same user
 * request — the accumulating evidence that some request is a routine rather
 * than a one-off.
 *
 * <p>Exists because recurrence is invisible from inside a single conversation.
 * The post-turn reflection reviewer sees one window and correctly declines to
 * write a skill for what looks like a one-off task; only a cross-session count
 * can distinguish "the user asked this once" from "the user asks this every
 * Monday". This row carries that count.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_skill_routine_candidate")
public class SkillRoutineCandidateEntity {

    /** Still gathering evidence; below the promotion gate. */
    public static final String STATUS_OBSERVING = "observing";
    /** A skill has been synthesized from this cluster. */
    public static final String STATUS_PROMOTED = "promoted";
    /** Operator rejected this cluster; never promote it. */
    public static final String STATUS_DISMISSED = "dismissed";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Agent the routine belongs to — routines are per-agent, not global. */
    private Long agentId;

    private Long workspaceId;

    /** Normalized representative text; the human-readable routine identity. */
    private String signature;

    /** Stable hash of {@link #signature}, used as the upsert key. */
    private String signatureHash;

    /** Verbatim opener of the most recent member conversation. */
    @TableField(value = "representative_text", updateStrategy = FieldStrategy.ALWAYS)
    private String representativeText;

    /** JSON array of member conversation ids, capped by the miner. */
    @TableField(value = "sample_conversations", updateStrategy = FieldStrategy.ALWAYS)
    private String sampleConversations;

    /** Conversations observed in this cluster. */
    private Integer occurrenceCount;

    /**
     * Distinct calendar days the cluster was seen on. Separate from
     * {@link #occurrenceCount} because five conversations in one afternoon is
     * one person retrying, whereas five conversations across five days is a
     * habit. Promotion requires both.
     */
    private Integer distinctDayCount;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    /** {@code observing} | {@code promoted} | {@code dismissed}. */
    private String status;

    /** Name of the skill synthesized from this cluster, once promoted. */
    @TableField(value = "promoted_skill_name", updateStrategy = FieldStrategy.ALWAYS)
    private String promotedSkillName;

    @TableField(value = "promoted_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime promotedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
