package vip.mate.llm.cache;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies reflective extraction of cache / reasoning token counters from the
 * provider-native usage shapes (Anthropic top-level accessors, OpenAI-compatible
 * and DashScope nested detail records).
 */
class CacheUsageExtractorTest {

    /** Minimal Usage stub whose native payload drives the extraction. */
    private record StubUsage(Object nativeUsage) implements Usage {
        @Override public Integer getPromptTokens() { return 0; }
        @Override public Integer getCompletionTokens() { return 0; }
        @Override public Object getNativeUsage() { return nativeUsage; }
    }

    /** Anthropic-style native usage: top-level cache accessors. */
    private record AnthropicStyleUsage(Integer inputTokens, Integer outputTokens,
                                       Integer cacheCreationInputTokens,
                                       Integer cacheReadInputTokens) {}

    /** OpenAI-style native usage: nested prompt/completion detail records. */
    private record OpenAiPromptDetails(Integer audioTokens, Integer cachedTokens) {}
    private record OpenAiCompletionDetails(Integer reasoningTokens, Integer audioTokens) {}
    private record OpenAiStyleUsage(Integer promptTokens, Integer completionTokens,
                                    OpenAiPromptDetails promptTokensDetails,
                                    OpenAiCompletionDetails completionTokenDetails) {}

    /** DashScope-style native usage: promptTokenDetailed.cachedTokens. */
    private record DashScopePromptDetailed(Integer cachedTokens) {}
    private record DashScopeStyleUsage(Integer inputTokens, Integer outputTokens,
                                       DashScopePromptDetailed promptTokenDetailed) {}

    @Test
    void anthropicTopLevelCacheFields() {
        var usage = new StubUsage(new AnthropicStyleUsage(100, 50, 2000, 66000));
        var tokens = CacheUsageExtractor.extract(usage);
        assertEquals(66000, tokens.cacheReadTokens());
        assertEquals(2000, tokens.cacheWriteTokens());
        assertEquals(0, tokens.reasoningTokens());
    }

    @Test
    void openAiNestedCachedAndReasoningTokens() {
        var usage = new StubUsage(new OpenAiStyleUsage(5000, 800,
                new OpenAiPromptDetails(0, 4200),
                new OpenAiCompletionDetails(300, 0)));
        var tokens = CacheUsageExtractor.extract(usage);
        assertEquals(4200, tokens.cacheReadTokens());
        assertEquals(0, tokens.cacheWriteTokens());
        assertEquals(300, tokens.reasoningTokens());
    }

    @Test
    void dashScopeNestedCachedTokens() {
        var usage = new StubUsage(new DashScopeStyleUsage(9000, 400,
                new DashScopePromptDetailed(7500)));
        var tokens = CacheUsageExtractor.extract(usage);
        assertEquals(7500, tokens.cacheReadTokens());
        assertEquals(0, tokens.cacheWriteTokens());
        assertEquals(0, tokens.reasoningTokens());
    }

    @Test
    void unknownProviderYieldsEmpty() {
        var tokens = CacheUsageExtractor.extract(new StubUsage(new Object()));
        assertTrue(tokens.isEmpty());
    }

    @Test
    void nullDetailRecordsYieldZeroNotError() {
        var usage = new StubUsage(new OpenAiStyleUsage(5000, 800, null, null));
        var tokens = CacheUsageExtractor.extract(usage);
        assertTrue(tokens.isEmpty());
    }
}
