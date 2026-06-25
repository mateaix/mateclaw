package vip.mate.context.adaptive;

import java.time.Instant;

/**
 * Per-model context window runtime state — the core state machine.
 *
 * <p>State transitions:
 * <pre>
 *   COLD → PROBING → BINARY_SEARCH → STABLE
 *                                    → DEGRADED → PROBING / STABLE
 * </pre>
 *
 * <p>Exponential probing with binary convergence:
 * <ul>
 *   <li>PROBING: confidence_upper doubles on each successful expansion step,
 *       capped by the declared provider limit and the global ceiling (2M).</li>
 *   <li>On first overflow, confidence_upper is pinned and BINARY_SEARCH begins.</li>
 *   <li>BINARY_SEARCH converges when (upper - lower) / lower < 10%.</li>
 *   <li>STABLE reverts to PROBING after 30 min without a near-ceiling call
 *       (GPU may have been added).</li>
 * </ul>
 *
 * <p>All fields are {@code volatile} because the enclosing
 * {@link AdaptiveContextTracker} performs read/write under
 * {@code ConcurrentHashMap.compute} atomicity but reads from the budget
 * path ({@link #getEffectiveWindow()}) may happen concurrently.
 *
 * @author MateClaw Team
 */
public class ModelWindowState {

    /** Window estimation phase */
    public enum Phase {
        /** Initial value exists but has never been verified by production traffic */
        COLD,
        /** Lower bound verified, upper bound being probed with exponential expansion */
        PROBING,
        /** Both bounds known, converging via binary search */
        BINARY_SEARCH,
        /** Window confirmed, passive observation only */
        STABLE,
        /** Just hit an overflow, shrinking toward the overflow point */
        DEGRADED
    }

    // --- window estimation ---

    volatile Phase phase = Phase.COLD;
    volatile int effectiveWindow;
    volatile int confidenceLower;
    volatile int confidenceUpper;
    volatile int declaredLimit;

    // --- gateway mode ---

    volatile boolean gatewayMode;

    // --- statistics ---

    volatile int peakObserved;
    volatile int successiveSuccess;
    volatile int successiveOverflow;
    volatile int totalSuccess;
    volatile int totalOverflow;
    volatile Instant lastSuccessAt;
    volatile Instant lastOverflowAt;
    volatile Instant lastUpdatedAt;

    // --- constants ---

    static final int GLOBAL_CEILING = 2_000_000;
    static final int COLD_SEED_FALLBACK = 32_768;
    static final double OVERFLOW_SHRINK = 0.85;
    static final double BINARY_CONVERGENCE = 0.10;
    static final long STALE_MS = 30 * 60_000;

    /** Build a cold-start state with the given seed window. */
    static ModelWindowState coldStart(int seed) {
        ModelWindowState s = new ModelWindowState();
        s.effectiveWindow = seed;
        s.confidenceLower = 0;
        s.confidenceUpper = Math.min(seed * 2, GLOBAL_CEILING);
        s.phase = Phase.COLD;
        s.declaredLimit = 0;
        s.lastUpdatedAt = Instant.now();
        return s;
    }

    /** Build a cold-start state with a declared provider limit as the initial upper bound. */
    static ModelWindowState coldStart(int seed, int declaredLimit) {
        ModelWindowState s = coldStart(seed);
        s.declaredLimit = declaredLimit;
        if (declaredLimit > 0 && declaredLimit < s.confidenceUpper) {
            s.confidenceUpper = declaredLimit;
        }
        return s;
    }

