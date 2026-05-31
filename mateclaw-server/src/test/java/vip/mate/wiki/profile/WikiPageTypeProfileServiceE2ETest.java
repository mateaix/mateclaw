package vip.mate.wiki.profile;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.wiki.model.WikiPageTypeProfileEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link WikiPageTypeProfileService} CRUD against H2:
 * upsert keeps a single enabled row (no generated-column violation), reset
 * falls back to the default, and JSON validation reports structural issues.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999"
        }
)
class WikiPageTypeProfileServiceE2ETest {

    @Autowired
    private WikiPageTypeProfileService service;

    // Persistent file-DB isolation: fresh kb ids per test.
    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private static final String EPISODE_JSON =
            "{\"version\":1,\"pageTypes\":{\"episode\":{\"label\":\"Episode\"}}}";

    @Test
    void saveThenResolve_roundTrips() {
        long kb = SEQ.incrementAndGet();
        service.saveProfile(kb, "liquidity", EPISODE_JSON);

        WikiPageTypeProfileEntity row = service.findEnabledRow(kb);
        assertNotNull(row);
        assertEquals("liquidity", row.getName());
        assertEquals(1, row.getVersion());
        assertTrue(service.resolveProfile(kb).hasPageType("episode"));
    }

    @Test
    void saveTwice_upsertsInPlaceAndBumpsVersion() {
        long kb = SEQ.incrementAndGet();
        service.saveProfile(kb, "v1", EPISODE_JSON);
        // Saving again must update the single enabled row, not insert a second
        // (which would violate the one-enabled-per-KB generated-column UNIQUE).
        service.saveProfile(kb, "v1",
                "{\"version\":1,\"pageTypes\":{\"episode\":{\"label\":\"E\"},\"pattern\":{\"label\":\"P\"}}}");

        WikiPageTypeProfileEntity row = service.findEnabledRow(kb);
        assertEquals(2, row.getVersion());
        assertTrue(service.resolveProfile(kb).hasPageType("pattern"));
    }

    @Test
    void resetToDefault_removesRowAndFallsBack() {
        long kb = SEQ.incrementAndGet();
        service.saveProfile(kb, "custom", EPISODE_JSON);
        assertNotNull(service.findEnabledRow(kb));

        service.resetToDefault(kb);

        assertNull(service.findEnabledRow(kb));
        // Resolution now returns the built-in default.
        assertTrue(service.resolveProfile(kb).hasPageType("concept"));
        assertFalse(service.resolveProfile(kb).hasPageType("episode"));
    }

    @Test
    void invalidConfig_isRejectedOnSave() {
        try {
            service.saveProfile(SEQ.incrementAndGet(), "bad", "{ not json");
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void validateProfileJson_reportsIssues() {
        assertTrue(service.validateProfileJson(EPISODE_JSON).isEmpty());

        List<String> noTypes = service.validateProfileJson("{\"pageTypes\":{}}");
        assertFalse(noTypes.isEmpty());

        List<String> badEnum = service.validateProfileJson(
                "{\"pageTypes\":{\"x\":{\"schema\":{\"f\":{\"type\":\"enum\"}}}}}");
        assertTrue(badEnum.stream().anyMatch(s -> s.contains("enum")));

        List<String> badType = service.validateProfileJson(
                "{\"pageTypes\":{\"x\":{\"schema\":{\"f\":{\"type\":\"banana\"}}}}}");
        assertTrue(badType.stream().anyMatch(s -> s.contains("unknown field type")));
    }
}
