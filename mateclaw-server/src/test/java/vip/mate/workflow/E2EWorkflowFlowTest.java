package vip.mate.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.trigger.dispatch.WorkflowGraphLoader;
import vip.mate.trigger.ingest.TriggerEventEnvelope;
import vip.mate.trigger.ingest.TriggerEventIngestService;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.service.TriggerService;
import vip.mate.workflow.compiler.WorkflowAclPort;
import vip.mate.workflow.compiler.WorkflowParser;
import vip.mate.workflow.model.WorkflowEntity;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.repository.WorkflowMapper;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.runtime.StubAgentInvoker;
import vip.mate.workflow.runtime.StubAgentInvokerConfig;
import vip.mate.workflow.service.WorkflowService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke for the publish → ingest → run pipeline. Walks the full
 * happy path:
 *
 * <ol>
 *   <li>Create a workflow row and save a draft.</li>
 *   <li>Publish the draft → revision row + latest_revision_id pointer.</li>
 *   <li>Wire a {@code channel_message} trigger pointed at the workflow.</li>
 *   <li>Ingest an event envelope; the dedup / rate / bot-self filters pass.</li>
 *   <li>The trigger dispatches the latest revision through the runner;
 *       a workflow_run row appears in succeeded state and the run's
 *       triggered_by column points back at the trigger id.</li>
 * </ol>
 *
 * <p>Uses {@link StubAgentInvoker} so the agent step has no LLM dependency,
 * and a custom {@link WorkflowGraphLoader} backed by the published revision
 * (the same path production traffic walks).
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_e2e_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.workflow.trigger.async-dispatch=false"
})
@Import({StubAgentInvokerConfig.class, E2EWorkflowFlowTest.PermissiveAclConfig.class,
        E2EWorkflowFlowTest.RealRevisionLoaderConfig.class})
class E2EWorkflowFlowTest {

    @Autowired private WorkflowService workflowService;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private TriggerService triggerService;
    @Autowired private TriggerEventIngestService ingest;
    @Autowired private StubAgentInvoker stubInvoker;

    @Test
    @DisplayName("Create → publish → trigger ingest → workflow run succeeds end-to-end.")
    void publishAndIngestProducesRunningWorkflow() {
        stubInvoker.reset();
        stubInvoker.respond("greeter", "hello {{ inputs.who }}");

        // 1. Create the workflow + save a draft.
        WorkflowEntity wf = new WorkflowEntity();
        wf.setWorkspaceId(99L);
        wf.setName("e2e-flow");
        wf.setEnabled(true);
        wf = workflowService.create(wf);
        long workflowId = wf.getId();

        String draft = """
                {
                  "steps": [
                    {"name":"greet","agentName":"greeter","mode":{"type":"sequential"},
                     "promptTemplate":"hi {{ inputs.who }}"}
                  ]
                }
                """;
        workflowService.saveDraft(workflowId, wf.getWorkspaceId(), draft, 1L);

        // 2. Publish — compiler walks every phase including ACL via permissive port.
        WorkflowService.PublishOutcome pub = workflowService.publish(workflowId, wf.getWorkspaceId(), 1L, "e2e v1");
        assertNotNull(pub.revision().getId());
        assertEquals(1, pub.revision().getRevision());
        WorkflowEntity reloaded = workflowMapper.selectById(workflowId);
        assertEquals(pub.revision().getId(), reloaded.getLatestRevisionId());

        // 3. Wire a trigger pointed at the workflow.
        TriggerEntity trigger = new TriggerEntity();
        trigger.setWorkspaceId(99L);
        trigger.setName("e2e-trigger");
        trigger.setPatternType("webhook");
        trigger.setPatternJson("{}");
        trigger.setTargetType("workflow");
        trigger.setTargetId(workflowId);
        trigger.setEnabled(true);
        trigger.setRateLimitPerMin(60);
        trigger.setDedupWindowSecs(60);
        trigger.setBotSelfFilter(true);
        trigger.setPayloadTemplate("{\"who\":\"{{ event.who }}\"}");
        trigger = triggerService.create(trigger);
        long triggerId = trigger.getId();

        // 4. Ingest an event — pipeline filters all pass on a clean envelope.
        TriggerEventEnvelope envelope = new TriggerEventEnvelope(
                99L, "webhook", "evt-e2e-1", "user-1",
                Map.of("who", "alice"));
        List<TriggerEventIngestService.IngestResult> results = ingest.ingest(envelope);
        assertEquals(1, results.size());
        assertTrue(results.get(0).fired());
        assertEquals(triggerId, results.get(0).triggerId());

        // 5. The runner produced one succeeded workflow_run for this workflow,
        //    and the agent saw the templated input threaded through.
        List<WorkflowRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getWorkflowId, workflowId));
        assertEquals(1, runs.size());
        WorkflowRunEntity run = runs.get(0);
        assertEquals("succeeded", run.getState());
        assertEquals("trigger:" + triggerId, run.getTriggeredBy());
        assertEquals(pub.revision().getId(), run.getRevisionId());
        assertEquals("hi alice", stubInvoker.lastPromptFor("greeter"));
    }

    /**
     * Permissive ACL so the test does not need to provision agent / channel
     * rows just to satisfy the publish-time port. Production binding is
     * exercised by other tests.
     */
    @TestConfiguration
    static class PermissiveAclConfig {
        @Bean
        @Primary
        WorkflowAclPort permissiveAclPort() {
            return new WorkflowAclPort() {
                @Override public boolean agentExists(long ws, String n) { return true; }
                @Override public boolean agentIdExists(long ws, long id) { return true; }
                @Override public boolean channelAllowed(long ws, String n) { return true; }
                @Override public boolean employeeInWorkspace(long ws, String e) { return true; }
            };
        }
    }

    /**
     * The real production loader reads the revision row's graph_json, which
     * is exactly what we want here — the test exercises the published
     * revision rather than handing the loader a hand-built graph. It is
     * marked {@link Primary} so it wins over any test-only stub bound by
     * sibling tests living in the same Spring context.
     */
    @TestConfiguration
    static class RealRevisionLoaderConfig {
        @Bean
        @Primary
        WorkflowGraphLoader realRevisionLoader(WorkflowMapper workflowMapper,
                                               vip.mate.workflow.repository.WorkflowRevisionMapper revisionMapper,
                                               WorkflowParser parser) {
            return new vip.mate.trigger.dispatch.DefaultWorkflowGraphLoader(
                    workflowMapper, revisionMapper, parser);
        }
    }
}
