package vip.mate.goal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Decides whether to inject a follow-up user prompt for the next graph
 * pass. PR2 wires the plumbing; the actual "yes, continue" path defaults
 * to off until PR5 flips {@code mateclaw.goal.enabled=true} and operators
 * opt their goals in via {@code auto_followup_enabled}.
 */
@Slf4j
@Service
public class GoalFollowupService {

    /**
     * Build the follow-up prompt to inject, or empty when no follow-up
     * should fire this turn. Conditions follow RFC 48 §3.10:
     * <ol>
     *   <li>{@code autoFollowupEnabled} is true.</li>
     *   <li>Evaluator decision is "continue" with score &lt; 0.95.</li>
     *   <li>Cooldown since the last follow-up has elapsed.</li>
     *   <li>turn_budget has at least one slot left after this turn.</li>
     *   <li>(agent + eval) LLM calls below 90 % of llm_call_budget.</li>
     * </ol>
     */
    public Optional<String> maybeBuildFollowup(GoalEntity goal,
                                                GoalEvaluationResult result) {
        if (goal == null || result == null) return Optional.empty();
        if (!Boolean.TRUE.equals(goal.getAutoFollowupEnabled())) return Optional.empty();
        if (!GoalEvaluationResult.DECISION_CONTINUE.equals(result.decision())) {
            return Optional.empty();
        }
        if (result.score() >= 0.95) return Optional.empty();

        // Cooldown — last_followup_at recorded by recordFollowupInjected().
        Integer cooldownSec = goal.getFollowupCooldownSeconds();
        if (cooldownSec != null && cooldownSec > 0 && goal.getLastFollowupAt() != null) {
            Duration since = Duration.between(goal.getLastFollowupAt(), LocalDateTime.now());
            if (since.getSeconds() < cooldownSec) {
                log.debug("[GoalFollowup] cooldown not elapsed: {}s < {}s", since.getSeconds(), cooldownSec);
                return Optional.empty();
            }
        }

        int turnsUsed = goal.getTurnsUsed() != null ? goal.getTurnsUsed() : 0;
        int turnBudget = goal.getTurnBudget() != null ? goal.getTurnBudget() : Integer.MAX_VALUE;
        // Leave at least one turn slot for the real user — refuse to burn
        // the final slot on an auto-followup that the user can't watch.
        if (turnsUsed >= turnBudget - 1) return Optional.empty();

        int callBudget = goal.getLlmCallBudget() != null ? goal.getLlmCallBudget() : Integer.MAX_VALUE;
        if (goal.totalLlmCallsUsed() >= (int) (callBudget * 0.9)) return Optional.empty();

        String gap = result.gap();
        if (gap == null || gap.isBlank()) gap = "the goal is not yet complete.";
        String prompt = "Continue working on the goal. Still missing: " + gap
                + "\nTake the next concrete step.";
        return Optional.of(prompt);
    }
}
