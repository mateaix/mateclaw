package vip.mate.agent.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the thinking-only soft cap added in P0-2.
 *
 * <p>The cap disposes the upstream stream when the model has emitted
 * {@code >= THINKING_ONLY_HARD_CAP_CHARS} of {@code reasoning_content}
 * with zero visible content and zero tool calls. The risk noted during
 * review (P1-A): some providers (Anthropic / DeepSeek-thinking variants)
 * pack {@code reasoning_content} and a {@code tool_call} into the same
 * SSE chunk. If the cap check sits inside the thinking-delta block (i.e.
 * before the chunk's tool_call is accumulated) it would dispose just
 * before observing the tool — turning a request that was about to dispatch
 * a tool into a spurious "INCOMPLETE: thinking-only" outcome.
 */
class NodeStreamingChatHelperThinkingCapTest {

    private ChatStreamTracker streamTracker;

    @BeforeEach
    void setUp() {
        streamTracker = mock(ChatStreamTracker.class);
        when(streamTracker.isStopRequested(any())).thenReturn(false);
    }

    private static Prompt smallPrompt() {
        return new Prompt(List.of(new UserMessage("hi")));
    }

    private static ChatModel singleChunkModel(AssistantMessage msg) {
        Generation gen = new Generation(msg, ChatGenerationMetadata.NULL);
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResults()).thenReturn(List.of(gen));
        when(resp.getResult()).thenReturn(gen);
        when(resp.getMetadata()).thenReturn(null);
        ChatModel m = mock(ChatModel.class);
        when(m.stream(any(Prompt.class))).thenReturn(Flux.just(resp));
        return m;
    }

    @Test
    @DisplayName("thinking >= cap + tool_call in same chunk: cap must NOT trigger; tool_call survives")
    void thinkingAndToolCallSameChunk_doesNotTripSoftCap() {
        // Build a single chunk that carries 40k thinking (well above the
        // 32k cap) AND a tool call. With the buggy ordering this would
        // dispose before accumulateToolCalls runs and the helper would
        // return a partial "thinking_only_no_content" result.
        String hugeThinking = "x".repeat(40_000);
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "id-1", "function", "search", "{\"q\":\"foo\"}");
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc))
                .properties(Map.of("reasoningContent", hugeThinking))
                .build();

        ChatModel m = singleChunkModel(msg);
        var helper = new NodeStreamingChatHelper(streamTracker);

        var result = helper.streamCall(m, smallPrompt(), "conv-thinking-tc", "reasoning");

        assertTrue(result.hasToolCalls(),
                "Tool call accompanying huge thinking in the same chunk must survive");
        assertEquals(1, result.toolCalls().size());
        assertEquals("search", result.toolCalls().get(0).name());
        assertFalse(result.partial(),
                "Result must not be marked partial when a tool_call was observed in the same chunk");
        assertNotEquals("thinking_only_no_content", result.errorMessage(),
                "Soft cap must not fire when the chunk carrying huge thinking also carried a tool call");
    }

    @Test
    @DisplayName("thinking >= cap with NO tool_call and NO content: cap fires, result is partial+thinking_only_no_content")
    void thinkingOnlyNoContent_capFires() {
        // Symmetric positive case: confirms the cap still triggers in the
        // genuine "深度思考 ... never finishes" scenario the cap was added for.
        String hugeThinking = "y".repeat(40_000);
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", hugeThinking))
                .build();

        ChatModel m = singleChunkModel(msg);
        var helper = new NodeStreamingChatHelper(streamTracker);

        var result = helper.streamCall(m, smallPrompt(), "conv-thinking-only", "reasoning");

        assertFalse(result.hasToolCalls());
        assertTrue(result.partial(), "Cap should mark the result as partial");
        assertEquals("thinking_only_no_content", result.errorMessage());
        assertEquals(hugeThinking, result.thinking(),
                "Thinking transcript is preserved so the UI can show it in a collapse panel");
    }
}
