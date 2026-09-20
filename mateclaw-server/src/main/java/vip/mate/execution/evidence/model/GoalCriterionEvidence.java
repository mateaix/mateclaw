package vip.mate.execution.evidence.model;

import java.time.Instant;

/** Versioned binding metadata; creating a binding requires separate source authorization. */
public record GoalCriterionEvidence(Long id, Long workspaceId, Long goalId, String criterionId,
        long criterionRevision, Long evidenceId, Instant boundAt) { }
