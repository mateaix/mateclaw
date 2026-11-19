package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService.StreamDelta;
import vip.mate.agent.BaseAgent;
import vip.mate.agent.graph.StateGraphReActAgent;
import vip.mate.agent.graph.plan.StateGraphPlanExecuteAgent;
import vip.mate.agent.runtime.AgentRuntime;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.RunStep;
import vip.mate.planning.service.PlanningService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

class NativeStreamUsageTest {
    enum Entry { REACT, REACT_REPLAY, PLAN, PLAN_REPLAY }

    static final class ReactAgent extends StateGraphReActAgent {
        ReactAgent(AgentRuntime runtime) { super(null, null, runtime, null, null); agentId = "1"; }
        @Override protected List<Message> buildConversationHistory(String c, String m) { return List.of(); }
        @Override protected CurrentTurnUserMessage buildCurrentUserMessageWithRouting(String c, String m) {
            return new CurrentTurnUserMessage(new UserMessage(m), null);
        }
    }

    private static final class PlanAgent extends StateGraphPlanExecuteAgent {
        PlanAgent(AgentRuntime runtime) {
            super(null, null, runtime, mock(PlanningService.class), null, null);
            agentId = "1";
        }
        @Override protected List<Message> buildConversationHistory(String c, String m) { return List.of(); }
        @Override protected CurrentTurnUserMessage buildCurrentUserMessageWithRouting(String c, String m) {
            return new CurrentTurnUserMessage(new UserMessage(m), null);
        }
    }

    private Flux<StreamDelta> stream(Entry entry, AgentRuntime runtime, String conversation) {
        return switch (entry) {
            case REACT -> new ReactAgent(runtime).chatStructuredStream("hello", conversation);
            case REACT_REPLAY -> new ReactAgent(runtime).chatWithReplayStream("hello", conversation, "{}");
            case PLAN -> new PlanAgent(runtime).chatStructuredStream("hello", conversation);
            case PLAN_REPLAY -> new PlanAgent(runtime).chatWithReplayStream("hello", conversation, "{}");
        };
    }

    static RunStep completedNode(int promptTokens, int completionTokens) {
        return new RunStep("reasoning", new RunState(Map.of(
                PROMPT_TOKENS, promptTokens, COMPLETION_TOKENS, completionTokens,
                CACHE_READ_TOKENS, 7, CACHE_WRITE_TOKENS, 3, REASONING_TOKENS, 5,
                RUNTIME_MODEL_NAME, "model", RUNTIME_PROVIDER_ID, "provider")));
    }

    private AgentStreamAccumulator accumulator() {
        return new AgentStreamAccumulator(new ObjectMapper(), mock(AgentStreamAccumulator.Sink.class));
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void stopKeepsUsageFromCompletedNodes(Entry entry) {
        String conversation = "usage-stop-" + entry;
        ChatStreamTracker tracker = new ChatStreamTracker(new ObjectMapper());
        tracker.register(conversation);
        AgentStreamAccumulator accumulator = accumulator();
        Disposable subscription = stream(entry, inputs -> Flux.just(completedNode(100, 20))
                .concatWith(Flux.never()), conversation)
                .doOnNext(delta -> accumulator.accept(delta, conversation)).subscribe();
        tracker.setDisposable(conversation, subscription);

        assertTrue(tracker.requestStop(conversation));
        assertEquals(100, accumulator.getPromptTokens());
        assertEquals(20, accumulator.getCompletionTokens());
        assertEquals(7, accumulator.getCacheReadTokens());
        assertEquals(3, accumulator.getCacheWriteTokens());
        assertEquals(5, accumulator.getReasoningTokens());
        assertEquals("model", accumulator.getRuntimeModelName());
        assertEquals("provider", accumulator.getRuntimeProviderId());
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void errorKeepsUsageFromCompletedNodes(Entry entry) {
        AgentStreamAccumulator accumulator = accumulator();
        AtomicReference<Throwable> error = new AtomicReference<>();
        stream(entry, inputs -> Flux.just(completedNode(100, 20))
                .concatWith(Flux.error(new IllegalStateException("later node failed"))), "usage-error-" + entry)
                .doOnNext(delta -> accumulator.accept(delta, "usage-error-" + entry))
                .subscribe(delta -> { }, error::set);

        assertNotNull(error.get());
        assertEquals(100, accumulator.getPromptTokens());
        assertEquals(20, accumulator.getCompletionTokens());
    }

    @ParameterizedTest @EnumSource(Entry.class)
    void cumulativeSnapshotsAndFinalUsageAreNotAddedTwice(Entry entry) {
        AgentStreamAccumulator accumulator = accumulator();
        stream(entry, inputs -> Flux.just(completedNode(100, 20), completedNode(150, 30)), "usage-complete-" + entry)
                .doOnNext(delta -> accumulator.accept(delta, "usage-complete-" + entry)).blockLast();

        assertEquals(150, accumulator.getPromptTokens());
        assertEquals(30, accumulator.getCompletionTokens());
        assertEquals(7, accumulator.getCacheReadTokens());
    }
}
