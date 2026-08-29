package vip.mate.agent.runtime.dsh;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DshBridgeEventsTest {
    @Test
    void createsReadyToolApprovalAndSubagentMessages() {
        assertEquals("ready", DshBridgeEvents.ready("s-1").method());
        assertEquals("tool/call", DshBridgeEvents.toolCall("c-1", "read", Map.of("path", "a.txt")).method());
        assertEquals("approval/ask", DshBridgeEvents.approvalAsk("a-1", "bash", "run command").method());
        assertEquals("subagent/lifecycle", DshBridgeEvents.subagentLifecycle(
                "child-1", "started", Map.of("goal", "inspect")).method());
    }

    @Test
    void createsToolCancellationNotification() {
        DshBridgeMessage message = DshBridgeEvents.toolCancel("c-1");

        assertEquals("tool/cancel", message.method());
        assertEquals("c-1", message.params().get("callId"));
    }
}
