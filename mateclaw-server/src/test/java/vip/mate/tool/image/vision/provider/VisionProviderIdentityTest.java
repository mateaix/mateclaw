package vip.mate.tool.image.vision.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.image.ImageCapability;
import vip.mate.tool.image.vision.ImageVisionProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Identity + ordering contract for the OpenAI-compatible vision
 * providers. Verifies each provider exposes a stable id, a sane
 * autoDetectOrder, IMAGE_TO_TEXT capability, and that the auto-detect
 * ordering across all three is monotonically increasing — operators
 * relying on "DashScope wins when both are configured" depend on this.
 */
class VisionProviderIdentityTest {

    private final ModelProviderService modelProviderService = mock(ModelProviderService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DashScopeVisionProvider dashScope() {
        return new DashScopeVisionProvider(modelProviderService, objectMapper);
    }

    private ZhipuVisionProvider zhipu() {
        return new ZhipuVisionProvider(modelProviderService, objectMapper);
    }

    private DoubaoVisionProvider doubao() {
        return new DoubaoVisionProvider(modelProviderService, objectMapper);
    }

    @Test
    @DisplayName("DashScope provider keeps its public id and order")
    void dashScopeIdentity() {
        ImageVisionProvider p = dashScope();
        assertThat(p.id()).isEqualTo("dashscope-vision");
        assertThat(p.label()).isEqualTo("DashScope qwen-vl");
        assertThat(p.autoDetectOrder()).isEqualTo(10);
        assertThat(p.requiresCredential()).isTrue();
        assertThat(p.capabilities()).contains(ImageCapability.IMAGE_TO_TEXT);
    }

    @Test
    @DisplayName("Zhipu provider exposes its own id, slot 20")
    void zhipuIdentity() {
        ImageVisionProvider p = zhipu();
        assertThat(p.id()).isEqualTo("zhipu-vision");
        assertThat(p.label()).isEqualTo("Zhipu GLM-V");
        assertThat(p.autoDetectOrder()).isEqualTo(20);
        assertThat(p.capabilities()).contains(ImageCapability.IMAGE_TO_TEXT);
    }

    @Test
    @DisplayName("Doubao provider exposes its own id, slot 30")
    void doubaoIdentity() {
        ImageVisionProvider p = doubao();
        assertThat(p.id()).isEqualTo("doubao-vision");
        assertThat(p.label()).isEqualTo("Volcano Doubao Vision");
        assertThat(p.autoDetectOrder()).isEqualTo(30);
        assertThat(p.capabilities()).contains(ImageCapability.IMAGE_TO_TEXT);
    }

    @Test
    @DisplayName("auto-detect ordering: DashScope < Zhipu < Doubao")
    void orderingAcrossProviders() {
        List<Integer> orders = List.of(
                dashScope().autoDetectOrder(),
                zhipu().autoDetectOrder(),
                doubao().autoDetectOrder());
        assertThat(orders).isSorted();
        assertThat(orders).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("isAvailable: each provider checks its own model_provider key")
    void availabilityChecksDelegate() {
        SystemSettingsDTO settings = new SystemSettingsDTO();
        when(modelProviderService.isProviderConfigured(anyString())).thenReturn(false);

        assertThat(dashScope().isAvailable(settings)).isFalse();
        assertThat(zhipu().isAvailable(settings)).isFalse();
        assertThat(doubao().isAvailable(settings)).isFalse();

        // Each provider must look up by the right provider_id
        when(modelProviderService.isProviderConfigured("dashscope")).thenReturn(true);
        assertThat(dashScope().isAvailable(settings)).isTrue();
        assertThat(zhipu().isAvailable(settings)).isFalse();
        assertThat(doubao().isAvailable(settings)).isFalse();

        when(modelProviderService.isProviderConfigured("zhipu-cn")).thenReturn(true);
        assertThat(zhipu().isAvailable(settings)).isTrue();
        assertThat(doubao().isAvailable(settings)).isFalse();

        when(modelProviderService.isProviderConfigured("volcengine")).thenReturn(true);
        assertThat(doubao().isAvailable(settings)).isTrue();
    }

    @Test
    @DisplayName("isAvailable returns false when ModelProviderService throws — fail-soft")
    void availabilityFailsSoft() {
        SystemSettingsDTO settings = new SystemSettingsDTO();
        when(modelProviderService.isProviderConfigured(anyString()))
                .thenThrow(new RuntimeException("db down"));

        assertThat(dashScope().isAvailable(settings)).isFalse();
        assertThat(zhipu().isAvailable(settings)).isFalse();
        assertThat(doubao().isAvailable(settings)).isFalse();
    }
}
