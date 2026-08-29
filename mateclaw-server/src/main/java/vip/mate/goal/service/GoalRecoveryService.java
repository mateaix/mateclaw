package vip.mate.goal.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.channel.web.ConversationInputQueueStore;
import vip.mate.goal.model.GoalAttempt;

import java.time.LocalDateTime;

/** Reconciles expired attempts from durable checkpoints before dispatching new work. */
@Service
public class GoalRecoveryService {
    public enum RecoveryDecision {
        RETRY_SAFE,
        RESUME_FROM_EVIDENCE,
        RECONCILE_MESSAGE,
        BLOCK_UNCERTAIN_SIDE_EFFECT
    }

    private final GoalAttemptStore attempts;
    private final GoalContinuationStore continuations;
    private final ConversationInputQueueStore inputs;
    private final GoalService goals;
    private final LocalDateTime startupCutoff=LocalDateTime.now();
    private volatile boolean orphanClaimsReleased;

    public GoalRecoveryService(GoalAttemptStore attempts,GoalContinuationStore continuations,
                               ConversationInputQueueStore inputs,GoalService goals) {
        this.attempts=attempts;this.continuations=continuations;this.inputs=inputs;this.goals=goals;
    }

    public RecoveryDecision classify(GoalAttempt attempt) {
        if("tool_started".equals(attempt.checkpointType()) && "uncertain".equals(attempt.replaySafety())) {
            return RecoveryDecision.BLOCK_UNCERTAIN_SIDE_EFFECT;
        }
        if("message_saved".equals(attempt.checkpointType()) && attempt.assistantMessageId()!=null) {
            return RecoveryDecision.RECONCILE_MESSAGE;
        }
        if("tool_completed".equals(attempt.checkpointType()) && "resolved".equals(attempt.replaySafety())) {
            return RecoveryDecision.RESUME_FROM_EVIDENCE;
        }
        return RecoveryDecision.RETRY_SAFE;
    }

    public int recoverExpired(LocalDateTime now) {
        if(!orphanClaimsReleased) {
            synchronized(this) {
                if(!orphanClaimsReleased) {
                    inputs.releaseClaimsBefore(startupCutoff,now);
                    orphanClaimsReleased=true;
                }
            }
        }
        int recovered=0;
        for(GoalAttempt attempt:attempts.expired(now,100)) {
            if(recover(attempt,now)) recovered++;
        }
        return recovered;
    }

    @Transactional
    boolean recover(GoalAttempt attempt,LocalDateTime now) {
        var continuation=continuations.get(attempt.goalId());
        if(continuation==null || !attempt.id().equals(continuation.currentAttemptId())
                || !attempt.leaseToken().equals(continuation.leaseOwner())) return false;
        RecoveryDecision decision=classify(attempt);
        String attemptState=decision==RecoveryDecision.BLOCK_UNCERTAIN_SIDE_EFFECT ? "blocked" : "retryable";
        String projectionState=decision==RecoveryDecision.BLOCK_UNCERTAIN_SIDE_EFFECT ? "blocked" : "retry";
        String reason=decision==RecoveryDecision.BLOCK_UNCERTAIN_SIDE_EFFECT
                ? "uncertain_tool_outcome_requires_review" : "restart_recovery";
        if(!attempts.finish(attempt.id(),attempt.leaseToken(),attemptState,reason,
                decision.name().toLowerCase(),now)) return false;
        if(!continuations.recoverExpired(attempt.goalId(),attempt.leaseToken(),attempt.id(),now,
                projectionState,now,continuation.failures()+1,reason,now)) {
            throw new IllegalStateException("Expired goal projection changed during recovery");
        }
        inputs.releaseClaims(attempt.id(),now);
        if(decision==RecoveryDecision.BLOCK_UNCERTAIN_SIDE_EFFECT) {
            var goal=goals.getById(attempt.goalId());
            if(goal!=null) goals.pause(goal.getId(),goal.getCreatedBy());
        }
        return true;
    }
}
