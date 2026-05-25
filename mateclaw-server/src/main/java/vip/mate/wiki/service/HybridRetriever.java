package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.dto.ImageRef;
import vip.mate.wiki.dto.PageSearchResult;
import vip.mate.wiki.dto.RelatedPageResult;
import vip.mate.wiki.dto.WikiPageLite;
import vip.mate.wiki.metrics.WikiMetrics;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageMapper;
import vip.mate.wiki.retrieval.SnippetExtractor;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RFC-011 + RFC-032: Hybrid retrieval service.
 * <p>
 * Three search modes: keyword (DB LIKE), semantic (chunk vectors),
 * hybrid (RRF fusion). RFC-032 adds: N+1 fix, two-phase keyword search,
 * relation boost, snippet extraction, and PageSearchResult DTO.
 */
@Slf4j
@Service
public class HybridRetriever {

    private final WikiPageService pageService;
    private final WikiChunkService chunkService;
    private final WikiEmbeddingService embeddingService;
    private final WikiProperties properties;
    private final WikiPageMapper pageMapper;
    private final WikiMetrics metrics;
    private final WikiContentNormalizer contentNormalizer;

    @Autowired(required = false)
    private WikiRelationService relationService;

    private static final double RELATION_BOOST = 0.15;

    /** Cap how many image references one search hit carries — large pages can have
     *  dozens; we only need a couple of thumbnails for the result panel. */
    private static final int MAX_IMAGE_REFS_PER_HIT = 3;

    public HybridRetriever(WikiPageService pageService,
                            WikiChunkService chunkService,
                            WikiEmbeddingService embeddingService,
                            WikiProperties properties,
                            WikiPageMapper pageMapper,
                            WikiMetrics metrics,
                            WikiContentNormalizer contentNormalizer) {
        this.pageService = pageService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.pageMapper = pageMapper;
        this.metrics = metrics;
        this.contentNormalizer = contentNormalizer;
    }

    public enum Mode { KEYWORD, SEMANTIC, HYBRID }

    /**
     * Legacy page hit record (kept for backward compatibility).
     */
    public record PageHit(Long pageId, String slug, String title, String summary, double score) {}

    /**
     * Chunk-level search result (semantic search).
     * <p>
     * RFC-051 PR-1c: {@code pageNumber} and {@code headerBreadcrumb} are
     * populated when the chunk has those columns set (lazy ingest with
     * preprocessor on, or backfilled chunks). Both are nullable.
     */
    public record ChunkHit(Long chunkId, Long rawId, String snippet, float score,
                            Integer pageNumber, String headerBreadcrumb) {

        /** Backwards-compatible factory for callers that don't yet pass metadata. */
        public ChunkHit(Long chunkId, Long rawId, String snippet, float score) {
            this(chunkId, rawId, snippet, score, null, null);
        }
    }

