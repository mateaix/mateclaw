package vip.mate.agent.graph.plan.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import org.junit.jupiter.api.Test;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Routing coverage for the Plan-Execute step dispatcher, including the
 * step-failure re-plan edge (phase=plan_replan → PLAN_GENERATION).
 */
class StepProgressDispatcherTest {

    private final StepProgressDispatcher dispatcher = new StepProgressDispatcher();

    private OverAllState state(String phase, int stepIndex, List<String> steps) {
        Map<String, Object> vals = new HashMap<>();
        vals.put(MateClawStateKeys.CURRENT_PHASE, phase);
        vals.put(PlanStateKeys.CURRENT_STEP_INDEX, stepIndex);
        vals.put(PlanStateKeys.PLAN_STEPS, steps);
        return new OverAllState(vals);
    }

    @Test
    void replanPhase_routesToPlanGeneration() {
        String next = dispatcher.apply(state("plan_replan", 0, List.of("a", "b")));
        assertEquals(PlanStateKeys.PLAN_GENERATION_NODE, next);
    }

    @Test
    void abortedPhase_routesToEnd() {
        assertEquals(StateGraph.END, dispatcher.apply(state("plan_aborted", 1, List.of("a", "b"))));
    }

    @Test
    void awaitingApproval_routesToEnd() {
        assertEquals(StateGraph.END, dispatcher.apply(state("awaiting_approval", 0, List.of("a"))));
    }

    @Test
    void moreStepsRemaining_routesToStepExecution() {
        assertEquals(PlanStateKeys.STEP_EXECUTION_NODE,
                dispatcher.apply(state("step_completed", 1, List.of("a", "b", "c"))));
    }

    @Test
    void allStepsDone_routesToPlanSummary() {
        assertEquals(PlanStateKeys.PLAN_SUMMARY_NODE,
                dispatcher.apply(state("step_completed", 2, List.of("a", "b"))));
    }
}
