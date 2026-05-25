package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link ChatStreamTracker#broadcastChunked} splits oversize
 * payloads into ordered {@code tool_result_chunk} events with the documented
 * envelope shape, and leaves small payloads intact.
 */
class ChatStreamTrackerChunkedBroadcastTest {

    private ChatStreamTracker newTracker() {
        return new ChatStreamTracker(new ObjectMapper());
    }

    /**
     * Captures (eventName, jsonData) tuples by intercepting Spring's
     * {@code send(SseEventBuilder)} path. The builder emits multiple
     * "event:..." / "data:..." entries when rendered to a Set, so we walk
     * the rendered set once and collect a single logical pair.
     */
    private static final class CapturingEmitter extends SseEmitter {
        final List<Map<String, String>> events = new CopyOnWriteArrayList<>();

        CapturingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            Set<ResponseBodyEmitter.DataWithMediaType> entries = builder.build();
            // Spring renders the SSE event as:
            //   1) header string: "event:<name>\ndata:" (note: data: prefix
            //      already attached, no payload yet)
            //   2) the actual data object (here always a JSON string)
            //   3) terminator string: "\n\n"
            // We detect the header from prefix scanning, then take the next
            // String entry as the payload. Anything else is ignored.
            String name = null;
            String payload = null;
            boolean expectPayload = false;
            for (ResponseBodyEmitter.DataWithMediaType d : entries) {
                Object obj = d.getData();
                if (!(obj instanceof String text)) continue;
                if (text.contains("event:") && text.contains("data:")) {
                    int evStart = text.indexOf("event:") + "event:".length();
                    int evEnd = text.indexOf('\n', evStart);
                    if (evEnd < 0) evEnd = text.length();
                    name = text.substring(evStart, evEnd).trim();
                    expectPayload = true;
                } else if (expectPayload && payload == null && !text.equals("\n\n")) {
                    payload = text;
                    expectPayload = false;
                }
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("event", name != null ? name : "");
            entry.put("data", payload != null ? payload : "");
            events.add(entry);
        }
    }

    @Test
    @DisplayName("Small payload broadcasts as a single event unchanged")
    void smallPayloadBroadcastsUnchanged() {
        ChatStreamTracker tracker = newTracker();
        String cid = "small-payload";
        tracker.register(cid);
        CapturingEmitter emitter = new CapturingEmitter();
        tracker.attach(cid, emitter);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", "call-1");
        payload.put("toolName", "echo");
        payload.put("result", "small text");
        payload.put("success", true);

        tracker.broadcastChunked(cid, "tool_call_completed", payload, "call-1");

        long completedCount = emitter.events.stream()
                .filter(e -> "tool_call_completed".equals(e.get("event"))).count();
        long chunkCount = emitter.events.stream()
                .filter(e -> "tool_result_chunk".equals(e.get("event"))).count();
        assertEquals(1, completedCount, "Expected single tool_call_completed event");
        assertEquals(0, chunkCount, "No chunk events for small payload");
    }

    @Test
    @DisplayName("Large payload splits into ordered tool_result_chunk events with final flag")
    void largePayloadChunksAndTerminates() throws Exception {
        ChatStreamTracker tracker = newTracker();
        String cid = "large-payload";
        tracker.register(cid);
        CapturingEmitter emitter = new CapturingEmitter();
        tracker.attach(cid, emitter);

        // Build a result well above CHUNK_SIZE (8192 bytes) so the splitter
        // produces multiple chunks. 30 KiB ensures at least 4 splits even
        // after envelope overhead.
        StringBuilder big = new StringBuilder(30_000);
        for (int i = 0; i < 3000; i++) {
            big.append("0123456789");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", "call-large");
        payload.put("toolName", "shell");
        payload.put("result", big.toString());
        payload.put("success", true);

        tracker.broadcastChunked(cid, "tool_call_completed", payload, "call-large");

        // 1. Header event preserved with empty result + chunked=true.
        List<Map<String, String>> completed = new ArrayList<>();
        List<Map<String, String>> chunks = new ArrayList<>();
        for (Map<String, String> e : emitter.events) {
            if ("tool_call_completed".equals(e.get("event"))) completed.add(e);
            else if ("tool_result_chunk".equals(e.get("event"))) chunks.add(e);
        }
        assertEquals(1, completed.size(), "Header event should fire exactly once");
        assertTrue(chunks.size() >= 4,
                "Expected several chunk events; got " + chunks.size());

        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> header = mapper.readValue(completed.get(0).get("data"), Map.class);
        assertEquals(Boolean.TRUE, header.get("chunked"));
        assertEquals("call-large", header.get("chunkRef"));
        assertEquals("", header.get("result"),
                "Header must replace long field with empty placeholder");

        // 2. Chunk envelope: kind / scope / ref / seq monotonic / final on last.
        StringBuilder reconstructed = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Map<?, ?> chunk = mapper.readValue(chunks.get(i).get("data"), Map.class);
            assertEquals("tool_result", chunk.get("kind"));
            assertEquals("parent", chunk.get("scope"));
            assertEquals("call-large", chunk.get("ref"));
            assertEquals(i, chunk.get("seq"), "Chunks must be in seq order");
            boolean isLast = (i == chunks.size() - 1);
            assertEquals(isLast, chunk.get("final"),
                    "Only the last chunk should set final=true");
            reconstructed.append((String) chunk.get("delta"));
        }
        assertEquals(big.toString(), reconstructed.toString(),
                "Concatenated chunks must reproduce the original result verbatim");
    }

    @Test
    @DisplayName("Disabling chunked transport keeps single-event behavior")
    void disabledChunkingIsPassThrough() {
        ChatStreamTracker tracker = newTracker();
        tracker.setChunkedToolResultsEnabled(false);
        String cid = "disabled";
        tracker.register(cid);
        CapturingEmitter emitter = new CapturingEmitter();
        tracker.attach(cid, emitter);

        StringBuilder big = new StringBuilder(15_000);
        for (int i = 0; i < 1500; i++) big.append("0123456789");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", "call-x");
        payload.put("toolName", "shell");
        payload.put("result", big.toString());
        payload.put("success", true);

        tracker.broadcastChunked(cid, "tool_call_completed", payload, "call-x");

        long chunkCount = emitter.events.stream()
                .filter(e -> "tool_result_chunk".equals(e.get("event"))).count();
        assertEquals(0, chunkCount, "Chunking disabled — must not emit chunk events");
    }
}
