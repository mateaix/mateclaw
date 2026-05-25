package vip.mate.agent.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import vip.mate.llm.failover.FallbackEntry;
import vip.mate.llm.failover.ProviderHealthProperties;
import vip.mate.llm.failover.ProviderHealthTracker;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression test for the AUTH_ERROR-must-fall-back fix.
 *
 * <p>Prior to this fix, primary AUTH_ERROR (e.g. Kimi 401 with an invalid
 * API key) returned immediately without trying the fallback chain — a
 * fallback provider with a different, valid key never got a chance.
 * After the fix, AUTH_ERROR breaks out of the same-model retry loop
 * and falls through to the chain walker, mirroring how BILLING and
 * MODEL_NOT_FOUND already behave.</p>
 */
class NodeStreamingChatHelperFailoverTest {

    private ChatStreamTracker streamTracker;
    private ProviderHealthTracker healthTracker;

    @BeforeEach
    void setUp() {
        streamTracker = mock(ChatStreamTracker.class);
        when(streamTracker.isStopRequested(any())).thenReturn(false);
        ProviderHealthProperties props = new ProviderHealthProperties();
        healthTracker = new ProviderHealthTracker(props);
    }

    /** Build a chat-model mock whose stream() emits a single successful chunk with the given text. */
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

    /** Build a chat-model mock whose stream() errors with the given Throwable. */
    private static ChatModel errorModel(Throwable err) {
        ChatModel m = mock(ChatModel.class);
        when(m.stream(any(Prompt.class))).thenReturn(Flux.error(err));
        return m;
    }

    private NodeStreamingChatHelper helper(ChatModel primary, List<FallbackEntry> chain, String primaryProviderId) {
        // Construct via the full constructor so health tracking is wired and the
        // chain walker has provider-id context.
        return new NodeStreamingChatHelper(streamTracker, chain, null, healthTracker, primaryProviderId);
    }

    private static Prompt smallPrompt() {
        return new Prompt(List.of(new UserMessage("hi")));
    }

    // ============================================================
    // C1: primary 401 + fallback#1 success → fallback wins
    // ============================================================

    @Test
    @DisplayName("C1: primary AUTH_ERROR triggers fallback chain (was: returned immediately, never tried fallback)")
    void primaryAuthErrorFallsBackToHealthyProvider() {
        ChatModel primary = errorModel(new RuntimeException("401 Unauthorized: Invalid API Key"));
        ChatModel fallback = successModel("hello from fallback");
        var helper = helper(primary, List.of(new FallbackEntry("dashscope", fallback)), "kimi");

        var result = helper.streamCall(primary, smallPrompt(), "conv-c1", "reasoning");

        assertEquals("hello from fallback", result.text(),
                "fallback provider must succeed and its text must surface as the result");
        assertEquals(NodeStreamingChatHelper.ErrorType.NONE, result.errorType());
        // Primary was tried exactly once (no same-model retries on AUTH_ERROR — fix verified)
        verify(primary, times(1)).stream(any(Prompt.class));
        verify(fallback, times(1)).stream(any(Prompt.class));
    }

    // ============================================================
    // C2: primary 401 + fallback#1 401 + fallback#2 success
    // ============================================================

    @Test
    @DisplayName("C2: chain walks past auth-failing fallback to the next healthy one")
    void chainSkipsAuthFailingFallback() {
        ChatModel primary = errorModel(new RuntimeException("401 Unauthorized"));
        ChatModel fbBad = errorModel(new RuntimeException("401 Unauthorized: bad key"));
        ChatModel fbGood = successModel("ok via 2nd fallback");
        var helper = helper(primary, List.of(
                new FallbackEntry("openai", fbBad),
                new FallbackEntry("dashscope", fbGood)), "kimi");

        var result = helper.streamCall(primary, smallPrompt(), "conv-c2", "reasoning");

        assertEquals("ok via 2nd fallback", result.text());
        assertEquals(NodeStreamingChatHelper.ErrorType.NONE, result.errorType());
        verify(primary, times(1)).stream(any(Prompt.class));
        verify(fbBad, times(1)).stream(any(Prompt.class));
        verify(fbGood, times(1)).stream(any(Prompt.class));
    }

    // ============================================================
    // C3: primary 401 + every fallback 401 → last AUTH_ERROR surfaces
    // ============================================================

