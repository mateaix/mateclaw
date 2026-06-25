package vip.mate.context.adaptive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vip.mate.llm.failover.ProviderHealthTracker;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Context pressure monitor — weak-dependency wrapper layer.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Collect success / overflow signals from production traffic and forward them
 *       to {@link AdaptiveContextTracker}.</li>
 *   <li>Infer the current {@link ResourcePressure} level from LLM latency and
 *       consecutive overflow counts.</li>
 *   <li>All exceptions are caught — the monitor is best-effort only; the main LLM
 *       call flow must never be affected by a monitor-side failure.</li>
 *   <li>When the underlying {@code tracker} is not injected, every method is a
 *       silent no-op.</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class ContextPressureMonitor {

    @Autowired(required = false)
    private AdaptiveContextTracker tracker;

    @Autowired(required = false)
    private ProviderHealthTracker healthTracker;

    /** Ring buffer of recent LLM call latencies (ms). 20 entries, oldest overwritten. */
    private final AtomicReference<long[]> recentLatencies = new AtomicReference<>(new long[20]);
    private final AtomicInteger latencyIdx = new AtomicInteger(0);

    /** Consecutive overflow / success counters for pressure inference */
    private final AtomicInteger consecutiveOverflows = new AtomicInteger(0);
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);

    /** Current resource pressure level */
    private volatile ResourcePressure pressure = ResourcePressure.NORMAL;

    /** Consecutive "normal" assessments for recovery (de-escalation is slow to avoid oscillation) */
    private volatile int normalStreak = 0;

    /** Latency thresholds for pressure inference (ms) */
    private static final long LATENCY_ELEVATED_THRESHOLD = 15_000;
    private static final long LATENCY_HIGH_THRESHOLD = 45_000;

    // ==================== Public API ====================

    /**
     * Record a successful LLM call.
     */
    public void onLlmSuccess(String provider, String modelName,
                              String actualModel,
                              int promptTokens, int completionTokens,
                              long latencyMs) {
        if (tracker == null) return;
        try {
            tracker.recordSuccess(provider, modelName, actualModel, promptTokens);
            consecutiveSuccesses.incrementAndGet();
            consecutiveOverflows.set(0);
            recordLatency(latencyMs);
            reassessPressure();
        } catch (Exception e) {
            log.debug("[ContextPressure] onLlmSuccess failed: {}", e.getMessage());
        }
    }

    /**
     * Record a prompt-too-long overflow.
     */
    public void onLlmOverflow(String provider, String modelName, int attemptedTokens) {
        if (tracker == null) return;
        try {
            tracker.recordOverflow(provider, modelName, attemptedTokens);
            consecutiveOverflows.incrementAndGet();
            consecutiveSuccesses.set(0);
            if (consecutiveOverflows.get() >= 3 && pressure.atLeast(ResourcePressure.ELEVATED)) {
                pressure = ResourcePressure.HIGH;
                log.warn("[ContextPressure] 3+ consecutive overflows, escalating to HIGH");
            }
            reassessPressure();
        } catch (Exception e) {
            log.debug("[ContextPressure] onLlmOverflow failed: {}", e.getMessage());
        }
    }

    /** Current resource pressure level. The budget / compaction layers consult this for degradation. */
    public ResourcePressure getPressure() { return pressure; }

    /**
     * Effective context window from the underlying tracker.
     *
     * @return window in tokens, or 0 when the tracker has no data
     *         (caller should fall back to yml / provider defaults)
     */
    public int getEffectiveWindow(String provider, String modelName) {
        if (tracker == null) return 0;
        try {
            return tracker.getEffectiveWindow(provider, modelName);
        } catch (Exception e) {
            log.debug("[ContextPressure] getEffectiveWindow failed: {}", e.getMessage());
            return 0;
        }
    }

    /** Whether the given model is currently classified as gateway multi-backend. */
    public boolean isGatewayMode(String provider, String modelName) {
        if (tracker == null) return false;
        try {
            return tracker.isGatewayMode(provider, modelName);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Internals ====================

    private void recordLatency(long latencyMs) {
        long[] buf = recentLatencies.get();
        int idx = latencyIdx.getAndIncrement() % buf.length;
        buf[idx] = latencyMs;
    }

    private void reassessPressure() { if (pressure == ResourcePressure.NORMAL && consecutiveSuccesses.get() % 10 != 0) return;
        long[] buf = recentLatencies.get();
        int count = 0;
        long sum = 0;
        long max = 0;
        for (long v : buf) {
            if (v > 0) {
                count++;
                sum += v;
                max = Math.max(max, v);
            }
        }
        if (count < 5) return;

        double avg = (double) sum / count;
        ResourcePressure newPressure;
        if (avg > LATENCY_HIGH_THRESHOLD || consecutiveOverflows.get() >= 5) {
            newPressure = ResourcePressure.HIGH;
        } else if (avg > LATENCY_ELEVATED_THRESHOLD || consecutiveOverflows.get() >= 3) {
            newPressure = ResourcePressure.ELEVATED;
        } else {
            newPressure = ResourcePressure.NORMAL;
        }

        // Escalate quickly, de-escalate slowly (anti-oscillation)
        if (newPressure.level() > pressure.level()) {
            pressure = newPressure;
            normalStreak = 0;
        } else if (newPressure.level() < pressure.level()) {
            normalStreak++;
            if (normalStreak >= pressure.recoveryThreshold()) {
                ResourcePressure prev = pressure;
                pressure = switch (pressure) {
                    case CRITICAL -> ResourcePressure.HIGH;
                    case HIGH -> ResourcePressure.ELEVATED;
                    case ELEVATED -> ResourcePressure.NORMAL;
                    default -> ResourcePressure.NORMAL;
                };
                log.info("[ContextPressure] Recovered from {} to {} (normalStreak={})",
                        prev, pressure, normalStreak);
                normalStreak = 0;
            }
        }
    }
}
