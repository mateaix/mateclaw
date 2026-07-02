package vip.mate.agent.graph.plan.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;

import java.util.List;

/**
 * 步骤进度分发器
 * <p>
 * 根据 current_phase 和 current_step_index / plan_steps 判断路由：
 * <ul>
 *   <li>current_phase == "awaiting_approval" → END（暂停图执行，等待用户审批后 replay）</li>
 *   <li>当前步骤索引 &lt; 步骤总数 → 继续执行下一步（STEP_EXECUTION_NODE）</li>
 *   <li>所有步骤完成 → 路由到汇总节点（PLAN_SUMMARY_NODE）</li>
 * </ul>
 *
 * @author MateClaw Team
 */
public class StepProgressDispatcher implements EdgeAction {

    @Override
    @SuppressWarnings("unchecked")
    public String apply(OverAllState state) {
        // 审批暂停态或步骤执行失败中止态：直接结束当前图 tick
        String currentPhase = state.value(MateClawStateKeys.CURRENT_PHASE, "");
        if ("awaiting_approval".equals(currentPhase) || "plan_aborted".equals(currentPhase)) {
            return StateGraph.END;
        }
        // Step-failure recovery: a failed step requested a re-plan of the
        // remaining work. Route back to PlanGeneration instead of aborting;
        // PLAN_REPLAN_COUNT (set by StepExecutionNode) bounds the loop.
        if ("plan_replan".equals(currentPhase)) {
            return PlanStateKeys.PLAN_GENERATION_NODE;
        }

        int currentIndex = state.value(PlanStateKeys.CURRENT_STEP_INDEX, 0);
        List<String> steps = state.<List<String>>value(PlanStateKeys.PLAN_STEPS).orElse(List.of());
        if (currentIndex >= steps.size()) {
            return PlanStateKeys.PLAN_SUMMARY_NODE;
        }
        return PlanStateKeys.STEP_EXECUTION_NODE;
    }
}
