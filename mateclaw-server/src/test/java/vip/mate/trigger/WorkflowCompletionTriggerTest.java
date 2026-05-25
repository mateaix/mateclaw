package vip.mate.trigger;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.service.TriggerService;
import vip.mate.workflow.compiler.WorkflowParser;
import vip.mate.workflow.compiler.ir.WorkflowGraph;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.runtime.StubAgentInvoker;
import vip.mate.workflow.runtime.StubAgentInvokerConfig;
import vip.mate.workflow.runtime.WorkflowRunRequest;
import vip.mate.workflow.runtime.WorkflowRunResult;
import vip.mate.workflow.runtime.WorkflowRunner;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the workflow_completion event source is genuinely wired —
 * a workflow run reaching a terminal state must publish a Spring event
 * that the trigger module's bridge converts into a TriggerEventEnvelope
 * and pushes through the ingest pipeline. Without this end-to-end
 * confirmation the runtime decision could regress quietly.
 *
 * <p>Setup: a "downstream" trigger keyed on workflow_completion fires a
 * second workflow when the first one succeeds. The chain runs
 * synchronously in the same JVM thread so by the time the upstream
 * runner.run returns, the downstream run row should also exist.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:wf_completion_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.workflow.trigger.async-dispatch=false"
})
@Import({StubAgentInvokerConfig.class, TriggerDispatcherWorkflowTest.StubGraphLoaderConfig.class})
class WorkflowCompletionTriggerTest {

    @Autowired private WorkflowRunner runner;
    @Autowired private WorkflowParser parser;
    @Autowired private TriggerService triggerService;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private TriggerDispatcherWorkflowTest.StubGraphLoader stubGraphLoader;
    @Autowired private StubAgentInvoker stubInvoker;

    @Test
    @DisplayName("A succeeded workflow run fans out via workflow_completion to a downstream trigger.")
    void completionEventChainsToDownstreamWorkflow() {
        long upstreamWf = 7100L;
        long downstreamWf = 7200L;
        long workspace = 510L;

        stubInvoker.reset();
        stubInvoker.respond("greeter", "ok-upstream");
        stubInvoker.respond("downstream", "ok-downstream");

        // Bind the downstream graph so the trigger dispatcher has something
        // to compile when the completion event fires.
        stubGraphLoader.reset();
        stubGraphLoader.bind(downstreamWf, 1L,
                "{\"steps\":[{\"name\":\"d\",\"agentName\":\"downstream\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"chained\"}]}");

        // Wire a trigger that fires on workflow_completion of the upstream
        // workflow. The matcher narrows by sourceWorkflowId so it only fires
        // for the run we're about to start.
        TriggerEntity trig = new TriggerEntity();
        trig.setWorkspaceId(workspace);
        trig.setName("downstream-on-upstream");
        trig.setPatternType("workflow_completion");
        trig.setPatternJson("{\"sourceWorkflowId\":" + upstreamWf + ",\"stateFilter\":\"completed\"}");
        trig.setTargetType("workflow");
        trig.setTargetId(downstreamWf);
        trig.setEnabled(true);
        triggerService.create(trig);

        // Run the upstream workflow. Bind a graph for runner.run; we use
        // parser.parse since this test doesn't go through publish.
        WorkflowGraph graph = parser.parse(
                "{\"steps\":[{\"name\":\"u\",\"agentName\":\"greeter\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"go\"}]}");

        WorkflowRunResult upstream = runner.run(graph,
                new WorkflowRunRequest(upstreamWf, 1L, workspace, "manual", Map.of()));
        assertEquals("succeeded", upstream.state());

        // The completion event should have caused the downstream workflow
        // to run synchronously. Look for its run row.
        List<WorkflowRunEntity> downstreamRuns = runMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunEntity>()
                        .eq(WorkflowRunEntity::getWorkflowId, downstreamWf));
        assertTrue(!downstreamRuns.isEmpty(),
                "completion event should have triggered a downstream run");
        assertEquals("succeeded", downstreamRuns.get(0).getState());
        // The runner stamps triggered_by with "trigger:{id}" — confirm the
        // chain was traced through the trigger module, not invoked directly.
        assertTrue(downstreamRuns.get(0).getTriggeredBy() != null
                        && downstreamRuns.get(0).getTriggeredBy().startsWith("trigger:"),
                "downstream run should be triggered_by trigger:* — got "
                        + downstreamRuns.get(0).getTriggeredBy());
    }

    @Test
    @DisplayName("A workflow_completion trigger with mismatched sourceWorkflowId stays dormant.")
    void completionEventDoesNotMisfireForOtherWorkflows() {
        long upstreamWf = 7300L;
        long otherWf = 7400L;
        long workspace = 520L;

        stubInvoker.reset();
        stubInvoker.respond("greeter", "ok");

        // Trigger keyed on a DIFFERENT workflow id — it must not fire when
        // upstreamWf completes.
        TriggerEntity trig = new TriggerEntity();
        trig.setWorkspaceId(workspace);
        trig.setName("only-other");
        trig.setPatternType("workflow_completion");
        trig.setPatternJson("{\"sourceWorkflowId\":" + otherWf + "}");
        trig.setTargetType("workflow");
        trig.setTargetId(otherWf);
        trig.setEnabled(true);
        triggerService.create(trig);

        WorkflowGraph graph = parser.parse(
                "{\"steps\":[{\"name\":\"u\",\"agentName\":\"greeter\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"go\"}]}");
        runner.run(graph, new WorkflowRunRequest(upstreamWf, 1L, workspace, "manual", Map.of()));

        List<WorkflowRunEntity> otherRuns = runMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunEntity>()
                        .eq(WorkflowRunEntity::getWorkflowId, otherWf));
        assertTrue(otherRuns.isEmpty(),
                "trigger keyed on a different sourceWorkflowId must not fire");
    }
}
