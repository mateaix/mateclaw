package vip.mate.tool.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the configuration-driven payload behaviour:
 * <ol>
 *   <li>The model spec's {@code supports} set is the final whitelist — keys not
 *       on it must be dropped from the produced JSON regardless of how they got
 *       there (defaults, explicit setters, sizing).</li>
 *   <li>Each {@link SizeStyle} produces the right key and translates from the
 *       unified {@code size} / {@code aspectRatio} inputs to the model-native
 *       form (literal dim / aspect ratio / preset).</li>
 *   <li>Empty / null whitelist passes everything through.</li>
 * </ol>
 */
@Tag("media-gen")
class PayloadBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ImageModelSpec literalSpec(Set<String> supports) {
        return ImageModelSpec.builder()
                .id("literal-test")
                .endpoint("https://example/api")
                .transport(ImageModelSpec.Transport.SYNC)
                .sizeStyle(SizeStyle.LITERAL_DIMENSION)
                .sizeMapping("1:1", "1024x1024")
                .sizeMapping("16:9", "1280x720")
                .sizeMapping("9:16", "720x1280")
                .sizeMapping("landscape", "1280x720")
                .sizeMapping("square", "1024x1024")
                .sizeMapping("portrait", "720x1280")
                .supports(supports)
                .maxCount(4)
                .build();
    }

    @Test
    @DisplayName("supports whitelist: keys outside the set are dropped from JSON")
    void supportsWhitelistFiltersOutKeys() {
        ImageModelSpec spec = literalSpec(Set.of("size", "n"));
        ObjectNode body = PayloadBuilder.from(spec)
                .withPrompt("hello")
                .withCount(2)
                .withSize("1024x1024", "1:1")
                .withSeed(42)
                .put("custom", "yes")
                .toJsonNode(mapper);

        assertTrue(body.has("size"));
        assertTrue(body.has("n"));
        assertFalse(body.has("prompt"), "prompt is not in supports => filtered");
        assertFalse(body.has("seed"), "seed is not in supports => filtered");
        assertFalse(body.has("custom"), "ad-hoc keys not in supports => filtered");
    }

    @Test
    @DisplayName("empty supports set means passthrough — no filtering")
    void emptySupports_passesEverything() {
        ImageModelSpec spec = literalSpec(Set.of());
        ObjectNode body = PayloadBuilder.from(spec)
                .withPrompt("p")
                .withCount(1)
                .withSize("1024x1024", "1:1")
                .toJsonNode(mapper);
        assertTrue(body.has("prompt"));
        assertTrue(body.has("size"));
        assertTrue(body.has("n"));
    }

    @Test
    @DisplayName("LITERAL_DIMENSION: requested size in sizeMap is translated to native form")
    void literalDimension_translatesViaSizeMap() {
        // sizeMap entry "1024x1024" -> native form would normally be the same;
        // legacy DashScope translates to "1024*1024". Provide a custom mapping.
        ImageModelSpec spec = ImageModelSpec.builder()
                .id("legacy-async")
                .endpoint("https://x/api")
                .transport(ImageModelSpec.Transport.ASYNC)
                .sizeStyle(SizeStyle.LITERAL_DIMENSION)
                .sizeMapping("1024x1024", "1024*1024")
                .sizeMapping("landscape", "1280*720")
                .supports(Set.of("size", "n"))
                .maxCount(4)
                .build();
        ObjectNode body = PayloadBuilder.from(spec)
                .withSize("1024x1024", "1:1")
                .toJsonNode(mapper);
        assertEquals("1024*1024", body.get("size").asText());
    }

    @Test
    @DisplayName("LITERAL_DIMENSION: missing size falls back to orientation lookup in sizeMap")
    void literalDimension_orientationFallback() {
        ImageModelSpec spec = literalSpec(Set.of("size"));
        // No requested size, aspect 16:9 → must pick landscape entry.
        ObjectNode body = PayloadBuilder.from(spec).withSize(null, "16:9").toJsonNode(mapper);
        assertEquals("1280x720", body.get("size").asText());

        // 9:16 → portrait
        ObjectNode portrait = PayloadBuilder.from(spec).withSize(null, "9:16").toJsonNode(mapper);
        assertEquals("720x1280", portrait.get("size").asText());
    }

    @Test
    @DisplayName("ASPECT_RATIO style sets aspect_ratio (not size); requested ratio is forwarded")
    void aspectRatioStyle_setsAspectRatioKey() {
        ImageModelSpec spec = ImageModelSpec.builder()
                .id("aspect")
                .endpoint("https://x")
                .transport(ImageModelSpec.Transport.SYNC)
                .sizeStyle(SizeStyle.ASPECT_RATIO)
                .supports(Set.of("aspect_ratio"))
                .build();
        ObjectNode body = PayloadBuilder.from(spec).withSize(null, "16:9").toJsonNode(mapper);
        assertEquals("16:9", body.get("aspect_ratio").asText());
        assertFalse(body.has("size"));
    }

    @Test
    @DisplayName("PRESET_NAME style sets image_size to the orientation-keyed preset")
    void presetStyle_setsImageSizeKey() {
        ImageModelSpec spec = ImageModelSpec.builder()
                .id("preset")
                .endpoint("https://x")
                .transport(ImageModelSpec.Transport.SYNC)
                .sizeStyle(SizeStyle.PRESET_NAME)
                .sizeMapping("landscape", "landscape_16_9")
                .sizeMapping("square", "square_hd")
                .sizeMapping("portrait", "portrait_16_9")
                .supports(Set.of("image_size"))
                .build();
        // 16:9 is landscape
        assertEquals("landscape_16_9",
                PayloadBuilder.from(spec).withSize(null, "16:9").toJsonNode(mapper).get("image_size").asText());
        // 1:1 is square
        assertEquals("square_hd",
                PayloadBuilder.from(spec).withSize(null, "1:1").toJsonNode(mapper).get("image_size").asText());
    }

    @Test
    @DisplayName("defaults from spec are seeded before explicit setters; overrides take precedence")
    void defaultsAreSeededFirst() {
        ImageModelSpec spec = ImageModelSpec.builder()
                .id("with-defaults")
                .endpoint("https://x")
                .transport(ImageModelSpec.Transport.SYNC)
                .sizeStyle(SizeStyle.LITERAL_DIMENSION)
                .sizeMapping("1:1", "1024x1024")
                .defaultParam("watermark", true)
                .defaultParam("n", 1)
                .supports(Set.of("watermark", "n", "size"))
                .build();
        ObjectNode body = PayloadBuilder.from(spec).withSize(null, "1:1").withCount(3).toJsonNode(mapper);
        assertEquals(true, body.get("watermark").asBoolean());
        // explicit count overrides default
        assertEquals(3, body.get("n").asInt());
    }

    @Test
    @DisplayName("withCount clamps to spec.maxCount when above it")
    void withCount_clampsToMaxCount() {
        ImageModelSpec spec = literalSpec(Set.of("n"));
        ObjectNode body = PayloadBuilder.from(spec).withCount(99).toJsonNode(mapper);
        assertEquals(4, body.get("n").asInt());
    }
}
