package vip.mate.wiki.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import vip.mate.wiki.model.WikiPageEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the structured-metadata persistence path against H2: applyMetadata
 * writes the metadata columns without disturbing the page content (partial
 * column update).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WikiPageMetadataE2ETest {

    @Autowired
    private WikiPageService pageService;

    @Test
    void applyMetadata_persistsColumns_withoutTouchingContent() {
        long kb = 6001L;
        WikiPageEntity page = pageService.createPage(kb, "episode-x", "Episode X",
                "## Body\n\noriginal content", "summary", "[1]", "episode");
        assertNotNull(page.getId());

        pageService.applyMetadata(page.getId(),
                "{\"event_date\":\"2024-09-18\"}", "ok", null, 3);

        WikiPageEntity loaded = pageService.getBySlug(kb, "episode-x");
        assertEquals("{\"event_date\":\"2024-09-18\"}", loaded.getMetadataJson());
        assertEquals("ok", loaded.getMetadataValidationStatus());
        assertEquals(3, loaded.getProfileVersion());
        // Partial update must not have wiped content / summary.
        assertEquals("## Body\n\noriginal content", loaded.getContent());
        assertEquals("summary", loaded.getSummary());
    }

    @Test
    void applyMetadata_warningStatusAndJson() {
        long kb = 6002L;
        WikiPageEntity page = pageService.createPage(kb, "episode-y", "Episode Y",
                "body", "summary", "[1]", "episode");

        pageService.applyMetadata(page.getId(),
                "{\"event_date\":\"bad\"}", "warning",
                "[{\"field\":\"event_date\",\"reason\":\"expected ISO date YYYY-MM-DD\"}]", 1);

        WikiPageEntity loaded = pageService.getBySlug(kb, "episode-y");
        assertEquals("warning", loaded.getMetadataValidationStatus());
        assertNotNull(loaded.getMetadataValidationJson());
    }
}
