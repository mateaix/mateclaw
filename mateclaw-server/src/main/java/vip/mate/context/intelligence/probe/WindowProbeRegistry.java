package vip.mate.context.intelligence.probe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.mate.context.intelligence.config.ContextIntelligenceProperties;
import vip.mate.context.intelligence.metrics.ContextIntelMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-model probe registry (write path entry).
 * <p>
 * Manages the {@code (provider, model) → WindowProbe} mapping, using {@link ConcurrentHashMap#compute}
 * to guarantee per-key atomic updates. The read path (budget planning) does not go through here; it reads
 * {@code EnvSnapshotStore} directly.
 * <p>
 * <b>key format</b>: {@code provider + ":" + modelName} (split on the first colon)
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class WindowProbeRegistry {

    private final ConcurrentHashMap<String, WindowProbe> states = new ConcurrentHashMap<>();

    private final ContextIntelligenceProperties props;
    private final WindowStateLoader stateLoader;  // nullable
    /** Observability metrics gateway (§C.7), nullable: ObjectProvider may return null in test environments */
    private final ContextIntelMetrics metrics;

    public WindowProbeRegistry(
            ContextIntelligenceProperties props,
            ObjectProvider<WindowStateLoader> stateLoaderProvider,
            ObjectProvider<ContextIntelMetrics> metricsProvider) {
        this.props = props;
        this.stateLoader = stateLoaderProvider.getIfAvailable();
        this.metrics = metricsProvider.getIfAvailable();
    }

    // ==================== key utilities ====================

    /** Build a unified key for (provider, modelName) */
    public static String key(String provider, String modelName) {
        return provider + ":" + modelName;
    }

    private static String[] splitKey(String key) {
        int idx = key.indexOf(':');
        if (idx < 0) {
            return new String[]{key, ""};
        }
        return new String[]{key.substring(0, idx), key.substring(idx + 1)};
    }

    // ==================== Write path ====================

    /**
     * Record a successful invocation (state machine update is performed within the compute lock).
     * <p>
     * Both snapshot and previousPhase are captured within the compute lock to ensure consistency.
     *
     * @return ProbeUpdate (containing snapshot + previousPhase); the processor uses it to decide whether to persist
     */
    public ProbeUpdate recordSuccess(String key, int promptTokens) {
        final WindowProbe.Phase[] prevHolder = new WindowProbe.Phase[1];
        final WindowProbeSnapshot[] snapHolder = new WindowProbeSnapshot[1];
        states.compute(key, (k, existing) -> {
            WindowProbe probe = (existing != null) ? existing : loadOrCreate(k);
            prevHolder[0] = probe.phase;
            probe.recordSuccess(promptTokens);
            snapHolder[0] = probe.snapshot();
            return probe;
        });
        recordPhaseMetrics(key, prevHolder[0], snapHolder[0]);
        return new ProbeUpdate(snapHolder[0], prevHolder[0]);
    }

    /**
     * Record an overflow invocation (state machine update is performed within the compute lock).
     * <p>
     * The overflow path executes synchronously (@EventListener without @Async), ensuring confidenceUpper is
     * updated before the PTL retry.
     */
    public ProbeUpdate recordOverflow(String key, int attemptedTokens) {
        final WindowProbe.Phase[] prevHolder = new WindowProbe.Phase[1];
        final WindowProbeSnapshot[] snapHolder = new WindowProbeSnapshot[1];
        states.compute(key, (k, existing) -> {
            WindowProbe probe = (existing != null) ? existing : loadOrCreate(k);
            prevHolder[0] = probe.phase;
            probe.recordOverflow(attemptedTokens);
            snapHolder[0] = probe.snapshot();
            return probe;
        });
        recordPhaseMetrics(key, prevHolder[0], snapHolder[0]);
        return new ProbeUpdate(snapHolder[0], prevHolder[0]);
    }

    // ==================== Read path ====================

    /**
     * Get a probe snapshot (for metrics / debugging, not the main budget-planning path).
     * <p>
     * The main budget-planning path reads EnvSnapshotStore, not the Registry directly.
     */
    public WindowProbeSnapshot getSnapshot(String key) {
        WindowProbe probe = states.get(key);
        return (probe != null) ? probe.snapshot() : null;
    }

    // ==================== Idle eviction (C.2) ====================

    /**
     * Periodically evict probes of idle models to prevent the states map from growing unbounded.
     * <p>
     * Eviction rule: STABLE state + no successful invocations for longer than idleThresholdMs.
     * After eviction, the next access rebuilds from DB via loadOrCreate.
     */
    @Scheduled(fixedRateString = "${mateclaw.context.intelligence.auto-clean.clean-interval-ms:3600000}")
    public void evictIdleProbes() {
        ContextIntelligenceProperties.AutoClean cfg = props.getAutoClean();
        if (!cfg.isEnabled()) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofMillis(cfg.getIdleThresholdMs()));
        int[] removed = {0};
        states.entrySet().removeIf(entry -> {
            WindowProbe probe = entry.getValue();
            if (probe.phase != WindowProbe.Phase.STABLE) {
                return false;
            }
            if (probe.lastSuccessAt == null) {
                return false;
            }
            if (probe.lastSuccessAt.isAfter(cutoff)) {
                return false;
            }
            removed[0]++;
            return true;
        });
        if (removed[0] > 0) {
            log.debug("[ContextIntel] evicted {} idle probes (STABLE + idle > {}ms)",
                    removed[0], cfg.getIdleThresholdMs());
        }
    }

    // ==================== Internal methods ====================

    /**
     * Report phase-related metrics (§C.7).
     * <p>
     * Executed outside the compute lock to avoid holding the lock during potential metrics registration contention.
     * <ul>
     *   <li>{@code updatePhase} gauge is always updated to reflect the current phase</li>
     *   <li>{@code recordPhaseTransition} counter is incremented only on phase transitions</li>
     * </ul>
     * Wrapped in try-catch throughout; a metrics failure must not affect the state machine.
     */
    private void recordPhaseMetrics(String key, WindowProbe.Phase prevPhase, WindowProbeSnapshot snap) {
        if (metrics == null) return;
        try {
            String[] parts = splitKey(key);
            String provider = parts[0];
            String model = parts[1];
            WindowProbe.Phase currPhase = snap.phase();
            metrics.updatePhase(provider, model, currPhase.ordinal());
            if (prevPhase != currPhase) {
                metrics.recordPhaseTransition(provider, model,
                        prevPhase.name(), currPhase.name());
            }
        } catch (Exception e) {
            log.debug("[ContextIntel] phase metrics failed for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Load or create a probe (§5.7 restart recovery flow).
     * <p>
     * 1. First query DB (if stateLoader is available)
     * 2. DB has a record → restoreFromSnapshot
     * 3. DB has no record → coldStart(coldSeedFallback)
     * <p>
     * <b>Note</b>: this method is called within the compute lock, so DB I/O holds the lock.
     * It is only triggered on first access (cold start), which is acceptable.
     */
    private WindowProbe loadOrCreate(String key) {
        // 1. Try to restore from DB
        if (stateLoader != null && props.isDbPersist()) {
            try {
                String[] parts = splitKey(key);
                WindowProbeSnapshot snap = stateLoader.load(parts[0], parts[1]);
                if (snap != null && snap.effectiveWindow() > 0) {
                    return WindowProbe.restoreFromSnapshot(snap, props.getProbe());
                }
            } catch (Exception e) {
                log.debug("[ContextIntel] loadFromDB failed for {}: {}", key, e.getMessage());
            }
        }

        // 2. Cold start
        return WindowProbe.coldStart(props.getProbe().getColdSeedFallback(), props.getProbe());
    }
}
