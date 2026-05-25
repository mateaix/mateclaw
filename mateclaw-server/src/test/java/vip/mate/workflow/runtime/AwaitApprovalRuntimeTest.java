package vip.mate.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.workflow.compiler.WorkflowParser;
import vip.mate.workflow.compiler.ir.WorkflowGraph;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.model.WorkflowRunPauseEntity;
import vip.mate.workflow.model.WorkflowRunStepEntity;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.repository.WorkflowRunPauseMapper;
import vip.mate.workflow.repository.WorkflowRunStepMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the full pause / resume lifecycle through the runtime: a workflow
 * with a pre-step, an {@code await_approval} barrier, and a post-step pauses
 * mid-flight, persists a pause row, then resumes via {@link WorkflowResumer}
 * and reaches succeeded state. A second scenario verifies that a rejected
 * approval marks the run failed without running the post-step.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_await_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.workflow.trigger.async-dispatch=false"
})
@Import(StubAgentInvokerConfig.class)
class AwaitApprovalRuntimeTest {

    @Autowired private WorkflowRunner runner;
    @Autowired private WorkflowResumer resumer;
    @Autowired private WorkflowParser parser;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private WorkflowRunStepMapper stepMapper;
    @Autowired private WorkflowRunPauseMapper pauseMapper;
    @Autowired private StubAgentInvoker stubInvoker;

