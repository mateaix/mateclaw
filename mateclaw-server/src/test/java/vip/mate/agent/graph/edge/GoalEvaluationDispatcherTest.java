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
        OverAllState s = mock(OverAllState.class);
        // The dispatcher only reads GOAL_FOLLOWUP_INJECTED; everything else
        // can stay default.
        lenient().when(s.value("goal_followup_injected", false)).thenReturn(followup);
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
}
