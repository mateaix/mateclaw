package vip.mate.stt.transport;

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
}
