package vip.mate.llm.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parse-level tests for {@link OllamaContextProbe} against captured
 * {@code /api/show} response shapes — no HTTP involved.
 */
class OllamaContextProbeParseTest {

    @Test
    @DisplayName("num_ctx from modelfile parameters wins over architecture context_length")
    void numCtxWins() {
        String body = """
                {"parameters": "num_ctx                        8192\\nstop \\"<|im_end|>\\"",
                 "model_info": {"qwen2.context_length": 32768, "qwen2.embedding_length": 3584}}
                """;
        assertEquals(OptionalInt.of(8192), OllamaContextProbe.parseShowResponse(body));
    }

    @Test
    @DisplayName("architecture context_length used when no num_ctx is set")
    void contextLengthFallback() {
        String body = """
                {"parameters": "stop \\"<|im_end|>\\"",
                 "model_info": {"llama.context_length": 131072, "llama.block_count": 32}}
                """;
        assertEquals(OptionalInt.of(131072), OllamaContextProbe.parseShowResponse(body));
    }

    @Test
    @DisplayName("no usable field → empty")
    void noUsableField() {
        assertTrue(OllamaContextProbe.parseShowResponse("{\"model_info\": {}}").isEmpty());
        assertTrue(OllamaContextProbe.parseShowResponse("not json").isEmpty());
        assertTrue(OllamaContextProbe.parseShowResponse(null).isEmpty());
    }

    @Test
    @DisplayName("base URL normalization strips trailing slash and /v1, defaults when blank")
    void baseUrlNormalization() {
        assertEquals("http://127.0.0.1:11434", OllamaContextProbe.normalizeBaseUrl(null));
        assertEquals("http://127.0.0.1:11434", OllamaContextProbe.normalizeBaseUrl(" "));
        assertEquals("http://192.168.1.5:11434", OllamaContextProbe.normalizeBaseUrl("http://192.168.1.5:11434/v1"));
        assertEquals("http://192.168.1.5:11434", OllamaContextProbe.normalizeBaseUrl("http://192.168.1.5:11434/"));
    }
}
