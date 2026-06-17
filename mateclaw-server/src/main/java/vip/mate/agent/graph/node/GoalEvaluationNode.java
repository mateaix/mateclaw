package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.graph.state.FinishReason;
import vip.mate.agent.graph.state.MateClawStateAccessor;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.service.GoalEvaluationService;
import vip.mate.goal.service.GoalFollowupService;
import vip.mate.goal.service.GoalService;
import vip.mate.goal.service.GraphFlavor;
import vip.mate.workspace.conversation.ConversationService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sits between FinalAnswerNode (or PlanSummaryNode) and the graph END.
 *
 * <p>Evaluation runs on a settled terminal answer so upstream finishReason /
 * evidence checks are already authoritative. The node:
 * <ol>
 *   <li>Bails out for the "this turn shouldn't count" finishReasons
 *       (evidence_insufficient, stopped, error_fallback, return_direct,
 *       max_iterations_reached, plus awaiting_approval).</li>
 *   <li>Otherwise calls the evaluator, persists the
 *       agent/eval LLM-call deltas + score + gap via GoalService.</li>
 *   <li>Decides completed / exhausted / followup / continue. Completed
 *       and exhausted update {@code mate_agent_goal.status} ONLY — they
 *       never touch FINISH_REASON, since the graph's own terminal status
 *       is independent of goal status.</li>
 *   <li>On followup, sets GOAL_FOLLOWUP_PROMPT and clears whichever
 *       graph-specific state would otherwise short-circuit the re-entry
 *       pass (clear set depends on the constructor-time GraphFlavor).</li>
 * </ol>
 */
@Slf4j
public class GoalEvaluationNode implements NodeAction {

    private final GoalEvaluationService evaluationService;
    private final GoalFollowupService followupService;
    private final GoalService goalService;
    private final GoalProperties properties;
    private final ConversationWindowManager windowManager; // reserved for evaluator context windowing
    private final ConversationService conversationService; // reserved for evaluator context lookups
    private final GraphFlavor flavor;

