package vip.mate.execution.evidence.model;

import java.time.Instant;

/** Allowlisted execution metadata; raw invocation parameters are never accepted. */
public record EvidenceObservation(String sourceKey, EvidenceKind kind, EvidenceResult result,
        SourceLevel sourceLevel, Long scopeId, Long generation, String inputFingerprint,
        String recipeId, Long recipeRevision, String checkScope, String artifactRef,
        String artifactDigest, String summary, String payloadRef, Instant observedAt, Instant expiresAt) {
    public EvidenceObservation(String sourceKey, EvidenceKind kind, EvidenceResult result,
            SourceLevel sourceLevel, String summary) {
        this(sourceKey, kind, result, sourceLevel, null, null, null, null, null, null,
                null, null, summary, null, null, null);
    }
}
