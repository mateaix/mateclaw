package vip.mate.workflow.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.workflow.compiler.PublishContext;
import vip.mate.workflow.compiler.WorkflowAclPort;
import vip.mate.workflow.compiler.WorkflowCompiler;
import vip.mate.workflow.model.WorkflowEntity;
import vip.mate.workflow.model.WorkflowRevisionEntity;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.model.WorkflowRunPauseEntity;
import vip.mate.workflow.repository.WorkflowRevisionMapper;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.repository.WorkflowRunPauseMapper;
import vip.mate.workflow.runtime.WorkflowResumer;
import vip.mate.workflow.service.WorkflowService;

import java.nio.charset.StandardCharsets;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * HTTP surface for resuming an {@code await_approval} pause.
 *
 * <p>The pause itself is opened by {@code AwaitApprovalStepAdapter} when a
 * step transitions to PAUSED; this controller is what advances the run
 * once a human (operator UI / approval webhook / timeout sweeper)
 * decides the outcome. Without a public endpoint here, every paused run
 * would be stuck until someone called {@code WorkflowResumer} from
 * inside the JVM — exactly the gap the design called out as
 * "v0 functionally broken".
 *
 * <p>v0 supports the operator-driven path: an authorised user in the
 * owning workspace POSTs the pauseToken and an outcome. v1 will add the
 * webhook callback that {@code ApprovalWorkflowService.requestWorkflowApproval}
 * fires once the platform has a real workflow-approval pending row.
 */
@Tag(name = "工作流恢复")
@RestController
@RequestMapping("/api/v1/workflows/runs")
@RequiredArgsConstructor
public class WorkflowResumeController {

    private final WorkflowResumer resumer;
    private final WorkflowRunMapper runMapper;
    private final WorkflowRunPauseMapper pauseMapper;
    private final WorkflowRevisionMapper revisionMapper;
    private final WorkflowService workflowService;
    private final WorkflowCompiler compiler;
    private final WorkflowAclPort aclPort;

    @Operation(summary = "Resume a paused workflow run with the given outcome.")
    @PostMapping("/{runId}/resume")
    @RequireWorkspaceRole("admin")
    public ResponseEntity<?> resume(@PathVariable long runId,
                                    @RequestBody ResumeRequest body,
                                    @RequestHeader("X-Workspace-Id") long workspaceId) {
        if (body == null || body.pauseToken() == null || body.pauseToken().isBlank()) {
            return ResponseEntity.badRequest().body(R.fail("pauseToken is required"));
        }
        WorkflowResumer.ResumeOutcome outcome = parseOutcome(body.outcome());
        if (outcome == null) {
            return ResponseEntity.badRequest()
                    .body(R.fail("outcome must be one of: approved / rejected / timeout / cancelled"));
        }

        WorkflowRunEntity run = runMapper.selectById(runId);
        if (run == null
                || run.getWorkspaceId() == null
                || run.getWorkspaceId() != workspaceId) {
            // Same surface as "not found" so tenants can't probe foreign run ids.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(R.fail("run not found: " + runId));
        }

        // Validate the pause token belongs to this run before doing anything.
        // Without this, a token leaked from one workspace could resume a run
        // in another workspace just because the resumer doesn't itself check
        // the workspace-vs-token coupling.
        WorkflowRunPauseEntity pause = pauseMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowRunPauseEntity>()
                        .eq(WorkflowRunPauseEntity::getPauseToken, body.pauseToken())
                        .last("LIMIT 1"));
        if (pause == null || pause.getRunId() == null || pause.getRunId() != runId) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(R.fail("pause not found for run " + runId));
        }

        // Re-compile the locked revision to materialize a graph the resumer
        // can walk. Compile errors here would mean a published revision is
        // unparseable — should never happen in practice but we surface 500
        // explicitly rather than crashing inside the resumer.
        WorkflowEntity workflow = workflowService.get(run.getWorkflowId(), workspaceId);
        if (workflow == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(R.fail("workflow not found: " + run.getWorkflowId()));
        }
        WorkflowRevisionEntity revision = revisionMapper.selectById(run.getRevisionId());
        if (revision == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(R.fail("revision " + run.getRevisionId() + " missing for run " + runId));
        }
        // PublishContext is (workspaceId, publisherId) — mind the order;
        // ACL resolution scopes by workspace.
        WorkflowCompiler.Result compiled = compiler.compile(revision.getGraphJson(),
                new PublishContext(run.getWorkspaceId(), 0L), aclPort);
        if (!compiled.ok()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(R.fail("revision graph failed to recompile on resume"));
        }

        byte[] payload = (body.payload() == null || body.payload().isEmpty())
                ? null
                : body.payload().getBytes(StandardCharsets.UTF_8);

        WorkflowResumer.Outcome result = resumer.resume(compiled.graph(), body.pauseToken(), outcome, payload);
        return ResponseEntity.ok(R.ok(new ResumeResponse(result.kind().name(),
                result.runId(), result.errorMessage())));
    }

    private static WorkflowResumer.ResumeOutcome parseOutcome(String token) {
        if (token == null) return null;
        String t = token.trim().toLowerCase();
        return switch (t) {
            case "approved" -> WorkflowResumer.ResumeOutcome.APPROVED;
            case "rejected" -> WorkflowResumer.ResumeOutcome.REJECTED;
            case "timeout" -> WorkflowResumer.ResumeOutcome.TIMEOUT;
            case "cancelled" -> WorkflowResumer.ResumeOutcome.CANCELLED;
            default -> null;
        };
    }

    public record ResumeRequest(String pauseToken, String outcome, String payload) {}
    public record ResumeResponse(String kind, Long runId, String errorMessage) {}
}
