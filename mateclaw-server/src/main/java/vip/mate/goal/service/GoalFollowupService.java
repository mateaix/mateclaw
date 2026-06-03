package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalCriteriaCodec;
import vip.mate.goal.model.GoalCriterion;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether to inject a follow-up user prompt for the next graph pass,
 * driving the autonomous "continue until the checklist is complete" loop.
 */
@Slf4j
@Service
public class GoalFollowupService {

    private final GoalProperties properties;
    private final ObjectMapper objectMapper;

    public GoalFollowupService(GoalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Build the follow-up prompt to inject, or empty when no follow-up should
     * fire this turn. Gating order:
     * <ol>
     *   <li>{@code allow-auto-followup} runtime hard gate (operator kill
     *       switch; overrides per-goal flag).</li>
     *   <li>Per-goal {@code autoFollowupEnabled}.</li>
     *   <li>Evaluator decision is "continue" with score &lt; 0.95.</li>
     *   <li>Cooldown since the last follow-up has elapsed.</li>
     *   <li>turn_budget has at least one slot left after this turn.</li>
     *   <li>(agent + eval) LLM calls below 90% of llm_call_budget.</li>
     * </ol>
     */
    public Optional<String> maybeBuildFollowup(GoalEntity goal,
                                               GoalEvaluationResult result) {
        if (goal == null || result == null) return Optional.empty();
        // Runtime hard gate first — overrides any per-goal flag.
        if (!properties.isAllowAutoFollowup()) return Optional.empty();
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
        // Leave at least one turn slot for the real user — refuse to burn the
        // final slot on an auto-followup the user can't watch.
        if (turnsUsed >= turnBudget - 1) return Optional.empty();

        int callBudget = goal.getLlmCallBudget() != null ? goal.getLlmCallBudget() : Integer.MAX_VALUE;
        if (goal.totalLlmCallsUsed() >= (int) (callBudget * 0.9)) return Optional.empty();

        return Optional.of(buildPrompt(goal, result));
    }

    /**
     * Prefer a concrete remaining-criteria list when the goal has a checklist;
     * fall back to the free-text gap otherwise. Both end with the same "take
     * the next concrete step" instruction.
     */
    private String buildPrompt(GoalEntity goal, GoalEvaluationResult result) {
        List<GoalCriterion> all = GoalCriteriaCodec.parse(goal.getCriteria(), objectMapper);
        List<GoalCriterion> remaining = GoalCriteriaCodec.remaining(all);
        if (!remaining.isEmpty()) {
            int total = all.size();
            int passed = total - remaining.size();
            StringBuilder sb = new StringBuilder();
            sb.append("Continue working toward the goal. ")
              .append(passed).append('/').append(total).append(" criteria passed. Remaining:\n");
            for (GoalCriterion c : remaining) {
                sb.append("  - ").append(c.text()).append('\n');
            }
            sb.append("Take the next concrete step on the remaining criteria.");
            return sb.toString();
        }
        String gap = result.gap();
        if (gap == null || gap.isBlank()) gap = "the goal is not yet complete.";
        return "Continue working on the goal. Still missing: " + gap
                + "\nTake the next concrete step.";
    }
}
