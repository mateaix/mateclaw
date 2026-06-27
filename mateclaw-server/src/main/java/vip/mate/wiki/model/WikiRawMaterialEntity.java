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
     * 结构化错误码，与 {@code WikiProcessingService#classifyErrorCode} 同一词表
     * （AUTH_ERROR / BILLING / MODEL_NOT_FOUND / RATE_LIMIT / TIMEOUT /
     * SERVER_ERROR / CONTENT_FILTER / NO_CONTENT / EMPTY_RESULT / UNKNOWN）。
     * 供前端做本地化的友好提示；null 表示无错误。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorCode;

    /**
     * RFC-012 M2 v2 UI：当前处理阶段（null 未开始 / "route" / "phase-b" / "done"）。
     * 供前端决定是否显示进度条以及显示"准备中"还是具体进度。
     */
    private String progressPhase;

    /** RFC-012 M2 v2 UI：本次处理计划的总页数（route 阶段确定后写入）。 */
    private Integer progressTotal;

    /** RFC-012 M2 v2 UI：已完成的页数（每个 phase B 页成功后 +1）。 */
    private Integer progressDone;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
