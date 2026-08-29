package vip.mate.goal.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalAttempt;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.model.SegmentOutcome;

import java.time.LocalDateTime;
import java.util.UUID;

/** Owns fenced claim, renewal and settlement for one durable goal segment. */
@Service
public class GoalRunCoordinator {
    private static final int LEASE_SECONDS=60;
    private final GoalContinuationStore continuations;
    private final GoalAttemptStore attempts;
    private final GoalService goals;
    private final GoalProperties properties;

    public GoalRunCoordinator(GoalContinuationStore continuations,GoalAttemptStore attempts,GoalService goals,
                              GoalProperties properties) {
        this.continuations=continuations;this.attempts=attempts;this.goals=goals;this.properties=properties;
    }

    public record ClaimedRun(GoalContinuationStore.Continuation candidate,GoalEntity goal,
                             GoalAttempt attempt,long revision) {}

    @Transactional
    public ClaimedRun claim(GoalContinuationStore.Continuation candidate,GoalEntity goal,LocalDateTime now) {
        if(candidate==null || goal==null || candidate.currentAttemptId()!=null) return null;
        String token=UUID.randomUUID().toString();
        LocalDateTime until=now.plusSeconds(LEASE_SECONDS);
        if(!continuations.claim(goal.getId(),token,now,until)) return null;
        GoalContinuationStore.Continuation claimed=continuations.get(goal.getId());
        String parentAttemptId=null;
        if("restart_recovery".equals(candidate.reason())) {
            var recent=attempts.listRecent(goal.getId(),1);
            if(!recent.isEmpty()) parentAttemptId=recent.getFirst().id();
        }
        GoalAttempt attempt=attempts.create(goal.getId(),goal.getConversationId(),parentAttemptId,
                "continuation",token,until,null,now);
        if(!continuations.bindAttempt(goal.getId(),token,attempt.id(),claimed.revision())) {
            throw new IllegalStateException("Goal attempt could not be bound to its continuation");
        }
        return new ClaimedRun(candidate,goal,attempt,claimed.revision()+1);
    }

    @Transactional
    public boolean markRunning(ClaimedRun run,LocalDateTime now) {
        if(!current(run)) return false;
        return attempts.markRunning(run.attempt().id(),run.attempt().leaseToken(),now);
    }

    @Transactional
    public boolean renew(ClaimedRun run,LocalDateTime now) {
        LocalDateTime until=now.plusSeconds(LEASE_SECONDS);
        if(!continuations.renewFenced(run.goal().getId(),run.attempt().leaseToken(),
                run.attempt().id(),run.revision(),until)) return false;
        return attempts.renew(run.attempt().id(),run.attempt().leaseToken(),until,now);
    }

    @Transactional
    public boolean checkpoint(ClaimedRun run,String replaySafety,String checkpointType,
                              Long assistantMessageId,LocalDateTime now) {
        if(!current(run)) return false;
        return attempts.checkpoint(run.attempt().id(),run.attempt().leaseToken(),replaySafety,
                checkpointType,assistantMessageId,now);
    }

    @Transactional
    public boolean settle(ClaimedRun run,SegmentOutcome outcome,LocalDateTime now) {
        if(!current(run)) return false;
        GoalEntity fresh=goals.getById(run.goal().getId());
        Settlement settlement=classify(run,outcome,fresh,now);
        if((outcome instanceof SegmentOutcome.Continue || outcome instanceof SegmentOutcome.Complete)
                && !attempts.checkpoint(run.attempt().id(),run.attempt().leaseToken(),"resolved",
                "evaluation_saved",null,now)) return false;
        if(!attempts.finish(run.attempt().id(),run.attempt().leaseToken(),settlement.attemptState,
                outcome.reason(),settlement.errorCategory,now)) return false;
        if(!continuations.settleFenced(run.goal().getId(),run.attempt().leaseToken(),run.attempt().id(),
                run.revision(),settlement.projectionState,settlement.nextRunAt,settlement.failures,
                settlement.reason,now)) {
            throw new IllegalStateException("Goal projection fence changed during settlement");
        }
        return true;
    }

    private boolean current(ClaimedRun run) {
        return run!=null && continuations.matchesFence(run.goal().getId(),run.attempt().leaseToken(),
                run.attempt().id(),run.revision());
    }

    private Settlement classify(ClaimedRun run,SegmentOutcome outcome,GoalEntity fresh,LocalDateTime now) {
        int failures=run.candidate().failures();
        if(fresh!=null && fresh.getStatus()==GoalStatus.COMPLETED || outcome instanceof SegmentOutcome.Complete) {
            return new Settlement("succeeded","completed",now,0,"goal_completed",null);
        }
        if(fresh!=null && fresh.getStatus()==GoalStatus.PAUSED && goals.isBudgetExhausted(fresh)) {
            return new Settlement("succeeded","budget_limited",now,0,goals.exhaustionReason(fresh),null);
        }
        if(outcome instanceof SegmentOutcome.AwaitApproval) {
            return new Settlement("succeeded","waiting_approval",now,0,outcome.reason(),null);
        }
        if(outcome instanceof SegmentOutcome.WaitInput) {
            return new Settlement("succeeded","waiting_input",now,0,outcome.reason(),null);
        }
        if(outcome instanceof SegmentOutcome.Retry retry) {
            int nextFailures=Math.min(1000,failures+1);
            long delay=Math.min(300,5L << Math.min(6,nextFailures-1));
            return new Settlement("retryable","retry",now.plusSeconds(delay),nextFailures,
                    retry.reason(),retry.category());
        }
        if(outcome instanceof SegmentOutcome.Defer defer) {
            return new Settlement("succeeded","queued",defer.nextRunAt(),failures,defer.reason(),null);
        }
        if(outcome instanceof SegmentOutcome.Blocked blocked) {
            return new Settlement("blocked","blocked",now,Math.min(1000,failures+1),
                    blocked.reason(),blocked.category());
        }
        if(outcome instanceof SegmentOutcome.Cancelled || !eligible(fresh)) {
            boolean waiting=fresh!=null && fresh.getProgressSummary()!=null
                    && fresh.getProgressSummary().startsWith("Waiting for input:");
            return new Settlement("cancelled",waiting ? "waiting_input" : "paused",now,0,
                    waiting ? fresh.getProgressSummary() : outcome.reason(),null);
        }
        int cooldown=fresh==null || fresh.getFollowupCooldownSeconds()==null ? 0 : fresh.getFollowupCooldownSeconds();
        int delay=Math.max(properties.getMinimumContinuationIntervalSeconds(),cooldown);
        return new Settlement("succeeded","queued",now.plusSeconds(delay),0,
                outcome.reason(),null);
    }

    private static boolean eligible(GoalEntity goal) {
        return goal!=null && goal.getStatus()==GoalStatus.ACTIVE
                && Boolean.TRUE.equals(goal.getPersistentExecution())
                && Boolean.TRUE.equals(goal.getAutoFollowupEnabled());
    }

    private record Settlement(String attemptState,String projectionState,LocalDateTime nextRunAt,
                              int failures,String reason,String errorCategory) {}
}
