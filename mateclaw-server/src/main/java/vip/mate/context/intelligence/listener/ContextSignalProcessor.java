package vip.mate.context.intelligence.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import vip.mate.context.intelligence.event.LlmOverflowSignal;
import vip.mate.context.intelligence.event.LlmSuccessSignal;
import vip.mate.context.intelligence.metrics.ContextIntelMetrics;
import vip.mate.context.intelligence.perception.BackendDiversityRegistry;
import vip.mate.context.intelligence.perception.BackendDiversityTracker;
import vip.mate.context.intelligence.perception.PressureInferencer;
import vip.mate.context.intelligence.persist.PersistRetryQueue;
import vip.mate.context.intelligence.persist.WindowStateRepository;
import vip.mate.context.intelligence.probe.ProbeUpdate;
import vip.mate.context.intelligence.probe.WindowProbeRegistry;
import vip.mate.context.intelligence.snapshot.EnvSnapshotStore;

import java.time.Duration;

/**
 * Signal processor (write path entry).
 * <p>
 * Listens to {@link LlmSuccessSignal} and {@link LlmOverflowSignal}, coordinating state machine + perception + snapshot + persistence.
 * <p>
 * <b>Success path</b> (§5.2 / C.6): {@code @Async("signalExecutor")} asynchronous processing, does not block inference threads.
 * Each sub-step has an independent try-catch, so one failure does not affect others.
 * <p>
 * <b>Overflow path</b> (§5.3): synchronous processing (no @Async), ensuring confidenceUpper is updated before PTL retry.
 *
 * @author MateClaw Team
 */
@Slf4j
@RequiredArgsConstructor
public class ContextSignalProcessor {

    private final WindowProbeRegistry windowProbeRegistry;
    private final BackendDiversityRegistry diversityRegistry;
    private final PressureInferencer pressureInferencer;
    private final EnvSnapshotStore envSnapshotStore;
    private final WindowStateRepository windowStateRepository;
    private final PersistRetryQueue persistRetryQueue;
    /** Observability metrics gateway (§C.7), internal null degradation, no caller null-check needed */
    private final ContextIntelMetrics metrics;

    // ==================== Success path (async) ====================

    /**
     * Handle success signal (§5.2 / C.6).
     * <p>
     * Each sub-step has an independent try-catch, so one failure does not affect others.
     */
    @Async("signalExecutor")
    @EventListener
    public void onSuccess(LlmSuccessSignal event) {
        long startNs = System.nanoTime();
        try {
            String key = WindowProbeRegistry.key(event.provider(), event.modelName());

        // Step 1: WindowProbe state machine update
        ProbeUpdate update = null;
        try {
            update = windowProbeRegistry.recordSuccess(key, event.promptTokens());
        } catch (Exception e) {
            log.debug("[ContextIntel] traceId={} WindowProbe recordSuccess failed: {}",
                    event.traceId(), e.getMessage());
            // §C.7: probe update failed -> signal main effect lost, record as dropped
            metrics.recordSignalDropped("probe_update_failed");
        }

        // Step 2: BackendDiversityTracker update
        try {
            diversityRegistry.recordSuccess(key, event.promptTokens());
        } catch (Exception e) {
            log.debug("[ContextIntel] traceId={} DiversityRegistry failed: {}",
                    event.traceId(), e.getMessage());
        }

        // Step 3: PressureInferencer update
        try {
            pressureInferencer.recordSuccess(event.latencyMs());
        } catch (Exception e) {
            log.debug("[ContextIntel] traceId={} PressureInferencer failed: {}",
                    event.traceId(), e.getMessage());
        }

        // Step 4+5: Refresh EnvSnapshot (only when value changes)
        try {
            if (update != null) {
                BackendDiversityTracker tracker = diversityRegistry.get(key);
                boolean diversity = (tracker != null) && tracker.isDiversityDetected();
                int safeWindow = (tracker != null && diversity) ? tracker.computeSafeWindow() : 0;
                envSnapshotStore.refreshIfChanged(key,
                        update.snapshot().effectiveWindow(),
                        pressureInferencer.currentPressure(),
                        diversity,
                        safeWindow);
            }
        } catch (Exception e) {
            log.debug("[ContextIntel] traceId={} EnvSnapshot refresh failed: {}",
                    event.traceId(), e.getMessage());
        }

        // Step 6: DB persist (only on phase change or first success)
        if (update != null && (update.phaseChanged() || update.snapshot().totalSuccess() == 1)) {
            try {
                windowStateRepository.persist(key, update.snapshot());
            } catch (Exception e) {
                log.debug("[ContextIntel] traceId={} DB persist failed: {}",
                        event.traceId(), e.getMessage());
            }
        }

        // Step 7: Retry queue
        try {
            persistRetryQueue.retryOnce();
        } catch (Exception e) {
            log.debug("[ContextIntel] traceId={} retryOnce failed: {}",
                    event.traceId(), e.getMessage());
        }
        } finally {
            // §C.7: total duration of success signal processing
            metrics.recordSuccessProcess(Duration.ofNanos(System.nanoTime() - startNs));
        }
    }

