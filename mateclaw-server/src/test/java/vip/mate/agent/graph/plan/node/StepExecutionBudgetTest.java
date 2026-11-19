package vip.mate.agent.graph.plan.node;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.planning.service.PlanningService;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StepExecutionBudgetTest {
    @ParameterizedTest
    @CsvSource({"tool_limit,0", "tool_limit,1", "wall_clock,0", "wall_clock,1", "empty,0", "empty,1", "success,1"})
    void incompleteStepsRecoverOrAbortWithoutAdvancing(String failure, int replans) throws Exception {
        var planning = mock(PlanningService.class);
        var helper = mock(NodeStreamingChatHelper.class);
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("read_file")
                .description("read").inputSchema("{}").build());
        var tools = AgentToolSet.fromCallbacks(List.of(), List.of(callback));
        var executor = mock(ToolExecutionExecutor.class);
        var rounds = new AtomicInteger();
        when(helper.streamCall(any(), any(), anyString(), anyString())).thenAnswer(invocation -> {
            int round = rounds.incrementAndGet();
            var calls = failure.equals("tool_limit")
                    ? List.of(new AssistantMessage.ToolCall("c" + round, "function", "read_file", "{\"page\":" + round + "}"))
                    : List.<AssistantMessage.ToolCall>of();
            String text = failure.equals("success") ? "completed result" : "";
            return new NodeStreamingChatHelper.StreamResult(text, "", new AssistantMessage(text), calls,
                    !calls.isEmpty(), 1, 1);
        });
        when(executor.execute(anyList(), anyString(), anyString(), anyBoolean(), anyString(), anyString(), any(), anySet()))
                .thenAnswer(invocation -> {
                    List<AssistantMessage.ToolCall> calls = invocation.getArgument(0);
                    var call = calls.getFirst();
                    return new ToolExecutionExecutor.ToolExecutionResult(
                            List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), "data " + call.arguments())),
                            List.of(), false, null, null, List.of());
                });
        var node = new StepExecutionNode(mock(ChatModel.class), tools, executor, planning, null,
                null, helper, null, failure.equals("wall_clock") ? -1 : 60000);
        var state = new RunState(Map.of(PlanStateKeys.PLAN_ID, 42L,
                PlanStateKeys.PLAN_STEPS, List.of("read all pages", "calculate from pages"),
                PlanStateKeys.CURRENT_STEP_INDEX, 0, PlanStateKeys.PLAN_REPLAN_COUNT, replans,
                MateClawStateKeys.CONVERSATION_ID, "conv", MateClawStateKeys.AGENT_ID, "1"));

        var output = node.apply(state);

        if (failure.equals("success")) {
            assertEquals("step_completed", output.get(MateClawStateKeys.CURRENT_PHASE));
            assertEquals(1, output.get(PlanStateKeys.CURRENT_STEP_INDEX));
            verify(planning).updateSubPlanResult(42L, 0, "completed result");
            return;
        }
        assertEquals(replans == 0 ? "plan_replan" : "plan_aborted", output.get(MateClawStateKeys.CURRENT_PHASE));
        if (replans == 1) {
            assertEquals("incomplete", output.get(MateClawStateKeys.FINISH_REASON));
            assertFalse(output.containsKey(PlanStateKeys.CURRENT_STEP_INDEX));
        } else {
            assertEquals(1, output.get(PlanStateKeys.PLAN_REPLAN_COUNT));
            assertEquals(0, output.get(PlanStateKeys.CURRENT_STEP_INDEX));
        }
        assertFalse(output.containsKey(PlanStateKeys.COMPLETED_RESULTS));
        verify(planning, never()).updateSubPlanResult(any(), anyInt(), anyString());
        verify(planning).updateSubPlanFailure(eq(42L), eq(0), anyString());
        if (failure.equals("tool_limit")) assertEquals(100, rounds.get());
    }
}
