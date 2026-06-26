package vip.mate.context.intelligence.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vip.mate.context.intelligence.config.ContextIntelligenceProperties;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Context Intelligence domain metrics gateway (modeled after {@code vip.mate.wiki.metrics.WikiMetrics}).
 *
 * <p>All observability metrics of the v2 module flow through this gateway, in order to:
 * <ul>
 *   <li>Standardize tag names ({@code provider}, {@code model}, {@code type}, {@code reason},
 *       {@code from}, {@code to})</li>
 *   <li>Gracefully degrade to no-op when {@link MeterRegistry} is missing (test environment)</li>
 *   <li>Centrally handle cross-cutting concerns (cardinality limits, sampling)</li>
 * </ul>
 *
 * <p>Corresponds to design document §C.7 and §8.2. Metric inventory:
 * <table border="1">
 * <tr><th>Metric name</th><th>Type</th><th>Tags</th><th>Description</th></tr>
 * <tr><td>{@code context.intel.signal.process.duration}</td><td>Timer</td><td>type=success|overflow</td><td>Signal processing duration</td></tr>
 * <tr><td>{@code context.intel.phase.transition}</td><td>Counter</td><td>provider,model,from,to</td><td>Phase transition count</td></tr>
 * <tr><td>{@code context.intel.snapshot.refresh}</td><td>Counter</td><td>provider,model</td><td>Snapshot refresh count</td></tr>
 * <tr><td>{@code context.intel.db.persist.failure}</td><td>Counter</td><td>provider,model</td><td>DB persist failure</td></tr>
 * <tr><td>{@code context.intel.signal.dropped}</td><td>Counter</td><td>reason</td><td>Signal dropped</td></tr>
 * <tr><td>{@code context.intel.pressure.level}</td><td>Gauge</td><td>provider,model</td><td>Current pressure level 0/1/2/3</td></tr>
 * <tr><td>{@code context.intel.effective.window}</td><td>Gauge</td><td>provider,model</td><td>Current effective window tokens</td></tr>
 * <tr><td>{@code context.intel.phase}</td><td>Gauge</td><td>provider,model</td><td>Current state machine phase 0/1/2/3/4</td></tr>
 * <tr><td>{@code context.intel.diversity.detected}</td><td>Gauge</td><td>provider,model</td><td>Whether multiple backends detected 0/1</td></tr>
 * </table>
 *
 * <p><b>Gauge implementation note</b>: Uses {@link AtomicInteger} as the gauge value container,
 * registered once via {@link MeterRegistry#gauge(String, Iterable, Object)};
 * subsequent updates via {@link AtomicInteger#set(int)} are automatically reflected at scrape time.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class ContextIntelMetrics {

    private final MeterRegistry registry;
    /** Combined switch: true only when MeterRegistry exists AND metrics.enabled=true */
    private final boolean enabled;
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> gaugeValueCache = new ConcurrentHashMap<>();

    public ContextIntelMetrics(ObjectProvider<MeterRegistry> registryProvider,
                                ContextIntelligenceProperties props) {
        this.registry = registryProvider.getIfAvailable();
        this.enabled = registry != null && props.getMetrics().isEnabled();
        if (!enabled) {
            log.info("[ContextIntel] metrics disabled (MeterRegistry={}, metrics.enabled={})",
                    registry != null ? "available" : "absent",
                    props.getMetrics().isEnabled());
        } else {
            log.info("[ContextIntel] MeterRegistry available; metrics enabled");
        }
    }

    // ==================== Signal processing timers (§C.7) ====================

    /** Record successful signal processing duration (Processor.onSuccess). */
    public void recordSuccessProcess(Duration duration) {
        if (!enabled) return;
        getTimer("context.intel.signal.process.duration",
                Tags.of(Tag.of("type", "success"))).record(duration);
    }

    /** Record overflow signal processing duration (Processor.onOverflow). */
    public void recordOverflowProcess(Duration duration) {
        if (!enabled) return;
        getTimer("context.intel.signal.process.duration",
                Tags.of(Tag.of("type", "overflow"))).record(duration);
    }

    // ==================== Phase transitions (§C.7) ====================

    /** Record state machine phase transition (WindowProbeRegistry). */
    public void recordPhaseTransition(String provider, String model, String from, String to) {
        if (!enabled) return;
        getCounter("context.intel.phase.transition",
                Tags.of(Tag.of("provider", provider),
                        Tag.of("model", model),
                        Tag.of("from", from),
                        Tag.of("to", to))).increment();
    }

    // ==================== Snapshot refresh (§C.7) ====================

    /** Record snapshot refresh count (when EnvSnapshotStore.refreshIfChanged actually updates). */
    public void recordSnapshotRefresh(String provider, String model) {
        if (!enabled) return;
        getCounter("context.intel.snapshot.refresh",
                Tags.of(Tag.of("provider", provider),
                        Tag.of("model", model))).increment();
    }

    // ==================== DB persist failures (§C.7) ====================

    /** Record DB persist failure (WindowStateRepository.persist catch block). */
    public void recordDbPersistFailure(String provider, String model) {
        if (!enabled) return;
        getCounter("context.intel.db.persist.failure",
                Tags.of(Tag.of("provider", provider),
                        Tag.of("model", model))).increment();
    }

    // ==================== Signal dropped (§C.7) ====================

    /** Record signal dropped (signal not fully processed due to Processor exception). */
    public void recordSignalDropped(String reason) {
        if (!enabled) return;
        getCounter("context.intel.signal.dropped",
                Tags.of(Tag.of("reason", reason))).increment();
    }

    // ==================== Gauges (per provider:model, §C.7) ====================

    /** Update pressure level gauge (0/1/2/3). */
    public void updatePressureLevel(String provider, String model, int level) {
        if (!enabled) return;
        updateGauge("context.intel.pressure.level", provider, model, level);
    }

    /** Update effective window gauge (tokens). */
    public void updateEffectiveWindow(String provider, String model, int tokens) {
        if (!enabled) return;
        updateGauge("context.intel.effective.window", provider, model, tokens);
    }

    /** Update state machine phase gauge (0/1/2/3/4). */
    public void updatePhase(String provider, String model, int phase) {
        if (!enabled) return;
        updateGauge("context.intel.phase", provider, model, phase);
    }

    /** Update diversity detection gauge (0/1). */
    public void updateDiversityDetected(String provider, String model, boolean detected) {
        if (!enabled) return;
        updateGauge("context.intel.diversity.detected", provider, model, detected ? 1 : 0);
    }

    // ==================== Internal cache (copied from WikiMetrics) ====================

    private void updateGauge(String name, String provider, String model, int value) {
        String key = gaugeKey(name, provider, model);
        AtomicInteger holder = gaugeValueCache.computeIfAbsent(key, k -> {
            AtomicInteger h = new AtomicInteger(value);
            Tags tags = Tags.of(Tag.of("provider", provider),
                    Tag.of("model", model));
            registry.gauge(name, tags, h);
            return h;
        });
        holder.set(value);
    }

    private Counter getCounter(String name, Tags tags) {
        return counterCache.computeIfAbsent(meterKey(name, tags),
                k -> registry.counter(name, tags));
    }

    private Timer getTimer(String name, Tags tags) {
        return timerCache.computeIfAbsent(meterKey(name, tags),
                k -> registry.timer(name, tags));
    }

    private static String meterKey(String name, Tags tags) {
        StringBuilder sb = new StringBuilder(name);
        for (Tag t : tags) {
            sb.append('|').append(t.getKey()).append('=').append(t.getValue());
        }
        return sb.toString();
    }

    private static String gaugeKey(String name, String provider, String model) {
        return name + "|provider=" + provider + "|model=" + model;
    }
}
