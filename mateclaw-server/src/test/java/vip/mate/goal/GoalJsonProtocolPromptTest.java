package vip.mate.goal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.BaseAgent;
import vip.mate.agent.graph.StateGraphReActAgent;
import vip.mate.agent.graph.plan.StateGraphPlanExecuteAgent;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.service.GoalService;
import vip.mate.workspace.conversation.ConversationService;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalJsonProtocolPromptTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bothGraphEntryPointsExplainManagedProtocolOnlyForSelectedGoals(boolean plan) {
        var conversations = mock(ConversationService.class);
        var client = mock(ChatClient.class);
        BaseAgent agent = plan ? new StateGraphPlanExecuteAgent(client, conversations, null, null, null, null)
                : new StateGraphReActAgent(client, conversations, null, null, null);
        var goals = mock(GoalService.class);
        var goal = new GoalEntity(); goal.setId(1L); goal.setJsonAcceptanceRequired(true);
        when(goals.findActiveByConversation("conv")).thenReturn(goal);
        ReflectionTestUtils.setField(agent, "goalService", goals);
        ReflectionTestUtils.setField(agent, "systemPrompt", "base instructions");
        Map<String, Object> selected = ReflectionTestUtils.invokeMethod(agent, "buildInitialState", "write report", "conv");
        assertNotNull(selected);
        assertTrue(selected.get(MateClawStateKeys.SYSTEM_PROMPT).toString().contains("checkManagedGoalJson"));
        assertTrue(selected.get(MateClawStateKeys.SYSTEM_PROMPT).toString().startsWith("base instructions"));
        goal.setJsonAcceptanceRequired(false);
        Map<String, Object> legacy = ReflectionTestUtils.invokeMethod(agent, "buildInitialState", "write report", "conv");
        assertNotNull(legacy);
        assertEquals("base instructions", legacy.get(MateClawStateKeys.SYSTEM_PROMPT));
    }
}
