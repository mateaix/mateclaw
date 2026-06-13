package vip.mate.wiki.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.wiki.job.WikiChunkTokenBackfillJob;
import vip.mate.wiki.service.WikiOverviewService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiScaffoldService;

import java.util.HashMap;
import java.util.Map;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * RFC-051 follow-up: small set of operator-facing endpoints for things the
 * scheduled jobs / event hooks normally handle automatically. Useful when the
 * cron hasn't fired yet (fresh upgrade), the auto-rebuild was skipped, or you
 * just want to force-refresh during debugging.
 *
 * <p>All endpoints are idempotent and synchronous.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wiki/admin")
@RequiredArgsConstructor
@Tag(name = "Wiki Admin", description = "Operator endpoints for system pages and backfill jobs")
public class WikiAdminController {

    private final WikiScaffoldService scaffoldService;
    private final WikiPageService pageService;

    /** Optional so the controller can boot in environments where the rebuilder isn't wired (e.g. minimal tests). */
    @Autowired(required = false)
    private WikiOverviewService overviewService;

    @Autowired(required = false)
    private WikiChunkTokenBackfillJob backfillJob;

    @Operation(summary = "Ensure overview/log scaffold + rebuild overview stats now",
               description = "Idempotent. Use after manual data imports or when stats look stale.")
    @PostMapping("/kb/{kbId}/rebuild-overview")
    @RequireWorkspaceRole("admin")
    public ResponseEntity<Map<String, Object>> rebuildOverview(@PathVariable Long kbId) {
        Map<String, Object> body = new HashMap<>();
        scaffoldService.ensureScaffold(kbId);
        if (overviewService != null) {
            overviewService.rebuild(kbId);
            body.put("rebuilt", true);
        } else {
            body.put("rebuilt", false);
            body.put("note", "Overview service not wired; only scaffold ensured");
        }
        body.put("kbId", kbId);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Force-run the token-count backfill batch now",
               description = "Picks up to BATCH_SIZE chunks with token_count IS NULL and fills them. "
                       + "Returns the pending count after the batch so callers can poll.")
    @PostMapping("/backfill-tokens")
    @RequireWorkspaceRole("admin")
    public ResponseEntity<Map<String, Object>> backfillTokens() {
        Map<String, Object> body = new HashMap<>();
        if (backfillJob == null) {
            body.put("ok", false);
            body.put("note", "Backfill job not wired");
            return ResponseEntity.ok(body);
        }
        long beforePending = backfillJob.pendingCount();
        backfillJob.runOnce();
        long afterPending = backfillJob.pendingCount();
        body.put("ok", true);
        body.put("pendingBefore", beforePending);
        body.put("pendingAfter", afterPending);
        body.put("filledThisBatch", Math.max(0, beforePending - afterPending));
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Merge duplicate pages that share a canonical title",
               description = "Heals duplicate rows produced before title-based dedup existed (one concept "
                       + "stored under several LLM-minted slugs). Defaults to a dry run that only reports "
                       + "what would change. Set dryRun=false to apply. concatenate=true (default) appends each "
                       + "loser's body to the winner so no content is lost; concatenate=false keeps only the "
                       + "winner's body. Protected (system/locked) pages always win and are never deleted.")
    @PostMapping("/kb/{kbId}/merge-duplicate-titles")
    @RequireWorkspaceRole("admin")
    public ResponseEntity<Map<String, Object>> mergeDuplicateTitles(
            @PathVariable Long kbId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "true") boolean concatenate) {
        Map<String, Object> report = pageService.mergeDuplicateTitles(kbId, dryRun, concatenate);
        return ResponseEntity.ok(report);
    }
}
