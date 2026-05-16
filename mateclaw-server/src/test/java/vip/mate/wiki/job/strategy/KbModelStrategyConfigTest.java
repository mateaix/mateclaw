package vip.mate.wiki.job.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.job.WikiJobStep;
import vip.mate.wiki.job.model.WikiProcessingJobEntity;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KbModelStrategyConfigTest {

    @Test
    void defaultModelStrategyReadsMarkdownFrontmatter() {
        WikiKnowledgeBaseEntity kb = kb("""
                ---
                wikiDefaultModelId: 12345
                ---
                # Wiki Processing Rules
                """);

        Long modelId = new KbDefaultModelStrategy(new ObjectMapper())
                .selectModelId(job(), kb, WikiJobStep.ROUTE);

        assertEquals(12345L, modelId);
    }

    @Test
    void stepModelStrategyReadsDottedFrontmatterKey() {
        WikiKnowledgeBaseEntity kb = kb("""
                ---
                stepModels.heavy_ingest.create_page: 67890
                ---
                # Wiki Processing Rules
                """);

        Long modelId = new KbConfigStepModelStrategy(new ObjectMapper())
                .selectModelId(job(), kb, WikiJobStep.CREATE_PAGE);

        assertEquals(67890L, modelId);
    }

    @Test
    void plainMarkdownConfigIsTreatedAsEmptyConfig() {
        WikiKnowledgeBaseEntity kb = kb("# Wiki Processing Rules\n\nNo machine config here.");

        Long modelId = new KbDefaultModelStrategy(new ObjectMapper())
                .selectModelId(job(), kb, WikiJobStep.ROUTE);

        assertNull(modelId);
    }

    private static WikiKnowledgeBaseEntity kb(String configContent) {
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(7L);
        kb.setConfigContent(configContent);
        return kb;
    }

    private static WikiProcessingJobEntity job() {
        WikiProcessingJobEntity job = new WikiProcessingJobEntity();
        job.setJobType("heavy_ingest");
        return job;
    }
}
