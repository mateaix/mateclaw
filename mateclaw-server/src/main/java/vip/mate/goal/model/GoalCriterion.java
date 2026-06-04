package vip.mate.goal.model;

/**
 * One checkable item of a goal's exit checklist — the persistent unit.
 *
 * <p>Stored as part of the JSON array in {@code mate_agent_goal.criteria}
 * and surfaced to clients as an element of {@code GoalResponse.criteria}.
 * Completion of a goal is derived from "every criterion passed" rather than
 * a fuzzy completion score.
 *
 * @param id       stable identifier ({@code C1}, {@code C2}, ...), assigned
 *                 by the service on create/append; callers never mint ids
 * @param text     the criterion statement (human + LLM readable)
 * @param passed   whether the evaluator has judged this criterion satisfied
 * @param evidence concrete justification for {@code passed} (an output line,
 *                 a file excerpt, a command result); empty until evaluated
 */
public record GoalCriterion(String id, String text, boolean passed, String evidence) {
}
