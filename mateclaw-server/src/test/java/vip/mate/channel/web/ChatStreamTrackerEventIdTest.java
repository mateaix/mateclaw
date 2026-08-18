package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamTrackerEventIdTest {

    @Test
    void eventIdsIncreaseAcrossChannelsAndRecreatedRunState() {
        ChatStreamTracker tracker = new ChatStreamTracker(new ObjectMapper());
        CapturingEmitter firstChannel = new CapturingEmitter();
        CapturingEmitter secondChannel = new CapturingEmitter();
        CapturingEmitter recreatedChannel = new CapturingEmitter();

        tracker.register("channel-a");
        tracker.attach("channel-a", firstChannel);
        tracker.broadcast("channel-a", "progress", "{}");

        tracker.register("channel-b");
        tracker.attach("channel-b", secondChannel);
        tracker.broadcast("channel-b", "progress", "{}");

        tracker.broadcast("channel-a", "done", "{}");
        tracker.register("channel-a");
        tracker.attach("channel-a", recreatedChannel, Long.MAX_VALUE);
        tracker.broadcast("channel-a", "progress", "{}");

        long first = firstChannel.ids.getFirst();
        long second = secondChannel.ids.getFirst();
        long recreated = recreatedChannel.ids.getFirst();
        assertTrue(first < second);
        assertTrue(second < recreated);
    }

    @Test
    void laterClockFloorStartsAboveIdsFromAnEarlierGeneratorInstance() {
        SseEventIdGenerator firstProcess = new SseEventIdGenerator(() -> 1_000L);
        long first = firstProcess.nextId();
        long second = firstProcess.nextId();

        SseEventIdGenerator restartedProcess = new SseEventIdGenerator(() -> 1_001L);
        long afterRestart = restartedProcess.nextId();

        assertEquals(first + 1, second);
        assertTrue(afterRestart > second);
    }

    @Test
    void concurrentAllocationIsUnique() {
        SseEventIdGenerator generator = new SseEventIdGenerator(() -> 1_000L);
        Set<Long> ids = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 10_000).parallel().forEach(ignored -> ids.add(generator.nextId()));

        assertEquals(10_000, ids.size());
    }

    @Test
    void currentEventIdsStayWithinTheJavaScriptSafeIntegerRange() {
        SseEventIdGenerator generator = new SseEventIdGenerator(System::currentTimeMillis);

        assertTrue(generator.nextId() <= SseEventIdGenerator.MAX_SAFE_INTEGER);
    }

    @Test
    void fixedClockCanBorrowFutureSlotsBeyondOneMillisecondCapacity() {
        SseEventIdGenerator generator = new SseEventIdGenerator(() -> 1_000L);

        long first = generator.nextId();
        long last = IntStream.range(0, 2_048)
                .mapToLong(ignored -> generator.nextId())
                .reduce(first, (ignored, id) -> id);

        assertEquals(first + 2_048, last);
        assertTrue(last <= SseEventIdGenerator.MAX_SAFE_INTEGER);
    }

    @Test
    void exhaustsAtTheJavaScriptSafeIntegerBoundary() {
        long maxEpochMillis = SseEventIdGenerator.MAX_SAFE_INTEGER / 1_024L;
        SseEventIdGenerator generator = new SseEventIdGenerator(() -> maxEpochMillis);

        long last = 0L;
        for (int i = 0; i < 1_024; i++) {
            last = generator.nextId();
        }

        assertEquals(SseEventIdGenerator.MAX_SAFE_INTEGER, last);
        assertThrows(IllegalStateException.class, generator::nextId);
        assertThrows(IllegalStateException.class,
                () -> new SseEventIdGenerator(() -> maxEpochMillis + 1));
    }

    private static final class CapturingEmitter extends SseEmitter {

        private final List<Long> ids = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            Set<ResponseBodyEmitter.DataWithMediaType> entries = builder.build();
            for (ResponseBodyEmitter.DataWithMediaType entry : entries) {
                if (entry.getData() instanceof String text && text.startsWith("id:")) {
                    int end = text.indexOf('\n');
                    ids.add(Long.parseLong(text.substring(3, end).trim()));
                }
            }
        }
    }
}
