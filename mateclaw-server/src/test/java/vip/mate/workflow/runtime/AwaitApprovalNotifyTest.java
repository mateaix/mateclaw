package vip.mate.workflow.runtime;

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
import vip.mate.workflow.compiler.WorkflowParser;
import vip.mate.workflow.compiler.ir.WorkflowGraph;
import vip.mate.workflow.model.WorkflowRunPauseEntity;
import vip.mate.workflow.repository.WorkflowRunPauseMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies ISSUE #413 P0-B1: an {@code await_approval} step pushes a notice to
 * every channel listed in {@code approverChannels} that carries a target. Before
 * the fix, {@code approverChannels} was write-only metadata — a workflow that
 * declared {@code ["feishu:oc_xxx"]} silently dropped the notice.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_notify_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.workflow.trigger.async-dispatch=false"
})
@Import({StubAgentInvokerConfig.class,
        AwaitApprovalNotifyTest.StubChannelDispatcherConfig.class})
class AwaitApprovalNotifyTest {

    @Autowired private WorkflowRunner runner;
    @Autowired private WorkflowParser parser;
    @Autowired private WorkflowRunPauseMapper pauseMapper;
    @Autowired private StubChannelDispatcher stubDispatcher;

    @Test
    @DisplayName("approverChannels with target dispatches a notice; bare 'web' does not.")
    void dispatchesNoticeToTargetedChannels() {
        stubDispatcher.reset();

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"approve",
                     "mode":{"type":"await_approval","approvalKind":"manager",
                             "approverChannels":["feishu:oc_manager_group","web","email:ops@acme.com"],
                             "approvalMessage":"请经理审批新客户入驻"}}
                  ]
                }
                """);

        WorkflowRunResult result = runner.run(graph,
                new WorkflowRunRequest(70L, 1L, 99L, "manual", Map.of()));
        assertEquals("paused", result.state());

        // feishu + email both carry a target → two dispatches; "web" has no
        // target → skipped (operator uses the admin console).
        List<StubChannelDispatcher.Sent> sent = stubDispatcher.sentList();
        assertEquals(2, sent.size(), "only channels with an explicit target should be notified");
        assertTrue(sent.stream().anyMatch(s -> "feishu".equals(s.channel())
                && "oc_manager_group".equals(s.target())));
        assertTrue(sent.stream().anyMatch(s -> "email".equals(s.channel())
                && "ops@acme.com".equals(s.target())));

        // The notice body carries the approval message + runId so the approver
        // can correlate it with the inbox entry.
        assertTrue(sent.get(0).content().contains("请经理审批新客户入驻"));
        assertTrue(sent.get(0).content().contains("runId"),
                "notice should mention runId: " + sent.get(0).content());

        // The pause row still exists — the notice is non-fatal best-effort.
        WorkflowRunPauseEntity pause = pauseMapper.selectOne(
                new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                        .eq(WorkflowRunPauseEntity::getRunId, result.runId()));
        assertNotNull(pause);
    }

    @Test
    @DisplayName("A channel dispatch failure does not fail the await_approval step.")
    void channelFailureIsNonFatal() {
        stubDispatcher.reset();
        stubDispatcher.makeFail("feishu", "rate limited");

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"approve",
                     "mode":{"type":"await_approval","approvalKind":"k",
                             "approverChannels":["feishu:oc_group"]}}
                  ]
                }
                """);

        WorkflowRunResult result = runner.run(graph,
                new WorkflowRunRequest(71L, 1L, 99L, "manual", Map.of()));
        // The step still pauses successfully — a delivery hiccup must not abort
        // the run (pause row + REST resume are the canonical recovery path).
        assertEquals("paused", result.state());
    }

    @TestConfiguration
    static class StubChannelDispatcherConfig {
        @Bean
        @Primary
        StubChannelDispatcher stubChannelDispatcher() {
            return new StubChannelDispatcher();
        }
    }

    static class StubChannelDispatcher implements ChannelDispatcher {
        record Sent(String channel, String target, String content) {}

        private final List<Sent> sent = new ArrayList<>();
        private final Map<String, String> failures = new ConcurrentHashMap<>();

        synchronized void reset() {
            sent.clear();
            failures.clear();
        }

        synchronized List<Sent> sentList() {
            return List.copyOf(sent);
        }

        void makeFail(String channelType, String message) {
            failures.put(channelType, message);
        }

        @Override
        public synchronized DispatchResult dispatch(long workspaceId, String channelType,
                                                    String targetId, String content) {
            String forced = failures.get(channelType);
            if (forced != null) {
                return DispatchResult.fail(forced);
            }
            sent.add(new Sent(channelType, targetId, content));
            return DispatchResult.ok();
        }
    }
}
