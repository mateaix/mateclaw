package vip.mate.goal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.goal.service.GoalContinuationStore;
import vip.mate.goal.service.GoalAttemptStore;
import vip.mate.goal.model.GoalAttempt;
import vip.mate.goal.service.GoalService;
import vip.mate.workspace.conversation.ConversationService;

/** Durable execution status, separate from goal acceptance status. */
@RestController
@RequiredArgsConstructor
public class GoalExecutionController {
    private final GoalService goals;
    private final GoalContinuationStore store;
    private final ConversationService conversations;
    private final GoalAttemptStore attempts;

    @GetMapping("/api/v1/goals/{id}/execution")
    public R<GoalContinuationStore.Continuation> execution(@PathVariable Long id, Authentication auth) {
        authorize(id,auth);
        return R.ok(store.get(id));
    }

    @GetMapping("/api/v1/goals/{id}/execution/attempts")
    public R<java.util.List<AttemptView>> attempts(@PathVariable Long id,Authentication auth) {
        authorize(id,auth);
        return R.ok(attempts.listRecent(id,50).stream().map(AttemptView::from).toList());
    }

    private void authorize(Long id,Authentication auth) {
        var goal=goals.getById(id);
        if(auth==null || !conversations.isConversationOwner(goal.getConversationId(),auth.getName())) {
            throw new MateClawException("err.goal.forbidden",403,"Not the conversation owner");
        }
    }

    public record AttemptView(String id,String parentAttemptId,String triggerType,String state,
                              Long inputItemId,Long assistantMessageId,String replaySafety,
                              String checkpointType,String finishReason,String errorCategory,
                              java.time.LocalDateTime startedAt,java.time.LocalDateTime finishedAt,
                              java.time.LocalDateTime createdAt,java.time.LocalDateTime updatedAt) {
        static AttemptView from(GoalAttempt attempt) {
            return new AttemptView(attempt.id(),attempt.parentAttemptId(),attempt.triggerType(),attempt.state(),
                    attempt.inputItemId(),attempt.assistantMessageId(),attempt.replaySafety(),attempt.checkpointType(),
                    attempt.finishReason(),attempt.errorCategory(),attempt.startedAt(),attempt.finishedAt(),
                    attempt.createdAt(),attempt.updatedAt());
        }
    }
}
