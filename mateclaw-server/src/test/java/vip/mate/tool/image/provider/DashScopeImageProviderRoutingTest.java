package vip.mate.tool.image.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import vip.mate.tool.image.ImageGenerationRequest;
import vip.mate.tool.image.ImageModelSpec;
import vip.mate.tool.image.ImageReference;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level checks on the per-request model routing in
 * {@link DashScopeImageProvider#resolveSpec(ImageGenerationRequest)}. The HTTP
 * surface is excluded — that needs a mock server. The routing decision is the
 * part that's easy to break and easy to verify cheaply.
 */
@Tag("media-gen")
class DashScopeImageProviderRoutingTest {

    private final DashScopeImageProvider provider = new DashScopeImageProvider(null, new ObjectMapper());

    @Test
    @DisplayName("text-to-image request with no model returns DEFAULT_MODEL")
    void noModelNoInputs_resolvesDefault() {
        ImageGenerationRequest req = ImageGenerationRequest.builder().prompt("hi").build();
        ImageModelSpec spec = provider.resolveSpec(req);
        assertEquals(DashScopeImageModels.DEFAULT_MODEL, spec.id());
    }

    @Test
    @DisplayName("text-to-image with explicit model id returns that exact spec")
    void explicitModel_resolvesSameId() {
        ImageGenerationRequest req = ImageGenerationRequest.builder()
                .prompt("hi").model("z-image-turbo").build();
        ImageModelSpec spec = provider.resolveSpec(req);
        assertEquals("z-image-turbo", spec.id());
    }

    @Test
    @DisplayName("edit request with edit-capable model keeps that model")
    void editCapableModel_keepsModel() {
        ImageGenerationRequest req = ImageGenerationRequest.builder()
                .prompt("change the background")
                .model("qwen-image-edit")
                .inputImages(List.of(new ImageReference(new byte[]{1}, "image/png", "x.png", "test")))
                .build();
        ImageModelSpec spec = provider.resolveSpec(req);
        assertEquals("qwen-image-edit", spec.id());
        assertTrue(spec.supportsEdit());
    }

    @Test
    @DisplayName("edit request with non-edit-capable model falls back to DEFAULT_EDIT_MODEL")
    void editRequestOnNonEditModel_fallsBackToEditDefault() {
        ImageGenerationRequest req = ImageGenerationRequest.builder()
                .prompt("change the background")
                .model("z-image-turbo") // text-to-image only
                .inputImages(List.of(new ImageReference(new byte[]{1}, "image/png", "x.png", "test")))
                .build();
        ImageModelSpec spec = provider.resolveSpec(req);
        assertEquals(DashScopeImageModels.DEFAULT_EDIT_MODEL, spec.id());
        assertTrue(spec.supportsEdit(),
                "fallback target must actually support edits — that's the point of the fallback");
        assertNotEquals("z-image-turbo", spec.id());
    }

    @Test
    @DisplayName("edit request with no model and inputs falls back to DEFAULT_EDIT_MODEL")
    void editRequestNoModel_fallsBackToEditDefault() {
        ImageGenerationRequest req = ImageGenerationRequest.builder()
                .prompt("change the background")
                .inputImages(List.of(new ImageReference(new byte[]{1}, "image/png", "x.png", "test")))
                .build();
        ImageModelSpec spec = provider.resolveSpec(req);
        // DEFAULT_MODEL is a legacy text-only async model — edit request must not land there.
        assertEquals(DashScopeImageModels.DEFAULT_EDIT_MODEL, spec.id());
        assertTrue(spec.supportsEdit());
    }

    @Test
    @DisplayName("provider declares both TEXT_TO_IMAGE and IMAGE_EDIT capabilities at provider level")
    void providerDeclaresBothCapabilities() {
        var caps = provider.capabilities();
        assertTrue(caps.contains(vip.mate.tool.image.ImageCapability.TEXT_TO_IMAGE));
        assertTrue(caps.contains(vip.mate.tool.image.ImageCapability.IMAGE_EDIT));
    }
}
