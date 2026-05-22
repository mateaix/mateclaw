package vip.mate.goal.service;

import org.junit.jupiter.api.Test;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the five follow-up gating conditions from RFC 48 §3.10. Every
 * negative case must independently block the follow-up.
 */
class GoalFollowupServiceTest {

    private final GoalFollowupService svc = new GoalFollowupService();

    private GoalEntity goal(boolean autoEnabled) {
        GoalEntity g = new GoalEntity();
        g.setId(1L);
        g.setTitle("ship");
        g.setStatus(GoalStatus.ACTIVE);
        g.setTurnBudget(20);
        g.setTurnsUsed(5);
        g.setLlmCallBudget(200);
        g.setAgentLlmCallsUsed(30);
        g.setEvalLlmCallsUsed(4);
        g.setAutoFollowupEnabled(autoEnabled);
        g.setFollowupCooldownSeconds(0);
        return g;
    }

    private GoalEvaluationResult res(double score, String decision) {
        return new GoalEvaluationResult(
                score, "missing X",
                decision, false,
                "stub", 0, 0L);
    }

    @Test
    void disabledAutoFollowup_returnsEmpty() {
        Optional<String> out = svc.maybeBuildFollowup(
                goal(false),
                res(0.6, GoalEvaluationResult.DECISION_CONTINUE));
        assertTrue(out.isEmpty());
    }

    @Test
    void completedDecision_returnsEmpty() {
        Optional<String> out = svc.maybeBuildFollowup(
                goal(true),
                res(0.99, GoalEvaluationResult.DECISION_COMPLETED));
        assertTrue(out.isEmpty());
    }

    @Test
    void highScore_returnsEmpty() {
        Optional<String> out = svc.maybeBuildFollowup(
                goal(true),
                res(0.96, GoalEvaluationResult.DECISION_CONTINUE));
        assertTrue(out.isEmpty());
    }

    @Test
    void cooldownNotElapsed_returnsEmpty() {
        GoalEntity g = goal(true);
        g.setFollowupCooldownSeconds(60);
        g.setLastFollowupAt(LocalDateTime.now().minusSeconds(10));
        Optional<String> out = svc.maybeBuildFollowup(
                g, res(0.6, GoalEvaluationResult.DECISION_CONTINUE));
        assertTrue(out.isEmpty());
    }

    @Test
    void cooldownElapsed_allowsFollowup() {
        GoalEntity g = goal(true);
        g.setFollowupCooldownSeconds(60);
        g.setLastFollowupAt(LocalDateTime.now().minusSeconds(120));
        Optional<String> out = svc.maybeBuildFollowup(
                g, res(0.6, GoalEvaluationResult.DECISION_CONTINUE));
        assertTrue(out.isPresent());
    }

    @Test
    void nearTurnBudget_returnsEmpty() {
        GoalEntity g = goal(true);
        g.setTurnBudget(20);
        g.setTurnsUsed(19);  // only one slot left — reserved for the real user
        Optional<String> out = svc.maybeBuildFollowup(
                g, res(0.6, GoalEvaluationResult.DECISION_CONTINUE));
        assertTrue(out.isEmpty());
    }

    @Test
    void over90PercentLlmBudget_returnsEmpty() {
        GoalEntity g = goal(true);
        g.setLlmCallBudget(100);
        g.setAgentLlmCallsUsed(85);
        g.setEvalLlmCallsUsed(10);  // total 95 = 95% > 90% guard
        Optional<String> out = svc.maybeBuildFollowup(
                g, res(0.6, GoalEvaluationResult.DECISION_CONTINUE));
        assertTrue(out.isEmpty());
    }

    @Test
    void happyPath_returnsPrompt_containingGap() {
        Optional<String> out = svc.maybeBuildFollowup(
                goal(true),
                res(0.6, GoalEvaluationResult.DECISION_CONTINUE));
        assertTrue(out.isPresent());
        assertTrue(out.get().contains("missing X"));
        assertTrue(out.get().toLowerCase().contains("next concrete step"));
    }
}
