package vip.mate.wiki.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiTransformationEntity;
import vip.mate.wiki.model.WikiTransformationRunEntity;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiTransformationAggregator;
import vip.mate.wiki.service.WikiTransformationExecutor;
import vip.mate.wiki.service.WikiTransformationService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * Management surface for user-defined wiki transformation templates and
 * their execution history. Templates live under the workspace; a template
 * with non-null {@code kbId} is pinned to a single KB, otherwise it is
 * available to every KB in the workspace.
 */
@Slf4j
@Tag(name = "Wiki Transformations",
     description = "User-defined prompt templates run over wiki raw materials")
@RestController
@RequestMapping("/api/v1/wiki/transformations")
@RequiredArgsConstructor
public class WikiTransformationController {

    private final WikiTransformationService transformationService;
    private final WikiTransformationExecutor executor;
    private final WikiTransformationAggregator aggregator;
    private final WikiKnowledgeBaseService kbService;

    // ==================== Templates ====================

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "List transformations available to a KB",
               description = "Returns templates pinned to the KB plus workspace-wide templates.")
    @GetMapping
    public R<List<WikiTransformationEntity>> list(
            @RequestParam(required = false) Long kbId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        if (kbId != null) {
            verifyKBWorkspace(kbId, wsId);
            return R.ok(transformationService.listForKb(kbId, wsId));
        }
        return R.ok(transformationService.listByWorkspace(wsId));
    }

    @RequireWorkspaceRole("viewer")
    @GetMapping("/{id}")
    public R<WikiTransformationEntity> get(@PathVariable Long id,
                                            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        WikiTransformationEntity t = transformationService.getById(id);
        if (t == null) return R.fail(404, "Transformation not found");
        verifyTemplateWorkspace(t, workspaceId);
        return R.ok(t);
    }

    @RequireWorkspaceRole("member")
    @PostMapping
    public R<WikiTransformationEntity> create(@RequestBody WikiTransformationEntity body,
                                               @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        if (body.getKbId() != null) {
            verifyKBWorkspace(body.getKbId(), wsId);
        }
        body.setWorkspaceId(wsId);
        WikiTransformationEntity created = transformationService.create(body);
        return R.ok(created);
    }

    @RequireWorkspaceRole("member")
    @PutMapping("/{id}")
    public R<WikiTransformationEntity> update(@PathVariable Long id,
                                               @RequestBody WikiTransformationEntity body,
                                               @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        WikiTransformationEntity existing = transformationService.getById(id);
        if (existing == null) return R.fail(404, "Transformation not found");
        verifyTemplateWorkspace(existing, workspaceId);
        return R.ok(transformationService.update(id, body));
    }

    @RequireWorkspaceRole("member")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id,
                           @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        WikiTransformationEntity existing = transformationService.getById(id);
        if (existing != null) {
            verifyTemplateWorkspace(existing, workspaceId);
            transformationService.delete(id);
        }
        return R.ok();
    }

    // ==================== Apply ====================

    @RequireWorkspaceRole("member")
    @Operation(summary = "Run a transformation against a raw material or wiki page",
               description = "Body accepts exactly one of {rawId, pageId}. Set sync=true to block "
                           + "until the LLM call returns; when false (default) the call returns "
                           + "immediately with the pending run row.")
    @PostMapping("/{id}/apply")
    public R<WikiTransformationRunEntity> apply(@PathVariable Long id,
                                                 @RequestBody Map<String, Object> body,
                                                 @RequestParam(defaultValue = "false") boolean sync,
                                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        WikiTransformationEntity t = transformationService.getById(id);
        if (t == null) return R.fail(404, "Transformation not found");
        verifyTemplateWorkspace(t, workspaceId);

        Object rawIdRaw = body == null ? null : body.get("rawId");
        Object pageIdRaw = body == null ? null : body.get("pageId");
        if (rawIdRaw == null && pageIdRaw == null) {
            return R.fail(400, "One of rawId / pageId is required");
        }
        if (rawIdRaw != null && pageIdRaw != null) {
            return R.fail(400, "Pass only one of rawId / pageId, not both");
        }

        if (rawIdRaw != null) {
            Long rawId = Long.valueOf(rawIdRaw.toString());
            if (sync) return R.ok(executor.runOnRawSync(t, rawId, "manual"));
            executor.runOnRawAsync(t, rawId, "manual");
        } else {
            Long pageId = Long.valueOf(pageIdRaw.toString());
            if (sync) return R.ok(executor.runOnPageSync(t, pageId, "manual"));
            executor.runOnPageAsync(t, pageId, "manual");
        }
        return R.ok();
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "Aggregate all completed runs of a template into one KB-level synthesis page",
               description = "Map-reduces across every completed run of the template within the given KB. "
                           + "Upserts the merged document at slug '<template-name>-aggregate'.")
    @PostMapping("/{id}/aggregate")
    public R<Map<String, Object>> aggregate(@PathVariable Long id,
                                             @RequestParam Long kbId,
                                             @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        WikiTransformationEntity t = transformationService.getById(id);
        if (t == null) return R.fail(404, "Transformation not found");
        verifyTemplateWorkspace(t, workspaceId);
        verifyKBWorkspace(kbId, workspaceId != null ? workspaceId : 1L);

        try {
            WikiTransformationAggregator.Result res = aggregator.aggregate(t, kbId, "manual");
            if (res.pageId() == null) {
                return R.fail(409, res.title()); // when sources are empty we put the reason in title field
            }
            return R.ok(Map.of(
                    "pageId", res.pageId(),
                    "slug", res.slug(),
                    "title", res.title(),
                    "sourcesUsed", res.sourcesUsed(),
                    "charsFed", res.charsFed(),
                    "created", res.created()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        }
    }

    // ==================== Runs ====================

    @RequireWorkspaceRole("viewer")
    @GetMapping("/runs/{runId}")
    public R<WikiTransformationRunEntity> getRun(@PathVariable Long runId,
                                                  @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        WikiTransformationRunEntity run = transformationService.getRun(runId);
        if (run == null) return R.fail(404, "Run not found");
        verifyKBWorkspace(run.getKbId(), workspaceId != null ? workspaceId : 1L);
        return R.ok(run);
    }

    @RequireWorkspaceRole("viewer")
    @GetMapping("/runs")
    public R<List<WikiTransformationRunEntity>> listRuns(
            @RequestParam(required = false) Long rawId,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) Long transformationId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        if (rawId != null) {
            return R.ok(transformationService.listRunsByRaw(rawId, limit));
        }
        if (transformationId != null) {
            WikiTransformationEntity t = transformationService.getById(transformationId);
            if (t == null) return R.fail(404, "Transformation not found");
            verifyTemplateWorkspace(t, wsId);
            return R.ok(transformationService.listRunsByTransformation(transformationId, limit));
        }
        if (kbId != null) {
            verifyKBWorkspace(kbId, wsId);
            return R.ok(transformationService.listRunsByKb(kbId, limit));
        }
        return R.fail(400, "One of rawId / kbId / transformationId is required");
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "Cancel a still-running transformation run",
               description = "Marks the run as cancelled so the executor drops the eventual LLM output. "
                           + "The HTTP request to the model continues server-side because most providers "
                           + "do not support cancellation; this endpoint affects bookkeeping only.")
    @PostMapping("/runs/{runId}/cancel")
    public R<Void> cancelRun(@PathVariable Long runId,
                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        WikiTransformationRunEntity run = transformationService.getRun(runId);
        if (run == null) return R.fail(404, "Run not found");
        verifyKBWorkspace(run.getKbId(), workspaceId != null ? workspaceId : 1L);
        boolean cancelled = executor.cancelRun(runId);
        if (!cancelled) return R.fail(409, "Run is not running");
        return R.ok();
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "Save a completed run's output as a synthesis wiki page",
               description = "Idempotent: re-saving an already-saved run updates the same page slug.")
    @PostMapping("/runs/{runId}/save-as-page")
    public R<Map<String, Object>> saveRunAsPage(@PathVariable Long runId,
                                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        WikiTransformationRunEntity run = transformationService.getRun(runId);
        if (run == null) return R.fail(404, "Run not found");
        verifyKBWorkspace(run.getKbId(), workspaceId != null ? workspaceId : 1L);
        try {
            var page = executor.manualSaveRunAsPage(runId);
            if (page == null) return R.fail(503, "Page service unavailable");
            return R.ok(Map.of(
                    "pageId", page.getId(),
                    "slug", page.getSlug(),
                    "title", page.getTitle()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        }
    }

    @RequireWorkspaceRole("member")
    @DeleteMapping("/runs/{runId}")
    public R<Void> deleteRun(@PathVariable Long runId,
                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        WikiTransformationRunEntity run = transformationService.getRun(runId);
        if (run != null) {
            verifyKBWorkspace(run.getKbId(), workspaceId != null ? workspaceId : 1L);
            transformationService.deleteRun(runId);
        }
        return R.ok();
    }

    // ==================== helpers ====================

    private void verifyKBWorkspace(Long kbId, Long workspaceId) {
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) {
            throw new MateClawException("Knowledge base not found");
        }
        long wsId = workspaceId != null ? workspaceId : 1L;
        if (kb.getWorkspaceId() != null && !kb.getWorkspaceId().equals(wsId)) {
            throw new MateClawException("err.common.wrong_workspace", 403, "Resource does not belong to current workspace");
        }
    }

    private void verifyTemplateWorkspace(WikiTransformationEntity t, Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        if (t.getWorkspaceId() != null && !t.getWorkspaceId().equals(wsId)) {
            throw new MateClawException("err.common.wrong_workspace", 403, "Resource does not belong to current workspace");
        }
    }
}
