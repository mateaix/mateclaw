package vip.mate.context.intelligence.snapshot;

import vip.mate.context.intelligence.enums.ResourcePressure;

/**
 * Immutable environment snapshot (sole data source for read path).
 * <p>
 * Copy-on-write: create a new instance to replace the old one whenever a value changes;
 * the read path reads lock-free via {@code AtomicReference.get()}.
 * <p>
 * Field descriptions:
 * <ul>
 *   <li>{@code effectiveWindow} — effective context window estimated by WindowProbe</li>
 *   <li>{@code pressure} — pressure level inferred by PressureInferencer</li>
 *   <li>{@code diversityDetected} — whether BackendDiversityTracker detected multiple backends</li>
 *   <li>{@code safeWindow} — P10 safe window (effective only when diversityDetected=true, acts as a ceiling for effectiveWindow)</li>
 *   <li>{@code lastUpdatedAt} — snapshot timestamp (for freshness checks)</li>
 * </ul>
 *
 * @param effectiveWindow  effective window (0 means no data)
 * @param pressure         pressure level
 * @param diversityDetected whether multiple backends were detected
 * @param safeWindow       P10 safe window (0 when diversityDetected=false)
 * @param lastUpdatedAt    snapshot timestamp (epoch millis, 0 means empty snapshot)
 *
 * @author MateClaw Team
 */
public record EnvSnapshot(
        int effectiveWindow,
        ResourcePressure pressure,
        boolean diversityDetected,
        int safeWindow,
        long lastUpdatedAt
) {

    /** Empty snapshot, indicates no data; read path falls back to yml */
    public static final EnvSnapshot EMPTY =
            new EnvSnapshot(0, ResourcePressure.NORMAL, false, 0, 0);

    /** Whether the snapshot is available (effectiveWindow > 0 and not empty) */
    public boolean isAvailable() {
        return effectiveWindow > 0;
    }
}