    /**
     * Record a successful LLM call.
     *
     * @param promptTokens actual prompt token count returned by the API
     * @return true if the window estimate changed
     */
    boolean recordSuccess(int promptTokens) {
        boolean changed = false;
        Instant now = Instant.now();

        successiveSuccess++;
        successiveOverflow = 0;
        totalSuccess++;
        lastSuccessAt = now;
        lastUpdatedAt = now;
        peakObserved = Math.max(peakObserved, promptTokens);

        // Immediate upward: if this call used more context than we thought possible,
        // the window is at least this large. In COLD, trust the first real data over the seed
        // (seed could be wildly wrong for very small/very large models).
        if (phase != Phase.COLD) {
            effectiveWindow = Math.max(effectiveWindow, promptTokens);
        } else {
            effectiveWindow = promptTokens;
        }

        // Accumulate samples for statistical tracking (overflow verification, etc.)
        samples[sampleIdx % SAMPLE_SIZE] = promptTokens;
        sampleIdx++;
        if (sampleCount < SAMPLE_SIZE) { sampleCount++; return false; }
        sampleIdx = sampleIdx % SAMPLE_SIZE;
        int safeEstimate = 0;
        for (int s : samples) { if (s > safeEstimate) safeEstimate = s; }

        switch (phase) {
            case COLD -> {
                // Sampling only discovers larger windows; short conversations are harmless noise
                confidenceLower = Math.max(confidenceLower, safeEstimate);
                effectiveWindow = Math.max(effectiveWindow, safeEstimate);
                phase = Phase.PROBING;
                changed = true;
            }
            case PROBING -> {
                confidenceLower = Math.max(confidenceLower, safeEstimate);
                effectiveWindow = Math.max(effectiveWindow, safeEstimate);

                if (successiveSuccess >= 3 && safeEstimate >= effectiveWindow * 0.80) {
                    int nextUpper = Math.min(confidenceUpper * 2, GLOBAL_CEILING);
                    if (declaredLimit > 0 && nextUpper > declaredLimit) nextUpper = declaredLimit;
                    if (nextUpper > confidenceUpper) {
                        confidenceUpper = nextUpper;
                        changed = true;
                    } else if (confidenceUpper >= GLOBAL_CEILING
                            || (declaredLimit > 0 && confidenceUpper >= declaredLimit)) {
                        phase = Phase.STABLE;
                        changed = true;
                    }
                }
            }
            case BINARY_SEARCH -> {
                confidenceLower = Math.max(confidenceLower, safeEstimate);
                effectiveWindow = Math.max(effectiveWindow, safeEstimate);

                if (confidenceLower > 0
                        && (double)(confidenceUpper - confidenceLower) / confidenceLower < BINARY_CONVERGENCE) {
                    effectiveWindow = confidenceLower;
                    phase = Phase.STABLE;
                    changed = true;
                }
            }
            case STABLE -> {
                // Only expand upward from sampling; shrinking requires an overflow event
                effectiveWindow = Math.max(effectiveWindow, safeEstimate);
                if (lastSuccessAt != null
                        && now.toEpochMilli() - lastSuccessAt.toEpochMilli() > STALE_MS) {
                    phase = Phase.PROBING;
                    changed = true;
                }
            }
            case DEGRADED -> {
                confidenceLower = Math.max(confidenceLower, safeEstimate);
                effectiveWindow = Math.max(effectiveWindow, safeEstimate);
                if (successiveSuccess >= 3) {
                    phase = Phase.PROBING;
                    confidenceUpper = Math.min(effectiveWindow * 2, GLOBAL_CEILING);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Record an overflow (prompt-too-long error).
     *
     * @param attemptedTokens estimated prompt token count before the failed call
     * @param isGateway       true if this model is in gateway multi-backend mode
     * @return true if the window estimate changed
     */
    boolean recordOverflow(int attemptedTokens) {
        boolean changed = false;
        Instant now = Instant.now();

        successiveOverflow++;
        successiveSuccess = 0;
        totalOverflow++;
        lastOverflowAt = now;
        lastUpdatedAt = now;

        // Record the overflow point, but DO NOT immediately shrink.
        // A single overflow could be a transient error or network glitch.
        // Only shrink when statistical evidence from successful samples confirms.
        confidenceUpper = Math.min(confidenceUpper, attemptedTokens);
        if (sampleCount < SAMPLE_SIZE / 2) return false;
        int safeMax = 0;
        for (int s : samples) { if (s > safeMax) safeMax = s; }
        int shrunk = Math.max(safeMax, (int)(attemptedTokens * OVERFLOW_SHRINK));

        switch (phase) {
            case COLD, PROBING -> {
                effectiveWindow = shrunk;
                if (confidenceLower > 0 && confidenceLower < confidenceUpper) {
                    phase = Phase.BINARY_SEARCH;
                } else {
                    phase = Phase.DEGRADED;
                }
                changed = true;
            }
            case BINARY_SEARCH -> {
                effectiveWindow = shrunk;
                if (confidenceLower > 0
                        && (double)(confidenceUpper - confidenceLower) / confidenceLower < BINARY_CONVERGENCE) {
                    effectiveWindow = confidenceLower;
                    phase = Phase.STABLE;
                }
                changed = true;
            }
            case STABLE -> {
                effectiveWindow = shrunk;
                phase = Phase.DEGRADED;
                changed = true;
            }
            case DEGRADED -> {
                effectiveWindow = Math.min(effectiveWindow, shrunk);
                changed = true;
            }
        }
        return changed;
    }

    /** Current effective window (used by budget allocation). */
    int getEffectiveWindow() { return effectiveWindow; }

    /** Gateway-mode safe window: the verified lower bound. */
    int getSafeGatewayWindow() {
        if (confidenceLower > 0) {
            return Math.max(COLD_SEED_FALLBACK, confidenceLower);
        }
        return effectiveWindow;
    }
}
