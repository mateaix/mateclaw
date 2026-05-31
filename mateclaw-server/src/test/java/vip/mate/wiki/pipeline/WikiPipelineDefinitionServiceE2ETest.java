package vip.mate.wiki.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.wiki.model.WikiPipelineDefinitionEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E for {@link WikiPipelineDefinitionService}: YAML/JSON parsing, structural
 * validation, and upsert/list/delete against H2.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
class WikiPipelineDefinitionServiceE2ETest {

    @Autowired
    private WikiPipelineDefinitionService service;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private static final String YAML = """
            name: episode-to-pattern
            owner_agent: 2055137662148763649
            trigger:
              type: page_type_count
              page_type: episode
              threshold: 20
              dedup_window_seconds: 3600
            steps:
              - id: summarize
                executor: llm
                prompt: pattern-analysis
              - id: enrich
                executor: skill
                skill: wiki-link-enrich
            """;

    @Test
    void parsesYamlAndUpserts() {
        long kb = SEQ.incrementAndGet();
        WikiPipelineDefinitionEntity def = service.saveFromConfig(kb, YAML, true);
        assertNotNull(def.getId());
        assertEquals("episode-to-pattern", def.getName());
        assertEquals(2055137662148763649L, def.getOwnerAgentId());
        assertEquals("page_type_count", def.getTriggerType());
        assertEquals(3600, def.getDedupWindowSeconds());
        assertTrue(def.getTriggerConfigJson().contains("episode"));
        assertTrue(def.getStepsJson().contains("wiki-link-enrich"));

        // upsert: same name → update in place, not a second row
        service.saveFromConfig(kb, YAML.replace("threshold: 20", "threshold: 40"), true);
        List<WikiPipelineDefinitionEntity> all = service.list(kb);
        assertEquals(1, all.size());
        assertTrue(all.get(0).getTriggerConfigJson().contains("40"));

        service.delete(def.getId());
        assertNull(service.get(def.getId()));
    }

    @Test
    void parsesJson() {
        long kb = SEQ.incrementAndGet();
        String json = "{\"name\":\"p\",\"owner_agent\":42,\"trigger\":{\"type\":\"page_created\"},"
                + "\"steps\":[{\"id\":\"s\",\"executor\":\"llm\",\"prompt\":\"x\"}]}";
        WikiPipelineDefinitionEntity def = service.saveFromConfig(kb, json, false);
        assertEquals("page_created", def.getTriggerType());
    }

    @Test
    void validation_reportsIssues() {
        assertTrue(service.validateConfig(YAML, true).isEmpty());

        // missing name + unknown trigger + python step
        String bad = """
                owner_agent: 1
                trigger:
                  type: nope
                steps:
                  - id: x
                    executor: python
                """;
        List<String> issues = service.validateConfig(bad, true);
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(s -> s.contains("name")));
        assertTrue(issues.stream().anyMatch(s -> s.contains("trigger type")));
        assertTrue(issues.stream().anyMatch(s -> s.contains("python")));
    }
}
