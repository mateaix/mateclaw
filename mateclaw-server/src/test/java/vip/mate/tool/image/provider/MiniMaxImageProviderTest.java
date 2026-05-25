package vip.mate.tool.image.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import vip.mate.system.model.SystemSettingsDTO;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Region-routing pin for {@link MiniMaxImageProvider}. Image and video share
 * the same {@code minimaxRegion} field on {@link SystemSettingsDTO} —
 * verifying both providers land on the same host when region is set
 * prevents the "image works in CN but video times out" footgun.
 */
@Tag("media-gen")
class MiniMaxImageProviderTest {

    @Test
    @DisplayName("resolveBaseUrl: minimaxRegion='cn' → CN endpoint (matches video provider)")
    void resolveBaseUrl_cn() {
        SystemSettingsDTO cfg = new SystemSettingsDTO();
        cfg.setMinimaxRegion("cn");
        assertEquals(MiniMaxImageProvider.BASE_URL_CN, MiniMaxImageProvider.resolveBaseUrl(cfg));
    }

    @Test
    @DisplayName("resolveBaseUrl: default / null / 'global' → Global endpoint")
    void resolveBaseUrl_default() {
        SystemSettingsDTO cfg = new SystemSettingsDTO();
        assertEquals(MiniMaxImageProvider.BASE_URL_GLOBAL,
                MiniMaxImageProvider.resolveBaseUrl(cfg));
        cfg.setMinimaxRegion("global");
        assertEquals(MiniMaxImageProvider.BASE_URL_GLOBAL,
                MiniMaxImageProvider.resolveBaseUrl(cfg));
        assertEquals(MiniMaxImageProvider.BASE_URL_GLOBAL,
                MiniMaxImageProvider.resolveBaseUrl(null));
    }

    @Test
    @DisplayName("Host constants match MiniMax's documented endpoints")
    void hostsAreCanonical() {
        // Pin string values so a typo (e.g. minimax.com vs minimaxi.com) fails
        // the test before users notice in production. The Video provider's
        // constants are package-private — pinning by literal here cross-checks
        // the image provider without leaking visibility.
        assertEquals("https://api.minimax.io", MiniMaxImageProvider.BASE_URL_GLOBAL);
        assertEquals("https://api.minimaxi.com", MiniMaxImageProvider.BASE_URL_CN);
    }
}
