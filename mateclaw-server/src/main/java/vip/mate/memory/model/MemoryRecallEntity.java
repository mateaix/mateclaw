package vip.mate.memory.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记忆召回追踪实体
 * <p>
 * 记录 workspace 文件在对话上下文注入时的召回信息，
 * 用于加权评分驱动的记忆整合（Dreaming）。
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_memory_recall")
public class MemoryRecallEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联 Agent ID */
    private Long agentId;

    /** 文件名，如 "memory/2026-04-01.md"、"MEMORY.md" */
    private String filename;

    /** 被召回片段的 SHA-256 哈希（可为 null，文件级追踪时不填） */
    private String snippetHash;

    /** 片段预览（前 200 字符，方便调试） */
    private String snippetPreview;

    /** 累计召回次数 */
    private Integer recallCount;

    /** 当日召回次数（每轮 dreaming 重置） */
    private Integer dailyCount;

    /** 不同 user query hash 的 JSON 数组 */
    private String queryHashes;

    /** 加权评分 */
    private Double score;

    /** 最近一次召回时间 */
    private LocalDateTime lastRecalledAt;

    /** 是否已提升到 MEMORY.md */
    private Boolean promoted;

    /** Times this candidate was reviewed but not promoted (Dream v2, Phase 1 write-only) */
    private Integer reviewCount;

    /** Last time this candidate was reviewed during a dream run */
    private LocalDateTime lastReviewedAt;

    /** Memory subject this recall belongs to (e.g. "user:42"); null for shared/legacy rows. */
    private String ownerKey;

    /** Visibility scope: PERSONAL / TEAM / GLOBAL. Defaults to TEAM at the DB level. */
    private String scope;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
