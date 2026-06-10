package vip.mate.goal.model;

import java.util.List;

/**
 * Evaluator output for the <b>bootstrap</b> round — the first evaluation of a
 * goal that has no criteria yet.
 *
 * <p>When {@code mate_agent_goal.criteria} is empty there is nothing to score
 * by id, so the evaluator instead decomposes the goal (title / description /
 * exit criteria) into a full checklist with text. The service persists this
 * as the goal's initial criteria (all {@code passed=false}); completion is
 * not judged on the bootstrap round.
 *
 * <p>Distinct from {@link GoalChecklistVerdict}, which is the per-round delta
 * used once the checklist already exists.
 */
public record GoalCriteriaDraft(List<GoalCriterion> criteria) {
}
