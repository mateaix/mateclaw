package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Field-shape tests for {@link GraphEventPublisher#iterationStart} and
 * {@link GraphEventPublisher#iterationEnd}. Verifies the payload contract
 * that downstream SSE consumers depend on (index / scope default / optional
 * subagentId / char counters).
 */
class GraphEventPublisherIterationTest {

    @Test
    @DisplayName("iterationStart carries index, reason, scope, timestamp")
    void iterationStartShape() {
        GraphEventPublisher.GraphEvent event = GraphEventPublisher.iterationStart(
                3, "react_step", "parent", null);
        assertEquals(GraphEventPublisher.EVENT_ITERATION_START, event.type());
        Map<String, Object> data = event.data();
        assertEquals(3, data.get("index"));
        assertEquals("react_step", data.get("reason"));
        assertEquals("parent", data.get("scope"));
        assertFalse(data.containsKey("subagentId"),
                "subagentId must be absent when null/empty");
        assertTrue(data.containsKey("timestamp"));
    }

    @Test
    @DisplayName("iterationStart defaults missing scope to 'parent'")
    void iterationStartDefaultsScope() {
        GraphEventPublisher.GraphEvent event = GraphEventPublisher.iterationStart(
                0, null, null, null);
        Map<String, Object> data = event.data();
        assertEquals("parent", data.get("scope"));
        assertEquals("", data.get("reason"),
                "Missing reason should serialize as empty string, not null");
    }

    @Test
    @DisplayName("iterationStart includes subagentId when provided")
    void iterationStartIncludesSubagentId() {
        GraphEventPublisher.GraphEvent event = GraphEventPublisher.iterationStart(
                7, "plan_step", "subagent", "sa-42");
        Map<String, Object> data = event.data();
        assertEquals("subagent", data.get("scope"));
        assertEquals("sa-42", data.get("subagentId"));
    }

    @Test
    @DisplayName("iterationEnd carries char counters and scope")
    void iterationEndShape() {
        GraphEventPublisher.GraphEvent event = GraphEventPublisher.iterationEnd(
                5, "parent", null, 1234, 56);
        assertEquals(GraphEventPublisher.EVENT_ITERATION_END, event.type());
        Map<String, Object> data = event.data();
        assertEquals(5, data.get("index"));
        assertEquals("parent", data.get("scope"));
        assertEquals(1234, data.get("contentChars"));
        assertEquals(56, data.get("thinkingChars"));
        assertFalse(data.containsKey("subagentId"));
    }

    @Test
    @DisplayName("iterationEnd defaults scope to 'parent' when null")
    void iterationEndDefaultsScope() {
        GraphEventPublisher.GraphEvent event = GraphEventPublisher.iterationEnd(
                0, null, "", 0, 0);
        Map<String, Object> data = event.data();
        assertEquals("parent", data.get("scope"));
        assertFalse(data.containsKey("subagentId"),
                "Empty subagentId must be omitted");
    }
}
