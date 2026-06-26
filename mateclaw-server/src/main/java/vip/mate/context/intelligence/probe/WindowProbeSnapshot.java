package vip.mate.context.intelligence.probe;

import java.time.Instant;

/**
 * WindowProbe state snapshot (for DB persistence and out-of-lock reads).
 * <p>
 * Captured within the {@code ConcurrentHashMap.compute} lock via {@code WindowProbe.snapshot()},
 * and passed outside the lock for DB writes and snapshot refresh.
 * <p>
 * samples / sampleIdx / sampleCount are volatile data and are not included in the snapshot.
 *
 * @param phase              current phase
 * @param effectiveWindow    effective window
 * @param confidenceLower    confidence lower bound
 * @param confidenceUpper    confidence upper bound
 * @param declaredLimit      model declared limit (0 means unknown)
 * @param peakObserved       historical max successful token count
 * @param successiveSuccess  successive success count
 * @param successiveOverflow successive overflow count
 * @param totalSuccess       total success count
 * @param totalOverflow      total overflow count
 * @param lastSuccessAt      last success time
 * @param lastOverflowAt     last overflow time
 * @param lastUpdatedAt      last update time
 *
 * @author MateClaw Team
 */
public record WindowProbeSnapshot(
        WindowProbe.Phase phase,
        int effectiveWindow,
        int confidenceLower,
        int confidenceUpper,
        int declaredLimit,
        int peakObserved,
        int successiveSuccess,
        int successiveOverflow,
        int totalSuccess,
        int totalOverflow,
        Instant lastSuccessAt,
        Instant lastOverflowAt,
        Instant lastUpdatedAt
) {}
