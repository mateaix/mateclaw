package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import vip.mate.goal.model.GoalContinuationDecision.Action;

/**
 * Covers the follow-up gating conditions. Every negative case must
 * independently block the follow-up.
 */
class GoalFollowupServiceTest {

    private final GoalProperties properties = new GoalProperties();
    private final GoalFollowupService svc = new GoalFollowupService(properties, new ObjectMapper());

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
                "stub", 0, 0L,
                java.util.List.of(), null);
    }

    @Test
    void disabledAutoFollowup_returnsEmpty() {
        Optional<String> out = svc.maybeBuildFollowup(
                goal(false),
                res(0.6, GoalEvaluationResult.DECISION_CONTINUE));
        assertTrue(out.isEmpty());
    }

    @Test
    void allowAutoFollowupGate_overridesPerGoalFlag() {
        properties.setAllowAutoFollowup(false);
        try {
            // per-goal flag on + budget healthy, yet the runtime hard gate wins.
            Optional<String> out = svc.maybeBuildFollowup(
                    goal(true),
                    res(0.6, GoalEvaluationResult.DECISION_CONTINUE));
            assertTrue(out.isEmpty());
        } finally {
            properties.setAllowAutoFollowup(true);
        }
    }

    @Test
    void completedDecision_returnsEmpty() {
        Optional<String> out = svc.maybeBuildFollowup(
                goal(true),
                res(0.99, GoalEvaluationResult.DECISION_COMPLETED));
        assertTrue(out.isEmpty());
    }

    @Test
    void highScoreButStillContinue_followsUp() {
        // No score gate: completion is decided by decision==completed, not a
        // numeric threshold. A 20/21 goal (score ~0.95) that is still "continue"
        // has remaining criteria and MUST follow up.
        Optional<String> out = svc.maybeBuildFollowup(
                goal(true),
                res(0.96, GoalEvaluationResult.DECISION_CONTINUE));
        assertTrue(out.isPresent());
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
    private GoalEntity persistentGoal() {
        GoalEntity goal = goal(true);
        goal.setPersistentExecution(true);
        goal.setTurnBudget(0);
        goal.setLlmCallBudget(0);
        return goal;
    }

    @Test
    void persistentUnlimitedBudgetsContinue() {
        assertEquals(Action.CONTINUE, svc.decide(persistentGoal(),
                res(0.6, GoalEvaluationResult.DECISION_CONTINUE), LocalDateTime.now()).action());
    }

    @Test
    void persistentMayUseLastTurn_andEntireCallBudget() {
        GoalEntity goal = persistentGoal();
        goal.setTurnBudget(6);
        goal.setLlmCallBudget(35);
        var result = res(0.6, GoalEvaluationResult.DECISION_CONTINUE);
        assertEquals(Action.CONTINUE, svc.decide(goal, result, LocalDateTime.now()).action());
        goal.setTurnsUsed(6);
        assertEquals(Action.BUDGET_LIMITED, svc.decide(goal, result, LocalDateTime.now()).action());
        goal.setTurnsUsed(5);
        goal.setEvalLlmCallsUsed(5);
        assertEquals(Action.BUDGET_LIMITED, svc.decide(goal, result, LocalDateTime.now()).action());
    }

    @Test
    void cooldownReturnsExactDeadline_andContinuesAtDeadline() {
        GoalEntity goal = persistentGoal();
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        goal.setFollowupCooldownSeconds(60);
        goal.setLastFollowupAt(now.minusSeconds(10));
        var result = res(0.6, GoalEvaluationResult.DECISION_CONTINUE);
        var deferred = svc.decide(goal, result, now);
        assertEquals(Action.DEFER, deferred.action());
        assertEquals(now.plusSeconds(50), deferred.nextRunAt());
        assertNotNull(deferred.prompt());
        assertEquals(Action.CONTINUE, svc.decide(goal, result, now.plusSeconds(50)).action());
    }

    @Test
    void fallbackIsRetry_andNeverSuccessfulCompletion() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        var decision = svc.decide(persistentGoal(), GoalEvaluationResult.fallback("network"), now);
        assertEquals(Action.RETRY, decision.action());
        assertNotNull(decision.nextRunAt());
        assertTrue(decision.nextRunAt().isAfter(now));
        assertTrue(decision.reason().contains("network"));
        assertTrue(svc.maybeBuildFollowup(persistentGoal(), GoalEvaluationResult.fallback("network")).isEmpty());
    }

    @Test
    void inactiveAndDisabledGoalsNeverContinue() {
        GoalEntity goal = persistentGoal();
        var result = res(0.6, GoalEvaluationResult.DECISION_CONTINUE);
        LocalDateTime now = LocalDateTime.now();
        for (GoalStatus status : new GoalStatus[]{GoalStatus.PAUSED, GoalStatus.ABANDONED, GoalStatus.EXHAUSTED}) {
            goal.setStatus(status);
            assertEquals(Action.DISABLED, svc.decide(goal, result, now).action());
        }
        goal.setStatus(GoalStatus.COMPLETED);
        assertEquals(Action.COMPLETE, svc.decide(goal, result, now).action());
        goal.setStatus(GoalStatus.ACTIVE);
        goal.setAutoFollowupEnabled(false);
        assertEquals(Action.DISABLED, svc.decide(goal, result, now).action());
        goal.setAutoFollowupEnabled(true);
        properties.setEnabled(false);
        assertEquals(Action.DISABLED, svc.decide(goal, result, now).action());
    }

    @Test
    void authoritativeCompletionWinsAtBudgetLimit() {
        GoalEntity goal = persistentGoal();
        goal.setTurnBudget(5);
        goal.setCriteria("[{\"id\":\"C1\",\"text\":\"deployed\",\"passed\":true,\"evidence\":\"verified\"}]");
        var result = new GoalEvaluationResult(1, "verified", GoalEvaluationResult.DECISION_COMPLETED,
                true, "stub", 1, 0, java.util.List.of(), null);
        assertEquals(Action.COMPLETE, svc.decide(goal, result, LocalDateTime.now()).action());
    }

    @Test
    void promptPreservesObjectiveAndRemainingChecklist_andGuardsSideEffects() {
        GoalEntity goal = persistentGoal();
        goal.setDescription("Deploy the original blog");
        goal.setExitCriteria("Public URL responds");
        goal.setCriteria("[{\"id\":\"C1\",\"text\":\"already satisfied\",\"passed\":true,\"evidence\":\"verified\"},"
                + "{\"id\":\"C2\",\"text\":\"verify production\",\"passed\":false}]");
        String prompt = svc.decide(goal, res(0.6, GoalEvaluationResult.DECISION_CONTINUE),
                LocalDateTime.now()).prompt();
        assertTrue(prompt.contains("ship"));
        assertTrue(prompt.contains("Deploy the original blog"));
        assertTrue(prompt.contains("Public URL responds"));
        assertTrue(prompt.contains("verify production"));
        assertFalse(prompt.contains("already satisfied"));
        assertTrue(prompt.contains("original objective"));
        assertTrue(prompt.contains("authoritative state"));
        assertTrue(prompt.contains("async handles"));
        assertTrue(prompt.contains("side effects"));
    }

    @Test
    void promptHasBoundedSize_evenForLargeGoalAndChecklist() {
        GoalEntity goal = persistentGoal();
        goal.setTitle("T".repeat(10000));
        goal.setDescription("D".repeat(10000));
        goal.setExitCriteria("E".repeat(10000));
        goal.setCriteria("[{\"id\":\"C1\",\"text\":\"" + "C".repeat(20000) + "\",\"passed\":false}]");
        String prompt = svc.decide(goal, res(0.6, GoalEvaluationResult.DECISION_CONTINUE),
                LocalDateTime.now()).prompt();
        assertTrue(prompt.length() <= 12000);
        assertTrue(prompt.contains("authoritative state"));
        assertTrue(prompt.contains("next concrete step"));
    }
    @Test
    void passedCriterionWithoutEvidenceRemainsInPersistentPrompt() {
        GoalEntity goal = persistentGoal();
        goal.setCriteria("[{\"id\":\"C1\",\"text\":\"verify deployed endpoint\",\"passed\":true,\"evidence\":\"  \"}]");
        var decision = svc.decide(goal, res(1, GoalEvaluationResult.DECISION_CONTINUE), LocalDateTime.now());
        assertEquals(Action.CONTINUE, decision.action());
        assertTrue(decision.prompt().contains("verify deployed endpoint"));
    }
    @Test
    void persistentCompletionDecisionRetriesWithoutAuthoritativeEvidence() {
        GoalEntity goal = persistentGoal();
        goal.setCriteria("[{\"id\":\"C1\",\"text\":\"deployed\",\"passed\":true,\"evidence\":\"\"}]");
        var result = new GoalEvaluationResult(1, "claimed complete", GoalEvaluationResult.DECISION_COMPLETED,
                true, "stub", 1, 0, java.util.List.of(), null);
        var decision = svc.decide(goal, result, LocalDateTime.now());
        assertEquals(Action.RETRY, decision.action());
        assertEquals("completion_not_verified", decision.reason());
        assertTrue(decision.prompt().contains("deployed"));
    }
    @Test
    void persistentPromptExplainsEssentialInputBoundary() {
        String prompt = svc.decide(persistentGoal(), res(0.5, GoalEvaluationResult.DECISION_CONTINUE),
                LocalDateTime.now()).prompt();
        assertTrue(prompt.contains("waitForGoalInput"));
        assertTrue(prompt.contains("essential input"));
        assertTrue(prompt.contains("permission"));
        assertTrue(prompt.contains("difficulty"));
        assertTrue(prompt.contains("time"));
    }
}
