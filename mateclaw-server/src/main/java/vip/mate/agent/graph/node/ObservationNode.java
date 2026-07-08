package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.guard.ToolLoopGuard;
import vip.mate.agent.graph.observation.ObservationProcessor;
import vip.mate.agent.graph.state.MateClawStateAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * 观察节点（ReAct Observation 阶段）
 * <p>
 * 处理工具执行结果，通过 {@link ObservationProcessor} 进行标准化和截断，
 * 递增迭代计数器，并判断是否需要进入 summarizing 阶段。
 * <p>
 * 这是 maxIterations 强制执行的核心节点之一，配合 ObservationDispatcher 实现迭代控制。
 *
 * @author MateClaw Team
 */
@Slf4j
public class ObservationNode implements NodeAction {

    private final ObservationProcessor observationProcessor;
    private final vip.mate.channel.web.ChatStreamTracker streamTracker;

    /**
     * Progressive-disclosure meta-tools that perform setup, not real work. A
     * round whose entire batch is one of these is refunded its iteration (see
     * {@link MateClawStateKeys#ITERATION_REFUND_COUNT}). Mirrors the authoritative
     * set in {@code DefaultToolDisclosureService.ALWAYS_CORE}.
     */
    private static final java.util.Set<String> DISCLOSURE_TOOLS =
            java.util.Set.of("load_skill", "enable_tool");

    /** Per-run cap on iteration refunds — keeps a load-skill-only model from looping forever. */
    private static final int MAX_ITERATION_REFUNDS_PER_RUN = 3;

    /**
     * File-mutation tools whose first successful call this run triggers the
     * one-shot verification reminder — nudging the model to verify the change
     * (run tests / re-read the file) before declaring the task complete.
     * Shell/code/SQL tools are excluded: their mutating nature can't be
     * determined statically, and a false reminder is worse than none.
     */
    private static final java.util.Set<String> FILE_MUTATION_TOOLS =
            java.util.Set.of("write_file", "edit_file");

    private static final String VERIFICATION_REMINDER =
            "\n\n[✅ 验证提醒] 本轮修改了文件。在给出最终回答前，请先验证改动是否生效" +
            "（运行相关测试 / 构建命令，或重读文件确认关键内容）；" +
            "若无法验证，请在回答中明确说明「未经验证」及原因，不要声称已确认。";

    public ObservationNode(ObservationProcessor observationProcessor) {
        this(observationProcessor, null);
    }