    @Test
    @DisplayName("C3: when entire chain is auth-failing, last AUTH_ERROR is surfaced (not silently dropped)")
    void allChainAuthFailsSurfacesLastError() {
        ChatModel primary = errorModel(new RuntimeException("401 Unauthorized — kimi"));
        ChatModel fb1 = errorModel(new RuntimeException("401 Unauthorized — openai"));
        ChatModel fb2 = errorModel(new RuntimeException("401 Unauthorized — dashscope"));
        var helper = helper(primary, List.of(
                new FallbackEntry("openai", fb1),
                new FallbackEntry("dashscope", fb2)), "kimi");

        var result = helper.streamCall(primary, smallPrompt(), "conv-c3", "reasoning");

        assertNotNull(result, "result must not be null even when whole chain fails");
        assertEquals(NodeStreamingChatHelper.ErrorType.AUTH_ERROR, result.errorType(),
                "last seen AUTH_ERROR must propagate so callers can surface a real error");
        // Each rung tried exactly once
        verify(primary, times(1)).stream(any(Prompt.class));
        verify(fb1, times(1)).stream(any(Prompt.class));
        verify(fb2, times(1)).stream(any(Prompt.class));
        // Health tracker should have recorded a failure against every fallback provider
        var snap = healthTracker.snapshot();
        assertTrue(snap.get("openai").consecutiveFailures() >= 1, "openai failure must be recorded");
        assertTrue(snap.get("dashscope").consecutiveFailures() >= 1, "dashscope failure must be recorded");
    }

    // ============================================================
    // C4 regression: BILLING still falls back unchanged
    // ============================================================

    @Test
    @DisplayName("C4 (regression): primary BILLING still triggers fallback (unchanged from RFC-009 P3.2)")
    void billingStillFallsBack() {
        ChatModel primary = errorModel(new RuntimeException("402 Payment Required: insufficient_quota"));
        ChatModel fallback = successModel("recovered via fallback");
        var helper = helper(primary, List.of(new FallbackEntry("dashscope", fallback)), "openai");

        var result = helper.streamCall(primary, smallPrompt(), "conv-c4", "reasoning");

        assertEquals("recovered via fallback", result.text());
        verify(primary, times(1)).stream(any(Prompt.class));
        verify(fallback, times(1)).stream(any(Prompt.class));
    }

    // ============================================================
    // C5 regression: RATE_LIMIT (429) must fall back, not surface
    // ============================================================

    /**
     * Prior to this fix a rate-limited primary exhausted its 2 same-model
     * retries and then {@code return}ed the 429 error result directly,
     * skipping the fallback chain entirely — the 429 surfaced as the
     * conversation's answer even though other providers were healthy.
     * After the fix RATE_LIMIT breaks out to the chain walker, mirroring
     * AUTH_ERROR / BILLING.
     *
     * <p>Note: this test waits out two real retry backoffs (~3s + ~6s) on
     * the primary before the hand-off, so it runs for ~10s by design.</p>
     */
    @Test
    @DisplayName("C5 (regression): primary RATE_LIMIT (429) hands off to the fallback chain")
    void rateLimitFallsBack() {
        ChatModel primary = errorModel(new RuntimeException("429 Too Many Requests"));
        ChatModel fallback = successModel("recovered after rate limit");
        var helper = helper(primary, List.of(new FallbackEntry("dashscope", fallback)), "zhipu-cn");

        var result = helper.streamCall(primary, smallPrompt(), "conv-c5", "reasoning");

        assertEquals("recovered after rate limit", result.text(),
                "a rate-limited primary must fail over instead of surfacing the 429");
        verify(primary, atLeast(2)).stream(any(Prompt.class));
        verify(fallback, times(1)).stream(any(Prompt.class));
    }

    // ============================================================
    // Bonus: confirm no infinite loop / regression on success path
    // ============================================================

    @Test
    @DisplayName("Bonus: primary success path is unaffected — no fallback call")
    void primarySuccessSkipsFallback() {
        ChatModel primary = successModel("primary works fine");
        AtomicInteger fallbackCalls = new AtomicInteger();
        ChatModel fallback = mock(ChatModel.class);
        when(fallback.stream(any(Prompt.class))).thenAnswer(inv -> {
            fallbackCalls.incrementAndGet();
            return Flux.just((ChatResponse) null);
        });
        var helper = helper(primary, List.of(new FallbackEntry("dashscope", fallback)), "openai");

        var result = helper.streamCall(primary, smallPrompt(), "conv-bonus", "reasoning");

        assertEquals("primary works fine", result.text());
        assertEquals(0, fallbackCalls.get(), "primary success must not touch the fallback chain");
    }
}
