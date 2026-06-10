package vip.mate.llm.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.routing.model.MultimodalRoutingDecision;
import vip.mate.llm.routing.model.MultimodalRoutingDecision.SkippedAttachment;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.service.ModelCapabilityService.Modality;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Decides how to handle attachments whose modality outruns the agent's primary model.
 *
 * <p>The router is a pure decision step: it inspects the parts list and the primary
 * model's capability set, then returns a {@link MultimodalRoutingDecision}. Caller is
 * responsible for executing the decision (e.g. invoking the caption service when
 * strategy is SIDECAR).
 *
 * <p>v1 only supports image sidecar. Video attachments fall through to the NONE
 * branch with an explanatory skip reason — the next iteration will add a video
 * captioning path once a strategy for frame sampling is in place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultimodalRouter {

    private final SystemSettingService systemSettingService;
    private final ModelConfigService modelConfigService;
    private final ModelCapabilityService capabilityService;

    public MultimodalRoutingDecision route(List<MessageContentPart> parts, ModelConfigEntity primary) {
        Set<Modality> required = collectRequiredModalities(parts);
        if (required.isEmpty()) return MultimodalRoutingDecision.none();

        EnumSet<Modality> primaryCaps = primary == null
                ? EnumSet.noneOf(Modality.class)
                : capabilityService.resolve(primary.getModelName(), primary.getModalities());
        if (primaryCaps.containsAll(required)) return MultimodalRoutingDecision.none();

        EnumSet<Modality> missing = EnumSet.copyOf(required);
        missing.removeAll(primaryCaps);

        List<SkippedAttachment> skipped = new ArrayList<>();
        ModelConfigEntity sidecarModel = null;

        // VISION sidecar: resolve configured default vision model.
        if (missing.contains(Modality.VISION)) {
            ModelConfigEntity candidate = resolveSidecar(Modality.VISION);
            if (candidate != null) {
                sidecarModel = candidate;
            } else {
                String reason = describeMissingSidecar(Modality.VISION);
                for (MessageContentPart p : imageParts(parts)) {
                    skipped.add(new SkippedAttachment("image", p.getFileName(), reason));
                }
            }
        }

        // VIDEO: v1 has no sidecar implementation. Mark as skipped so the UI can
        // tell the user to switch to a video-capable primary model. Reserved for
        // a follow-up RFC.
        if (missing.contains(Modality.VIDEO)) {
            for (MessageContentPart p : videoParts(parts)) {
                skipped.add(new SkippedAttachment("video", p.getFileName(),
                        "video_sidecar_not_supported_in_v1"));
            }
        }

        if (sidecarModel != null) {
            return MultimodalRoutingDecision.sidecar(sidecarModel, required, missing);
        }
        return MultimodalRoutingDecision.noneWithSkipped(required, missing, skipped);
    }

    private Set<Modality> collectRequiredModalities(List<MessageContentPart> parts) {
        if (parts == null || parts.isEmpty()) return Set.of();
        EnumSet<Modality> required = EnumSet.noneOf(Modality.class);
        for (MessageContentPart part : parts) {
            if (part == null) continue;
            String type = part.getType();
            String contentType = part.getContentType();
            if (isImagePart(type, contentType)) required.add(Modality.VISION);
            else if (isVideoPart(type, contentType)) required.add(Modality.VIDEO);
            else if (isAudioPart(type, contentType)) required.add(Modality.AUDIO);
        }
        return required;
    }

    private boolean isImagePart(String type, String contentType) {
        if ("image".equals(type)) return true;
        return "file".equals(type) && contentType != null && contentType.startsWith("image/");
    }

    private boolean isVideoPart(String type, String contentType) {
        if ("video".equals(type)) return true;
        return "file".equals(type) && contentType != null && contentType.startsWith("video/");
    }

    private boolean isAudioPart(String type, String contentType) {
        if ("audio".equals(type)) return true;
        return "file".equals(type) && contentType != null && contentType.startsWith("audio/");
    }

    private List<MessageContentPart> imageParts(List<MessageContentPart> parts) {
        return parts.stream()
                .filter(p -> p != null && isImagePart(p.getType(), p.getContentType()))
                .toList();
    }

    private List<MessageContentPart> videoParts(List<MessageContentPart> parts) {
        return parts.stream()
                .filter(p -> p != null && isVideoPart(p.getType(), p.getContentType()))
                .toList();
    }

    /**
     * Resolve the configured sidecar model for a modality. Returns null only when:
     *   - the setting is empty / blank;
     *   - the referenced row no longer exists or has been disabled.
     * The caller treats null as "ask the user to configure one."
     * <p>
     * An explicit sidecar selection is treated as the user's own capability
     * declaration: a provider-compatible model can be vision-capable in practice
     * even when the built-in heuristics don't recognize its name and it carries no
     * declared {@code modalities}. Rejecting such a model here made it impossible to
     * use a perfectly good compatible-mode vision model as the sidecar. We therefore
     * honour the explicit choice and only emit a diagnostic when the heuristics
     * can't confirm it — a wrong pick degrades gracefully (the caption call fails and
     * the attachment is reported as un-processed) rather than being silently ignored.
     */
    private ModelConfigEntity resolveSidecar(Modality modality) {
        SystemSettingsDTO settings = systemSettingService.getSettings();
        Long modelId = switch (modality) {
            case VISION -> settings.getDefaultVisionModelId();
            case VIDEO -> settings.getDefaultVideoModelId();
            default -> null;
        };
        if (modelId == null) return null;
        ModelConfigEntity model;
        try {
            model = modelConfigService.getModel(modelId);
        } catch (Exception e) {
            log.debug("Configured sidecar model id={} could not be loaded: {}", modelId, e.getMessage());
            return null;
        }
        if (model == null || !Boolean.TRUE.equals(model.getEnabled())) return null;
        if (!capabilityService.supports(model.getModelName(), model.getModalities(), modality)) {
            log.info("Configured sidecar model {}/{} is not recognized as {}-capable by the "
                            + "built-in heuristics; honouring the explicit selection anyway",
                    model.getProvider(), model.getModelName(), modality.name().toLowerCase());
        }
        return model;
    }

    private String describeMissingSidecar(Modality modality) {
        SystemSettingsDTO settings = systemSettingService.getSettings();
        Long configured = modality == Modality.VISION
                ? settings.getDefaultVisionModelId()
                : settings.getDefaultVideoModelId();
        if (configured == null) return modality.name().toLowerCase() + "_model_not_configured";
        return modality.name().toLowerCase() + "_model_unavailable";
    }
}
