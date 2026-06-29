package vip.mate.context.intelligence.perception;

import vip.mate.context.intelligence.config.ContextIntelligenceProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backend diversity tracker (per-model instance, not a Spring Bean).
 * <p>
 * Detects whether a single model name routes to multiple backends (e.g. a gateway sometimes
 * routes "gpt-4" to an 8K model, sometimes to a 128K model). This manifests as a large
 * variance in successTokens or an abnormal success/overflow ratio for that key.
 * <p>
 * <b>Detection conditions</b> (triggered when any is met, sticky until decay):
 * <ul>
 *   <li>Condition A (strong signal): minOverflowToken > 0 and maxSuccessToken / minOverflowToken > overflowRatioThreshold</li>
 *   <li>Condition B (weak signal): maxSuccessToken / minSuccessToken > successVarianceThreshold</li>
 * </ul>
 * <p>
 * <b>Thread safety</b>: successTokens uses copy-on-write + AtomicReference;
 * minOverflowToken / maxSuccessToken / observationCount use AtomicInteger.
 *
 * @author MateClaw Team
 */
public class BackendDiversityTracker {

    // --- observation data ---
    final AtomicReference<List<Integer>> successTokens;
    final AtomicInteger minOverflowToken;   // v1 Bug 2: never updated, fixed in v2
    final AtomicInteger maxSuccessToken;
    final AtomicInteger observationCount;

    // --- detection result (sticky)---
    volatile boolean diversityDetected;
    volatile long lastResetTime;

    // --- config ---
    final int maxObservations;
    final int safePercentile;
    final double successVarianceThreshold;
    final double overflowRatioThreshold;
    final long decayMs;

    BackendDiversityTracker(ContextIntelligenceProperties.Diversity cfg) {
        this.successTokens = new AtomicReference<>(new ArrayList<>());
        this.minOverflowToken = new AtomicInteger(Integer.MAX_VALUE);
        this.maxSuccessToken = new AtomicInteger(0);
        this.observationCount = new AtomicInteger(0);
        this.diversityDetected = false;
        this.lastResetTime = System.currentTimeMillis();
        this.maxObservations = cfg.getMaxObservations();
        this.safePercentile = cfg.getSafePercentile();
        this.successVarianceThreshold = cfg.getSuccessVarianceThreshold();
        this.overflowRatioThreshold = cfg.getOverflowRatioThreshold();
        this.decayMs = cfg.getDecayHours() * 3_600_000L;
    }

    /** Restore diversityDetected flag from DB (§5.7 restart recovery) */
    static BackendDiversityTracker restore(boolean diversityDetected, ContextIntelligenceProperties.Diversity cfg) {
        BackendDiversityTracker t = new BackendDiversityTracker(cfg);
        t.diversityDetected = diversityDetected;
        return t;
    }

    // ==================== write path ====================

    /** Record the prompt token count of a successful call */
    void recordSuccess(int promptTokens) {
        checkDecay();

        // copy-on-write update of successTokens
        successTokens.updateAndGet(current -> {
            List<Integer> copy = new ArrayList<>(current);
            copy.add(promptTokens);
            // discard oldest observations when exceeding the cap
            while (copy.size() > maxObservations) {
                copy.remove(0);
            }
            return Collections.unmodifiableList(copy);
        });

        // update maxSuccessToken
        maxSuccessToken.accumulateAndGet(promptTokens, Math::max);

        // check detection conditions every 10 observations
        int count = observationCount.incrementAndGet();
        if (count % 10 == 0) {
            detectPattern();
        }
    }

    /** Record the attempted token count of an overflow call (v1 Bug 2 fix: this method is finally invoked) */
    void recordOverflow(int attemptedTokens) {
        checkDecay();

        // update minOverflowToken
        minOverflowToken.accumulateAndGet(attemptedTokens, Math::min);

        observationCount.incrementAndGet();

        // overflow is a strong signal, check immediately
        detectPattern();
    }

    // ==================== read path ====================

    public boolean isDiversityDetected() {
        return diversityDetected;
    }

    /**
     * Compute the P10 safe window (10th percentile of successTokens).
     * <p>
     * Only meaningful when diversityDetected=true. Returns 0 when there is no observation data.
     */
    public int computeSafeWindow() {
        List<Integer> tokens = successTokens.get();
        if (tokens.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(tokens);
        Collections.sort(sorted);
        // P10 = 10th percentile
        int idx = (int) Math.ceil(sorted.size() * safePercentile / 100.0) - 1;
        if (idx < 0) idx = 0;
        return sorted.get(idx);
    }

    // ==================== internal methods ====================

    /** Check decay: reset detection result after decayMs */
    private void checkDecay() {
        long now = System.currentTimeMillis();
        if (now - lastResetTime > decayMs) {
            diversityDetected = false;
            successTokens.set(new ArrayList<>());
            minOverflowToken.set(Integer.MAX_VALUE);
            maxSuccessToken.set(0);
            observationCount.set(0);
            lastResetTime = now;
        }
    }

    /** Run detection conditions A and B */
    private void detectPattern() {
        if (diversityDetected) {
            return;  // sticky, no need to re-detect once detected
        }

        int maxSuccess = maxSuccessToken.get();
        int minOverflow = minOverflowToken.get();

        // Condition A (strong signal): abnormal success/overflow ratio
        // maxSuccess much larger than minOverflow -> some backends can handle far more tokens than the overflow threshold
        if (minOverflow > 0 && minOverflow != Integer.MAX_VALUE && maxSuccess > 0) {
            double ratio = (double) maxSuccess / minOverflow;
            if (ratio > overflowRatioThreshold) {
                diversityDetected = true;
                return;
            }
        }

        // Condition B (weak signal): large variance in success token distribution
        List<Integer> tokens = successTokens.get();
        if (tokens.size() >= 10) {
            int min = tokens.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = tokens.stream().mapToInt(Integer::intValue).max().orElse(0);
            if (min > 0) {
                double varianceRatio = (double) max / min;
                if (varianceRatio > successVarianceThreshold) {
                    diversityDetected = true;
                }
            }
        }
    }
}
