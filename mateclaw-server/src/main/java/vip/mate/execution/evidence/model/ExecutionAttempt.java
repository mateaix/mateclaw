package vip.mate.execution.evidence.model;

import java.time.Instant;

public record ExecutionAttempt(Long id, ExecutionIdentity identity, AttemptState state, EffectOutcome effectOutcome,
        Instant startedAt, Instant finishedAt) { }
