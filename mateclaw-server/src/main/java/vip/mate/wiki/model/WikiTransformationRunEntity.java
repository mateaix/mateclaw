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
 * One execution of a {@link WikiTransformationEntity} against a source
 * (raw material today; pages in a follow-up). Output is stored inline so
 * the UI can render the result without re-running the LLM.
 */
@Data
@TableName("mate_wiki_transformation_run")
public class WikiTransformationRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long transformationId;
    private Long kbId;
    private Long workspaceId;

    /** {@code raw} | {@code page} | {@code text}. */
    private String inputKind;

    private Long rawId;
    private Long pageId;

    /** {@code pending} | {@code running} | {@code completed} | {@code failed}. */
    private String status;

    /** LLM output; treat as Markdown unless the prompt asked for JSON. */
    private String output;

    private String error;

    /** Model that actually produced the output after routing fallback. */
    private Long modelId;

    /** {@code apply_default} | {@code manual} | {@code agent_tool}. */
    private String triggeredBy;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;

    /**
     * Set when the run was persisted as a synthesis wiki page (either
     * automatically because the template's {@code outputTarget} is {@code page},
     * or manually via the save-as-page endpoint). Points at
     * {@code mate_wiki_page.id}.
     */
    private Long outputPageId;

    /** Prompt-side tokens reported by the provider (Spring AI Usage). */
    private Long inputTokens;

    /** Completion-side tokens reported by the provider. */
    private Long outputTokens;

    /** Provider's own total (usually input + output, but providers vary). */
    private Long totalTokens;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
