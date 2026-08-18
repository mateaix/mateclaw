package vip.mate.channel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService.StreamDelta;
import vip.mate.agent.ContentKind;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that producer-assigned {@link ContentKind} tags survive segment
 * accumulation into persisted {@code metadata.segments}, and that untagged
 * deltas (pre-tag producers) leave the field absent so consumers can fall
 * back to structural detection.
 */
class AgentStreamAccumulatorKindTest {

    private static final AgentStreamAccumulator.Sink NOOP_SINK = new AgentStreamAccumulator.Sink() {
        @Override public void broadcast(String conversationId, String eventName, Object payload) { }
        @Override public void updatePhase(String conversationId, String phase) { }
    };

    private static JsonNode segmentsOf(AgentStreamAccumulator acc, ObjectMapper mapper) throws Exception {
        return mapper.readTree(acc.toMetadataJson()).path("segments");
    }

    @Test
    @DisplayName("kind tags land on persisted content segments; tool segments stay untagged")
    void kindTagsPersisted() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator acc = new AgentStreamAccumulator(mapper, NOOP_SINK);
        String cid = "conv-1";

        acc.accept(StreamDelta.segmentOnly("先查询两间会议室的环境数据。", null,
                ContentKind.PRE_TOOL_NARRATION), cid);
        acc.accept(StreamDelta.event("tool_call_started",
                Map.of("toolCallId", "t1", "toolName", "envQuery", "arguments", "{}")), cid);
        acc.accept(StreamDelta.event("tool_call_completed",
                Map.of("toolCallId", "t1", "toolName", "envQuery", "result", "data={}", "success", true)), cid);
        acc.accept(StreamDelta.finalAnswer("接口返回为空，无环境数据。", true), cid);

        JsonNode segments = segmentsOf(acc, mapper);
        assertEquals(3, segments.size(), "content + tool_call + content");
        assertEquals("pre_tool_narration", segments.get(0).path("kind").asText());
        assertEquals("tool_call", segments.get(1).path("type").asText());
        assertFalse(segments.get(1).has("kind"), "kind is content-segment semantics only");
        assertEquals("final_answer", segments.get(2).path("kind").asText());

        assertEquals("接口返回为空，无环境数据。", acc.getContent(),
                "segmentOnly narration must stay out of the persisted top-level content");
    }

    @Test
    @DisplayName("untagged deltas leave kind absent — legacy consumers keep structural fallback")
    void untaggedDeltaHasNoKind() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator acc = new AgentStreamAccumulator(mapper, NOOP_SINK);

        acc.accept(StreamDelta.segmentOnly("legacy narration", null), "conv-2");

        JsonNode segments = segmentsOf(acc, mapper);
        assertEquals(1, segments.size());
        assertFalse(segments.get(0).has("kind"));
    }

    @Test
    @DisplayName("first writer wins — appended deltas cannot re-kind a running segment")
    void firstWriterWins() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator acc = new AgentStreamAccumulator(mapper, NOOP_SINK);
        String cid = "conv-3";

        acc.accept(StreamDelta.segmentOnly("part one ", null, ContentKind.GROUNDED_NARRATION), cid);
        acc.accept(StreamDelta.segmentOnly("part two", null, ContentKind.PRE_TOOL_NARRATION), cid);

        JsonNode segments = segmentsOf(acc, mapper);
        assertEquals(1, segments.size(), "second delta appends into the running segment");
        assertEquals("grounded_narration", segments.get(0).path("kind").asText());
        assertTrue(segments.get(0).path("text").asText().endsWith("part two"));
    }

    @Test
    @DisplayName("kind fills in late when the segment opener was untagged")
    void lateKindFillsUntaggedSegment() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator acc = new AgentStreamAccumulator(mapper, NOOP_SINK);
        String cid = "conv-4";

        acc.accept(StreamDelta.segmentOnly("opener ", null), cid);
        acc.accept(StreamDelta.segmentOnly("tail", null, ContentKind.GROUNDED_NARRATION), cid);

        JsonNode segments = segmentsOf(acc, mapper);
        assertEquals("grounded_narration", segments.get(0).path("kind").asText());
    }

    @Test
    @DisplayName("started-only tool calls persist as interrupted, never completed")
    void startedOnlyToolCallRemainsInterrupted() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator acc = new AgentStreamAccumulator(mapper, NOOP_SINK);

        acc.accept(StreamDelta.event("tool_call_started",
                Map.of("toolCallId", "orphan-1", "toolName", "schedule_meeting",
                        "arguments", "{\"title\":\"review\"}")), "conv-interrupted");

        JsonNode metadata = mapper.readTree(acc.toMetadataJson());
        JsonNode call = metadata.path("toolCalls").get(0);
        JsonNode segment = metadata.path("segments").get(0);

        assertEquals("interrupted", call.path("status").asText());
        assertFalse(call.has("result"), "no completion event means there is no tool result");
        assertEquals("interrupted", segment.path("status").asText());
        assertFalse(segment.has("toolResult"), "timeline must not manufacture a result");
    }

    @Test
    @DisplayName("Plan step result stays in plan metadata while final summary exclusively owns message content")
    void planStepResultDoesNotDuplicateFinalSummary() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator acc = new AgentStreamAccumulator(mapper, NOOP_SINK);
        String cid = "conv-plan";

        acc.accept(StreamDelta.event("plan_created",
                Map.of("planId", 7L, "steps", java.util.List.of("answer once"))), cid);
        acc.accept(StreamDelta.event("plan_step_completed",
                Map.of("index", 0, "result", "TP-01")), cid);
        acc.accept(StreamDelta.finalAnswer("TP-01", true), cid);

        JsonNode metadata = mapper.readTree(acc.toMetadataJson());
        assertEquals("TP-01", metadata.path("plan").path("stepResults")
                .get(0).path("result").asText());
        assertEquals("TP-01", acc.getContent(),
                "the canonical body must contain FINAL_SUMMARY exactly once");
    }
}
