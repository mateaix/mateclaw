package vip.mate.wiki.job.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RFC-030: Wiki processing job entity — tracks the lifecycle of a single
 * raw material processing run with per-stage state and model routing info.
 */
@Data
@TableName("mate_wiki_processing_job")
public class WikiProcessingJobEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    private Long rawId;

    private String jobType;

    private String stage;

    private String status;

    private Long primaryModelId;

    private Long currentModelId;

    private String fallbackChainJson;

    private Integer retryCount;

    private Integer maxRetries;

    private String errorCode;

    private String errorMessage;

    private String resumeFromStage;

    /** Generic JSON metadata (e.g. targetPageId for LOCAL_REPAIR) */
    private String metaJson;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
