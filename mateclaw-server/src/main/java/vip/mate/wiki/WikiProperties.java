package vip.mate.wiki;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Wiki 知识库配置
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mate.wiki")
public class WikiProperties {

    /** 是否启用 Wiki 知识库功能 */
    private boolean enabled = true;

    /**
     * LLM 单次处理最大字符数（超过则分块）。
     * <p>
     * RFC-012：默认从 30000 下调到 15000 —— 单 chunk 输出 tokens 砍半、并行饱和度更高、
     * 质量也更稳定。中端模型（qwen-plus/claude-sonnet）建议 12000-20000，
     * 旗舰模型（qwen-max/claude-opus）可调大到 20000-30000。
     */
    private int maxChunkSize = 15000;

    /**
     * 同一次 processAllPending 下同时处理的原始材料数上限。
     * <p>
     * RFC-012 Change 1：保护共享的 @Async 线程池（max=16）不被 wiki 长时间占满，
     * 同时给材料级并发定一个可控的上限，避免 LLM 提供方触发限流。
     */
    private int maxParallelRawMaterials = 3;

    /**
     * Max parallel chunks within a single raw material.
     * <p>
     * RFC-047 P3: Changed from 5 to 1. Parallel chunks all share the same existingPagesIndex
     * snapshot, so chunk N cannot see pages created by chunk N-1 — causing duplicate pages and
     * stale-index collisions. Serializing chunks eliminates this class of bug. Document-level
     * parallelism (maxParallelRawMaterials) is preserved, so overall throughput is unchanged
     * for multi-document batches.
     */
    private int maxParallelChunks = 1;

    /**
     * 单个 chunk 内 phase B 阶段的 page 并行处理数上限。
     * <p>
     * RFC-012 follow-up #3：原实现 phase B 的 create / merge 循环逐页串行调用 LLM，
     * 单 chunk N 个 page 就要串行 N 次 LLM 调用——一个卡超时整条流水线停摆。
     * 改为受此 Semaphore 控制的并行，默认 3。结合 maxParallelRawMaterials × maxParallelChunks
     * × maxParallelPhaseBPages = 3 × 5 × 3 = 45 的理论最大并发，实际按 LLM 限流为准。
     */
    private int maxParallelPhaseBPages = 3;

    /** 注入 agent prompt 的最大字符数 */
    private int maxContextChars = 10000;

    /** 单个原始材料最多生成的 Wiki 页面数 */
    private int maxPagesPerRaw = 15;

    /** 上传后是否自动触发处理 */
    private boolean autoProcessOnUpload = true;

    /** 上传文件存储目录 */
    private String uploadDir = "./data/wiki-uploads";

    /** 目录扫描最大文件数 */
    private int maxScanFiles = 500;

    /** 扫描时跳过大于此大小的文件（字节），默认 50MB */
    private long maxScanFileSize = 50 * 1024 * 1024;

    /**
     * Wiki LLM 重试最大尝试次数（含首次）。
     * <p>
     * RFC-012 M1：旧实现无最大次数，遇到 nginx 504 这种"反复瞬时"错误会永远重试。
     * 设为 5 后单 chunk 最多走 5 轮，配合 llmMaxTotalDurationMs 共同保证有界停止。
     */
    private int llmMaxAttempts = 5;

    /**
     * Wiki LLM 重试总耗时上限（毫秒），从首次调用开始计时。
     * <p>
     * RFC-012 M1：单 chunk LLM 调用 + 重试的硬封顶，超过即放弃，让该 chunk 进入 failed 计数。
     * 默认 4 分钟。
     */
    private long llmMaxTotalDurationMs = 240_000;

    /**
     * RFC-047 P1: Max pages per BatchCreate LLM call.
     * Pages planned by route are chunked into sub-batches of this size;
     * a local liveIndex is updated between sub-batches so later pages can
     * link to earlier ones created in the same chunk.
     * Default 2: keeps total output tokens well under typical provider caps
     * (~2k–3k completion tokens) so the FILE-block JSON doesn't get truncated
     * mid-object. Raising this risks unparseable JSON skips on long content.
     */
    private int batchCreatePageSize = 2;

    /**
     * RFC-047: Minimum chunk length (chars) for the chunk-fallback mechanism.
     * If route returns 0 create+update entries and the chunk exceeds this threshold,
     * an overview page is auto-injected so no substantial content is silently dropped.
     * Chunks shorter than this (e.g. TOC lines, blank pages) are allowed to produce nothing.
     */
    private int chunkFallbackMinChars = 200;

    /**
     * 是否启用两阶段消化（路由 → 逐页 merge）。
     * <p>
     * RFC-012 M2：true 时单 chunk 的 LLM 输出量大幅缩减，避免 nginx 60s 网关超时。
     * 默认 true（M2 上线）；遇问题可在 application.yml 配 mate.wiki.use-two-phase-digest=false 回退到旧行为。
     */
    private boolean useTwoPhaseDigest = true;

    // ==================== RFC-011: Embedding ====================

    /** 嵌入模型名称（DashScope） */
    private String embeddingModel = "text-embedding-v3";

    /** 嵌入批量大小（一次 API 调用处理多少 chunk） */
    private int embeddingBatchSize = 16;

    /**
     * Embedding 模型单段最大字符数，超过则子段拆分 + 向量均值。
     * <p>
     * 默认 6000（中文安全值，对应 ~4000 token，远小于 text-embedding-v3 的 8192 上限）。
     * 纯英文场景可调大到 7500；其他 embedding 模型切换时按该模型的 token 限制调整。
     */
    private int embeddingMaxChars = 6000;

