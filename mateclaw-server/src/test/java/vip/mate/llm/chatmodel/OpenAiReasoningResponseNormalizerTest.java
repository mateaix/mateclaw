package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiReasoningResponseNormalizerTest {
    @Test
    void eventMappingSubscribesOnceAndPropagatesCancellation() {
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        Flux<DataBuffer> body = Flux.<DataBuffer>defer(() -> {
            subscriptions.incrementAndGet();
            String event = "data: {\"choices\":[{\"delta\":{\"reasoning\":\"thinking\"}}]}\n\n";
            return Flux.concat(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(event.getBytes(StandardCharsets.UTF_8))),
                    Flux.never());
        }).doOnCancel(cancellations::incrementAndGet);
        ClientResponse source = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE).body(body).build();
        var request = ClientRequest.create(HttpMethod.POST, URI.create("http://localhost/v1/chat/completions")).build();
        var filtered = OpenAiReasoningResponseNormalizer.streamingFilter()
                .filter(request, ignored -> Mono.just(source)).block();
        assertEquals(0, subscriptions.get(), "filter must not eagerly drain the HTTP body");
        var events = filtered.bodyToFlux(String.class).take(1).collectList().block(Duration.ofSeconds(2));
        assertEquals(1, events.size());
        assertTrue(events.getFirst().contains("reasoning_content"));
        assertEquals(1, subscriptions.get());
        assertEquals(1, cancellations.get());
    }

    @Test
    void malformedFramesAndDoneRemainUnchanged() {
        assertEquals("[DONE]", OpenAiReasoningResponseNormalizer.normalize("[DONE]"));
        String malformed = "{\"reasoning\": broken";
        assertEquals(malformed, OpenAiReasoningResponseNormalizer.normalize(malformed));
    }
}
