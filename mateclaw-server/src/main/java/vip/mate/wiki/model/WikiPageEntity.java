package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Wiki 页面实体（AI 生成的结构化知识页面）
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_page")
public class WikiPageEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属知识库 ID */
    private Long kbId;

    /** URL 安全标识符，也是 [[link]] 的目标 */
    private String slug;

    /** 页面标题 */
    private String title;

    /** Markdown 内容（包含 [[links]]） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String content;

    /** 一段话摘要（用于上下文注入） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String summary;

    /** 出站链接（JSON 数组，如 ["slug-a","slug-b"]） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String outgoingLinks;

    /** 来源原始材料 ID（JSON 数组） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceRawIds;

    /** RFC-047 P2: paired source lineage — JSON array of {rawId, rawTitle} objects. Canonical; dual-written with sourceRawIds. */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceEntries;

    /** Page type: entity / concept / source / synthesis */
    private String pageType;

    /** Purpose hint for LLM ingest routing */
    private String purposeHint;

    /** Version number (incremented on each AI update) */
    private Integer version;

    /** 最后更新者：ai / manual */
    private String lastUpdatedBy;

    /**
     * RFC-051 PR-2: protection flag. {@code locked=1} blocks AI/tool/UI deletion
     * and batch cleanup; combined with {@code pageType="system"} for the
     * built-in {@code overview} / {@code log} pages.
     */
    private Integer locked;

    /**
     * RFC-051 PR-7: soft-archive flag. {@code archived=1} hides the page from
     * default list / search / related results without destroying it. Used to
     * tuck away pages that are no longer relevant but whose history (citations,
     * source-raw lineage) should stay queryable.
     */
    private Integer archived;

    /**
     * Page-level embedding (float32 little-endian) used by the semantic
     * retriever to surface pages whose generated content does not appear
     * in any source raw's chunks — typically synthesis pages produced by
     * a transformation. {@code null} = not yet embedded.
     */
    private byte[] embedding;

    /** Model name that produced {@link #embedding}; used for re-embed detection. */
    private String embeddingModel;

    /** Input-format version for {@link #embedding}; bumped when the embedding builder changes. */
    private String embeddingTextVersion;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