    public GoalEvaluationNode(GoalEvaluationService evaluationService,
                              GoalFollowupService followupService,
                              GoalService goalService,
                              GoalProperties properties,
                              ConversationWindowManager windowManager,
                              ConversationService conversationService,
                              GraphFlavor flavor) {
        this.evaluationService = evaluationService;
        this.followupService = followupService;
        this.goalService = goalService;
        this.properties = properties;
        this.windowManager = windowManager;
        this.conversationService = conversationService;
        this.flavor = flavor;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        // Master kill switch — when disabled the node stays inert.
        if (!properties.isEnabled()) {
            return Map.of();
        }

        MateClawStateAccessor accessor = new MateClawStateAccessor(state);

        // Resolve the active goal from the turn-start snapshot, falling back to
        // a conversation lookup. The fallback is what makes a goal created
        // MID-TURN (the agent calls setGoal, which only writes the DB) get
        // evaluated on the very turn it was set — otherwise ACTIVE_GOAL is empty
        // in this run's state and the goal would sit inert until the next message.
        Optional<GoalEntity> goalOpt = resolveActiveGoal(state, goalService);
        if (goalOpt.isEmpty()) {
            return Map.of();
        }

        // Re-entry guard — the FinalAnswer→GoalEvaluation conditional edge
        // also checks this, but defence in depth pays for itself here.
        if (accessor.goalEvaluatedThisRun()) {
            return Map.of();
        }

        // Every skip path below emits a goal_evaluated event with a reason
        // so the frontend can flip the breathing-halo state back off. The
        // chat composable's `message_complete` handler optimistically sets
        // evaluating=true; without a balancing event the ring would stay
        // in that state forever after e.g. a max-iterations turn.
        Long goalIdForEvents = goalOpt.get().getId();

        // ReAct path: FinalAnswerNode wrote a canonical finishReason that
        // determines whether this turn counts. Plan-Execute usually doesn't
        // set finishReason on the happy path, so we only enforce these
        // exit conditions in REACT mode + the universal awaiting_approval
        // gate that both flavors share.
        // A turn that hit the ReAct iteration cap. Continuing it needs a FRESH
        // iteration budget (a "hard continuation"), handled in the follow-up
        // branch below; capture it here while finishReason is still authoritative.
        boolean reactIterationCapReached = flavor == GraphFlavor.REACT
                && isIterationCapReached(accessor.finishReason());

        if (flavor == GraphFlavor.REACT) {
            String fr = accessor.finishReason();
            if (isHardSkipFinishReason(fr)) {
                log.debug("[GoalEvaluationNode] skipping evaluation (REACT finishReason={})", fr);
                return MateClawStateAccessor.output()
                        .goalEvaluatedThisRun(true)
                        .events(List.of(skippedEvent(goalIdForEvents, "react_finish_reason:" + fr)))
                        .build();
            }
            // MAX_ITERATIONS_REACHED and EVIDENCE_INSUFFICIENT intentionally
            // fall through: both mean "answer produced but the goal is likely
            // unmet", which is exactly when a corrective follow-up helps.
            // Max-iterations additionally needs a fresh budget (see below).
        }
        if (accessor.awaitingApproval()) {
            return MateClawStateAccessor.output()
                    .goalEvaluatedThisRun(true)
                    .events(List.of(skippedEvent(goalIdForEvents, "awaiting_approval")))
                    .build();
        }

        GoalEntity goal = goalOpt.get();

        String terminal = accessor.terminalAnswer();
        if (terminal.isEmpty()) {
            log.warn("[GoalEvaluationNode] terminalAnswer empty (flavor={}); skipping evaluation", flavor);
            return MateClawStateAccessor.output()
                    .goalEvaluatedThisRun(true)
                    .events(List.of(skippedEvent(goal.getId(), "empty_terminal_answer")))
                    .build();
        }

        // Build a thin recent-messages slice for the evaluator prompt.
        List<Message> recent = accessor.messages();
        int max = properties.getEvaluatorContextMessages();
        if (recent.size() > max) {
            recent = recent.subList(recent.size() - max, recent.size());
        }

        // Evaluator + persistence wrapped together: the just-emitted final
        // answer is the user-visible thing and must NOT be lost just because
        // a provider timeout or DB hiccup happens on the way to the
        // bookkeeping write. On any failure we mark the run as evaluated
        // (so the conditional edge above won't loop us back) and route to
        // the normal terminal path — the user still sees their answer; the
        // goal stays in whatever state it was before this turn.
        GoalEvaluationResult result;
        GoalEntity refreshed;
        try {
            result = evaluationService.evaluate(goal, recent, terminal);

            // Bill only the NEW agent LLM calls since the last accounted point.
            // The run-to-completion loop evaluates multiple times per graph run
            // while LLM_CALL_COUNT keeps growing, so passing the cumulative value
            // raw would re-bill earlier calls on every pass and exhaust the
            // goal's LLM budget prematurely. The followup branch advances the
            // accounted marker; terminal branches don't (the run ends there).
            int agentLlmDelta = Math.max(0, accessor.llmCallCount() - accessor.goalAccountedLlmCallCount());
            int evalLlmDelta = result.llmCallsConsumed();
            goalService.recordEvaluation(goal.getId(), result, agentLlmDelta, evalLlmDelta);

            refreshed = goalService.getById(goal.getId());
        } catch (Throwable t) {
            log.warn("[GoalEvaluationNode] evaluator/persist failed for goal={} — skipping this pass: {}",
                    goal.getId(), t.toString());
            return MateClawStateAccessor.output()
                    .goalEvaluationResult(GoalEvaluationResult.fallback("node_exception").toMap())
                    .goalEvaluatedThisRun(true)
                    .events(List.of(skippedEvent(goal.getId(), "evaluator_or_persist_failed")))
                    .build();
        }

        // Decision branches. Each terminal write is wrapped so a DB hiccup
        // (e.g. optimistic-lock conflict exceeding retries, memory sync
        // failure on completion) does not propagate into the chat graph
        // and abort the streamed answer the user already sees.
        try {
            // Completion is the deterministic "all criteria passed" signal the
            // evaluator already folded into result.completed() — no score gate.
            if (result.completed()) {
                GoalEntity completed = goalService.markCompleted(refreshed.getId(), result);
                return MateClawStateAccessor.output()
                        .goalEvaluationResult(result.toMap())
                        .goalEvaluatedThisRun(true)
                        .events(List.of(goalEvent("goal_completed", Map.of(
                                "goalId", String.valueOf(completed.getId()),
                                "score", result.score(),
                                "goal", goalService.toResponse(completed)))))
                        .build();
            }

            if (goalService.isBudgetExhausted(refreshed)) {
                String reason = goalService.exhaustionReason(refreshed);
                GoalEntity exhausted = goalService.markExhausted(refreshed.getId(), reason);
                return MateClawStateAccessor.output()
                        .goalEvaluationResult(result.toMap())
                        .goalEvaluatedThisRun(true)
                        .events(List.of(goalEvent("goal_exhausted", Map.of(
                                "goalId", String.valueOf(exhausted.getId()),
                                "turnsUsed", exhausted.getTurnsUsed(),
                                "agentLlmCallsUsed", exhausted.getAgentLlmCallsUsed(),
                                "evalLlmCallsUsed", exhausted.getEvalLlmCallsUsed(),
                                "totalLlmCallsUsed", exhausted.totalLlmCallsUsed(),
                                "reason", reason,
                                "goal", goalService.toResponse(exhausted)))))
                        .build();
            }
        } catch (Throwable t) {
            log.warn("[GoalEvaluationNode] terminal write failed for goal={} — degrading to evaluated-only: {}",
                    refreshed.getId(), t.toString());
            return MateClawStateAccessor.output()
                    .goalEvaluationResult(result.toMap())
                    .goalEvaluatedThisRun(true)
                    .events(List.of(skippedEvent(refreshed.getId(), "terminal_write_failed")))
                    .build();
        }

        int followupCountThisRun = accessor.goalFollowupCount();
        int hardContinuationCount = accessor.goalHardContinuationCount();
        int hardCap = Math.min(properties.getMaxHardContinuationsPerRun(),
                GoalProperties.MAX_HARD_CONTINUATIONS_CEILING);
        Optional<String> followup;
        try {
            followup = followupService.maybeBuildFollowup(refreshed, result);
        } catch (Throwable t) {
            log.warn("[GoalEvaluationNode] followup planning failed for goal={}: {}",
                    refreshed.getId(), t.toString());
            followup = Optional.empty();
        }
        // Per-run safety net: cap the autonomous self-continuation loop so a
        // single user message can't drive an unbounded number of steps or
        // approach the graph recursion limit. When the cap is hit we fall
        // through to the terminal "continue, no followup" path — the goal stays
        // active and the cross-message turn / LLM budget (or the user) carries
        // it on.
        boolean perRunCapReached = followupCountThisRun >= properties.getMaxFollowupsPerRun();
        // A max-iterations continuation re-runs a FULL fresh ReAct segment
        // (iteration budget reset), so it carries a tighter, dedicated cap on
        // top of the per-run follow-up cap — and is sized into the graph
        // recursion ceiling. hardCap==0 keeps the legacy behaviour (a
        // max-iterations turn simply ends the run).
        boolean hardCapReached = reactIterationCapReached && hardContinuationCount >= hardCap;
        if (followup.isPresent() && (perRunCapReached || hardCapReached)) {
            log.info("[GoalEvaluationNode] follow-up suppressed for goal={} " +
                            "(followups {}/{}, hardContinuations {}/{}, iterationCapReached={}); ending this run",
                    refreshed.getId(), followupCountThisRun, properties.getMaxFollowupsPerRun(),
                    hardContinuationCount, hardCap, reactIterationCapReached);
        }
        if (followup.isPresent() && !perRunCapReached && !hardCapReached) {
            try {
                goalService.recordFollowupInjected(refreshed.getId(), followup.get());
            } catch (Throwable t) {
                log.warn("[GoalEvaluationNode] recordFollowupInjected failed — emitting followup anyway: {}",
                        t.toString());
                // Continue: the in-memory state-machine path still works
                // even if the audit row could not be written.
            }
            MateClawStateAccessor.OutputBuilder out = MateClawStateAccessor.output()
                    .goalEvaluationResult(result.toMap())
                    .goalFollowupInjected(true)
                    .goalFollowupPrompt(followup.get())
                    .goalFollowupCount(followupCountThisRun + 1)
                    // Advance the LLM-billing marker to the current cumulative
                    // count so the NEXT evaluation in this run charges only its
                    // own delta (see agentLlmDelta above).
                    .goalAccountedLlmCallCount(accessor.llmCallCount())
                    // Deliberately NOT setting goalEvaluatedThisRun(true): leaving
                    // it false lets the NEXT answer be re-evaluated, turning the
                    // old single-step behaviour into run-to-completion. The loop
                    // is bounded by the per-run cap above plus the turn / LLM
                    // budgets; the dispatcher treats any terminal pass
                    // (goalEvaluatedThisRun == true) as END even if this flag
                    // lingers true under the REPLACE key strategy.
                    .needsToolCall(false)
                    .events(List.of(goalEvent("goal_followup", Map.of(
                            "goalId", String.valueOf(refreshed.getId()),
                            "prompt", followup.get(),
                            "goal", goalService.toResponse(refreshed)))));

            if (flavor == GraphFlavor.REACT) {
                // ReAct: append the followup as a fresh user message via the
                // MESSAGES APPEND strategy. ReasoningNode picks it up on its
                // next call without any followup-specific logic on its side.
                out.clearFinalAnswer()
                   .clearFinishReason()
                   .messages(List.of((Message) new UserMessage(followup.get())));
                if (reactIterationCapReached) {
                    // Hard continuation: the run's iteration budget is spent, so
                    // grant a brand-new ReAct segment. Reset the counter, clear
                    // the stale limit-exceeded draft/flag (FinalAnswerNode prefers
                    // the draft over a freshly reasoned answer) and any latched
                    // error, and advance the dedicated hard-continuation counter.
                    out.iterationCount(0)
                       .clearLimitExceededDraft()
                       .error("")
                       .goalHardContinuationCount(hardContinuationCount + 1);
                    log.info("[GoalEvaluationNode] hard continuation {}/{} for goal={} " +
                                    "(fresh ReAct iteration budget after max-iterations turn)",
                            hardContinuationCount + 1, hardCap, refreshed.getId());
                }
            } else {
                // Plan-Execute: wipe the wider mid-pass + terminal state.
                // WORKING_CONTEXT and PlanStateKeys.GOAL are intentionally
                // preserved — the next PlanGeneration pass needs them.
                out.clearFinalAnswer()
                   .clearFinishReason()
                   .clearPlanFinalSummary()
                   .clearPlanDirectAnswer()
                   .clearPlanId()
                   .clearPlanSteps()
                   .clearPlanValid()
                   .clearNeedsPlanning()
                   .clearCurrentStepIndex()
                   .clearCurrentStepTitle()
                   .clearCurrentStepResult()
                   .clearCompletedResults()
                   .clearFinalSummaryThinking()
                   .clearCurrentStepThinking();
            }
            return out.build();
        }

        // Continue but no follow-up — just record the evaluation event.
        // (helper below avoids needing a custom() factory on GraphEventPublisher.)
        return MateClawStateAccessor.output()
                .goalEvaluationResult(result.toMap())
                .goalEvaluatedThisRun(true)
                .events(List.of(goalEvent("goal_evaluated", Map.of(
                        "goalId", String.valueOf(refreshed.getId()),
                        "score", result.score(),
                        "gap", result.gap() == null ? "" : result.gap(),
                        "goal", goalService.toResponse(refreshed)))))
                .build();
    }