    /**
     * RFC-032: Enhanced search returning PageSearchResult with snippet and matchedBy metadata.
     */
    public List<PageSearchResult> search(Long kbId, String query, String modeStr, int topK) {
        Mode mode = parseMode(modeStr);

        long startNanos = System.nanoTime();

        List<RankedItem> semantic = List.of();
        List<RankedItem> keyword = List.of();

        if (mode != Mode.KEYWORD && embeddingService.isAvailable()) {
            semantic = semanticSearch(kbId, query, topK * 3);
        }
        if (mode != Mode.SEMANTIC) {
            keyword = keywordSearch(kbId, query, topK * 3);
        }

        if (mode == Mode.SEMANTIC && semantic.isEmpty()) {
            keyword = keywordSearch(kbId, query, topK * 3);
        }

        List<RankedItem> fused;
        if (mode == Mode.KEYWORD || semantic.isEmpty()) {
            fused = keyword;
        } else if (mode == Mode.SEMANTIC) {
            fused = semantic;
        } else {
            fused = rrfFuse(semantic, keyword, 60);
        }

        // RFC-032: Relation boost (1-hop expansion on top-3 seeds)
        fused = applyRelationBoost(fused, kbId, topK);

        // Batch-fetch page info (N+1 fix)
        List<Long> topIds = fused.stream().limit(topK).map(ri -> ri.pageId).toList();
        if (topIds.isEmpty()) {
            metrics.recordRetrieval(mode.name().toLowerCase(),
                    Duration.ofNanos(System.nanoTime() - startNanos), 0);
            return List.of();
        }

        Map<Long, WikiPageLite> liteMap = pageMapper.selectBatchLite(topIds)
            .stream().collect(Collectors.toMap(WikiPageLite::id, p -> p));

        // Build result with snippets
        List<PageSearchResult> results = new ArrayList<>();
        for (RankedItem ri : fused.stream().limit(topK).toList()) {
            WikiPageLite lite = liteMap.get(ri.pageId);
            if (lite == null) continue;
            // RFC-051 PR-2: hide system pages (overview / log) from search results.
            if (lite.isSystem()) continue;

            String snippet = null;
            List<ImageRef> imageRefs = List.of();
            if (!ri.matchedBy.contains("relation_boost")) {
                String content = pageMapper.selectContentById(ri.pageId);
                if (content != null) {
                    snippet = SnippetExtractor.extract(content, query);
                    // Surface up to N image references inline with the hit so the UI
                    // can render thumbnails without re-fetching the page body.
                    List<ImageRef> all = contentNormalizer.extractImageRefs(content);
                    imageRefs = all.size() > MAX_IMAGE_REFS_PER_HIT
                            ? all.subList(0, MAX_IMAGE_REFS_PER_HIT)
                            : all;
                }
            }

            String reason = buildReason(lite, ri.matchedBy, query);
            // RFC-051 §9.4: when the entry came from relation boost, override / append
            // the reason with the seed slug + dominant signals so callers can explain
            // why an out-of-search-corpus page surfaced.
            if (ri.relationReason() != null && !ri.relationReason().isBlank()) {
                reason = (reason == null || reason.isBlank())
                        ? ri.relationReason()
                        : reason + " · " + ri.relationReason();
            }
            results.add(new PageSearchResult(
                lite.slug(), lite.title(), lite.summary(),
                snippet != null ? snippet : lite.summary(),
                ri.matchedBy, reason, ri.score, imageRefs));
        }
        metrics.recordRetrieval(mode.name().toLowerCase(),
                Duration.ofNanos(System.nanoTime() - startNanos), results.size());
        return results;
    }

    /**
     * Legacy searchPages — returns PageHit for backward compatibility.
     */
    public List<PageHit> searchPages(Long kbId, String query, String modeStr, int topK) {
        return search(kbId, query, modeStr, topK).stream()
            .map(r -> new PageHit(null, r.slug(), r.title(), r.summary(), r.score()))
            .toList();
    }

    /**
     * Chunk-level semantic search.
     */
    public List<ChunkHit> searchChunks(Long kbId, String query, int topK) {
        if (!embeddingService.isAvailable()) return List.of();

        float[] queryVec = embeddingService.embedQuery(kbId, query);
        if (queryVec == null) return List.of();

        List<WikiChunkEntity> allChunks = chunkService.listByKbId(kbId);

        return allChunks.stream()
                .filter(c -> c.getEmbedding() != null)
                .map(c -> {
                    float[] chunkVec = WikiEmbeddingService.bytesToFloats(c.getEmbedding());
                    float score = WikiEmbeddingService.cosine(queryVec, chunkVec);
                    String snippet = c.getContent().length() > 300
                            ? c.getContent().substring(0, 300) + "..."
                            : c.getContent();
                    return new ChunkHit(c.getId(), c.getRawId(), snippet, score,
                            c.getPageNumber(), c.getHeaderBreadcrumb());
                })
                .sorted(Comparator.comparingDouble(ChunkHit::score).reversed())
                .limit(topK)
                .toList();
    }

    // ==================== Internal methods ====================

