package vip.mate.agent.runtime.dsh;

import org.junit.jupiter.api.Test;
import vip.mate.agent.runtime.contract.RuntimeSession;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DshBridgeRequestsTest {
    @Test
    void createsSessionOpenWithSessionContext() {
        DshBridgeMessage message = DshBridgeRequests.sessionOpen(session(), Map.of("read", true));

        assertEquals("session/open", message.method());
        assertEquals("session-1", message.params().get("sessionId"));
        assertEquals("conversation-1", message.params().get("conversationId"));
        assertEquals("read-only", message.params().get("permissionMode"));
    }

    @Test
    void createsPromptCancelPolicyAndUsageRequests() {
        assertEquals("session/prompt", DshBridgeRequests.prompt("7", "hello").method());
        assertEquals("session/cancel", DshBridgeRequests.cancel("8", "session-1").method());
        assertEquals("policy/update", DshBridgeRequests.policyUpdate("9", Map.of("mode", "read-only")).method());
        assertEquals("context/usage", DshBridgeRequests.contextUsage("10", "session-1").method());
    }

    @Test
    void rejectsBlankRequestIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> DshBridgeRequests.prompt("", "hello"));
        assertThrows(IllegalArgumentException.class, () -> DshBridgeRequests.cancel("1", ""));
    }

    private static RuntimeSession session() {
        return new RuntimeSession("session-1", "conversation-1", 1L, 2L,
                "model", Path.of("/workspace"), Map.of("permissionMode", "read-only"));
    }
}