    @Test
    @DisplayName("Run pauses on await_approval, resumes via APPROVED, and completes the post-step.")
    void approvedResumeContinuesPostStep() {
        stubInvoker.reset();
        stubInvoker.respond("collect", "{\"customer\":\"Acme\"}");
        stubInvoker.respond("ship", "shipped to Acme");

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"collect-data","agentName":"collect","mode":{"type":"sequential"},
                     "promptTemplate":"collect","outputVar":"data","outputContentType":"json"},
                    {"name":"manager-approve",
                     "mode":{"type":"await_approval","approvalKind":"manager","approverChannels":["web"]}},
                    {"name":"ship-it","agentName":"ship","mode":{"type":"sequential"},
                     "promptTemplate":"ship to {{ outputs.data.customer }}",
                     "outputVar":"shipped","outputContentType":"text"}
                  ]
                }
                """);

        WorkflowRunResult initial = runner.run(graph,
                new WorkflowRunRequest(50L, 1L, 99L, "manual", Map.of()));
        assertEquals("paused", initial.state());
        // The post-step has not yet run.
        assertEquals(0, stubInvoker.invocationCount("ship"));

        // The pause row carries the resume token; pull it out.
        WorkflowRunPauseEntity pause = pauseMapper.selectOne(
                new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                        .eq(WorkflowRunPauseEntity::getRunId, initial.runId()));
        assertNotNull(pause);
        assertEquals("await_approval", pause.getPauseKind());
        assertNotNull(pause.getPauseToken());
        assertNull(pause.getResumedAt());
        // external_approval_id is wired now — the adapter calls
        // ApprovalWorkflowService.requestWorkflowApproval and writes the
        // returned id back so the workflow pause is visible in the
        // tool-approval inbox alongside chat-driven approvals.
        assertNotNull(pause.getExternalApprovalId(),
                "await_approval should record the linked tool_approval row id");

        // Resume with APPROVED — the resumer hydrates outputs from the prior
        // step row and threads them into the ship-it template.
        WorkflowResumer.Outcome outcome = resumer.resume(graph, pause.getPauseToken(),
                WorkflowResumer.ResumeOutcome.APPROVED, null);
        assertEquals(WorkflowResumer.Outcome.Kind.CONTINUED, outcome.kind());
        assertEquals("succeeded", outcome.finalResult().state());

        // Pause row is settled; run row is succeeded; step rows reflect outcomes.
        WorkflowRunPauseEntity reloaded = pauseMapper.selectById(pause.getId());
        assertNotNull(reloaded.getResumedAt());
        assertEquals("approved", reloaded.getResumeOutcome());

        WorkflowRunEntity runRow = runMapper.selectById(initial.runId());
        assertEquals("succeeded", runRow.getState());
        assertNotNull(runRow.getCompletedAt());

        List<WorkflowRunStepEntity> stepRows = stepMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunStepEntity>()
                        .eq(WorkflowRunStepEntity::getRunId, initial.runId())
                        .orderByAsc(WorkflowRunStepEntity::getStepIndex));
        assertEquals(3, stepRows.size());
        assertEquals("succeeded", stepRows.get(0).getState());
        assertEquals("succeeded", stepRows.get(1).getState());
        assertEquals("succeeded", stepRows.get(2).getState());

        // Post-step ran exactly once with the prior step's output threaded in.
        assertEquals(1, stubInvoker.invocationCount("ship"));
        assertEquals("ship to Acme", stubInvoker.lastPromptFor("ship"));
    }

    @Test
    @DisplayName("Rejected approval marks the run failed and skips the post-step.")
    void rejectedApprovalFailsRun() {
        stubInvoker.reset();
        stubInvoker.respond("collect", "{}");

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"collect","agentName":"collect","mode":{"type":"sequential"},
                     "promptTemplate":"x","outputVar":"d","outputContentType":"json"},
                    {"name":"approve",
                     "mode":{"type":"await_approval","approvalKind":"manager","approverChannels":["web"]}},
                    {"name":"after","agentName":"collect","mode":{"type":"sequential"},
                     "promptTemplate":"after"}
                  ]
                }
                """);

        WorkflowRunResult initial = runner.run(graph,
                new WorkflowRunRequest(51L, 1L, 99L, "manual", Map.of()));
        assertEquals("paused", initial.state());

        WorkflowRunPauseEntity pause = pauseMapper.selectOne(
                new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                        .eq(WorkflowRunPauseEntity::getRunId, initial.runId()));

        WorkflowResumer.Outcome outcome = resumer.resume(graph, pause.getPauseToken(),
                WorkflowResumer.ResumeOutcome.REJECTED, null);
        assertEquals(WorkflowResumer.Outcome.Kind.FAILED, outcome.kind());

        WorkflowRunEntity runRow = runMapper.selectById(initial.runId());
        assertEquals("failed", runRow.getState());
        assertTrue(runRow.getErrorMessage().contains("rejected"));

        // The post-collect agent was invoked once for step 1 only — never for step 3.
        assertEquals(1, stubInvoker.invocationCount("collect"));

        List<WorkflowRunStepEntity> stepRows = stepMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunStepEntity>()
                        .eq(WorkflowRunStepEntity::getRunId, initial.runId())
                        .orderByAsc(WorkflowRunStepEntity::getStepIndex));
        assertEquals(2, stepRows.size());
        assertEquals("succeeded", stepRows.get(0).getState());
        assertEquals("failed", stepRows.get(1).getState());
    }

    @Test
    @DisplayName("Resuming the same pause token twice yields ALREADY_RESOLVED on the second call.")
    void duplicateResumeIsIdempotent() {
        stubInvoker.reset();
        stubInvoker.respond("collect", "{}");

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"collect","agentName":"collect","mode":{"type":"sequential"},
                     "promptTemplate":"x","outputVar":"d","outputContentType":"json"},
                    {"name":"approve",
                     "mode":{"type":"await_approval","approvalKind":"k","approverChannels":["web"]}}
                  ]
                }
                """);

        WorkflowRunResult initial = runner.run(graph,
                new WorkflowRunRequest(52L, 1L, 99L, "manual", Map.of()));
        WorkflowRunPauseEntity pause = pauseMapper.selectOne(
                new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                        .eq(WorkflowRunPauseEntity::getRunId, initial.runId()));

        WorkflowResumer.Outcome first = resumer.resume(graph, pause.getPauseToken(),
                WorkflowResumer.ResumeOutcome.APPROVED, null);
        assertEquals(WorkflowResumer.Outcome.Kind.CONTINUED, first.kind());

        WorkflowResumer.Outcome second = resumer.resume(graph, pause.getPauseToken(),
                WorkflowResumer.ResumeOutcome.APPROVED, null);
        assertEquals(WorkflowResumer.Outcome.Kind.ALREADY_RESOLVED, second.kind());
    }
}
