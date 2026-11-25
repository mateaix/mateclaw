package vip.mate.agent.graph;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import reactor.core.publisher.Sinks;

/** Uses the framework's indexed-part aggregation for normal, stopped and interrupted calls. */
final class StreamingMessageParts {
    private final Sinks.Many<ChatResponse> chunks = Sinks.many().unicast().onBackpressureBuffer();
    private AssistantMessage message;
    private RuntimeException failure;

    StreamingMessageParts() {
        new MessageAggregator().aggregate(chunks.asFlux(), response -> message = response.getResult().getOutput())
                .subscribe(ignored -> {}, error -> failure = new IllegalStateException("Message part aggregation failed", error));
    }

    synchronized void accept(ChatResponse response) {
        chunks.tryEmitNext(response);
    }

    synchronized AssistantMessage finish() {
        chunks.tryEmitComplete();
        if (failure != null) throw failure;
        return message;
    }
}
