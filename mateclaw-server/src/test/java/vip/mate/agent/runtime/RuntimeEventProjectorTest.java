package vip.mate.agent.runtime;

import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEventProjectorTest {
    @Test
    void projectsAssistantAndToolEventsToExistingStreamVocabulary() {
        AgentService.StreamDelta assistant = RuntimeEventProjector.project(
                RuntimeEvent.of("session-1", 4, RuntimeEventType.ASSISTANT_DELTA, null,
                        Map.of("delta", "hello")));
        assertEquals("hello", assistant.content());

        AgentService.StreamDelta tool = RuntimeEventProjector.project(
                RuntimeEvent.of("session-1", 5, RuntimeEventType.TOOL_STARTED, null,
                        Map.of("callId", "call-1", "toolName", "read_file")));
        assertEquals("tool_call_started", tool.eventType());
        assertEquals("call-1", tool.eventData().get("toolCallId"));
        assertEquals("read_file", tool.eventData().get("toolName"));
        assertEquals("session-1", tool.eventData().get("runtimeSessionId"));
    }

    @Test
    void usesRuntimeEventTextWhenDeltaFieldIsAbsent() {
        AgentService.StreamDelta assistant = RuntimeEventProjector.project(
                RuntimeEvent.of("session-1", 1, RuntimeEventType.ASSISTANT_DELTA,
                        "from-text", Map.of()));

        assertEquals("from-text", assistant.content());
    }

    @Test
    void projectsTerminalEventsWithoutChangingTerminalMeaning() {
        AgentService.StreamDelta failed = RuntimeEventProjector.project(
                RuntimeEvent.terminal("session-1", 9, RuntimeEventType.FAILED,
                        Map.of("message", "bridge closed")));
        assertEquals("error", failed.eventType());
        assertTrue(failed.eventData().containsKey("runtimeSequence"));
    }
}
