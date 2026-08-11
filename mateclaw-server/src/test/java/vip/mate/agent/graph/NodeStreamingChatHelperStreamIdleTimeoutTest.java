package vip.mate.agent.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.llm.failover.ProviderHealthProperties;
import vip.mate.llm.failover.ProviderHealthTracker;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

/**
 * Regression test for issue #585: the streaming body Flux must have an
 * inter-frame idle timeout so a provider that accepts the connection and then
 * goes silent cannot hang the call forever (no exception → no failover).
 *
 * <p>The JDK HttpClient request timeout (what {@code setReadTimeout} maps to)
 * only protects up to the response headers; once they arrive the clock stops.
 * A reactor {@code .timeout()} on the delta Flux closes the body-level gap.
 * This test wires a {@link ChatModel} whose {@code stream()} returns
 * {@link Flux#never()} (a provider that sends headers then stalls) and asserts
 * the call surfaces an error within a bounded time instead of hanging until
 * the 10-minute latch deadline.
 */
class NodeStreamingChatHelperStreamIdleTimeoutTest {

    private ChatStreamTracker streamTracker;
    private ProviderHealthTracker healthTracker;

    @BeforeEach
    void setUp() {
        streamTracker = mock(ChatStreamTracker.class);
        when(streamTracker.isStopRequested(any())).thenReturn(false);
        healthTracker = new ProviderHealthTracker(new ProviderHealthProperties());
    }

    /** A chat model whose stream() never emits — a stalled provider. */
    private static ChatModel stalledModel() {
        ChatModel m = mock(ChatModel.class);
        when(m.stream(any(Prompt.class))).thenReturn(Flux.never());
        return m;
    }

    private NodeStreamingChatHelper helperWithIdle(ChatModel primary, long idleSec) {
        NodeStreamingChatHelper h = new NodeStreamingChatHelper(
                streamTracker, List.of(), null, healthTracker, "stalled-provider");
        // Shrink the retry backoff so the full retry loop stays fast even
        // though each attempt waits `idleSec` for the idle timeout to fire.
        h.setRetryTimingForTest(1L, 1L, 5_000L);
        h.setStreamIdleTimeoutSec(idleSec);
        return h;
    }

    private static Prompt smallPrompt() {
        return new Prompt(List.of(new UserMessage("hi")));
    }

    @Test
    @DisplayName("Stalled stream (Flux.never) surfaces an error via the idle timeout, not the 10-min latch")
    void stalledStreamSurfacesErrorViaIdleTimeout() {
        ChatModel primary = stalledModel();
        // 1s idle timeout; the retry loop exhausts well inside the 60s bound.
        NodeStreamingChatHelper helper = helperWithIdle(primary, 1L);

        // assertTimeoutPreemptively fails the test (and unwedges it) if the
        // idle timeout did NOT wire — the call would otherwise block on the
        // 10-minute latch deadline.
        var result = assertTimeoutPreemptively(Duration.ofSeconds(60), () ->
                helper.streamCall(primary, smallPrompt(), "conv-stall", "reasoning"));

        // The stalled provider produced no text and a non-NONE error type —
        // the idle timeout fired (otherwise the latch would have timed out
        // and the result would still carry a generic timeout message, but
        // far slower; the bounded duration above is the real assertion).
        assertNotEquals(NodeStreamingChatHelper.ErrorType.NONE, result.errorType(),
                "a stalled stream must surface a non-NONE error type via the idle timeout");
        // The primary was retried (idle-timeout error is retryable), proving
        // the timeout propagated through the normal error path rather than
        // hanging the subscription. atLeast(2) is enough — the exact count
        // depends on the retry time budget, which the test shrinks.
        verify(primary, org.mockito.Mockito.atLeast(2)).stream(any(Prompt.class));
    }

    @Test
    @DisplayName("idle timeout disabled (<=0) keeps the legacy behavior: stream completes normally when not stalled")
    void disabledIdleTimeoutDoesNotBreakNormalStream() {
        // A fast-completing stream must still work when the idle timeout is
        // turned off — guards against the .timeout() wiring accidentally
        // short-circuiting the happy path.
        ChatModel m = mock(ChatModel.class);
        var gen = new Generation(
                new AssistantMessage("ok"), ChatGenerationMetadata.NULL);
        var resp = mock(ChatResponse.class);
        when(resp.getResults()).thenReturn(List.of(gen));
        when(resp.getResult()).thenReturn(gen);
        when(resp.getMetadata()).thenReturn(null);
        when(m.stream(any(Prompt.class))).thenReturn(Flux.just(resp));

        NodeStreamingChatHelper helper = helperWithIdle(m, 0L);

        var result = assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                helper.streamCall(m, smallPrompt(), "conv-ok", "reasoning"));

        assertThat(result.text()).contains("ok");
    }
}
