package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService.StreamDelta;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentStreamAccumulatorUsageTest {
    @Test
    void usageSnapshotsReplacePreviousValuesAndRemainInternal() {
        var sink = mock(AgentStreamAccumulator.Sink.class);
        var accumulator = new AgentStreamAccumulator(new ObjectMapper(), sink);
        var update = StreamDelta.event("_usage_update", Map.of("promptTokens", 100, "completionTokens", 20));
        accumulator.accept(update, "usage");
        accumulator.accept(update, "usage");
        assertEquals(100, accumulator.getPromptTokens());
        assertEquals(20, accumulator.getCompletionTokens());

        accumulator.accept(StreamDelta.event("_usage_final", Map.of(
                "promptTokens", 140, "completionTokens", 30,
                "delegatedPromptTokens", 40, "delegatedCompletionTokens", 10)), "usage");
        assertEquals(140, accumulator.getPromptTokens());
        assertEquals(30, accumulator.getCompletionTokens());
        assertTrue(accumulator.getContent().isEmpty());
        verifyNoInteractions(sink);
    }
}
