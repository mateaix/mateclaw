package vip.mate.agent.graph.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.support.ToolCallbacks;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.local.LocalShellTool;
import vip.mate.tool.local.LocalToolBridgeService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShellOutcomeEventTest {
    @ParameterizedTest @ValueSource(strings = {"OFFLINE", "spawn failed"})
    void nativeFailureReachesBothNormalAndPreapprovedEvents(String failure) throws Exception {
        var bridge = mock(LocalToolBridgeService.class);
        when(bridge.invoke(any(), anyString(), anyString(), anyString(), any(), anyInt(), eq(true)))
                .thenReturn(failure.equals("OFFLINE")
                        ? LocalToolBridgeService.BridgeResult.failure("OFFLINE", "No desktop connected")
                        : LocalToolBridgeService.BridgeResult.success(new ObjectMapper().readTree("{\"error\":\"spawn failed\"}")));
        var callback = ToolCallbacks.from(new LocalShellTool(bridge, new ObjectMapper()));
        var executor = new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(), Arrays.asList(callback)),
                (name, args) -> ToolGuardResult.allow(), null, null);
        var call = new AssistantMessage.ToolCall("shell-1", "function", "local_execute_shell", "{\"command\":\"printf test\",\"title\":\"Check connection\"}");
        var normal = executor.execute(List.of(call), "shell-test", "agent", false, "user", null);
        var complete = normal.events().stream().filter(e -> e.type().equals(GraphEventPublisher.EVENT_TOOL_COMPLETE)).findFirst().orElseThrow();
        assertEquals(false, complete.data().get("success"));
        assertTrue(normal.responses().getFirst().responseData().contains(failure));
        var replayEvents = new ArrayList<GraphEventPublisher.GraphEvent>();
        var replay = executor.executePreApproved(call, call.arguments(), replayEvents, "shell-test", null);
        var replayComplete = replayEvents.stream().filter(e -> e.type().equals(GraphEventPublisher.EVENT_TOOL_COMPLETE)).findFirst().orElseThrow();
        assertEquals(false, replayComplete.data().get("success"));
        assertTrue(replay.responseData().contains(failure));
        verify(bridge, times(2)).invoke(any(), anyString(), anyString(), anyString(), any(), anyInt(), eq(true));
    }
}
