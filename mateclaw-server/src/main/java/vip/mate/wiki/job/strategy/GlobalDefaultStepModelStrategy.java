package vip.mate.wiki.job.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

/**
 * Final fallback strategy — uses the system default model for every step.
 * Cheap steps that want a lighter model are handled earlier by
 * {@link WikiLightModelStrategy} (Order 2) when a light model is configured;
 * this strategy is the last resort and keeps all steps on the system default.
 */
@Component
@Order(4)
@RequiredArgsConstructor
public class GlobalDefaultStepModelStrategy implements WikiStepModelStrategy {

    private final ModelConfigService modelConfigService;

    @Override
    public boolean supports(WikiJobStep step) { return true; }

    @Override
    public Long selectModelId(WikiProcessingJobEntity job, WikiKnowledgeBaseEntity kb, WikiJobStep step) {
        return modelConfigService.getDefaultModel().getId();
    }
}
