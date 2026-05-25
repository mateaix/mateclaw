package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.dto.EnrichmentBatchPlan;
import vip.mate.wiki.job.WikiModelRoutingService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * RFC-051 follow-up: pin parseBatchPlan's tolerance for the shapes weak
 * models actually emit.
 */
class WikiEnrichmentBatchParseTest {

    private WikiLinkEnrichmentService svc;

    @BeforeEach
    void setUp() {
        svc = new WikiLinkEnrichmentService(
                mock(WikiPageService.class),
                mock(WikiModelRoutingService.class),
                new WikiProperties(),
                new ObjectMapper());
    }

    @Test
    @DisplayName("happy path: parses well-formed multi-page plan")
    void parsesGoodBatch() {
        String json = """
                {
                  "plans": {
                    "spring-ai": {
                      "replacements": [
                        {"original": "Spring AI", "replacement": "[[spring-ai|Spring AI]]", "occurrence": 1}
                      ]
                    },
                    "linux": {
                      "replacements": []
                    }
                  }
                }
                """;
        EnrichmentBatchPlan p = svc.parseBatchPlan(json);
        assertNotNull(p);
        assertEquals(2, p.plans().size());
        assertEquals(1, p.plans().get("spring-ai").replacements().size());
        assertTrue(p.plans().get("linux").replacements().isEmpty());
    }

    @Test
    @DisplayName("tolerates fenced code block wrapper that some models love")
    void parsesCodeFenced() {
        String json = """
                ```json
                {
                  "plans": {
                    "x": {"replacements": [{"original": "X", "replacement": "[[x|X]]", "occurrence": 1}]}
                  }
                }
                ```""";
        EnrichmentBatchPlan p = svc.parseBatchPlan(json);
        assertNotNull(p);
        assertEquals(1, p.plans().size());
    }

    @Test
    @DisplayName("tolerates stray prose around the JSON object")
    void parsesWithSurroundingProse() {
        String json = "Sure! Here's the plan:\n{\"plans\":{\"x\":{\"replacements\":[]}}}\nLet me know if you need adjustments.";
        EnrichmentBatchPlan p = svc.parseBatchPlan(json);
        assertNotNull(p);
        assertEquals(1, p.plans().size());
    }

    @Test
    @DisplayName("returns empty (not null) when plans field is missing")
    void missingPlansFieldYieldsEmpty() {
        EnrichmentBatchPlan p = svc.parseBatchPlan("{}");
        assertNotNull(p);
        assertTrue(p.isEmpty());
    }

    @Test
    @DisplayName("garbage input returns null so caller can skip cleanly")
    void garbageReturnsNull() {
        assertNull(svc.parseBatchPlan(""));
        assertNull(svc.parseBatchPlan("not json at all"));
    }
}
