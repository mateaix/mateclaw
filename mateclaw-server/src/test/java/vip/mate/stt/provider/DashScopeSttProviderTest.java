package vip.mate.stt.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DashScopeSttProvider}'s wire-format helpers. The
 * HTTP round trip itself isn't exercised (no mock server); request-body
 * construction, response/transcript parsing, and error extraction are the
 * parts with encoding rules worth pinning:
 *
 * <ul>
 *   <li>The audio must ride as a MIME-qualified data URI. Qwen3-ASR does not
 *       use the separate {@code format} field supported by other Qwen audio models.</li>
 *   <li>{@code asr_options} must be omitted entirely when no language hint
 *       is supplied, so the service auto-detects.</li>
 *   <li>Transcript extraction must tolerate both plain-string and
 *       content-part-array response shapes.</li>
 * </ul>
 */
class DashScopeSttProviderTest {

    private DashScopeSttProvider provider;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        provider = new DashScopeSttProvider(null, mapper);
    }

    @Test
    @DisplayName("buildRequestBody serialises model and MIME-qualified base64 audio")
    void buildRequestBody_coreShape() throws Exception {
        byte[] audio = "fake-wav-bytes".getBytes(StandardCharsets.UTF_8);
        String json = provider.buildRequestBody("qwen3-asr-flash", audio, "audio/wav", null);
        JsonNode node = mapper.readTree(json);

        assertEquals("qwen3-asr-flash", node.path("model").asText());
        assertEquals(false, node.path("stream").asBoolean(true));

        JsonNode content = node.path("messages").path(0).path("content").path(0);
        assertEquals("user", node.path("messages").path(0).path("role").asText());
        assertEquals("input_audio", content.path("type").asText());
        assertTrue(content.path("input_audio").path("format").isMissingNode());

        String data = content.path("input_audio").path("data").asText();
        assertTrue(data.startsWith("data:audio/wav;base64,"),
                "audio must carry its real MIME type in the data URI");
        assertEquals(Base64.getEncoder().encodeToString(audio),
                data.substring("data:audio/wav;base64,".length()));
    }

    @Test
    @DisplayName("buildRequestBody adds asr_options.language with locale stripped")
    void buildRequestBody_languageHint() throws Exception {
        String json = provider.buildRequestBody("qwen3-asr-flash", new byte[]{1}, "audio/wav", "zh-CN");
        JsonNode node = mapper.readTree(json);
        assertEquals("zh", node.path("asr_options").path("language").asText());
    }

    @Test
    @DisplayName("buildRequestBody omits asr_options when language is null — auto-detect")
    void buildRequestBody_omitsAsrOptionsWhenNoLanguage() throws Exception {
        // An empty or null language means "let the service detect the
        // language"; sending asr_options with a null/blank language field
        // would be rejected as a parameter error.
        String json = provider.buildRequestBody("qwen3-asr-flash", new byte[]{1}, "audio/wav", null);
        assertTrue(mapper.readTree(json).path("asr_options").isMissingNode());
        String jsonBlank = provider.buildRequestBody("qwen3-asr-flash", new byte[]{1}, "audio/wav", " ");
        assertTrue(mapper.readTree(jsonBlank).path("asr_options").isMissingNode());
    }

    @Test
    @DisplayName("stripLocale: zh-CN → zh, en → en, null/blank → null")
    void stripLocale_variants() {
        assertEquals("zh", DashScopeSttProvider.stripLocale("zh-CN"));
        assertEquals("zh", DashScopeSttProvider.stripLocale("ZH-Hant"));
        assertEquals("en", DashScopeSttProvider.stripLocale("en"));
        assertNull(DashScopeSttProvider.stripLocale(null));
        assertNull(DashScopeSttProvider.stripLocale("  "));
    }

    @Test
    @DisplayName("parseTranscript reads plain-string message content")
    void parseTranscript_stringContent() throws Exception {
        String response = """
                {"choices":[{"message":{"role":"assistant","content":"你好世界"},
                             "finish_reason":"stop"}],"usage":{}}
                """;
        assertEquals("你好世界", provider.parseTranscript(response));
    }

    @Test
    @DisplayName("parseTranscript concatenates content-part arrays")
    void parseTranscript_arrayContent() throws Exception {
        String response = """
                {"choices":[{"message":{"content":[{"text":"你好"},{"text":"世界"}]}}]}
                """;
        assertEquals("你好世界", provider.parseTranscript(response));
    }

    @Test
    @DisplayName("parseTranscript returns empty string on missing/odd shapes instead of throwing")
    void parseTranscript_missingContent() throws Exception {
        assertEquals("", provider.parseTranscript("{}"));
        assertEquals("", provider.parseTranscript("{\"choices\":[]}"));
    }

    @Test
    @DisplayName("duration mismatch detects a truncated decode but tolerates rounding")
    void durationMismatch() {
        assertTrue(DashScopeSttProvider.isSuspiciouslyTruncated(8.0, 2));
        assertEquals(false, DashScopeSttProvider.isSuspiciouslyTruncated(8.0, 7));
        assertEquals(false, DashScopeSttProvider.isSuspiciouslyTruncated(2.0, 1));
        assertEquals(6, provider.parseRecognizedSeconds("{\"usage\":{\"seconds\":6}}"));
        assertEquals(-1, provider.parseRecognizedSeconds("{}"));
    }

    @Test
    @DisplayName("parseErrorMessage handles compatible-mode and native error bodies")
    void parseErrorMessage_variants() {
        assertEquals("InvalidApiKey — Invalid API-key provided.",
                provider.parseErrorMessage("""
                        {"error":{"code":"InvalidApiKey","message":"Invalid API-key provided."}}
                        """));
        assertEquals("Throttling — Requests throttled.",
                provider.parseErrorMessage("""
                        {"code":"Throttling","message":"Requests throttled."}
                        """));
        assertEquals("just a message",
                provider.parseErrorMessage("{\"message\":\"just a message\"}"));
        assertEquals("", provider.parseErrorMessage("not json"));
        assertEquals("", provider.parseErrorMessage(null));
    }

    @Test
    @DisplayName("computePcmPeakRms returns 0,0 on silence; non-zero on synthetic tone")
    void computePcmPeakRms_distinguishesSilenceFromSignal() {
        // The diagnostic distinguishing "mic captured silence" (peak=0) from
        // "provider rejected non-empty audio" is a critical user-visible
        // signal — pin its math.
        byte[] silent = new byte[1000];                          // all zeros
        int[] silentStats = DashScopeSttProvider.computePcmPeakRms(silent);
        assertEquals(0, silentStats[0]);
        assertEquals(0, silentStats[1]);

        // Two samples: 0x4000 (16384, positive) and 0xC000 (-16384, negative).
        // peak should be 16384, rms = sqrt((16384^2 + 16384^2) / 2) = 16384.
        byte[] tone = new byte[]{
                0x00, 0x40,   // 16384 little-endian
                0x00, (byte) 0xC0  // -16384 little-endian
        };
        int[] toneStats = DashScopeSttProvider.computePcmPeakRms(tone);
        assertEquals(16384, toneStats[0]);
        assertEquals(16384, toneStats[1]);
    }

    @Test
    @DisplayName("autoDetectOrder boosts DashScope on Chinese, defaults otherwise")
    void autoDetectOrder_languageRouting() {
        assertEquals(60, provider.autoDetectOrder("zh"));
        assertEquals(60, provider.autoDetectOrder("zh-CN"));
        assertEquals(60, provider.autoDetectOrder("ZH-Hant"));   // case-insensitive
        assertEquals(150, provider.autoDetectOrder("en-US"));    // default order
        assertEquals(150, provider.autoDetectOrder(null));       // language unknown
    }
}
