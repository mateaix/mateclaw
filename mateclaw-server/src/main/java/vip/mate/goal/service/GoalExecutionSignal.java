package vip.mate.goal.service;

/** Explicit user controls, distinct from a graph segment reaching its limit. */
public final class GoalExecutionSignal {
    private GoalExecutionSignal() {}
    public record Stop(String conversationId) {}
    public record Resume(Long goalId) {}
    public record TurnFinished(String conversationId) {}
}
