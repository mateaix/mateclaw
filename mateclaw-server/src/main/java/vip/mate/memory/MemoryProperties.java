package vip.mate.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Set;

/**
 * 记忆自动更新配置
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mate.memory")
public class MemoryProperties {

    /** 启用对话后自动记忆提取 */
    private boolean autoSummarizeEnabled = true;

    /** 触发记忆提取的最小消息数 */
    private int minMessagesForSummarize = 4;

    /** 触发记忆提取的最小用户消息长度 */
    private int minUserMessageLength = 10;

    /** 跳过 cron 触发的对话（避免递归写入） */
    private boolean skipCronConversations = true;

    /** 记忆摘要的最大输出 token 数 */
    private int summaryMaxTokens = 1000;

    /** 启用定期记忆整合（daily notes → MEMORY.md） */
    private boolean emergenceEnabled = true;

    /** 记忆整合扫描的天数范围 */
    private int emergenceDayRange = 7;

    /** 同一 Agent 记忆提取的冷却时间（分钟） */
    private int cooldownMinutes = 5;

    /** 构建对话 transcript 时的最大消息数（防止过长） */
    private int maxTranscriptMessages = 30;

    // ==================== Dreaming 配置 ====================

    /** 启用定时 Dreaming（自动执行记忆整合） */
    private boolean dreamingEnabled = true;

    /** Dreaming 调度 cron 表达式（Spring 6 字段格式，默认每天凌晨 3 点） */
    private String dreamingCron = "0 0 3 * * ?";

    /** 记忆召回评分阈值，低于此分的候选不进入 LLM 整合 */
    private double emergenceScoreThreshold = 0.4;

    /** 最少召回次数门控（低于此值直接跳过评分） */
    private int emergenceMinRecallCount = 3;

    /** 最少不同查询数门控（低于此值直接跳过评分） */
    private int emergenceMinUniqueQueries = 2;

    /** 候选最大年龄（天），超过此值不参与评分。0=不限 */
    private int emergenceMaxAgeDays = 30;

    // ==================== Memory Nudge 配置 ====================

    /** 启用对话中记忆自省（每 N 轮异步提取结构化记忆） */
    private boolean nudgeEnabled = true;

    /** 每多少轮消息触发一次 Nudge（0=关闭） */
    private int nudgeTurnInterval = 6;

    /** Nudge 审查的最大消息数 */
    private int nudgeMaxMessages = 20;

    /** 同一 Agent Nudge 冷却时间（分钟） */
    private int nudgeCooldownMinutes = 10;

    // ==================== Provider 管理 ====================

    /** 禁用的 MemoryProvider ID 集合（例如 "structured", "session_search"） */
    private Set<String> disabledProviders = new HashSet<>();

    // ==================== Always-on injection budget ====================

    /**
     * Character budget for the always-on structured memory block injected into
     * every system prompt (user + feedback entries). When exceeded, the
     * most-recently-updated entries are kept and older ones are omitted, so
     * accumulated memory cannot grow the per-turn context without bound.
     * 0 = unlimited (legacy behavior).
     */
    private int systemBlockMaxChars = 4000;

    /**
     * Hard cap on the number of entries injected per structured type in the
     * always-on block. Keeps the newest entries per type even when the global
     * character budget would otherwise admit more from a single type.
     * 0 = unlimited.
     */
    private int systemBlockMaxEntriesPerType = 40;

    /**
     * Enable the nightly consolidation pass over always-on structured memory
     * (user/feedback): an LLM merges near-duplicate and stale entries so the
     * files shrink on disk rather than only being trimmed at injection time.
     */
    private boolean structuredConsolidationEnabled = true;

    /**
     * Minimum entry count before a structured type is consolidated. Below this
     * the file is already small, so the LLM call is skipped.
     */
    private int structuredConsolidationMinEntries = 8;

    /**
     * Cron expression for the structured-memory consolidation maintenance task.
     * Independent of {@code dreamingCron} so the two passes can be scheduled
     * (and gated) separately. Default: 3:30 AM daily, after nightly emergence.
     */
    private String structuredConsolidationCron = "0 30 3 * * ?";

    /**
     * Maximum number of owner buckets (shared + personal) consolidated per agent
     * per run. Bounds LLM cost on agents with many per-owner memory buckets;
     * remaining owners are picked up on subsequent runs. 0 = unlimited.
     */
    private int structuredConsolidationMaxOwnersPerRun = 50;

    /**
     * Deterministic character ceiling for PROFILE.md, the always-on user-profile
     * file. The summarization pass rewrites it and is asked to stay concise; this
     * is the hard backstop that truncates at a section boundary if it overruns.
     * 0 = unlimited.
     */
    private int profileMaxChars = 4000;

    /**
     * Deterministic character ceiling for MEMORY.md, the always-on long-term
     * memory file rewritten by summarization and emergence. 0 = unlimited.
     */
    private int memoryMdMaxChars = 8000;

    // ==================== Dream v2 Feature Flags ====================

    // --- Phase 1: Lifecycle mediator wiring ---

    /** Enable MemoryLifecycleMediator prefetch/sync/onSessionEnd chain; off = Phase 0 behavior only */
    private boolean lifecycleMediatorEnabled = false;

    /** Dream v2 configuration */
    private DreamProperties dream = new DreamProperties();

    // --- Phase 2: SOUL auto-evolution and provider decorators ---

    /** SOUL.md auto-evolution trigger interval (0 = off) */
    private int soulUpdateInterval = 0;

    /** Provider decorator retry attempts (1 = no retry) */
    private int providerRetryAttempts = 1;

    /** Enable provider metrics collection */
    private boolean providerMetricsEnabled = false;

    // --- Phase 3: Fact projection ---

    /** Fact projection configuration */
    private FactProperties fact = new FactProperties();

    @Data
    public static class DreamProperties {
        /** Enable focused dream mode endpoint */
        private boolean focusedEnabled = false;

        /** Enable monthly dream archive rotation */
        private boolean archiveEnabled = false;

        /** Days to keep in DREAMS.md before archiving */
        private int archiveKeepDays = 30;

        /** Maximum candidates per dream consolidation run */
        private int maxCandidatesPerDream = 100;
    }

    @Data
    public static class FactProperties {
        /** Enable fact projection rebuild */
        private boolean projectionEnabled = false;

        /** Fact projection rebuild cron expression */
        private String projectionRebuildCron = "0 */30 * * * ?";

        /** Enable LLM-based entity extraction (false = pattern-only) */
        private boolean llmExtractionEnabled = false;

        /** Enable contradiction detection in dream consolidation */
        private boolean contradictionCheckEnabled = false;

        /** Trust score half-life in days for time decay */
        private int trustHalfLifeDays = 60;

        /** Enable UI Forget button on facts (Phase 3) */
        private boolean forgetEnabled = false;
    }
}
