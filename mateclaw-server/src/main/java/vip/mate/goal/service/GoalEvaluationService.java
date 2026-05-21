package vip.mate.goal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;

import java.util.List;

/**
 * Evaluates whether the agent's latest reply satisfies the goal's exit
 * criteria. PR2 ships a deterministic "always continue" stub plus a
 * passthrough completion gate; PR5 wires this through to a real LLM call.
 *
 * <p>The split lets PR2 unblock the graph topology + dispatcher work
 * without committing to a particular evaluator model. The stub is
 * safe-by-default: it never marks a goal completed and never charges
 * eval_llm_calls.
 */
@Slf4j
@Service
public class GoalEvaluationService {

    private final GoalProperties properties;

    public GoalEvaluationService(GoalProperties properties) {
        this.properties = properties;
    }

    /**
     * Evaluate one turn's terminal answer against the goal's exit criteria.
     *
     * <p>Returns {@link GoalEvaluationResult#fallback(String)} when the
     * evaluator is unavailable so the calling node can skip the bookkeeping
     * delta path. PR5 swaps this implementation with a real LLM call;
     * until then we never block the user on evaluator latency.
     */
    public GoalEvaluationResult evaluate(GoalEntity goal,
                                         List<?> recentMessages,
                                         String terminalAnswer) {
        if (goal == null) {
            return GoalEvaluationResult.fallback("no_goal");
        }
        if (terminalAnswer == null || terminalAnswer.isBlank()) {
            return GoalEvaluationResult.fallback("empty_answer");
        }
        // PR2 placeholder: deterministic continue with a passthrough gap.
        // Real evaluator lands in PR5 — see RFC 48 §3.9.
        String model = properties.getEvaluatorModel();
        if (model == null || model.isBlank()) {
            model = "stub";
        }
        log.debug("[GoalEvaluation] stub evaluate goal={} answerChars={} -> decision=continue",
                goal.getId(), terminalAnswer.length());
        return new GoalEvaluationResult(
                0.0,
                "(stub evaluator — wired in PR5)",
                GoalEvaluationResult.DECISION_CONTINUE,
                false,
                model,
                0,
                0L);
    }
}
