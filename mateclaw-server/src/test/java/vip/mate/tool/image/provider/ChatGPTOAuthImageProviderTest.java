package vip.mate.tool.image.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import vip.mate.llm.oauth.OpenAIOAuthService;
import vip.mate.tool.image.ImageGenerationRequest;
import vip.mate.tool.image.ImageProviderCapabilities;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the OAuth image provider. Focus on the deterministic bits —
 * Responses-API body construction, SSE stream parsing, quality/size mapping.
 * Network-dependent {@code submit()} is exercised end-to-end via a separate
 * integration test once a sandbox token is available.
 */
@Tag("media-gen")
class ChatGPTOAuthImageProviderTest {

    private ChatGPTOAuthImageProvider provider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        provider = new ChatGPTOAuthImageProvider(mock(OpenAIOAuthService.class), objectMapper);
        // @Value defaults aren't applied in plain new() construction — inject
        // them via reflection so the build paths see realistic values.
        setField(provider, "chatHostModel", "gpt-5.4");
        setField(provider, "defaultQuality", "medium");
        setField(provider, "timeoutMs", 240000);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = ChatGPTOAuthImageProvider.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ==================== body construction =================================

    @Test
    @DisplayName("body uses chat-host model + image_generation tool pinned to gpt-image-2")
    void buildResponsesBody_pinsImageModelAndTool() throws Exception {
        String body = provider.buildResponsesBody("a red panda", "1024x1024", "medium");
        JsonNode root = objectMapper.readTree(body);

        assertEquals("gpt-5.4", root.path("model").asText());
        assertFalse(root.path("store").asBoolean(true));
        // The /codex/responses endpoint rejects non-streaming with HTTP 400
        // "Stream must be set to true" — lock the flag in.
        assertTrue(root.path("stream").asBoolean(false),
                "stream must be true; codex/responses rejects non-streaming requests");
        assertTrue(root.path("instructions").asText("").contains("image_generation"));

        // Single user message carrying the prompt
        JsonNode input = root.path("input");
        assertTrue(input.isArray());
        assertEquals(1, input.size());
        JsonNode msg = input.get(0);
        assertEquals("user", msg.path("role").asText());
        assertEquals("a red panda",
                msg.path("content").get(0).path("text").asText());

        // Tool definition pinned to gpt-image-2 with the right knobs
        JsonNode tools = root.path("tools");
        assertEquals(1, tools.size());
        JsonNode tool = tools.get(0);
        assertEquals("image_generation", tool.path("type").asText());
        assertEquals("gpt-image-2", tool.path("model").asText());
        assertEquals("1024x1024", tool.path("size").asText());
        assertEquals("medium", tool.path("quality").asText());
        assertEquals("png", tool.path("output_format").asText());
        assertEquals("opaque", tool.path("background").asText());
        assertEquals(1, tool.path("partial_images").asInt());

        // Forced tool_choice
        JsonNode choice = root.path("tool_choice");
        assertEquals("allowed_tools", choice.path("type").asText());
        assertEquals("required", choice.path("mode").asText());
        assertEquals("image_generation",
                choice.path("tools").get(0).path("type").asText());
    }

    @Test
    @DisplayName("buildResponsesBody tolerates a null prompt (degrades to empty string)")
    void buildResponsesBody_nullPromptSafe() throws Exception {
        String body = provider.buildResponsesBody(null, "1024x1024", "low");
        JsonNode root = objectMapper.readTree(body);
        assertEquals("",
                root.path("input").get(0).path("content").get(0).path("text").asText());
    }

    @Test
    @DisplayName("buildResponsesBody respects a configurable chat-host model override")
    void buildResponsesBody_chatHostModelConfigurable() throws Exception {
        setField(provider, "chatHostModel", "gpt-5.5");
        String body = provider.buildResponsesBody("hi", "1024x1024", "medium");
        assertEquals("gpt-5.5", objectMapper.readTree(body).path("model").asText());
    }

    // ==================== quality & size mapping ============================

    @Test
    @DisplayName("qualityForRequest reads tier from model id; falls back to default")
    void qualityForRequest_tiersAndDefault() {
        assertEquals("low", provider.qualityForRequest(
                ImageGenerationRequest.builder().prompt("x").model("gpt-image-2-low").build()));
        assertEquals("medium", provider.qualityForRequest(
                ImageGenerationRequest.builder().prompt("x").model("gpt-image-2-medium").build()));
        assertEquals("high", provider.qualityForRequest(
                ImageGenerationRequest.builder().prompt("x").model("gpt-image-2-high").build()));
        // unknown model id → fall back to configured default
        assertEquals("medium", provider.qualityForRequest(
                ImageGenerationRequest.builder().prompt("x").model("gpt-5.4").build()));
        assertEquals("medium", provider.qualityForRequest(
                ImageGenerationRequest.builder().prompt("x").build()));
    }

