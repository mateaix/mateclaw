package vip.mate.goal.model;

/** Durable scheduling facts returned by one bounded goal segment. */
public sealed interface SegmentOutcome {
    String reason();

    default String finishReason() { return reason(); }
    default boolean awaitingApproval() { return this instanceof AwaitApproval; }
    default boolean evaluationUnavailable() { return this instanceof Retry retry
            && "evaluation".equals(retry.category()); }

    record Continue(String reason) implements SegmentOutcome {}
    record Defer(String reason, java.time.LocalDateTime nextRunAt) implements SegmentOutcome {}
    record Complete(String reason) implements SegmentOutcome {}
    record AwaitApproval(String reason) implements SegmentOutcome {}
    record WaitInput(String reason) implements SegmentOutcome {}
    record Retry(String category, String reason) implements SegmentOutcome {}
    record Blocked(String category, String reason) implements SegmentOutcome {}
    record Cancelled(String reason) implements SegmentOutcome {}
}
