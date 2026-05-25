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
import vip.mate.trigger.dispatch.WorkflowGraphLoader;
import vip.mate.trigger.ingest.BotSelfFilter;
import vip.mate.trigger.ingest.TriggerEventEnvelope;
import vip.mate.trigger.ingest.TriggerEventIngestService;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.repository.TriggerMapper;
import vip.mate.trigger.service.TriggerService;
import vip.mate.workflow.compiler.WorkflowParser;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.runtime.StubAgentInvoker;
import vip.mate.workflow.runtime.StubAgentInvokerConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the four-stage ingest pipeline end-to-end against H2 with stub
 * agent invocation and stub workflow graph loading: the dedup window
 * collapses repeated events, the per-trigger sliding rate limit drops
 * over-cap events, the bot-self filter shields against echo loops, and
 * a clean event produces exactly one workflow run.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:trigger_ingest_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.workflow.trigger.async-dispatch=false"
})
@Import({StubAgentInvokerConfig.class,
        TriggerDispatcherWorkflowTest.StubGraphLoaderConfig.class,
        TriggerEventIngestServiceTest.SwitchableBotFilterConfig.class})
class TriggerEventIngestServiceTest {

    @Autowired private TriggerService triggerService;
    @Autowired private TriggerMapper triggerMapper;
    @Autowired private TriggerEventIngestService ingest;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private TriggerDispatcherWorkflowTest.StubGraphLoader stubGraphLoader;
    @Autowired private StubAgentInvoker stubInvoker;
    @Autowired private SwitchableBotFilter botFilter;

    // Each test uses its own (workspaceId, patternType=webhook) pair so the
    // ingest's selectList only returns the trigger this test owns. webhook
    // is the pass-through pattern documented in TriggerPatternMatcher; we
    // can't reuse synthetic types like "evt.clean" anymore because the
    // matcher correctly fails closed on unknown pattern types now.

    @Test
    @DisplayName("A clean event for one matching trigger produces one workflow run.")
    void cleanEventFiresOnce() {
        long ws = 91000L;
        TriggerEntity t = createTrigger(ws, "hook", 9100L, "webhook", 60, 60);
        bindGraph(9100L);
        stubInvoker.respond("greeter", "ok");

        List<TriggerEventIngestService.IngestResult> results = ingest.ingest(envelope(
                ws, "evt-1", "u-1", "webhook"));
        assertEquals(1, results.size());
        assertTrue(results.get(0).fired());
        assertEquals(t.getId(), results.get(0).triggerId());

        List<WorkflowRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getWorkflowId, 9100L));
        assertEquals(1, runs.size());
    }

    @Test
    @DisplayName("Dedup window collapses repeated events with the same eventId.")
    void duplicateEventIdIsDropped() {
        long ws = 92000L;
        createTrigger(ws, "dedup", 9200L, "webhook", 60, 60);
        bindGraph(9200L);
        stubInvoker.respond("greeter", "ok");

        var first = ingest.ingest(envelope(ws, "evt-dup", "u", "webhook"));
        var second = ingest.ingest(envelope(ws, "evt-dup", "u", "webhook"));

        assertTrue(first.get(0).fired());
        assertFalse(second.get(0).fired());
        assertEquals(TriggerEventIngestService.Reason.DUPLICATE, second.get(0).droppedReason());

        List<WorkflowRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getWorkflowId, 9200L));
        assertEquals(1, runs.size());
    }

    @Test
    @DisplayName("Sliding rate limit drops events past the per-minute cap.")
    void rateLimitedEventsAreDropped() {
        long ws = 93000L;
        createTrigger(ws, "burst", 9300L, "webhook", /* rate */ 2, 60);
        bindGraph(9300L);
        stubInvoker.respond("greeter", "ok");

        var r1 = ingest.ingest(envelope(ws, "evt-1", "u", "webhook"));
        var r2 = ingest.ingest(envelope(ws, "evt-2", "u", "webhook"));
        var r3 = ingest.ingest(envelope(ws, "evt-3", "u", "webhook"));

        assertTrue(r1.get(0).fired());
        assertTrue(r2.get(0).fired());
        assertFalse(r3.get(0).fired());
        assertEquals(TriggerEventIngestService.Reason.RATE_LIMITED, r3.get(0).droppedReason());
    }

    @Test
    @DisplayName("Bot-self events are dropped before any DB or dispatch work happens.")
    void botSelfFilterDropsEcho() {
        long ws = 94000L;
        createTrigger(ws, "echo", 9400L, "webhook", 60, 60);
        bindGraph(9400L);
        botFilter.flagAsBot("bot-account");

        var results = ingest.ingest(envelope(ws, "evt-1", "bot-account", "webhook"));
        assertEquals(1, results.size());
        assertFalse(results.get(0).fired());
        assertEquals(TriggerEventIngestService.Reason.BOT_SELF, results.get(0).droppedReason());

        List<WorkflowRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getWorkflowId, 9400L));
        assertTrue(runs.isEmpty(), "no run row for bot-self event");
    }

    @Test
    @DisplayName("Triggers exhausted on max_fires drop further events without dispatch.")
    void exhaustedTriggerStopsFiring() {
        long ws = 95000L;
        TriggerEntity t = createTrigger(ws, "oneshot", 9500L, "webhook", 60, 60);
        bindGraph(9500L);
        TriggerEntity row = triggerMapper.selectById(t.getId());
        row.setMaxFires(1L);
        row.setFireCount(1L);
        triggerMapper.updateById(row);

        var results = ingest.ingest(envelope(ws, "evt-late", "u", "webhook"));
        assertEquals(TriggerEventIngestService.Reason.EXHAUSTED, results.get(0).droppedReason());
    }

    private TriggerEntity createTrigger(long workspaceId, String name, long workflowId,
                                        String patternType, int ratePerMin, int dedupWindowSecs) {
        TriggerEntity t = new TriggerEntity();
        t.setWorkspaceId(workspaceId);
        t.setName(name);
        t.setPatternType(patternType);
        t.setPatternJson("{}");
        t.setTargetType("workflow");
        t.setTargetId(workflowId);
        t.setEnabled(true);
        t.setRateLimitPerMin(ratePerMin);
        t.setDedupWindowSecs(dedupWindowSecs);
        t.setBotSelfFilter(true);
        return triggerService.create(t);
    }

    private void bindGraph(long workflowId) {
        stubInvoker.reset();
        stubGraphLoader.reset();
        stubGraphLoader.bind(workflowId, 1L,
                "{\"steps\":[{\"name\":\"a\",\"agentName\":\"greeter\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"go\"}]}");
    }

    private static TriggerEventEnvelope envelope(long workspaceId, String eventId,
                                                 String senderId, String patternType) {
        return new TriggerEventEnvelope(workspaceId, patternType, eventId, senderId,
                Map.of("hello", "world"));
    }

    @TestConfiguration
    static class SwitchableBotFilterConfig {
        @Bean
        @Primary
        SwitchableBotFilter switchableBotFilter() { return new SwitchableBotFilter(); }
    }

    static class SwitchableBotFilter implements BotSelfFilter {
        private final Set<String> bots = new CopyOnWriteArraySet<>();

        void flagAsBot(String senderId) { bots.add(senderId); }

        @Override
        public boolean isBotSelf(long workspaceId, String senderId) {
            return bots.contains(senderId);
        }
    }
}
