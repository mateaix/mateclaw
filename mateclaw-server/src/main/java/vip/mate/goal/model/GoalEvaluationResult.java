package vip.mate.goal.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Value object carrying one evaluation pass result from
 * {@code GoalEvaluationService} to {@code GoalEvaluationNode} and on to
 * {@code GoalService.recordEvaluation}.
 *
 * <p>Defined in PR1 so the service-layer signature is stable; the actual
 * evaluator implementation lands in PR2.
 *
 * <p>{@link #completed} means "evaluator judged this turn satisfies all
 * exit criteria". It does not mean "graph FINISH_REASON should change" —
 * goal status and graph FinishReason are independent (RFC 48 §3.1 v2).
 *
 * <p>{@link #llmCallsConsumed} is the evaluator-side delta only; the
 * agent-side delta is read from graph state by the node itself.
 */
public record GoalEvaluationResult(
        double score,
        String gap,
        String decision,
        boolean completed,
        String evaluatorModel,
        int llmCallsConsumed,
        long latencyMs) {

    public static final String DECISION_COMPLETED = "completed";
    public static final String DECISION_CONTINUE = "continue";
    public static final String DECISION_FALLBACK = "fallback";

    /** Failure fallback used when the evaluator LLM call errors out.
     *  Does NOT charge eval_llm_calls_used. */
    public static GoalEvaluationResult fallback(String reason) {
        return new GoalEvaluationResult(
                0.0, "evaluator unavailable: " + reason,
                DECISION_FALLBACK, false,
                "", 0, 0L);
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
        return m;
    }
}
