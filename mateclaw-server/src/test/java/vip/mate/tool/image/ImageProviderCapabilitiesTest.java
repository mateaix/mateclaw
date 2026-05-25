package vip.mate.tool.image;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks in the orientation-aware {@link ImageProviderCapabilities#normalizeSize}
 * contract. The earlier implementation matched purely by area, which collapsed
 * portrait/landscape requests onto the wrong supported size when supported
 * sizes had identical area (720x1280 vs 1280x720). Each provider previously
 * worked around this by re-deriving the size from {@code aspectRatio} inside
 * {@code submit()}; centralizing that logic here lets providers trust
 * {@code request.getSize()}.
 */
@Tag("media-gen")
class ImageProviderCapabilitiesTest {

    private static ImageProviderCapabilities dashScopeStyle() {
        return ImageProviderCapabilities.builder()
                .supportedSizes(List.of("1024x1024", "720x1280", "1280x720"))
                .aspectRatios(List.of("1:1", "16:9", "9:16"))
                .build();
    }

    private static ImageProviderCapabilities falStyle() {
        return ImageProviderCapabilities.builder()
                .supportedSizes(List.of("1024x1024", "1024x1536", "1536x1024"))
                .aspectRatios(List.of("1:1", "16:9", "9:16", "4:3", "3:4"))
                .build();
    }

    @Test
    void exactMatchPassesThrough() {
        assertEquals("1280x720", dashScopeStyle().normalizeSize("1280x720", "16:9"));
        assertEquals("720x1280", dashScopeStyle().normalizeSize("720x1280", "9:16"));
    }

    @Test
    void aspectRatioPicksLandscapeWhenSizeMissing() {
        // Without aspect: area-based fallback could pick either 720x1280 or 1280x720
        // (identical area). With aspect 16:9, must select landscape.
        assertEquals("1280x720", dashScopeStyle().normalizeSize(null, "16:9"));
    }

    @Test
    void aspectRatioPicksPortraitWhenSizeMissing() {
        assertEquals("720x1280", dashScopeStyle().normalizeSize(null, "9:16"));
    }

    @Test
    void aspectRatioPreservesOrientationWhenSizeIsUnsupported() {
        // 1920x1080 is unsupported; without aspect awareness the area match would
        // collapse to whichever 720*1280 entry came first. Aspect 16:9 forces landscape.
        assertEquals("1280x720", dashScopeStyle().normalizeSize("1920x1080", "16:9"));
        assertEquals("720x1280", dashScopeStyle().normalizeSize("1080x1920", "9:16"));
    }

    @Test
    void squareAspectFallsBackToSquareSize() {
        assertEquals("1024x1024", dashScopeStyle().normalizeSize(null, "1:1"));
        assertEquals("1024x1024", falStyle().normalizeSize(null, "1:1"));
    }

    @Test
    void undeclaredButLandscapeAspectStillRoutesToLandscapeSize() {
        // 4:3 is not in aspectRatios but is numerically landscape (4 > 3).
        // Orientation filter narrows to landscape candidates (1280x720 only).
        assertEquals("1280x720", dashScopeStyle().normalizeSize(null, "4:3"));
        assertEquals("720x1280", dashScopeStyle().normalizeSize(null, "3:4"));
    }

    @Test
    void blankInputReturnsAreaClosest() {
        // Blank size + blank aspect: pick by default area (1M).
        assertEquals("1024x1024", dashScopeStyle().normalizeSize("", null));
        assertEquals("1024x1024", dashScopeStyle().normalizeSize(null, null));
    }

    @Test
    void backwardsCompatibleOverloadStillWorks() {
        // Old single-arg overload delegates to the new one with null aspect.
        assertEquals("1024x1024", dashScopeStyle().normalizeSize("1024x1024"));
    }

    @Test
    void normalizeAspectRatioFallsBackToFirstSupported() {
        assertEquals("1:1", dashScopeStyle().normalizeAspectRatio("21:9"));
        assertEquals("16:9", dashScopeStyle().normalizeAspectRatio("16:9"));
    }

    @Test
    void normalizeCountClampsWithinBounds() {
        ImageProviderCapabilities caps = ImageProviderCapabilities.builder()
                .maxCount(4).build();
        assertEquals(1, caps.normalizeCount(0));
        assertEquals(4, caps.normalizeCount(10));
        assertEquals(2, caps.normalizeCount(2));
    }
}
