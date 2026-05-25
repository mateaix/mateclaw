package vip.mate.tool.video.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.video.VideoCapability;
import vip.mate.tool.video.VideoProviderCapabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the two pure-logic surfaces of {@link MiniMaxVideoProvider} that
 * shouldn't require a live API: region routing and the published model
 * catalog. Network paths (submit / poll / file-resolve) need wiremock or
 * live fixtures and are out of scope here.
 */
@Tag("media-gen")
class MiniMaxVideoProviderTest {

    private final MiniMaxVideoProvider provider = new MiniMaxVideoProvider(new ObjectMapper());

    @Test
    @DisplayName("resolveBaseUrl: minimaxRegion='cn' (any case) → CN endpoint")
    void resolveBaseUrl_cn() {
        // CN MiniMax accounts can't reach api.minimax.io — region routing is
        // not optional for that user segment.
        SystemSettingsDTO cfg = new SystemSettingsDTO();
        cfg.setMinimaxRegion("cn");
        assertEquals(MiniMaxVideoProvider.BASE_URL_CN, MiniMaxVideoProvider.resolveBaseUrl(cfg));

        cfg.setMinimaxRegion("CN");
        assertEquals(MiniMaxVideoProvider.BASE_URL_CN, MiniMaxVideoProvider.resolveBaseUrl(cfg),
                "Region match must be case-insensitive");
    }

    @Test
    @DisplayName("resolveBaseUrl: default / explicit global / null → Global endpoint")
    void resolveBaseUrl_globalFallbacks() {
        // Defaults must NOT silently route to CN — operators outside mainland
        // CN must work without setting any region.
        SystemSettingsDTO cfg = new SystemSettingsDTO();
        assertEquals(MiniMaxVideoProvider.BASE_URL_GLOBAL,
                MiniMaxVideoProvider.resolveBaseUrl(cfg));
        cfg.setMinimaxRegion("global");
        assertEquals(MiniMaxVideoProvider.BASE_URL_GLOBAL,
                MiniMaxVideoProvider.resolveBaseUrl(cfg));
        // Defensive: null config → still global, no NPE.
        assertEquals(MiniMaxVideoProvider.BASE_URL_GLOBAL,
                MiniMaxVideoProvider.resolveBaseUrl(null));
    }

    @Test
    @DisplayName("Catalog: 6 models declared (3 T2V + 3 I2V) matching openclaw")
    void detailedCapabilities_listsAllModels() {
        // Sync with openclaw extensions/minimax/provider-models.ts. Adding a
        // model here without verifying MiniMax actually serves it would lead
        // to opaque 404s — the public catalog is the source of truth.
        VideoProviderCapabilities caps = provider.detailedCapabilities();
        assertTrue(caps.getModels().contains("MiniMax-Hailuo-2.3"));
        assertTrue(caps.getModels().contains("MiniMax-Hailuo-2.3-Fast"));
        assertTrue(caps.getModels().contains("MiniMax-Hailuo-02"),
                "Hailuo-02 was missing before this change — keep pinned to detect regressions");
        assertTrue(caps.getModels().contains("I2V-01-Director"));
        assertTrue(caps.getModels().contains("I2V-01-live"));
        assertTrue(caps.getModels().contains("I2V-01"));
        assertEquals(6, caps.getModels().size(),
                "Adding a model? Update this assertion + wire it through openclaw to confirm the API serves it");
    }

    @Test
    @DisplayName("Default model stays MiniMax-Hailuo-2.3 (most-used T2V)")
    void detailedCapabilities_defaultModel() {
        // Default model is what users hit when they don't explicitly pick.
        // Changing this changes user behavior — pin it.
        assertEquals("MiniMax-Hailuo-2.3", provider.detailedCapabilities().getDefaultModel());
    }

    @Test
    @DisplayName("Capabilities: TEXT_TO_VIDEO + IMAGE_TO_VIDEO both declared")
    void capabilities_includesBoth() {
        // I2V-01-* models live in the catalog but the provider also has to
        // advertise the capability flag, otherwise the dispatcher won't route
        // image-input requests here.
        var caps = provider.capabilities();
        assertTrue(caps.contains(VideoCapability.GENERATE));
        assertTrue(caps.contains(VideoCapability.IMAGE_TO_VIDEO));
    }

    @Test
    @DisplayName("Host constants match MiniMax's documented endpoints")
    void hostsAreCanonical() {
        // Pin string values so a typo (api.minimax.com vs api.minimaxi.com)
        // is caught at test time, not via opaque DNS errors in production.
        assertEquals("https://api.minimax.io", MiniMaxVideoProvider.BASE_URL_GLOBAL);
        assertEquals("https://api.minimaxi.com", MiniMaxVideoProvider.BASE_URL_CN);
    }
}
