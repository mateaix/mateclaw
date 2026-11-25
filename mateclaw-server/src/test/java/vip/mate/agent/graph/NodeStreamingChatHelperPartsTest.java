package vip.mate.agent.graph;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.messages.part.*;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import vip.mate.channel.web.ChatStreamTracker;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class NodeStreamingChatHelperPartsTest {
    private ChatResponse chunk(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().id("response-parts").build());
    }
    private NodeStreamingChatHelper.StreamResult call(Flux<ChatResponse> frames) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("compatible").build());
        when(model.stream(any(Prompt.class))).thenReturn(frames);
        return new NodeStreamingChatHelper(mock(ChatStreamTracker.class), (ChatModel) null)
                .streamCall(model, new Prompt("hello"), "parts", "reasoning");
    }
    @Test void completeIndexedTextReplacesPartialTextInFinalResultAndReplay() {
        var partial = AssistantMessage.builder().part(StreamingParts.partial(TextPart.of("Hel"), 0)).build();
        var complete = AssistantMessage.builder().part(StreamingParts.complete(TextPart.of("Hello"), 0)).build();
        var result = call(Flux.just(chunk(partial), chunk(complete)));
        assertEquals("Hello", result.text());
        assertEquals("Hello", result.assistantMessage().getText());
    }
    @Test void completeIndexedReasoningReplacesPartialReasoningInFinalResult() {
        var partial = AssistantMessage.builder().part(StreamingParts.partial(ReasoningPart.of("Rea"), 0)).build();
        var complete = AssistantMessage.builder().part(StreamingParts.complete(ReasoningPart.of("Reason"), 0)).build();
        var result = call(Flux.just(chunk(partial), chunk(complete), chunk(new AssistantMessage("Answer"))));
        assertEquals("Reason", result.thinking());
        assertEquals("Reason", result.assistantMessage().getMetadata().get("reasoningContent"));
    }
    @Test void indexedAggregationDoesNotUndoInlineThinkingFiltering() {
        var message = AssistantMessage.builder().part(StreamingParts.complete(
                TextPart.of("<think>Reason</think>Answer"), 0)).build();
        var result = call(Flux.just(chunk(message)));
        assertEquals("Answer", result.text());
        assertEquals("Reason", result.thinking());
        assertEquals("Answer", result.assistantMessage().getText());
    }
    @Test void explicitTruncatedCategorySurvivesStreamingAssembly() {
        var metadata = org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                .finishReason("provider_limit").finishReasonCategory(
                        org.springframework.ai.chat.metadata.FinishReasonCategory.TRUNCATED).build();
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage("Partial"), metadata)));
        assertTrue(call(Flux.just(response)).isOutputTokenLimit());
    }
    @Test void nativeReasoningIsDeltaEvenWhenMetadataIsCumulative() {
        var first = AssistantMessage.builder().part(StreamingParts.partial(ReasoningPart.of("same"), 0))
                .properties(Map.of("reasoningContent", "same")).build();
        var second = AssistantMessage.builder().part(StreamingParts.partial(ReasoningPart.of("same"), 0))
                .properties(Map.of("reasoningContent", "samesame")).build();
        var result = call(Flux.just(chunk(first), chunk(second), chunk(new AssistantMessage("Answer"))));
        assertEquals("samesame", result.thinking());
        assertEquals("samesame", result.assistantMessage().getReasoning().getFirst().text());
    }
    @Test void preservesOrderedSignedPartsAndCompleteToolPayloads() {
        var reasoning = new ReasoningPart("Reason", null, new OpaquePayload("anthropic", "thinking", "signed"), Map.of());
        var tool = new ToolCallPart(new AssistantMessage.ToolCall("call-1", "function", "echo", "{}"),
                new OpaquePayload("openai", "additional", "opaque-tool-signature"), Map.of());
        var unknown = new UnknownPart("anthropic", "server_tool", "{}", null, Map.of());
        var message = AssistantMessage.builder().parts(List.of(StreamingParts.complete(reasoning, 0),
                StreamingParts.complete(unknown, 1), StreamingParts.complete(tool, 2))).build();
        var result = call(Flux.just(chunk(message)));
        assertEquals(List.of(reasoning, unknown, tool), result.assistantMessage().getParts());
    }
    @Test void errorAfterSignedReasoningRetainsPayloadInPartialResult() {
        var reasoning = new ReasoningPart("Reason", null, new OpaquePayload("anthropic", "thinking", "signed"), Map.of());
        var message = AssistantMessage.builder().parts(List.of(reasoning, TextPart.of("Answer"))).build();
        var result = call(Flux.just(chunk(message)).concatWith(Flux.error(new IllegalStateException("interrupted"))));
        assertTrue(result.partial());
        assertEquals(reasoning, result.assistantMessage().getReasoning().getFirst());
    }
    @Test void legacyOpenAiRelayDoesNotReplayForeignSignedReasoningAsPlainText() {
        var signed = new ReasoningPart("private reasoning", null,
                new OpaquePayload("anthropic", "thinking", "signed"), Map.of());
        var message = AssistantMessage.builder().reasoning(signed).build();
        List<String> relayed = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                NodeStreamingChatHelper.class, "extractAssistantThinkings", new Prompt(List.of(message)));
        assertEquals(List.of(""), relayed);
    }
    @Test void stripsOnlyUnsignedReasoningFromEarlierTurns() {
        var old = AssistantMessage.builder().reasoning(ReasoningPart.of("old reasoning")).content("old answer").build();
        var current = AssistantMessage.builder().reasoning(ReasoningPart.of("current reasoning")).content("current answer").build();
        var result = NodeStreamingChatHelper.stripThinkingFromPrompt(new Prompt(List.of(old, new UserMessage("next"), current)));
        assertTrue(((AssistantMessage) result.getInstructions().getFirst()).getReasoning().isEmpty());
        assertEquals(current, result.getInstructions().getLast());
    }
}
