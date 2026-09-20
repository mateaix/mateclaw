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
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final LocalDateTime startupCutoff=LocalDateTime.now();
    private volatile boolean orphanClaimsReleased;

    public GoalRecoveryService(GoalAttemptStore attempts,GoalContinuationStore continuations,
                               ConversationInputQueueStore inputs,GoalService goals, org.springframework.transaction.PlatformTransactionManager manager) {
        this.attempts=attempts;this.continuations=continuations;this.inputs=inputs;this.goals=goals;
        this.transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    public RecoveryDecision classify(GoalAttempt attempt) {
        if("uncertain".equals(attempt.replaySafety())) {
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

    public int recoverExpired(java.time.Instant moment) {
        long nowEpoch=moment.getEpochSecond();
        LocalDateTime now=GoalLeaseTime.local(nowEpoch);
        if(!orphanClaimsReleased) {
            synchronized(this) {
                if(!orphanClaimsReleased) {
                    inputs.releaseClaimsBefore(startupCutoff,now);
                    orphanClaimsReleased=true;
                }
            }
        }
        int recovered=0;
        for(GoalAttempt attempt:attempts.expired(nowEpoch,100)) {
            if(Boolean.TRUE.equals(transactions.execute(status -> recover(attempt,now,nowEpoch)))) recovered++;
        }
        return recovered;
    }

    @Transactional
    boolean recover(GoalAttempt attempt,LocalDateTime now,long nowEpoch) {
        if(!continuations.lockGoal(attempt.goalId())) return false;
        var continuation=continuations.get(attempt.goalId());
        if(continuation==null || !attempt.id().equals(continuation.currentAttemptId())
                || !attempt.leaseToken().equals(continuation.leaseOwner())) return false;
        // The scan only proves the attempt expired. A live or changed projection
        // is not recoverable yet and must not abort recovery of later goals.
        if(!continuations.hasExpiredFence(attempt.goalId(),attempt.leaseToken(),attempt.id(),nowEpoch)) return false;
        RecoveryDecision decision=classify(attempt);
        String attemptState=decision==RecoveryDecision.BLOCK_UNCERTAIN_SIDE_EFFECT ? "blocked" : "retryable";
        String projectionState=decision==RecoveryDecision.BLOCK_UNCERTAIN_SIDE_EFFECT ? "blocked" : "retry";
        String reason=decision==RecoveryDecision.BLOCK_UNCERTAIN_SIDE_EFFECT
                ? "uncertain_tool_outcome_requires_review" : "restart_recovery";
        if(!attempts.finish(attempt.id(),attempt.leaseToken(),attemptState,reason,
                decision.name().toLowerCase(),now)) return false;
        if(!continuations.recoverExpired(attempt.goalId(),attempt.leaseToken(),attempt.id(),nowEpoch,
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
