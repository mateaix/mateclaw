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
import vip.mate.workflow.model.WorkflowRunStepEntity;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.repository.WorkflowRunStepMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full Spring context against H2, overrides {@link AgentInvoker}
 * with a deterministic stub, and drives the runner through the four base
 * step modes (sequential / fan_out / collect / conditional). Asserts that
 * run + step rows persist correctly, payloads round-trip, and the rolling
 * outputs map threads outputs forward as the steps progress.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_runtime_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
@Import(StubAgentInvokerConfig.class)
class WorkflowRunnerIntegrationTest {

    @Autowired private WorkflowRunner runner;
    @Autowired private WorkflowParser parser;
    @Autowired private PayloadStore payloadStore;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private WorkflowRunStepMapper stepMapper;
    @Autowired private StubAgentInvoker stubInvoker;

    @Test
    @DisplayName("Three-step linear pipeline completes; outputs thread forward; payloads readable.")
    void linearSequentialPipelineCompletesSuccessfully() {
        stubInvoker.reset();
        stubInvoker.respond("collect-data", "{\"customer\":\"Acme\",\"tier\":\"enterprise\"}");
        stubInvoker.respond("draft-summary", "Acme is an enterprise customer.");
        stubInvoker.respond("polish", "Final: Acme — enterprise.");

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"step-a","agentName":"collect-data","mode":{"type":"sequential"},
                     "promptTemplate":"collect for {{ inputs.id }}",
                     "outputVar":"data","outputContentType":"json"},
                    {"name":"step-b","agentName":"draft-summary","mode":{"type":"sequential"},
                     "promptTemplate":"customer={{ outputs.data.customer }} tier={{ outputs.data.tier }}",
                     "outputVar":"summary","outputContentType":"text"},
                    {"name":"step-c","agentName":"polish","mode":{"type":"sequential"},
                     "promptTemplate":"polish: {{ outputs.summary }}",
                     "outputVar":"final","outputContentType":"text"}
                  ]
                }
                """);

        WorkflowRunRequest req = new WorkflowRunRequest(
                42L, 1L, 99L, "manual", Map.of("id", "Acme"));
        WorkflowRunResult result = runner.run(graph, req);

        assertEquals("succeeded", result.state());
        assertNotNull(result.finalOutputUri());

        // Final payload retrievable.
        assertEquals("Final: Acme — enterprise.", payloadStore.readString(result.finalOutputUri()));

        // Run row persisted.
        WorkflowRunEntity runRow = runMapper.selectById(result.runId());
        assertEquals("succeeded", runRow.getState());
        assertNotNull(runRow.getInitialInputRef());
        assertEquals(result.finalOutputUri(), runRow.getFinalOutputRef());
        assertNotNull(runRow.getCompletedAt());

        // Three step rows in the right order, all succeeded.
        List<WorkflowRunStepEntity> stepRows = stepMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunStepEntity>()
                        .eq(WorkflowRunStepEntity::getRunId, result.runId())
                        .orderByAsc(WorkflowRunStepEntity::getStepIndex));
        assertEquals(3, stepRows.size());
        for (WorkflowRunStepEntity row : stepRows) {
            assertEquals("succeeded", row.getState());
            assertNotNull(row.getOutputRef());
            assertNotNull(row.getDurationMs());
        }
        assertEquals("step-a", stepRows.get(0).getStepName());
        assertEquals("json", stepRows.get(0).getOutputContentType());
        assertEquals("text", stepRows.get(1).getOutputContentType());

        // The stub saw the rendered prompts threading prior outputs forward.
        assertEquals("collect for Acme", stubInvoker.lastPromptFor("collect-data"));
        assertEquals("customer=Acme tier=enterprise", stubInvoker.lastPromptFor("draft-summary"));
        assertEquals("polish: Acme is an enterprise customer.",
                stubInvoker.lastPromptFor("polish"));
    }

    @Test
    @DisplayName("Fan-out group runs branches in parallel and the collect joins their outputs.")
    void fanOutThenCollectRunsBranchesAndJoins() {
        stubInvoker.reset();
        stubInvoker.respond("notify-email", "email-ok");
        stubInvoker.respond("notify-feishu", "feishu-ok");

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"email","agentName":"notify-email","mode":{"type":"fan_out"},
                     "promptTemplate":"email","outputVar":"emailResult","outputContentType":"text"},
                    {"name":"feishu","agentName":"notify-feishu","mode":{"type":"fan_out"},
                     "promptTemplate":"feishu","outputVar":"feishuResult","outputContentType":"text"},
                    {"name":"join","mode":{"type":"collect"}}
                  ]
                }
                """);

        WorkflowRunRequest req = new WorkflowRunRequest(43L, 1L, 99L, "manual", Map.of());
        WorkflowRunResult result = runner.run(graph, req);

        assertEquals("succeeded", result.state());

        List<WorkflowRunStepEntity> stepRows = stepMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunStepEntity>()
                        .eq(WorkflowRunStepEntity::getRunId, result.runId())
                        .orderByAsc(WorkflowRunStepEntity::getStepIndex));
        assertEquals(3, stepRows.size());
        // Branch rows carry iteration index; join row does not.
        assertEquals(0, stepRows.get(0).getIterationIndex());
        assertEquals(1, stepRows.get(1).getIterationIndex());
        assertNull(stepRows.get(2).getIterationIndex());
        for (WorkflowRunStepEntity row : stepRows) {
            assertEquals("succeeded", row.getState());
        }
        // Both branches were invoked exactly once.
        assertEquals(1, stubInvoker.invocationCount("notify-email"));
        assertEquals(1, stubInvoker.invocationCount("notify-feishu"));
    }

    @Test
    @DisplayName("fan_out branches see only the pre-group outputs — not sibling writes.")
    void fanOutBranchesIsolateFromSiblingOutputs() {
        stubInvoker.reset();
        // Branch A's outputVar is "aResult"; branch B's is "bResult". Each
        // branch's prompt template references the OTHER branch's outputVar.
        // With snapshot isolation, neither branch can see the sibling's
        // value — both prompts must render the sibling slot as the empty
        // / default form. Without isolation, whichever branch finishes
        // first would mutate the shared outputs map and the slower branch
        // would render the sibling's value into its prompt, making the
        // test schedule-dependent.
        stubInvoker.respond("branch-a", "valueFromA");
        stubInvoker.respond("branch-b", "valueFromB");

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"seed","agentName":"seeder","mode":{"type":"sequential"},
                     "promptTemplate":"seed","outputVar":"shared","outputContentType":"text"},
                    {"name":"a","agentName":"branch-a","mode":{"type":"fan_out"},
                     "promptTemplate":"a-sees-shared:{{ outputs.shared }}|sibling:{{ outputs.bResult }}",
                     "outputVar":"aResult","outputContentType":"text"},
                    {"name":"b","agentName":"branch-b","mode":{"type":"fan_out"},
                     "promptTemplate":"b-sees-shared:{{ outputs.shared }}|sibling:{{ outputs.aResult }}",
                     "outputVar":"bResult","outputContentType":"text"},
                    {"name":"join","mode":{"type":"collect"}},
                    {"name":"after","agentName":"after","mode":{"type":"sequential"},
                     "promptTemplate":"after-sees:a={{ outputs.aResult }}|b={{ outputs.bResult }}"}
                  ]
                }
                """);
        stubInvoker.respond("seeder", "seedValue");
        stubInvoker.respond("after", "ok");

        WorkflowRunResult result = runner.run(graph,
                new WorkflowRunRequest(43L, 1L, 99L, "manual", Map.of()));
        assertEquals("succeeded", result.state());

        // Each branch must see its OWN snapshot of pre-group outputs (the
        // "shared" var written by the seeder) but NOT the sibling's outputVar.
        String aPrompt = stubInvoker.lastPromptFor("branch-a");
        String bPrompt = stubInvoker.lastPromptFor("branch-b");
        assertTrue(aPrompt.contains("a-sees-shared:seedValue"),
                "branch a must see pre-group outputs.shared, got: " + aPrompt);
        assertTrue(bPrompt.contains("b-sees-shared:seedValue"),
                "branch b must see pre-group outputs.shared, got: " + bPrompt);
        assertTrue(!aPrompt.contains("valueFromB"),
                "branch a's prompt MUST NOT contain branch b's output, got: " + aPrompt);
        assertTrue(!bPrompt.contains("valueFromA"),
                "branch b's prompt MUST NOT contain branch a's output, got: " + bPrompt);

        // After the fan_out group, the master context must have BOTH branch
        // outputs visible to subsequent steps (deterministic merge).
        String afterPrompt = stubInvoker.lastPromptFor("after");
        assertTrue(afterPrompt.contains("a=valueFromA"),
                "post-group step must see merged aResult, got: " + afterPrompt);
        assertTrue(afterPrompt.contains("b=valueFromB"),
                "post-group step must see merged bResult, got: " + afterPrompt);
    }

    @Test
    @DisplayName("Conditional whose guard evaluates false skips the embedded step.")
    void conditionalSkipsWhenGuardFalse() {
        stubInvoker.reset();
        stubInvoker.respond("collect-data", "{\"tier\":\"smb\"}");
        stubInvoker.respond("vip-route", "should-not-run");

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"a","agentName":"collect-data","mode":{"type":"sequential"},
                     "promptTemplate":"x","outputVar":"d","outputContentType":"json"},
                    {"name":"b","agentName":"vip-route",
                     "mode":{"type":"conditional","expression":"outputs.d.tier == 'enterprise'"},
                     "promptTemplate":"vip route"}
                  ]
                }
                """);

        WorkflowRunResult result = runner.run(graph,
                new WorkflowRunRequest(44L, 1L, 99L, "manual", Map.of()));
        assertEquals("succeeded", result.state());

        List<WorkflowRunStepEntity> stepRows = stepMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunStepEntity>()
                        .eq(WorkflowRunStepEntity::getRunId, result.runId())
                        .orderByAsc(WorkflowRunStepEntity::getStepIndex));
        assertEquals(2, stepRows.size());
        assertEquals("succeeded", stepRows.get(0).getState());
        assertEquals("skipped", stepRows.get(1).getState());
        assertNull(stepRows.get(1).getOutputRef());
        // The vip-route agent was never invoked.
        assertEquals(0, stubInvoker.invocationCount("vip-route"));
    }

    @Test
    @DisplayName("A failing agent invocation marks the run failed and records the error.")
    void failedAgentInvocationMarksRunFailed() {
        stubInvoker.reset();
        stubInvoker.respondWithThrow("broken", new RuntimeException("upstream timeout"));

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"x","agentName":"broken","mode":{"type":"sequential"},
                     "promptTemplate":"do thing"}
                  ]
                }
                """);

        WorkflowRunResult result = runner.run(graph,
                new WorkflowRunRequest(45L, 1L, 99L, "manual", Map.of()));
        assertEquals("failed", result.state());
        assertNull(result.finalOutputUri());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("upstream timeout"),
                "error message should propagate the underlying cause: " + result.errorMessage());

        WorkflowRunEntity row = runMapper.selectById(result.runId());
        assertEquals("failed", row.getState());
        assertNotNull(row.getCompletedAt());

        List<WorkflowRunStepEntity> stepRows = stepMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunStepEntity>()
                        .eq(WorkflowRunStepEntity::getRunId, result.runId()));
        assertEquals(1, stepRows.size());
        assertEquals("failed", stepRows.get(0).getState());
    }

}
