package vip.mate.wiki.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;
import vip.mate.wiki.dto.*;
import vip.mate.wiki.job.WikiProcessingJobService;
import vip.mate.wiki.job.event.WikiJobCreatedEvent;
import vip.mate.wiki.repository.WikiProcessingJobMapper;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.service.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC-029/030/031/032/033: REST endpoints for wiki relations, jobs, enrichment, and search.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wiki")
@RequiredArgsConstructor
public class WikiRelationController {

    private final WikiRelationService relationService;
    private final WikiProcessingJobService jobService;
    private final WikiProcessingJobMapper jobMapper;
    private final WikiPageService pageService;
    private final WikiPageCitationMapper citationMapper;
    private final HybridRetriever hybridRetriever;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final WikiEmbeddingService embeddingService;

    // ==================== RFC-029: Relations ====================

    @GetMapping("/kb/{kbId}/pages/{slug}/related")
    public List<RelatedPageResult> relatedPages(
            @PathVariable Long kbId,
            @PathVariable String slug,
            @RequestParam(defaultValue = "5") int topK) {
        return relationService.relatedPages(kbId, slug, Math.min(topK, 20));
    }

    @GetMapping("/kb/{kbId}/pages/{slugA}/relation/{slugB}")
    public RelationExplanation explainRelation(
            @PathVariable Long kbId,
            @PathVariable String slugA,
            @PathVariable String slugB) {
        return relationService.explain(kbId, slugA, slugB);
    }

    @GetMapping("/raw/{rawId}/pages")
    public List<WikiPageLite> pagesByRawId(@PathVariable Long rawId) {
        return relationService.pagesByRawId(rawId);
    }

    @GetMapping("/chunks/{chunkId}/pages")
    public List<WikiPageLite> pagesByChunkId(@PathVariable Long chunkId) {
        return relationService.pagesByChunkId(chunkId);
    }

    // ==================== RFC-029: Citations ====================

    @GetMapping("/kb/{kbId}/pages/{pageId}/citations")
    public List<PageCitationWithRaw> pageCitations(
            @PathVariable Long kbId,
            @PathVariable Long pageId) {
        return citationMapper.listWithRawByPageId(pageId);
    }

    // ==================== RFC-030: Jobs ====================

    @GetMapping("/kb/{kbId}/jobs")
    public List<WikiProcessingJobEntity> getJobs(
            @PathVariable Long kbId,
            @RequestParam(required = false) Long rawId) {
        if (rawId != null) {
            return jobMapper.findLatestByRawId(rawId)
                    .map(List::of).orElse(List.of());
        }
        return jobMapper.listQueued(kbId, 20);
    }

    // ==================== RFC-030/033: KB Stats ====================

    @GetMapping("/kb/{kbId}/stats")
    public Map<String, Object> kbStats(@PathVariable Long kbId) {
        int pageCount = pageService.countByKbId(kbId);
        // Count enriched pages (those containing [[wikilinks]])
        long enrichedCount = pageService.listByKbIdWithContent(kbId).stream()
                .filter(p -> p.getContent() != null && p.getContent().contains("[["))
                .count();
        // Use listByKbId (all statuses) instead of listQueued (queued-only).
        // A raw material accumulates one job row per (re)processing attempt;
        // only its most recent job reflects current state. Collapse to the
        // latest job per raw (highest snowflake id wins) so a failed attempt
        // that a later successful reprocess superseded stops being counted.
        var allJobs = jobMapper.listByKbId(kbId, 200);
        Map<Long, WikiProcessingJobEntity> latestByRaw = new HashMap<>();
        for (WikiProcessingJobEntity job : allJobs) {
            latestByRaw.merge(job.getRawId(), job,
                    (a, b) -> a.getId() >= b.getId() ? a : b);
        }
        int failedJobCount = (int) latestByRaw.values().stream()
                .filter(j -> "failed".equals(j.getStatus()))
                .count();
        int runningJobCount = (int) latestByRaw.values().stream()
                .filter(j -> "running".equals(j.getStatus()))
                .count();

        WikiEmbeddingService.EmbeddingDrift drift = embeddingService.describeDrift(kbId);

        return Map.of(
                "pageCount", pageCount,
                "enrichedPageCount", enrichedCount,
                "failedJobCount", failedJobCount,
                "runningJobCount", runningJobCount,
                "embeddingDrift", drift
        );
    }

    // ==================== RFC-031: Enrichment & Repair ====================

    @PostMapping("/kb/{kbId}/pages/{slug}/enrich")
    public Map<String, Object> enrichPage(@PathVariable Long kbId, @PathVariable String slug) {
        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) return Map.of("error", "Page not found: " + slug);

        Long rawId = 0L;
        try {
            List<Long> rawIds = objectMapper.readValue(
                    page.getSourceRawIds() != null ? page.getSourceRawIds() : "[]",
                    new TypeReference<List<Long>>() {});
            if (!rawIds.isEmpty()) rawId = rawIds.get(0);
        } catch (Exception ignored) {}

        WikiProcessingJobEntity job = jobService.createLightEnrich(kbId, rawId);
        eventPublisher.publishEvent(new WikiJobCreatedEvent(job.getId()));
        return Map.of("jobId", job.getId());
    }

    @PostMapping("/kb/{kbId}/pages/{slug}/repair")
    public Map<String, Object> repairPage(@PathVariable Long kbId, @PathVariable String slug) {
        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) return Map.of("error", "Page not found: " + slug);

        Long rawId = 0L;
        try {
            List<Long> rawIds = objectMapper.readValue(
                    page.getSourceRawIds() != null ? page.getSourceRawIds() : "[]",
                    new TypeReference<List<Long>>() {});
            if (!rawIds.isEmpty()) rawId = rawIds.get(0);
        } catch (Exception ignored) {}

        WikiProcessingJobEntity job = jobService.createLocalRepair(kbId, rawId, page.getId());
        eventPublisher.publishEvent(new WikiJobCreatedEvent(job.getId()));
        return Map.of("jobId", job.getId());
    }

    // ==================== RFC-032: Search preview ====================

    @PostMapping("/kb/{kbId}/search-preview")
    public List<PageSearchResult> searchPreview(
            @PathVariable Long kbId,
            @RequestBody Map<String, Object> body) {
        String query = (String) body.getOrDefault("query", "");
        String mode = (String) body.getOrDefault("mode", "hybrid");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 5;
        return hybridRetriever.search(kbId, query, mode, Math.min(topK, 20));
    }
}
