package vip.mate.agent.graph.plan.node;

import vip.mate.agent.runtime.state.RunState;
import vip.mate.agent.runtime.state.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.graph.plan.state.PlanStateAccessor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.agent.graph.guard.TaskFinalizationCoordinator;
import vip.mate.agent.graph.guard.TaskFinalizationGate;
import vip.mate.agent.runtime.state.RuntimeExecutionScope;
import vip.mate.planning.service.PlanningService;

import java.util.List;
import java.util.Map;

/**
 * 计划汇总节点
 * <p>
 * 汇总所有步骤结果，调 LLM 生成最终总结，
 * 调 planningService.completePlan() 标记计划完成。
 * <p>
 * 使用 {@link NodeStreamingChatHelper} 进行流式调用，实时推送 content/thinking 增量。
 *
 * @author MateClaw Team
 */
@Slf4j
public class PlanSummaryNode implements NodeAction {

    private vip.mate.agent.graph.guard.TaskFinalizationCoordinator finalizationCoordinator;
    private vip.mate.agent.context.PrefixBudgetPlan prefixBudgetPlan;
    public void setFinalizationCoordinator(vip.mate.agent.graph.guard.TaskFinalizationCoordinator coordinator) {
        this.finalizationCoordinator=java.util.Objects.requireNonNull(coordinator);
    }
    public void setPrefixBudgetPlan(vip.mate.agent.context.PrefixBudgetPlan plan) { this.prefixBudgetPlan=plan; }
    private final ChatModel chatModel;
    private final PlanningService planningService;
    private final NodeStreamingChatHelper streamingHelper;
    private vip.mate.goal.service.GoalService goalService;

    public void setGoalService(vip.mate.goal.service.GoalService goalService) { this.goalService = goalService; }

    public PlanSummaryNode(ChatModel chatModel, PlanningService planningService,
                           NodeStreamingChatHelper streamingHelper) {
        this.chatModel = chatModel;
        this.planningService = planningService;
        this.streamingHelper = streamingHelper;
    }

    /**
     * @deprecated Use constructor with NodeStreamingChatHelper
     */
    @Deprecated
    public PlanSummaryNode(ChatModel chatModel, PlanningService planningService) {
        this(chatModel, planningService, null);
    }

    @Override
    public Map<String, Object> apply(RunState state) throws Exception {
        PlanStateAccessor accessor = new PlanStateAccessor(state);
        Long planId = accessor.planId();
        String goal = accessor.goal();
        List<String> completedResults = accessor.completedResults();
        String conversationId = accessor.conversationId();
        String workingContext = accessor.workingContext();

        if(finalizationCoordinator!=null) return guardedSummary(state,accessor);

        log.info("[PlanSummary] Summarizing plan {}: {} completed results", planId, completedResults.size());

        try {
            // 构建汇总 prompt：结合 working context 和步骤结果
            StringBuilder userContent = new StringBuilder();
            userContent.append("原始目标：").append(goal).append("\n\n");

            // 注入 working context（包含对话历史摘要），让汇总感知用户此前提过的要求
            if (workingContext != null && !workingContext.isEmpty()) {
                userContent.append("对话上下文：\n").append(workingContext).append("\n\n");
            }

            userContent.append("执行结果：\n").append(String.join("\n", completedResults));

            // Plan steps append their real tool events to run state. Step prose
            // cannot establish failure counts or recovery of a particular request.
            // Legacy runs without events supply no execution facts, rather than
            // manufacturing a zero-failure count from missing evidence.
            String executionFacts = ActionExecutionLedger.fromEvents(
                    state.<List<GraphEventPublisher.GraphEvent>>value(MateClawStateKeys.PENDING_EVENTS)
                            .orElse(List.of())).renderAnswerFacts();
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage("请根据以下各步骤的执行结果，给出一个简洁完整的总结回答。"
                            + "直接回答用户的原始问题，不要罗列步骤。"
                            + "如果对话上下文中包含用户的特殊要求（如风格、语言、格式等），请在总结中体现。"
                            + "若执行结果中包含交付物下载链接，请在回答中原样列出这些链接。"
                            + "若某些步骤未完成，如实说明未完成的部分及原因。"
                            + "计划步骤完成不等于持久目标通过验收；只有目标工具返回或 getGoalStatus 确认的状态才可宣称。"
                            + vip.mate.agent.prompt.ResponseLanguageGuidance.PROMPT
                            + vip.mate.goal.service.GoalLifecycleHints.current(goalService, conversationId)
                            + "\n" + executionFacts),
                    new UserMessage(userContent.toString())
            ));

