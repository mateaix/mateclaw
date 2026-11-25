package vip.mate.agent.graph;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import vip.mate.llm.chatmodel.AssistantMessageParts;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import reactor.core.publisher.Sinks;

/** Uses the framework's indexed-part aggregation for normal, stopped and interrupted calls. */
final class StreamingMessageParts {
    private final Sinks.Many<ChatResponse> chunks = Sinks.many().unicast().onBackpressureBuffer();
    private AssistantMessage message;
    private RuntimeException failure;
    private boolean indexedText;
    private boolean indexedReasoning;
    private boolean textTransformed;

    StreamingMessageParts() {
        new MessageAggregator().aggregate(chunks.asFlux(), response -> message = response.getResult().getOutput())
                .subscribe(ignored -> {}, error -> failure = new IllegalStateException("Message part aggregation failed", error));
    }

    synchronized void accept(ChatResponse response) {
        if (response.getResult() != null) {
            for (var part : response.getResult().getOutput().getParts()) {
                if (StreamingParts.partIndex(part) != null) {
                    indexedText |= part instanceof TextPart;
                    indexedReasoning |= part instanceof ReasoningPart;
                }
            }
        }
        chunks.tryEmitNext(response);
    }

    synchronized void markTextTransformed() {
        textTransformed = true;
    }

    synchronized AssistantMessage finish(StringBuilder text, StringBuilder thinking) {
        AssistantMessage result = finish();
        // A complete indexed block replaces earlier partial blocks. Delta buffers are
        // only authoritative when application-level inline-think filtering changed them.
        if (result != null && !textTransformed) {
            if (indexedText) {
                text.setLength(0);
                if (result.getText() != null) text.append(result.getText());
            }
            if (indexedReasoning) {
                thinking.setLength(0);
                String reasoning = AssistantMessageParts.reasoningText(result);
                if (reasoning != null) thinking.append(reasoning);
            }
        }
        return result;
    }

    synchronized AssistantMessage finish() {
        chunks.tryEmitComplete();
        if (failure != null) throw failure;
        return message;
    }
}