    public ObservationNode(ObservationProcessor observationProcessor,
                           vip.mate.channel.web.ChatStreamTracker streamTracker) {
        this.observationProcessor = observationProcessor;
        this.streamTracker = streamTracker;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        MateClawStateAccessor accessor = new MateClawStateAccessor(state);

        // 检查停止标志
        String conversationId = accessor.conversationId();
        if (streamTracker != null && streamTracker.isStopRequested(conversationId)) {
            log.info("[ObservationNode] Stop requested, aborting: conversationId={}", conversationId);
            throw new CancellationException("Stream stopped by user");
        }

        int currentIteration = accessor.iterationCount();
        int maxIterations = accessor.maxIterations();

        // 提取最新的工具结果并处理
        List<ToolResponseMessage.ToolResponse> toolResults =
                state.<List<ToolResponseMessage.ToolResponse>>value(TOOL_RESULTS).orElse(List.of());

        // Iteration refund: a round whose entire batch was progressive-disclosure
        // setup (load_skill / enable_tool) did no real work, so don't charge it an
        // iteration — otherwise a tight budget loses a step to the load-then-use
        // two-step. Bounded by MAX_ITERATION_REFUNDS_PER_RUN so a model that only
        // ever loads skills can't dodge the budget forever.
        int refundCount = accessor.iterationRefundCount();
        boolean setupOnlyRound = !toolResults.isEmpty()
                && toolResults.stream().allMatch(tr -> DISCLOSURE_TOOLS.contains(tr.name()));
        boolean refundIteration = setupOnlyRound && refundCount < MAX_ITERATION_REFUNDS_PER_RUN;
        int nextIteration = refundIteration ? currentIteration : currentIteration + 1;

        if (refundIteration) {
            log.info("[ObservationNode] Iteration refunded (setup-only round, refunds {}/{}); staying at {}/{}",
                    refundCount + 1, MAX_ITERATION_REFUNDS_PER_RUN, nextIteration, maxIterations);
        } else {
            log.info("[ObservationNode] Iteration {}/{}", nextIteration, maxIterations);
        }

        // 将每个工具结果通过 ObservationProcessor 标准化和截断
        List<String> processedObservations = toolResults.stream()
                .map(tr -> observationProcessor.process(tr.name(), tr.responseData()))
                .collect(Collectors.toList());

        // 合并为单条观察记录
        String combinedObservation = String.join("\n---\n", processedObservations);

        // Tool-call loop guard: signature-level repetition detection across the
        // run (identical-arg failures / per-tool failures / idempotent
        // no-progress). Warnings are appended so the model can self-correct on
        // the next reasoning turn; crossing a halt threshold routes to the
        // graceful wrap-up via the existing ERROR path.
        List<AssistantMessage.ToolCall> toolCalls =
                state.<List<AssistantMessage.ToolCall>>value(TOOL_CALLS).orElse(List.of());
        ToolLoopGuard.Evaluation loopGuard = ToolLoopGuard.evaluate(
                accessor.toolLoopStats(), toolCalls, toolResults);
        for (String warning : loopGuard.warnings()) {
            combinedObservation += "\n\n" + warning;
            log.info("[ObservationNode] Loop-guard warning injected: {}", warning);
        }

        // One-shot post-mutation verification reminder: the first successful
        // file mutation this run asks the model to verify before wrapping up.
        boolean injectVerificationReminder = !accessor.mutationReminderInjected()
                && toolResults.stream().anyMatch(tr -> FILE_MUTATION_TOOLS.contains(tr.name())
                        && !ToolLoopGuard.isFailure(tr.responseData()));
        if (injectVerificationReminder) {
            combinedObservation += VERIFICATION_REMINDER;
            log.info("[ObservationNode] Post-mutation verification reminder injected");
        }

        // Budget Pressure Warning：接近上限时注入警告到工具结果中
        // LLM 下一轮 reasoning 时能看到，从而主动收束，而非被硬性截断
        if (maxIterations > 0) {
            int progress = (int) ((double) nextIteration / maxIterations * 100);
            if (progress >= 90) {
                combinedObservation += "\n\n[⚠️ 预算警告] 当前迭代 " + nextIteration + "/" + maxIterations +
                        "，仅剩 " + (maxIterations - nextIteration) + " 步。" +
                        "请立即提供最终回答，不要再调用工具（除非绝对必要）。";
                log.info("[ObservationNode] Budget WARNING injected: {}/{} ({}%)",
                        nextIteration, maxIterations, progress);
            } else if (progress >= 70) {
                combinedObservation += "\n\n[📊 预算提示] 当前迭代 " + nextIteration + "/" + maxIterations +
                        "，剩余 " + (maxIterations - nextIteration) + " 步。请开始整合已有信息，准备给出回答。";
                log.info("[ObservationNode] Budget caution injected: {}/{} ({}%)",
                        nextIteration, maxIterations, progress);
            }
        }

        // 手动累加观察历史（OBSERVATION_HISTORY 使用 REPLACE 策略，以便 SummarizingNode 可清空）
        List<String> existingHistory = accessor.observationHistory();
        List<String> updatedHistory = new ArrayList<>(existingHistory);
        updatedHistory.add(combinedObservation);

        // 检测重复观察：最近 N 条完全相同则强制终止
        boolean duplicateObservation = detectDuplicateObservations(existingHistory, combinedObservation, 3);
        if (duplicateObservation) {
            log.warn("[ObservationNode] Detected {} consecutive identical observations, " +
                    "forcing limit exceeded to break loop", 3);
        }

        // 判断是否需要 summarize
        boolean shouldSummarize = observationProcessor.needsSummarizing(
                existingHistory, combinedObservation);

        // 统计工具调用次数
        int newToolCallCount = accessor.toolCallCount() + toolResults.size();

        if (shouldSummarize) {
            log.info("[ObservationNode] Marking shouldSummarize=true (history={} entries, " +
                            "current={} chars, total tool calls={})",
                    existingHistory.size(), combinedObservation.length(), newToolCallCount);
        }

        var builder = MateClawStateAccessor.output()
                .iterationCount(nextIteration)
                .put(OBSERVATION_HISTORY, updatedHistory)
                .shouldSummarize(shouldSummarize)
                .toolCallCount(newToolCallCount)
                .toolLoopStats(loopGuard.stats());

        if (refundIteration) {
            builder.iterationRefundCount(refundCount + 1);
        }

        if (injectVerificationReminder) {
            builder.mutationReminderInjected(true);
        }

        // Close out the iteration we just observed. We use currentIteration
        // (not nextIteration) so the index pairs with whatever
        // iteration_start the ReasoningNode emitted at the top of this turn.
        // Char totals are best-effort: ObservationNode doesn't see the LLM
        // delta stream directly, so 0/0 is acceptable for now — consumers
        // that care fall back to summing the deltas themselves.
        List<GraphEventPublisher.GraphEvent> events = new ArrayList<>();
        if (streamTracker == null || streamTracker.isIterationEventsEnabled()) {
            events.add(GraphEventPublisher.iterationEnd(currentIteration, "parent", null, 0, 0));
        }
        // Surface loop-guard interventions to the user: the observation-text
        // injection above is LLM-only, so mirror each warning (and a halt) as
        // a "warning" graph event — the accumulator persists it under
        // metadata.warnings and rebroadcasts it live on SSE.
        for (String warning : loopGuard.warnings()) {
            events.add(GraphEventPublisher.warning(warning, "loop_guard"));
        }
        if (loopGuard.shouldHalt() && !duplicateObservation) {
            events.add(GraphEventPublisher.warning(
                    "[⛔ 循环熔断] " + loopGuard.haltReason() + "，已提前收尾。", "loop_guard"));
        }
        if (!events.isEmpty()) {
            builder.events(events);
        }

        // 重复观察时标记错误，让 ObservationDispatcher 路由到 limitExceededNode
        if (duplicateObservation) {
            builder.put(ERROR, "连续 3 次工具调用返回相同结果，已强制终止循环");
        } else if (loopGuard.shouldHalt()) {
            log.warn("[ObservationNode] Loop-guard halt: {}", loopGuard.haltReason());
            builder.put(ERROR, loopGuard.haltReason());
        }

        return builder.build();
    }

    /**
     * 检测最近 N 条观察是否与当前观察完全相同
     */
    private boolean detectDuplicateObservations(List<String> history, String current, int threshold) {
        if (history.size() < threshold - 1 || current == null || current.isEmpty()) {
            return false;
        }
        // 检查 history 的最后 (threshold-1) 条是否都与 current 相同
        int start = history.size() - (threshold - 1);
        for (int i = start; i < history.size(); i++) {
            if (!current.equals(history.get(i))) {
                return false;
            }
        }
        return true;
    }
}
