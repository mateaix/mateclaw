package vip.mate.tool.image.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import vip.mate.tool.image.ImageProviderCapabilities;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenAiImageProvider} GPT-Image-2 wiring.
 *
 * <p>Three virtual model IDs (gpt-image-2-low/medium/high) all map to API model
 * {@code gpt-image-2} with a different {@code quality} parameter. The new
 * size set is 1024x1024 / 1024x1536 / 1536x1024, distinct from DALL-E's
 * 1024x1024 / 1024x1792 / 1792x1024.
 *
 * <p>Tests focus on the pure-logic helpers (capabilities catalog, tier→quality
 * mapping, model dispatch detection, size normalization). HTTP submission is
 * not exercised here — that requires either a live OPENAI_API_KEY or an HTTP
 * mock framework. The split-out unit tests cover everything that isn't
 * literally "did the network return 200".
 */
@Tag("media-gen")
class OpenAiImageProviderGptImage2Test {

    private OpenAiImageProvider newProvider() {
        // ModelProviderService is only consulted inside submit(); the helper
        // methods we exercise here don't touch it. null is safe.
        return new OpenAiImageProvider(null, new ObjectMapper());
    }

    @Test
    @DisplayName("detailedCapabilities lists all three gpt-image-2 tiers + DALL-E models")
    void capabilities_listAllModels() {
        ImageProviderCapabilities caps = newProvider().detailedCapabilities();

        assertTrue(caps.getModels().contains("dall-e-3"));
        assertTrue(caps.getModels().contains("dall-e-2"));
        assertTrue(caps.getModels().contains("gpt-image-1"));
        assertTrue(caps.getModels().contains("gpt-image-2-low"),
                "gpt-image-2-low must be picker-visible");
        assertTrue(caps.getModels().contains("gpt-image-2-medium"));
        assertTrue(caps.getModels().contains("gpt-image-2-high"));

        assertEquals("dall-e-3", caps.getDefaultModel(),
                "Default stays dall-e-3 — gpt-image-2 is opt-in by selecting tier");
    }

    @Test
    @DisplayName("detailedCapabilities supportedSizes covers both DALL-E and gpt-image-2 sizes")
    void capabilities_unionOfSizes() {
        ImageProviderCapabilities caps = newProvider().detailedCapabilities();

        // DALL-E sizes
        assertTrue(caps.getSupportedSizes().contains("1024x1024"));
        assertTrue(caps.getSupportedSizes().contains("1024x1792"));
        assertTrue(caps.getSupportedSizes().contains("1792x1024"));

        // gpt-image-2 sizes (NOT identical to DALL-E)
        assertTrue(caps.getSupportedSizes().contains("1024x1536"));
        assertTrue(caps.getSupportedSizes().contains("1536x1024"));
    }

    @Test
    @DisplayName("isGptImage2Tier identifies the three virtual IDs and rejects others")
    void isGptImage2Tier_correctDispatch() {
        assertTrue(OpenAiImageProvider.isGptImage2Tier("gpt-image-2-low"));
        assertTrue(OpenAiImageProvider.isGptImage2Tier("gpt-image-2-medium"));
        assertTrue(OpenAiImageProvider.isGptImage2Tier("gpt-image-2-high"));

        assertFalse(OpenAiImageProvider.isGptImage2Tier("gpt-image-2"));
        assertFalse(OpenAiImageProvider.isGptImage2Tier("dall-e-3"));
        assertFalse(OpenAiImageProvider.isGptImage2Tier("dall-e-2"));
        assertFalse(OpenAiImageProvider.isGptImage2Tier("gpt-image-1"));
        assertFalse(OpenAiImageProvider.isGptImage2Tier(null));
        assertFalse(OpenAiImageProvider.isGptImage2Tier(""));
    }

    @Test
    @DisplayName("qualityForTier maps each virtual ID to the right quality string")
    void qualityForTier_correctMapping() {
        assertEquals("low", OpenAiImageProvider.qualityForTier("gpt-image-2-low"));
        assertEquals("medium", OpenAiImageProvider.qualityForTier("gpt-image-2-medium"));
        assertEquals("high", OpenAiImageProvider.qualityForTier("gpt-image-2-high"));

        // Defensive: any unrecognised id falls back to medium (sane default).
        assertEquals("medium", OpenAiImageProvider.qualityForTier("anything-else"));
        assertEquals("medium", OpenAiImageProvider.qualityForTier(""));
    }

    @Test
    @DisplayName("normalizeSize: gpt-image-2 path picks gpt-image-2 sizes from aspect ratio")
    void normalizeSize_gptImage2_byAspectRatio() {
        OpenAiImageProvider p = newProvider();

        assertEquals("1024x1024", p.normalizeSize(null, "1:1", true));
        assertEquals("1024x1536", p.normalizeSize(null, "9:16", true),
                "Portrait must map to gpt-image-2's 1024x1536, NOT dall-e's 1024x1792");
        assertEquals("1536x1024", p.normalizeSize(null, "16:9", true),
                "Landscape must map to gpt-image-2's 1536x1024, NOT dall-e's 1792x1024");
    }

    @Test
    @DisplayName("normalizeSize: dall-e path keeps original 1024x1792 / 1792x1024 sizes")
    void normalizeSize_dallE_unchanged() {
        OpenAiImageProvider p = newProvider();

        assertEquals("1024x1024", p.normalizeSize(null, "1:1", false));
        assertEquals("1024x1792", p.normalizeSize(null, "9:16", false));
        assertEquals("1792x1024", p.normalizeSize(null, "16:9", false));
    }

    @Test
    @DisplayName("normalizeSize: explicit size honored only when supported by selected model family")
    void normalizeSize_explicitSizeRespectsModelFamily() {
        OpenAiImageProvider p = newProvider();

        // gpt-image-2 explicit size hit
        assertEquals("1536x1024", p.normalizeSize("1536x1024", "1:1", true));
        // gpt-image-2 explicit size MISS (DALL-E size given to gpt-image-2 → fall back to aspect)
        assertEquals("1024x1024", p.normalizeSize("1792x1024", "1:1", true));

        // dall-e explicit size hit
        assertEquals("1792x1024", p.normalizeSize("1792x1024", "1:1", false));
        // dall-e explicit size MISS (gpt-image-2 size given to dall-e → fall back to aspect)
        assertEquals("1024x1024", p.normalizeSize("1536x1024", "1:1", false));
    }

    @Test
    @DisplayName("normalizeSize: extra gpt-image-2 aspect-ratio aliases (3:4, 2:3, 4:3, 3:2) work")
    void normalizeSize_gptImage2_extraAspectAliases() {
        OpenAiImageProvider p = newProvider();
        // Portrait aliases → 1024x1536, landscape → 1536x1024
        assertEquals("1024x1536", p.normalizeSize(null, "3:4", true));
        assertEquals("1024x1536", p.normalizeSize(null, "2:3", true));
        assertEquals("1536x1024", p.normalizeSize(null, "4:3", true));
        assertEquals("1536x1024", p.normalizeSize(null, "3:2", true));
    }
}
