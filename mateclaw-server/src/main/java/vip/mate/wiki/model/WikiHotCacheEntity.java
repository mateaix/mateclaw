package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One row per knowledge base — the rolling "what happened recently here"
 * snapshot rendered into the agent system prompt at conversation start.
 *
 * <p>The body is markdown produced from four sections (Last Updated, Key
 * Recent Facts, Recent Changes, Active Threads) by an LLM rebuild call.
 * This entity is the persistence shape; the value-object form lives in
 * {@link vip.mate.wiki.hotcache.HotCacheContent}.
 */
@Data
@TableName("mate_wiki_hot_cache")
public class WikiHotCacheEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long kbId;

    /** Markdown body, target ≤ 4096 chars after rendering. */
    private String content;

    /** SHA-256 of {@link #content}; lets the updater short-circuit identical rewrites. */
    private String contentHash;

    private LocalDateTime lastUpdated;

    /** {@link vip.mate.wiki.hotcache.HotCacheUpdateReason#name()} for the most recent rebuild. */
    private String updateReason;

    private Long rebuildCount;

    private LocalDateTime lastRebuildStartedAt;
    private Long lastRebuildDurationMs;
    private String lastRebuildError;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
