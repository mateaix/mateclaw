package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import vip.mate.agent.graph.plan.state.PlanStateAccessor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the re-plan context that StepExecutionNode hands to the next
 * PlanGeneration pass after a step fails: it must preserve the completed work
 * (carried in WORKING_CONTEXT) and append the failed step + error so the
 * planner can route around it.
 */
class StepExecutionReplanContextTest {

    private PlanStateAccessor accessor(String workingContext, List<String> steps) {
        Map<String, Object> vals = new HashMap<>();
        vals.put(PlanStateKeys.WORKING_CONTEXT, workingContext);
        vals.put(PlanStateKeys.PLAN_STEPS, steps);
        return new PlanStateAccessor(new OverAllState(vals));
    }

    @Test
    void replanContext_preservesPriorWork_andDescribesFailure() {
        PlanStateAccessor a = accessor(
                "已完成：步骤1 读取配置完成",
                List.of("读取配置", "迁移数据", "验证结果"));

        String ctx = StepExecutionNode.buildReplanContext(a, 1, "connection timeout");

        // Prior completed work is carried forward.
        assertTrue(ctx.contains("已完成：步骤1 读取配置完成"));
        // The failed step (1-based) + its title + the error are described.
        assertTrue(ctx.contains("步骤 2"));
        assertTrue(ctx.contains("迁移数据"));
        assertTrue(ctx.contains("connection timeout"));
        // Instructs the planner not to redo completed work.
        assertTrue(ctx.contains("不要重复"));
    }

    @Test
    void replanContext_handlesEmptyPriorContext() {
        PlanStateAccessor a = accessor("", List.of("only step"));
        String ctx = StepExecutionNode.buildReplanContext(a, 0, "boom");
        // No leading blank separator when there was no prior context.
        assertFalse(ctx.startsWith("\n"));
        assertTrue(ctx.contains("步骤 1"));
        assertTrue(ctx.contains("only step"));
        assertTrue(ctx.contains("boom"));
    }

    @Test
    void replanContext_toleratesOutOfRangeIndexAndNullError() {
        PlanStateAccessor a = accessor("ctx", List.of("a"));
        String ctx = StepExecutionNode.buildReplanContext(a, 5, null);
        assertTrue(ctx.contains("步骤 6"));
        assertTrue(ctx.contains("未知错误"));
    }
}