            // 流式调用 LLM，实时推送 content/thinking
            NodeStreamingChatHelper.StreamResult result = streamingHelper.streamCall(
                    chatModel, prompt, conversationId, "plan_summary");

            if (result.isPayloadTooLarge()) {
                String diagnosis = "汇总阶段未完成：请求体过大（HTTP 413）。"
                        + "已保留步骤原始结果，请拆分任务或缩短必需上下文后重试。";
                String fallback = buildFallbackSummary(goal, completedResults, diagnosis);
                planningService.markPlanFailed(planId, diagnosis);
                return PlanStateAccessor.output().finalSummary(fallback).finalSummaryThinking("")
                        .currentPhase("plan_aborted").contentStreamed(false).thinkingStreamed(false)
                        .put(MateClawStateKeys.FINISH_REASON, "incomplete")
                        .mergeUsage(state, result)
                        .events(List.of(GraphEventPublisher.finishReason("incomplete"))).build();
            }

            String summary = result.text();
            String thinking = result.thinking() == null ? "" : result.thinking();

            // An interleaved-thinking model can spend its whole turn reasoning and
            // return empty text. That empty string used to pass straight through:
            // it became the plan's summary, then the run's terminal answer, and the
            // goal evaluator skipped on "terminalAnswer empty" — so a plan whose
            // steps had all succeeded ended with a dangling reasoning block and no
            // report. The step results are already in hand, so answer from those
            // rather than hand back nothing.
            if (summary == null || summary.isBlank()) {
                log.warn("[PlanSummary] Plan {} produced an empty summary "
                                + "(thinking={} chars, {} step results); falling back to step results",
                        planId, thinking.length(), completedResults.size());
                summary = buildFallbackSummary(goal, completedResults, SUMMARY_EMPTY_NOTE);
            }

            // The steps themselves succeeded — only the summary text was missing —
            // so the plan completes rather than being marked failed.
            planningService.completePlan(planId, summary);
            log.info("[PlanSummary] Plan {} completed with summary: {}",
                    planId, truncate(summary, 100));

