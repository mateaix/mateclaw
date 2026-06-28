package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Wiki 原始材料实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_raw_material")
public class WikiRawMaterialEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属知识库 ID */
    private Long kbId;

    /** 材料标题 */
    private String title;

    /** Source type: text / pdf / docx / image / url / paste. */
    private String sourceType;

    /** Original Content-Type from the upload (e.g. {@code image/png}); null for text. */
    private String mimeType;

    /** Original file path on disk (binary uploads only). */
    private String sourcePath;

    /** 原始文本内容（文本类型） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String originalContent;

    /** 提取后的文本（PDF/DOCX 等） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String extractedText;

    /** 内容 SHA-256 哈希（用于去重和变更检测） */
    private String contentHash;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 处理状态：pending / processing / completed / failed / partial / cancelled */
    private String processingStatus;

    /**
     * User-requested cancellation flag. Set to {@code true} via the cancel
     * endpoint while a raw material is in {@code processing}. The pipeline
     * observes the flag at its abort checkpoints and exits early with
     * {@code processingStatus = "cancelled"}; the flag is cleared on the
     * next successful claim for processing.
     */
    private Boolean cancelRequested;

    /** 上次处理时间 */
    private LocalDateTime lastProcessedAt;

    /** 上次成功处理时的 content_hash，用于重处理时的短路判断 */
    private String lastProcessedHash;

    /** 错误信息（原始异常文本，供排查使用） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    /**
     * Structured error code, sharing the same vocabulary as
     * {@code WikiProcessingService#classifyErrorCode}
     * (AUTH_ERROR / BILLING / MODEL_NOT_FOUND / RATE_LIMIT / TIMEOUT /
     * SERVER_ERROR / CONTENT_FILTER / NO_CONTENT / EMPTY_RESULT / UNKNOWN).
     * Used by the frontend for localized friendly messages; null = no error.
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorCode;

    /**
     * Non-blocking warning code: the material was processed successfully
     * overall (completed/partial), but an async sub-step (embedding /
     * entity-graph extraction) failed causing a degraded feature (e.g. no
     * semantic search). Shares the same friendly-prompt mechanism as
     * {@link #errorCode}; null = no warning.
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String warningCode;

    /** Raw warning text (for troubleshooting), paired with {@link #warningCode}. */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String warningMessage;

    /**
     * Current processing phase (null = not started / "route" / "phase-b" /
     * "done"). Drives whether the frontend shows a progress bar and whether
     * it says "preparing" or a concrete percentage.
     */
    private String progressPhase;

    /** Total pages planned for this run (set after route phase). */
    private Integer progressTotal;

    /** Completed page count (incremented per successful phase-B page). */
    private Integer progressDone;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
