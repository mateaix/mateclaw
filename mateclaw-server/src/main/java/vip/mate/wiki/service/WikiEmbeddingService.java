package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;
import vip.mate.llm.embedding.EmbeddingModelFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.system.model.SystemSettingEntity;
import vip.mate.system.repository.SystemSettingMapper;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RFC-011 + Embedding-UI-Config: Wiki 嵌入服务
 * <p>
 * 按知识库（KB）动态解析应使用的 Embedding 模型，解析优先级：
 * <ol>
 *   <li>KB 级绑定：{@link WikiKnowledgeBaseEntity#getEmbeddingModelId()}</li>
 *   <li>系统默认：{@code mate_system_setting.setting_key = 'embedding.default.model.id'}</li>
 *   <li>任意 enabled 的 embedding 模型（取第一个）</li>
 *   <li>全无 → 返回不可用，上层降级（语义搜索返回空，关键词搜索仍可用）</li>
 * </ol>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiEmbeddingService {

    private final WikiChunkMapper chunkMapper;
    private final WikiPageMapper pageMapper;
    private final WikiRawMaterialMapper rawMaterialMapper;
    private final WikiProperties properties;
    private final EmbeddingModelFactory factory;
    private final ModelConfigService modelConfigService;
    private final WikiKnowledgeBaseService kbService;
    private final SystemSettingMapper systemSettingMapper;
    private final vip.mate.llm.service.ModelProviderService modelProviderService;
    private final WikiEmbeddingInputBuilder inputBuilder;

    /** 系统默认 embedding 模型的 mate_system_setting key */
    public static final String SYSTEM_SETTING_DEFAULT_EMBEDDING_ID = "embedding.default.model.id";

    /**
     * Returns the embedding input format version this service stamps onto
     * each chunk. The builder's constant is the source of truth; the
     * {@code mate.wiki.embedding-text-version-current} property exists only
     * to support ops overrides and is validated against the builder at
     * startup ({@link #verifyConfiguredInputVersion()}).
     */
    public String currentInputVersion() {
        String configured = properties.getEmbeddingTextVersionCurrent();
        return (configured == null || configured.isBlank()) ? inputBuilder.currentVersion() : configured.trim();
    }

    /**
     * Validate the configured embedding input version against the builder
     * constant on startup. A blank config is normal (the builder version is
     * used). A config below the builder is allowed with a WARN so a KB can
     * be embedded against an older format during a gradual rollback. A
     * config above the builder fails fast — it almost always means the
     * config was deployed ahead of the code.
     */
    @PostConstruct
    void verifyConfiguredInputVersion() {
        String configured = properties.getEmbeddingTextVersionCurrent();
        if (configured == null || configured.isBlank()) {
            log.info("[WikiEmbedding] Embedding input version: {} (from builder)", inputBuilder.currentVersion());
            return;
        }
        String builderVersion = inputBuilder.currentVersion();
        int cmp = compareInputVersions(configured.trim(), builderVersion);
        if (cmp == 0) {
            log.info("[WikiEmbedding] Embedding input version: {} (matches builder)", configured);
        } else if (cmp < 0) {
            log.warn("[WikiEmbedding] Configured embedding input version {} is older than builder {}; "
                    + "new embeddings will still be stamped with the configured value. "
                    + "Clear mate.wiki.embedding-text-version-current to use the builder default.",
                    configured, builderVersion);
        } else {
            throw new IllegalStateException(
                    "Configured embedding input version " + configured + " is newer than builder version "
                            + builderVersion + ". The builder code is older than the deployment config; "
                            + "upgrade the application or clear mate.wiki.embedding-text-version-current.");
        }
    }

    /**
     * Compare version tags of the form {@code v\d+} numerically (so v2 > v10
     * does not happen). Falls back to case-insensitive string compare when
     * either side does not match the expected pattern.
     */
    static int compareInputVersions(String a, String b) {
        Integer ai = parseNumericVersion(a);
        Integer bi = parseNumericVersion(b);
        if (ai != null && bi != null) {
            return Integer.compare(ai, bi);
        }
        return a.compareToIgnoreCase(b);
    }

    private static Integer parseNumericVersion(String tag) {
        if (tag == null || tag.length() < 2) return null;
        if (tag.charAt(0) != 'v' && tag.charAt(0) != 'V') return null;
        try {
            return Integer.parseInt(tag.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 判断全局是否有可用的 embedding 能力（任何 enabled 的 embedding 模型配置）
     */
    public boolean isAvailable() {
        try {
            ModelConfigEntity fallback = modelConfigService.findFirstEnabledEmbedding();
            return fallback != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析指定 KB 应使用的 embedding 模型与模型实例
     */
    public Resolved resolveForKb(Long kbId) {
        // 优先级 1：KB 级绑定
        try {
            WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
            if (kb != null && kb.getEmbeddingModelId() != null) {
                ModelConfigEntity model = safeGetModel(kb.getEmbeddingModelId());
                if (isUsable(model)) {
                    return new Resolved(factory.build(model), model.getModelName());
                }
                log.warn("[WikiEmbedding] KB {} bound embedding model {} is unusable, falling back",
                        kbId, kb.getEmbeddingModelId());
            }
        } catch (Exception e) {
            log.debug("[WikiEmbedding] KB binding resolve failed for kbId={}: {}", kbId, e.getMessage());
        }

        // 优先级 2：系统默认
        Long defaultId = readSystemDefaultEmbeddingId();
        if (defaultId != null) {
            ModelConfigEntity model = safeGetModel(defaultId);
            if (isUsable(model)) {
                try {
                    return new Resolved(factory.build(model), model.getModelName());
                } catch (Exception e) {
                    log.warn("[WikiEmbedding] System default embedding model {} build failed: {}", defaultId, e.getMessage());
                }
            } else {
                log.warn("[WikiEmbedding] System default embedding model {} is unusable, falling back", defaultId);
            }
        }

        // 优先级 3：任意 enabled
        ModelConfigEntity anyEnabled = modelConfigService.findFirstEnabledEmbedding();
        if (isUsable(anyEnabled)) {
            try {
                return new Resolved(factory.build(anyEnabled), anyEnabled.getModelName());
            } catch (Exception e) {
                log.warn("[WikiEmbedding] Fallback embedding model {} build failed: {}", anyEnabled.getId(), e.getMessage());
            }
        }

        log.warn("[WikiEmbedding] No usable embedding model configured. "
                + "Configure one under Settings → Models → Embedding tab.");
        return null;
    }

    /**
     * 批量嵌入指定 KB 中缺失 embedding 的 chunk。
     * <p>
     * Pending criteria: embedding is NULL, the stored embedding_model differs
     * from the currently-resolved model, or the stored embedding_text_version
     * differs from the active builder version. Switching the embedding model
     * or bumping the input format both trigger a full re-embed pass.
     */
    public int embedMissingChunks(Long kbId) {
        Resolved r = resolveForKb(kbId);
        if (r == null) {
            log.debug("[WikiEmbedding] Skipping kbId={} — no embedding model available", kbId);
            return 0;
        }

        String modelName = r.modelName();
        String inputVersion = currentInputVersion();
        List<WikiChunkEntity> pending = chunkMapper.selectList(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .eq(WikiChunkEntity::getKbId, kbId)
                        .and(w -> w.isNull(WikiChunkEntity::getEmbedding)
                                   .or().ne(WikiChunkEntity::getEmbeddingModel, modelName)
                                   .or().isNull(WikiChunkEntity::getEmbeddingTextVersion)
                                   .or().ne(WikiChunkEntity::getEmbeddingTextVersion, inputVersion)));

        if (pending.isEmpty()) {
            log.debug("[WikiEmbedding] No chunks need embedding for kbId={}", kbId);
            return 0;
        }

        RawTitleLookup titleLookup = preloadTitlesFor(pending);
        int batchSize = Math.max(1, properties.getEmbeddingBatchSize());
        int maxChars = Math.max(500, properties.getEmbeddingMaxChars());
        int threshold = Math.max(1, properties.getEmbeddingConsecutiveFailureThreshold());
        int total = 0;
        // Consecutive failure counter: resets on any successful batch / long
        // chunk, increments when a unit returns zero progress. Crossing the
        // threshold trips the circuit and aborts the rest of this pass.
        int consecutiveFailures = 0;

        for (int offset = 0; offset < pending.size(); offset += batchSize) {
            List<WikiChunkEntity> batch = pending.subList(offset, Math.min(offset + batchSize, pending.size()));

            // Split the batch into short chunks (direct batch embed) and long chunks
            // (split into sub-segments, embed each, then mean-pool into a single vector)
            List<WikiChunkEntity> shortBatch = new ArrayList<>();
            List<WikiChunkEntity> longChunks = new ArrayList<>();
            for (WikiChunkEntity c : batch) {
                if (c.getContent() == null || c.getContent().isBlank()) continue;
                if (c.getContent().length() <= maxChars) {
                    shortBatch.add(c);
                } else {
                    longChunks.add(c);
                }
            }

            // Short chunks: existing batch path
            if (!shortBatch.isEmpty()) {
                int embedded = embedShortBatch(shortBatch, r.model(), modelName, kbId, inputVersion, titleLookup);
                total += embedded;
                if (embedded == 0) {
                    consecutiveFailures++;
                    if (consecutiveFailures >= threshold) {
                        int remaining = pending.size() - (offset + batch.size());
                        throw circuitOpen(kbId, modelName, consecutiveFailures, remaining);
                    }
                } else {
                    consecutiveFailures = 0;
                }
            }

            // Long chunks: each goes through sub-segment split + mean pool
            for (WikiChunkEntity longChunk : longChunks) {
                if (embedLongChunk(longChunk, r.model(), modelName, maxChars, inputVersion, titleLookup)) {
                    total++;
                    consecutiveFailures = 0;
                } else {
                    consecutiveFailures++;
                    if (consecutiveFailures >= threshold) {
                        int remaining = pending.size() - (offset + batch.size());
                        throw circuitOpen(kbId, modelName, consecutiveFailures, remaining);
                    }
                }
            }
        }

        if (total == 0 && !pending.isEmpty()) {
            log.warn("[WikiEmbedding] ALL {} chunks failed for kbId={} model={} — check API key / model availability",
                    pending.size(), kbId, modelName);
        } else {
            log.info("[WikiEmbedding] Embedded {}/{} chunks for kbId={}, model={}",
                    total, pending.size(), kbId, modelName);
        }
        return total;
    }

    private vip.mate.wiki.job.WikiEmbeddingProviderFailingException circuitOpen(
            Long kbId, String modelName, int failures, int remaining) {
        String message = "Embedding provider unavailable: " + failures
                + " consecutive batch failures (kbId=" + kbId + ", model=" + modelName
                + "). Aborted with " + remaining + " chunk(s) still pending.";
        log.warn("[WikiEmbedding] Circuit opened — {}", message);
        return new vip.mate.wiki.job.WikiEmbeddingProviderFailingException(message, failures, remaining);
    }

    /**
     * Embed a batch of chunks whose content fits within the per-segment char limit.
     * One API call per batch; individual results are persisted independently.
     * Returns the number of chunks that were successfully embedded and persisted.
     */
    private int embedShortBatch(List<WikiChunkEntity> batch, EmbeddingModel model,
                                 String modelName, Long kbId,
                                 String inputVersion, RawTitleLookup titleLookup) {
        try {
            List<String> inputs = batch.stream()
                    .map(c -> inputBuilder.build(c, titleLookup))
                    .toList();
            EmbeddingResponse resp = model.call(new EmbeddingRequest(inputs, null));
            for (int i = 0; i < batch.size(); i++) {
                float[] vec = resp.getResults().get(i).getOutput();
                WikiChunkEntity chunk = batch.get(i);
                chunk.setEmbedding(floatsToBytes(vec));
                chunk.setEmbeddingModel(modelName);
                chunk.setEmbeddingTextVersion(inputVersion);
                chunkMapper.updateById(chunk);
            }
            return batch.size();
        } catch (Exception e) {
            log.error("[WikiEmbedding] Short-batch embedding failed (kbId={}, batchSize={}, model={}): {}",
                    kbId, batch.size(), modelName, e.getMessage());
            return 0;
        }
    }

    /**
     * Embed a single chunk whose content exceeds the per-segment char limit:
     *   1. Split into sub-segments (each ≤ maxChars) along sentence boundaries
     *   2. Batch-embed all sub-segments in one API call
     *   3. Fall back to per-segment retry if the batch fails (partial recovery)
     *   4. Mean-pool successful vectors and re-normalize (L2) to restore unit length
     *   5. Store the single pooled vector against this chunk's id
     * <p>
     * Returns true if at least one sub-segment succeeded and the chunk was persisted.
     */
    private boolean embedLongChunk(WikiChunkEntity chunk, EmbeddingModel model,
                                    String modelName, int maxChars,
                                    String inputVersion, RawTitleLookup titleLookup) {
        // Prepend the metadata prefix to every sub-segment so the per-segment
        // embeddings carry the same context before mean-pooling. The split
        // budget is reduced by the prefix length to keep each enriched segment
        // under the provider's per-input cap; the floor of 500 keeps the
        // splitter from collapsing to single-char windows when a pathological
        // metadata prefix appears.
        String prefix = inputBuilder.buildPrefix(chunk, titleLookup);
        int segmentBudget = Math.max(500, maxChars - prefix.length());
        List<String> rawSegments = splitForEmbedding(chunk.getContent(), segmentBudget);
        if (rawSegments.isEmpty()) {
            log.warn("[WikiEmbedding] Chunk {} produced no embeddable segments after split", chunk.getId());
            return false;
        }
        List<String> segments = prefix.isEmpty()
                ? rawSegments
                : rawSegments.stream().map(s -> prefix + s).toList();
        log.info("[WikiEmbedding] Chunk {} ({} chars) split into {} sub-segments",
                chunk.getId(), chunk.getContent().length(), segments.size());

        List<float[]> vectors = new ArrayList<>();
        try {
            EmbeddingResponse resp = model.call(new EmbeddingRequest(segments, null));
            for (int i = 0; i < resp.getResults().size(); i++) {
                vectors.add(resp.getResults().get(i).getOutput());
            }
        } catch (Exception e) {
            // Batch failed — degrade to per-segment retry; whatever succeeds is still usable
            log.warn("[WikiEmbedding] Batch of {} sub-segments failed for chunk {}: {} — retrying one-by-one",
                    segments.size(), chunk.getId(), e.getMessage());
            vectors.clear();
            for (String seg : segments) {
                try {
                    EmbeddingResponse single = model.call(new EmbeddingRequest(List.of(seg), null));
                    vectors.add(single.getResults().get(0).getOutput());
                } catch (Exception ignored) {
                    // Skip this segment; proceed with remaining
                }
            }
        }

        if (vectors.isEmpty()) {
            log.error("[WikiEmbedding] All {} sub-segments failed for chunk {}", segments.size(), chunk.getId());
            return false;
        }

        float[] pooled = averageAndNormalize(vectors);
        chunk.setEmbedding(floatsToBytes(pooled));
        chunk.setEmbeddingModel(modelName);
        chunk.setEmbeddingTextVersion(inputVersion);
        chunkMapper.updateById(chunk);
        return true;
    }

    /**
     * Split a long text into sub-segments of at most {@code maxChars} characters,
     * respecting sentence boundaries when possible.
     * Boundary priority: double-newline > Chinese period > English period > newline > space.
     * Hard-truncation fallback is applied when no boundary is found (e.g. a single
     * run-on passage with no punctuation).
     */
    private List<String> splitForEmbedding(String text, int maxChars) {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() <= maxChars) return List.of(text);

        List<String> segments = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int boundary = findEmbeddingBoundary(text, start, end, maxChars);
                if (boundary > start) end = boundary;
            }
            String seg = text.substring(start, end).trim();
            if (!seg.isBlank()) {
                // Hard-truncation fallback: a boundary beyond maxChars shouldn't happen
                // with the logic above, but guard against edge cases defensively.
                if (seg.length() > maxChars) {
                    seg = seg.substring(0, maxChars);
                }
                segments.add(seg);
            }
            int nextStart = end;
            if (nextStart <= start) nextStart = start + maxChars; // prevent infinite loop
            start = nextStart;
        }
        return segments;
    }

    /**
     * Find a sentence boundary within [start, end] for embedding sub-segmentation.
     * Only returns boundaries past the midpoint so we don't produce tiny segments.
     */
    private int findEmbeddingBoundary(String text, int start, int end, int maxChars) {
        int halfChunk = start + maxChars / 2;

        int lastPara = text.lastIndexOf("\n\n", end);
        if (lastPara > halfChunk) return lastPara + 2;

        int lastChinese = text.lastIndexOf("。", end);
        if (lastChinese > halfChunk) return lastChinese + 1;

        for (int i = end - 1; i > halfChunk; i--) {
            if (text.charAt(i) == '.' && i + 1 < text.length()
                    && (text.charAt(i + 1) == ' ' || text.charAt(i + 1) == '\n')) {
                return i + 1;
            }
        }

        int lastNewline = text.lastIndexOf("\n", end);
        if (lastNewline > halfChunk) return lastNewline + 1;

        int lastSpace = text.lastIndexOf(" ", end);
        if (lastSpace > halfChunk) return lastSpace + 1;

        return end; // hard cut
    }

    /**
     * Embed a wiki page's content directly so the semantic retriever can match
     * vocabulary that exists in the synthesised page but not in any source
     * raw's chunks (typical for transformation-generated synthesis pages).
     * Idempotent: skips when the stored embedding is already current for the
     * resolved model + input version.
     *
     * @return {@code true} when the page row was updated with a fresh embedding
     */
    public boolean embedPage(Long pageId) {
        if (pageId == null) return false;
        WikiPageEntity page = pageMapper.selectById(pageId);
        if (page == null) {
            log.warn("[WikiEmbedding] embedPage: page not found id={}", pageId);
            return false;
        }
        Resolved r = resolveForKb(page.getKbId());
        if (r == null) {
            log.debug("[WikiEmbedding] embedPage: no embedding model for kbId={}", page.getKbId());
            return false;
        }
        String inputVersion = currentInputVersion();
        // Short-circuit when this page is already embedded against the same
        // model + input format — nothing to do.
        if (page.getEmbedding() != null
                && r.modelName().equals(page.getEmbeddingModel())
                && inputVersion.equals(page.getEmbeddingTextVersion())) {
            return false;
        }

        String input = buildPageEmbeddingInput(page);
        if (input.isBlank()) return false;
        int maxChars = Math.max(500, properties.getEmbeddingMaxChars());
        if (input.length() > maxChars) input = input.substring(0, maxChars);

        try {
            EmbeddingResponse resp = r.model().call(new EmbeddingRequest(List.of(input), null));
            float[] vec = resp.getResults().get(0).getOutput();
            page.setEmbedding(floatsToBytes(vec));
            page.setEmbeddingModel(r.modelName());
            page.setEmbeddingTextVersion(inputVersion);
            pageMapper.updateById(page);
            log.info("[WikiEmbedding] Embedded page id={} kbId={} model={} ({} chars)",
                    pageId, page.getKbId(), r.modelName(), input.length());
            return true;
        } catch (Exception e) {
            log.warn("[WikiEmbedding] embedPage failed id={}: {}", pageId, e.getMessage());
            return false;
        }
    }

    /** Concatenates the fields that best capture a page's topic — title +
     *  summary + content prefix — so the embedding picks up both the
     *  vocabulary the LLM authored and the source-derived material. */
    private String buildPageEmbeddingInput(WikiPageEntity page) {
        StringBuilder sb = new StringBuilder();
        if (page.getTitle() != null && !page.getTitle().isBlank()) {
            sb.append("# ").append(page.getTitle()).append("\n\n");
        }
        if (page.getSummary() != null && !page.getSummary().isBlank()) {
            sb.append(page.getSummary()).append("\n\n");
        }
        if (page.getContent() != null && !page.getContent().isBlank()) {
            sb.append(page.getContent());
        }
        return sb.toString();
    }

    /**
     * 查询向量化（混合搜索时调用，需指定 KB 以便解析对应模型）
     */
    public float[] embedQuery(Long kbId, String query) {
        Resolved r = resolveForKb(kbId);
        if (r == null) return null;
        // Defensive truncation: user queries are usually short, but guard against
        // callers that accidentally pass document-sized text as a query.
        int maxChars = Math.max(500, properties.getEmbeddingMaxChars());
        String safeQuery = (query != null && query.length() > maxChars)
                ? query.substring(0, maxChars) : query;
        try {
            EmbeddingResponse resp = r.model().call(new EmbeddingRequest(List.of(safeQuery), null));
            return resp.getResults().get(0).getOutput();
        } catch (Exception e) {
            log.error("[WikiEmbedding] Query embedding failed for kbId={}: {}", kbId, e.getMessage());
            return null;
        }
    }

    /**
     * Snapshot of how many chunks in a KB still need to be re-embedded
     * against the current model + input version. Powers the admin "embedding
     * drift" indicator without exposing internal pending logic.
     */
    public EmbeddingDrift describeDrift(Long kbId) {
        String inputVersion = currentInputVersion();
        Resolved r = resolveForKb(kbId);
        String modelName = r == null ? null : r.modelName();

        long totalEmbedded = chunkMapper.selectCount(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .eq(WikiChunkEntity::getKbId, kbId)
                        .isNotNull(WikiChunkEntity::getEmbedding));

        LambdaQueryWrapper<WikiChunkEntity> pendingQ = new LambdaQueryWrapper<WikiChunkEntity>()
                .eq(WikiChunkEntity::getKbId, kbId)
                .and(w -> {
                    w.isNull(WikiChunkEntity::getEmbedding)
                            .or().isNull(WikiChunkEntity::getEmbeddingTextVersion)
                            .or().ne(WikiChunkEntity::getEmbeddingTextVersion, inputVersion);
                    if (modelName != null) {
                        w.or().ne(WikiChunkEntity::getEmbeddingModel, modelName);
                    }
                });
        List<WikiChunkEntity> pending = chunkMapper.selectList(pendingQ);

        long pendingChars = 0;
        for (WikiChunkEntity c : pending) {
            if (c.getContent() != null) pendingChars += c.getContent().length();
        }
        // Provider-agnostic token approximation; ~4 chars per token covers
        // English and is conservative for Chinese (which is denser per token).
        long pendingTokens = pendingChars / 4;

        return new EmbeddingDrift(inputVersion, pending.size(), totalEmbedded, pendingTokens);
    }

    /** Result of {@link #describeDrift(Long)}; serialized into KB stats. */
    public record EmbeddingDrift(
            String currentEmbeddingTextVersion,
            int pendingReembedChunks,
            long totalEmbeddedChunks,
            long pendingReembedEstimatedTokens) {}

    /**
     * 清空指定 KB 的所有 embedding（模型切换时调用）
     */
    public void clearEmbeddings(Long kbId) {
        chunkMapper.update(null, new LambdaUpdateWrapper<WikiChunkEntity>()
                .eq(WikiChunkEntity::getKbId, kbId)
                .set(WikiChunkEntity::getEmbedding, null)
                .set(WikiChunkEntity::getEmbeddingModel, null)
                .set(WikiChunkEntity::getEmbeddingTextVersion, null));
        log.info("[WikiEmbedding] Cleared all embeddings for kbId={}", kbId);
    }

    private RawTitleLookup preloadTitlesFor(List<WikiChunkEntity> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return RawTitleLookups.empty();
        }
        Set<Long> rawIds = new HashSet<>();
        for (WikiChunkEntity c : chunks) {
            if (c.getRawId() != null) rawIds.add(c.getRawId());
        }
        return RawTitleLookups.preload(rawMaterialMapper, rawIds);
    }

    // ==================== 私有 helper ====================

    private ModelConfigEntity safeGetModel(Long id) {
        try {
            return modelConfigService.getModel(id);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isUsable(ModelConfigEntity model) {
        if (model == null || !Boolean.TRUE.equals(model.getEnabled())
                || !"embedding".equals(model.getModelType())) {
            return false;
        }
        // Skip models whose provider lacks a valid API key (mirrors chat model path fix 341ad1f)
        try {
            return modelProviderService.isProviderConfigured(model.getProvider());
        } catch (Exception e) {
            log.debug("[WikiEmbedding] Provider check failed for model {}: {}", model.getId(), e.getMessage());
            return false;
        }
    }

    private Long readSystemDefaultEmbeddingId() {
        try {
            SystemSettingEntity entity = systemSettingMapper.selectOne(
                    new LambdaQueryWrapper<SystemSettingEntity>()
                            .eq(SystemSettingEntity::getSettingKey, SYSTEM_SETTING_DEFAULT_EMBEDDING_ID)
                            .last("LIMIT 1"));
            if (entity == null || entity.getSettingValue() == null || entity.getSettingValue().isBlank()) {
                return null;
            }
            return Long.parseLong(entity.getSettingValue().trim());
        } catch (Exception e) {
            log.debug("[WikiEmbedding] Failed to read system default embedding id: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 向量序列化 ====================

    public static byte[] floatsToBytes(float[] vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vec) buf.putFloat(v);
        return buf.array();
    }

    public static float[] bytesToFloats(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[bytes.length / 4];
        for (int i = 0; i < vec.length; i++) vec[i] = buf.getFloat();
        return vec;
    }

    /**
     * Arithmetic mean of N equal-length vectors, followed by L2 normalization.
     * <p>
     * Individual embedding outputs are usually unit vectors, but the arithmetic mean
     * of multiple unit vectors is generally not unit length (||v̄|| < 1 unless all
     * inputs are identical). Re-normalizing to unit length preserves the cosine
     * similarity semantics used by downstream retrievers.
     */
    public static float[] averageAndNormalize(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            throw new IllegalArgumentException("vectors must not be empty");
        }
        int dim = vectors.get(0).length;
        float[] avg = new float[dim];
        for (float[] v : vectors) {
            if (v.length != dim) {
                throw new IllegalArgumentException("dimension mismatch: expected " + dim + " got " + v.length);
            }
            for (int i = 0; i < dim; i++) avg[i] += v[i];
        }
        float n = vectors.size();
        for (int i = 0; i < dim; i++) avg[i] /= n;

        double norm = 0;
        for (float x : avg) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            float invNorm = (float) (1.0 / norm);
            for (int i = 0; i < dim; i++) avg[i] *= invNorm;
        }
        return avg;
    }

    /** 余弦相似度 */
    public static float cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0f;
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denom == 0 ? 0f : dot / denom;
    }

    /** 解析结果 DTO */
    public record Resolved(EmbeddingModel model, String modelName) {}

    /**
     * 暴露 factory 给外部（如测试连通性 API）
     */
    public EmbeddingModelFactory getFactory() {
        return factory;
    }
}
