package vip.mate.llm.chatmodel;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** Native message parts are authoritative; metadata supports older histories and plugins. */
public final class AssistantMessageParts {
    private AssistantMessageParts() {}

    public static String reasoningText(AssistantMessage message) {
        if (!message.getReasoning().isEmpty()) {
            return message.getReasoning().stream().map(ReasoningPart::text)
                    .filter(text -> text != null && !text.isEmpty()).collect(Collectors.joining());
        }
        Object legacy = message.getMetadata().get("reasoningContent");
        return legacy instanceof String text ? text : null;
    }

    /** Replace tool values in place, retaining each part's signature, attributes and position. */
    public static AssistantMessage mapToolCalls(AssistantMessage message,
            UnaryOperator<AssistantMessage.ToolCall> mapper) {
        return AssistantMessage.builder().properties(message.getMetadata()).parts(message.getParts().stream().map(part -> {
            if (part instanceof ToolCallPart tool) {
                return new ToolCallPart(mapper.apply(tool.toolCall()), tool.payload(), tool.attributes());
            }
            return part;
        }).toList()).build();
    }

    public static AssistantMessage withoutReasoning(AssistantMessage message, boolean keepSigned,
            Map<String, Object> metadata) {
        return AssistantMessage.builder().properties(message.getMetadata()).parts(message.getParts().stream()
                .filter(part -> !(part instanceof ReasoningPart reasoning)
                        || keepSigned && reasoning.payload() != null).toList())
                .properties(metadata).build();
    }
}
