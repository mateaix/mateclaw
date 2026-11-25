package vip.mate.llm.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.anthropic.models.messages.Usage;
import com.openai.models.completions.CompletionUsage;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

import java.util.Optional;

class SdkCacheUsageExtractorTest {
    @Test
    void openAiSdkOptionalDetailsRetainCacheAndReasoningCounters() {
        var nativeUsage =
                CompletionUsage.builder()
                        .promptTokens(100)
                        .completionTokens(20)
                        .totalTokens(120)
                        .promptTokensDetails(
                                CompletionUsage.PromptTokensDetails.builder()
                                        .cachedTokens(80)
                                        .build())
                        .completionTokensDetails(
                                CompletionUsage.CompletionTokensDetails.builder()
                                        .reasoningTokens(12)
                                        .build())
                        .build();
        assertEquals(
                new CacheUsageExtractor.CacheTokens(80, 0, 12),
                CacheUsageExtractor.extract(new DefaultUsage(0, 0, 0, nativeUsage)));
    }

    @Test
    void anthropicSdkOptionalCountersRetainCacheUsage() {
        var nativeUsage =
                Usage.builder()
                        .cacheCreation(Optional.empty())
                        .inferenceGeo(Optional.empty())
                        .outputTokensDetails(Optional.empty())
                        .serverToolUse(Optional.empty())
                        .serviceTier(Optional.empty())
                        .speed(Optional.empty())
                        .inputTokens(10)
                        .outputTokens(20)
                        .cacheReadInputTokens(80)
                        .cacheCreationInputTokens(30)
                        .build();
        assertEquals(
                new CacheUsageExtractor.CacheTokens(80, 30, 0),
                CacheUsageExtractor.extract(new DefaultUsage(0, 0, 0, nativeUsage)));
    }

    @Test
    void omittedSdkCountersRemainZero() {
        var nativeUsage =
                CompletionUsage.builder()
                        .promptTokens(100)
                        .completionTokens(20)
                        .totalTokens(120)
                        .build();
        assertEquals(
                CacheUsageExtractor.CacheTokens.EMPTY,
                CacheUsageExtractor.extract(new DefaultUsage(0, 0, 0, nativeUsage)));
    }
}
