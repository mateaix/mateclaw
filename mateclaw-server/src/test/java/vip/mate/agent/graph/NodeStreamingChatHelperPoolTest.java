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
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.failover.AvailableProviderPool.RemovalSource;
import vip.mate.llm.failover.FallbackEntry;
import vip.mate.llm.failover.ProviderHealthProperties;
import vip.mate.llm.failover.ProviderHealthTracker;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RFC-009 Phase 4 — verifies the three pool hooks wired into
 * {@link NodeStreamingChatHelper}:
 * <ol>
 *   <li>Primary short-circuit when its provider id is not in the pool —
 *       primary is never even called, fallback runs first.</li>
 *   <li>Walker head filter — out-of-pool fallback entries are skipped.</li>
 *   <li>HARD error → {@code pool.remove}; SOFT error → pool unchanged.</li>
 * </ol>
 *
 * <p>Pool state must remain consistent across these three behaviors so a
 * single misconfigured provider can't pollute every conversation turn.</p>
 */
class NodeStreamingChatHelperPoolTest {

    private ChatStreamTracker streamTracker;
    private ProviderHealthTracker healthTracker;
    private AvailableProviderPool pool;

    @BeforeEach
    void setUp() {
        streamTracker = mock(ChatStreamTracker.class);
        when(streamTracker.isStopRequested(any())).thenReturn(false);
        healthTracker = new ProviderHealthTracker(new ProviderHealthProperties());
        pool = new AvailableProviderPool();
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

    private static ChatModel errorModel(Throwable err) {
        ChatModel m = mock(ChatModel.class);
        when(m.stream(any(Prompt.class))).thenReturn(Flux.error(err));
        return m;
    }

    /** Stream a single chunk with empty text and no tool calls — triggers EMPTY_RESPONSE (SOFT). */
    private static ChatModel emptyResponseModel() {
        ChatModel m = mock(ChatModel.class);
        Generation gen = new Generation(new AssistantMessage(""), ChatGenerationMetadata.NULL);
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResults()).thenReturn(List.of(gen));
        when(resp.getResult()).thenReturn(gen);
        when(resp.getMetadata()).thenReturn(null);
        when(m.stream(any(Prompt.class))).thenReturn(Flux.just(resp));
        return m;
    }

    private NodeStreamingChatHelper helper(List<FallbackEntry> chain, String primary) {
        return new NodeStreamingChatHelper(streamTracker, chain, null, healthTracker, primary, pool);
    }

    private static Prompt smallPrompt() {
        return new Prompt(List.of(new UserMessage("hi")));
    }

    // ============================================================
    // Hook 1: primary out-of-pool short-circuits the retry loop
    // ============================================================

    @Test
    @DisplayName("Primary not in pool: skipped without being called, fallback wins")
    void primaryOutOfPoolShortCircuits() {
        // openai is HARD-removed from pool before the call
        pool.add("dashscope");
        pool.remove("openai", RemovalSource.AUTH_ERROR, "stale 401");

        ChatModel primary = successModel("primary should never be called");
        ChatModel fallback = successModel("fallback wins");
        var helper = helper(List.of(new FallbackEntry("dashscope", fallback)), "openai");

        var result = helper.streamCall(primary, smallPrompt(), "conv-h1", "reasoning");

        assertEquals("fallback wins", result.text());
        verify(primary, never()).stream(any(Prompt.class));
        verify(fallback, times(1)).stream(any(Prompt.class));
    }

    // ============================================================
    // Hook 2: walker skips out-of-pool fallback entries
    // ============================================================

    @Test
    @DisplayName("Walker skips out-of-pool fallback and lands on the next eligible one")
    void walkerSkipsOutOfPoolFallback() {
        pool.add("openai");                                     // primary
        pool.remove("anthropic", RemovalSource.BILLING, "402"); // first fallback dead
        pool.add("dashscope");                                  // second fallback alive

        // Use AUTH_ERROR (HARD) on primary — triggers the immediate break-to-walker
        // path. Picking SERVER_ERROR would burn 5 retries (~110s) and then exit
        // without ever hitting the walker, which is unrelated to the property
        // under test here.
        ChatModel primary = errorModel(new RuntimeException("401 Unauthorized"));
        ChatModel fbAnthropic = successModel("should be skipped");
        ChatModel fbDashscope = successModel("dashscope wins");
        var helper = helper(List.of(
                new FallbackEntry("anthropic", fbAnthropic),
                new FallbackEntry("dashscope", fbDashscope)), "openai");

        var result = helper.streamCall(primary, smallPrompt(), "conv-h2", "reasoning");

        assertEquals("dashscope wins", result.text());
        verify(fbAnthropic, never()).stream(any(Prompt.class));
        verify(fbDashscope, times(1)).stream(any(Prompt.class));
    }

    // ============================================================
    // Hook 3a: primary HARD error evicts from pool
    // ============================================================

    @Test
    @DisplayName("Primary AUTH_ERROR HARD-removes openai from pool with AUTH_ERROR source")
    void primaryAuthErrorEvictsFromPool() {
        pool.add("openai");
        pool.add("dashscope");

        ChatModel primary = errorModel(new RuntimeException("401 Unauthorized: bad key"));
        ChatModel fallback = successModel("recovered");
        var helper = helper(List.of(new FallbackEntry("dashscope", fallback)), "openai");

        helper.streamCall(primary, smallPrompt(), "conv-h3a", "reasoning");

        assertFalse(pool.contains("openai"), "openai must be removed from pool after AUTH_ERROR");
        var reason = pool.snapshot().get("openai");
        assertNotNull(reason);
        assertEquals(RemovalSource.AUTH_ERROR, reason.source());
        assertTrue(pool.contains("dashscope"), "successful fallback stays in pool");
    }

