package vip.mate.context.intelligence.probe;

/**
 * Return value of recordSuccess / recordOverflow.
 * <p>
 * Captures snapshot + previousPhase within the {@code ConcurrentHashMap.compute} lock,
 * and returns them to the processor for out-of-lock reading of effectiveWindow (step 5) and deciding
 * whether to persist (step 6). The processor is stateless and does not need to cache the previous phase —
 * it is obtained from the return value on each call.
 *
 * @param snapshot      the post-update state snapshot
 * @param previousPhase the pre-update phase
 * @author MateClaw Team
 */
public record ProbeUpdate(
        WindowProbeSnapshot snapshot,
        WindowProbe.Phase previousPhase
) {
    /** Whether the phase has changed (used to decide whether DB persistence is needed) */
    public boolean phaseChanged() {
        return snapshot.phase() != previousPhase;
    }
}
