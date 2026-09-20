package vip.mate.channel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamTrackerContentBatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void adjacentContentDeltasFlushAsOneTimedBatch() throws Exception {
        ChatStreamTracker tracker = tracker(25, 256);
        CapturingEmitter emitter = attach(tracker, "timed");

        tracker.broadcast("timed", "content_delta", "{\"delta\":\"你\"}");
        tracker.broadcast("timed", "content_delta", "{\"delta\":\"好\"}");

        awaitEventCount(emitter, 1);
        assertEquals(1, emitter.events.size());
        assertEquals("content_delta", emitter.events.getFirst().name());
        assertEquals("你好", text(emitter.events.getFirst().data(), "delta"));
    }

    @Test
    void characterLimitFlushesWithoutWaitingForTimer() throws Exception {
        ChatStreamTracker tracker = tracker(60_000, 4);
        CapturingEmitter emitter = attach(tracker, "bounded");

        tracker.broadcast("bounded", "content_delta", "{\"delta\":\"ab\"}");
        tracker.broadcast("bounded", "content_delta", "{\"delta\":\"cd\"}");

        assertEquals(1, emitter.events.size());
        assertEquals("abcd", text(emitter.events.getFirst().data(), "delta"));
    }

    @Test
    void lifecycleEventFlushesContentFirstAndDoneIsReplayable() throws Exception {
        ChatStreamTracker tracker = tracker(60_000, 256);
        CapturingEmitter live = attach(tracker, "ordered");

        tracker.broadcast("ordered", "content_delta", "{\"delta\":\"answer\"}");
        tracker.broadcast("ordered", "phase", "{\"phase\":\"complete\"}");
        tracker.broadcast("ordered", "done", "{\"status\":\"completed\"}");

        assertEquals(List.of("content_delta", "phase", "done"), live.names());

        CapturingEmitter replay = new CapturingEmitter();
        assertTrue(tracker.attach("ordered", replay));
        assertEquals(List.of("content_delta", "phase", "done"), replay.names());
        assertEquals("answer", text(replay.events.getFirst().data(), "delta"));
    }

    @Test
    void webchatTextPayloadKeepsItsWireField() throws Exception {
        ChatStreamTracker tracker = tracker(60_000, 4);
        ChatStreamTracker.RunHandle handle = tracker.register("webchat");
        CapturingEmitter emitter = new CapturingEmitter();
        tracker.attach(handle, emitter);

        tracker.broadcast(handle, "content_delta", "{\"text\":\"ab\"}");
        tracker.broadcast(handle, "content_delta", "{\"text\":\"cd\"}");

        assertEquals(1, emitter.events.size());
        assertEquals("abcd", text(emitter.events.getFirst().data(), "text"));
    }

    @Test
    void lifecycleCompletionFlushesPendingContentEvenWithoutDoneEnvelope() throws Exception {
        ChatStreamTracker tracker = tracker(60_000, 256);
        ChatStreamTracker.RunHandle handle = tracker.register("complete");
        CapturingEmitter emitter = new CapturingEmitter();
        tracker.attach(handle, emitter);

        tracker.broadcast(handle, "content_delta", "{\"delta\":\"partial\"}");
        tracker.complete(handle);

        assertEquals(1, emitter.events.size());
        assertEquals("partial", text(emitter.events.getFirst().data(), "delta"));
    }

    private static ChatStreamTracker tracker(long flushMs, int maxChars) {
        ChatStreamTracker tracker = new ChatStreamTracker(MAPPER);
        tracker.setContentBatchingForTesting(flushMs, maxChars);
        return tracker;
    }

    private static CapturingEmitter attach(ChatStreamTracker tracker, String conversationId) {
        tracker.register(conversationId);
        CapturingEmitter emitter = new CapturingEmitter();
        tracker.attach(conversationId, emitter);
        return emitter;
    }

    private static void awaitEventCount(CapturingEmitter emitter, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1_000;
        while (emitter.events.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    private static String text(String json, String field) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        return node.path(field).asText();
    }

    private record Event(String name, String data) {}

    private static final class CapturingEmitter extends SseEmitter {
        private final List<Event> events = new CopyOnWriteArrayList<>();

        CapturingEmitter() {
            super(60_000L);
        }

        List<String> names() {
            return events.stream().map(Event::name).toList();
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            Set<ResponseBodyEmitter.DataWithMediaType> entries = builder.build();
            String name = "";
            String payload = "";
            boolean expectPayload = false;
            for (ResponseBodyEmitter.DataWithMediaType entry : entries) {
                if (!(entry.getData() instanceof String text)) continue;
                if (text.contains("event:") && text.contains("data:")) {
                    int start = text.indexOf("event:") + 6;
                    int end = text.indexOf('\n', start);
                    name = text.substring(start, end < 0 ? text.length() : end).trim();
                    expectPayload = true;
                } else if (expectPayload && !"\n\n".equals(text)) {
                    payload = text;
                    expectPayload = false;
                }
            }
            events.add(new Event(name, payload));
        }
    }
}
