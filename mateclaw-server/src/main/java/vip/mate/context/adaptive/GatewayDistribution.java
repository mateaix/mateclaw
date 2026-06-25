package vip.mate.context.adaptive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks context window distribution for gateway multi-backend scenarios.
 *
 * <p>When a gateway (OneAPI / LiteLLM) load-balances across multiple model
 * backends with different context windows, tracking a single "ceiling" is
 * unreliable. This class tracks the distribution of successful prompt token
 * sizes and uses the P10 percentile as the safe window—90% of requests will
 * succeed at or below this value.
 *
 * <p>Gateway detection is automatic: if the ratio of max successful prompt
 * tokens to min overflow tokens exceeds 3.0, or if successful prompt tokens
 * alone vary by more than 5.0x, the pattern is classified as gateway.
 *
 * <p>Thread-safe via AtomicReference + AtomicInteger fields.
 *
 * @author MateClaw Team
 */
public class GatewayDistribution {

    /** Observation window for long-period variance analysis (hours, not call count) */
    static final int MAX_OBSERVATIONS = 500;

    /** Percentile for safe window calculation (P10 = 90% success rate) */
    static final int SAFE_PERCENTILE = 10;

    /** Threshold: ratio of maxSuccess / maxSuccess variance to detect gateway */
    static final double SUCCESS_VARIANCE_THRESHOLD = 5.0;

    /** Threshold: ratio of maxSuccess / minOverflow to detect gateway */
    static final double OVERFLOW_SUCCESS_RATIO_THRESHOLD = 3.0;

    /** Minimum observation period (hours) before gateway detection engages */
    static final int MIN_OBSERVATION_HOURS = 24;

    /** Immutable snapshot of recent successful prompt token observations */
    private final AtomicReference<List<Integer>> successTokens = new AtomicReference<>(List.of());

    /** Minimum prompt token count that triggered an overflow (PTL error) */
    private final AtomicInteger minOverflowToken = new AtomicInteger(Integer.MAX_VALUE);

    /** Maximum prompt token count that succeeded */
    private final AtomicInteger maxSuccessToken = new AtomicInteger(0);

    /** Total observation count (success + overflow) */
    private final AtomicInteger observationCount = new AtomicInteger(0);

    /** True once the gateway pattern has been confirmed */
    private volatile boolean gatewayDetected;
    private volatile long lastResetTime = System.currentTimeMillis();
    static final long DECAY_HOURS_MS = 24 * 60 * 60 * 1000;

    /**
     * Record a successful LLM call's prompt token count.
     * Updates the sliding window of recent successful observations.
     */
    public void recordSuccess(int promptTokens) {
        // Decay: reset buffer if 24h passed since last reset (prevent ancient data)
        long now = System.currentTimeMillis();
        if (now - lastResetTime > DECAY_HOURS_MS) {
            successTokens.set(List.of());
            observationCount.set(0);
            lastResetTime = now;
        }
        List<Integer> current;
        List<Integer> updated;
        do {
            current = successTokens.get();
            updated = new ArrayList<>(current.size() + 1);
            updated.addAll(current);
            updated.add(promptTokens);
            if (updated.size() > MAX_OBSERVATIONS) {
                updated = updated.subList(updated.size() - MAX_OBSERVATIONS, updated.size());
            }
        } while (!successTokens.compareAndSet(current, Collections.unmodifiableList(updated)));
        observationCount.incrementAndGet();
        maxSuccessToken.accumulateAndGet(promptTokens, Math::max);
    }

    /**
     * Record an overflow (prompt-too-long) event's attempted token count.
     */
    public void recordOverflow(int attemptedTokens) {
        minOverflowToken.accumulateAndGet(attemptedTokens, Math::min);
        observationCount.incrementAndGet();
    }

    /**
     * Check if the current observations match the gateway multi-backend pattern.
     *
     * <p>Condition A: an overflow exists AND the maximum successful prompt tokens
     * are more than 3x the minimum overflow tokens (a single model can't succeed
     * at 100K and overflow at 12K).
     *
     * <p>Condition B: at least 10 observations AND successful prompt tokens vary
     * by more than 5x (a single model's window doesn't fluctuate 6x).
     */
    public boolean detectGatewayPattern() {
        if (gatewayDetected) return true;
        int obs = observationCount.get();
        // Require substantial accumulated data before assessing long-term variance.
        // ~100 calls is a rough proxy for 24h of usage on a moderately active agent.
        if (obs < 100) return false;

        // Condition A: success/overflow ratio — "succeeded at 100K but overflowed at 12K"
        int maxSucc = maxSuccessToken.get();
        int minOver = minOverflowToken.get();
        if (minOver < Integer.MAX_VALUE && maxSucc > 0
                && (double) maxSucc / minOver > OVERFLOW_SUCCESS_RATIO_THRESHOLD) {
            gatewayDetected = true;
            return true;
        }

        // Condition B: long-period peak variance — "max varies >5x over days"
        List<Integer> tokens = successTokens.get();
        if (tokens.size() >= 100) {
            int min = tokens.stream().min(Integer::compareTo).orElse(0);
            int max = tokens.stream().max(Integer::compareTo).orElse(0);
            if (min > 0 && (double) max / min > SUCCESS_VARIANCE_THRESHOLD) {
                gatewayDetected = true;
                return true;
            }
        }
        return false;
    }

    /**
     * Compute the safe context window: the P{@value #SAFE_PERCENTILE} percentile
     * of recent successful prompt token observations. Roughly
     * {@code 100 - SAFE_PERCENTILE}% of requests will succeed at or below this
     * value.
     */
    public int computeSafeWindow() {
        List<Integer> tokens = successTokens.get();
        if (tokens.isEmpty()) {
            return ModelWindowState.COLD_SEED_FALLBACK;
        }
        List<Integer> sorted = new ArrayList<>(tokens);
        Collections.sort(sorted);
        int idx = (int) (sorted.size() * (100 - SAFE_PERCENTILE) / 100.0);
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        if (idx < 0) idx = 0;
        return Math.max(ModelWindowState.COLD_SEED_FALLBACK, sorted.get(idx));
    }

    public boolean isGatewayDetected() { return gatewayDetected; }
    public int getObservationCount() { return observationCount.get(); }
    public int getMaxSuccessToken() { return maxSuccessToken.get(); }
}
