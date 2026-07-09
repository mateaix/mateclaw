package vip.mate.wiki.job.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.system.service.SystemSettingService;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.WikiKbConfig;
import vip.mate.wiki.job.WikiKbConfigParser;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Routes the cheap, high-volume wiki steps (route / enrich / summary / entity
 * extraction) to a lightweight chat model so they don't bill at the premium
 * page-generation model's rate.
 *
 * <p>The light model is resolved as: per-KB {@code wikiLightModelId} →
 * system-level {@code wiki.lightModelId} setting. When neither is configured the
 * strategy returns {@code null} and routing falls through to the existing chain
 * ({@code wikiDefaultModelId} → system default), so behavior is unchanged until
 * an admin opts in.
 *
 * <p>Order is between the explicit per-step override
 * ({@link KbConfigStepModelStrategy}, Order 1) and the KB default
 * ({@link KbDefaultModelStrategy}, Order 3): once a light model is configured it
 * takes precedence over the KB default for the cheap steps, but a KB can still
 * pin a specific model on any step via {@code stepModels.<step>}. Strong steps
 * (create_page / merge_page) are not handled here.
 */
@Slf4j
@Component
@Order(2)
public class WikiLightModelStrategy implements WikiStepModelStrategy {

    /** System-setting key for the global lightweight wiki model id. */
    static final String SETTING_KEY = "wiki.lightModelId";

    /** Cheap, high-volume steps eligible for the lightweight model. */
    private static final Set<WikiJobStep> CHEAP_STEPS = EnumSet.of(
            WikiJobStep.ROUTE, WikiJobStep.ENRICH, WikiJobStep.SUMMARY, WikiJobStep.ENTITY_EXTRACTION);

    private final ObjectMapper objectMapper;
    private final SystemSettingService systemSettingService;

    public WikiLightModelStrategy(ObjectMapper objectMapper, SystemSettingService systemSettingService) {
        this.objectMapper = objectMapper;
        this.systemSettingService = systemSettingService;
    }

    @Override
    public boolean supports(WikiJobStep step) {
        return CHEAP_STEPS.contains(step);
    }

    @Override
    public Long selectModelId(WikiProcessingJobEntity job, WikiKnowledgeBaseEntity kb, WikiJobStep step) {
        if (!CHEAP_STEPS.contains(step)) {
            return null;
        }
        Long perKb = perKbLightModel(kb);
        if (perKb != null) {
            return perKb;
        }
        return systemLightModel();
    }

    private Long perKbLightModel(WikiKnowledgeBaseEntity kb) {
        if (kb == null || kb.getConfigContent() == null) {
            return null;
        }
        WikiKbConfig config = WikiKbConfigParser.parse(objectMapper, kb.getConfigContent());
        return config != null ? config.getWikiLightModelId() : null;
    }

    private Long systemLightModel() {
        String raw = systemSettingService.getString(SETTING_KEY, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("[WikiLightModel] Invalid {} setting (not a model id): {}", SETTING_KEY, raw);
            return null;
        }
    }
}
