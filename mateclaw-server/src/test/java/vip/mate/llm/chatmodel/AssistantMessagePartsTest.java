package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AssistantMessagePartsTest {
    @Test void toolRenamePreservesSignedPayloadAndPositionWithoutDuplicatingParts() {
        var reasoning = new ReasoningPart("reason", null, new OpaquePayload("anthropic", "thinking", "signature"), Map.of());
        var tool = new ToolCallPart(new AssistantMessage.ToolCall("id", "function", "echo", "{}"),
                new OpaquePayload("openai", "extra", "signature"), Map.of("custom", "value"));
        var original = AssistantMessage.builder().parts(List.of(reasoning, tool, TextPart.of("tail"))).build();
        var renamed = AssistantMessageParts.mapToolCalls(original, call ->
                new AssistantMessage.ToolCall(call.id(), call.type(), "prefix_" + call.name(), call.arguments()));
        assertEquals(3, renamed.getParts().size());
        assertEquals(reasoning, renamed.getParts().getFirst());
        var changedTool = (ToolCallPart) renamed.getParts().get(1);
        assertEquals("prefix_echo", changedTool.toolCall().name());
        assertEquals(tool.payload(), changedTool.payload());
        assertEquals(tool.attributes(), changedTool.attributes());
        assertEquals("echo", original.getToolCalls().getFirst().name());
    }
    @Test void signedPriorThinkingIsRetainedButExplicitOffRemovesIt() {
        var signed = new ReasoningPart("reason", null, new OpaquePayload("anthropic", "thinking", "signature"), Map.of());
        var original = AssistantMessage.builder().parts(List.of(signed, TextPart.of("answer"))).build();
        assertEquals(original.getParts(), AssistantMessageParts.withoutReasoning(original, true, Map.of()).getParts());
        assertTrue(AssistantMessageParts.withoutReasoning(original, false, Map.of()).getReasoning().isEmpty());
    }
    @Test void nativeReasoningWinsOverLegacyCumulativeValue() {
        var message = AssistantMessage.builder().reasoning(ReasoningPart.of("delta"))
                .properties(Map.of("reasoningContent", "cumulative delta")).build();
        assertEquals("delta", AssistantMessageParts.reasoningText(message));
    }
}
