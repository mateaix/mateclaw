package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmStatisticsCollectorTest {

    @Test
    void testCollectorEvictsWhenCapacityReached() {
        LlmStatisticsCollector collector = new LlmStatisticsCollector();
        // Insert 10005 metrics
        for (int i = 0; i < 10005; i++) {
            collector.record(new LlmRequestMetric(
                LocalDateTime.now(), "prov", "mod" + i, false, null, i
            ));
        }
        assertEquals(10000, collector.getMetricsSnapshot().size());
        // Verify FIFO eviction (first items mod0 to mod4 are evicted, mod5 remains)
        assertEquals("mod5", collector.getMetricsSnapshot().get(0).modelName());
    }

    @Test
    void testResetClearsMetrics() {
        LlmStatisticsCollector collector = new LlmStatisticsCollector();
        collector.record(new LlmRequestMetric(LocalDateTime.now(), "prov", "mod", false, null, 100));
        assertEquals(1, collector.getMetricsSnapshot().size());
        collector.reset();
        assertTrue(collector.getMetricsSnapshot().isEmpty());
    }
}
