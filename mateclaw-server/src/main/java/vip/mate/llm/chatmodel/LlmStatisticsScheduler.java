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
