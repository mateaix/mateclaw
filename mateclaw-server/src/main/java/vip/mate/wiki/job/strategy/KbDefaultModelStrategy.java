package vip.mate.wiki.job.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.WikiKbConfig;
import vip.mate.wiki.job.WikiKbConfigParser;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

/**
 * RFC-051 PR-1a: resolves the KB-level default chat model
 * ({@link WikiKbConfig#getWikiDefaultModelId()}). Sits below the per-step
 * override ({@link KbConfigStepModelStrategy}, Order 1) and the cheap-step light
 * model ({@link WikiLightModelStrategy}, Order 2), and above the system-wide
 * default ({@link GlobalDefaultStepModelStrategy}, Order 4), yielding the chain:
 *
 * <pre>
 *   stepModels[step] -&gt; (light model for cheap steps) -&gt; wikiDefaultModelId -&gt; system default
 * </pre>
 *
 * The frontend has long written {@code wikiDefaultModelId} into the KB config
 * JSON, but no Java code consumed it before this strategy existed.
 */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
public class KbDefaultModelStrategy implements WikiStepModelStrategy {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(WikiJobStep step) { return true; }

    @Override
    public Long selectModelId(WikiProcessingJobEntity job, WikiKnowledgeBaseEntity kb, WikiJobStep step) {
        if (kb == null || kb.getConfigContent() == null) return null;
        WikiKbConfig config = WikiKbConfigParser.parse(objectMapper, kb.getConfigContent());
        return config != null ? config.getWikiDefaultModelId() : null;
    }
}
