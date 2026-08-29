package vip.mate.goal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalContinuationDecision;
import vip.mate.goal.model.GoalContinuationDecision.Action;
import vip.mate.goal.model.GoalCriteriaCodec;
import vip.mate.goal.model.GoalCriterion;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Shared continuation policy for bounded graph passes and durable scheduling. */
@Service
public class GoalFollowupService {

    private static final int EVALUATION_RETRY_SECONDS = 30;
    private final GoalProperties properties;
    private final ObjectMapper objectMapper;

    public GoalFollowupService(GoalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Pure decision: callers persist due times and perform state transitions. */
    public GoalContinuationDecision decide(GoalEntity goal, GoalEvaluationResult result, LocalDateTime now) {
        if (goal == null) return decision(Action.DISABLED, null, null, "goal_missing");
        if (goal.getStatus() == GoalStatus.COMPLETED) {
            return decision(Action.COMPLETE, null, null, "goal_completed");
        }
        if (goal.getStatus() != GoalStatus.ACTIVE) {
            return decision(Action.DISABLED, null, null, "goal_not_active");
        }
        if (!properties.isEnabled() || !properties.isAllowAutoFollowup()
                || !Boolean.TRUE.equals(goal.getAutoFollowupEnabled())) {
            return decision(Action.DISABLED, null, null, "auto_followup_disabled");
        }
        boolean fallback = result == null || GoalEvaluationResult.DECISION_FALLBACK.equals(result.decision());
        // A fallback cannot prove completion, even if a malformed caller sets completed=true.
        boolean persistent = Boolean.TRUE.equals(goal.getPersistentExecution());
        boolean claimedComplete = !fallback && (result.completed()
                || GoalEvaluationResult.DECISION_COMPLETED.equals(result.decision()));
        boolean completionUnverified = claimedComplete && persistent && !hasVerifiedChecklist(goal);
        if (claimedComplete && !completionUnverified) {
            return decision(Action.COMPLETE, null, null, "criteria_completed");
        }

        int turns = goal.getTurnsUsed() != null ? goal.getTurnsUsed() : 0;
        int turnBudget = goal.getTurnBudget() != null ? goal.getTurnBudget() : Integer.MAX_VALUE;
        if ((persistent && turnBudget != 0 && turns >= turnBudget)
                || (!persistent && turns >= turnBudget - 1)) {
            return decision(Action.BUDGET_LIMITED, null, null, "turn_budget");
        }
        int callBudget = goal.getLlmCallBudget() != null ? goal.getLlmCallBudget() : Integer.MAX_VALUE;
        if ((persistent && callBudget != 0 && goal.totalLlmCallsUsed() >= callBudget)
                || (!persistent && goal.totalLlmCallsUsed() >= (int) (callBudget * 0.9))) {
            return decision(Action.BUDGET_LIMITED, null, null, "llm_call_budget");
        }

        String prompt = buildPrompt(goal, result);
        LocalDateTime cooldownDeadline = now;
        Integer cooldown = goal.getFollowupCooldownSeconds();
        if (cooldown != null && cooldown > 0 && goal.getLastFollowupAt() != null) {
            cooldownDeadline = goal.getLastFollowupAt().plusSeconds(cooldown);
        }
        if (fallback || completionUnverified || !GoalEvaluationResult.DECISION_CONTINUE.equals(result.decision())) {
            LocalDateTime retryAt = now.plusSeconds(EVALUATION_RETRY_SECONDS);
            if (cooldownDeadline.isAfter(retryAt)) retryAt = cooldownDeadline;
            return decision(Action.RETRY, prompt, retryAt,
                    completionUnverified ? "completion_not_verified"
                            : result == null ? "evaluation_missing" : bounded(result.gap(), 1000));
        }
        if (cooldownDeadline.isAfter(now)) {
            return decision(Action.DEFER, prompt, cooldownDeadline, "followup_cooldown");
        }
        return decision(Action.CONTINUE, prompt, now, "remaining_criteria");
    }

    private boolean hasVerifiedChecklist(GoalEntity goal) {
        List<GoalCriterion> checklist = GoalCriteriaCodec.parse(goal.getCriteria(), objectMapper);
        return !checklist.isEmpty() && checklist.stream().allMatch(c -> c != null && c.passed()
                && c.evidence() != null && !c.evidence().isBlank());
    }

    /** Compatibility wrapper for graph-local followups; deferred/retry work is not injected. */
    public Optional<String> maybeBuildFollowup(GoalEntity goal, GoalEvaluationResult result) {
        GoalContinuationDecision decision = decide(goal, result, LocalDateTime.now());
        return decision.action() == Action.CONTINUE ? Optional.of(decision.prompt()) : Optional.empty();
    }

    private GoalContinuationDecision decision(Action action, String prompt, LocalDateTime nextRunAt, String reason) {
        return new GoalContinuationDecision(action, prompt, nextRunAt, reason);
    }

    /** Bound each evidence section while always retaining recovery/safety instructions. */
    private String buildPrompt(GoalEntity goal, GoalEvaluationResult result) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Continue working toward the original objective; preserve its scope and exit criteria.\n")
                .append("Title: ").append(bounded(goal.getTitle(), 255)).append('\n')
                .append("Objective: ").append(bounded(goal.getDescription(), 2500)).append('\n')
                .append("Exit criteria: ").append(bounded(goal.getExitCriteria(), 2000)).append('\n');
        List<GoalCriterion> all = GoalCriteriaCodec.parse(goal.getCriteria(), objectMapper);
        boolean persistent = Boolean.TRUE.equals(goal.getPersistentExecution());
        List<GoalCriterion> remaining = persistent
                ? all.stream().filter(c -> !c.passed() || c.evidence() == null || c.evidence().isBlank()).toList()
                : GoalCriteriaCodec.remaining(all);
        if (!remaining.isEmpty()) {
            prompt.append(all.size() - remaining.size()).append('/').append(all.size())
                    .append(persistent ? " criteria verified. Remaining checklist (verify missing evidence):\n"
                            : " criteria passed. Remaining checklist:\n");
            StringBuilder checklist = new StringBuilder();
            for (GoalCriterion criterion : remaining) {
                if (checklist.length() >= 4000) break;
                checklist.append("  - ").append(bounded(criterion.text(), 600)).append('\n');
            }
            prompt.append(bounded(checklist.toString(), 4000));
        }
        String gap = result != null ? result.gap() : null;
        if (gap != null && !gap.isBlank()) {
            prompt.append("\nLatest evaluation: ").append(bounded(gap, 1000));
        }
        if (persistent) {
            prompt.append("\nIf essential input or permission is still unavailable after checking existing state, ")
                    .append("call waitForGoalInput with the precise missing requirement and ask the user once. ")
                    .append("Do not use this boundary because of difficulty, elapsed time, incomplete work, or transient errors.");
        }
        prompt.append("\nInspect authoritative state and any existing async handles before repeating side effects. ")
                .append("Poll or resume existing operations instead of starting duplicates. ")
                .append("Verify completed work against evidence; do not treat a prior attempt as success. ")
                .append("If a section above was truncated, retrieve the full goal/checklist before acting. ")
                .append("Take the next concrete step on the remaining criteria without changing the original objective.");
        return prompt.toString();
    }

    private static String bounded(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit - 14) + "… [truncated]";
    }
}
