package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebClient (streaming) counterpart of {@link RateLimitDiagnosticInterceptor}.
 *
 * <p>On 429, logs outgoing request headers (sanitized), a body preview captured
 * non-destructively via {@link DataBuffer#toByteBuffer(int, int)}, and the
 * {@code anthropic-ratelimit-*} response headers. Delegates constant and
 * formatting logic to the shared statics on {@link RateLimitDiagnosticInterceptor}.
 */
@Slf4j
class RateLimitDiagnosticExchangeFilter implements ExchangeFilterFunction {

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        AtomicReference<String> capturedBody = new AtomicReference<>();

        ClientRequest intercepted = ClientRequest.from(request)
                .body((outputMessage, context) -> request.body().insert(
                        new ClientHttpRequestDecorator(outputMessage) {
                            @Override
                            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                                return super.writeWith(
                                        Flux.from(body).doOnNext(buf -> {
                                            if (capturedBody.get() == null) {
                                                int len = Math.min(buf.readableByteCount(),
                                                        RateLimitDiagnosticInterceptor.BODY_LOG_LIMIT);
                                                ByteBuffer view = buf.toByteBuffer(buf.readPosition(), len);
                                                byte[] bytes = new byte[len];
                                                view.get(bytes);
                                                capturedBody.compareAndSet(null,
                                                        new String(bytes, StandardCharsets.UTF_8));
                                            }
                                        })
                                );
                            }
                        }, context))
                .build();

        return next.exchange(intercepted).doOnNext(response -> {
            if (response.statusCode().value() == 429) {
                RateLimitDiagnosticInterceptor.logRequestHeaders(request.headers());
                String preview = capturedBody.get();
                log.warn("[Anthropic 429] request body preview: {}",
                        preview != null ? preview : "(not captured)");
                RateLimitDiagnosticInterceptor.logResponseHeaders(
                        response.headers().asHttpHeaders());
            }
        });
    }
}
