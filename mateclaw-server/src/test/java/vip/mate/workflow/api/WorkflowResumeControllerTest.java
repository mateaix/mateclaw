package vip.mate.workflow.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.common.result.R;
import vip.mate.workflow.compiler.WorkflowParser;
import vip.mate.workflow.compiler.ir.WorkflowGraph;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.model.WorkflowRunPauseEntity;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.repository.WorkflowRunPauseMapper;
import vip.mate.workflow.runtime.StubAgentInvoker;
import vip.mate.workflow.runtime.StubAgentInvokerConfig;
import vip.mate.workflow.runtime.WorkflowRunRequest;
import vip.mate.workflow.runtime.WorkflowRunner;
import vip.mate.workflow.service.WorkflowService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace-isolation contract for the resume endpoint: cross-tenant probes
 * cannot resolve foreign run ids, and a leaked pause token from one
 * workspace cannot be used to advance a run in another. The earlier
 * version of the controller leaned on the resumer alone for the security
 * check, which is why these tests guard the integration boundary
 * explicitly.
 *
 * <p>The happy path uses {@link WorkflowRunner} to run a workflow with an
 * {@code await_approval} step so a real run + pause row is materialized,
 * then advances the run via the controller and asserts the run reaches
 * succeeded.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_resume_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.workflow.trigger.async-dispatch=false"
})
@Import({StubAgentInvokerConfig.class, WorkflowControllerTest.PermissiveAclConfig.class})
class WorkflowResumeControllerTest {

    @Autowired private WorkflowResumeController controller;
    @Autowired private WorkflowRunner runner;
    @Autowired private WorkflowParser parser;
    @Autowired private WorkflowService workflowService;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private WorkflowRunPauseMapper pauseMapper;
    @Autowired private StubAgentInvoker stubInvoker;

    @Test
    @DisplayName("Cross-workspace probe returns 404 — no information leak about foreign run ids.")
    void crossWorkspaceProbeReturns404() {
        long ownerWs = 1100L;
        long stranger = 1200L;
        long runId = pauseAndCaptureRunId(ownerWs);

        ResponseEntity<?> response = controller.resume(runId,
                new WorkflowResumeController.ResumeRequest("any-token", "approved", null),
                stranger);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("Resume with a pauseToken that belongs to a different run returns 404.")
    void foreignPauseTokenReturns404() {
        long ws = 1300L;
        long runIdA = pauseAndCaptureRunId(ws);
        long runIdB = pauseAndCaptureRunId(ws);
        WorkflowRunPauseEntity pauseB = pauseMapper.selectOne(
                new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                        .eq(WorkflowRunPauseEntity::getRunId, runIdB)
                        .last("LIMIT 1"));
        assertNotNull(pauseB, "fixture: pause row for runB must exist");

        // pause token comes from runB but we POST against runA; controller must reject.
        ResponseEntity<?> response = controller.resume(runIdA,
                new WorkflowResumeController.ResumeRequest(pauseB.getPauseToken(), "approved", null),
                ws);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("Happy path: workspace owner resumes and the run reaches succeeded.")
    void happyPathResumeSucceeds() {
        long ws = 1400L;
        long runId = pauseAndCaptureRunId(ws);
        WorkflowRunPauseEntity pause = pauseMapper.selectOne(
                new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                        .eq(WorkflowRunPauseEntity::getRunId, runId)
                        .last("LIMIT 1"));
        assertNotNull(pause);

        ResponseEntity<?> response = controller.resume(runId,
                new WorkflowResumeController.ResumeRequest(pause.getPauseToken(), "approved", null),
                ws);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        R<WorkflowResumeController.ResumeResponse> body =
                (R<WorkflowResumeController.ResumeResponse>) response.getBody();
        assertNotNull(body);
        assertEquals(200, body.getCode());

        WorkflowRunEntity reloaded = runMapper.selectById(runId);
        assertEquals("succeeded", reloaded.getState(),
                "approved resume should drive the run all the way to succeeded");
        WorkflowRunPauseEntity reloadedPause = pauseMapper.selectById(pause.getId());
        assertNotNull(reloadedPause.getResumedAt());
        assertEquals("approved", reloadedPause.getResumeOutcome());
    }

    @Test
    @DisplayName("Bad outcome string yields 400 instead of pretending the resume happened.")
    void invalidOutcomeReturns400() {
        long ws = 1500L;
        long runId = pauseAndCaptureRunId(ws);
        WorkflowRunPauseEntity pause = pauseMapper.selectOne(
                new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                        .eq(WorkflowRunPauseEntity::getRunId, runId)
                        .last("LIMIT 1"));
        assertNotNull(pause);

        ResponseEntity<?> response = controller.resume(runId,
                new WorkflowResumeController.ResumeRequest(pause.getPauseToken(), "yolo", null),
                ws);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    /**
     * Use the real workflow service to create + draft + publish a tiny
     * graph that pauses on an await_approval step, then run it once so
     * a paused run + pause row exists for the controller to act on.
     * Driving through {@link WorkflowService} keeps the wf row,
     * revision, and {@code latest_revision_id} link consistent.
     */
    private long pauseAndCaptureRunId(long workspaceId) {
        stubInvoker.reset();
        stubInvoker.respond("greeter", "ok");

        var wf = new vip.mate.workflow.model.WorkflowEntity();
        wf.setWorkspaceId(workspaceId);
        wf.setName("resume-fixture-" + workspaceId + "-" + System.nanoTime());
        wf.setEnabled(true);
        wf = workflowService.create(wf);

        String graphJson = """
                {
                  "steps": [
                    {"name":"start","agentName":"greeter","mode":{"type":"sequential"},
                     "promptTemplate":"hi"},
                    {"name":"approve",
                     "mode":{"type":"await_approval","approvalKind":"manager",
                             "approverChannels":["web"]}}
                  ]
                }
                """;
        workflowService.saveDraft(wf.getId(), workspaceId, graphJson, 1L);
        WorkflowService.PublishOutcome pub = workflowService.publish(
                wf.getId(), workspaceId, 1L, "fixture v1");

        WorkflowGraph graph = parser.parse(graphJson);
        var result = runner.run(graph,
                new WorkflowRunRequest(wf.getId(), pub.revision().getId(), workspaceId,
                        "manual", Map.of()));
        assertTrue("paused".equals(result.state()),
                "fixture: expected a paused run, got " + result.state());
        return result.runId();
    }
}
