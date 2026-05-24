package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatStreamTrackerQueueDrainTest {

    private ChatStreamTracker newTracker() {
        return new ChatStreamTracker(new ObjectMapper());
    }

    @Test
    @DisplayName("Queued inputs survive RunState replacement and drain FIFO")
    void queuedInputsSurviveRunStateReplacementAndDrainFifo() {
        ChatStreamTracker tracker = newTracker();
        String conversationId = "queue-drain";

        tracker.register(conversationId);
        tracker.incrementFlux(conversationId);
        assertTrue(tracker.enqueueMessage(conversationId, "q1", 101L, false));
        assertTrue(tracker.enqueueMessage(conversationId, "q2", 101L, false));
        assertTrue(tracker.enqueueMessage(conversationId, "q3", 101L, false));

        ChatStreamTracker.CompletionResult first = tracker.completeAndConsumeIfLast(conversationId);
        assertTrue(first.allDone());
        assertNotNull(first.queuedInput());
        assertEquals("q1", first.queuedInput().message());

        tracker.register(conversationId);
        tracker.incrementFlux(conversationId);
        ChatStreamTracker.CompletionResult second = tracker.completeAndConsumeIfLast(conversationId);
        assertTrue(second.allDone());
        assertNotNull(second.queuedInput());
        assertEquals("q2", second.queuedInput().message());

        tracker.register(conversationId);
        tracker.incrementFlux(conversationId);
        ChatStreamTracker.CompletionResult third = tracker.completeAndConsumeIfLast(conversationId);
        assertTrue(third.allDone());
        assertNotNull(third.queuedInput());
        assertEquals("q3", third.queuedInput().message());

        tracker.register(conversationId);
        tracker.incrementFlux(conversationId);
        ChatStreamTracker.CompletionResult empty = tracker.completeAndConsumeIfLast(conversationId);
        assertTrue(empty.allDone());
        assertNull(empty.queuedInput());
    }
}
