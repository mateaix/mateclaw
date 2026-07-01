package vip.mate.agent.graph.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;
import vip.mate.agent.BaseAgent;
import vip.mate.agent.graph.state.MateClawStateAccessor;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * 观察路由（3 路分支，迭代控制核心）
 * <p>
 * 决定 ReAct 循环在 Observation 后的走向：
 * <ol>
 *   <li>迭代超限 → limitExceededNode（强制终止）</li>
 *   <li>需要总结 → summarizingNode（观察够多/结果太长）</li>
 *   <li>继续循环 → reasoningNode</li>
 * </ol>
 * <p>
 * 这是 maxIterations 字段的核心执行点。
 *
 * @author MateClaw Team
 */
@Slf4j
public class ObservationDispatcher implements EdgeAction {

    @Override
    public String apply(OverAllState state) throws Exception {
        MateClawStateAccessor accessor = new MateClawStateAccessor(state);

        int currentIteration = accessor.iterationCount();
        int maxIterations = accessor.maxIterations();

        // 0. 审批等待检查 — Graph 必须立即终止，由 Replay 继续
        if (accessor.awaitingApproval()) {
            log.info("[ObservationDispatcher] AWAITING_APPROVAL=true, terminating graph " +
                    "(replay will continue after user decision), iteration {}/{}", currentIteration, maxIterations);
            return FINAL_ANSWER_NODE;
        }

        // RFC-052: returnDirect short-circuit — highest priority after approval.
        // Any tool in the latest batch declared returnDirect=true: skip the next
        // LLM call entirely and route straight to FinalAnswerNode, which will
        // assemble the final answer from DIRECT_TOOL_OUTPUTS.
        if (accessor.returnDirectTriggered()) {
            log.info("[ObservationDispatcher] RETURN_DIRECT_TRIGGERED=true, " +
                    "routing to finalAnswerNode (skipping next LLM call), iteration {}/{}",
                    currentIteration, maxIterations);
            return FINAL_ANSWER_NODE;
        }

        // 1. 迭代超限检查
        //    maxIterations=0 表示用户配置不限制，但仍以 MAX_ITERATIONS_HARD_CEILING
        //    作为硬性兜底，防止 agent 无限循环卡死。
        int effectiveMaxIterations = maxIterations > 0
                ? maxIterations
                : BaseAgent.MAX_ITERATIONS_HARD_CEILING;
        if (currentIteration >= effectiveMaxIterations) {
            if (maxIterations <= 0) {
                log.warn("[ObservationDispatcher] Unlimited-mode agent hit hard ceiling " +
                        "({}) at iteration {}, routing to limitExceededNode", effectiveMaxIterations, currentIteration);
            } else {
                log.warn("[ObservationDispatcher] Max iterations ({}) reached at iteration {}, " +
                        "routing to limitExceededNode", effectiveMaxIterations, currentIteration);
            }
            return LIMIT_EXCEEDED_NODE;
        }

        // 2. 错误检查
        if (accessor.hasError()) {
            log.warn("[ObservationDispatcher] Error detected, routing to limitExceededNode: {}",
                    accessor.error());
            return LIMIT_EXCEEDED_NODE;
        }

        // 3. 需要总结（ObservationNode 已判断并设置 shouldSummarize）
        if (accessor.shouldSummarize()) {
            log.info("[ObservationDispatcher] shouldSummarize=true, routing to summarizingNode " +
                            "(iteration {}/{}, observations={} entries)",
                    currentIteration, maxIterations, accessor.observationHistory().size());
            return SUMMARIZING_NODE;
        }

        // 4. 继续循环
        log.debug("[ObservationDispatcher] Continuing loop, iteration {}/{}", currentIteration, maxIterations);
        return REASONING_NODE;
    }
}
