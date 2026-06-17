package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.plan.state.PlanStateAccessor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.service.GoalService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers auto-deriving a goal from a multi-step Plan-Execute plan: gating
 * conditions and that the plan steps seed the goal's acceptance criteria.
 */
class PlanGenerationAutoGoalTest {

    private final GoalService goalService = mock(GoalService.class);
    private final GoalProperties properties = new GoalProperties();

    private PlanGenerationNode node() {
        return new PlanGenerationNode(null, null, null, null, null, goalService, properties);
    }

    private PlanStateAccessor accessor(boolean withAgent, String goalText) {
        ChatOrigin origin = ChatOrigin.web("conv_1", "admin", 1L, null);
        if (withAgent) {
            origin = origin.withAgent(1000000001L);
        }
        Map<String, Object> vals = new HashMap<>();
        vals.put(MateClawStateKeys.CHAT_ORIGIN, origin);
        vals.put(PlanStateKeys.GOAL, goalText);
        return new PlanStateAccessor(new OverAllState(vals));
    }

    @Test
    void multiStepPlan_createsGoal_seededWithStepCriteria() {
        GoalEntity created = new GoalEntity();
        created.setId(99L);
        when(goalService.create(any(), eq("admin"))).thenReturn(created);

        GoalEntity result = node().maybeAutoCreateGoal(
                accessor(true, "分三步完成：读取、分析、汇总"),
                List.of("读取文件", "列出建议", "汇总计划"));

        assertNotNull(result);
        assertEquals(99L, result.getId());

        ArgumentCaptor<GoalCreateRequest> cap = ArgumentCaptor.forClass(GoalCreateRequest.class);
        verify(goalService).create(cap.capture(), eq("admin"));
        GoalCreateRequest req = cap.getValue();
        assertEquals("conv_1", req.getConversationId());
        assertEquals(1000000001L, req.getAgentId());
        // Plan steps become acceptance criteria.
        assertNotNull(req.getCriteria());
        assertEquals(3, req.getCriteria().size());
        assertEquals("读取文件", req.getCriteria().get(0).text());
    }

    @Test
    void featureDisabled_returnsNull_noCreate() {
        properties.setAutoGoalFromPlan(false);
        GoalEntity result = node().maybeAutoCreateGoal(
                accessor(true, "g"), List.of("a", "b"));
        assertNull(result);
        verify(goalService, never()).create(any(), any());
    }

    @Test
    void singleStepPlan_returnsNull() {
        GoalEntity result = node().maybeAutoCreateGoal(accessor(true, "g"), List.of("only one"));
        assertNull(result);
        verify(goalService, never()).create(any(), any());
    }

    @Test
    void existingActiveGoal_returnsNull_noCreate() {
        when(goalService.findActiveByConversation("conv_1")).thenReturn(new GoalEntity());
        GoalEntity result = node().maybeAutoCreateGoal(
                accessor(true, "g"), List.of("a", "b"));
        assertNull(result);
        verify(goalService, never()).create(any(), any());
    }

    @Test
    void missingAgentContext_returnsNull() {
        GoalEntity result = node().maybeAutoCreateGoal(
                accessor(false, "g"), List.of("a", "b"));
        assertNull(result);
        verify(goalService, never()).create(any(), any());
    }

    @Test
    void masterSwitchOff_returnsNull() {
        properties.setEnabled(false);
        lenient().when(goalService.create(any(), any())).thenReturn(new GoalEntity());
        GoalEntity result = node().maybeAutoCreateGoal(
                accessor(true, "g"), List.of("a", "b"));
        assertNull(result);
        verify(goalService, never()).create(any(), any());
    }
}
