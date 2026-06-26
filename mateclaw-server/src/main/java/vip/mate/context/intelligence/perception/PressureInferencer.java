package vip.mate.context.intelligence.perception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.context.intelligence.config.ContextIntelligenceProperties;
import vip.mate.context.intelligence.enums.ResourcePressure;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pressure inferencer (global singleton, not per-model).
 * <p>
 * Infers the current resource pressure level of the LLM backend based on consecutive
 * success/overflow/latency. The pressure level is shared globally (affecting the budget
 * allocation ratio for all models).
 * <p>
 * <b>Degradation path</b> (stepped, C.4): CRITICAL → HIGH → ELEVATED → NORMAL
 * <p>
 * <b>Escalation path</b>: overflowEscalateThreshold consecutive overflows OR average latency > latencyEscalateMs (requires ≥ minLatencySamples samples)
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class PressureInferencer {

    // --- current pressure level (volatile, lock-free read path)---
    volatile ResourcePressure currentPressure = ResourcePressure.NORMAL;

    // --- consecutive counters (AtomicInteger, CAS updates)---
    final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
    final AtomicInteger consecutiveOverflows = new AtomicInteger(0);

    // --- latency samples (ring buffer, synchronized guards multi-field updates)---
    final long[] latencySamples = new long[10];
    int latencyIdx = 0;
    int latencySampleCount = 0;

    private final ContextIntelligenceProperties.Pressure cfg;

    public PressureInferencer(ContextIntelligenceProperties props) {
        this.cfg = props.getPressure();
    }

    // ==================== write path ====================

    /**
     * Record a successful call (stepped degradation, C.4).
     * <p>
     * Each level of degradation requires successDegradeStep consecutive successes; the counter
     * is reset after degrading. Latency escalation requires ≥ minLatencySamples samples to
     * avoid misjudging a single spike (v2.6).
     */
    public void recordSuccess(long latencyMs) {
        try {
            consecutiveSuccesses.incrementAndGet();
            consecutiveOverflows.set(0);
            updateLatencySamples(latencyMs);

            // stepped degradation
            int step = cfg.getSuccessDegradeStep();
            int successes = consecutiveSuccesses.get();
            if (successes >= step) {
                boolean degraded = false;
                if (currentPressure == ResourcePressure.CRITICAL) {
                    currentPressure = ResourcePressure.HIGH;
                    degraded = true;
                } else if (currentPressure == ResourcePressure.HIGH) {
                    currentPressure = ResourcePressure.ELEVATED;
                    degraded = true;
                } else if (currentPressure == ResourcePressure.ELEVATED) {
                    currentPressure = ResourcePressure.NORMAL;
                    degraded = true;
                }
                if (degraded) {
                    consecutiveSuccesses.set(0);  // reset counter, accumulate step successes again for the next level
                }
            }

            // latency escalation check (requires ≥ minLatencySamples samples, v2.6)
            if (latencySampleCount >= cfg.getMinLatencySamples()) {
                long avgLatency = averageLatency();
                if (avgLatency > cfg.getLatencyEscalateMs()) {
                    escalatePressure();
                }
            }
        } catch (Exception e) {
            log.debug("[ContextIntel] PressureInferencer recordSuccess failed: {}", e.getMessage());
        }
    }

    /** Record an overflow call (overflowEscalateThreshold consecutive overflows -> escalate) */
    public void recordOverflow() {
        try {
            consecutiveOverflows.incrementAndGet();
            consecutiveSuccesses.set(0);

            if (consecutiveOverflows.get() >= cfg.getOverflowEscalateThreshold()) {
                escalatePressure();
            }
        } catch (Exception e) {
            log.debug("[ContextIntel] PressureInferencer recordOverflow failed: {}", e.getMessage());
        }
    }

    // ==================== read path ====================

    public ResourcePressure currentPressure() {
        return currentPressure;
    }

    // ==================== internal methods ====================

    /** Escalate the pressure level (NORMAL → ELEVATED → HIGH → CRITICAL) */
    private void escalatePressure() {
        if (currentPressure == ResourcePressure.NORMAL) {
            currentPressure = ResourcePressure.ELEVATED;
        } else if (currentPressure == ResourcePressure.ELEVATED) {
            currentPressure = ResourcePressure.HIGH;
        } else if (currentPressure == ResourcePressure.HIGH) {
            currentPressure = ResourcePressure.CRITICAL;
        }
        // CRITICAL is the highest level, no further escalation
    }

    /**
     * Update the latency sample ring buffer.
     * <p>
     * synchronized guard: multiple fields (latencySamples / latencyIdx / latencySampleCount)
     * need atomic updates. signalExecutor has 8 threads, but the method is very fast and contention is low.
     */
    private synchronized void updateLatencySamples(long latencyMs) {
        latencySamples[latencyIdx] = latencyMs;
        latencyIdx = (latencyIdx + 1) % latencySamples.length;
        if (latencySampleCount < latencySamples.length) {
            latencySampleCount++;
        }
    }

    /** Compute the average of the latency samples */
    private long averageLatency() {
        if (latencySampleCount == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < latencySampleCount; i++) {
            sum += latencySamples[i];
        }
        return sum / latencySampleCount;
    }
}
