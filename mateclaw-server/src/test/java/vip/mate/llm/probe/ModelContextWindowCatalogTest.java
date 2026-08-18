package vip.mate.llm.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ModelContextWindowCatalog} — prefix matching, vendor
 * segments, and the "unknown stays unknown" contract.
 */
class ModelContextWindowCatalogTest {

    @Test
    @DisplayName("longest prefix wins — v4 does not inherit the v3 window")
    void longestPrefixWins() {
        assertEquals(1_000_000, ModelContextWindowCatalog.lookup("deepseek-v4-flash"));
        assertEquals(1_000_000, ModelContextWindowCatalog.lookup("deepseek-v4-pro"));
        assertEquals(128_000, ModelContextWindowCatalog.lookup("deepseek-v3-2-251201"));
        assertEquals(128_000, ModelContextWindowCatalog.lookup("deepseek-chat"));
    }

    @Test
    @DisplayName("matching is case-insensitive and ignores the vendor segment")
    void vendorSegmentIsStripped() {
        assertEquals(200_000, ModelContextWindowCatalog.lookup("anthropic/claude-opus-4-8"));
        assertEquals(1_048_576, ModelContextWindowCatalog.lookup("google/gemini-2.5-flash:free"));
        assertEquals(128_000, ModelContextWindowCatalog.lookup("Pro/deepseek-ai/DeepSeek-V3"));
        assertEquals(1_048_576, ModelContextWindowCatalog.lookup("meta-llama/llama-4-maverick"));
    }

    @Test
    @DisplayName("same-family models with different windows do not bleed into each other")
    void familyVariantsStaySeparate() {
        // Coder: plus is 1M, next is 256k native.
        assertEquals(1_000_000, ModelContextWindowCatalog.lookup("qwen3-coder-plus"));
        assertEquals(262_144, ModelContextWindowCatalog.lookup("qwen3-coder-next"));
        // GLM-5 line is 200k; only 5.2 lifted it to 1M.
        assertEquals(204_800, ModelContextWindowCatalog.lookup("glm-5"));
        assertEquals(204_800, ModelContextWindowCatalog.lookup("glm-5.1"));
        assertEquals(204_800, ModelContextWindowCatalog.lookup("glm-5-turbo"));
        assertEquals(1_000_000, ModelContextWindowCatalog.lookup("glm-5.2"));
        assertEquals(204_800, ModelContextWindowCatalog.lookup("glm-5v-turbo"));
        // Vendor alias for the K2.7 code model, including its -highspeed tier.
        assertEquals(262_144, ModelContextWindowCatalog.lookup("kimi-for-coding"));
        assertEquals(262_144, ModelContextWindowCatalog.lookup("kimi-for-coding-highspeed"));
        // Max line stays at 256k while plus/flash run 1M.
        assertEquals(262_144, ModelContextWindowCatalog.lookup("qwen3-max-2026-01-23"));
        assertEquals(1_000_000, ModelContextWindowCatalog.lookup("qwen3.6-plus-2026-04-02"));
    }

    @Test
    @DisplayName("dotted and dashed ids of the same model resolve alike")
    void dottedAndDashedIdsAgree() {
        assertEquals(262_144, ModelContextWindowCatalog.lookup("doubao-seed-2.0-pro"));
        assertEquals(262_144, ModelContextWindowCatalog.lookup("doubao-seed-2-0-pro-260215"));
        assertEquals(204_800, ModelContextWindowCatalog.lookup("MiniMax-M2.7-highspeed"));
        assertEquals(204_800, ModelContextWindowCatalog.lookup("minimax-m2.7"));
        assertEquals(1_000_000, ModelContextWindowCatalog.lookup("minimax-m3"));
    }

    @Test
    @DisplayName("models outside the table return null so the caller keeps its default")
    void unknownModelsReturnNull() {
        assertNull(ModelContextWindowCatalog.lookup("acme-llm-1"));
        assertNull(ModelContextWindowCatalog.lookup("vendor/unknown-model"));
        assertNull(ModelContextWindowCatalog.lookup(""));
        assertNull(ModelContextWindowCatalog.lookup(null));
    }
}
