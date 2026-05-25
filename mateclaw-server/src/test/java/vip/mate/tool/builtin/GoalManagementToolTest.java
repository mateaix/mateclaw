package vip.mate.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.exception.MateClawException;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.model.GoalEvaluationResult;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.service.GoalService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the four @Tool methods on {@link GoalManagementTool}, especially
 * the disable-flag short-circuit + ChatOrigin requirement gates.
 */
@ExtendWith(MockitoExtension.class)
class GoalManagementToolTest {

    @Mock private GoalService goalService;
    @Mock private vip.mate.channel.web.ChatStreamTracker streamTracker;

    private GoalProperties properties;
    private GoalManagementTool tool;

    @BeforeEach
    void setUp() {
        properties = new GoalProperties();
        properties.setEnabled(true);
        tool = new GoalManagementTool(goalService, properties, new ObjectMapper(), streamTracker);
    }

    private ToolContext ctxWith(String convId, Long agentId, String requester) {
        ChatOrigin origin = ChatOrigin.web(convId, requester, 1L, "/tmp")
                .withAgent(agentId);
        return origin.toToolContext();
    }

    private GoalEntity goal(GoalStatus status) {
        GoalEntity g = new GoalEntity();
        g.setId(123L);
        g.setConversationId("conv-1");
        g.setAgentId(10L);
        g.setWorkspaceId(1L);
        g.setTitle("ship the blog");
        g.setStatus(status);
        g.setTurnBudget(20);
        g.setTurnsUsed(3);
        g.setLlmCallBudget(200);
        g.setAgentLlmCallsUsed(12);
        g.setEvalLlmCallsUsed(2);
        g.setAutoFollowupEnabled(false);
        return g;
    }

    // ==================== setGoal ====================

    @Test
    void setGoal_disabledFlag_returnsError() {
        properties.setEnabled(false);
        String result = tool.setGoal("title", null, null, null, null,
                ctxWith("conv-1", 10L, "alice"));
        assertTrue(result.contains("disabled"));
        verify(goalService, never()).create(any(), anyString());
    }

    @Test
    void setGoal_blankTitle_returnsError() {
        String result = tool.setGoal("  ", null, null, null, null,
                ctxWith("conv-1", 10L, "alice"));
        assertTrue(result.contains("title is required"));
    }

    @Test
    void setGoal_happyPath_returnsGoalId() {
        when(goalService.create(any(GoalCreateRequest.class), eq("alice")))
                .thenReturn(goal(GoalStatus.ACTIVE));
        String result = tool.setGoal("ship the blog",
                "deploy to fly.io",
                "tests pass + deployed",
                15, true,
                ctxWith("conv-1", 10L, "alice"));
        assertTrue(result.contains("\"goalId\":\"123\""));
        assertTrue(result.contains("\"status\":\"active\""));
    }

    @Test
    void setGoal_missingConversationContext_returnsError() {
        String result = tool.setGoal("title", null, null, null, null, null);
        assertTrue(result.contains("requires a bound conversation"));
    }

    // ==================== addGoalCriterion ====================

    @Test
    void addCriterion_noActiveGoal_returnsError() {
        when(goalService.findActiveByConversation("conv-1")).thenReturn(null);
        String result = tool.addGoalCriterion("test on Safari too",
                ctxWith("conv-1", 10L, "alice"));
        assertTrue(result.contains("No active goal"));
        verify(goalService, never()).appendCriterion(any(), anyString(), anyString());
    }

    @Test
    void addCriterion_blankInput_returnsError() {
        String result = tool.addGoalCriterion("   ",
                ctxWith("conv-1", 10L, "alice"));
        assertTrue(result.contains("must not be empty"));
    }

    @Test
    void addCriterion_happyPath_delegatesToService() {
        when(goalService.findActiveByConversation("conv-1")).thenReturn(goal(GoalStatus.ACTIVE));
        when(goalService.appendCriterion(eq(123L), eq("test on Safari"), eq("alice")))
                .thenReturn(goal(GoalStatus.ACTIVE));
        String result = tool.addGoalCriterion("test on Safari",
                ctxWith("conv-1", 10L, "alice"));
        assertTrue(result.contains("\"goalId\":\"123\""));
    }

    // ==================== completeGoal ====================

    @Test
    void completeGoal_noActiveGoal_returnsError() {
        when(goalService.findActiveByConversation("conv-1")).thenReturn(null);
        String result = tool.completeGoal(ctxWith("conv-1", 10L, "alice"));
        assertTrue(result.contains("No active goal"));
        verify(goalService, never()).markCompleted(any(), any(GoalEvaluationResult.class));
    }

    @Test
    void completeGoal_happyPath_callsMarkCompleted() {
        when(goalService.findActiveByConversation("conv-1")).thenReturn(goal(GoalStatus.ACTIVE));
        GoalEntity completed = goal(GoalStatus.COMPLETED);
        when(goalService.markCompleted(eq(123L), any(GoalEvaluationResult.class)))
                .thenReturn(completed);
        String result = tool.completeGoal(ctxWith("conv-1", 10L, "alice"));
        assertTrue(result.contains("\"status\":\"completed\""));
    }

    // ==================== getGoalStatus ====================

    @Test
    void getGoalStatus_noActive_returnsActiveFalse() {
        when(goalService.findActiveByConversation("conv-1")).thenReturn(null);
        String result = tool.getGoalStatus(ctxWith("conv-1", 10L, "alice"));
        assertTrue(result.contains("\"active\":false"));
    }

    @Test
    void getGoalStatus_active_carriesProgressSummary() {
        GoalEntity g = goal(GoalStatus.ACTIVE);
        g.setProgressSummary("missing DNS");
        g.setCompletionScore(0.62);
        when(goalService.findActiveByConversation("conv-1")).thenReturn(g);
        String result = tool.getGoalStatus(ctxWith("conv-1", 10L, "alice"));
        assertTrue(result.contains("\"goalId\":\"123\""));
        assertTrue(result.contains("\"completionScore\":0.62"));
        assertTrue(result.contains("missing DNS"));
        // total = agent(12) + eval(2)
        assertTrue(result.contains("\"totalLlmCallsUsed\":14"));
    }
}