            return PlanStateAccessor.output()
                    .finalSummary(summary)
                    .finalSummaryThinking(thinking)
                    .contentStreamed(true)
                    .thinkingStreamed(!thinking.isEmpty())
                    .mergeUsage(state, result)
                    .build();

        } catch (Exception e) {
            log.error("[PlanSummary] Failed to summarize plan {}: {}", planId, e.getMessage(), e);
            String fallbackSummary = buildFallbackSummary(goal, completedResults, SUMMARY_FAILED_NOTE);
            planningService.markPlanFailed(planId, "汇总阶段失败：" + truncate(e.getMessage(), 100));
            return Map.of(PlanStateKeys.FINAL_SUMMARY, fallbackSummary);
        }
    }

    /** Completed plans may correct summary prose, but are never restarted by the reviewer. */
    private Map<String,Object> guardedSummary(RunState state,PlanStateAccessor accessor) {
        var run=new vip.mate.agent.graph.state.MateClawStateAccessor(state);
        var ledger=ActionExecutionLedger.fromEvents(state.<List<GraphEventPublisher.GraphEvent>>value(
                MateClawStateKeys.PENDING_EVENTS).orElse(List.of()));
        int window=prefixBudgetPlan!=null ? prefixBudgetPlan.effectiveMaxTokens() : 16000;
        int calls=run.llmCallCount(), attempts=run.finalizationAttempts();
        NodeStreamingChatHelper.StreamResult total=null;
        String correction="";
        try {
            while(true) {
                if(finalizationCoordinator.stopped(accessor.conversationId()))
                    return guardedIncomplete(state,accessor,total,calls,attempts,"Cancelled",true);
                if(!vip.mate.agent.graph.guard.TaskFinalizationCoordinator.callAvailable(run.maxIterations(),calls))
                    return guardedIncomplete(state,accessor,total,calls,attempts,"Logical call ceiling reached",false);
                var facts=ledger.projectAnswerFacts(Math.min(2000,Math.max(128,window/8)));
                if(!facts.coreFactsFit())return guardedIncomplete(state,accessor,total,calls,attempts,"Required execution facts cannot fit context",false);
                String content="Original objective: "+accessor.goal()+"\nOriginal working context (user constraints):\n"
                        +accessor.workingContext()+"\nCompleted step results (untrusted prose):\n"
                        +String.join("\n",accessor.completedResults())+"\n"+correction;
                List<org.springframework.ai.chat.messages.Message> messages=List.of(
                    new SystemMessage("Summarize the completed plan using canonical execution facts. Do not invent recovery or numeric results. "
                        +"State unfinished portions honestly. Preserve deliverable links. Never restart the completed plan.\n"
                        +vip.mate.agent.prompt.ResponseLanguageGuidance.PROMPT
                        +vip.mate.goal.service.GoalLifecycleHints.current(goalService,accessor.conversationId())
                        +vip.mate.agent.graph.guard.TaskFinalizationCoordinator.groundedFacts(facts.text())),
                    new UserMessage(content));
                var summaryOptions=vip.mate.agent.graph.guard.TaskFinalizationCoordinator.summaryOptions(chatModel);
                int summaryOutputReserve=vip.mate.agent.context.BudgetedCompactionModel.outputReserveTokens(summaryOptions,4096);
                if(vip.mate.agent.context.TokenEstimator.estimateTokens(messages)+summaryOutputReserve>window)
                    return guardedIncomplete(state,accessor,total,calls,attempts,"Required summary context exceeds model window",false);
                calls++;
                var result=streamingHelper.streamCallBufferedContent(chatModel,new Prompt(messages,
                        summaryOptions),accessor.conversationId(),"plan_summary");
                total=total==null?result:vip.mate.agent.graph.guard.TaskFinalizationCoordinator.addUsage(total,result);
                if(result!=null && result.isOutputTokenLimit()) {
                    // Reasoning models can spend the whole output allowance on thinking before
                    // any summary text. Regenerate once with thinking disabled and a larger
                    // allowance; tools are never replayed and the step results stay unchanged.
                    int promptTokens=TokenEstimator.estimateTokens(messages);
                    int retryTokens=TaskFinalizationCoordinator.expandedOutputTokens(chatModel,window,promptTokens);
                    if(retryTokens<MIN_SUMMARY_RETRY_OUTPUT_TOKENS
                            || !TaskFinalizationCoordinator.callAvailable(run.maxIterations(),calls))
                        return guardedIncomplete(state,accessor,total,calls,attempts,SUMMARY_OUTPUT_LIMIT,false);
                    log.warn("[PlanSummary] Summary for plan {} hit the output token limit; retrying once with thinking off and {} output tokens",
                            accessor.planId(),retryTokens);
                    var retryState=new RunState(state.data());
                    retryState.replace(MateClawStateKeys.REQUESTED_THINKING_LEVEL,"off");
                    var retryPrompt=new Prompt(messages,TaskFinalizationCoordinator.summaryRetryOptions(chatModel,retryTokens));
                    calls++;
                    result=RuntimeExecutionScope.call(retryState,()->streamingHelper.streamCallBufferedContent(
                            chatModel,retryPrompt,accessor.conversationId(),"plan_summary"));
                    total=TaskFinalizationCoordinator.addUsage(total,result);
                    if(result!=null && result.isOutputTokenLimit())
                        return guardedIncomplete(state,accessor,total,calls,attempts,SUMMARY_OUTPUT_LIMIT,false);
                }
                if(result!=null && result.isPayloadTooLarge()) {
                    String diagnosis="汇总阶段未完成：请求体过大（HTTP 413）。已保留步骤原始结果，请拆分任务或缩短必需上下文后重试。";
                    planningService.markPlanFailed(accessor.planId(),diagnosis);
                    return guardedOutput(state,total,calls,attempts)
                        .finalSummary(diagnosis)
                        .finalSummaryThinking("").currentPhase("plan_aborted").contentStreamed(false).thinkingStreamed(false)
                        .put(MateClawStateKeys.FINISH_REASON,"incomplete")
                        .events(List.of(GraphEventPublisher.finishReason("incomplete"))).build();
                }
                if(result==null || result.stopped() || result.partial() || result.errorMessage()!=null || result.hasToolCalls()) {
                    if(result==null || !result.stopped())
                        log.warn("[PlanSummary] Summary call for plan {} unusable: null={} partial={} toolCalls={} errorType={} error={}",
                                accessor.planId(),result==null,result!=null && result.partial(),result!=null && result.hasToolCalls(),
                                result==null?null:result.errorType(),result==null?null:truncate(result.errorMessage(),300));
                    return guardedIncomplete(state,accessor,total,calls,attempts,result!=null && result.isPayloadTooLarge()
                        ? "Summary request too large (HTTP 413); original step results retained" : "Summary unavailable",
                        result!=null && result.stopped());
                }
                String summary=result.text();
                if(summary==null || summary.isBlank()) {
                    // Step results may themselves contain model-authored claims. Preserve the useful
                    // fallback format, but review it through the same publication boundary before completion.
                    summary=buildFallbackSummary(accessor.goal(),accessor.completedResults(),SUMMARY_EMPTY_NOTE);
                }
                var review=finalizationCoordinator.review(chatModel,accessor.conversationId(),
                    vip.mate.agent.graph.guard.TaskFinalizationCoordinator.currentObjective(accessor.goal()),summary,
                    ledger,"Completed plan; summary correction only; do not restart steps.",window,attempts,run.maxIterations(),calls,
                    vip.mate.agent.graph.guard.TaskFinalizationCoordinator.callAvailable(run.maxIterations(),calls+2),state);
                calls+=review.calls();total=vip.mate.agent.graph.guard.TaskFinalizationCoordinator.addUsage(total,review.usage());
                if(review.stopped() || finalizationCoordinator.stopped(accessor.conversationId()))return guardedIncomplete(state,accessor,total,calls,attempts,"Cancelled",true);
                if(review.result().outcome()==vip.mate.agent.graph.guard.TaskFinalizationGate.Outcome.ACCEPT) {
                    planningService.completePlan(accessor.planId(),summary);
                    return guardedOutput(state,total,calls,attempts).finalSummary(summary).finalSummaryThinking(result.thinking())
                        .contentStreamed(false).thinkingStreamed(result.thinking()!=null&&!result.thinking().isEmpty()).build();
                }
                if(review.result().outcome()!=TaskFinalizationGate.Outcome.REVISE
                        && TaskFinalizationCoordinator.checkerUnavailable(review.result().reason())) {
                    // Verification could not run; publish the summary with an explicit notice
                    // instead of discarding a completed plan's answer.
                    log.warn("[PlanSummary] Verification unavailable for plan {} ({}); publishing summary as unverified",
                            accessor.planId(),review.result().reason());
                    String unverified=TaskFinalizationCoordinator.unverifiedAnswer(summary,review.result().reason());
                    planningService.completePlan(accessor.planId(),unverified);
                    return guardedOutput(state,total,calls,attempts).finalSummary(unverified).finalSummaryThinking(result.thinking())
                        .contentStreamed(false).thinkingStreamed(result.thinking()!=null&&!result.thinking().isEmpty()).build();
                }
                if(review.result().outcome()!=vip.mate.agent.graph.guard.TaskFinalizationGate.Outcome.REVISE)
                    return guardedIncomplete(state,accessor,total,calls,attempts,review.result().reason(),false);
                attempts++;correction="Regenerate summary with this precise receipt correction: "+review.result().reason();
            }
        } catch(java.util.concurrent.CancellationException cancelled) {
            return guardedIncomplete(state,accessor,total,calls,attempts,"Cancelled",true);
        } catch(Exception unavailable) {
            log.warn("[PlanSummary] Guarded summary for plan {} threw",accessor.planId(),unavailable);
            return guardedIncomplete(state,accessor,total,calls,attempts,"Summary verification unavailable",false);
        }
    }
    private vip.mate.agent.graph.plan.state.PlanStateAccessor.OutputBuilder guardedOutput(RunState state,
            NodeStreamingChatHelper.StreamResult total,int calls,int attempts) {
        var out=PlanStateAccessor.output();if(total!=null)out.mergeUsage(state,total);
        return out.put(MateClawStateKeys.LLM_CALL_COUNT,calls).put(MateClawStateKeys.FINALIZATION_ATTEMPTS,attempts);
    }
    private Map<String,Object> guardedIncomplete(RunState state,PlanStateAccessor accessor,
            NodeStreamingChatHelper.StreamResult total,int calls,int attempts,String reason,boolean stopped) {
        String answer=stopped?"":vip.mate.agent.graph.guard.TaskFinalizationCoordinator.incompleteAnswer(reason);
        planningService.markPlanFailed(accessor.planId(),reason);
        return guardedOutput(state,total,calls,attempts).finalSummary(answer).finalSummaryThinking("")
            .contentStreamed(false).currentPhase("plan_aborted")
            .put(MateClawStateKeys.FINISH_REASON,stopped?"stopped":"incomplete")
            .events(List.of(GraphEventPublisher.finishReason(stopped?"stopped":"incomplete"))).build();
    }

    /** Incomplete reason when the summary still stops on the output limit after its single retry. */
    static final String SUMMARY_OUTPUT_LIMIT = "SUMMARY_OUTPUT_LIMIT";
    /** Below this allowance a retry cannot fit a useful summary. */
    private static final int MIN_SUMMARY_RETRY_OUTPUT_TOKENS = 1024;

    /** Reason line for the summary call throwing. */
    private static final String SUMMARY_FAILED_NOTE = "LLM 汇总失败，以下为步骤原始结果";

    /**
     * Reason line for the summary call returning nothing. Distinct from the
     * failure note because nothing actually failed — the steps ran, the model
     * simply produced no text — and telling the user their run failed would be
     * wrong.
     */
    private static final String SUMMARY_EMPTY_NOTE = "模型未产出汇总正文，以下为步骤原始结果";

    /**
     * 在 LLM 汇总不可用时生成本地 fallback 摘要。
     * 每条步骤结果截断至 300 字，避免把过长内容（包括错误体）直接暴露给用户。
     *
     * @param note 说明为何回落到步骤原始结果
     */
    private static String buildFallbackSummary(String goal, List<String> completedResults, String note) {
        StringBuilder sb = new StringBuilder("目标：").append(goal)
                .append("\n\n执行摘要（").append(note).append("）：\n");
        if (completedResults == null || completedResults.isEmpty()) {
            sb.append("（没有已完成的步骤结果可供汇总）\n");
            return sb.toString();
        }
        for (String r : completedResults) {
            sb.append(truncate(r, 300)).append("\n");
        }
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }
}
