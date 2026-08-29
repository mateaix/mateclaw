package vip.mate.agent.runtime.contract;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeEventLogTest {

    @Test
    void rejectsEventsAfterTerminalEvent() {
        RuntimeEventLog log = new RuntimeEventLog("session-1");

        log.append(RuntimeEvent.of("session-1", 1, RuntimeEventType.ASSISTANT_DELTA,
                "hello", Map.of()));
        log.append(RuntimeEvent.terminal("session-1", 2, RuntimeEventType.COMPLETED,
                Map.of("answer", "hello")));

        assertThrows(IllegalStateException.class,
                () -> log.append(RuntimeEvent.of("session-1", 3, RuntimeEventType.ASSISTANT_DELTA,
                        "late", Map.of())));
    }

    @Test
    void rejectsOutOfOrderEventsAndWrongSession() {
        RuntimeEventLog log = new RuntimeEventLog("session-1");
        log.append(RuntimeEvent.of("session-1", 2, RuntimeEventType.RUNTIME_READY,
                null, Map.of()));

        assertThrows(IllegalArgumentException.class,
                () -> log.append(RuntimeEvent.of("session-1", 1, RuntimeEventType.ASSISTANT_DELTA,
                        "late", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> log.append(RuntimeEvent.of("session-2", 3, RuntimeEventType.ASSISTANT_DELTA,
                        "wrong", Map.of())));
    }
}
