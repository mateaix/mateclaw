package vip.mate.llm.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link ModelDiscoveryService#isEmbeddingModelId(String)} —
 * the predicate that keeps embedding models out of the chat-style probe and
 * out of the chat-discovery "new models" suggestion bucket.
 */
class ModelDiscoveryEmbeddingDetectionTest {

    @Test
    void dashscopeEmbeddingVariants_recognised() {
        assertTrue(ModelDiscoveryService.isEmbeddingModelId("text-embedding-v1"));
        assertTrue(ModelDiscoveryService.isEmbeddingModelId("text-embedding-v3"));
        assertTrue(ModelDiscoveryService.isEmbeddingModelId("text-embedding-v4"));
    }

    @Test
    void openAiEmbeddingVariants_recognised() {
        assertTrue(ModelDiscoveryService.isEmbeddingModelId("text-embedding-ada-002"));
        assertTrue(ModelDiscoveryService.isEmbeddingModelId("text-embedding-3-small"));
        assertTrue(ModelDiscoveryService.isEmbeddingModelId("text-embedding-3-large"));
    }

    @Test
    void genericEmbeddingPrefix_recognised() {
        // Some providers ship just "embedding-..." without the "text-" prefix.
        assertTrue(ModelDiscoveryService.isEmbeddingModelId("embedding-001"));
        assertTrue(ModelDiscoveryService.isEmbeddingModelId("embedding-large"));
    }

    @Test
    void chatModels_notRecognisedAsEmbedding() {
        assertFalse(ModelDiscoveryService.isEmbeddingModelId("qwen-plus"));
        assertFalse(ModelDiscoveryService.isEmbeddingModelId("gpt-4o"));
        assertFalse(ModelDiscoveryService.isEmbeddingModelId("claude-sonnet-4-6"));
        assertFalse(ModelDiscoveryService.isEmbeddingModelId("deepseek-r1"));
    }

    @Test
    void detectionIsCaseInsensitive() {
        assertTrue(ModelDiscoveryService.isEmbeddingModelId("Text-Embedding-V4"));
        assertTrue(ModelDiscoveryService.isEmbeddingModelId("TEXT-EMBEDDING-3-SMALL"));
    }

    @Test
    void nullAndBlank_notEmbedding() {
        assertFalse(ModelDiscoveryService.isEmbeddingModelId(null));
        assertFalse(ModelDiscoveryService.isEmbeddingModelId(""));
    }

    @Test
    void stringContainingEmbeddingMidway_notDetected() {
        // Only prefix-anchored matches count; arbitrary mentions don't.
        assertFalse(ModelDiscoveryService.isEmbeddingModelId("qwen-embedding-experimental"));
    }
}