    @Test
    @DisplayName("Primary BILLING HARD-removes with BILLING source (distinct from AUTH)")
    void primaryBillingEvictsWithBillingSource() {
        pool.add("openai");
        pool.add("dashscope");

        ChatModel primary = errorModel(new RuntimeException("402 Payment Required: insufficient_quota"));
        ChatModel fallback = successModel("ok");
        var helper = helper(List.of(new FallbackEntry("dashscope", fallback)), "openai");

        helper.streamCall(primary, smallPrompt(), "conv-h3b", "reasoning");

        assertFalse(pool.contains("openai"));
        assertEquals(RemovalSource.BILLING, pool.snapshot().get("openai").source());
    }

    @Test
    @DisplayName("Primary MODEL_NOT_FOUND keeps the provider in the pool (model-scoped, not provider-wide)")
    void primaryModelNotFoundKeepsProviderInPool() {
        pool.add("openai");
        pool.add("dashscope");

        // One model id is rejected — the provider's other models are still fine,
        // so the provider must stay usable for them.
        ChatModel primary = errorModel(new RuntimeException("404 model_not_found: gpt-99"));
        ChatModel fallback = successModel("ok");
        var helper = helper(List.of(new FallbackEntry("dashscope", fallback)), "openai");

        var result = helper.streamCall(primary, smallPrompt(), "conv-h3c", "reasoning");

        assertEquals("ok", result.text(), "request still succeeds via the fallback chain");
        assertTrue(pool.contains("openai"),
                "MODEL_NOT_FOUND rejects one model id — the provider's sibling models stay usable");
        assertNull(pool.snapshot().get("openai"),
                "a model-scoped error must not record a provider removal reason");
        assertNull(healthTracker.snapshot().get("openai"),
                "MODEL_NOT_FOUND must not nudge the provider toward cooldown");
    }

    // ============================================================
    // Hook 3b: SOFT errors do NOT evict from pool
    // ============================================================

    @Test
    @DisplayName("Primary EMPTY_RESPONSE (SOFT) keeps provider in pool, only records failure")
    void primarySoftErrorKeepsInPool() {
        pool.add("openai");
        pool.add("dashscope");

        // EMPTY_RESPONSE is SOFT and breaks straight to fallback (no 5x retry)
        // — keeps the test fast while still exercising the SOFT path.
        ChatModel primary = emptyResponseModel();
        ChatModel fallback = successModel("ok");
        var helper = helper(List.of(new FallbackEntry("dashscope", fallback)), "openai");

        helper.streamCall(primary, smallPrompt(), "conv-h3d", "reasoning");

        assertTrue(pool.contains("openai"),
                "SOFT errors must NOT evict — health tracker cooldown handles transient blips");
        assertTrue(healthTracker.snapshot().get("openai").consecutiveFailures() > 0,
                "SOFT failure must still be recorded by the health tracker");
    }

    // ============================================================
    // Hook 3c: fallback HARD errors also evict
    // ============================================================

    @Test
    @DisplayName("Fallback AUTH_ERROR evicts the fallback provider and walker continues")
    void fallbackHardErrorEvictsFallback() {
        pool.add("openai");
        pool.add("anthropic");
        pool.add("dashscope");

        // Use AUTH on primary so we reach the walker without burning 5 retries.
        // The behavior under test is fallback eviction, not the primary path.
        ChatModel primary = errorModel(new RuntimeException("401 Unauthorized: openai key"));
        ChatModel fbBad = errorModel(new RuntimeException("401 Unauthorized: anthropic key"));
        ChatModel fbGood = successModel("dashscope ok");
        var helper = helper(List.of(
                new FallbackEntry("anthropic", fbBad),
                new FallbackEntry("dashscope", fbGood)), "openai");

        var result = helper.streamCall(primary, smallPrompt(), "conv-h3e", "reasoning");

        assertEquals("dashscope ok", result.text());
        assertFalse(pool.contains("anthropic"), "fallback that failed AUTH must be evicted");
        assertEquals(RemovalSource.AUTH_ERROR, pool.snapshot().get("anthropic").source());
        assertTrue(pool.contains("dashscope"));
    }

    // ============================================================
    // Sanity: fail-open mode (null pool) — old call sites unchanged
    // ============================================================

    @Test
    @DisplayName("Null pool: helper behaves as before (no NPE, no skipping)")
    void nullPoolFailOpen() {
        ChatModel primary = errorModel(new RuntimeException("401 Unauthorized"));
        ChatModel fallback = successModel("ok");
        // 5-arg constructor — no pool wired
        var helper = new NodeStreamingChatHelper(streamTracker,
                List.of(new FallbackEntry("dashscope", fallback)), null, healthTracker, "openai");

        var result = helper.streamCall(primary, smallPrompt(), "conv-failopen", "reasoning");

        assertEquals("ok", result.text());
        // No pool to inspect — just confirm we didn't crash and fallback ran.
        verify(primary, times(1)).stream(any(Prompt.class));
        verify(fallback, times(1)).stream(any(Prompt.class));
    }
}
