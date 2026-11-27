package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import vip.mate.agent.runtime.contract.RuntimeEventType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DshSubagentEventsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void correlatesNestedChildrenAndRejectsUnrelatedOrDuplicateNotifications() {
        var tracker = new DshSubagentEvents("root");
        assertNull(tracker.accept("chat", 0, frame("started", "other", "foreign")));
        assertNull(tracker.accept("chat", 0, frame("finished", "root", "child")));
        var started = tracker.accept("chat", 1, frame("started", "root", "child"));
        assertEquals(RuntimeEventType.SUBAGENT_STARTED, started.type());
        assertEquals("chat", started.sessionId());
        assertEquals("child", started.data().get("agentId"));
        assertNull(tracker.accept("chat", 2, frame("started", "root", "child")));
        assertNotNull(tracker.accept("chat", 2, frame("started", "child", "grandchild")));
        assertNull(tracker.accept("chat", 3, frame("finished", "other", "child")));
        var finished = tracker.accept("chat", 3, frame("finished", "root", "child"));
        assertEquals(RuntimeEventType.SUBAGENT_FINISHED, finished.type());
        assertEquals("ok", finished.data().get("status"));
        assertNotNull(tracker.accept("chat", 4, frame("finished", "child", "grandchild")));
        assertNull(tracker.accept("chat", 5, frame("finished", "root", "child")));
    }

    private JsonNode frame(String state, String parent, String child) {
        return json.valueToTree(Map.of("method", "subagent." + state, "params",
                Map.of("parentSessionId", parent, "childSessionId", child, "status", "ok", "stopReason", "completed")));
    }
}
