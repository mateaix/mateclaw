package vip.mate.goal.model;

import java.time.LocalDateTime;

/** Explicit continuation outcome shared by graph compatibility and durable scheduling. */
public record GoalContinuationDecision(Action action, String prompt, LocalDateTime nextRunAt, String reason) {
    public enum Action { CONTINUE, DEFER, DISABLED, COMPLETE, BUDGET_LIMITED, RETRY }
}