    /**
     * Expected embedding-input format version. The authoritative source is
     * the builder's {@code CURRENT_INPUT_VERSION} constant; this property
     * exists for staged rollouts and ops overrides.
     * <p>
     * Behavior on startup:
     * <ul>
     *   <li>Blank: use the builder version.</li>
     *   <li>Less than builder version: WARN and continue, so a KB can be
     *       embedded against an older format during a gradual rollback.</li>
     *   <li>Greater than builder version: fail fast — this usually means the
     *       config was deployed ahead of the code that implements that format.</li>
     * </ul>
     */
    private String embeddingTextVersionCurrent = "";

    /**
     * Circuit-breaker threshold: abort an embedding pass after this many
     * consecutive batch failures (auth / rate-limit / network errors that
     * cause an entire batch to embed zero chunks). Without it, a broken
     * provider would silently iterate through every pending chunk in the
     * KB, producing only log noise and wasted wall-clock time before the
     * user can intervene.
     * <p>
     * Set too low and a transient blip aborts a healthy pass; set too
     * high and the user waits forever on a clearly-broken provider.
     * Default 5 covers most real outages while tolerating a couple of
     * isolated 5xx hiccups.
     */
    private int embeddingConsecutiveFailureThreshold = 5;

    /** 混合搜索默认模式：keyword / semantic / hybrid */
    private String searchDefaultMode = "hybrid";

    /**
     * Minimum trimmed char length for a user message before per-turn wiki
     * retrieval kicks in. Short messages like "继续" / "嗯" / "OK" carry no
     * semantic signal and the retriever falls back to whichever pages happen
     * to dominate the index, polluting the prompt with off-topic content.
     */
    private int relevantContextMinQueryLength = 3;

    /**
     * Relative score floor for relevant-wiki hits. A hit is dropped when its
     * score is below {@code topHit.score * this ratio}, so a single strong
     * match does not drag in low-relevance tail pages alongside it. Set to
     * 0 to disable.
     */
    private double relevantContextMinRelativeScore = 0.5;

    // ==================== RFC-031: Light processing tiers ====================

    /** Whether to auto-dispatch a LIGHT_ENRICH job after heavy ingest completes */
    private boolean lightEnrichEnabled = true;

    /** Delay before light enrichment starts (ms) */
    private long lightEnrichDelayMs = 2000;

    /**
     * Minimum ratio of enriched content length to original content length.
     * If the LLM returns text shorter than this ratio, the enrichment is rejected.
     */
    private double wikilinkMinContentRatio = 0.5;

    /** Maximum characters for local repair single-page regeneration */
    private int localRepairMaxChars = 8000;

    /**
     * Whether to run a document-level analysis pass before routing.
     * When enabled, a single LLM call produces a concept map (topics + key_concepts)
     * that is injected into every chunk's route prompt, giving the router global
     * awareness of the document structure and reducing concept omissions.
     * Adds ~1 LLM call and 10-20s per raw material.
     */
    private boolean useDocumentAnalysis = true;

    /**
     * Max characters of document text fed to the analysis pass.
     * Larger values improve coverage but increase prompt tokens.
     * Default 15000 covers most documents while staying well within model limits.
     */
    private int documentAnalysisSampleChars = 15000;

    /**
     * RFC-051 PR-6b: route-phase output binding. When {@code true}, the route
     * LLM call uses a Spring AI {@code BeanOutputConverter<RouteResult>} —
     * the format hint is injected into the user prompt and the response is
     * parsed strictly into the DTO. Failures fall back to the legacy lenient
     * JSON parser so a flaky model never blocks ingest.
     * <p>
     * Default {@code false} keeps existing behavior on first upgrade. Flip on
     * once you've validated the route prompt against the models you actually
     * run (DashScope / OpenAI / Anthropic / DeepSeek tend to be fine; Ollama
     * and weaker models may need the fallback).
     */
    private boolean useStructuredRoute = false;

    /**
     * RFC-051 follow-up: how many pages the enrich service packs into a single
     * LLM call. {@code 1} (default) reproduces the legacy behavior of one
     * call per page. {@code 5}–{@code 10} is reasonable for most chat models;
     * weaker locally-served models may need to stay at 1.
     * <p>
     * Larger batches reduce LLM cost roughly proportional to the batch size,
     * but each batch's prompt grows linearly with the included page bodies,
     * so very long pages still benefit from single-page mode. Pages exceeding
     * {@link #enrichBatchPerPageMaxChars} are excluded from the batch and
     * enriched individually.
     */
    private int enrichBatchSize = 1;

    /**
     * RFC-051 follow-up: per-page content cap when packing pages into an
     * enrich batch. Pages whose body exceeds this size fall through to a
     * single-page enrich call so the batch prompt stays bounded. The cap
     * applies only to the prompt; the applier always sees full content.
     */
    private int enrichBatchPerPageMaxChars = 3000;

    /**
     * RFC-051 §9.4: replace the legacy flat 0.15 relation boost with a
     * normalized score per query, scaled by {@link #relationBoostLambda}.
     * Default {@code false} keeps the legacy ranking; flip on after
     * validating against your retrieval test set.
     * <p>
     * Why it matters: the flat 0.15 was bigger than typical RRF scores
     * (~0.02–0.05), so boosted neighbors routinely leapfrogged real RRF
     * hits. Normalization keeps boost on the same scale as fused scores.
     */
    private boolean useNormalizedRelationBoost = false;

    /**
     * RFC-051 §9.4: maximum boost contribution from the relation pass when
     * {@link #useNormalizedRelationBoost} is on. Each boosted candidate
     * gets {@code (rawRelationScore / maxRawRelationScore) * lambda} added
     * to its fused score. Default {@code 0.05} is roughly the size of a
     * top-3 RRF score, so a max-relation neighbor competes evenly with a
     * top-3 RRF hit but doesn't dominate it.
     */
    private double relationBoostLambda = 0.05;
}
