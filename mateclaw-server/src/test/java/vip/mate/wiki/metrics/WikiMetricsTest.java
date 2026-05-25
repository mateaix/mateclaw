package vip.mate.wiki.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiMetrics}.
 *
 * <p>Covers three regimes:
 * <ul>
 *   <li>Registry available: meters are registered with correct tags</li>
 *   <li>Registry absent: all methods become no-ops, never throw</li>
 *   <li>{@link WikiTimerSample}: try-with-resources records elapsed time</li>
 * </ul>
 */
class WikiMetricsTest {

    @Test
    @DisplayName("recordCompileStage registers timer with stage and kb_id tags")
    void recordCompileStage_registersTaggedTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WikiMetrics metrics = new WikiMetrics(provider(registry));

        metrics.recordCompileStage("summary", 42L, Duration.ofMillis(150));

        var timer = registry.find("wiki.compile.stage")
                .tag("stage", "summary")
                .tag("kb_id", "42")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordCompileCache emits hit/miss counter and tokens_saved")
    void recordCompileCache_emitsBothCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WikiMetrics metrics = new WikiMetrics(provider(registry));

        metrics.recordCompileCache(true, 800);
        metrics.recordCompileCache(false, 0);

        assertThat(registry.find("wiki.compile.cache.outcome").tag("outcome", "hit").counter().count())
                .isEqualTo(1);
        assertThat(registry.find("wiki.compile.cache.outcome").tag("outcome", "miss").counter().count())
                .isEqualTo(1);
        assertThat(registry.find("wiki.compile.cache.tokens_saved").counter().count())
                .isEqualTo(800);
    }

    @Test
    @DisplayName("recordRetrieval tags by mode and increments result counter")
    void recordRetrieval_tagsByMode() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WikiMetrics metrics = new WikiMetrics(provider(registry));

        metrics.recordRetrieval("hybrid", Duration.ofMillis(50), 5);
        metrics.recordRetrieval("hybrid", Duration.ofMillis(80), 3);

        var timer = registry.find("wiki.retrieval.duration").tag("mode", "hybrid").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(registry.find("wiki.retrieval.results").tag("mode", "hybrid").counter().count())
                .isEqualTo(8);
    }

    @Test
    @DisplayName("recordVisionCall tags by provider and outcome")
    void recordVisionCall_tagsByProviderAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WikiMetrics metrics = new WikiMetrics(provider(registry));

        metrics.recordVisionCall("dashscope-vision", true, Duration.ofMillis(2000));
        metrics.recordVisionCall("dashscope-vision", false, Duration.ofMillis(500));

        assertThat(registry.find("wiki.vision.call")
                .tag("provider", "dashscope-vision")
                .tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.find("wiki.vision.call")
                .tag("provider", "dashscope-vision")
                .tag("outcome", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Without MeterRegistry available, all methods are silent no-ops")
    void noRegistry_allMethodsNoOp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);

        WikiMetrics metrics = new WikiMetrics(empty);

        // None of these may throw.
        metrics.recordCompileStage("summary", 1L, Duration.ZERO);
        metrics.recordCompileCache(true, 100);
        metrics.recordRelationCompute(1L, 50, Duration.ZERO);
        metrics.recordRelationCacheHit(true);
        metrics.recordRetrieval("hybrid", Duration.ZERO, 5);
        metrics.recordVisionCall("p", true, Duration.ZERO);
        metrics.recordVisionCacheHit(false);

        // Sample close should also be silent.
        try (var sample = metrics.startTimer("wiki.test.foo", "kb_id", "1")) {
            // no-op
        }
    }

    @Test
    @DisplayName("startTimer records elapsed time on close, with tags applied")
    void timerSample_recordsOnClose() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WikiMetrics metrics = new WikiMetrics(provider(registry));

        try (var sample = metrics.startTimer("wiki.test.foo", "kb_id", "7")) {
            sleepMillis(5);
        }

        var timer = registry.find("wiki.test.foo").tag("kb_id", "7").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Calling close twice on a sample does not double-record")
    void timerSample_idempotentClose() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WikiMetrics metrics = new WikiMetrics(provider(registry));

        WikiTimerSample sample = metrics.startTimer("wiki.test.idempotent");
        sample.close();
        sample.close();

        assertThat(registry.find("wiki.test.idempotent").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Same meter name + tags is registered only once across calls")
    void meterCacheReusesRegistration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WikiMetrics metrics = new WikiMetrics(provider(registry));

        for (int i = 0; i < 100; i++) {
            metrics.recordCompileStage("summary", 1L, Duration.ofMillis(1));
        }

        // 100 records on a single meter, not 100 separate meters.
        assertThat(registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("wiki.compile.stage"))
                .count()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> provider(MeterRegistry r) {
        ObjectProvider<MeterRegistry> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(r);
        return p;
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
