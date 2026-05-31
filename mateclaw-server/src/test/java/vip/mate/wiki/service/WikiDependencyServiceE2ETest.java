package vip.mate.wiki.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the dependency graph and stale propagation against H2:
 * valid fact dependencies are recorded, illegal ones rejected, and updating a
 * fact page marks its dependents stale.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
class WikiDependencyServiceE2ETest {

    @Autowired
    private WikiDependencyService dependencyService;
    @Autowired
    private WikiPageService pageService;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private WikiPageEntity factPage(long kb, String slug) {
        WikiPageEntity p = pageService.createPage(kb, slug, "Fact " + slug, "body", "s", "[1]", "episode");
        pageService.setLayerAndDependencies(p.getId(), "fact", null);
        return pageService.getBySlug(kb, slug);
    }

    private WikiPageEntity experiencePage(long kb, String slug) {
        WikiPageEntity p = pageService.createPage(kb, slug, "Exp " + slug, "body", "s", "[1]", "analysis");
        pageService.setLayerAndDependencies(p.getId(), "experience", null);
        return pageService.getBySlug(kb, slug);
    }

    @Test
    void validFactDependency_isRecorded_andStalePropagates() {
        long kb = SEQ.incrementAndGet();
        WikiPageEntity fact = factPage(kb, "fact-" + kb);
        WikiPageEntity exp = experiencePage(kb, "exp-" + kb);

        List<String> rejected = dependencyService.setDependencies(kb, exp.getId(), List.of(fact.getId()));
        assertTrue(rejected.isEmpty(), () -> "unexpected rejections: " + rejected);

        // Fact page changes -> dependent experience page goes stale.
        int marked = dependencyService.markDependentsStale(kb, fact.getId(), "fact body changed");
        assertEquals(1, marked);

        WikiPageEntity reloaded = pageService.getBySlug(kb, "exp-" + kb);
        assertEquals(1, reloaded.getStale());
        assertTrue(reloaded.getStaleReasonJson().contains(String.valueOf(fact.getId())));

        // Regenerating clears the flag.
        pageService.clearStale(reloaded.getId());
        assertEquals(0, pageService.getBySlug(kb, "exp-" + kb).getStale());
    }

    @Test
    void experienceTargetDependency_isRejected() {
        long kb = SEQ.incrementAndGet();
        WikiPageEntity expA = experiencePage(kb, "expA-" + kb);
        WikiPageEntity expB = experiencePage(kb, "expB-" + kb);

        // Depending on an experience page (not a fact) must be rejected.
        List<String> rejected = dependencyService.setDependencies(kb, expA.getId(), List.of(expB.getId()));
        assertFalse(rejected.isEmpty());
        assertTrue(rejected.get(0).contains("not a fact-layer"));

        // No stale propagation since no edge was created.
        assertEquals(0, dependencyService.markDependentsStale(kb, expB.getId(), "x"));
    }

    @Test
    void crossKbDependency_isRejected() {
        long kbA = SEQ.incrementAndGet();
        long kbB = SEQ.incrementAndGet();
        WikiPageEntity factOther = factPage(kbB, "factB-" + kbB);
        WikiPageEntity exp = experiencePage(kbA, "expA2-" + kbA);

        List<String> rejected = dependencyService.setDependencies(kbA, exp.getId(), List.of(factOther.getId()));
        assertFalse(rejected.isEmpty());
        assertTrue(rejected.get(0).contains("not found in this KB"));
    }
}
