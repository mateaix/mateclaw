package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageMapper;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KB-wide broken-link scan job orchestrator.
 * <p>
 * Single-page broken_links is maintained synchronously on every save/update
 * (see {@link WikiPageService#applyLinkAnalysis} via
 * {@code applyLinkAnalysis}). This service handles the on-demand "scan the
 * whole KB" path that surfaces accumulated drift — pages whose targets went
 * missing because some OTHER page was renamed / deleted / archived, or
 * pages whose broken_links was never computed (legacy content predating
 * V129).
 * <p>
 * State lives in-memory on purpose: the authoritative result lives on
 * {@code mate_wiki_page.broken_links} (persisted). The job record here is
 * pure UX glue so the frontend can show "scan started / running / done"
 * without a second DB table. A server restart mid-scan loses progress
 * tracking but never corrupts data — each per-page rewrite is transactional,
 * and the user simply re-triggers the scan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiLintJobService {

    private final WikiPageMapper pageMapper;
    private final WikiPageService pageService;
    private final WikiLinkService linkService;

    /**
     * Per-KB job state. The map holds the most recent job for each KB
     * regardless of status, so {@link #getLatestJob} can answer "did we ever
     * finish a scan on this KB". Cleared only on completion of a newer scan
     * for the same KB — no TTL, the cardinality is bounded by KB count.
     */
    private final ConcurrentHashMap<Long, LintJob> jobsByKb = new ConcurrentHashMap<>();

    /** Index by jobId for the optional {@code GET .../jobs/{jobId}} path. */
    private final ConcurrentHashMap<String, LintJob> jobsById = new ConcurrentHashMap<>();

    /**
     * Single-threaded executor: lint scans are I/O-bound but cheap; one job
     * per KB at a time avoids piling up concurrent full-KB reads on the
     * mapper. Daemon thread so we don't block JVM shutdown.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wiki-lint-scan");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Job status lifecycle: {@code queued → running → completed | failed}. */
    public enum JobStatus { QUEUED, RUNNING, COMPLETED, FAILED }

    /**
     * Immutable snapshot of a job — the running mutable state lives behind
     * an {@link AtomicReference} so callers receive a thread-safe view.
     */
    public record LintJob(
            String jobId,
            Long kbId,
            JobStatus status,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            int totalPages,
            int pagesWithBrokenLinks,
            int totalBrokenRefs,
            String errorMessage
    ) {}

    /**
     * Start a scan on {@code kbId}. If a scan is already queued or running for
     * the same KB, return that job — POST is idempotent under in-flight load.
     */
    public LintJob startOrGetRunning(Long kbId) {
        // computeIfAbsent skipped — we need access to the existing value to
        // decide whether to keep or replace it, which compute() supports.
        return jobsByKb.compute(kbId, (k, prev) -> {
            if (prev != null && (prev.status() == JobStatus.QUEUED || prev.status() == JobStatus.RUNNING)) {
                log.debug("[WikiLint] Reusing in-flight job {} for kbId={}", prev.jobId(), kbId);
                return prev;
            }
            String jobId = newJobId();
            LintJob job = new LintJob(jobId, kbId, JobStatus.QUEUED, LocalDateTime.now(),
                    null, 0, 0, 0, null);
            jobsById.put(jobId, job);
            executor.submit(() -> runJob(jobId, kbId));
            log.info("[WikiLint] Scheduled job {} for kbId={}", jobId, kbId);
            return job;
        });
    }

    /** @return latest job (any status) for {@code kbId}, or {@code null} */
    public LintJob getLatestJob(Long kbId) {
        return jobsByKb.get(kbId);
    }

    /** @return job by id, or {@code null} */
    public LintJob getJob(String jobId) {
        return jobsById.get(jobId);
    }

    /**
     * Aggregate the broken-link state for {@code kbId} from persisted
     * {@code broken_links} fields. Distinct from {@link #getLatestJob} —
     * this is "what does the data say RIGHT NOW", regardless of whether a
     * scan job is recorded in memory. Used by {@code GET /lint/broken-links}.
     *
     * @return null if no page in the KB has ever been scanned (every page's
     *         {@code broken_links_scanned_at} is null); else an aggregate
     */
    public Aggregate aggregate(Long kbId) {
        List<WikiPageEntity> pages = pageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WikiPageEntity>()
                        .select(WikiPageEntity::getId, WikiPageEntity::getSlug,
                                WikiPageEntity::getTitle, WikiPageEntity::getBrokenLinks,
                                WikiPageEntity::getBrokenLinksScannedAt)
                        .eq(WikiPageEntity::getKbId, kbId));
        if (pages.isEmpty()) return null;
        boolean anyScanned = pages.stream().anyMatch(p -> p.getBrokenLinksScannedAt() != null);
        if (!anyScanned) return null;

        LocalDateTime completedAt = pages.stream()
                .map(WikiPageEntity::getBrokenLinksScannedAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        int pagesWithBroken = 0;
        int totalBrokenRefs = 0;
        java.util.List<PageBrokenRefs> details = new java.util.ArrayList<>();
        for (WikiPageEntity p : pages) {
            List<String> refs = linkService.fromJsonArray(p.getBrokenLinks());
            if (refs.isEmpty()) continue;
            pagesWithBroken++;
            totalBrokenRefs += refs.size();
            details.add(new PageBrokenRefs(p.getId(), p.getSlug(), p.getTitle(), refs));
        }
        return new Aggregate(kbId, completedAt, pages.size(), pagesWithBroken, totalBrokenRefs, details);
    }

    /** Per-page aggregation row. */
    public record PageBrokenRefs(Long pageId, String slug, String title, List<String> brokenRefs) {}

    /** KB-level aggregate response. */
    public record Aggregate(
            Long kbId,
            LocalDateTime completedAt,
            int totalPages,
            int pagesWithBrokenLinks,
            int totalBrokenRefs,
            List<PageBrokenRefs> pages
    ) {}

    // ============================================================
    // Worker
    // ============================================================

    private void runJob(String jobId, Long kbId) {
        updateJob(kbId, jobId, prev -> new LintJob(
                jobId, kbId, JobStatus.RUNNING, prev.startedAt(), null,
                0, 0, 0, null));
        try {
            ScanCounts counts = scan(kbId);
            updateJob(kbId, jobId, prev -> new LintJob(
                    jobId, kbId, JobStatus.COMPLETED, prev.startedAt(), LocalDateTime.now(),
                    counts.totalPages, counts.pagesWithBroken, counts.totalBrokenRefs, null));
            log.info("[WikiLint] Job {} completed: {} pages, {} with broken links ({} refs)",
                    jobId, counts.totalPages, counts.pagesWithBroken, counts.totalBrokenRefs);
        } catch (Exception e) {
            log.error("[WikiLint] Job {} failed for kbId={}", jobId, kbId, e);
            updateJob(kbId, jobId, prev -> new LintJob(
                    jobId, kbId, JobStatus.FAILED, prev.startedAt(), LocalDateTime.now(),
                    prev.totalPages(), prev.pagesWithBrokenLinks(), prev.totalBrokenRefs(),
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private record ScanCounts(int totalPages, int pagesWithBroken, int totalBrokenRefs) {}

    /**
     * Walk every active page in the KB, recompute its broken_links. Each
     * page write is transactional ({@link #rewriteBrokenLinks}); the outer
     * loop is NOT transactional so a single failing page doesn't roll back
     * the whole scan.
     */
    private ScanCounts scan(Long kbId) {
        // listSummaries gives us the active (non-archived) page slug set —
        // archived pages are NOT considered as valid targets, matching the
        // resolver's behaviour.
        List<WikiPageEntity> summaries = pageService.listSummaries(kbId);
        Set<String> activeSlugs = linkService.lowercaseSlugSet(summaries);

        // Now fetch the same pages WITH content so we can re-extract outlinks.
        // We must not use listSummaries here because it omits the content
        // column to keep memory bounded — but we have summaries for the slug
        // set already, so the loaded list and the slug set agree.
        List<WikiPageEntity> withContent = pageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WikiPageEntity>()
                        .select(WikiPageEntity::getId, WikiPageEntity::getSlug,
                                WikiPageEntity::getContent)
                        .eq(WikiPageEntity::getKbId, kbId)
                        .ne(WikiPageEntity::getArchived, 1));

        int total = withContent.size();
        int pagesWithBroken = 0;
        int totalBrokenRefs = 0;

        for (WikiPageEntity p : withContent) {
            Set<String> activeWithSelf;
            if (p.getSlug() != null && !p.getSlug().isBlank()) {
                java.util.Set<String> tmp = new java.util.HashSet<>(activeSlugs);
                tmp.add(p.getSlug().toLowerCase(Locale.ROOT));
                activeWithSelf = tmp;
            } else {
                activeWithSelf = activeSlugs;
            }
            WikiLinkService.LinkAnalysis a = linkService.analyze(p.getContent(), activeWithSelf);
            int brokenCount = a.brokenLinks().size();
            if (brokenCount > 0) {
                pagesWithBroken++;
                totalBrokenRefs += brokenCount;
            }
            try {
                rewriteBrokenLinks(p.getId(),
                        linkService.toJsonArray(a.outgoingLinks()),
                        linkService.toJsonArray(a.brokenLinks()));
            } catch (Exception perPageErr) {
                log.warn("[WikiLint] Per-page rewrite failed for pageId={}; continuing scan: {}",
                        p.getId(), perPageErr.getMessage());
            }
        }
        return new ScanCounts(total, pagesWithBroken, totalBrokenRefs);
    }

    /**
     * Transactional single-page rewrite of outgoing_links + broken_links +
     * broken_links_scanned_at. Kept in this service so the scan loop above
     * is unambiguously per-page transactional without polluting the larger
     * WikiPageService API.
     * <p>
     * Uses {@link com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper}
     * with explicit {@code set()} calls instead of {@code updateById(partial entity)}.
     * Several columns on {@link WikiPageEntity} (content, summary,
     * outgoing_links, broken_links) carry {@code FieldStrategy.ALWAYS} so an
     * entity-style update with those fields left null would generate
     * {@code SET content = NULL, summary = NULL} and wipe the page body.
     * The wrapper-based update only emits SET clauses for the three columns
     * we mean to touch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rewriteBrokenLinks(Long pageId, String outgoingLinksJson, String brokenLinksJson) {
        pageMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getId, pageId)
                        .set(WikiPageEntity::getOutgoingLinks, outgoingLinksJson)
                        .set(WikiPageEntity::getBrokenLinks, brokenLinksJson)
                        .set(WikiPageEntity::getBrokenLinksScannedAt, LocalDateTime.now()));
    }

    private void updateJob(Long kbId, String jobId, java.util.function.Function<LintJob, LintJob> fn) {
        LintJob updated = jobsByKb.compute(kbId, (k, prev) -> {
            LintJob base = prev != null && prev.jobId().equals(jobId) ? prev : prev;
            // If prev is null somehow, synthesize a minimal stub so the
            // updater can still run. In practice prev is always non-null
            // here because startOrGetRunning seeded the map first.
            if (base == null) {
                base = new LintJob(jobId, kbId, JobStatus.QUEUED, LocalDateTime.now(),
                        null, 0, 0, 0, null);
            }
            return fn.apply(base);
        });
        jobsById.put(jobId, updated);
    }

    private static String newJobId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
