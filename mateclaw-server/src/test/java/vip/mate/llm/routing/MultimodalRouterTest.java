package vip.mate.llm.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.routing.model.MultimodalRoutingDecision;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.service.ModelCapabilityService.Modality;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultimodalRouterTest {

    @Mock
    private SystemSettingService systemSettingService;

    @Mock
    private ModelConfigService modelConfigService;

    @Mock
    private ModelCapabilityService capabilityService;

    @InjectMocks
    private MultimodalRouter router;

    private SystemSettingsDTO settings;

    @BeforeEach
    void setUp() {
        settings = new SystemSettingsDTO();
        lenient().when(systemSettingService.getSettings()).thenReturn(settings);
    }

    @Test
    @DisplayName("No attachments → strategy NONE, no reads to settings")
    void noAttachmentsReturnsNone() {
        // No capabilityService stubbing here — the router must short-circuit before
        // touching capabilities when no attachments are present.
        MultimodalRoutingDecision decision = router.route(
                List.of(), chatModel("deepseek", "deepseek-chat", null));
        assertEquals(MultimodalRoutingDecision.Strategy.NONE, decision.strategy());
        assertTrue(decision.skipped().isEmpty());
        assertNull(decision.sidecarModel());
    }

    @Test
    @DisplayName("Primary already supports vision → strategy NONE")
    void primaryCoversVisionReturnsNone() {
        ModelConfigEntity primary = chatModel("zhipu", "glm-4v", "[\"vision\"]");
        when(capabilityService.resolve("glm-4v", "[\"vision\"]"))
                .thenReturn(EnumSet.of(Modality.VISION));

        MultimodalRoutingDecision decision = router.route(List.of(imagePart("a.png")), primary);
        assertEquals(MultimodalRoutingDecision.Strategy.NONE, decision.strategy());
    }

    @Test
    @DisplayName("Image attachment + text-only primary + configured vision sidecar → SIDECAR")
    void textPrimaryImageWithSidecarConfigured() {
        ModelConfigEntity primary = chatModel("deepseek", "deepseek-chat", null);
        ModelConfigEntity vision = chatModel("zhipu", "glm-4v", "[\"vision\"]");
        vision.setId(42L);
        vision.setEnabled(true);

        when(capabilityService.resolve("deepseek-chat", null))
                .thenReturn(EnumSet.of(Modality.TEXT));
        settings.setDefaultVisionModelId(42L);
        when(modelConfigService.getModel(42L)).thenReturn(vision);
        when(capabilityService.supports(eq("glm-4v"), eq("[\"vision\"]"), eq(Modality.VISION)))
                .thenReturn(true);

        MultimodalRoutingDecision decision = router.route(List.of(imagePart("a.png")), primary);

        assertEquals(MultimodalRoutingDecision.Strategy.SIDECAR, decision.strategy());
        assertNotNull(decision.sidecarModel());
        assertEquals(42L, decision.sidecarModel().getId());
        assertTrue(decision.skipped().isEmpty());
    }

    @Test
    @DisplayName("Image + text-only primary + sidecar NOT configured → NONE with skipped reason")
    void textPrimaryImageNoSidecar() {
        ModelConfigEntity primary = chatModel("deepseek", "deepseek-chat", null);
        when(capabilityService.resolve("deepseek-chat", null))
                .thenReturn(EnumSet.of(Modality.TEXT));
        settings.setDefaultVisionModelId(null);

        MultimodalRoutingDecision decision = router.route(List.of(imagePart("a.png")), primary);

        assertEquals(MultimodalRoutingDecision.Strategy.NONE, decision.strategy());
        assertEquals(1, decision.skipped().size());
        assertEquals("vision_model_not_configured", decision.skipped().get(0).reason());
    }

    @Test
    @DisplayName("Image + sidecar configured but model disabled → NONE with vision_model_unavailable")
    void textPrimaryImageSidecarDisabled() {
        ModelConfigEntity primary = chatModel("deepseek", "deepseek-chat", null);
        ModelConfigEntity vision = chatModel("zhipu", "glm-4v", "[\"vision\"]");
        vision.setId(42L);
        vision.setEnabled(false);

        when(capabilityService.resolve("deepseek-chat", null))
                .thenReturn(EnumSet.of(Modality.TEXT));
        settings.setDefaultVisionModelId(42L);
        when(modelConfigService.getModel(42L)).thenReturn(vision);

        MultimodalRoutingDecision decision = router.route(List.of(imagePart("a.png")), primary);

        assertEquals(MultimodalRoutingDecision.Strategy.NONE, decision.strategy());
        assertEquals("vision_model_unavailable", decision.skipped().get(0).reason());
    }

    @Test
    @DisplayName("Video attachment never sidecarred in v1 → NONE with reserved reason")
    void videoAttachmentSkippedInV1() {
        ModelConfigEntity primary = chatModel("deepseek", "deepseek-chat", null);
        when(capabilityService.resolve("deepseek-chat", null))
                .thenReturn(EnumSet.of(Modality.TEXT));

        MultimodalRoutingDecision decision = router.route(List.of(videoPart("b.mp4")), primary);
        assertEquals(MultimodalRoutingDecision.Strategy.NONE, decision.strategy());
        assertEquals(1, decision.skipped().size());
        assertEquals("video_sidecar_not_supported_in_v1", decision.skipped().get(0).reason());
    }

    @Test
    @DisplayName("Configured sidecar that does not actually support VISION → fallback to NONE")
    void sidecarLacksClaimedCapability() {
        ModelConfigEntity primary = chatModel("deepseek", "deepseek-chat", null);
        ModelConfigEntity vision = chatModel("acme", "acme-chat", "[]");
        vision.setId(42L);
        vision.setEnabled(true);

        when(capabilityService.resolve("deepseek-chat", null))
                .thenReturn(EnumSet.of(Modality.TEXT));
        settings.setDefaultVisionModelId(42L);
        when(modelConfigService.getModel(42L)).thenReturn(vision);
        when(capabilityService.supports(anyString(), anyString(), eq(Modality.VISION)))
                .thenReturn(false);

        MultimodalRoutingDecision decision = router.route(List.of(imagePart("a.png")), primary);

        assertEquals(MultimodalRoutingDecision.Strategy.NONE, decision.strategy());
        assertEquals("vision_model_unavailable", decision.skipped().get(0).reason());
    }

    @Test
    @DisplayName("Null primary → routing returns SIDECAR if vision configured, else NONE")
    void nullPrimaryHonorsSidecarConfig() {
        ModelConfigEntity vision = chatModel("zhipu", "glm-4v", "[\"vision\"]");
        vision.setId(42L);
        vision.setEnabled(true);
        settings.setDefaultVisionModelId(42L);
        when(modelConfigService.getModel(42L)).thenReturn(vision);
        when(capabilityService.supports(anyString(), anyString(), eq(Modality.VISION))).thenReturn(true);

        MultimodalRoutingDecision decision = router.route(List.of(imagePart("a.png")), null);
        assertEquals(MultimodalRoutingDecision.Strategy.SIDECAR, decision.strategy());
    }

    private static MessageContentPart imagePart(String fileName) {
        MessageContentPart part = new MessageContentPart();
        part.setType("image");
        part.setContentType("image/png");
        part.setFileName(fileName);
        return part;
    }

    private static MessageContentPart videoPart(String fileName) {
        MessageContentPart part = new MessageContentPart();
        part.setType("video");
        part.setContentType("video/mp4");
        part.setFileName(fileName);
        return part;
    }

    private static ModelConfigEntity chatModel(String provider, String modelName, String modalitiesJson) {
        ModelConfigEntity m = new ModelConfigEntity();
        m.setProvider(provider);
        m.setModelName(modelName);
        m.setModalities(modalitiesJson);
        m.setEnabled(true);
        return m;
    }

}
