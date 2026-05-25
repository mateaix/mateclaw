package vip.mate.tool.image.vision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.system.featureflag.FlagContext;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.tool.image.ImageCapability;
import vip.mate.wiki.metrics.WikiMetrics;
import vip.mate.wiki.model.WikiImageCaptionCacheEntity;
import vip.mate.wiki.service.WikiImageCaptionCacheService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;

/**
 * Unit tests for {@link ImageVisionService}.
 *
 * <p>Covers feature-flag short-circuit, cache-hit fast path, provider
 * fallback chain (failure of higher-priority provider falls through to
 * the next), all-failed case, and persist-after-success.
 */
class ImageVisionServiceTest {

    private WikiImageCaptionCacheService cacheService;
    private SystemSettingService settingService;
    private FeatureFlagService featureFlag;
    private WikiMetrics metrics;

    @BeforeEach
    void setUp() {
        cacheService = mock(WikiImageCaptionCacheService.class);
        settingService = mock(SystemSettingService.class);
        featureFlag = mock(FeatureFlagService.class);
        metrics = mock(WikiMetrics.class);
        when(settingService.getSettings()).thenReturn(new SystemSettingsDTO());
        // Default: feature flag on
        when(featureFlag.isEnabled("wiki.ocr.enabled")).thenReturn(true);
    }

    @Test
    @DisplayName("Disabled feature flag short-circuits with err.wiki.vision.disabled")
    void disabledFlag_shortCircuits() {
        when(featureFlag.isEnabled(anyString())).thenReturn(false);
        ImageVisionService service = newService(List.of(stubProvider("p1", true, sampleResult("a"))));

        assertThatThrownBy(() -> service.caption(sampleRequest()))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    @DisplayName("Empty / null image bytes rejected with IllegalArgumentException")
    void emptyImage_rejected() {
        ImageVisionService service = newService(List.of());

        assertThatThrownBy(() -> service.caption(null))
                .isInstanceOf(IllegalArgumentException.class);

        VisionRequest empty = VisionRequest.builder().imageBytes(new byte[0]).mimeType("image/png").build();
        assertThatThrownBy(() -> service.caption(empty))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Cache hit returns immediately and skips provider chain")
    void cacheHit_skipsProviders() {
        WikiImageCaptionCacheEntity row = sampleCacheRow();
        when(cacheService.lookup(anyString())).thenReturn(Optional.of(row));

        ImageVisionProvider p1 = stubProvider("p1", true, sampleResult("would-have-called"));
        ImageVisionService service = newService(List.of(p1));

        VisionResult result = service.caption(sampleRequest());

        assertThat(result.getCaption()).isEqualTo(row.getCaption());
        assertThat(result.getProviderId()).isEqualTo(row.getProviderId());
        verify(p1, never()).caption(any(), any());
        verify(metrics).recordVisionCacheHit(true);
        verify(cacheService, never()).persist(any());
    }

    @Test
    @DisplayName("No available provider → err.wiki.vision.no_provider")
    void noAvailableProvider_throws() {
        when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
        ImageVisionProvider p = stubProvider("p", false, null);
        ImageVisionService service = newService(List.of(p));

        assertThatThrownBy(() -> service.caption(sampleRequest()))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("provider");
    }

    @Test
    @DisplayName("First provider failure falls through to second in autoDetectOrder")
    void firstFails_secondSucceeds() {
        when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
        ImageVisionProvider p1 = stubProvider("p1", true, null);  // null result via throwing
        when(p1.caption(any(), any())).thenThrow(new RuntimeException("rate-limited"));
        when(p1.autoDetectOrder()).thenReturn(10);

        VisionResult win = sampleResult("from p2");
        ImageVisionProvider p2 = stubProvider("p2", true, win);
        when(p2.autoDetectOrder()).thenReturn(20);

        ImageVisionService service = newService(List.of(p1, p2));

        VisionResult result = service.caption(sampleRequest());

        assertThat(result.getCaption()).isEqualTo("from p2");
        verify(p1).caption(any(), any());
        verify(p2).caption(any(), any());
        verify(cacheService).persist(any());
        verify(metrics).recordVisionCall(eq("p1"), eq(false), any());
        verify(metrics).recordVisionCall(eq("p2"), eq(true), any());
    }

    @Test
    @DisplayName("All providers fail → err.wiki.vision.all_failed")
    void allFail_throws() {
        when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
        ImageVisionProvider p1 = stubProvider("p1", true, null);
        when(p1.caption(any(), any())).thenThrow(new RuntimeException("HTTP 500"));
        ImageVisionService service = newService(List.of(p1));

        assertThatThrownBy(() -> service.caption(sampleRequest()))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("All image vision providers failed");
        verify(cacheService, never()).persist(any());
    }

    @Test
    @DisplayName("Lower autoDetectOrder is tried first")
    void orderingHonored() {
        when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
        VisionResult r1 = sampleResult("from p-low");
        ImageVisionProvider pLow = stubProvider("p-low", true, r1);
        when(pLow.autoDetectOrder()).thenReturn(10);

        ImageVisionProvider pHigh = stubProvider("p-high", true, sampleResult("from p-high"));
        when(pHigh.autoDetectOrder()).thenReturn(99);

        // Pass in reversed order to confirm internal sort.
        ImageVisionService service = newService(List.of(pHigh, pLow));

        VisionResult result = service.caption(sampleRequest());

        assertThat(result.getCaption()).isEqualTo("from p-low");
        verify(pHigh, never()).caption(any(), any());
    }

    @Test
    @DisplayName("Same image bytes always produce the same SHA-256 hex")
    void sha256_stable() {
        byte[] bytes = "hello world".getBytes();
        String a = ImageVisionService.sha256Hex(bytes);
        String b = ImageVisionService.sha256Hex(bytes);
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    // ==================== helpers ====================

    private ImageVisionService newService(List<ImageVisionProvider> providers) {
        return new ImageVisionService(providers, cacheService, settingService, featureFlag, metrics);
    }

    private static VisionRequest sampleRequest() {
        return VisionRequest.builder()
                .imageBytes(new byte[]{1, 2, 3, 4})
                .mimeType("image/png")
                .build();
    }

    private static VisionResult sampleResult(String caption) {
        return VisionResult.builder()
                .caption(caption)
                .providerId("test-provider")
                .model("test-model")
                .capturedAt(Instant.now())
                .durationMs(123L)
                .build();
    }

    private static WikiImageCaptionCacheEntity sampleCacheRow() {
        WikiImageCaptionCacheEntity row = new WikiImageCaptionCacheEntity();
        row.setImageSha256("0123456789abcdef".repeat(4));
        row.setCaption("cached caption");
        row.setCaptureModel("cached-model");
        row.setProviderId("cached-provider");
        row.setCapturedAt(LocalDateTime.now());
        row.setDurationMs(0L);
        return row;
    }

    private static ImageVisionProvider stubProvider(String id, boolean available, VisionResult result) {
        ImageVisionProvider provider = mock(ImageVisionProvider.class);
        when(provider.id()).thenReturn(id);
        when(provider.label()).thenReturn(id);
        when(provider.requiresCredential()).thenReturn(true);
        when(provider.autoDetectOrder()).thenReturn(50);
        when(provider.capabilities()).thenReturn(Set.of(ImageCapability.IMAGE_TO_TEXT));
        when(provider.isAvailable(any())).thenReturn(available);
        if (result != null) {
            when(provider.caption(any(), any())).thenReturn(result);
        }
        return provider;
    }
}