    /** Semantic search: chunk cosine → aggregate to page level, then merged
     *  with direct page-level cosine when a page has its own embedding.
     *  The page-level signal covers synthesis pages whose vocabulary doesn't
     *  appear in any source raw's chunks. */
    private List<RankedItem> semanticSearch(Long kbId, String query, int limit) {
        float[] queryVec = embeddingService.embedQuery(kbId, query);
        if (queryVec == null) return List.of();

        List<WikiChunkEntity> allChunks = chunkService.listByKbId(kbId);

        Map<Long, Float> chunkScores = new HashMap<>();
        for (WikiChunkEntity chunk : allChunks) {
            if (chunk.getEmbedding() == null || chunk.getRawId() == null) continue;
            float[] vec = WikiEmbeddingService.bytesToFloats(chunk.getEmbedding());
            float score = WikiEmbeddingService.cosine(queryVec, vec);
            chunkScores.merge(chunk.getRawId(), score, Math::max);
        }

        List<WikiPageEntity> allPages = pageService.listByKbId(kbId);
        Map<Long, Double> pageScores = new HashMap<>();
        for (WikiPageEntity page : allPages) {
            // Direct page-level signal: the page carries its own embedding
            // (typical for transformation synthesis pages).
            if (page.getEmbedding() != null) {
                float[] pageVec = WikiEmbeddingService.bytesToFloats(page.getEmbedding());
                float pageScore = WikiEmbeddingService.cosine(queryVec, pageVec);
                pageScores.merge(page.getId(), (double) pageScore, Math::max);
            }
            // Transitive signal: chunks of any source raw this page references.
            String rawIds = page.getSourceRawIds();
            if (rawIds == null) continue;
            for (String rawIdStr : rawIds.replaceAll("[\\[\\]\\s]", "").split(",")) {
                try {
                    long rawId = Long.parseLong(rawIdStr.trim());
                    Float score = chunkScores.get(rawId);
                    if (score != null) {
                        pageScores.merge(page.getId(), (double) score, Math::max);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        return pageScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new RankedItem(e.getKey(), e.getValue(), List.of("semantic")))
                .toList();
    }

    /**
     * RFC-032: Two-phase keyword search — fast path (title+summary) first,
     * full content search only if needed to fill topK.
     */
    private List<RankedItem> keywordSearch(Long kbId, String query, int limit) {
        String kw = "%" + query.toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";

        // Phase 1: fast path (title + summary only)
        List<Long> fastIds = pageMapper.searchFastIds(kbId, kw, limit);

        List<RankedItem> ranked = new ArrayList<>();
        for (int i = 0; i < fastIds.size(); i++) {
            ranked.add(new RankedItem(fastIds.get(i), 1.0 / (i + 1), List.of("title")));
        }

        if (fastIds.size() >= limit) return ranked;

        // Phase 2: full content search (supplement)
        List<Long> contentIds = pageMapper.searchContentIds(kbId, kw, fastIds, limit - fastIds.size());
        for (int i = 0; i < contentIds.size(); i++) {
            ranked.add(new RankedItem(contentIds.get(i),
                    1.0 / (fastIds.size() + i + 1), List.of("content")));
        }

        return ranked;
    }

    /** RRF fusion: score = Σ 1/(k + rank_i) */
    private List<RankedItem> rrfFuse(List<RankedItem> a, List<RankedItem> b, int k) {
        Map<Long, Double> fused = new HashMap<>();
        Map<Long, List<String>> matchedByMap = new HashMap<>();

        for (int i = 0; i < a.size(); i++) {
            fused.merge(a.get(i).pageId, 1.0 / (k + i + 1), Double::sum);
            matchedByMap.computeIfAbsent(a.get(i).pageId, x -> new ArrayList<>()).addAll(a.get(i).matchedBy);
        }
        for (int i = 0; i < b.size(); i++) {
            fused.merge(b.get(i).pageId, 1.0 / (k + i + 1), Double::sum);
            matchedByMap.computeIfAbsent(b.get(i).pageId, x -> new ArrayList<>()).addAll(b.get(i).matchedBy);
        }

        return fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(e -> new RankedItem(e.getKey(), e.getValue(),
                        matchedByMap.getOrDefault(e.getKey(), List.of()).stream().distinct().toList()))
                .toList();
    }

    /**
     * RFC-032: 1-hop relation boost on top-3 seed pages.
     * <p>
     * RFC-051 §9.4 makes the boost magnitude data-driven instead of a flat
     * constant when {@code mate.wiki.use-normalized-relation-boost} is on.
     */
    private List<RankedItem> applyRelationBoost(List<RankedItem> hits, Long kbId, int topK) {
        if (relationService == null || hits.isEmpty()) return hits;

        List<Long> seedIds = hits.stream().limit(3).map(h -> h.pageId).toList();
        // Per-candidate aggregate raw score (sum of contributions from each seed-relation
        // pair) plus a remembered "best" reason — the seed that contributed the highest
        // relation score and its dominant signals. Used for the human-readable reason
        // surfaced via PageSearchResult.reason.
        Map<Long, Double> rawScoreMap = new HashMap<>();
        Map<Long, RelationReasonRecord> reasonMap = new HashMap<>();

        for (Long seedId : seedIds) {
            List<WikiPageLite> seedLites = pageMapper.selectBatchLite(List.of(seedId));
            if (seedLites.isEmpty()) continue;
            WikiPageLite seed = seedLites.get(0);
            // RFC-051 PR-5: don't expand 1-hop neighborhood from a system page seed.
            // Otherwise the overview / log neighborhood — typically every page that
            // shares a raw with them — leaks into search results via boost. PR-2's
            // result-emit filter drops the system pages themselves; this guard ensures
            // we don't even use them as expansion roots.
            if (seed.isSystem()) continue;
            try {
                relationService.relatedPages(kbId, seed.slug(), 3)
                    .forEach(r -> {
                        WikiPageEntity relPage = pageService.getBySlug(kbId, r.slug());
                        if (relPage == null) return;
                        rawScoreMap.merge(relPage.getId(), r.score(), Double::sum);
                        // Keep the strongest single seed→neighbor pair as the reason.
                        RelationReasonRecord existing = reasonMap.get(relPage.getId());
                        if (existing == null || r.score() > existing.contribution) {
                            reasonMap.put(relPage.getId(),
                                new RelationReasonRecord(seed.slug(), r.signals(), r.score()));
                        }
                    });
            } catch (Exception e) {
                log.debug("[HybridRetriever] Relation boost failed for seed {}: {}", seed.slug(), e.getMessage());
            }
        }

        Set<Long> existingIds = hits.stream().map(h -> h.pageId).collect(Collectors.toSet());
        rawScoreMap.keySet().removeAll(existingIds);

        if (rawScoreMap.isEmpty()) return hits;

        // Choose boost magnitude per candidate: legacy flat constant or normalized × λ.
        Map<Long, Double> boostMap = new HashMap<>();
        if (properties != null && properties.isUseNormalizedRelationBoost()) {
            double maxRaw = rawScoreMap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            double lambda = Math.max(0, properties.getRelationBoostLambda());
            if (maxRaw <= 0 || lambda <= 0) {
                rawScoreMap.forEach((pid, raw) -> boostMap.put(pid, 0.0));
            } else {
                final double maxRawF = maxRaw;
                rawScoreMap.forEach((pid, raw) -> boostMap.put(pid, (raw / maxRawF) * lambda));
            }
        } else {
            rawScoreMap.forEach((pid, raw) -> boostMap.put(pid, RELATION_BOOST));
        }

        List<RankedItem> expanded = new ArrayList<>(hits);
        boostMap.forEach((pid, score) -> {
            RelationReasonRecord rr = reasonMap.get(pid);
            String reason = rr == null ? null : formatRelationReason(rr);
            expanded.add(new RankedItem(pid, score, List.of("relation_boost"), reason));
        });
        return expanded;
    }

    private String buildReason(WikiPageLite lite, List<String> matchedBy, String query) {
        if (matchedBy.contains("relation_boost")) return "Structurally related to top search results";
        if (matchedBy.contains("title") && matchedBy.contains("semantic")) return "Title and semantic match";
        if (matchedBy.contains("title")) return "Title match";
        if (matchedBy.contains("semantic")) return "Semantic similarity";
        if (matchedBy.contains("content")) return "Content match";
        return "Keyword match";
    }

    private Mode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            String defaultMode = properties.getSearchDefaultMode();
            return switch (defaultMode) {
                case "keyword" -> Mode.KEYWORD;
                case "semantic" -> Mode.SEMANTIC;
                default -> Mode.HYBRID;
            };
        }
        return switch (mode.toLowerCase()) {
            case "keyword" -> Mode.KEYWORD;
            case "semantic" -> Mode.SEMANTIC;
            default -> Mode.HYBRID;
        };
    }

    /**
     * RFC-051 §9.4: optional human-readable explanation for relation boost
     * entries. {@code null} when this RankedItem wasn't produced by the
     * relation pass.
     */
    private record RankedItem(Long pageId, double score, List<String> matchedBy, String relationReason) {
        /** Back-compat ctor — keyword/semantic items don't carry a relation reason. */
        RankedItem(Long pageId, double score, List<String> matchedBy) {
            this(pageId, score, matchedBy, null);
        }
    }

    /** Internal: which seed/signals contributed the strongest relation pull to a candidate. */
    private record RelationReasonRecord(String seedSlug, List<String> signals, double contribution) {}

    private static String formatRelationReason(RelationReasonRecord r) {
        if (r == null) return null;
        StringBuilder sb = new StringBuilder("related to '").append(r.seedSlug()).append("'");
        if (r.signals() != null && !r.signals().isEmpty()) {
            sb.append(" via ").append(String.join("+", r.signals()));
        }
        return sb.toString();
    }
}
