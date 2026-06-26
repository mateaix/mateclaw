package vip.mate.context.intelligence.probe;

import vip.mate.context.intelligence.config.ContextIntelligenceProperties;

import java.time.Instant;

/**
 * Window probe state machine (up aggressive / down conservative).
 * <p>
 * State transitions: COLD → PROBING → BINARY_SEARCH → STABLE → DEGRADED → PROBING
 * <p>
 * <b>Thread-safety model</b>:
 * <ul>
 *   <li>{@code recordSuccess} / {@code recordOverflow} execute inside the {@code ConcurrentHashMap.compute} lock,
 *       serialized per-key, no extra synchronization needed</li>
 *   <li>{@code snapshot()} and volatile fields can be read concurrently by the read path (budget planning);
 *       volatile guarantees visibility</li>
 *   <li>{@code samples} / {@code sampleIdx} / {@code sampleCount} are volatile data, only accessed within the compute lock</li>
 * </ul>
 * <p>
 * <b>Not a Spring Bean</b>: created per-key by {@link WindowProbeRegistry}, with config injected at construction.
 *
 * @author MateClaw Team
 */
public class WindowProbe {

    /** State machine phase */
    public enum Phase {
        /** Initial value exists but has not been validated by production traffic */
        COLD,
        /** Lower bound validated, upper bound under exponential probing */
        PROBING,
        /** Both bounds known, converging via binary search */
        BINARY_SEARCH,
        /** Window confirmed, passively observing */
        STABLE,
        /** Just overflowed, shrinking */
        DEGRADED
    }

    // --- Window estimation (volatile, visible to read path) ---
    volatile Phase phase;
    volatile int effectiveWindow;
    volatile int confidenceLower;
    volatile int confidenceUpper;
    volatile int declaredLimit;

    // --- Statistics (volatile, visible to read path) ---
    volatile int peakObserved;
    volatile int successiveSuccess;
    volatile int successiveOverflow;
    volatile int totalSuccess;
    volatile int totalOverflow;
    volatile Instant lastSuccessAt;
    volatile Instant lastOverflowAt;
    volatile Instant lastUpdatedAt;

    /**
     * Last DB persistence time in STABLE state (used for C.3 throttling).
     * <p>
     * Non-volatile field, only used in the ContextSignalProcessor single-writer scenario;
     * the worst-case race is one extra or one missed DB write, which is harmless.
     */
    volatile Instant lastPersistAt;

    // --- Sampling ring buffer (volatile data, accessed within compute lock) ---
    final int[] samples;
    int sampleIdx;
    int sampleCount;
    final int sampleSize;

    // --- Constants (injected from yml) ---
    final int globalCeiling;
    final int coldSeedFallback;
    final double overflowShrinkRatio;
    final double binaryConvergence;
    final long staleReprobeMs;

    /**
     * Config is injected when the Registry creates an instance.
     */
    WindowProbe(ContextIntelligenceProperties.Probe cfg) {
        this.globalCeiling = cfg.getGlobalCeiling();
        this.coldSeedFallback = cfg.getColdSeedFallback();
        this.overflowShrinkRatio = cfg.getOverflowShrinkRatio();
        this.binaryConvergence = cfg.getBinaryConvergence();
        this.staleReprobeMs = cfg.getStaleReprobeMs();
        this.sampleSize = cfg.getSampleSize();
        this.samples = new int[sampleSize];
    }

    // ==================== Factory methods ====================

    /**
     * Cold start (no declaredLimit).
     * <p>
     * The seed value is typically the yml cold-seed-fallback (default 32768), to avoid excessive
     * initial optimism for large models.
     */
    static WindowProbe coldStart(int seed, ContextIntelligenceProperties.Probe cfg) {
        WindowProbe p = new WindowProbe(cfg);
        p.effectiveWindow = seed;
        p.confidenceLower = 0;
        p.confidenceUpper = Math.min(seed * 2, p.globalCeiling);
        p.phase = Phase.COLD;
        p.declaredLimit = 0;
        p.lastUpdatedAt = Instant.now();
        return p;
    }

    /**
     * Cold start (with declaredLimit, for scenarios where the model's declared limit is known).
     * <p>
     * When the declared limit is smaller than the exponential probing upper bound, lower the upper
     * bound to declaredLimit.
     */
    static WindowProbe coldStart(int seed, int declaredLimit, ContextIntelligenceProperties.Probe cfg) {
        WindowProbe p = coldStart(seed, cfg);
        p.declaredLimit = declaredLimit;
        if (declaredLimit > 0 && declaredLimit < p.confidenceUpper) {
            p.confidenceUpper = declaredLimit;
        }
        return p;
    }

