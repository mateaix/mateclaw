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
import vip.mate.workflow.model.WorkflowRunStepEntity;
import vip.mate.workflow.repository.WorkflowRunStepMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives dispatch_channel steps through the runtime against a deterministic
 * channel stub. Verifies that the rendered template is delivered to every
 * configured channel, and that any per-channel failure marks the step + run
 * failed without dropping the failure detail.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_dispatch_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
@Import({StubAgentInvokerConfig.class,
        DispatchChannelRuntimeTest.StubChannelDispatcherConfig.class})
class DispatchChannelRuntimeTest {

    @Autowired private WorkflowRunner runner;
    @Autowired private WorkflowParser parser;
    @Autowired private WorkflowRunStepMapper stepMapper;
    @Autowired private StubChannelDispatcher stubDispatcher;
    @Autowired private PayloadStore payloadStore;
    @Autowired private StubAgentInvoker stubInvoker;

    @Test
    @DisplayName("Renders content and delivers to every configured channel.")
    void deliversToAllChannels() {
        stubInvoker.reset();
        stubInvoker.respond("collect", "{\"customer\":\"Acme\"}");
        stubDispatcher.reset();

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"collect","agentName":"collect","mode":{"type":"sequential"},
                     "promptTemplate":"x","outputVar":"d","outputContentType":"json"},
                    {"name":"notify",
                     "mode":{"type":"dispatch_channel","channels":["feishu","email"],
                             "targets":{"feishu":"chat-007","email":"ops@acme.com"},
                             "content":"Onboarded {{ outputs.d.customer }}"}}
                  ]
                }
                """);

        WorkflowRunResult result = runner.run(graph,
                new WorkflowRunRequest(60L, 1L, 99L, "manual", Map.of()));
        assertEquals("succeeded", result.state());

        // Both channels saw the rendered content + their respective targets.
        List<StubChannelDispatcher.Sent> sent = stubDispatcher.sentList();
        assertEquals(2, sent.size());
        assertTrue(sent.stream().anyMatch(s -> "feishu".equals(s.channel())
                && "chat-007".equals(s.target())
                && "Onboarded Acme".equals(s.content())));
        assertTrue(sent.stream().anyMatch(s -> "email".equals(s.channel())
                && "ops@acme.com".equals(s.target())));

        // The step row's output_ref points at the rendered payload.
        WorkflowRunStepEntity dispatchRow = stepMapper.selectOne(new LambdaQueryWrapper<WorkflowRunStepEntity>()
                .eq(WorkflowRunStepEntity::getRunId, result.runId())
                .eq(WorkflowRunStepEntity::getStepName, "notify"));
        assertNotNull(dispatchRow.getOutputRef());
        assertEquals("Onboarded Acme", payloadStore.readString(dispatchRow.getOutputRef()));
    }

    @Test
    @DisplayName("A single channel failure fails the dispatch step and aborts the run.")
    void singleChannelFailureFailsRun() {
        stubInvoker.reset();
        stubDispatcher.reset();
        stubDispatcher.makeFail("feishu", "rate limited");

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"notify",
                     "mode":{"type":"dispatch_channel","channels":["feishu","email"],
                             "targets":{"feishu":"c","email":"e"},
                             "content":"hello"}}
                  ]
                }
                """);

        WorkflowRunResult result = runner.run(graph,
                new WorkflowRunRequest(61L, 1L, 99L, "manual", Map.of()));
        assertEquals("failed", result.state());
        assertTrue(result.errorMessage().contains("feishu"),
                "error message should mention failing channel: " + result.errorMessage());
        assertTrue(result.errorMessage().contains("rate limited"));

        // Email succeeded; feishu failed. Atomicity is not promised — the step
        // is still marked failed so the run aborts before downstream steps.
        assertEquals(1, stubDispatcher.sentList().size());
    }

    @Test
    @DisplayName("Missing target for a channel fails dispatch with a useful message.")
    void missingTargetIsReportedAsFailure() {
        stubInvoker.reset();
        stubDispatcher.reset();

        WorkflowGraph graph = parser.parse("""
                {
                  "steps": [
                    {"name":"notify",
                     "mode":{"type":"dispatch_channel","channels":["feishu"],
                             "targets":{},
                             "content":"hello"}}
                  ]
                }
                """);

        WorkflowRunResult result = runner.run(graph,
                new WorkflowRunRequest(62L, 1L, 99L, "manual", Map.of()));
        assertEquals("failed", result.state());
        assertTrue(result.errorMessage().contains("missing targetId"),
                "error message should flag the missing target: " + result.errorMessage());
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
            if (targetId == null || targetId.isBlank()) {
                return DispatchResult.fail("missing targetId for channel '" + channelType + "'");
            }
            sent.add(new Sent(channelType, targetId, content));
            return DispatchResult.ok();
        }
    }
}
