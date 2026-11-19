package vip.mate.agent.graph.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.*;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.runtime.state.RunState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

class ReasoningNodeLocalOverflowTest {
    private static final String GOAL = "Read the data and report the result.";
    private final ChatModel model = mock(ChatModel.class);
    private final NodeStreamingChatHelper helper = mock(NodeStreamingChatHelper.class);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void smallOverflowAdjustsActualOutputLimitAndPreservesLatestToolPair(boolean completionTokens) throws Exception {
        when(model.getDefaultOptions()).thenReturn(completionTokens
                ? OpenAiChatOptions.builder().maxCompletionTokens(32768).build()
                : OpenAiChatOptions.builder().maxTokens(32768).build());
        var history = history(78500);
        var node = node(null);
        var output = node.apply(state(history));

        assertEquals("Final answer", output.get(FINAL_ANSWER));
        assertEquals(1, output.get(LLM_CALL_COUNT));
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(helper).streamCall(eq(model), prompt.capture(), eq("local-overflow"), eq("reasoning"));
        var request = prompt.getValue();
        assertTrue(PromptBudget.estimateTokens(request, null, 0) <= 128000);
        assertTrue(PromptBudget.outputReserveTokens(request.getOptions(), null, 0) < 32768);
        assertTrue(request.getInstructions().containsAll(history));
        if (completionTokens) {
            var options = (OpenAiChatOptions) request.getOptions();
            assertNull(options.getMaxTokens());
            assertNotNull(options.getMaxCompletionTokens());
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {2000, 100000})
    void localOverflowUsesBudgetedCompactionBeforeAnyBusinessRequest(int compactedSize) throws Exception {
        var window = window();
        var oversized = history(124000);
        var compacted = history(compactedSize);
        when(window.compactForRetry(anyList(), any(), anyString(), any())).thenAnswer(call -> {
            ChatModel summaryModel = call.getArgument(1);
            summaryModel.call(new Prompt("Summarize history"));
            return compacted;
        });
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("summary"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(19, 11)).build()));
        var output = node(window).apply(state(oversized));

        assertEquals("Final answer", output.get(FINAL_ANSWER));
        assertEquals(2, output.get(LLM_CALL_COUNT));
        assertEquals(19, output.get(PROMPT_TOKENS));
        assertEquals(11, output.get(COMPLETION_TOKENS));
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(helper).streamCall(eq(model), prompt.capture(), eq("local-overflow"), eq("reasoning_compact_retry"));
        assertTrue(prompt.getValue().getInstructions().containsAll(compacted));
        assertTrue(PromptBudget.estimateTokens(prompt.getValue(), null, 0) <= 128000);
        verify(model).call(any(Prompt.class));
    }

    @Test
    void ineffectiveLocalCompactionStopsWithoutSendingOversizedRequest() throws Exception {
        var window = window();
        when(window.compactForRetry(anyList(), any(), anyString(), any()))
                .thenAnswer(call -> call.getArgument(0));
        var output = node(window).apply(state(history(124000)));
        assertEquals("error_fallback", output.get(FINISH_REASON));
        assertEquals(0, output.get(LLM_CALL_COUNT));
        verify(window, times(1)).compactForRetry(anyList(), any(), anyString(), any());
        verifyNoInteractions(helper);
    }

    @Test
    void outputAdjustmentPreservesAnthropicThinkingBudget() throws Exception {
        when(model.getDefaultOptions()).thenReturn(AnthropicChatOptions.builder().model("claude-test").build());
        vip.mate.llm.chatmodel.ThinkingLevelHolder.set("high");
        try {
            var output = node(null).apply(state(history(78500)));
            assertEquals("Final answer", output.get(FINAL_ANSWER));
            var prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(helper).streamCall(eq(model), prompt.capture(), eq("local-overflow"), eq("reasoning"));
            var options = (AnthropicChatOptions) prompt.getValue().getOptions();
            assertEquals(16384, options.getThinking().asEnabled().budgetTokens());
            assertTrue(options.getMaxTokens() >= 16384 + 512);
            assertTrue(options.getMaxTokens() < 16384 + 32768);
            assertTrue(PromptBudget.estimateTokens(prompt.getValue(), null, 0) <= 128000);
        } finally {
            vip.mate.llm.chatmodel.ThinkingLevelHolder.clear();
        }
    }

    @Test
    void impossibleThinkingBudgetDoesNotProduceInvalidProviderOptions() throws Exception {
        when(model.getDefaultOptions()).thenReturn(AnthropicChatOptions.builder().model("claude-test").build());
        vip.mate.llm.chatmodel.ThinkingLevelHolder.set("high");
        try {
            node(null).apply(state(history(108000)));
            verifyNoInteractions(helper);
        } finally {
            vip.mate.llm.chatmodel.ThinkingLevelHolder.clear();
        }
    }

    @Test
    void localCompactionCancellationStopsWithoutBusinessRequest() throws Exception {
        var window = window();
        when(window.compactForRetry(anyList(), any(), anyString(), any()))
                .thenThrow(new java.util.concurrent.CancellationException("stopped"));
        var output = node(window).apply(state(history(124000)));
        assertEquals("stopped", output.get(FINISH_REASON));
        assertEquals(0, output.get(LLM_CALL_COUNT));
        verifyNoInteractions(helper);
    }

    @Test
    void localCompactionReservesBusinessCallWithinLogicalLimit() throws Exception {
        var window = window();
        var state = state(history(124000));
        state.replace(MAX_ITERATIONS, 1);
        state.replace(LLM_CALL_COUNT, 4);
        var output = node(window).apply(state);
        assertEquals("incomplete", output.get(FINISH_REASON));
        assertEquals(4, output.get(LLM_CALL_COUNT));
        verify(window, never()).compactForRetry(anyList(), any(), anyString(), any());
        verifyNoInteractions(helper);
    }

    private ReasoningNode node(ConversationWindowManager window) {
        when(helper.streamCall(any(), any(), anyString(), anyString())).thenReturn(
                new NodeStreamingChatHelper.StreamResult("Final answer", "", new AssistantMessage("Final answer"),
                        List.of(), false, 0, 0));
        var node = new ReasoningNode(model, AgentToolSet.fromCallbacks(List.of(), List.of()), null,
                false, helper, window, null, 32768, null, null, null, null);
        node.setPrefixBudgetPlan(new PrefixBudgetPlan(true, 128000, PrefixBudgetPlan.Profile.NORMAL,
                0, 0, 0, 0, 0, 0, 0));
        return node;
    }

    private static List<Message> history(int chars) {
        return List.of(new UserMessage(GOAL),
                AssistantMessage.builder().toolCalls(List.of(
                        new AssistantMessage.ToolCall("read-1", "function", "read_file", "{}"))).build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("read-1", "read_file", "界".repeat(chars)))).build());
    }

    private static RunState state(List<Message> messages) {
        return new RunState(Map.of(CONVERSATION_ID, "local-overflow", SYSTEM_PROMPT, "界".repeat(17000),
                USER_MESSAGE, GOAL, MESSAGES, messages, MAX_ITERATIONS, 10, LLM_CALL_COUNT, 0));
    }

    private static ConversationWindowManager window() {
        var window = mock(ConversationWindowManager.class);
        when(window.compactAgedToolResponses(anyList(), anyInt())).thenAnswer(call -> call.getArgument(0));
        when(window.pruneOldToolResultsForModelInput(anyList(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(0));
        return window;
    }
}