    // ==================== Overflow path (sync) ====================

    /**
     * Handle overflow signal (§5.3).
     * <p>
     * Synchronous execution (no @Async), ensuring confidenceUpper is updated before PTL retry.
     * Fully wrapped in try-catch; exceptions only logged at debug level (risk 1 mitigation).
     */
    @EventListener
    public void onOverflow(LlmOverflowSignal event) {
        long startNs = System.nanoTime();
        try {
            String key = WindowProbeRegistry.key(event.provider(), event.modelName());

        try {
            // Step 1: WindowProbe state machine update (overflow shrinkage)
            ProbeUpdate update = windowProbeRegistry.recordOverflow(key, event.attemptedTokens());

            // Step 2: BackendDiversityTracker update (v1 Bug 2 fix)
            try {
                diversityRegistry.recordOverflow(key, event.attemptedTokens());
            } catch (Exception e) {
                log.debug("[ContextIntel] traceId={} DiversityRegistry.recordOverflow failed: {}",
                        event.traceId(), e.getMessage());
            }

            // Step 3: PressureInferencer update
            try {
                pressureInferencer.recordOverflow();
            } catch (Exception e) {
                log.debug("[ContextIntel] traceId={} PressureInferencer.recordOverflow failed: {}",
                        event.traceId(), e.getMessage());
            }

            // Step 4: Refresh EnvSnapshot (overflow inevitably shrinks the window, so the value definitely changes)
            try {
                if (update != null) {
                    BackendDiversityTracker tracker = diversityRegistry.get(key);
                    boolean diversity = (tracker != null) && tracker.isDiversityDetected();
                    int safeWindow = (tracker != null && diversity) ? tracker.computeSafeWindow() : 0;
                    envSnapshotStore.refreshIfChanged(key,
                            update.snapshot().effectiveWindow(),
                            pressureInferencer.currentPressure(),
                            diversity,
                            safeWindow);
                }
            } catch (Exception e) {
                log.debug("[ContextIntel] traceId={} EnvSnapshot refresh failed: {}",
                        event.traceId(), e.getMessage());
            }

            // Step 5: DB persist (synchronous, overflow is a low-frequency event)
            if (update != null) {
                try {
                    windowStateRepository.persist(key, update.snapshot());
                } catch (Exception e) {
                    log.debug("[ContextIntel] traceId={} DB persist failed: {}",
                            event.traceId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("[ContextIntel] traceId={} onOverflow failed: {}",
                    event.traceId(), e.getMessage());
            // §C.7: overflow handling failed entirely -> signal dropped
            metrics.recordSignalDropped("overflow_exception");
        }
        } finally {
            // §C.7: total duration of overflow signal processing
            metrics.recordOverflowProcess(Duration.ofNanos(System.nanoTime() - startNs));
        }
    }
}
