package vip.mate.tool.image.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import vip.mate.tool.image.ImageCapability;
import vip.mate.tool.image.ImageModelSpec;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog-shape invariants on the DashScope image model registry. The point of
 * these is not to assert specific model ids — those churn as Aliyun ships /
 * deprecates families — but to enforce that whatever is registered is
 * internally consistent:
 * <ul>
 *   <li>Sync-transport models must hit the multimodal endpoint; async-transport
 *       models must hit the legacy image-generation endpoint.</li>
 *   <li>Edit-capable models must declare a positive {@code maxInputImages}.</li>
 *   <li>The {@code DEFAULT_EDIT_MODEL} must actually support {@link ImageCapability#IMAGE_EDIT}.</li>
 *   <li>Every model spec carries a non-empty endpoint, transport, and modes set.</li>
 * </ul>
 */
@Tag("media-gen")
class DashScopeImageModelsTest {

    @Test
    @DisplayName("every spec has non-null endpoint, transport, and at least one mode")
    void everySpecIsWellFormed() {
        Map<String, ImageModelSpec> all = DashScopeImageModels.all();
        assertFalse(all.isEmpty(), "catalog must not be empty");
        for (Map.Entry<String, ImageModelSpec> e : all.entrySet()) {
            ImageModelSpec spec = e.getValue();
            assertEquals(e.getKey(), spec.id(), "map key must equal spec.id()");
            assertNotNull(spec.endpoint(), spec.id());
            assertFalse(spec.endpoint().isBlank(), spec.id());
            assertNotNull(spec.transport(), spec.id());
            assertNotNull(spec.modes(), spec.id());
            assertFalse(spec.modes().isEmpty(), spec.id());
        }
    }

    @Test
    @DisplayName("transport drives endpoint family (SYNC ⇒ multimodal-generation, ASYNC ⇒ image-generation)")
    void transportMatchesEndpointFamily() {
        for (ImageModelSpec spec : DashScopeImageModels.all().values()) {
            switch (spec.transport()) {
                case SYNC -> assertEquals(DashScopeImageModels.MULTIMODAL_ENDPOINT, spec.endpoint(),
                        "sync model " + spec.id() + " must use multimodal endpoint");
                case ASYNC -> assertEquals(DashScopeImageModels.LEGACY_ASYNC_ENDPOINT, spec.endpoint(),
                        "async model " + spec.id() + " must use legacy endpoint");
            }
        }
    }

    @Test
    @DisplayName("edit-capable specs declare maxInputImages > 0")
    void editCapableSpecsDeclareInputCapacity() {
        for (ImageModelSpec spec : DashScopeImageModels.all().values()) {
            if (spec.supportsEdit()) {
                assertTrue(spec.maxInputImages() > 0,
                        "edit-capable model " + spec.id() + " has maxInputImages=" + spec.maxInputImages());
            }
        }
    }

    @Test
    @DisplayName("DEFAULT_MODEL exists and supports text-to-image (the most common request)")
    void defaultModelExistsAndGenerates() {
        ImageModelSpec spec = DashScopeImageModels.get(DashScopeImageModels.DEFAULT_MODEL);
        assertNotNull(spec);
        assertEquals(DashScopeImageModels.DEFAULT_MODEL, spec.id());
        assertTrue(spec.supportsGenerate(),
                "default model must accept text-to-image requests");
    }

    @Test
    @DisplayName("DEFAULT_EDIT_MODEL exists and actually supports image edit")
    void defaultEditModelExistsAndEdits() {
        ImageModelSpec spec = DashScopeImageModels.get(DashScopeImageModels.DEFAULT_EDIT_MODEL);
        assertNotNull(spec);
        assertTrue(spec.supportsEdit(),
                "DEFAULT_EDIT_MODEL must declare IMAGE_EDIT capability");
    }

    @Test
    @DisplayName("get(unknown) falls back to DEFAULT_MODEL rather than returning null")
    void unknownModelFallsBackToDefault() {
        ImageModelSpec spec = DashScopeImageModels.get("not-a-real-model-id");
        assertEquals(DashScopeImageModels.DEFAULT_MODEL, spec.id());
    }

    @Test
    @DisplayName("get(null) and get(blank) fall back to DEFAULT_MODEL")
    void nullOrBlankModelFallsBackToDefault() {
        assertEquals(DashScopeImageModels.DEFAULT_MODEL, DashScopeImageModels.get(null).id());
        assertEquals(DashScopeImageModels.DEFAULT_MODEL, DashScopeImageModels.get("").id());
        assertEquals(DashScopeImageModels.DEFAULT_MODEL, DashScopeImageModels.get("   ").id());
    }
}
