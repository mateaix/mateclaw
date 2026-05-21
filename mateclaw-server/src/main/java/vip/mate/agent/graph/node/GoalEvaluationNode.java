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
 * <p>Per RFC 48 v3 §3.3, evaluation runs on a settled terminal answer so
 * upstream finishReason / evidence checks are already authoritative. The
 * node:
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
    private final ConversationWindowManager windowManager; // unused PR2, kept for PR5
    private final ConversationService conversationService; // unused PR2, kept for PR5
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
        // Master kill switch — node stays inert until PR5 flips this.
        if (!properties.isEnabled()) {
            return Map.of();
        }

        MateClawStateAccessor accessor = new MateClawStateAccessor(state);

        Optional<Object> goalOpt = accessor.activeGoal();
        if (goalOpt.isEmpty()) {
            return Map.of();
        }

        // Re-entry guard — the FinalAnswer→GoalEvaluation conditional edge
        // also checks this, but defence in depth pays for itself here.
        if (accessor.goalEvaluatedThisRun()) {
            return Map.of();
        }

        // ReAct path: FinalAnswerNode wrote a canonical finishReason that
        // determines whether this turn counts. Plan-Execute usually doesn't
        // set finishReason on the happy path, so we only enforce these
        // exit conditions in REACT mode + the universal awaiting_approval
        // gate that both flavors share.
        if (flavor == GraphFlavor.REACT) {
            String fr = accessor.finishReason();
            if (FinishReason.EVIDENCE_INSUFFICIENT.getValue().equals(fr)
                    || FinishReason.STOPPED.getValue().equals(fr)
                    || FinishReason.ERROR_FALLBACK.getValue().equals(fr)
                    || FinishReason.RETURN_DIRECT.getValue().equals(fr)
                    || FinishReason.MAX_ITERATIONS_REACHED.getValue().equals(fr)) {
                log.debug("[GoalEvaluationNode] skipping evaluation (REACT finishReason={})", fr);
                return MateClawStateAccessor.output()
                        .goalEvaluatedThisRun(true)
                        .build();
            }
        }
        if (accessor.awaitingApproval()) {
            return MateClawStateAccessor.output()
                    .goalEvaluatedThisRun(true)
                    .build();
        }

        Object goalObj = goalOpt.get();
        if (!(goalObj instanceof GoalEntity goal)) {
            log.warn("[GoalEvaluationNode] ACTIVE_GOAL is not a GoalEntity: {}", goalObj.getClass());
            return MateClawStateAccessor.output()
                    .goalEvaluatedThisRun(true)
                    .build();
        }

        String terminal = accessor.terminalAnswer();
        if (terminal.isEmpty()) {
            log.warn("[GoalEvaluationNode] terminalAnswer empty (flavor={}); skipping evaluation", flavor);
            return MateClawStateAccessor.output()
                    .goalEvaluatedThisRun(true)
                    .build();
        }

        // Build a thin recent-messages slice for the evaluator prompt.
        List<Message> recent = accessor.messages();
        int max = properties.getEvaluatorContextMessages();
        if (recent.size() > max) {
            recent = recent.subList(recent.size() - max, recent.size());
        }

        GoalEvaluationResult result = evaluationService.evaluate(goal, recent, terminal);

        // Pre-eval agent_llm count snapshot — the bookkeeping helper folds
        // it into the per-goal counter so future turns see growing usage.
        int agentLlmDelta = accessor.llmCallCount();
        int evalLlmDelta = result.llmCallsConsumed();
        goalService.recordEvaluation(goal.getId(), result, agentLlmDelta, evalLlmDelta);

        GoalEntity refreshed = goalService.getById(goal.getId());

        // Decision branches.
        if (result.completed() || result.score() >= 0.95) {
            goalService.markCompleted(refreshed.getId(), result);
            return MateClawStateAccessor.output()
                    .goalEvaluationResult(result.toMap())
                    .goalEvaluatedThisRun(true)
                    .events(List.of(goalEvent("goal_completed", Map.of(
                            "goalId", String.valueOf(refreshed.getId()),
                            "score", result.score()))))
                    .build();
        }

        if (goalService.isBudgetExhausted(refreshed)) {
            String reason = goalService.exhaustionReason(refreshed);
            goalService.markExhausted(refreshed.getId(), reason);
            return MateClawStateAccessor.output()
                    .goalEvaluationResult(result.toMap())
                    .goalEvaluatedThisRun(true)
                    .events(List.of(goalEvent("goal_exhausted", Map.of(
                            "goalId", String.valueOf(refreshed.getId()),
                            "turnsUsed", refreshed.getTurnsUsed(),
                            "agentLlmCallsUsed", refreshed.getAgentLlmCallsUsed(),
                            "evalLlmCallsUsed", refreshed.getEvalLlmCallsUsed(),
                            "totalLlmCallsUsed", refreshed.totalLlmCallsUsed(),
                            "reason", reason))))
                    .build();
        }

        Optional<String> followup = followupService.maybeBuildFollowup(refreshed, result);
        if (followup.isPresent()) {
            goalService.recordFollowupInjected(refreshed.getId(), followup.get());
            MateClawStateAccessor.OutputBuilder out = MateClawStateAccessor.output()
                    .goalEvaluationResult(result.toMap())
                    .goalFollowupInjected(true)
                    .goalFollowupPrompt(followup.get())
                    .goalEvaluatedThisRun(true)
                    .needsToolCall(false)
                    .events(List.of(goalEvent("goal_followup", Map.of(
                            "goalId", String.valueOf(refreshed.getId()),
                            "prompt", followup.get()))));

            if (flavor == GraphFlavor.REACT) {
                // ReAct: append the followup as a fresh user message via the
                // MESSAGES APPEND strategy. ReasoningNode picks it up on its
                // next call without any followup-specific logic on its side.
                out.clearFinalAnswer()
                   .clearFinishReason()
                   .messages(List.of((Message) new UserMessage(followup.get())));
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
                        "gap", result.gap() == null ? "" : result.gap()))))
                .build();
    }

    /** Stand-in for a missing {@code GraphEventPublisher.custom()} factory. */
    private static GraphEventPublisher.GraphEvent goalEvent(String type, Map<String, Object> data) {
        return new GraphEventPublisher.GraphEvent(type, Map.copyOf(data), System.currentTimeMillis());
    }
}