    @Test
    @DisplayName("normalizeSize honours explicit supported size, then aspect ratio, then defaults")
    void normalizeSize_priorityOrder() {
        assertEquals("1024x1024", provider.normalizeSize("1024x1024", "1:1"));
        assertEquals("1536x1024", provider.normalizeSize("1536x1024", "1:1"));
        assertEquals("1024x1536", provider.normalizeSize(null, "9:16"));
        assertEquals("1536x1024", provider.normalizeSize(null, "16:9"));
        assertEquals("1024x1024", provider.normalizeSize(null, null));
        // unsupported size → fall through to aspect ratio
        assertEquals("1536x1024", provider.normalizeSize("9999x9999", "16:9"));
    }

    // ==================== SSE parsing ========================================

    @Test
    @DisplayName("SSE parser returns final image from response.output_item.done")
    void sseParser_returnsFinalImage() {
        String body =
                "event: response.image_generation_call.partial_image\n" +
                "data: {\"type\":\"response.image_generation_call.partial_image\",\"partial_image_b64\":\"PARTIAL\"}\n" +
                "\n" +
                "event: response.output_item.done\n" +
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"image_generation_call\",\"result\":\"FINAL\"}}\n" +
                "\n";
        assertEquals("FINAL", provider.extractFinalImageFromSseBody(body));
    }

    @Test
    @DisplayName("SSE parser falls back to the latest partial image if the final frame is missing")
    void sseParser_fallsBackToPartial() {
        String body =
                "event: response.image_generation_call.partial_image\n" +
                "data: {\"type\":\"response.image_generation_call.partial_image\",\"partial_image_b64\":\"FIRST\"}\n" +
                "\n" +
                "event: response.image_generation_call.partial_image\n" +
                "data: {\"type\":\"response.image_generation_call.partial_image\",\"partial_image_b64\":\"SECOND\"}\n" +
                "\n";
        assertEquals("SECOND", provider.extractFinalImageFromSseBody(body));
    }

    @Test
    @DisplayName("SSE parser also reads image from response.completed.output[]")
    void sseParser_readsFromResponseCompleted() {
        String body =
                "event: response.completed\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"image_generation_call\",\"result\":\"DONE\"}]}}\n" +
                "\n";
        assertEquals("DONE", provider.extractFinalImageFromSseBody(body));
    }

    @Test
    @DisplayName("SSE parser ignores [DONE] sentinels and unparseable frames")
    void sseParser_ignoresNoiseFrames() {
        String body =
                ":heartbeat\n\n" +
                "data: [DONE]\n\n" +
                "data: not json at all\n\n" +
                "event: response.output_item.done\n" +
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"image_generation_call\",\"result\":\"REAL\"}}\n\n";
        assertEquals("REAL", provider.extractFinalImageFromSseBody(body));
    }

    @Test
    @DisplayName("SSE parser returns null when there is no image in any frame")
    void sseParser_returnsNullWhenNoImage() {
        assertNull(provider.extractFinalImageFromSseBody(""));
        assertNull(provider.extractFinalImageFromSseBody(null));
        assertNull(provider.extractFinalImageFromSseBody(
                "event: response.created\ndata: {\"type\":\"response.created\"}\n\n"));
    }

    // ==================== capability surface =================================

    @Test
    @DisplayName("detailedCapabilities exposes the three gpt-image-2 tiers and right sizes")
    void detailedCapabilities_advertisesTiers() {
        ImageProviderCapabilities caps = provider.detailedCapabilities();
        assertEquals("gpt-image-2-medium", caps.getDefaultModel());
        assertTrue(caps.getModels().containsAll(
                java.util.List.of("gpt-image-2-low", "gpt-image-2-medium", "gpt-image-2-high")));
        assertTrue(caps.getSupportedSizes().contains("1536x1024"));
        assertTrue(caps.getSupportedSizes().contains("1024x1536"));
        assertEquals(1, caps.getMaxCount());
    }

    @Test
    @DisplayName("provider id matches the existing OAuth provider id, label is descriptive")
    void identityFields() {
        assertEquals("openai-chatgpt", provider.id());
        assertTrue(provider.label().contains("ChatGPT"));
        assertTrue(provider.requiresCredential());
    }
}
