package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for the {@link McpProgressRelay} event listener.
 * Verifies that {@link McpProgressEvent} → {@link ChatStreamTracker#broadcastObject}
 * forwarding works correctly, including snapshot updates and skipBuffer=true.
 */
class McpProgressRelayTest {

    private ChatStreamTracker streamTracker;
    private McpProgressContext progressContext;
    private McpProgressRelay relay;

    @BeforeEach
    void setUp() {
        streamTracker = mock(ChatStreamTracker.class);
        progressContext = new McpProgressContext();
        relay = new McpProgressRelay(streamTracker, progressContext, new ObjectMapper());
    }

    @Test
    @DisplayName("relay forwards event to ChatStreamTracker with skipBuffer=true")
    void forwardsEvent() {
        McpProgressEvent event = new McpProgressEvent(
                this, "conv_1", "call_abc", "long_task", 0.5, 1.0, "Processing...");

        relay.onMcpProgress(event);

        verify(streamTracker).broadcastObject(
                eq("conv_1"),
                eq(McpProgressRelay.EVENT_TOOL_PROGRESS),
                any(Object.class),
                eq(true));
    }

    @Test
    @DisplayName("relay updates progress snapshot")
    void updatesSnapshot() {
        McpProgressEvent event = new McpProgressEvent(
                this, "conv_1", "call_abc", "task", 0.75, 1.0, "Almost done");

        relay.onMcpProgress(event);

        var snapshots = progressContext.getSnapshots("conv_1");
        assertEquals(1, snapshots.size());
        String json = snapshots.get("call_abc");
        assertNotNull(json);
        assertTrue(json.contains("\"percent\":75"));
        assertTrue(json.contains("\"call_abc\""));
    }

    @Test
    @DisplayName("streamTracker throws → relay logs warning, does not propagate")
    void streamTrackerThrowsDoesNotPropagate() {
        doThrow(new RuntimeException("SSE dead")).when(streamTracker)
                .broadcastObject(any(), any(), any(), anyBoolean());

        McpProgressEvent event = new McpProgressEvent(
                this, "conv", "call", "tool", 0.0, null, "init");

        // Should not throw
        assertDoesNotThrow(() -> relay.onMcpProgress(event));
    }

    @Test
    @DisplayName("null progress → broadcast still succeeds with 0.0")
    void nullProgress() {
        // This would be an edge case from MCP SDK; not expected but guarded
        McpProgressEvent event = new McpProgressEvent(
                this, "conv_2", "call_2", "task", 0.0, null, null);

        relay.onMcpProgress(event);

        verify(streamTracker).broadcastObject(
                eq("conv_2"),
                eq(McpProgressRelay.EVENT_TOOL_PROGRESS),
                any(Object.class),
                eq(true));
    }

    @Test
    @DisplayName("stage inference: 0-5% → prepare, 5-95% → execute, 95%+ → finalize")
    void stageInference() {
        // Test via the relay that stage reflects in the broadcast data
        McpProgressEvent event = new McpProgressEvent(
                this, "c", "t", "task", 0.97, 1.0, "Finishing");

        relay.onMcpProgress(event);

        verify(streamTracker).broadcastObject(
                eq("c"), eq("tool_call_progress"),
                argThat((Object data) -> {
                    if (data instanceof Map<?, ?> m) {
                        return "finalize".equals(m.get("stage"));
                    }
                    return false;
                }),
                eq(true));
    }

    @Test
    @DisplayName("event constant matches frontend expectation")
    void eventConstantCorrect() {
        assertEquals("tool_call_progress", McpProgressRelay.EVENT_TOOL_PROGRESS,
                "must match the SSE event name used in useChat.ts and ChatStreamTracker");
    }

    @Test
    @DisplayName("multiple events for same tool call update snapshot idempotently")
    void multipleEventsUpdateSameSnapshot() {
        relay.onMcpProgress(new McpProgressEvent(this, "c", "t", "n", 0.2, 1.0, "A"));
        relay.onMcpProgress(new McpProgressEvent(this, "c", "t", "n", 0.6, 1.0, "B"));
        relay.onMcpProgress(new McpProgressEvent(this, "c", "t", "n", 0.99, 1.0, "C"));

        // Only 1 snapshot (latest)
        var snapshots = progressContext.getSnapshots("c");
        assertEquals(1, snapshots.size());
        String json = snapshots.get("t");
        assertTrue(json.contains("\"percent\":99"));
        assertTrue(json.contains("C"));
    }
}
