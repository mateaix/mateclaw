package vip.mate.tool.video.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import vip.mate.tool.video.VideoCapability;
import vip.mate.tool.video.VideoGenerationRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pinpoints the routing decisions in {@link DashScopeVideoProvider}: the
 * model id picks both the endpoint family and the JSON body shape (legacy
 * {@code img_url} flat input vs unified {@code media[]} array). HTTP
 * submission is not exercised here.
 */
@Tag("media-gen")
class DashScopeVideoProviderRoutingTest {

    private final DashScopeVideoProvider provider =
            new DashScopeVideoProvider(null, new ObjectMapper());

    @Test
    @DisplayName("legacy text-to-video model: LEGACY body shape, video-generation/generation endpoint")
    void legacyT2v_routesToLegacyShape() {
        VideoGenerationRequest req = VideoGenerationRequest.builder()
                .prompt("a cat playing piano")
                .model("wan2.5-t2v-turbo")
                .mode(VideoCapability.GENERATE)
                .build();
        DashScopeVideoProvider.ModelSpec spec = provider.resolveSpec(req);
        assertEquals(DashScopeVideoProvider.BodyShape.LEGACY, spec.bodyShape());
        assertTrue(spec.endpoint().endsWith("/services/aigc/video-generation/generation"));
    }

    @Test
    @DisplayName("unified text-to-video model: UNIFIED body shape, video-synthesis endpoint")
    void unifiedT2v_routesToUnifiedShape() {
        VideoGenerationRequest req = VideoGenerationRequest.builder()
                .prompt("a sunset over the sea")
                .model("wan2.7-t2v-2026-04-25")
                .mode(VideoCapability.GENERATE)
                .build();
        DashScopeVideoProvider.ModelSpec spec = provider.resolveSpec(req);
        assertEquals(DashScopeVideoProvider.BodyShape.UNIFIED, spec.bodyShape());
        assertTrue(spec.endpoint().endsWith("/services/aigc/video-generation/video-synthesis"));
    }

    @Test
    @DisplayName("happyhorse t2v: routed to UNIFIED endpoint family")
    void happyhorse_routesToUnifiedShape() {
        VideoGenerationRequest req = VideoGenerationRequest.builder()
                .prompt("a horse running on a beach")
                .model("happyhorse-1.0-t2v")
                .mode(VideoCapability.GENERATE)
                .build();
        DashScopeVideoProvider.ModelSpec spec = provider.resolveSpec(req);
        assertEquals(DashScopeVideoProvider.BodyShape.UNIFIED, spec.bodyShape());
        assertTrue(spec.endpoint().endsWith("/services/aigc/video-generation/video-synthesis"));
    }

    @Test
    @DisplayName("legacy body: input.img_url is set when image url present, parameters.size keyed")
    void legacyBody_includesImgUrlAndSizeKey() {
        VideoGenerationRequest req = VideoGenerationRequest.builder()
                .prompt("walking forward")
                .model("wan2.5-i2v-turbo")
                .mode(VideoCapability.IMAGE_TO_VIDEO)
                .imageUrl("https://cdn.example.com/cover.png")
                .aspectRatio("16:9")
                .durationSeconds(5)
                .build();
        DashScopeVideoProvider.ModelSpec spec = provider.resolveSpec(req);
        JsonNode body = provider.buildRequestBody(req, spec);

        assertEquals("wan2.5-i2v-turbo", body.path("model").asText());
        assertEquals("https://cdn.example.com/cover.png", body.path("input").path("img_url").asText());
        assertFalse(body.path("input").has("media"),
                "legacy shape must not include the unified media[] array");
        // Size uses the legacy '*' separator
        assertEquals("1280*720", body.path("parameters").path("size").asText());
        assertEquals("5", body.path("parameters").path("duration").asText());
    }

    @Test
    @DisplayName("unified body: input.media[] is set with first_frame; parameters.resolution + ratio keyed")
    void unifiedBody_usesMediaArrayAndResolution() {
        VideoGenerationRequest req = VideoGenerationRequest.builder()
                .prompt("the camera pans right")
                .model("wan2.7-i2v-2026-04-25")
                .mode(VideoCapability.IMAGE_TO_VIDEO)
                .imageUrl("https://cdn.example.com/cover.png")
                .aspectRatio("16:9")
                .durationSeconds(8)
                .build();
        DashScopeVideoProvider.ModelSpec spec = provider.resolveSpec(req);
        JsonNode body = provider.buildRequestBody(req, spec);

        assertEquals("wan2.7-i2v-2026-04-25", body.path("model").asText());
        // Unified shape uses media[] not img_url
        assertFalse(body.path("input").has("img_url"));
        JsonNode media = body.path("input").path("media");
        assertTrue(media.isArray() && media.size() == 1);
        assertEquals("first_frame", media.get(0).path("type").asText());
        assertEquals("https://cdn.example.com/cover.png", media.get(0).path("url").asText());

        // Size lives in parameters.resolution + parameters.ratio
        assertFalse(body.path("parameters").has("size"),
                "unified shape uses resolution/ratio, not the legacy size key");
        assertEquals("720P", body.path("parameters").path("resolution").asText());
        assertEquals("16:9", body.path("parameters").path("ratio").asText());
        // Duration is an integer in unified shape (legacy was a string)
        assertEquals(8, body.path("parameters").path("duration").asInt());
    }

    @Test
    @DisplayName("unified body: text-only request omits media[] (no first_frame to send)")
    void unifiedBody_textOnlyOmitsMedia() {
        VideoGenerationRequest req = VideoGenerationRequest.builder()
                .prompt("a horse runs")
                .model("happyhorse-1.0-t2v")
                .mode(VideoCapability.GENERATE)
                .aspectRatio("16:9")
                .build();
        DashScopeVideoProvider.ModelSpec spec = provider.resolveSpec(req);
        JsonNode body = provider.buildRequestBody(req, spec);
        assertFalse(body.path("input").has("media"),
                "text-to-video must not synthesize an empty first_frame");
    }
}
