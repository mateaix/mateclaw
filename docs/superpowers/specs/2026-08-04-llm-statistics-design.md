# LLM Response Statistics and Latency Tracking Design Specification

## 1. Overview
The system heavily calls LLMs and acts dynamically based on responses. Keeping track of LLM request latencies (response speed) is critical for system health monitoring, routing decisions, and timeout tuning.
This specification designs a non-intrusive metadata timing collector to log and analyze latencies for:
- Non-streaming LLM requests (overall response time).
- Streaming LLM requests (first token latency and overall completion time).

Daily statistical metrics (Count, Min, Max, Average, p50, p90, p99) are aggregated per model/provider and globally, and printed to logs every 15 minutes. The buffer is cleared at midnight every day to prevent memory accumulation.

---

## 2. Architecture & Components

The timing statistics will be implemented via low-overhead components:
1. **`LlmRequestMetric.java` (Record)**: A simple data structure holding latency data for single requests.
2. **`LlmStatisticsCollector.java` (In-Memory Buffer)**: Stores recorded request metrics. Keeps a hard queue size limit of 10,000 to prevent out-of-memory (OOM) failures under heavy load.
3. **`LlmStatisticsDecorator.java` (ChatModel Decorator)**: Intercepts `ChatModel#call` and `ChatModel#stream` calls programmatically to timestamp outgoing and incoming chunks.
4. **`LlmStatisticsScheduler.java` (Aggregator & Cron)**: Scheduled cron job firing every 15 minutes to run statistical analysis and log reports, and resetting the collector at midnight.

```
       [ProviderChatModelFactory]
                   │
                   ▼ (wraps with)
       ┌──────────────────────────────┐
       │   LlmStatisticsDecorator     │
       └──────────────┬───────────────┘
                      │
           (intercepts call & stream)
                      │
                      ▼
        ┌────────────────────────────┐
        │   LlmStatisticsCollector   │ <─── [OOM Capacity Guard = 10,000]
        └─────────────┬──────────────┘
                      │
              (reads snapshot)
                      │
                      ▼
         ┌──────────────────────────┐
         │ LlmStatisticsScheduler   │ (runs every 15m; resets at midnight)
         └──────────────────────────┘
```

---

## 3. Detailed Component Designs

### 3.1 `LlmRequestMetric`
A Java record containing request parameters and timestamp attributes:
```java
package vip.mate.llm.chatmodel;

import java.time.LocalDateTime;

public record LlmRequestMetric(
    LocalDateTime timestamp,
    String providerId,
    String modelName,
    boolean isStreaming,
    Long firstTokenLatencyMs, // null for non-streaming
    long overallLatencyMs
) {}
```

### 3.2 `LlmStatisticsCollector`
A thread-safe bean that limits cumulative request storage to 10k items maximum.
```java
package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Component
public class LlmStatisticsCollector {

    private static final int MAX_CAPACITY = 10000;
    private final Queue<LlmRequestMetric> requestMetrics = new ConcurrentLinkedQueue<>();

    public void record(LlmRequestMetric metric) {
        if (requestMetrics.size() >= MAX_CAPACITY) {
            // Evict oldest element to guard memory (FIFO queue behavior)
            LlmRequestMetric evicted = requestMetrics.poll();
            if (evicted != null) {
                log.warn("[LlmStatisticsCollector] Capacity limit (10000) reached. Evicted oldest metric from: {}", evicted.timestamp());
            }
        }
        requestMetrics.add(metric);
    }

    public void reset() {
        requestMetrics.clear();
        log.info("[LlmStatisticsCollector] Metrics buffer reset.");
    }

    public List<LlmRequestMetric> getMetricsSnapshot() {
        return new ArrayList<>(requestMetrics);
    }
}
```

### 3.3 `LlmStatisticsDecorator`
This decorator intercepts standard call threads and reactive streams.
```java
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
```

### 3.4 Integration via `ProviderChatModelFactory`
We will decorate ChatModels inside the factory right before returning:
```java
// ProviderChatModelFactory.java
    public ChatModel buildFor(ModelConfigEntity model, RetryTemplate retry) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(model.getProvider());
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        ChatModelBuilder builder = builders.get(protocol);
        if (builder == null) {
            throw new MateClawException("err.agent.protocol_limited",
                    "No ChatModelBuilder registered for protocol: " + protocol.getId());
        }
        ChatModel rawModel = builder.build(model, provider, retry);
        // Wrap with LlmStatisticsDecorator to measure latency
        return new LlmStatisticsDecorator(rawModel, provider.getProviderId(), model.getModelName(), collector);
    }
```

