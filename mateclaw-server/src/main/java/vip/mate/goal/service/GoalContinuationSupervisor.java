package vip.mate.goal.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vip.mate.agent.runtime.RunningConversationRegistry;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.model.SegmentOutcome;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Owns cross-turn liveness. Graph recursion limits bound segments, not goal lifetime. */
@Slf4j
@Component
public class GoalContinuationSupervisor {
    private final GoalContinuationStore store;
    private final GoalService goals;
    private final GoalProperties properties;
    private final GoalFollowupService followups;
    private final GoalSegmentRunner runner;
    private final RunningConversationRegistry running;
    private final ChatStreamTracker streams;
    private final Clock clock;
    private final Executor executor;
    private final GoalRunCoordinator coordinator;
    private final GoalRecoveryService recovery;
    private final ConcurrentHashMap<Long, GoalRunCoordinator.ClaimedRun> active = new ConcurrentHashMap<>();
    private final AtomicReference<LocalDateTime> providerBackoffUntil = new AtomicReference<>();
    private volatile boolean closing;

    @Autowired
    public GoalContinuationSupervisor(GoalContinuationStore store, GoalService goals, GoalProperties properties,
            GoalFollowupService followups, GoalSegmentRunner runner, RunningConversationRegistry running,
            ChatStreamTracker streams,GoalRunCoordinator coordinator,GoalRecoveryService recovery) {
        this(store,goals,properties,followups,runner,running,streams,coordinator,recovery,Clock.systemDefaultZone(),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    GoalContinuationSupervisor(GoalContinuationStore store, GoalService goals, GoalProperties properties,
            GoalFollowupService followups, GoalSegmentRunner runner, RunningConversationRegistry running,
            ChatStreamTracker streams,GoalRunCoordinator coordinator,GoalRecoveryService recovery,
            Clock clock, Executor executor) {
        this.store=store; this.goals=goals; this.properties=properties; this.followups=followups;
        this.runner=runner; this.running=running; this.streams=streams; this.coordinator=coordinator;this.recovery=recovery;
        this.clock=clock; this.executor=executor;
    }

    @Scheduled(fixedDelayString="${mateclaw.goal.supervisor-poll-ms:5000}", initialDelayString="${mateclaw.goal.supervisor-poll-ms:5000}")
    public void tick() {
        if (closing || !properties.isEnabled() || !properties.isAllowAutoFollowup()) return;
        LocalDateTime now = LocalDateTime.now(clock);
        recovery.recoverExpired(now);
        active.forEach((id, claimed) -> {
            GoalEntity goal = goals.getById(id);
            boolean cancelled = goal.getStatus()==GoalStatus.PAUSED || goal.getStatus()==GoalStatus.ABANDONED
                    || !Boolean.TRUE.equals(goal.getAutoFollowupEnabled());
            if (cancelled || !coordinator.renew(claimed,now)) runner.cancel(id);
        });
        LocalDateTime backoffUntil=providerBackoffUntil.get();
        if (backoffUntil!=null && now.isBefore(backoffUntil)) return;
        store.discover(now);
        int maxConcurrent = properties.getMaxConcurrentSegments();
        // Scan beyond the execution capacity: a due conversation may currently
        // belong to an interactive user turn and must not starve later goals.
        for (var candidate : store.due(now, Math.max(20,maxConcurrent))) {
            if (active.size() >= maxConcurrent) break;
            String conv = candidate.conversationId();
            if (active.containsKey(candidate.goalId()) || running.isActive(conv)
                    || streams.isRunning(conv)) continue;
            GoalEntity goal = goals.getById(candidate.goalId());
            if (!eligible(goal)) continue;
            if (active.containsKey(goal.getId())) continue;
            GoalRunCoordinator.ClaimedRun claimed=coordinator.claim(candidate,goal,now);
            if(claimed==null || active.putIfAbsent(goal.getId(),claimed)!=null) continue;
            try {
                executor.execute(() -> execute(claimed));
            } catch (RuntimeException error) {
                active.remove(goal.getId(),claimed);
                settle(claimed,new SegmentOutcome.Retry("dispatch","dispatch_failed"),now);
                log.warn("Goal {} dispatch failed",goal.getId(),error);
            }
        }
    }

    private void execute(GoalRunCoordinator.ClaimedRun claimed) {
        GoalEntity initial=claimed.goal();
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            if (closing) return;
            GoalEntity goal = goals.getById(initial.getId());
            if (!eligible(goal)) {
                settle(claimed,new SegmentOutcome.Cancelled("goal_not_runnable"),now); return;
            }
            var decision = followups.decide(goal,new GoalEvaluationResult(0,goal.getProgressSummary(),
                    GoalEvaluationResult.DECISION_CONTINUE,false,"",0,0,List.of(),null),now);
            switch (decision.action()) {
                case DEFER, RETRY -> {
                    settle(claimed,new SegmentOutcome.Defer(decision.reason(),decision.nextRunAt()),now); return;
                }
                case BUDGET_LIMITED -> {
                    goals.markExhausted(goal.getId(),decision.reason());
                    settle(claimed,new SegmentOutcome.Cancelled(decision.reason()),now); return;
                }
                case COMPLETE, DISABLED -> {
                    settle(claimed,new SegmentOutcome.Cancelled(decision.reason()),now); return;
                }
                case CONTINUE -> { }
            }
            if(!coordinator.markRunning(claimed,now)) return;
            SegmentOutcome outcome = runner.run(claimed,decision.prompt(),"running".equals(claimed.candidate().state()));
            if (outcome instanceof SegmentOutcome.Retry retry
                    && ("provider".equals(retry.category()) || "evaluation".equals(retry.category()))) {
                activateProviderBackoff(LocalDateTime.now(clock));
            }
            // Shutdown cancellation is not user Stop: retain the lease for recovery.
            if (closing) return;
            settle(claimed,outcome,LocalDateTime.now(clock));
        } catch (RuntimeException error) {
            // A shutdown/lost-lease cancellation is not a task failure. Leave the
            // running lease for restart recovery; the runner saves partial evidence.
            if (closing || Thread.currentThread().isInterrupted()) return;
            boolean transientError = retryable(error);
            if (transientError) activateProviderBackoff(now);
            if (!transientError) {
                GoalEntity fresh=goals.getById(initial.getId());
                if (eligible(fresh)) goals.pause(fresh.getId(),fresh.getCreatedBy());
            }
            settle(claimed,transientError
                    ? new SegmentOutcome.Retry("provider","transient_provider_error")
                    : new SegmentOutcome.Blocked("execution","execution_requires_review"),now);
            log.warn("Goal {} segment failed ({})",initial.getId(),transientError ? "retry" : "blocked",error);
        } finally {
            active.remove(initial.getId(),claimed);
        }
    }

    private void activateProviderBackoff(LocalDateTime now) {
        int seconds=properties.getProviderFailureGlobalBackoffSeconds();
        if (seconds<=0) return;
        LocalDateTime proposed=now.plusSeconds(seconds);
        LocalDateTime effective=providerBackoffUntil.updateAndGet(current ->
                current==null || current.isBefore(proposed) ? proposed : current);
        log.warn("Goal dispatch paused until {} after retryable provider failure",effective);
    }

    private void settle(GoalRunCoordinator.ClaimedRun claimed,SegmentOutcome outcome,LocalDateTime now) {
        if (coordinator.settle(claimed,outcome,now)) {
            streams.broadcastObject(claimed.goal().getConversationId(),"goal_continuation",store.get(claimed.goal().getId()));
        }
    }

    private static boolean eligible(GoalEntity goal) {
        return goal != null && goal.getStatus()==GoalStatus.ACTIVE
                && Boolean.TRUE.equals(goal.getPersistentExecution()) && Boolean.TRUE.equals(goal.getAutoFollowupEnabled());
    }

    static boolean retryable(Throwable error) {
        for (Throwable e=error; e!=null; e=e.getCause()) {
            if (e instanceof java.io.IOException || e instanceof java.util.concurrent.TimeoutException
                    || e instanceof org.springframework.web.client.ResourceAccessException) return true;
            if (e instanceof org.springframework.web.client.RestClientResponseException response) {
                int code = response.getStatusCode().value();
                return code==408 || code==429 || code>=500;
            }
            if (e instanceof vip.mate.exception.MateClawException mate && mate.getCode()==409) return true;
        }
        return false;
    }

    @EventListener
    public void stopped(GoalExecutionSignal.Stop event) {
        runner.stopConversation(event.conversationId());
        GoalEntity goal = goals.findActiveByConversation(event.conversationId());
        if (goal != null && Boolean.TRUE.equals(goal.getPersistentExecution())) {
            runner.cancel(goal.getId());
            goals.pause(goal.getId(),goal.getCreatedBy());
            store.suspendConversation(event.conversationId(),"user_stopped");
        }
    }

    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT, fallbackExecution=true)
    public void resumed(GoalExecutionSignal.Resume event) {
        store.resume(event.goalId(),LocalDateTime.now(clock));
    }

    @EventListener
    public void turnFinished(GoalExecutionSignal.TurnFinished event) {
        store.turnFinished(event.conversationId(),LocalDateTime.now(clock));
    }

    @EventListener
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void approvalResolved(vip.mate.approval.event.ApprovalResolutionEvent event) {
        if ("denied".equals(event.resolutionNote()) || "TIMEOUT".equals(event.decisionSource())) {
            stopped(new GoalExecutionSignal.Stop(event.conversationId()));
        }
        // Approval execution belongs to the existing replay path. Only its
        // TurnFinished event releases waiting_approval; never consume/replay here.
    }

    @PreDestroy public void close() {
        closing=true;
        runner.cancelAll();
        if (executor instanceof java.util.concurrent.ExecutorService workers) {
            // Cancellation must finish checkpoint persistence without interrupting JDBC I/O.
            workers.shutdown();
            try {
                if (!workers.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("Goal workers did not finish shutdown persistence within 10 seconds");
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
