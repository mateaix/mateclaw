package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.GraphEventPublisher;
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
        int nextIteration = currentIteration + 1;

        log.info("[ObservationNode] Iteration {}/{}", nextIteration, maxIterations);

        // 提取最新的工具结果并处理
        List<ToolResponseMessage.ToolResponse> toolResults =
                state.<List<ToolResponseMessage.ToolResponse>>value(TOOL_RESULTS).orElse(List.of());

        // 将每个工具结果通过 ObservationProcessor 标准化和截断
        List<String> processedObservations = toolResults.stream()
                .map(tr -> observationProcessor.process(tr.name(), tr.responseData()))
                .collect(Collectors.toList());

        // 合并为单条观察记录
        String combinedObservation = String.join("\n---\n", processedObservations);

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
                .toolCallCount(newToolCallCount);

        // Close out the iteration we just observed. We use currentIteration
        // (not nextIteration) so the index pairs with whatever
        // iteration_start the ReasoningNode emitted at the top of this turn.
        // Char totals are best-effort: ObservationNode doesn't see the LLM
        // delta stream directly, so 0/0 is acceptable for now — consumers
        // that care fall back to summing the deltas themselves.
        if (streamTracker == null || streamTracker.isIterationEventsEnabled()) {
            builder.events(List.of(
                    GraphEventPublisher.iterationEnd(currentIteration, "parent", null, 0, 0)));
        }

        // 重复观察时标记错误，让 ObservationDispatcher 路由到 limitExceededNode
        if (duplicateObservation) {
            builder.put(ERROR, "连续 3 次工具调用返回相同结果，已强制终止循环");
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
