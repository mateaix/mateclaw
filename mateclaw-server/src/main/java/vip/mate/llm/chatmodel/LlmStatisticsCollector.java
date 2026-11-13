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
