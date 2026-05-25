package vip.mate.agent.graph.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the dual-target dispatcher: instances configured for ReAct
 * route to REASONING_NODE on followup, instances configured for
 * Plan-Execute route to PLAN_GENERATION_NODE on followup, and both
 * route to END otherwise.
 */
class GoalEvaluationDispatcherTest {

    private OverAllState stateWith(boolean followup) {
        return stateWith(followup, false);
    }

    private OverAllState stateWith(boolean followup, boolean terminal) {
        OverAllState s = mock(OverAllState.class);
        lenient().when(s.value("goal_followup_injected", false)).thenReturn(followup);
        lenient().when(s.value("goal_evaluated_this_run", false)).thenReturn(terminal);
        return s;
    }

    @Test
    void reactInstance_routesFollowupToReasoning() throws Exception {
        GoalEvaluationDispatcher d = new GoalEvaluationDispatcher("reasoning", "__END__");
        assertEquals("reasoning", d.apply(stateWith(true)));
    }

    @Test
    void reactInstance_routesTerminalToEnd() throws Exception {
        GoalEvaluationDispatcher d = new GoalEvaluationDispatcher("reasoning", "__END__");
        assertEquals("__END__", d.apply(stateWith(false)));
    }

    @Test
    void planExecuteInstance_routesFollowupToPlanGeneration() throws Exception {
        GoalEvaluationDispatcher d = new GoalEvaluationDispatcher("plan_generation", "__END__");
        assertEquals("plan_generation", d.apply(stateWith(true)));
    }

    @Test
    void planExecuteInstance_routesTerminalToEnd() throws Exception {
        GoalEvaluationDispatcher d = new GoalEvaluationDispatcher("plan_generation", "__END__");
        assertEquals("__END__", d.apply(stateWith(false)));
    }

    // ===== Run-to-completion loop guard =====

    @Test
    void followupOnNonTerminalPass_reentersLoop() throws Exception {
        // The self-continuation loop: followup injected, not a terminal pass.
        GoalEvaluationDispatcher d = new GoalEvaluationDispatcher("reasoning", "__END__");
        assertEquals("reasoning", d.apply(stateWith(true, false)));
    }

    @Test
    void followupFlagLingeringOnTerminalPass_routesToEnd() throws Exception {
        // GOAL_FOLLOWUP_INJECTED uses REPLACE and is never cleared, so after a
        // run-to-completion loop it can still be true on the final (completed /
        // exhausted) pass. goalEvaluatedThisRun == true must win and END the run,
        // otherwise the graph loops forever.
        GoalEvaluationDispatcher react = new GoalEvaluationDispatcher("reasoning", "__END__");
        assertEquals("__END__", react.apply(stateWith(true, true)));
        GoalEvaluationDispatcher plan = new GoalEvaluationDispatcher("plan_generation", "__END__");
        assertEquals("__END__", plan.apply(stateWith(true, true)));
    }
}
