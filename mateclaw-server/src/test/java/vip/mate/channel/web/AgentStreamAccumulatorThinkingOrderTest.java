package vip.mate.channel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService.StreamDelta;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the delta emission order that the graph agents use for a turn's
 * thinking, and the segment timeline it produces.
 *
 * <p>Reasoning precedes the answer it produced, so the thinking delta is
 * emitted ahead of the final-answer content delta of the same batch. The
 * accumulator builds {@code metadata.segments} strictly in delta arrival
 * order, so emitting thinking last used to append a thinking segment
 * <em>after</em> the content segment — a timeline that contradicts what
 * actually happened and that readers had to reorder before rendering.
 *
 * <p>These cases lock the producer-side contract: a consumer may render
 * {@code segments} in array order without any type-based reordering.
 */
class AgentStreamAccumulatorThinkingOrderTest {

    private static final AgentStreamAccumulator.Sink NOOP_SINK = new AgentStreamAccumulator.Sink() {
        @Override public void broadcast(String conversationId, String eventName, Object payload) { }
        @Override public void updatePhase(String conversationId, String phase) { }
    };

    private static JsonNode segmentsOf(AgentStreamAccumulator acc, ObjectMapper mapper) throws Exception {
        return mapper.readTree(acc.toMetadataJson()).path("segments");
    }

    private static List<String> typesOf(JsonNode segments) {
        return segments.findValuesAsText("type");
    }

    @Test
    @DisplayName("direct answer — thinking segment precedes the answer's content segment")
    void thinkingPrecedesFinalAnswer() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator acc = new AgentStreamAccumulator(mapper, NOOP_SINK);
        String cid = "conv-order-1";

        // Emission order used by the graph agents for a no-tool turn.
        acc.accept(StreamDelta.persistOnly(null, "用户问的是时区换算，直接算即可。"), cid);
        acc.accept(StreamDelta.finalAnswer("北京时间 21:00 对应 UTC 13:00。", true), cid);

        JsonNode segments = segmentsOf(acc, mapper);
        assertEquals(List.of("thinking", "content"), typesOf(segments),
                "thinking must land before the content it produced — no consumer-side reordering");
        assertEquals("用户问的是时区换算，直接算即可。", segments.get(0).path("thinkingText").asText());
        assertEquals("北京时间 21:00 对应 UTC 13:00。", segments.get(1).path("text").asText());
    }

    @Test
    @DisplayName("tool turn — the final call's thinking sits between the tool card and the answer")
    void thinkingKeepsItsPlaceInAToolTurn() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator acc = new AgentStreamAccumulator(mapper, NOOP_SINK);
        String cid = "conv-order-2";

        acc.accept(StreamDelta.segmentOnly("先查一下会议室占用。", null), cid);
        acc.accept(StreamDelta.event("tool_call_started",
                Map.of("toolCallId", "t1", "toolName", "roomQuery", "arguments", "{}")), cid);
        acc.accept(StreamDelta.event("tool_call_completed",
                Map.of("toolCallId", "t1", "toolName", "roomQuery", "result", "[]", "success", true)), cid);
        acc.accept(StreamDelta.persistOnly(null, "返回为空，说明当前没有占用记录。"), cid);
        acc.accept(StreamDelta.finalAnswer("目前没有会议室被占用。", true), cid);

        JsonNode segments = segmentsOf(acc, mapper);
        assertEquals(List.of("content", "tool_call", "thinking", "content"), typesOf(segments),
                "the final call's reasoning belongs after the observation it read, not at the top of the turn");
        assertEquals("目前没有会议室被占用。", acc.getContent(),
                "segmentOnly narration stays out of the persisted top-level content");
    }

    @Test
    @DisplayName("multi-iteration turn keeps one thinking span per iteration, in place")
    void everyIterationsThinkingSurvives() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator acc = new AgentStreamAccumulator(mapper, NOOP_SINK);
        String cid = "conv-order-4";

        // Two tool rounds, each preceded by its own reasoning, then the answer.
        acc.accept(StreamDelta.persistOnly(null, "先确认今天的日期。"), cid);
        acc.accept(StreamDelta.event("tool_call_started",
                Map.of("toolCallId", "t1", "toolName", "clock", "arguments", "{}")), cid);
        acc.accept(StreamDelta.event("tool_call_completed",
                Map.of("toolCallId", "t1", "toolName", "clock", "result", "2026-08-06", "success", true)), cid);
        acc.accept(StreamDelta.persistOnly(null, "拿到日期了，再算天数差。"), cid);
        acc.accept(StreamDelta.event("tool_call_started",
                Map.of("toolCallId", "t2", "toolName", "calc", "arguments", "{}")), cid);
        acc.accept(StreamDelta.event("tool_call_completed",
                Map.of("toolCallId", "t2", "toolName", "calc", "result", "147", "success", true)), cid);
        acc.accept(StreamDelta.persistOnly(null, "147 天，可以作答。"), cid);
        acc.accept(StreamDelta.finalAnswer("还有 147 天。", true), cid);

        JsonNode segments = segmentsOf(acc, mapper);
        assertEquals(
                List.of("thinking", "tool_call", "thinking", "tool_call", "thinking", "content"),
                typesOf(segments),
                "each iteration's reasoning stays at the point it was produced");

        assertEquals("先确认今天的日期。", segments.get(0).path("thinkingText").asText());
        assertEquals("拿到日期了，再算天数差。", segments.get(2).path("thinkingText").asText());
        assertEquals("147 天，可以作答。", segments.get(4).path("thinkingText").asText());

        assertEquals("先确认今天的日期。\n\n拿到日期了，再算天数差。\n\n147 天，可以作答。", acc.getThinking(),
                "the flat thinking field carries every span, separated so they stay readable");
    }

    @Test
    @DisplayName("deltas within one span append without inserting a separator")
    void withinSpanDeltasAreNotSeparated() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator acc = new AgentStreamAccumulator(mapper, NOOP_SINK);
        String cid = "conv-order-5";

        // A streaming channel feeds one span as many small deltas.
        acc.accept(StreamDelta.persistOnly(null, "先看"), cid);
        acc.accept(StreamDelta.persistOnly(null, "一下"), cid);
        acc.accept(StreamDelta.persistOnly(null, "输入。"), cid);

        JsonNode segments = segmentsOf(acc, mapper);
        assertEquals(List.of("thinking"), typesOf(segments), "one running span, not three");
        assertEquals("先看一下输入。", acc.getThinking());
    }

    @Test
    @DisplayName("every segment carries a monotonic seq matching its emission position")
    void segmentsCarryMonotonicSeq() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentStreamAccumulator acc = new AgentStreamAccumulator(mapper, NOOP_SINK);
        String cid = "conv-order-3";

        acc.accept(StreamDelta.segmentOnly("先查一下会议室占用。", null), cid);
        acc.accept(StreamDelta.event("tool_call_started",
                Map.of("toolCallId", "t1", "toolName", "roomQuery", "arguments", "{}")), cid);
        acc.accept(StreamDelta.event("tool_call_completed",
                Map.of("toolCallId", "t1", "toolName", "roomQuery", "result", "[]", "success", true)), cid);
        acc.accept(StreamDelta.persistOnly(null, "返回为空，说明当前没有占用记录。"), cid);
        acc.accept(StreamDelta.finalAnswer("目前没有会议室被占用。", true), cid);

        JsonNode segments = segmentsOf(acc, mapper);
        for (int i = 0; i < segments.size(); i++) {
            assertEquals(i, segments.get(i).path("seq").asInt(-1),
                    "seq is the emission index — renderers sort by it instead of relocating by type");
        }
    }
}
