package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.job.WikiModelRoutingService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * RFC-051 follow-up: pin the enrich prompt's "already-used" decoration so
 * the LLM stops spinning cycles re-proposing wraps that the applier would
 * just skip.
 */
class WikiLinkEnrichmentIndexAnnotateTest {

    private WikiLinkEnrichmentService svc;

    @BeforeEach
    void setUp() {
        // We never call enrich here, just the package-private static counter
        // and the decorate helper. Mocks are placeholders to satisfy the ctor.
        svc = new WikiLinkEnrichmentService(
                mock(WikiPageService.class),
                mock(WikiModelRoutingService.class),
                new WikiProperties(),
                new ObjectMapper());
    }

    @Test
    @DisplayName("countWikilinkSlugs counts both [[slug]] and [[slug|label]] forms")
    void countsBothForms() {
        String content = "Mention [[spring-ai]] then [[spring-ai|Spring AI]] then [[linux]] once.";
        Map<String, Integer> c = WikiLinkEnrichmentService.countWikilinkSlugs(content);
        assertEquals(2, c.get("spring-ai"));
        assertEquals(1, c.get("linux"));
    }

    @Test
    @DisplayName("decorate annotates only slugs already used; untouched slugs render unchanged")
    void decorateAnnotatesOnlyUsed() {
        String index = """
                spring-ai → Spring AI
                linux → Linux
                ssh → SSH""";
        String content = "Use [[spring-ai|Spring AI]] and [[spring-ai]] but no Linux yet.";
        String result = svc.decorateIndexWithUsage(index, content);
        assertTrue(result.contains("spring-ai → Spring AI (used 2× already)"),
                "spring-ai should carry usage count. Got:\n" + result);
        assertTrue(result.contains("linux → Linux\n") || result.endsWith("linux → Linux"),
                "linux still has 0 occurrences, should render unchanged");
        assertTrue(result.contains("ssh → SSH"),
                "ssh untouched");
    }

    @Test
    @DisplayName("decorate is a no-op when no wikilinks exist yet")
    void decorateNoopOnFreshPage() {
        String index = "spring-ai → Spring AI\nlinux → Linux";
        String result = svc.decorateIndexWithUsage(index, "Plain prose with no wikilinks.");
        assertEquals(index, result);
    }
}