    /**
     * Resolve the active goal for this run: prefer the turn-start
     * {@code ACTIVE_GOAL} snapshot; if absent, fall back to a conversation
     * lookup so a goal created mid-turn (via {@code setGoal}, which only writes
     * the DB) is still evaluated on the turn it was set.
     *
     * <p>Shared by {@link #apply} and the {@code FinalAnswer/PlanSummary →
     * GoalEvaluation} routing edges so both agree on whether a goal is active.
     * The DB fallback costs one indexed lookup per terminal turn whose snapshot
     * is empty; callers should additionally gate on {@code properties.isEnabled()}
     * to skip it when the feature is off.
     */
    public static Optional<GoalEntity> resolveActiveGoal(OverAllState state, GoalService goalService) {
        MateClawStateAccessor a = new MateClawStateAccessor(state);
        Optional<Object> snapshot = a.activeGoal();
        if (snapshot.isPresent() && snapshot.get() instanceof GoalEntity ge) {
            return Optional.of(ge);
        }
        if (goalService == null) {
            return Optional.empty();
        }
        String conversationId = a.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(goalService.findActiveByConversation(conversationId));
        } catch (Throwable t) {
            log.warn("[GoalEvaluationNode] active-goal fallback lookup failed for conversation={}: {}",
                    conversationId, t.toString());
            return Optional.empty();
        }
    }

    /**
     * REACT-mode finish reasons that should neither count toward the goal nor
     * trigger a continuation:
     * <ul>
     *   <li>{@code STOPPED} — the user halted the run; don't fight them.</li>
     *   <li>{@code RETURN_DIRECT} — a tool produced the answer verbatim; this
     *       is not goal-progress reasoning work to evaluate or continue.</li>
     *   <li>{@code ERROR_FALLBACK} — a fatal error already failed the turn;
     *       re-running immediately would just re-fail.</li>
     * </ul>
     * Other terminal reasons — notably {@code MAX_ITERATIONS_REACHED} and
     * {@code EVIDENCE_INSUFFICIENT} — mean "answer produced but the goal is
     * likely unmet", which is exactly when a corrective follow-up helps, so
     * they are deliberately NOT skipped.
     */
    static boolean isHardSkipFinishReason(String finishReason) {
        return FinishReason.STOPPED.getValue().equals(finishReason)
                || FinishReason.RETURN_DIRECT.getValue().equals(finishReason)
                || FinishReason.ERROR_FALLBACK.getValue().equals(finishReason);
    }

    /**
     * True when the terminal turn hit the ReAct iteration cap. Continuing such
     * a turn requires a fresh iteration budget (a "hard continuation"), because
     * the run's shared budget is already exhausted.
     */
    static boolean isIterationCapReached(String finishReason) {
        return FinishReason.MAX_ITERATIONS_REACHED.getValue().equals(finishReason);
    }

    /** Stand-in for a missing {@code GraphEventPublisher.custom()} factory. */
    private static GraphEventPublisher.GraphEvent goalEvent(String type, Map<String, Object> data) {
        return new GraphEventPublisher.GraphEvent(type, Map.copyOf(data), System.currentTimeMillis());
    }

    /**
     * Builds a goal_evaluated event for skip paths so the frontend can
     * unconditionally flip its "evaluating" flag off after every turn that
     * has an active goal — even when the evaluator never ran. The reason
     * field lets us tell apart "normal continue" from "skipped because of
     * max iterations" in logs / future telemetry without ambiguity.
     */
    private static GraphEventPublisher.GraphEvent skippedEvent(Long goalId, String reason) {
        return goalEvent("goal_evaluated", Map.of(
                "goalId", goalId == null ? "" : String.valueOf(goalId),
                "skipped", true,
                "reason", reason == null ? "" : reason));
    }
}
