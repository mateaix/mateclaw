package vip.mate.llm.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContextLimitErrorParser} — the reconciliation
 * fallback that learns the model's context window from rejection text.
 */
class ContextLimitErrorParserTest {

    @Test
    @DisplayName("vLLM max_model_len rejection yields the limit, not the requested size")
    void vllmMaxModelLen() {
        OptionalInt limit = ContextLimitErrorParser.extractLimit(
                "This request would exceed the max_model_len 32768 (requested 51234 tokens)");
        assertEquals(OptionalInt.of(32768), limit);
    }

    @Test
    @DisplayName("OpenAI-style maximum context length message")
    void openAiStyle() {
        OptionalInt limit = ContextLimitErrorParser.extractLimit(
                "This model's maximum context length is 4096 tokens. However, your messages resulted in 9012 tokens.");
        assertEquals(OptionalInt.of(4096), limit);
    }

    @Test
    @DisplayName("vLLM alternate wording: maximum model length")
    void vllmAlternate() {
        OptionalInt limit = ContextLimitErrorParser.extractLimit(
                "Input prompt (40000 tokens) is longer than the maximum model length of 16384");
        assertEquals(OptionalInt.of(16384), limit);
    }

    @Test
    @DisplayName("num_ctx wording in rejection text")
    void numCtx() {
        OptionalInt limit = ContextLimitErrorParser.extractLimit(
                "prompt exceeds server window (num_ctx 8192)");
        assertEquals(OptionalInt.of(8192), limit);
    }

    @Test
    @DisplayName("no pattern → empty")
    void unrelatedMessage() {
        assertTrue(ContextLimitErrorParser.extractLimit("connection refused").isEmpty());
        assertTrue(ContextLimitErrorParser.extractLimit("").isEmpty());
        assertTrue(ContextLimitErrorParser.extractLimit(null).isEmpty());
    }

    @Test
    @DisplayName("implausible numbers are rejected")
    void implausibleNumbers() {
        // Below one model page — likely a mis-parse.
        assertTrue(ContextLimitErrorParser.extractLimit("maximum context length is 100 tokens").isEmpty());
    }
}
