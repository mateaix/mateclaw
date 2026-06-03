package vip.mate.goal.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Value object carrying one evaluation pass result from
 * {@code GoalEvaluationService} to {@code GoalEvaluationNode} and on to
 * {@code GoalService.recordEvaluation}.
 *
 * <p>{@link #completed} means "evaluator judged this turn satisfies every
 * exit criterion". It does not mean "graph FINISH_REASON should change" —
 * goal status and graph FinishReason are independent.
 *
 * <p>{@link #llmCallsConsumed} is the evaluator-side delta only; the
 * agent-side delta is read from graph state by the node itself.
 *
 * <p>{@link #criterionVerdicts} and {@link #bootstrapCriteria} are mutually
 * exclusive carriers for the checklist:
 * <ul>
 *   <li><b>verdict round</b> (checklist already exists): {@code criterionVerdicts}
 *       holds the per-criterion delta (by id), {@code bootstrapCriteria} is null.</li>
 *   <li><b>bootstrap round</b> (no criteria yet): {@code bootstrapCriteria}
 *       holds the freshly decomposed full checklist, {@code criterionVerdicts}
 *       is empty.</li>
 * </ul>
 * Neither is the outward-facing full list — clients always receive the merged
 * checklist via {@code GoalResponse.criteria}.
 */
public record GoalEvaluationResult(
        double score,
        String gap,
        String decision,
        boolean completed,
        String evaluatorModel,
        int llmCallsConsumed,
        long latencyMs,
        List<GoalChecklistVerdict.CriterionVerdict> criterionVerdicts,
        List<GoalCriterion> bootstrapCriteria) {

    public static final String DECISION_COMPLETED = "completed";
    public static final String DECISION_CONTINUE = "continue";
    public static final String DECISION_FALLBACK = "fallback";

    /** Failure fallback used when the evaluator LLM call errors out.
     *  Does NOT charge eval_llm_calls_used. */
    public static GoalEvaluationResult fallback(String reason) {
        return new GoalEvaluationResult(
                0.0, "evaluator unavailable: " + reason,
                DECISION_FALLBACK, false,
                "", 0, 0L,
                List.of(), null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("completionScore", score);
        m.put("gap", gap == null ? "" : gap);
        m.put("decision", decision);
        m.put("completed", completed);
        m.put("evaluatorModel", evaluatorModel == null ? "" : evaluatorModel);
        m.put("llmCallsConsumed", llmCallsConsumed);
        m.put("latencyMs", latencyMs);
        // Per-round delta, for debugging/detail only. UI progress is driven by
        // the full GoalResponse.criteria array, never reconstructed from this.
        m.put("criterionVerdicts", criterionVerdicts == null ? List.of() : criterionVerdicts);
        return m;
    }
}
