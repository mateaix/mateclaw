package vip.mate.context.intelligence.snapshot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vip.mate.context.intelligence.enums.ResourcePressure;
import vip.mate.context.intelligence.metrics.ContextIntelMetrics;
import vip.mate.context.intelligence.probe.WindowProbeRegistry;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Environment snapshot cache (sole data source for read path).
 * <p>
 * Stores {@link EnvSnapshot} keyed by {@code (provider, model)}, using
 * {@code ConcurrentHashMap + AtomicReference} for lock-free reads.
 * <p>
 * <b>Key design</b> (§5.4):
 * <ul>
 *   <li>EnvSnapshot is an immutable record, copy-on-write</li>
 *   <li>AtomicReference.set is a volatile write, immediately visible to readers</li>
 *   <li>refreshIfChanged performs CAS only when effectiveWindow / pressure / diversityDetected change, reducing contention</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class EnvSnapshotStore {

    private final ConcurrentHashMap<String, AtomicReference<EnvSnapshot>> snapshots = new ConcurrentHashMap<>();

    /** Observability metrics gateway (§C.7), nullable: ObjectProvider may return null in test environment */
    private final ContextIntelMetrics metrics;

    public EnvSnapshotStore(ObjectProvider<ContextIntelMetrics> metricsProvider) {
        this.metrics = metricsProvider.getIfAvailable();
    }

    /**
     * Read path: get snapshot (fully lock-free).
     * <p>
     * Returns EMPTY to indicate no data; caller should fall back to yml.
     */
    public EnvSnapshot get(String provider, String modelName) {
        String key = WindowProbeRegistry.key(provider, modelName);
        AtomicReference<EnvSnapshot> ref = snapshots.get(key);
        if (ref == null) {
            return EnvSnapshot.EMPTY;
        }
        EnvSnapshot snap = ref.get();
        return (snap != null) ? snap : EnvSnapshot.EMPTY;
    }

    /**
     * Write path: conditional snapshot refresh (only update when key fields change).
     * <p>
     * Called by ContextSignalProcessor after each signal is processed.
     *
     * @param key                provider:model
     * @param newEffectiveWindow new effective window
     * @param newPressure        new pressure level
     * @param newDiversity       new diversity detection result
     * @param newSafeWindow      new P10 safe window (effective when diversityDetected=true)
     */
    public void refreshIfChanged(String key, int newEffectiveWindow,
                                 ResourcePressure newPressure, boolean newDiversity, int newSafeWindow) {
        try {
            AtomicReference<EnvSnapshot> ref = snapshots.computeIfAbsent(
                    key, k -> new AtomicReference<>(EnvSnapshot.EMPTY));
            EnvSnapshot old = ref.get();
            // Only create a new snapshot when one of the three key fields changes (reduce CAS contention, mitigates risk 2)
            if (old.effectiveWindow() != newEffectiveWindow
                    || old.pressure() != newPressure
                    || old.diversityDetected() != newDiversity) {
                ref.set(new EnvSnapshot(
                        newEffectiveWindow, newPressure, newDiversity, newSafeWindow, Instant.now().toEpochMilli()
                ));
                // §C.7: Report counter + 3 gauges when snapshot is actually refreshed
                recordRefreshMetrics(key, newEffectiveWindow, newPressure, newDiversity);
            }
        } catch (Exception e) {
            log.debug("[ContextIntel] EnvSnapshot refresh failed for {}: {}", key, e.getMessage());
        }
    }

    /** Idle eviction (coordinated by WindowProbeRegistry.evictIdleProbes) */
    public void evict(String key) {
        snapshots.remove(key);
    }

    // ==================== Internal methods ====================

    /**
     * Report snapshot refresh metrics (§C.7).
     * <p>
     * Only invoked on actual refresh (not on every refreshIfChanged call).
     * Fully try-catch guarded; metrics failures must not affect the snapshot.
     */
    private void recordRefreshMetrics(String key, int effectiveWindow,
                                       ResourcePressure pressure, boolean diversity) {
        if (metrics == null) return;
        try {
            String[] parts = splitKey(key);
            String provider = parts[0];
            String model = parts[1];
            metrics.recordSnapshotRefresh(provider, model);
            metrics.updatePressureLevel(provider, model, pressure.gaugeValue());
            metrics.updateEffectiveWindow(provider, model, effectiveWindow);
            metrics.updateDiversityDetected(provider, model, diversity);
        } catch (Exception e) {
            log.debug("[ContextIntel] refresh metrics failed for {}: {}", key, e.getMessage());
        }
    }

    private static String[] splitKey(String key) {
        int idx = key.indexOf(':');
        if (idx < 0) {
            return new String[]{key, ""};
        }
        return new String[]{key.substring(0, idx), key.substring(idx + 1)};
    }
}
