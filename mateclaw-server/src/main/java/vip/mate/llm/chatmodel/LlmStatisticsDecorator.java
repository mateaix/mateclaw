package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class LlmStatisticsDecorator implements ChatModel {

    private final ChatModel delegate;
    private final String providerId;
    private final String modelName;
    private final LlmStatisticsCollector collector;

    public LlmStatisticsDecorator(ChatModel delegate, String providerId, String modelName, LlmStatisticsCollector collector) {
        this.delegate = delegate;
        this.providerId = providerId;
        this.modelName = modelName;
        this.collector = collector;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        LocalDateTime requestTime = LocalDateTime.now();
        long start = System.currentTimeMillis();
        log.debug("[LLM Request Start] Provider: {}, Model: {}, Streaming: false, Time: {}", providerId, modelName, requestTime);
        try {
            ChatResponse response = delegate.call(prompt);
            long overall = System.currentTimeMillis() - start;
            log.debug("[LLM Request End] Provider: {}, Model: {}, Streaming: false, Latency: {}ms", providerId, modelName, overall);
            collector.record(new LlmRequestMetric(
                requestTime, providerId, modelName, false, null, overall
            ));
            return response;
        } catch (Exception e) {
            long overall = System.currentTimeMillis() - start;
            log.debug("[LLM Request Error] Provider: {}, Model: {}, Streaming: false, Latency: {}ms, Error: {}", providerId, modelName, overall, e.getMessage());
            collector.record(new LlmRequestMetric(
                requestTime, providerId, modelName, false, null, overall
            ));
            throw e;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        LocalDateTime requestTime = LocalDateTime.now();
        long start = System.currentTimeMillis();
        log.debug("[LLM Request Start] Provider: {}, Model: {}, Streaming: true, Time: {}", providerId, modelName, requestTime);

        AtomicBoolean firstTokenReceived = new AtomicBoolean(false);
        AtomicLong firstTokenLatency = new AtomicLong(-1);

        return delegate.stream(prompt)
                .doOnNext(response -> {
                    boolean hasContent = response.getResults() != null && response.getResults().stream()
                            .anyMatch(g -> g.getOutput() != null && g.getOutput().getText() != null && !g.getOutput().getText().isEmpty());
                    if (hasContent && firstTokenReceived.compareAndSet(false, true)) {
                        long diff = System.currentTimeMillis() - start;
                        firstTokenLatency.set(diff);
                        log.debug("[LLM First Token] Provider: {}, Model: {}, Latency: {}ms", providerId, modelName, diff);
                    }
                })
                .doOnError(e -> {
                    long overall = System.currentTimeMillis() - start;
                    log.debug("[LLM Stream Error] Provider: {}, Model: {}, Latency: {}ms, Error: {}", providerId, modelName, overall, e.getMessage());
                    collector.record(new LlmRequestMetric(
                        requestTime, providerId, modelName, true,
                        firstTokenLatency.get() >= 0 ? firstTokenLatency.get() : null,
                        overall
                    ));
                })
                .doOnComplete(() -> {
                    long overall = System.currentTimeMillis() - start;
                    log.debug("[LLM Stream End] Provider: {}, Model: {}, Latency: {}ms", providerId, modelName, overall);
                    collector.record(new LlmRequestMetric(
                        requestTime, providerId, modelName, true,
                        firstTokenLatency.get() >= 0 ? firstTokenLatency.get() : null,
                        overall
                    ));
                });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
