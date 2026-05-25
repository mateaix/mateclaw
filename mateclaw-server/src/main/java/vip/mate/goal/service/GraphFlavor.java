package vip.mate.goal.service;

/**
 * Which graph topology a {@code GoalEvaluationNode} instance is wired into.
 *
 * <p>The same node class serves both graphs but needs to know how to
 * clear graph-specific state on follow-up: ReAct only touches
 * {@code FINAL_ANSWER} + {@code FINISH_REASON}, while Plan-Execute has
 * a wider set of mid-pass + terminal state to wipe before a re-plan.
 *
 * <p>Builder-time decision; the value is captured in the node constructor
 * and never read from graph state.
 */
public enum GraphFlavor {
    REACT,
    PLAN_EXECUTE
}
