package vip.mate.agent.runtime.contract;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContractInvariantTest {

    @Test
    void terminalFlagMustMatchEventType() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeEvent("session-1", 1, RuntimeEventType.ASSISTANT_DELTA,
                        "text", Map.of(), true));
    }

    @Test
    void completedResultCannotContainError() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeResult(RuntimeResult.Status.COMPLETED, "answer", "error", "broken"));
    }

    @Test
    void sessionConfigurationIsImmutable() {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("permissionMode", "read-only");
        RuntimeSession session = new RuntimeSession(
                "session-1", "conversation-1", 1L, 2L, "model", Path.of("/workspace"), configuration);

        configuration.put("permissionMode", "danger-full-access");

        assertTrue(session.configuration().get("permissionMode").equals("read-only"));
        assertThrows(UnsupportedOperationException.class,
                () -> session.configuration().put("new", true));
    }
}
