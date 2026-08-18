package vip.mate.agent.graph.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * ReasoningDispatcher 单元测试
 */
class ReasoningDispatcherTest {

    private ReasoningDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ReasoningDispatcher();
    }

    @Test
    @DisplayName("需要工具调用时路由到 action")
    void shouldRouteToActionWhenToolCallNeeded() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                NEEDS_TOOL_CALL, true,
                CURRENT_ITERATION, 0,
                MAX_ITERATIONS, 10
        ));
        assertEquals(ACTION_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("不需要工具调用时路由到 final_answer_node")
    void shouldRouteToFinalAnswerWhenNoToolCall() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                NEEDS_TOOL_CALL, false,
                CURRENT_ITERATION, 0,
                MAX_ITERATIONS, 10
        ));
        assertEquals(FINAL_ANSWER_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("缺少 NEEDS_TOOL_CALL 键时默认路由到 final_answer_node")
    void shouldRouteToFinalAnswerWhenKeyMissing() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                CURRENT_ITERATION, 0,
                MAX_ITERATIONS, 10
        ));
        assertEquals(FINAL_ANSWER_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("动作完成门控请求续跑时回到 reasoning")
    void shouldContinueReasoningWhenCompletionGateRejectsCandidate() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                CONTINUE_REASONING, true,
                NEEDS_TOOL_CALL, false,
                CURRENT_ITERATION, 0,
                MAX_ITERATIONS, 10
        ));
        assertEquals(REASONING_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("迭代超限时路由到 limit_exceeded")
    void shouldRouteToLimitExceededWhenOverLimit() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                CURRENT_ITERATION, 10,
                MAX_ITERATIONS, 10,
                NEEDS_TOOL_CALL, true
        ));
        assertEquals(LIMIT_EXCEEDED_NODE, dispatcher.apply(state));
    }

    @Test
    @DisplayName("需要总结时路由到 summarizing")
    void shouldRouteToSummarizingWhenNeeded() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                SHOULD_SUMMARIZE, true,
                NEEDS_TOOL_CALL, false,
                CURRENT_ITERATION, 0,
                MAX_ITERATIONS, 10
        ));
        assertEquals(SUMMARIZING_NODE, dispatcher.apply(state));
    }
}
