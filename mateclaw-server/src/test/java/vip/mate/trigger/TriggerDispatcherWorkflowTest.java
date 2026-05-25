package vip.mate.trigger;

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
import vip.mate.trigger.dispatch.TriggerDispatcher;
import vip.mate.trigger.dispatch.WorkflowGraphLoader;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.repository.TriggerMapper;
import vip.mate.trigger.scheduler.TriggerScheduler;
import vip.mate.trigger.service.TriggerService;
import vip.mate.workflow.compiler.WorkflowParser;
import vip.mate.workflow.compiler.ir.WorkflowGraph;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.runtime.WorkflowRunResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the trigger dispatch path end-to-end against a stub workflow
 * loader and a stub agent invoker: a fired trigger should produce exactly
 * one {@code mate_workflow_run} row whose triggered_by column points back
 * at the trigger id, and the rendered payload template should land in the
 * run's initial inputs.
 *
 * <p>Also exercises the lamport-coordination path on the scheduler: a
 * fire dispatched with a stale captured version is silently dropped (the
 * scheduler self-cancels), no workflow run row appears, and the
 * registration is cleared.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:trigger_dispatch_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.workflow.trigger.async-dispatch=false"
})
@Import({vip.mate.workflow.runtime.StubAgentInvokerConfig.class,
        TriggerDispatcherWorkflowTest.StubGraphLoaderConfig.class})
class TriggerDispatcherWorkflowTest {

    @Autowired private TriggerService triggerService;
    @Autowired private TriggerMapper triggerMapper;
    @Autowired private TriggerScheduler scheduler;
    @Autowired private TriggerDispatcher dispatcher;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private vip.mate.workflow.runtime.StubAgentInvoker stubInvoker;
    @Autowired private StubGraphLoader stubGraphLoader;

    @Test
    @DisplayName("Dispatching a cron trigger creates a workflow run with payload-rendered inputs.")
    void dispatchProducesWorkflowRun() {
        stubInvoker.reset();
        stubInvoker.respond("greeter", "hello world");
        stubGraphLoader.reset();
        stubGraphLoader.bind(7000L, 11L,
                "{\"steps\":[{\"name\":\"a\",\"agentName\":\"greeter\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"hi {{ inputs.who }}\"}]}");

        TriggerEntity trigger = triggerService.create(cronTrigger(
                "hello-cron", "0 0 * * * *", 7000L,
                "{\"who\":\"{{ event.who }}\"}"));

        vip.mate.trigger.dispatch.DispatchResult result = dispatcher.dispatch(trigger,
                Map.of("who", "alice"));
        assertNotNull(result);
        assertEquals(vip.mate.trigger.dispatch.DispatchResult.Kind.FIRED, result.kind());
        assertEquals("hi alice", stubInvoker.lastPromptFor("greeter"));

        WorkflowRunEntity runRow = runMapper.selectById(result.runId());
        assertNotNull(runRow);
        assertEquals(7000L, runRow.getWorkflowId());
        assertEquals(11L, runRow.getRevisionId());
        assertEquals("trigger:" + trigger.getId(), runRow.getTriggeredBy());
    }

    @Test
    @DisplayName("Dispatching a workflow with no published revision skips fire and records nothing.")
    void missingRevisionSkipsRun() {
        stubGraphLoader.reset();
        stubGraphLoader.bindMissing(8001L);

        TriggerEntity trigger = triggerService.create(cronTrigger(
                "ghost", "0 0 * * * *", 8001L, null));

        vip.mate.trigger.dispatch.DispatchResult result = dispatcher.dispatch(trigger, Map.of());
        assertNotNull(result);
        assertEquals(vip.mate.trigger.dispatch.DispatchResult.Kind.SKIPPED, result.kind(),
                "missing revision should yield a SKIPPED outcome, not silent null");

        List<WorkflowRunEntity> runRows = runMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunEntity>().eq(WorkflowRunEntity::getWorkflowId, 8001L));
        assertTrue(runRows.isEmpty(), "no workflow run row should be inserted");
    }

    @Test
    @DisplayName("A fire whose captured pattern_version trails the live row self-cancels.")
    void staleCapturedVersionSelfCancels() {
        stubInvoker.reset();
        stubInvoker.respond("greeter", "ok");
        stubGraphLoader.reset();
        stubGraphLoader.bind(9000L, 21L,
                "{\"steps\":[{\"name\":\"a\",\"agentName\":\"greeter\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"hi\"}]}");

        TriggerEntity trigger = triggerService.create(cronTrigger(
                "lamport", "0 0 * * * *", 9000L, null));
        long triggerId = trigger.getId();
        assertTrue(scheduler.isRegistered(triggerId));

        // Bump the row's pattern_version directly so the in-flight scheduled
        // task's captured value is now stale.
        TriggerEntity row = triggerMapper.selectById(triggerId);
        row.setPatternVersion(row.getPatternVersion() + 5);
        triggerMapper.updateById(row);

        // Capture the original version 1; live is now 6 → fire should drop.
        scheduler.fireForTest(triggerId, 1L);

        // No new run row created.
        List<WorkflowRunEntity> runRows = runMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunEntity>().eq(WorkflowRunEntity::getWorkflowId, 9000L));
        assertTrue(runRows.isEmpty(), "stale lamport must drop the fire silently");
        // And the registration should be cleared so a peer with the latest version
        // can take over.
        assertTrue(!scheduler.isRegistered(triggerId), "scheduler should self-cancel stale registration");
    }

    private static TriggerEntity cronTrigger(String name, String cron, long workflowId, String payloadTpl) {
        TriggerEntity t = new TriggerEntity();
        t.setWorkspaceId(99L);
        t.setName(name);
        t.setPatternType("cron");
        t.setPatternJson("{\"cron\":\"" + cron + "\"}");
        t.setTargetType("workflow");
        t.setTargetId(workflowId);
        t.setPayloadTemplate(payloadTpl);
        t.setEnabled(true);
        return t;
    }

    @TestConfiguration
    static class StubGraphLoaderConfig {
        @Bean
        @Primary
        StubGraphLoader stubGraphLoader(WorkflowParser parser) {
            return new StubGraphLoader(parser);
        }
    }

    static class StubGraphLoader implements WorkflowGraphLoader {
        private final WorkflowParser parser;
        private final java.util.Map<Long, Loaded> graphs = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Set<Long> missing = java.util.concurrent.ConcurrentHashMap.newKeySet();

        StubGraphLoader(WorkflowParser parser) { this.parser = parser; }

        void reset() { graphs.clear(); missing.clear(); }

        void bind(long workflowId, long revisionId, String json) {
            WorkflowGraph g = parser.parse(json);
            graphs.put(workflowId, new Loaded(g, revisionId));
        }

        void bindMissing(long workflowId) { missing.add(workflowId); }

        @Override
        public Loaded load(long workflowId) {
            if (missing.contains(workflowId)) return Loaded.missing();
            return graphs.getOrDefault(workflowId, Loaded.missing());
        }
    }
}
