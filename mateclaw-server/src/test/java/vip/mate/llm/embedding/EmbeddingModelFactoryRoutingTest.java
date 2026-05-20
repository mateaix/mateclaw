package vip.mate.llm.embedding;

import org.junit.jupiter.api.Test;
import vip.mate.llm.model.EmbeddingProtocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Routing tests for {@link EmbeddingModelFactory#resolveEmbeddingProtocol(String)}.
 * The dashscope-compat regression (#166) lives in the OpenAIChatModel branch.
 */
class EmbeddingModelFactoryRoutingTest {

    @Test
    void dashscopeChatModel_routesToNativeEmbedding() {
        assertEquals(EmbeddingProtocol.DASHSCOPE_EMBEDDING,
                EmbeddingModelFactory.resolveEmbeddingProtocol("DashScopeChatModel"));
    }

    @Test
    void openaiChatModel_routesToOpenAiCompat() {
        // dashscope-compat carries chatModel='OpenAIChatModel' — must NOT take the native path
        assertEquals(EmbeddingProtocol.OPENAI_EMBEDDING,
                EmbeddingModelFactory.resolveEmbeddingProtocol("OpenAIChatModel"));
    }

    @Test
    void anthropicChatModel_routesToOpenAiCompat() {
        assertEquals(EmbeddingProtocol.OPENAI_EMBEDDING,
                EmbeddingModelFactory.resolveEmbeddingProtocol("AnthropicChatModel"));
    }

    @Test
    void nullChatModel_routesToOpenAiCompat() {
        assertEquals(EmbeddingProtocol.OPENAI_EMBEDDING,
                EmbeddingModelFactory.resolveEmbeddingProtocol(null));
    }

    @Test
    void blankChatModel_routesToOpenAiCompat() {
        assertEquals(EmbeddingProtocol.OPENAI_EMBEDDING,
                EmbeddingModelFactory.resolveEmbeddingProtocol(""));
        assertEquals(EmbeddingProtocol.OPENAI_EMBEDDING,
                EmbeddingModelFactory.resolveEmbeddingProtocol("   "));
    }

    @Test
    void chatModelMatchIsCaseInsensitiveAndTrimmed() {
        // Mirror ModelProtocol.fromChatModel's normalization: equalsIgnoreCase + trim.
        assertEquals(EmbeddingProtocol.DASHSCOPE_EMBEDDING,
                EmbeddingModelFactory.resolveEmbeddingProtocol("dashscopechatmodel"));
        assertEquals(EmbeddingProtocol.DASHSCOPE_EMBEDDING,
                EmbeddingModelFactory.resolveEmbeddingProtocol("  DashScopeChatModel  "));
    }

    @Test
    void unknownChatModel_fallsBackToOpenAiCompat() {
        // Future / custom chatModel strings default to OpenAI-compatible (safe default).
        assertEquals(EmbeddingProtocol.OPENAI_EMBEDDING,
                EmbeddingModelFactory.resolveEmbeddingProtocol("CustomGatewayChatModel"));
    }
}
