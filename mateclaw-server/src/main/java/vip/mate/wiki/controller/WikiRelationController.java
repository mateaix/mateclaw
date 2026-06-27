package vip.mate.wiki.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.dto.*;
import vip.mate.wiki.job.WikiProcessingJobService;
import vip.mate.wiki.job.event.WikiJobCreatedEvent;
import vip.mate.wiki.repository.WikiProcessingJobMapper;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiChunkMapper;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.service.*;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

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
    private final WikiKnowledgeBaseService kbService;
    private final WikiRawMaterialService rawService;
    private final WikiChunkMapper chunkMapper;

    // ==================== RFC-029: Relations ====================

    @RequireWorkspaceRole("viewer")
    @GetMapping("/kb/{kbId}/pages/{slug}/related")
    public List<RelatedPageResult> relatedPages(
            @PathVariable Long kbId,
            @PathVariable String slug,
            @RequestParam(defaultValue = "5") int topK,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        return relationService.relatedPages(kbId, slug, Math.min(topK, 20));
    }

    @RequireWorkspaceRole("viewer")
    @GetMapping("/kb/{kbId}/pages/{slugA}/relation/{slugB}")
    public RelationExplanation explainRelation(
            @PathVariable Long kbId,
            @PathVariable String slugA,
            @PathVariable String slugB,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        return relationService.explain(kbId, slugA, slugB);
    }

    @RequireWorkspaceRole("viewer")
    @GetMapping("/raw/{rawId}/pages")
    public List<WikiPageLite> pagesByRawId(
            @PathVariable Long rawId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyRawWorkspace(rawId, workspaceId);
        return relationService.pagesByRawId(rawId);
    }

    @RequireWorkspaceRole("viewer")
    @GetMapping("/chunks/{chunkId}/pages")
    public List<WikiPageLite> pagesByChunkId(
            @PathVariable Long chunkId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyChunkWorkspace(chunkId, workspaceId);
        return relationService.pagesByChunkId(chunkId);
    }

    // ==================== RFC-029: Citations ====================

    @RequireWorkspaceRole("viewer")
    @GetMapping("/kb/{kbId}/pages/{pageId}/citations")
    public List<PageCitationWithRaw> pageCitations(
            @PathVariable Long kbId,
            @PathVariable Long pageId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        return citationMapper.listWithRawByPageId(pageId);
    }

    // ==================== RFC-030: Jobs ====================

    @RequireWorkspaceRole("viewer")
    @GetMapping("/kb/{kbId}/jobs")
    public List<WikiProcessingJobEntity> getJobs(
            @PathVariable Long kbId,
            @RequestParam(required = false) Long rawId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        if (rawId != null) {
            return jobMapper.findLatestByRawId(rawId)
                    // Guard against a partial IDOR: kbId is workspace-checked
                    // above, but rawId is an independent query param that could
                    // point at another KB's material. Require the resolved job
                    // to actually belong to this kbId.
                    .filter(j -> kbId.equals(j.getKbId()))
                    .map(List::of).orElse(List.of());
        }
        return jobMapper.listQueued(kbId, 20);
    }

    // ==================== RFC-030/033: KB Stats ====================

    @RequireWorkspaceRole("viewer")
    @GetMapping("/kb/{kbId}/stats")
    public Map<String, Object> kbStats(
            @PathVariable Long kbId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
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

    @RequireWorkspaceRole("member")
    @PostMapping("/kb/{kbId}/pages/{slug}/enrich")
    public Map<String, Object> enrichPage(
            @PathVariable Long kbId,
            @PathVariable String slug,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
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

    @RequireWorkspaceRole("member")
    @PostMapping("/kb/{kbId}/pages/{slug}/repair")
    public Map<String, Object> repairPage(
            @PathVariable Long kbId,
            @PathVariable String slug,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
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

    @RequireWorkspaceRole("viewer")
    @PostMapping("/kb/{kbId}/search-preview")
    public List<PageSearchResult> searchPreview(
            @PathVariable Long kbId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        String query = (String) body.getOrDefault("query", "");
        String mode = (String) body.getOrDefault("mode", "hybrid");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 5;
        return hybridRetriever.search(kbId, query, mode, Math.min(topK, 20));
    }

    // ==================== Workspace Verification ====================

    private void verifyKBWorkspace(Long kbId, Long headerWorkspaceId) {
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) {
            throw new MateClawException(404, "Knowledge base not found");
        }
        long wsId = headerWorkspaceId != null ? headerWorkspaceId : 1L;
        if (kb.getWorkspaceId() != null && !kb.getWorkspaceId().equals(wsId)) {
            throw new MateClawException("err.common.wrong_workspace", 403, "资源不属于当前工作区");
        }
    }

    /**
     * Resolve the owning KB of a raw material and check it belongs to the
     * caller's workspace. Raw materials don't carry workspaceId directly;
     * they reference a KB which does.
     */
    private void verifyRawWorkspace(Long rawId, Long headerWorkspaceId) {
        WikiRawMaterialEntity raw = rawService.getById(rawId);
        if (raw == null || raw.getKbId() == null) {
            throw new MateClawException(404, "Raw material not found");
        }
        verifyKBWorkspace(raw.getKbId(), headerWorkspaceId);
    }

    /**
     * Resolve the owning KB of a chunk and check it belongs to the caller's
     * workspace. Like raw materials, chunks reference a KB, not a workspace.
     */
    private void verifyChunkWorkspace(Long chunkId, Long headerWorkspaceId) {
        WikiChunkEntity chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || chunk.getKbId() == null) {
            throw new MateClawException(404, "Chunk not found");
        }
        verifyKBWorkspace(chunk.getKbId(), headerWorkspaceId);
    }
}
