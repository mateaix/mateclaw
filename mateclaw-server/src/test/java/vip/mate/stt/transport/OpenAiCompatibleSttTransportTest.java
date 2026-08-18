package vip.mate.stt.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #76: pure-logic coverage for the path resolver + base URL normalization.
 * Network-side behaviour is exercised by the existing {@code SttServiceTest}
 * via Mockito stubs on the provider, so this class deliberately stays small
 * and unit-only — no Spring, no HTTP.
 */
class OpenAiCompatibleSttTransportTest {

    @Test
    @DisplayName("Base URL with no /vN suffix appends /v1/audio/transcriptions")
    void resolveAudioPathDefault() {
        assertEquals("/v1/audio/transcriptions",
                OpenAiCompatibleSttTransport.resolveAudioPath("https://api.openai.com"));
        assertEquals("/v1/audio/transcriptions",
                OpenAiCompatibleSttTransport.resolveAudioPath("http://10.0.0.5:9999"));
    }

    @Test
    @DisplayName("Base URL ending in /v1 (lmstudio-style) appends only /audio/transcriptions")
    void resolveAudioPathSkipsDoubledVersion() {
        assertEquals("/audio/transcriptions",
                OpenAiCompatibleSttTransport.resolveAudioPath("http://localhost:1234/v1"));
        assertEquals("/audio/transcriptions",
                OpenAiCompatibleSttTransport.resolveAudioPath("https://api.siliconflow.cn/v1"));
        assertEquals("/audio/transcriptions",
                OpenAiCompatibleSttTransport.resolveAudioPath("http://127.0.0.1:9999/v3"));
    }

    @Test
    @DisplayName("Mid-path /v1 segment is NOT treated as suffix (only end-of-string match)")
    void resolveAudioPathRejectsMidPath() {
        assertEquals("/v1/audio/transcriptions",
                OpenAiCompatibleSttTransport.resolveAudioPath("https://example.com/v1/foo"));
    }

    @Test
    @DisplayName("Base URL trims trailing slash; null/blank → null sentinel")
    void normalizeBaseUrl() {
        assertEquals("https://api.openai.com",
                OpenAiCompatibleSttTransport.normalizeBaseUrl("https://api.openai.com/"));
        assertEquals("https://api.openai.com",
                OpenAiCompatibleSttTransport.normalizeBaseUrl("  https://api.openai.com  "));
        assertNull(OpenAiCompatibleSttTransport.normalizeBaseUrl(""));
        assertNull(OpenAiCompatibleSttTransport.normalizeBaseUrl("   "));
        assertNull(OpenAiCompatibleSttTransport.normalizeBaseUrl(null));
    }

    @Test
    @DisplayName("apiMode is the stable family id every profile selects on")
    void apiModeIsStable() {
        OpenAiCompatibleSttTransport t = new OpenAiCompatibleSttTransport(null);
        assertEquals("openai_compatible_audio", t.apiMode());
        assertEquals(OpenAiCompatibleSttTransport.API_MODE, t.apiMode());
    }

    @Test
    @DisplayName("extractErrorMessage reads OpenAI-nested, FastAPI-detail and plain-message shapes")
    void extractErrorMessageShapes() {
        OpenAiCompatibleSttTransport t = new OpenAiCompatibleSttTransport(new ObjectMapper());
        // OpenAI / Groq / Ollama / LM Studio shape.
        assertEquals("model 'whisper-large-v2' not found",
                t.extractErrorMessage("{\"error\":{\"message\":\"model 'whisper-large-v2' not found\",\"type\":\"not_found_error\"}}"));
        // FastAPI-based self-hosted servers.
        assertEquals("Not Found", t.extractErrorMessage("{\"detail\":\"Not Found\"}"));
        // Plain message shims.
        assertEquals("boom", t.extractErrorMessage("{\"message\":\"boom\"}"));
        // Non-JSON / empty → "" so the caller falls back to a body snippet.
        assertEquals("", t.extractErrorMessage("<html>gateway error</html>"));
        assertEquals("", t.extractErrorMessage("{}"));
        assertEquals("", t.extractErrorMessage(null));
        assertEquals("", t.extractErrorMessage("  "));
    }
}