### 3.5 `LlmStatisticsScheduler`
Retrieves snapshots, sorts values, outputs them to logs, and resets daily at midnight.
```java
package vip.mate.llm.chatmodel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LlmStatisticsScheduler {

    private final LlmStatisticsCollector collector;
    private LocalDate lastReportingDate = LocalDate.now();

    public LlmStatisticsScheduler(LlmStatisticsCollector collector) {
        this.collector = collector;
    }

    @Scheduled(cron = "0 */15 * * * ?")
    public void reportStatistics() {
        LocalDate today = LocalDate.now();
        if (!today.equals(lastReportingDate)) {
            log.info("Midnight reached. Performing final LLM stats reporting for: {}", lastReportingDate);
            generateReport();
            collector.reset();
            lastReportingDate = today;
            log.info("LLM Statistics Collector has been reset for the new day: {}", today);
            return;
        }
        generateReport();
    }

    @Scheduled(cron = "0 0 0 * * ?")
    public void midnightReset() {
        collector.reset();
        lastReportingDate = LocalDate.now();
        log.info("LLM Statistics collector reset successfully at midnight.");
    }

    private void generateReport() {
        List<LlmRequestMetric> snapshot = collector.getMetricsSnapshot();
        if (snapshot.isEmpty()) {
            log.info("=== LLM Performance stats: No requests recorded today yet ===");
            return;
        }

        log.info("=================== LLM latency statistics report (Total: {}) ===================", snapshot.size());
        Map<String, List<LlmRequestMetric>> grouped = snapshot.stream()
                .collect(Collectors.groupingBy(m -> m.providerId() + " / " + m.modelName()));

        for (Map.Entry<String, List<LlmRequestMetric>> entry : grouped.entrySet()) {
            printGroupReport(entry.getKey(), entry.getValue());
        }

        printGroupReport("Global (All Models & Providers)", snapshot);
        log.info("=================================================================================");
    }

    private void printGroupReport(String groupName, List<LlmRequestMetric> metrics) {
        List<LlmRequestMetric> nonStreaming = metrics.stream().filter(m -> !m.isStreaming()).toList();
        List<LlmRequestMetric> streaming = metrics.stream().filter(LlmRequestMetric::isStreaming).toList();

        log.info("--- Stats for: {} (Total count: {}) ---", groupName, metrics.size());

        if (!nonStreaming.isEmpty()) {
            List<Long> latencies = nonStreaming.stream().map(LlmRequestMetric::overallLatencyMs).toList();
            log.info("   [Non-Streaming] Sample count: {} | Min: {}ms | Max: {}ms | Avg: {}ms | p50: {}ms | p90: {}ms | p99: {}ms",
                    latencies.size(),
                    latencies.stream().mapToLong(Long::longValue).min().orElse(0),
                    latencies.stream().mapToLong(Long::longValue).max().orElse(0),
                    Math.round(latencies.stream().mapToLong(Long::longValue).average().orElse(0.0)),
                    Math.round(getPercentile(latencies, 50)),
                    Math.round(getPercentile(latencies, 90)),
                    Math.round(getPercentile(latencies, 99))
            );
        }

        if (!streaming.isEmpty()) {
            List<Long> firstTokens = streaming.stream()
                    .map(LlmRequestMetric::firstTokenLatencyMs)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            List<Long> overalls = streaming.stream().map(LlmRequestMetric::overallLatencyMs).toList();

            if (!firstTokens.isEmpty()) {
                log.info("   [Streaming - First Token] Sample count: {} | Min: {}ms | Max: {}ms | Avg: {}ms | p50: {}ms | p90: {}ms | p99: {}ms",
                        firstTokens.size(),
                        firstTokens.stream().mapToLong(Long::longValue).min().orElse(0),
                        firstTokens.stream().mapToLong(Long::longValue).max().orElse(0),
                        Math.round(firstTokens.stream().mapToLong(Long::longValue).average().orElse(0.0)),
                        Math.round(getPercentile(firstTokens, 50)),
                        Math.round(getPercentile(firstTokens, 90)),
                        Math.round(getPercentile(firstTokens, 99))
                );
            }
            log.info("   [Streaming - Completion] Sample count: {} | Min: {}ms | Max: {}ms | Avg: {}ms | p50: {}ms | p90: {}ms | p99: {}ms",
                    overalls.size(),
                    overalls.stream().mapToLong(Long::longValue).min().orElse(0),
                    overalls.stream().mapToLong(Long::longValue).max().orElse(0),
                    Math.round(overalls.stream().mapToLong(Long::longValue).average().orElse(0.0)),
                    Math.round(getPercentile(overalls, 50)),
                    Math.round(getPercentile(overalls, 90)),
                    Math.round(getPercentile(overalls, 99))
            );
        }
    }

    private double getPercentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int idx = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }
}
```

---

## 4. Testing & Verification

1. **Unit Testing**:
   - Verify `LlmStatisticsCollector` capacity limit (adding 10005 metrics and confirming item count remains at 10000, with correct FIFO eviction of the first 5 elements).
   - Mock a `ChatModel` behavior for both blocking `call(...)` and reactive stream `stream(...)`, verifying timing durations and recorded metric details match mocked latencies.
2. **Logging Verification**:
   - Configure SLF4J log configurations if needed. Verify debug statements occur exactly at start and end times in local/dev log files.
