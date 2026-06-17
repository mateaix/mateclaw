package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.graph.observation.ObservationProcessor;
import vip.mate.config.GraphObservationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * Iteration-refund behaviour: a reasoning round whose entire tool batch was
 * progressive-disclosure setup (load_skill / enable_tool) must not consume an
 * iteration, bounded by a per-run cap.
 */
class ObservationNodeRefundTest {

    private ObservationNode node() {
        return new ObservationNode(new ObservationProcessor(new GraphObservationProperties()));
    }

    private static ToolResponseMessage.ToolResponse result(String name) {
        return new ToolResponseMessage.ToolResponse("id-" + name, name, "ok");
    }

    private OverAllState state(int iteration, int refundCount, List<ToolResponseMessage.ToolResponse> results) {
        Map<String, Object> m = new HashMap<>();
        m.put(CURRENT_ITERATION, iteration);
        m.put(MAX_ITERATIONS, 25);
        m.put(ITERATION_REFUND_COUNT, refundCount);
        m.put(OBSERVATION_HISTORY, new ArrayList<String>());
        m.put(TOOL_RESULTS, results);
        m.put(TOOL_CALL_COUNT, 0);
        return new OverAllState(m);
    }

    @Test
    @DisplayName("纯渐进披露轮（load_skill）退还迭代，不递增")
    void setupOnlyRound_refundsIteration() throws Exception {
        Map<String, Object> out = node().apply(state(3, 0, List.of(result("load_skill"))));
        assertEquals(3, out.get(CURRENT_ITERATION), "setup-only round must not advance the iteration");
        assertEquals(1, out.get(ITERATION_REFUND_COUNT));
    }

    @Test
    @DisplayName("enable_tool 同样视为 setup-only")
    void enableToolRound_refundsIteration() throws Exception {
        Map<String, Object> out = node().apply(state(5, 1, List.of(result("enable_tool"))));
        assertEquals(5, out.get(CURRENT_ITERATION));
        assertEquals(2, out.get(ITERATION_REFUND_COUNT));
    }

    @Test
    @DisplayName("真实工具轮正常计费")
    void realToolRound_consumesIteration() throws Exception {
        Map<String, Object> out = node().apply(state(3, 0, List.of(result("web_search"))));
        assertEquals(4, out.get(CURRENT_ITERATION));
        assertNull(out.get(ITERATION_REFUND_COUNT), "no refund on a real-work round");
    }

    @Test
    @DisplayName("混合批次（披露+真实工具）正常计费")
    void mixedRound_consumesIteration() throws Exception {
        Map<String, Object> out = node().apply(
                state(3, 0, List.of(result("load_skill"), result("web_search"))));
        assertEquals(4, out.get(CURRENT_ITERATION));
        assertNull(out.get(ITERATION_REFUND_COUNT));
    }

    @Test
    @DisplayName("退还次数达上限后不再退还")
    void refundCapReached_consumesIteration() throws Exception {
        // cap is 3; refundCount already 3 -> charged normally
        Map<String, Object> out = node().apply(state(7, 3, List.of(result("load_skill"))));
        assertEquals(8, out.get(CURRENT_ITERATION));
        assertNull(out.get(ITERATION_REFUND_COUNT));
    }
}
