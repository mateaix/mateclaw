package vip.mate.workflow.runtime.mode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowStep;
import vip.mate.workflow.model.WorkflowRunPauseEntity;
import vip.mate.workflow.model.WorkflowRunStepEntity;
import vip.mate.workflow.repository.WorkflowRunPauseMapper;
import vip.mate.workflow.repository.WorkflowRunStepMapper;
import vip.mate.workflow.runtime.StepAdapter;
import vip.mate.workflow.runtime.StepResult;
import vip.mate.workflow.runtime.WorkflowRunContext;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * {@code await_approval} — pauses the run pending an external approval
 * decision. Inserts a {@code mate_workflow_run_pause} row keyed by a fresh
 * {@code pauseToken}, then returns {@link StepResult.State#PAUSED} so the
 * runner can short-circuit and mark the run row {@code paused}.
 *
 * <p><b>Resolution path (v0):</b>
 * <ol>
 *   <li>Operator UI lists paused runs via {@code GET /api/v1/workflows/runs/paused},
 *       which returns the run + the active pause record (including the
 *       {@code pauseToken}).</li>
 *   <li>Operator picks an outcome and POSTs to
 *       {@code /api/v1/workflows/runs/{runId}/resume} with the
 *       {@code pauseToken} and {@code outcome ∈ {approved, rejected, timeout, cancelled}}.</li>
 *   <li>{@code WorkflowResumer} marks the pause row resolved and advances
 *       the run state machine.</li>
 * </ol>
 *
 * <p>The pause row's {@code resume_deadline} is honoured when the step
 * declares a {@code timeoutSecs}; otherwise it stays {@code null} and the
 * resumer treats the pause as open-ended.
 *
 * <p>The {@code external_approval_id} column on the pause row is reserved
 * for a future integration that bridges workflow pauses into the
 * tool-approval inbox; v0 leaves it null and routes resolution through the
 * pause-token path above.
 */
@Component
public class AwaitApprovalStepAdapter implements StepAdapter {

    private final WorkflowRunPauseMapper pauseMapper;
    private final WorkflowRunStepMapper stepMapper;

    public AwaitApprovalStepAdapter(WorkflowRunPauseMapper pauseMapper,
                                    WorkflowRunStepMapper stepMapper) {
        this.pauseMapper = pauseMapper;
        this.stepMapper = stepMapper;
    }

    @Override
    public String typeName() { return "await_approval"; }

    @Override
    public StepResult execute(WorkflowStep step, WorkflowRunContext context) {
        if (!(step.mode() instanceof StepMode.AwaitApproval cfg)) {
            return StepResult.failed("await_approval adapter received non-await mode: "
                    + step.mode().typeName());
        }

        // Look up the freshly opened step row so we can link the pause to it.
        WorkflowRunStepEntity stepRow = stepMapper.selectOne(new LambdaQueryWrapper<WorkflowRunStepEntity>()
                .eq(WorkflowRunStepEntity::getRunId, context.runId())
                .eq(WorkflowRunStepEntity::getStepName, step.name())
                .orderByDesc(WorkflowRunStepEntity::getId)
                .last("LIMIT 1"));
        if (stepRow == null) {
            return StepResult.failed("await_approval could not locate its run-step row");
        }

        String pauseToken = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        WorkflowRunPauseEntity pause = new WorkflowRunPauseEntity();
        pause.setRunId(context.runId());
        pause.setStepId(stepRow.getId());
        pause.setPauseKind("await_approval");
        pause.setPauseToken(pauseToken);
        pause.setPausedAt(now);
        if (cfg.timeoutSecs() != null && cfg.timeoutSecs() > 0) {
            pause.setResumeDeadline(now.plusSeconds(cfg.timeoutSecs()));
        }
        pauseMapper.insert(pause);

        return StepResult.paused(pauseToken,
                "awaiting " + (cfg.approvalKind() == null ? "approval" : cfg.approvalKind()));
    }
}
