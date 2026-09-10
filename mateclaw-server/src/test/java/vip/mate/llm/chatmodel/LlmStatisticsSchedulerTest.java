package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmStatisticsSchedulerTest {

    @Test
    void testSchedulerCalculationAndReset() {
        LlmStatisticsCollector collector = new LlmStatisticsCollector();
        collector.record(new LlmRequestMetric(LocalDateTime.now(), "p1", "m1", false, null, 100));
        collector.record(new LlmRequestMetric(LocalDateTime.now(), "p1", "m1", false, null, 200));

        LlmStatisticsScheduler scheduler = new LlmStatisticsScheduler(collector);
        scheduler.reportStatistics(); // Should log stats output without throwing error

        List<LlmRequestMetric> snapshot = collector.getMetricsSnapshot();
        assertEquals(2, snapshot.size());
    }
}
