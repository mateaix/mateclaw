package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamTrackerQueueDrainTest {

    private ChatStreamTracker newTracker() {
        return new ChatStreamTracker(new ObjectMapper());
    }

    @Test
    @DisplayName("Run state tracks only a durable queue wake signal")
    void runStateTracksOnlyDurableQueueWakeSignal() {
        ChatStreamTracker tracker = newTracker();
        String conversationId = "queue-drain";

        tracker.register(conversationId);
        tracker.incrementFlux(conversationId);
        assertTrue(tracker.notifyQueuedInput(conversationId));
        assertTrue(tracker.hasQueuedInputNotification(conversationId));

        ChatStreamTracker.CompletionResult completed = tracker.completeAndConsumeIfLast(conversationId);
        assertTrue(completed.allDone());
        assertFalse(tracker.notifyQueuedInput(conversationId));

        tracker.register(conversationId);
        tracker.incrementFlux(conversationId);
        assertFalse(tracker.hasQueuedInputNotification(conversationId));
    }
}
