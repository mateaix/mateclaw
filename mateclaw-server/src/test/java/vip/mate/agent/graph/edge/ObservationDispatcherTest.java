package vip.mate.agent.graph.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * ObservationDispatcher 单元测试
 */
class ObservationDispatcherTest {

    private ObservationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ObservationDispatcher();
    }

    @Test
    @DisplayName("迭代未达上限时继续推理")
    void shouldContinueWhenUnderLimit() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                CURRENT_ITERATION, 0,
                MAX_ITERATIONS, 10
        ));
        assertEquals(REASONING_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("接近上限时仍继续推理")
    void shouldContinueWhenNearLimit() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                CURRENT_ITERATION, 9,
                MAX_ITERATIONS, 10
        ));
        assertEquals(REASONING_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("达到上限时路由到 limit_exceeded")
    void shouldRouteToLimitExceededWhenAtLimit() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                CURRENT_ITERATION, 10,
                MAX_ITERATIONS, 10
        ));
        assertEquals(LIMIT_EXCEEDED_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("超过上限时路由到 limit_exceeded")
    void shouldRouteToLimitExceededWhenOverLimit() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                CURRENT_ITERATION, 15,
                MAX_ITERATIONS, 10
        ));
        assertEquals(LIMIT_EXCEEDED_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("有错误时路由到 limit_exceeded")
    void shouldRouteToLimitExceededWhenErrorPresent() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                CURRENT_ITERATION, 1,
                MAX_ITERATIONS, 10,
                ERROR, "something went wrong"
        ));
        assertEquals(LIMIT_EXCEEDED_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("默认值场景：无迭代计数时使用默认 0")
    void shouldUseDefaultsWhenMissing() throws Exception {
        OverAllState state = new OverAllState(Map.of());
        // default: current=0, max=10 → should continue
        assertEquals(REASONING_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("需要总结时路由到 summarizing")
    void shouldRouteToSummarizingWhenNeeded() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                CURRENT_ITERATION, 3,
                MAX_ITERATIONS, 10,
                SHOULD_SUMMARIZE, true
        ));
        assertEquals(SUMMARIZING_NODE, dispatcher.apply(state));
    }

    // ========== RFC-052 returnDirect routing ==========

    @Test
    @DisplayName("RFC-052: RETURN_DIRECT_TRIGGERED routes straight to FinalAnswerNode")
    void returnDirectTriggered_routesToFinalAnswer() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                CURRENT_ITERATION, 1,
                MAX_ITERATIONS, 10,
                RETURN_DIRECT_TRIGGERED, true
        ));
        assertEquals(FINAL_ANSWER_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("RFC-052: RETURN_DIRECT outranks shouldSummarize / limit-exceeded")
    void returnDirectTriggered_takesPriorityOverSummarizeAndLimit() throws Exception {
        // Even when summarize and limit conditions would trigger, RETURN_DIRECT wins.
        OverAllState state = new OverAllState(Map.of(
                CURRENT_ITERATION, 100,        // way over limit
                MAX_ITERATIONS, 10,
                SHOULD_SUMMARIZE, true,
                RETURN_DIRECT_TRIGGERED, true
        ));
        assertEquals(FINAL_ANSWER_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("RFC-052: AWAITING_APPROVAL still wins over RETURN_DIRECT")
    void awaitingApproval_winsOverReturnDirect() throws Exception {
        // Approval-pending must terminate the graph regardless; user decision
        // arrives later via the replay path.
        OverAllState state = new OverAllState(Map.of(
                CURRENT_ITERATION, 1,
                MAX_ITERATIONS, 10,
                AWAITING_APPROVAL, true,
                RETURN_DIRECT_TRIGGERED, true
        ));
        assertEquals(FINAL_ANSWER_NODE, dispatcher.apply(state));
    }
}
