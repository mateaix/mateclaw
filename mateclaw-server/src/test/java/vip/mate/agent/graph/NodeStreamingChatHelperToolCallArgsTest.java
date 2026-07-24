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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for tool-call arguments sanitization.
 *
 * <p>Some OpenAI-compatible providers (aliyun-codingplan, others using the
 * "coding" DashScope endpoint) reject the follow-up chat-completions request
 * with HTTP 400 when the assistant message in history carries a tool call
 * whose {@code function.arguments} is not parseable JSON. The streaming
 * accumulator can produce empty or truncated argument strings, so the helper
 * normalizes the final value to {@code "{}"} when it is missing or invalid.
 */
class NodeStreamingChatHelperToolCallArgsTest {

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
    @DisplayName("Empty tool-call arguments normalized to '{}'")
    void emptyArguments_replacedWithEmptyJsonObject() {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "id-empty", "function", "list_skills", "");
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc))
                .build();

        var helper = new NodeStreamingChatHelper(streamTracker);
        var result = helper.streamCall(singleChunkModel(msg), smallPrompt(),
                "conv-empty-args", "reasoning");

        assertTrue(result.hasToolCalls(), "tool call must survive");
        assertEquals(1, result.toolCalls().size());
        assertEquals("{}", result.toolCalls().get(0).arguments(),
                "empty arguments must be replaced with '{}' so strict providers "
                        + "(aliyun-codingplan, ...) accept the follow-up request");
    }

    @Test
    @DisplayName("Truncated/invalid JSON arguments pass through raw at aggregation")
    void truncatedJsonArguments_passedThroughAtAggregation() {
        // Simulates a stream cut mid-token: model emitted '{"q":"hel' and stopped.
        // At the aggregation point the fragment is passed through verbatim (not
        // replaced with '{}') so the tool executor's JSON pre-validation can detect
        // the truncation and return the model a "shorten and retry" guidance.
        String truncated = "{\"q\":\"hel";
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "id-truncated", "function", "search", truncated);
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc))
                .build();

        var helper = new NodeStreamingChatHelper(streamTracker);
        var result = helper.streamCall(singleChunkModel(msg), smallPrompt(),
                "conv-truncated-args", "reasoning");

        assertTrue(result.hasToolCalls(), "tool call must survive");
        assertEquals(1, result.toolCalls().size());
        assertEquals(truncated, result.toolCalls().get(0).arguments(),
                "a truncated fragment must reach the executor raw so its truncation "
                        + "detection fires; the outbound chokepoint still normalizes it "
                        + "to '{}' before the next provider request");
    }

    @Test
    @DisplayName("Valid JSON arguments preserved verbatim")
    void validJsonArguments_preservedAsIs() {
        String validArgs = "{\"query\":\"foo\",\"limit\":5}";
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "id-valid", "function", "search", validArgs);
        AssistantMessage msg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc))
                .build();

        var helper = new NodeStreamingChatHelper(streamTracker);
        var result = helper.streamCall(singleChunkModel(msg), smallPrompt(),
                "conv-valid-args", "reasoning");

        assertTrue(result.hasToolCalls());
        assertEquals(1, result.toolCalls().size());
        assertEquals(validArgs, result.toolCalls().get(0).arguments(),
                "valid JSON arguments must not be rewritten");
    }

    @Test
    @DisplayName("Prompt-history tool call with empty arguments normalized before send")
    void promptHistory_emptyArguments_normalized() {
        // Mirrors a tool call replayed from persisted history (e.g. an earlier
        // MCP call with no arguments): it never passes through the streaming
        // aggregator, so the prompt-level pass must repair it.
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "id-hist", "function", "mcp_excel_import", "");
        AssistantMessage historyMsg = AssistantMessage.builder()
                .content("calling tool")
                .toolCalls(List.of(tc))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("hi"), historyMsg));

        Prompt normalized = NodeStreamingChatHelper.normalizeToolCallArguments(prompt);

        AssistantMessage out = (AssistantMessage) normalized.getInstructions().get(1);
        assertEquals(1, out.getToolCalls().size());
        assertEquals("{}", out.getToolCalls().get(0).arguments(),
                "replayed empty arguments must be normalized to '{}'");
        assertEquals("calling tool", out.getText(), "assistant content must be preserved");
        assertEquals("mcp_excel_import", out.getToolCalls().get(0).name(),
                "tool name must be preserved");
        assertEquals("id-hist", out.getToolCalls().get(0).id(),
                "tool call id must be preserved so the tool_call pairing holds");
    }

    @Test
    @DisplayName("Prompt-history tool call with truncated arguments normalized before send")
    void promptHistory_truncatedArguments_normalized() {
        // A truncated fragment that survived aggregation (passed through raw so the
        // executor could detect it) is persisted into history. On the next send the
        // outbound chokepoint must still normalize it to '{}' so strict providers
        // (aliyun-codingplan, ...) accept the follow-up request.
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "id-hist-trunc", "function", "search", "{\"q\":\"hel");
        AssistantMessage historyMsg = AssistantMessage.builder()
                .content("calling tool")
                .toolCalls(List.of(tc))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("hi"), historyMsg));

        Prompt normalized = NodeStreamingChatHelper.normalizeToolCallArguments(prompt);

        AssistantMessage out = (AssistantMessage) normalized.getInstructions().get(1);
        assertEquals(1, out.getToolCalls().size());
        assertEquals("{}", out.getToolCalls().get(0).arguments(),
                "replayed truncated arguments must be normalized to '{}' at the chokepoint");
        assertEquals("id-hist-trunc", out.getToolCalls().get(0).id(),
                "tool call id must be preserved so the tool_call pairing holds");
    }

    @Test
    @DisplayName("Prompt with only valid tool-call arguments returned unchanged")
    void promptHistory_validArguments_returnsSameInstance() {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "id-ok", "function", "search", "{\"q\":\"x\"}");
        AssistantMessage historyMsg = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("hi"), historyMsg));

        Prompt normalized = NodeStreamingChatHelper.normalizeToolCallArguments(prompt);

        assertTrue(normalized == prompt,
                "a prompt that needs no fix must be returned unchanged (no copy)");
    }
}