    /**
     * Restore from a DB snapshot (restart recovery flow, §5.7).
     * <p>
     * samples / sampleIdx / sampleCount are cleared (volatile data), and re-accumulate quickly after restart.
     */
    static WindowProbe restoreFromSnapshot(WindowProbeSnapshot snap, ContextIntelligenceProperties.Probe cfg) {
        WindowProbe p = new WindowProbe(cfg);
        p.phase = snap.phase();
        p.effectiveWindow = snap.effectiveWindow();
        p.confidenceLower = snap.confidenceLower();
        p.confidenceUpper = snap.confidenceUpper();
        p.declaredLimit = snap.declaredLimit();
        p.peakObserved = snap.peakObserved();
        p.successiveSuccess = snap.successiveSuccess();
        p.successiveOverflow = snap.successiveOverflow();
        p.totalSuccess = snap.totalSuccess();
        p.totalOverflow = snap.totalOverflow();
        p.lastSuccessAt = snap.lastSuccessAt();
        p.lastOverflowAt = snap.lastOverflowAt();
        p.lastUpdatedAt = snap.lastUpdatedAt();
        return p;
    }

    // ==================== Lock-free reads ====================

    /**
     * Capture the current state snapshot (for out-of-lock DB persistence and EnvSnapshot refresh).
     * <p>
     * Read path is concurrency-safe: all fields are volatile, reading an approximate value at a given moment.
     */
    WindowProbeSnapshot snapshot() {
        return new WindowProbeSnapshot(
                phase, effectiveWindow, confidenceLower, confidenceUpper,
                declaredLimit, peakObserved, successiveSuccess, successiveOverflow,
                totalSuccess, totalOverflow, lastSuccessAt, lastOverflowAt, lastUpdatedAt
        );
    }

    // ==================== Write path: recordSuccess (up aggressive) ====================

    /**
     * Record a successful invocation and immediately try to expand effectiveWindow (§6.2).
     * <p>
     * <b>Must be called within the ConcurrentHashMap.compute lock</b>.
     */
    void recordSuccess(int promptTokens) {
        // 1. Sampling
        samples[sampleIdx] = promptTokens;
        sampleIdx = (sampleIdx + 1) % sampleSize;
        if (sampleCount < sampleSize) {
            sampleCount++;
        }

        // 2. Statistics
        if (promptTokens > peakObserved) {
            peakObserved = promptTokens;
        }
        successiveSuccess++;
        successiveOverflow = 0;
        totalSuccess++;
        lastSuccessAt = Instant.now();
        lastUpdatedAt = lastSuccessAt;

        // 3. State transition
        int safeEstimate = safeEstimate();
        switch (phase) {
            case COLD -> handleColdSuccess(safeEstimate);
            case PROBING -> handleProbingSuccess(safeEstimate);
            case BINARY_SEARCH -> handleBinarySearchSuccess();
            case STABLE -> handleStableSuccess();
            case DEGRADED -> handleDegradedSuccess();
        }
    }

    /** COLD → PROBING: when samples fill SAMPLE_SIZE/2, extract the lower bound and start probing */
    private void handleColdSuccess(int safeEstimate) {
        if (sampleCount < sampleSize / 2) {
            return;
        }
        effectiveWindow = safeEstimate;
        confidenceLower = safeEstimate;
        confidenceUpper = (int) Math.min((long) safeEstimate * 2, globalCeiling);
        if (declaredLimit > 0 && declaredLimit < confidenceUpper) {
            confidenceUpper = declaredLimit;
        }
        phase = Phase.PROBING;
    }

    /** PROBING: 3 consecutive successes and samples near the current window → expand the upper bound; hit ceiling → STABLE */
    private void handleProbingSuccess(int safeEstimate) {
        if (successiveSuccess >= 3 && safeEstimate >= effectiveWindow * 0.8) {
            // Expand the upper bound (up aggressive)
            confidenceLower = Math.max(confidenceLower, effectiveWindow);
            int newUpper = (int) Math.min((long) confidenceUpper * 2, globalCeiling);
            if (declaredLimit > 0 && declaredLimit < newUpper) {
                newUpper = declaredLimit;
            }
            confidenceUpper = newUpper;
            effectiveWindow = confidenceUpper;
        }
        // Hit ceiling → STABLE (conservatively pin to the lower bound)
        boolean hitCeiling = confidenceUpper >= globalCeiling;
        boolean hitDeclared = declaredLimit > 0 && confidenceUpper >= declaredLimit;
        if (hitCeiling || hitDeclared) {
            effectiveWindow = confidenceLower;
            phase = Phase.STABLE;
        }
    }

