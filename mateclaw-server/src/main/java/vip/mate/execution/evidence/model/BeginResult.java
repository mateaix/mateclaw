package vip.mate.execution.evidence.model;

/** Only the successful inserter may initiate a new execution. */
public record BeginResult(ExecutionAttempt attempt, boolean created) { }
