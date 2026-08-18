package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.planning.service.PlanningService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the summary node's behaviour when the model returns no text.
 *
 * <p>An interleaved-thinking model can burn its whole turn on reasoning and
 * come back with an empty string. That empty summary used to flow through as
 * the run's terminal answer, the goal evaluator skipped on "terminalAnswer
 * empty", and a plan whose steps had all succeeded ended with a dangling
 * reasoning block and nothing to show for it. The step results are already in
 * hand at that point, so the node must answer from those.
 */
class PlanSummaryEmptyResultTest {

    private ChatModel chatModel;
    private PlanningService planningService;
    private NodeStreamingChatHelper streamingHelper;
    private PlanSummaryNode node;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        planningService = mock(PlanningService.class);
        streamingHelper = mock(NodeStreamingChatHelper.class);
        node = new PlanSummaryNode(chatModel, planningService, streamingHelper);
    }

    private static NodeStreamingChatHelper.StreamResult result(String text, String thinking) {
        return new NodeStreamingChatHelper.StreamResult(text, thinking, null, List.of(), false, 0, 0);
    }

    private OverAllState state() {
        Map<String, Object> vals = new HashMap<>();
        vals.put(PlanStateKeys.PLAN_ID, 42L);
        vals.put(PlanStateKeys.GOAL, "对订单数据做质量体检");
        vals.put(PlanStateKeys.COMPLETED_RESULTS,
                List.of("第 1 步：主键唯一性，发现 3 条重复", "第 2 步：缺失率统计完成"));
        return new OverAllState(vals);
    }

    private String summaryOf(Map<String, Object> out) {
        return String.valueOf(out.get(PlanStateKeys.FINAL_SUMMARY));
    }

    @Test
    @DisplayName("empty summary text falls back to the step results")
    void emptyTextFallsBackToStepResults() throws Exception {
        // The exact shape that slipped through: empty string, not null. A null
        // tripped an NPE and reached the catch-block fallback by accident; "" did
        // not, so it was the only unhandled case.
        when(streamingHelper.streamCall(any(), any(), any(), anyString()))
                .thenReturn(result("", "let me think about this for 36 seconds"));

        Map<String, Object> out = node.apply(state());
        String summary = summaryOf(out);

        assertFalse(summary.isBlank(), "an empty model summary must not become an empty answer");
        assertTrue(summary.contains("主键唯一性"), summary);
        assertTrue(summary.contains("缺失率统计"), summary);
    }

    @Test
    @DisplayName("whitespace-only summary is treated the same as empty")
    void whitespaceOnlyTextFallsBack() throws Exception {
        when(streamingHelper.streamCall(any(), any(), any(), anyString()))
                .thenReturn(result("   \n  ", ""));

        assertTrue(summaryOf(node.apply(state())).contains("主键唯一性"));
    }

    @Test
    @DisplayName("an empty summary completes the plan — the steps did succeed")
    void emptySummaryStillCompletesThePlan() throws Exception {
        when(streamingHelper.streamCall(any(), any(), any(), anyString()))
                .thenReturn(result("", "thinking"));

        node.apply(state());

        // Nothing failed: every step ran, only the summary text was missing.
        // Marking the plan failed would misreport a successful run.
        verify(planningService).completePlan(eq(42L), anyString());
        verify(planningService, never()).markPlanFailed(any(), anyString());
    }

    @Test
    @DisplayName("the fallback says the model produced nothing, not that the run failed")
    void emptySummaryNoteDistinguishesFromFailure() throws Exception {
        when(streamingHelper.streamCall(any(), any(), any(), anyString()))
                .thenReturn(result("", "thinking"));

        String summary = summaryOf(node.apply(state()));

        assertTrue(summary.contains("未产出汇总正文"), summary);
        assertFalse(summary.contains("汇总失败"),
                "nothing failed here; calling it a failure misreports a successful run: " + summary);
    }

    @Test
    @DisplayName("null thinking does not blow up the node")
    void nullThinkingIsTolerated() throws Exception {
        when(streamingHelper.streamCall(any(), any(), any(), anyString()))
                .thenReturn(result("", null));

        assertTrue(summaryOf(node.apply(state())).contains("主键唯一性"));
    }

    @Test
    @DisplayName("a real summary is passed through untouched")
    void realSummaryIsUnchanged() throws Exception {
        when(streamingHelper.streamCall(any(), any(), any(), anyString()))
                .thenReturn(result("体检完成：共发现 5 类问题。", "thinking"));

        Map<String, Object> out = node.apply(state());

        assertEquals("体检完成：共发现 5 类问题。", summaryOf(out));
        verify(planningService).completePlan(eq(42L), eq("体检完成：共发现 5 类问题。"));
    }

    @Test
    @DisplayName("empty summary with no step results still yields a readable answer")
    void emptySummaryWithNoStepResults() throws Exception {
        when(streamingHelper.streamCall(any(), any(), any(), anyString()))
                .thenReturn(result("", "thinking"));
        Map<String, Object> vals = new HashMap<>();
        vals.put(PlanStateKeys.PLAN_ID, 42L);
        vals.put(PlanStateKeys.GOAL, "空计划");
        vals.put(PlanStateKeys.COMPLETED_RESULTS, List.of());

        String summary = summaryOf(node.apply(new OverAllState(vals)));

        assertFalse(summary.isBlank());
        assertTrue(summary.contains("没有已完成的步骤结果"), summary);
    }
}
