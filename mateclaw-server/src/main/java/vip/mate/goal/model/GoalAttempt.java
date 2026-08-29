package vip.mate.goal.model;

import java.time.LocalDateTime;
import java.util.Set;

/** One immutable-identity execution attempt for a bounded goal segment. */
public record GoalAttempt(
        String id,
        Long goalId,
        String conversationId,
        String parentAttemptId,
        String triggerType,
        String state,
        String leaseToken,
        LocalDateTime leaseUntil,
        Long inputItemId,
        Long assistantMessageId,
        String replaySafety,
        String checkpointType,
        String finishReason,
        String errorCategory,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    private static final Set<String> TERMINAL_STATES =
            Set.of("succeeded", "retryable", "blocked", "cancelled");

    public boolean terminal() {
        return TERMINAL_STATES.contains(state);
    }
}
