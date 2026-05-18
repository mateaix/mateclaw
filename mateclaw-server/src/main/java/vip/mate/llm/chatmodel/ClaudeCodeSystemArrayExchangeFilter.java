package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebClient (streaming) counterpart of {@link ClaudeCodeSystemArrayInterceptor}.
 *
 * <p>Collects the full request body via {@code DataBufferUtils.join}, delegates
 * the rewrite to {@link ClaudeCodeSystemArrayInterceptor#rewriteSystemField}, and
 * emits the modified bytes as a single new {@link DataBuffer}.
 */
@Slf4j
@RequiredArgsConstructor
class ClaudeCodeSystemArrayExchangeFilter implements ExchangeFilterFunction {

    private final ObjectMapper objectMapper;

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        ClientRequest intercepted = ClientRequest.from(request)
                .body((outputMessage, context) -> request.body().insert(
                        new ClientHttpRequestDecorator(outputMessage) {
                            @Override
                            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                                return DataBufferUtils.join(Flux.from(body))
                                        .flatMap(joined -> {
                                            byte[] original = new byte[joined.readableByteCount()];
                                            joined.read(original);
                                            DataBufferUtils.release(joined);

                                            byte[] rewritten = ClaudeCodeSystemArrayInterceptor
                                                    .rewriteSystemField(original, objectMapper);

                                            long declared = getHeaders().getContentLength();
                                            if (declared > 0 && declared != rewritten.length) {
                                                getHeaders().setContentLength(rewritten.length);
                                            }

                                            return super.writeWith(Mono.just(
                                                    outputMessage.bufferFactory().wrap(rewritten)));
                                        });
                            }
                        }, context))
                .build();

        return next.exchange(intercepted);
    }
}
