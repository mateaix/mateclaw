package vip.mate.agent.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression tests for Lane D performance fixes (RFC 06-lane-d-performance-fixes).
 *
 * <ul>
 *   <li>D-1: Backoff sleep responds to Stop signal within 100ms</li>
 *   <li>D-2: RATE_LIMIT/SERVER_ERROR retries capped at 2 (was 5)</li>
 * </ul>
 */
class LaneDPerformanceFixesTest {

    private ChatStreamTracker streamTracker;

    @BeforeEach
    void setUp() {
        streamTracker = mock(ChatStreamTracker.class);
        when(streamTracker.isStopRequested(any())).thenReturn(false);
    }

    private NodeStreamingChatHelper helper(ChatModel primary) {
        return new NodeStreamingChatHelper(streamTracker, List.of(), null);
    }

    private static Prompt smallPrompt() {
        return new Prompt(List.of(new UserMessage("hi")));
    }

    private static ChatModel successModel(String text) {
        ChatModel m = mock(ChatModel.class);
        Generation gen = new Generation(new AssistantMessage(text), ChatGenerationMetadata.NULL);
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResults()).thenReturn(List.of(gen));
        when(resp.getResult()).thenReturn(gen);
        when(resp.getMetadata()).thenReturn(null);
        when(m.stream(any(Prompt.class))).thenReturn(Flux.just(resp));
        return m;
    }

    private static ChatModel rateLimitModel() {
        ChatModel m = mock(ChatModel.class);
        when(m.stream(any(Prompt.class))).thenReturn(
                Flux.error(new RuntimeException("429 Too Many Requests: rate limit exceeded")));
        return m;
    }

    // ============================================================
    // D-1: Backoff sleep responds to Stop signal
    // ============================================================

    @Nested
    @DisplayName("D-1: Backoff sleep responds to Stop signal")
    class BackoffStopSignalTests {

        @Test
        @DisplayName("Stop requested during backoff aborts retry quickly with CancellationException")
        void stopDuringBackoffAbortsRetry() {
            // Arrange: model always returns rate-limit error to trigger backoff
            AtomicInteger callCount = new AtomicInteger(0);
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
                callCount.incrementAndGet();
                return Flux.error(new RuntimeException("429 Too Many Requests"));
            });

            // Stop is requested after first call — during backoff sleep.
            // First poll returns false (initial check before sleep loop starts),
            // then true on subsequent checks to simulate user clicking stop.
            AtomicInteger stopCheckCount = new AtomicInteger(0);
            when(streamTracker.isStopRequested("conv-d1")).thenAnswer(inv ->
                    stopCheckCount.incrementAndGet() > 2);

            var helper = helper(model);
            long startMs = System.currentTimeMillis();

            // The stop-during-backoff path throws CancellationException
            assertThrows(CancellationException.class, () ->
                    helper.streamCall(model, smallPrompt(), "conv-d1", "reasoning"));

            long elapsedMs = System.currentTimeMillis() - startMs;

            // The backoff for attempt 1 is 3000ms base. With stop polling at 100ms intervals,
            // it should abort well before the full 3000ms backoff completes.
            assertTrue(elapsedMs < 2000,
                    "Stop should abort backoff quickly, but took " + elapsedMs + "ms");
            // The model should only have been called once (first attempt fails, backoff
            // for second attempt is interrupted by stop)
            assertEquals(1, callCount.get(),
                    "Model should only be called once before stop aborts the backoff");
        }

        @Test
        @DisplayName("Normal flow without stop completes backoff normally")
        void normalFlowWithoutStopCompletesBackoff() {
            // First call: rate limit; second call: success
            AtomicInteger callCount = new AtomicInteger(0);
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
                if (callCount.incrementAndGet() == 1) {
                    return Flux.error(new RuntimeException("429 Too Many Requests"));
                }
                Generation gen = new Generation(new AssistantMessage("ok"), ChatGenerationMetadata.NULL);
                ChatResponse resp = mock(ChatResponse.class);
                when(resp.getResults()).thenReturn(List.of(gen));
                when(resp.getResult()).thenReturn(gen);
                when(resp.getMetadata()).thenReturn(null);
                return Flux.just(resp);
            });

            // Stop never requested
            when(streamTracker.isStopRequested(any())).thenReturn(false);

            var helper = helper(model);
            var result = helper.streamCall(model, smallPrompt(), "conv-d1b", "reasoning");

            assertEquals("ok", result.text(), "Second attempt should succeed");
            assertEquals(2, callCount.get(), "Model should be called twice (fail + succeed)");
        }
    }

    // ============================================================
    // D-2: RATE_LIMIT retries capped at 2
    // ============================================================

    @Nested
    @DisplayName("D-2: RATE_LIMIT/SERVER_ERROR retries capped at 2")
    class RateLimitRetryCapTests {

        @Test
        @DisplayName("RATE_LIMIT error retries at most 2 times before giving up")
        void rateLimitMaxTwoRetries() {
            AtomicInteger callCount = new AtomicInteger(0);
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
                callCount.incrementAndGet();
                return Flux.error(new RuntimeException("429 Too Many Requests: rate limit"));
            });

            var helper = helper(model);
            var result = helper.streamCall(model, smallPrompt(), "conv-d2a", "reasoning");

            // With MAX_RETRIES_RATE_LIMIT=2, attempts are: 0, 1, 2 = 3 total calls
            assertTrue(callCount.get() <= 3,
                    "RATE_LIMIT should retry at most 2 times (3 total calls), but got " + callCount.get());
            assertNotEquals(NodeStreamingChatHelper.ErrorType.NONE, result.errorType(),
                    "Result should be an error after exhausting retries");
        }

        @Test
        @DisplayName("SERVER_ERROR keeps full MAX_RETRIES (not capped like RATE_LIMIT)")
        void serverErrorKeepsFullRetries() {
            AtomicInteger callCount = new AtomicInteger(0);
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
                callCount.incrementAndGet();
                return Flux.error(new RuntimeException("500 Internal Server Error"));
            });

            var helper = helper(model);
            var result = helper.streamCall(model, smallPrompt(), "conv-d2b", "reasoning");

            // SERVER_ERROR should use the full MAX_RETRIES budget, NOT the reduced
            // MAX_RETRIES_RATE_LIMIT=2. Total calls = MAX_RETRIES + 1 (attempt 0 +
            // MAX_RETRIES retries). Reading the constant directly keeps this test
            // in sync if MAX_RETRIES changes again — last bumped 5 -> 10 in
            // commit 1dd99b68 to ride out provider flaps under wiki batch load.
            int expectedCalls = NodeStreamingChatHelper.MAX_RETRIES + 1;
            assertTrue(callCount.get() > NodeStreamingChatHelper.MAX_RETRIES_RATE_LIMIT + 1,
                    "SERVER_ERROR should retry more than RATE_LIMIT, but got " + callCount.get());
            assertEquals(expectedCalls, callCount.get(),
                    "SERVER_ERROR should try " + expectedCalls + " times total " +
                    "(attempt 0 through " + NodeStreamingChatHelper.MAX_RETRIES + ")");
        }

        @Test
        @DisplayName("AUTH_ERROR is not retried (unchanged behavior)")
        void authErrorNotRetried() {
            AtomicInteger callCount = new AtomicInteger(0);
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
                callCount.incrementAndGet();
                return Flux.error(new RuntimeException("401 Unauthorized"));
            });

            var helper = helper(model);
            var result = helper.streamCall(model, smallPrompt(), "conv-d2c", "reasoning");

            assertEquals(1, callCount.get(),
                    "AUTH_ERROR should not be retried at all");
            assertEquals(NodeStreamingChatHelper.ErrorType.AUTH_ERROR, result.errorType());
        }
    }

    // ============================================================
    // D-3: broadcastProgress method exists and works
    // ============================================================

    @Nested
    @DisplayName("D-3: broadcastProgress method")
    class BroadcastProgressTests {

        @Test
        @DisplayName("broadcastProgress sends progress event via streamTracker")
        void broadcastProgressSendsEvent() {
            var helper = new NodeStreamingChatHelper(streamTracker);
            helper.broadcastProgress("conv-d3", "分析中...");

            verify(streamTracker, times(1)).broadcastObject(
                    eq("conv-d3"), eq("progress"), any());
        }

        @Test
        @DisplayName("broadcastProgress is safe with null streamTracker")
        void broadcastProgressNullTrackerNoOp() {
            var helper = new NodeStreamingChatHelper(null);
            // Should not throw
            assertDoesNotThrow(() -> helper.broadcastProgress("conv-d3b", "分析中..."));
        }

        @Test
        @DisplayName("broadcastProgress is safe with null conversationId")
        void broadcastProgressNullConvIdNoOp() {
            var helper = new NodeStreamingChatHelper(streamTracker);
            assertDoesNotThrow(() -> helper.broadcastProgress(null, "分析中..."));
            // Should not invoke streamTracker when conversationId is null
            verify(streamTracker, never()).broadcastObject(any(), any(), any());
        }
    }
}
