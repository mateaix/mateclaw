package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Bridges vLLM's newer reasoning field to Spring AI 1.1.x's reasoning_content. */
final class OpenAiReasoningResponseNormalizer {
    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiReasoningResponseNormalizer() {}

    static String normalize(String body) {
        if (!body.contains("\"reasoning\"")) return body;
        try {
            JsonNode root = JSON.readTree(body);
            boolean changed = false;
            for (JsonNode choice : root.path("choices")) {
                changed |= normalizeMessage(choice.path("delta"));
                changed |= normalizeMessage(choice.path("message"));
            }
            return changed ? JSON.writeValueAsString(root) : body;
        } catch (JsonProcessingException ignored) {
            // Keep malformed input intact: the SDK owns protocol error handling.
            return body;
        }
    }

    private static boolean normalizeMessage(JsonNode node) {
        if (node instanceof ObjectNode message && !message.hasNonNull("reasoning_content")
                && message.path("reasoning").isTextual()) {
            message.set("reasoning_content", message.get("reasoning"));
            return true;
        }
        return false;
    }

    static ExchangeFilterFunction streamingFilter() {
        return (request, next) -> next.exchange(request).map(response -> {
            if (!response.statusCode().is2xxSuccessful()
                    || !response.headers().contentType().map(MediaType.TEXT_EVENT_STREAM::isCompatibleWith).orElse(false)) {
                return response;
            }
            // Decode complete SSE data events, not TCP/DataBuffer fragments. This
            // preserves split UTF-8, multiline data, [DONE], backpressure and cancellation.
            var events = response.bodyToFlux(String.class).map(OpenAiReasoningResponseNormalizer::normalize)
                    .map(data -> "data: " + data.replace("\n", "\ndata: ") + "\n\n")
                    .<DataBuffer>map(data -> DefaultDataBufferFactory.sharedInstance.wrap(data.getBytes(StandardCharsets.UTF_8)));
            // The function overload retains the source body. body(Flux) would
            // release it immediately and subscribe to the HTTP body twice.
            return response.mutate().headers(headers -> headers.remove(HttpHeaders.CONTENT_LENGTH)).body(original -> events).build();
        });
    }

    static ClientHttpRequestInterceptor blockingInterceptor() {
        return (request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            if (!response.getStatusCode().is2xxSuccessful()
                    || response.getHeaders().getContentType() == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType())) {
                return response;
            }
            try {
                byte[] bytes = normalize(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8);
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(response.getHeaders());
                headers.setContentLength(bytes.length);
                return new ClientHttpResponse() {
                    private final InputStream input = new ByteArrayInputStream(bytes);
                    @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                    @Override public String getStatusText() throws IOException { return response.getStatusText(); }
                    @Override public HttpHeaders getHeaders() { return headers; }
                    @Override public InputStream getBody() { return input; }
                    @Override public void close() { response.close(); }
                };
            } catch (IOException | RuntimeException error) {
                response.close();
                throw error;
            }
        };
    }
}