    /** BINARY_SEARCH: success → raise the lower bound, check convergence */
    private void handleBinarySearchSuccess() {
        confidenceLower = Math.max(confidenceLower, effectiveWindow);
        if (confidenceLower > 0 && (double) (confidenceUpper - confidenceLower) / confidenceLower < binaryConvergence) {
            effectiveWindow = confidenceLower;
            phase = Phase.STABLE;
        }
    }

    /** STABLE: no near-ceiling calls for longer than staleReprobeMs → re-probe (GPU may have scaled up) */
    private void handleStableSuccess() {
        if (lastSuccessAt == null) {
            return;
        }
        long elapsedMs = System.currentTimeMillis() - lastSuccessAt.toEpochMilli();
        if (elapsedMs > staleReprobeMs) {
            phase = Phase.PROBING;
            confidenceUpper = (int) Math.min((long) effectiveWindow * 2, globalCeiling);
            if (declaredLimit > 0 && declaredLimit < confidenceUpper) {
                confidenceUpper = declaredLimit;
            }
            effectiveWindow = confidenceUpper;
        }
    }

    /** DEGRADED → PROBING: 3 consecutive successes, re-probe */
    private void handleDegradedSuccess() {
        if (successiveSuccess >= 3) {
            phase = Phase.PROBING;
            confidenceUpper = (int) Math.min((long) effectiveWindow * 2, globalCeiling);
            if (declaredLimit > 0 && declaredLimit < confidenceUpper) {
                confidenceUpper = declaredLimit;
            }
            effectiveWindow = confidenceUpper;
        }
    }

    // ==================== Write path: recordOverflow (down conservative) ====================

    /**
     * Record an overflow invocation (§6.3).
     * <p>
     * <b>Must be called within the ConcurrentHashMap.compute lock</b>.
     * A single overflow does not shrink; shrinkage requires statistical evidence from ≥ SAMPLE_SIZE/2 samples.
     */
    void recordOverflow(int attemptedTokens) {
        successiveOverflow++;
        successiveSuccess = 0;
        totalOverflow++;
        lastOverflowAt = Instant.now();
        lastUpdatedAt = lastOverflowAt;

        // Insufficient samples: only update confidenceUpper, no shrinkage
        if (sampleCount < sampleSize / 2) {
            if (attemptedTokens > 0 && attemptedTokens < confidenceUpper) {
                confidenceUpper = attemptedTokens;
            }
            return;
        }

        int safeMax = safeEstimate();
        int shrunk = Math.max(safeMax, (int) Math.floor(attemptedTokens * overflowShrinkRatio));

        switch (phase) {
            case COLD, PROBING -> handleProbingOverflow(attemptedTokens, shrunk);
            case BINARY_SEARCH -> handleBinarySearchOverflow(attemptedTokens, shrunk);
            case STABLE -> handleStableOverflow(attemptedTokens, shrunk);
            case DEGRADED -> handleDegradedOverflow(shrunk);
        }
    }

    /** COLD/PROBING overflow: has lower bound → BINARY_SEARCH, no lower bound → DEGRADED */
    private void handleProbingOverflow(int attemptedTokens, int shrunk) {
        effectiveWindow = shrunk;
        confidenceUpper = attemptedTokens;
        if (confidenceLower > 0) {
            phase = Phase.BINARY_SEARCH;
        } else {
            phase = Phase.DEGRADED;
        }
    }

    /** BINARY_SEARCH overflow: lower the upper bound, check convergence */
    private void handleBinarySearchOverflow(int attemptedTokens, int shrunk) {
        effectiveWindow = shrunk;
        confidenceUpper = Math.min(confidenceUpper, attemptedTokens);
        if (confidenceLower > 0 && (double) (confidenceUpper - confidenceLower) / confidenceLower < binaryConvergence) {
            effectiveWindow = confidenceLower;
            phase = Phase.STABLE;
        }
    }

    /** STABLE overflow → DEGRADED */
    private void handleStableOverflow(int attemptedTokens, int shrunk) {
        effectiveWindow = shrunk;
        confidenceUpper = Math.min(confidenceUpper, attemptedTokens);
        phase = Phase.DEGRADED;
    }

    /** DEGRADED overflow: only shrink, never expand */
    private void handleDegradedOverflow(int shrunk) {
        if (shrunk < effectiveWindow) {
            effectiveWindow = shrunk;
        }
    }

    // ==================== Utilities ====================

    /** Max successful token count within the sampling window (safeEstimate) */
    private int safeEstimate() {
        int max = 0;
        for (int i = 0; i < sampleCount; i++) {
            if (samples[i] > max) {
                max = samples[i];
            }
        }
        return max;
    }
}
