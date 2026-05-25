package vip.mate.agent.graph.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static vip.mate.agent.graph.state.MateClawStateKeys.GOAL_EVALUATED_THIS_RUN;
import static vip.mate.agent.graph.state.MateClawStateKeys.GOAL_FOLLOWUP_INJECTED;

/**
 * Decides whether to re-enter the reasoning loop with an injected
 * follow-up prompt or terminate the graph run.
 *
 * <p>Both targets are passed in by the builder so the same class serves
 * the ReAct graph (followup -&gt; {@code REASONING_NODE}, terminal -&gt;
 * {@code END}) and the Plan-Execute graph (followup -&gt;
 * {@code PLAN_GENERATION_NODE}, terminal -&gt; {@code END}) without
 * branching on graph type at runtime.
 */
@Slf4j
@RequiredArgsConstructor
public class GoalEvaluationDispatcher implements EdgeAction {

    /** Where to re-enter the loop when GoalEvaluationNode injected a followup. */
    private final String followupTarget;

    /** Where to go on the normal terminal path (typically {@code END}). */
    private final String terminalTarget;

    @Override
    public String apply(OverAllState state) {
        // Re-enter the loop only when a followup was injected AND this was not a
        // terminal evaluation pass. GOAL_FOLLOWUP_INJECTED uses the REPLACE key
        // strategy and is never cleared by the reasoning nodes, so after a
        // run-to-completion loop it can linger true; goalEvaluatedThisRun (set
        // true on every terminal branch — completed / exhausted / skip /
        // continue-without-followup) is the authoritative end-of-run signal.
        boolean followup = Boolean.TRUE.equals(state.value(GOAL_FOLLOWUP_INJECTED, false));
        boolean terminal = Boolean.TRUE.equals(state.value(GOAL_EVALUATED_THIS_RUN, false));
        if (followup && !terminal) {
            log.debug("[GoalEvaluationDispatcher] followup injected -> routing to {}", followupTarget);
            return followupTarget;
        }
        return terminalTarget;
    }
}
