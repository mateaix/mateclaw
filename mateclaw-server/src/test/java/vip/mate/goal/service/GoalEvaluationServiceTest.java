package vip.mate.goal.service;

import org.junit.jupiter.api.Test;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the PR2 stub evaluator: deterministic continue + safe fallback
 * paths. The LLM-backed evaluator lands in PR5; until then any "real"
 * evaluation always returns continue without charging eval_llm_calls.
 */
class GoalEvaluationServiceTest {

    private final GoalProperties props = new GoalProperties();
    private final GoalEvaluationService svc = new GoalEvaluationService(props);

    private GoalEntity goal() {
        GoalEntity g = new GoalEntity();
        g.setId(1L);
        g.setTitle("ship the blog");
        g.setStatus(GoalStatus.ACTIVE);
        return g;
    }

    @Test
    void nullGoal_returnsFallback() {
        GoalEvaluationResult r = svc.evaluate(null, List.of(), "anything");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
        assertFalse(r.completed());
        assertEquals(0, r.llmCallsConsumed());
    }

    @Test
    void emptyAnswer_returnsFallback() {
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "");
        assertEquals(GoalEvaluationResult.DECISION_FALLBACK, r.decision());
    }

    @Test
    void normalCall_returnsContinue() {
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "the answer text");
        assertNotNull(r);
        assertEquals(GoalEvaluationResult.DECISION_CONTINUE, r.decision());
        assertFalse(r.completed());
        assertEquals(0, r.llmCallsConsumed(),
                "PR2 stub must not charge eval_llm_calls — that path lands in PR5");
    }

    @Test
    void evaluatorModel_defaultsToStub_whenPropertyBlank() {
        props.setEvaluatorModel("");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "the answer text");
        assertEquals("stub", r.evaluatorModel());
    }

    @Test
    void evaluatorModel_carriesPropertyValue_whenConfigured() {
        props.setEvaluatorModel("qwen-turbo");
        GoalEvaluationResult r = svc.evaluate(goal(), List.of(), "the answer text");
        assertEquals("qwen-turbo", r.evaluatorModel());
    }

    @Test
    void fallback_doesNotChargeLlmCalls() {
        GoalEvaluationResult r = GoalEvaluationResult.fallback("evaluator_unavailable");
        assertEquals(0, r.llmCallsConsumed());
        assertFalse(r.completed());
        assertTrue(r.gap().contains("evaluator unavailable"));
    }
}
