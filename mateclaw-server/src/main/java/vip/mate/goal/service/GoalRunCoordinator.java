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
    private final java.time.Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public GoalRunCoordinator(GoalContinuationStore continuations,GoalAttemptStore attempts,GoalService goals,
                              GoalProperties properties) {
        this(continuations, attempts, goals, properties, java.time.Clock.systemDefaultZone());
    }

    GoalRunCoordinator(GoalContinuationStore continuations,GoalAttemptStore attempts,GoalService goals,
                       GoalProperties properties,java.time.Clock clock) {
        this.continuations=continuations;this.attempts=attempts;this.goals=goals;this.properties=properties;
        this.clock=clock;
    }

    public record ClaimedRun(GoalContinuationStore.Continuation candidate,GoalEntity goal,
                             GoalAttempt attempt,long revision) {}

    @Transactional
    public ClaimedRun claim(GoalContinuationStore.Continuation candidate,GoalEntity goal,LocalDateTime now) {
        if(candidate==null || goal==null || candidate.currentAttemptId()!=null) return null;
        if(!continuations.lockGoal(goal.getId())) return null;
        java.time.Instant instant=currentInstant(now);
        long nowEpoch=instant.getEpochSecond();
        now=LocalDateTime.ofInstant(instant,java.time.ZoneId.systemDefault());
        String token=UUID.randomUUID().toString();
        long untilEpoch=nowEpoch+LEASE_SECONDS;
        LocalDateTime until=GoalLeaseTime.local(untilEpoch);
        if(!continuations.claim(goal.getId(),token,now,until,nowEpoch,untilEpoch)) return null;
        GoalContinuationStore.Continuation claimed=continuations.get(goal.getId());
        String parentAttemptId=null;
        var recent=attempts.listRecent(goal.getId(),1);
        if(!recent.isEmpty()) {
            var previous=recent.getFirst();
            // A recovered attempt may be deferred before reaching the provider.
            // Keep that pending recovery context until a segment actually starts.
            if("restart_recovery".equals(candidate.reason())
                    || previous.parentAttemptId()!=null && "claimed".equals(previous.checkpointType())) {
                parentAttemptId=previous.id();
            }
        }
        GoalAttempt attempt=attempts.create(goal.getId(),goal.getConversationId(),parentAttemptId,
                "continuation",token,until,null,now,untilEpoch);
        if(!continuations.bindAttempt(goal.getId(),token,attempt.id(),claimed.revision())) {
            throw new IllegalStateException("Goal attempt could not be bound to its continuation");
        }
        return new ClaimedRun(candidate,goal,attempt,claimed.revision()+1);
    }

    @Transactional
    public boolean markRunning(ClaimedRun run,LocalDateTime now) {
        if(run==null || !continuations.lockGoal(run.goal().getId())) return false;
        java.time.Instant instant=currentInstant(now);
        long nowEpoch=instant.getEpochSecond();
        now=LocalDateTime.ofInstant(instant,java.time.ZoneId.systemDefault());
        if(!current(run,nowEpoch)) return false;
        return attempts.markRunning(run.attempt().id(),run.attempt().leaseToken(),now);
    }

    @Transactional
    public boolean renew(ClaimedRun run,LocalDateTime now) {
        if(run==null || !continuations.lockGoal(run.goal().getId())) return false;
        java.time.Instant instant=currentInstant(now);
        long nowEpoch=instant.getEpochSecond();
        now=LocalDateTime.ofInstant(instant,java.time.ZoneId.systemDefault());
        if(!current(run,nowEpoch)) return false;
        long untilEpoch=nowEpoch+LEASE_SECONDS;
        LocalDateTime until=GoalLeaseTime.local(untilEpoch);
        if(!continuations.renewFenced(run.goal().getId(),run.attempt().leaseToken(),
                run.attempt().id(),run.revision(),until,untilEpoch)) return false;
        if(!attempts.renew(run.attempt().id(),run.attempt().leaseToken(),until,now,untilEpoch)) {
            throw new IllegalStateException("Goal attempt fence changed during renewal");
        }
        return true;
    }

    @Transactional
    public boolean checkpoint(ClaimedRun run,String replaySafety,String checkpointType,
                              Long assistantMessageId,LocalDateTime now) {
        if(run==null || !continuations.lockGoal(run.goal().getId())) return false;
        java.time.Instant instant=currentInstant(now);
        long nowEpoch=instant.getEpochSecond();
        now=LocalDateTime.ofInstant(instant,java.time.ZoneId.systemDefault());
        if(!current(run,nowEpoch)) return false;
        return attempts.checkpoint(run.attempt().id(),run.attempt().leaseToken(),replaySafety,
                checkpointType,assistantMessageId,now);
    }

    @Transactional
    public boolean settle(ClaimedRun run,SegmentOutcome outcome,LocalDateTime now) {
        if(run==null || !continuations.lockGoal(run.goal().getId())) return false;
        java.time.Instant instant=currentInstant(now);
        long nowEpoch=instant.getEpochSecond();
        now=LocalDateTime.ofInstant(instant,java.time.ZoneId.systemDefault());
        if(!current(run,nowEpoch)) return false;
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

    private java.time.Instant currentInstant(LocalDateTime requested) {
        // Preserve absolute time across DST overlap and time spent waiting for the goal lock.
        // Keep sub-second precision for next_run_at comparisons; only persisted leases use seconds.
        java.time.Instant observed=clock.instant();
        java.time.Instant supplied=requested.atZone(java.time.ZoneId.systemDefault()).toInstant();
        return observed.isAfter(supplied) ? observed : supplied;
    }

    private boolean current(ClaimedRun run,long nowEpoch) {
        return run!=null && continuations.matchesFence(run.goal().getId(),run.attempt().leaseToken(),
                run.attempt().id(),run.revision(),nowEpoch)
                && attempts.hasLiveFence(run.attempt().id(),run.attempt().leaseToken(),nowEpoch);
    }

    private Settlement classify(ClaimedRun run,SegmentOutcome outcome,GoalEntity fresh,LocalDateTime now) {
        int failures=run.candidate().failures();
        if(fresh!=null && fresh.getStatus()==GoalStatus.COMPLETED
                || outcome instanceof SegmentOutcome.Complete && (fresh==null || !fresh.isJsonAcceptanceRequired())) {
            return new Settlement("succeeded","completed",now,0,"goal_completed",null);
        }
        if(outcome instanceof SegmentOutcome.Complete && fresh!=null && fresh.isJsonAcceptanceRequired()) {
            return eligible(fresh)
                    ? new Settlement("retryable","retry",now.plusSeconds(5),Math.min(1000,failures+1),"json_completion_not_committed","acceptance")
                    : new Settlement("cancelled","paused",now,0,"goal_not_runnable",null);
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
