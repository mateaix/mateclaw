package vip.mate.kbopen.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.kbopen.auth.RequireKbScope;
import vip.mate.kbopen.dto.KbOpenApiDtos.*;
import vip.mate.kbopen.service.KbOpenApiService;
import vip.mate.wiki.dto.PageCitationWithRaw;
import vip.mate.wiki.dto.PageSearchResult;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiEntityEntity;
import vip.mate.wiki.model.WikiEntityRelationEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiEntityMapper;
import vip.mate.wiki.repository.WikiEntityRelationMapper;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * KB Open API — 9 read-only endpoints for programmatic knowledge base access.
 *
 * <p>All endpoints are under {@code /api/v1/open/kb/**} (SecurityConfig
 * permitAll), authenticated by {@code KbOpenApiAuthFilter} (API Key), and
 * authorized by {@code @RequireKbScope} (scope + KB ownership).
 *
 * <p>A5: returns explicit DTOs, never raw entities.
 * A6: service-layer methods return pure DTOs (no HTTP coupling).
 */
@Tag(name = "KB Open API")
@RestController
@RequestMapping("/api/v1/open/kb")
@RequiredArgsConstructor
public class KbOpenApiController {

    private final WikiPageService pageService;
    private final HybridRetriever hybridRetriever;
    private final WikiKnowledgeBaseService kbService;
    private final WikiPageCitationMapper citationMapper;
    private final WikiChunkMapper chunkMapper;
    private final WikiEntityMapper entityMapper;
    private final WikiEntityRelationMapper relationMapper;
    private final KbOpenApiService openApiService;

    // ── 1. GET /pages/{slug} — entity card / page detail ──────────────────

    @RequireKbScope("kb:read")
    @GetMapping("/{kbId}/pages/{slug}")
    @Operation(summary = "Get entity card / page detail (mode controls content depth)")
    public R<PageCard> getPage(
            @PathVariable Long kbId,
            @PathVariable String slug,
            @RequestParam(defaultValue = "summary") String mode,
            @RequestParam(required = false) String fields) {
        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            throw new MateClawException(404, "Page not found: " + slug);
        }
        return R.ok(openApiService.assembleCard(page, mode, fields));
    }

    // ── 2. POST /search — hybrid retrieval ────────────────────────────────

    @RequireKbScope("kb:search")
    @PostMapping("/{kbId}/search")
    @Operation(summary = "Hybrid search (granularity controls result shape)")
    public R<Map<String, Object>> search(
            @PathVariable Long kbId,
            @RequestBody SearchRequest req) {
        String query = req.query() != null ? req.query() : "";
        String mode = req.mode() != null ? req.mode() : "hybrid";
        int topK = req.topK() != null ? Math.min(req.topK(), 20) : 5;

        List<PageSearchResult> hits = hybridRetriever.search(kbId, query, mode, topK);

        // Filter by pageType if specified
        if (req.pageType() != null && !req.pageType().isBlank()) {
            hits = hits.stream().filter(h -> {
                WikiPageEntity p = pageService.getBySlug(kbId, h.slug());
                return p != null && req.pageType().equals(p.getPageType());
            }).collect(Collectors.toList());
        }

        // granularity: entity (default) returns summary-level hits; chunk is via /search/chunks
        List<Map<String, Object>> results = hits.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", h.slug());
            m.put("title", h.title());
            m.put("summary", h.summary());
            m.put("snippet", h.snippet());
            m.put("matchedBy", h.matchedBy());
            m.put("score", h.score());
            return m;
        }).collect(Collectors.toList());

        return R.ok(Map.of(
                "kbId", kbId,
                "query", query,
                "mode", mode,
                "count", results.size(),
                "results", results));
    }

    public record SearchRequest(String query, String mode, String pageType,
                                String granularity, Integer topK) {}

    // ── 3. POST /search/chunks — chunk-level retrieval ────────────────────

    @RequireKbScope("kb:search")
    @PostMapping("/{kbId}/search/chunks")
    @Operation(summary = "Chunk-level semantic search (fine-grained RAG evidence)")
    public R<Map<String, Object>> searchChunks(
            @PathVariable Long kbId,
            @RequestBody ChunkSearchRequest req) {
        String query = req.query() != null ? req.query() : "";
        int topK = req.topK() != null ? Math.min(req.topK(), 20) : 5;

        List<HybridRetriever.ChunkHit> hits = hybridRetriever.searchChunks(kbId, query, topK);

        List<Map<String, Object>> results = hits.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", h.chunkId());
            m.put("rawId", h.rawId());
            m.put("snippet", h.snippet());
            m.put("score", h.score());
            m.put("pageNumber", h.pageNumber());
            m.put("headerBreadcrumb", h.headerBreadcrumb());
            return m;
        }).collect(Collectors.toList());

        return R.ok(Map.of(
                "kbId", kbId,
                "count", results.size(),
                "chunks", results));
    }

    public record ChunkSearchRequest(String query, Integer topK) {}

    // ── 4. POST /pages/{slug}/traverse — entity relation graph ────────────

    @RequireKbScope("kb:read")
    @PostMapping("/{kbId}/pages/{slug}/traverse")
    @Operation(summary = "Traverse entity relations (depth ≤ 2)")
    public R<TraverseResult> traverse(
            @PathVariable Long kbId,
            @PathVariable String slug,
            @RequestBody(required = false) TraverseRequest req) {
        String relation = req != null ? req.relation() : null;
        int depth = req != null && req.depth() != null ? Math.min(req.depth(), 2) : 1;
        String direction = req != null && req.direction() != null ? req.direction() : "both";
        int limit = req != null && req.limit() != null ? Math.min(req.limit(), 50) : 20;
        return R.ok(openApiService.traverse(kbId, slug, relation, depth, direction, limit));
    }

    public record TraverseRequest(String relation, Integer depth, String direction, Integer limit) {}

    // ── 5. GET /pages/{slug}/trace — provenance ───────────────────────────

    @RequireKbScope("kb:read")
    @GetMapping("/{kbId}/pages/{slug}/trace")
    @Operation(summary = "Trace page provenance (page → chunk → raw)")
    public R<TraceResult> trace(
            @PathVariable Long kbId,
            @PathVariable String slug) {
        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            throw new MateClawException(404, "Page not found: " + slug);
        }
        var citations = citationMapper.listWithRawByPageId(page.getId());

        // Group by rawId
        Map<Long, List<PageCitationWithRaw>> byRaw = citations.stream()
                .collect(Collectors.groupingBy(PageCitationWithRaw::rawId));

        List<SourceGroup> sources = byRaw.entrySet().stream().map(e -> {
            List<CitationDetail> details = e.getValue().stream().map(c ->
                    new CitationDetail(c.chunkId(), c.snippet(),
                            c.confidence() != null ? c.confidence().doubleValue() : null,
                            null)).collect(Collectors.toList()); // pageNumber from chunk lookup omitted for brevity
            String rawTitle = e.getValue().get(0).rawTitle();
            return new SourceGroup(e.getKey(), rawTitle, details);
        }).collect(Collectors.toList());

        return R.ok(new TraceResult(
                page.getSlug(),
                page.getPageType(),
                page.getKnowledgeLayer(),
                sources,
                page.getUpdateTime(),
                page.getVersion()));
    }

    // ── 6. GET /taxonomy — type enumeration map ───────────────────────────

    @RequireKbScope("kb:list")
    @GetMapping("/{kbId}/taxonomy")
    @Operation(summary = "Get type/scope taxonomy (pageTypes, entityTypes, relationTypes)")
    public R<TaxonomyResult> taxonomy(@PathVariable Long kbId) {
        // Page types
        List<WikiPageEntity> pages = pageService.listByKbId(kbId);
        Map<String, Long> pageTypeCounts = pages.stream()
                .filter(p -> p.getPageType() != null)
                .collect(Collectors.groupingBy(WikiPageEntity::getPageType, Collectors.counting()));
        List<TypeCount> pageTypes = pageTypeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new TypeCount(e.getKey(), e.getValue().intValue()))
                .collect(Collectors.toList());

        // Entity types
        List<WikiEntityEntity> entities = entityMapper.selectList(
                new LambdaQueryWrapper<WikiEntityEntity>().eq(WikiEntityEntity::getKbId, kbId));
        Map<String, Long> entityTypeCounts = entities.stream()
                .filter(e -> e.getType() != null)
                .collect(Collectors.groupingBy(WikiEntityEntity::getType, Collectors.counting()));
        List<TypeCount> entityTypes = entityTypeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new TypeCount(e.getKey(), e.getValue().intValue()))
                .collect(Collectors.toList());

        // Relation types
        List<WikiEntityRelationEntity> rels = relationMapper.selectList(
                new LambdaQueryWrapper<WikiEntityRelationEntity>().eq(WikiEntityRelationEntity::getKbId, kbId));
        Map<String, Long> relTypeCounts = rels.stream()
                .filter(r -> r.getPredicate() != null)
                .collect(Collectors.groupingBy(WikiEntityRelationEntity::getPredicate, Collectors.counting()));
        List<TypeCount> relationTypes = relTypeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new TypeCount(e.getKey(), e.getValue().intValue()))
                .collect(Collectors.toList());

        return R.ok(new TaxonomyResult(pageTypes, entityTypes, relationTypes));
    }

    // ── 7. GET /whats-new — freshness query ───────────────────────────────

    @RequireKbScope("kb:meta")
    @GetMapping("/{kbId}/whats-new")
    @Operation(summary = "Query recent changes and stale pages")
    public R<WhatsNewResult> whatsNew(
            @PathVariable Long kbId,
            @RequestParam(defaultValue = "updated") String kind,
            @RequestParam(required = false) LocalDateTime since,
            @RequestParam(defaultValue = "50") int limit) {
        if (since == null) {
            since = LocalDateTime.now().minusDays(7);
        }
        int safeLimit = Math.min(limit, 200);

        List<WikiPageEntity> changed = "created".equals(kind)
                ? pageService.findRecentCreated(kbId, since, safeLimit)
                : pageService.findRecentUpdated(kbId, since, safeLimit);

        List<ChangedPage> changedPages = changed.stream()
                .map(p -> new ChangedPage(p.getSlug(), p.getTitle(), p.getKnowledgeLayer(),
                        p.getUpdateTime(), null))
                .collect(Collectors.toList());

        // Stale pages
        List<WikiPageEntity> allPages = pageService.listByKbId(kbId);
        List<ChangedPage> stalePages = allPages.stream()
                .filter(p -> p.getStale() != null && p.getStale() == 1)
                .map(p -> new ChangedPage(p.getSlug(), p.getTitle(), p.getKnowledgeLayer(),
                        p.getUpdateTime(), "Upstream fact page changed"))
                .collect(Collectors.toList());

        return R.ok(new WhatsNewResult(kbId, since, changedPages, stalePages));
    }

    // ── 8. GET /stats — KB metadata ───────────────────────────────────────

    @RequireKbScope("kb:meta")
    @GetMapping("/{kbId}/stats")
    @Operation(summary = "Get KB statistics")
    public R<KbStats> stats(@PathVariable Long kbId) {
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) {
            throw new MateClawException(404, "Knowledge base not found: " + kbId);
        }
        int pageCount = pageService.countByKbId(kbId);
        // Must use listByKbIdWithContent — listByKbId nulls out content.
        List<WikiPageEntity> pagesWithContent = pageService.listByKbIdWithContent(kbId);
        long pagesWithLinks = pagesWithContent.stream()
                .filter(p -> p.getContent() != null && p.getContent().contains("[["))
                .count();

        return R.ok(new KbStats(
                kbId,
                kb.getName(),
                pageCount,
                kb.getRawCount() != null ? kb.getRawCount() : 0,
                0, // chunkCount not trivially available without a service call
                0, // embeddedChunks same
                (int) pagesWithLinks,
                null, // lastIngest
                null  // embeddingModel
        ));
    }

    // ── 9. GET /pages — list pages ────────────────────────────────────────

    @RequireKbScope("kb:list")
    @GetMapping("/{kbId}/pages")
    @Operation(summary = "List pages (lightweight)")
    public R<PageList> listPages(
            @PathVariable Long kbId,
            @RequestParam(required = false) String pageType) {
        List<WikiPageEntity> pages = pageService.listByKbId(kbId);
        if (pageType != null && !pageType.isBlank()) {
            pages = pages.stream()
                    .filter(p -> pageType.equals(p.getPageType()))
                    .collect(Collectors.toList());
        }
        List<PageListItem> items = pages.stream()
                .map(p -> new PageListItem(p.getSlug(), p.getTitle(), p.getSummary(),
                        p.getPageType(), p.getKnowledgeLayer()))
                .collect(Collectors.toList());
        return R.ok(new PageList(kbId, items.size(), items));
    }
}
