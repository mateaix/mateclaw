package vip.mate.workflow.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.workflow.compiler.PublishContext;
import vip.mate.workflow.compiler.WorkflowAclPort;
import vip.mate.workflow.compiler.WorkflowCompileFailedException;
import vip.mate.workflow.compiler.WorkflowCompiler;
import vip.mate.workflow.model.WorkflowEntity;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.model.WorkflowRunPauseEntity;
import vip.mate.workflow.model.WorkflowRunStepEntity;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.repository.WorkflowRunPauseMapper;
import vip.mate.workflow.repository.WorkflowRunStepMapper;
import vip.mate.workflow.service.WorkflowService;

import java.util.List;

/**
 * REST surface for workflow CRUD + draft / publish / run inspection.
 * Endpoints follow the project convention of a single workspace id passed
 * via query param (production deploys read it from {@code X-Workspace-Id}
 * via the workspace interceptor; the param fallback keeps tests simple).
 */
@Tag(name = "工作流管理")
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowRunMapper runMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final WorkflowRunPauseMapper pauseMapper;
    private final WorkflowCompiler compiler;
    private final WorkflowAclPort aclPort;

    @Operation(summary = "List workflows in the workspace")
    @GetMapping
    public R<List<WorkflowEntity>> list(@RequestHeader("X-Workspace-Id") long workspaceId) {
        return R.ok(workflowService.listByWorkspace(workspaceId));
    }

    @Operation(summary = "Get a workflow by id (includes inline draft).")
    @GetMapping("/{id}")
    public R<WorkflowEntity> get(@PathVariable long id,
                                 @RequestHeader("X-Workspace-Id") long workspaceId) {
        WorkflowEntity row = workflowService.get(id, workspaceId);
        if (row == null) return R.fail("workflow not found: " + id);
        return R.ok(row);
    }

    @Operation(summary = "Create a workflow row (draft starts empty).")
    @PostMapping
    public R<WorkflowEntity> create(@RequestBody WorkflowEntity workflow,
                                    @RequestHeader("X-Workspace-Id") long workspaceId) {
        // Force the workspace from the trusted header — the request body
        // can't choose a workspace for the new row, otherwise a caller
        // could plant rows into another tenant.
        workflow.setWorkspaceId(workspaceId);
        return R.ok(workflowService.create(workflow));
    }

    @Operation(summary = "Update workflow metadata (name / description / enabled).")
    @PutMapping("/{id}")
    public R<WorkflowEntity> update(@PathVariable long id,
                                    @RequestBody WorkflowMetadataRequest body,
                                    @RequestHeader("X-Workspace-Id") long workspaceId) {
        return R.ok(workflowService.updateMetadata(id, workspaceId,
                body.name(), body.description(), body.enabled()));
    }

    @Operation(summary = "Save the inline draft graph_json without compiling.")
    @PutMapping("/{id}/draft")
    public R<WorkflowEntity> saveDraft(@PathVariable long id,
                                       @RequestBody WorkflowDraftRequest body,
                                       @RequestParam(value = "userId", required = false) Long userId,
                                       @RequestHeader("X-Workspace-Id") long workspaceId) {
        return R.ok(workflowService.saveDraft(id, workspaceId, body.draftJson(), userId));
    }

    @Operation(summary = "Compile the draft and surface diagnostics without persisting a revision.")
    @PostMapping("/{id}/compile")
    public ResponseEntity<?> compileDraft(@PathVariable long id,
                                          @RequestHeader("X-Workspace-Id") long workspaceId) {
        WorkflowEntity row = workflowService.get(id, workspaceId);
        if (row == null) {
            return ResponseEntity.badRequest().body(R.fail("workflow not found: " + id));
        }
        if (row.getDraftJson() == null) {
            return ResponseEntity.badRequest()
                    .body(R.fail("workflow has no draft to compile: " + id));
        }
        WorkflowCompiler.Result result = compiler.compile(row.getDraftJson(),
                new PublishContext(0L, row.getWorkspaceId()), aclPort);
        if (!result.ok()) {
            return ResponseEntity.unprocessableEntity()
                    .body(buildCompileFailure(result.errors()));
        }
        return ResponseEntity.ok(R.ok());
    }

    @Operation(summary = "Compile the draft and persist a new revision pointed at by latest_revision_id.")
    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable long id,
                                     @RequestBody(required = false) WorkflowPublishRequest body,
                                     @RequestParam(value = "userId", required = false) Long userId,
                                     @RequestHeader("X-Workspace-Id") long workspaceId) {
        try {
            WorkflowService.PublishOutcome outcome = workflowService.publish(id, workspaceId, userId,
                    body == null ? null : body.note());
            return ResponseEntity.ok(R.ok(outcome));
        } catch (WorkflowCompileFailedException e) {
            return ResponseEntity.unprocessableEntity().body(buildCompileFailure(e.errors()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(e.getMessage()));
        }
    }

    @Operation(summary = "Soft-delete a workflow row.")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id,
                          @RequestHeader("X-Workspace-Id") long workspaceId) {
        workflowService.delete(id, workspaceId);
        return R.ok();
    }

    @Operation(summary = "List the most recent runs for a workflow.")
    @GetMapping("/{id}/runs")
    public R<List<WorkflowRunEntity>> listRuns(@PathVariable long id,
                                               @RequestParam(value = "limit", defaultValue = "50") int limit,
                                               @RequestHeader("X-Workspace-Id") long workspaceId) {
        // Verify the parent workflow belongs to the caller's workspace
        // before listing run rows, otherwise a caller could enumerate
        // every workspace's runs by guessing workflow ids.
        if (workflowService.get(id, workspaceId) == null) {
            return R.fail("workflow not found: " + id);
        }
        int capped = Math.min(Math.max(limit, 1), 200);
        List<WorkflowRunEntity> rows = runMapper.selectList(new LambdaQueryWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getWorkflowId, id)
                .eq(WorkflowRunEntity::getWorkspaceId, workspaceId)
                .orderByDesc(WorkflowRunEntity::getStartedAt)
                .last("LIMIT " + capped));
        return R.ok(rows);
    }

    @Operation(summary = "List paused runs across the workspace so operators can resume them.")
    @GetMapping("/runs/paused")
    public R<List<PausedRunSummary>> listPausedRuns(@RequestParam(value = "limit", defaultValue = "50") int limit,
                                                    @RequestHeader("X-Workspace-Id") long workspaceId) {
        // Without this listing surface, an await_approval pause is only
        // recoverable by a caller that already happens to know the runId
        // and pauseToken — i.e. orphaned for any human operator. The
        // shape is small (run + active pause token) because operator UIs
        // primarily need to know "which runs are blocked, and how do I
        // resume them".
        int capped = Math.min(Math.max(limit, 1), 200);
        List<WorkflowRunEntity> paused = runMapper.selectList(new LambdaQueryWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getWorkspaceId, workspaceId)
                .eq(WorkflowRunEntity::getState, "paused")
                .orderByDesc(WorkflowRunEntity::getStartedAt)
                .last("LIMIT " + capped));
        if (paused.isEmpty()) return R.ok(List.of());
        List<PausedRunSummary> out = new java.util.ArrayList<>(paused.size());
        for (WorkflowRunEntity run : paused) {
            WorkflowRunPauseEntity pause = pauseMapper.selectOne(
                    new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                            .eq(WorkflowRunPauseEntity::getRunId, run.getId())
                            .isNull(WorkflowRunPauseEntity::getResumedAt)
                            .orderByDesc(WorkflowRunPauseEntity::getPausedAt)
                            .last("LIMIT 1"));
            out.add(new PausedRunSummary(run, pause));
        }
        return R.ok(out);
    }

    @Operation(summary = "Inspect a single run with its step rows for replay / debugging.")
    @GetMapping("/runs/{runId}")
    public R<RunDetail> getRun(@PathVariable long runId,
                               @RequestHeader("X-Workspace-Id") long workspaceId) {
        WorkflowRunEntity run = runMapper.selectById(runId);
        if (run == null) return R.fail("run not found: " + runId);
        if (run.getWorkspaceId() == null || run.getWorkspaceId() != workspaceId) {
            // Same surface as "not found" — don't leak run id existence
            // to non-owning workspaces.
            return R.fail("run not found: " + runId);
        }
        List<WorkflowRunStepEntity> steps = stepMapper.selectList(new LambdaQueryWrapper<WorkflowRunStepEntity>()
                .eq(WorkflowRunStepEntity::getRunId, runId)
                .orderByAsc(WorkflowRunStepEntity::getStepIndex)
                .orderByAsc(WorkflowRunStepEntity::getIterationIndex));
        // Include the most recent unresolved pause so the caller can wire
        // a "resume" button without a second roundtrip.
        WorkflowRunPauseEntity activePause = pauseMapper.selectOne(
                new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                        .eq(WorkflowRunPauseEntity::getRunId, runId)
                        .isNull(WorkflowRunPauseEntity::getResumedAt)
                        .orderByDesc(WorkflowRunPauseEntity::getPausedAt)
                        .last("LIMIT 1"));
        return R.ok(new RunDetail(run, steps, activePause));
    }

    /** Narrow patch shape for {@link #update}; keeps the metadata path
     *  from accepting fields that would clobber the draft. */
    public record WorkflowMetadataRequest(String name, String description, Boolean enabled) {}

    public record RunDetail(WorkflowRunEntity run,
                            List<WorkflowRunStepEntity> steps,
                            WorkflowRunPauseEntity activePause) {}

    public record PausedRunSummary(WorkflowRunEntity run, WorkflowRunPauseEntity pause) {}

    private static R<CompileErrorResponse> buildCompileFailure(List<vip.mate.workflow.compiler.CompileError> errors) {
        R<CompileErrorResponse> r = new R<>();
        r.setCode(422);
        r.setMsg("compile failed");
        r.setData(CompileErrorResponse.of(errors));
        return r;
    }
}
