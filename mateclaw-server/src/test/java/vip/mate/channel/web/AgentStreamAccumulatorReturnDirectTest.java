package vip.mate.channel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService.StreamDelta;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentStreamAccumulatorReturnDirectTest {

    private static final String DIRECT_PLACEHOLDER =
            "[Tool result returned directly to user. "
                    + "Content withheld from model context per tool policy.]";

    private static final AgentStreamAccumulator.Sink NOOP_SINK =
            new AgentStreamAccumulator.Sink() {
                @Override
                public void broadcast(String conversationId, String eventName, Object payload) { }

                @Override
                public void updatePhase(String conversationId, String phase) { }
            };

    @Test
    @DisplayName("returnDirect result terminates its tool card without persisting the direct payload there")
    void directResultHasCompletedToolPair() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator accumulator = new AgentStreamAccumulator(mapper, NOOP_SINK);
        String conversationId = "conv-direct";

        accumulator.accept(StreamDelta.event("tool_call_started", Map.of(
                "toolCallId", "call-1", "toolName", "renderDocx", "arguments", "{}")),
                conversationId);
        accumulator.accept(StreamDelta.event("tool_direct_result", Map.of(
                "toolCallId", "call-1", "toolName", "renderDocx",
                "result", "SECRET-DIRECT-PAYLOAD")), conversationId);
        accumulator.accept(StreamDelta.event("tool_call_completed", Map.of(
                "toolCallId", "call-1", "toolName", "renderDocx",
                "result", DIRECT_PLACEHOLDER, "success", true)),
                conversationId);

        JsonNode metadata = mapper.readTree(accumulator.toMetadataJson());
        JsonNode call = metadata.path("toolCalls").get(0);
        JsonNode segment = metadata.path("segments").get(0);

        assertEquals("completed", call.path("status").asText());
        assertEquals("completed", segment.path("status").asText());
        assertEquals(DIRECT_PLACEHOLDER, segment.path("toolResult").asText());
        assertFalse(metadata.toString().contains("SECRET-DIRECT-PAYLOAD"));
        assertEquals("renderDocx", metadata.path("directToolNames").get(0).asText());
    }
}
