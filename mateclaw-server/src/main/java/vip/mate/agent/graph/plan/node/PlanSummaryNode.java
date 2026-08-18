package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.plan.state.PlanStateAccessor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
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

    private final ChatModel chatModel;
    private final PlanningService planningService;
    private final NodeStreamingChatHelper streamingHelper;

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
    public Map<String, Object> apply(OverAllState state) throws Exception {
        PlanStateAccessor accessor = new PlanStateAccessor(state);
        Long planId = accessor.planId();
        String goal = accessor.goal();
        List<String> completedResults = accessor.completedResults();
        String conversationId = accessor.conversationId();
        String workingContext = accessor.workingContext();

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

            Prompt prompt = new Prompt(List.of(
                    new SystemMessage("请根据以下各步骤的执行结果，给出一个简洁完整的总结回答。"
                            + "直接回答用户的原始问题，不要罗列步骤。"
                            + "如果对话上下文中包含用户的特殊要求（如风格、语言、格式等），请在总结中体现。"
                            + "若执行结果中包含交付物下载链接，请在回答中原样列出这些链接。"
                            + "若某些步骤未完成，如实说明未完成的部分及原因。"),
                    new UserMessage(userContent.toString())
            ));

            // 流式调用 LLM，实时推送 content/thinking
            NodeStreamingChatHelper.StreamResult result = streamingHelper.streamCall(
                    chatModel, prompt, conversationId, "plan_summary");

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
