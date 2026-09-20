package vip.mate.goal.service;

import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import vip.mate.agent.AgentService.StreamDelta;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.exception.MateClawException;
import vip.mate.goal.model.SegmentOutcome;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Owns the lease and durable checkpoints while the existing channel owns its replay messages. */
@Service
public class GoalApprovalReplayStream {
    private final GoalApprovalRunService runs;
    private final GoalRunCoordinator coordinator;

    public GoalApprovalReplayStream(GoalApprovalRunService runs, GoalRunCoordinator coordinator) {
        this.runs=runs; this.coordinator=coordinator;
    }

    public boolean applies(ChatOrigin origin) { return runs.requiresHandoff(origin); }

    public Flux<StreamDelta> replay(ChatOrigin origin, String payload, Function<ChatOrigin, Flux<StreamDelta>> invoke) {
        return Flux.using(() -> new Execution(runs.claim(origin, payload)), execution ->
                Flux.defer(() -> invoke.apply(execution.claim.origin()))
                        .doOnSubscribe(subscription -> execution.source = subscription)
                        .doOnNext(execution::observe)
                        .takeUntilOther(execution.lost.asMono())
                        .doOnComplete(execution::complete), Execution::close)
                .retryWhen(reactor.util.retry.Retry.fixedDelay(20, java.time.Duration.ofMillis(25))
                        .filter(GoalApprovalRunService.SettlementPending.class::isInstance)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private final class Execution implements AutoCloseable {
        private final GoalApprovalRunService.ReplayRun claim;
        private final Sinks.One<StreamDelta> lost = Sinks.one();
        private final Set<String> inFlight = new HashSet<>();
        private final Disposable renewal;
        private volatile org.reactivestreams.Subscription source;
        private volatile boolean sourceCompleted;
        private boolean unknown = true;
        private boolean awaitingApproval;
        private boolean evaluationUnavailable;
        private String finishReason = "approval_replay_completed";

        Execution(GoalApprovalRunService.ReplayRun claim) {
            this.claim=claim;
            // A crash before the forced approved call reports its outcome must not replay it blindly.
            checkpoint("uncertain", "approval_replay_started");
            renewal = Schedulers.boundedElastic().schedulePeriodically(() -> {
                try {
                    if (!coordinator.renew(claim.run(), LocalDateTime.now())) {
                        lost.tryEmitError(new MateClawException(409, "Approved Goal execution lost its owner lease"));
                    }
                } catch (RuntimeException error) { lost.tryEmitError(error); }
            }, 20, 20, TimeUnit.SECONDS);
        }

        private void checkpoint(String safety, String kind) {
            if (!coordinator.checkpoint(claim.run(), safety, kind, null, LocalDateTime.now())) {
                throw new MateClawException(409, "Approved Goal execution lost its checkpoint fence");
            }
        }

        void observe(StreamDelta delta) {
            String event = delta.eventType();
            var data = delta.eventData();
            if ("tool_call_started".equals(event)) {
                Object id = data == null ? null : data.get("toolCallId");
                if (id != null && !String.valueOf(id).isBlank()) { inFlight.add(String.valueOf(id)); unknown=false; }
                else { inFlight.add("<unknown>"); unknown=true; }
                checkpoint("uncertain", "tool_started");
            } else if ("tool_call_completed".equals(event)) {
                Object id = data == null ? null : data.get("toolCallId");
                if (id != null) inFlight.remove(String.valueOf(id));
                // ReAct may deliver these events after a whole action node returns. A later
                // tool can already be executing before its start delta arrives, so retain
                // uncertainty until the entire replay stream terminates normally.
                checkpoint("uncertain", "tool_completed");
            } else if ("tool_approval_requested".equals(event)) {
                awaitingApproval=true;
                // This exact call was deferred by the guard and has not executed.
                Object id = data == null ? null : data.get("toolCallId");
                if (id != null && !String.valueOf(id).isBlank()) inFlight.remove(String.valueOf(id));
            } else if ("goal_evaluated".equals(event) && data != null) {
                evaluationUnavailable = Boolean.TRUE.equals(data.get("skipped")) || "fallback".equals(data.get("decision"));
            } else if ("finish_reason".equals(event) && data != null && data.get("reason") != null) {
                finishReason=String.valueOf(data.get("reason"));
            }
        }

        void complete() {
            sourceCompleted=true;
            // On error/cancellation, or an unresolved tool even on normal termination, keep the
            // last checkpoint for existing expiry recovery (which pauses uncertain side effects).
            if (unknown || !inFlight.isEmpty()) return;
            checkpoint("resolved", "approval_replay_finished");
            SegmentOutcome outcome = awaitingApproval ? new SegmentOutcome.AwaitApproval("approval_required")
                    : "error_fallback".equals(finishReason) ? new SegmentOutcome.Blocked("approval", finishReason)
                    : evaluationUnavailable ? new SegmentOutcome.Retry("evaluation", "evaluation_unavailable")
                    : new SegmentOutcome.Continue(finishReason);
            if (!coordinator.settle(claim.run(), outcome, LocalDateTime.now())) {
                throw new MateClawException(409, "Approved Goal execution lost its settlement fence");
            }
        }

        @Override public void close() {
            renewal.dispose();
            // Explicitly cancel the producer when lease failure wins the other publisher.
            // Merely signalling an error downstream must not leave the graph subscribed.
            var subscription = source;
            if (!sourceCompleted && subscription != null) subscription.cancel();
        }
    }
}
