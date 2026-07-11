package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for cross-KB wikilink targets ({@code [[kbId/slug]]}): parsing and
 * the broken-link exemption. A cross-KB target must never be flagged broken by
 * the single-KB lint (existence can only be checked in the target KB), while a
 * plain single-KB target keeps its exact slug/title resolution.
 */
class WikiLinkServiceCrossKbTest {

    private final WikiLinkService svc = new WikiLinkService(new ObjectMapper());

    @Test
    void parseCrossKb_recognisesNumericPrefix() {
        WikiLinkService.CrossKbRef ref = svc.parseCrossKb("2055137662148763649/photosynthesis");
        assertNotNull(ref);
        assertEquals(2055137662148763649L, ref.kbId());
        assertEquals("photosynthesis", ref.slug());
    }

    @Test
    void parseCrossKb_ignoresPlainSlugAndTitle() {
        assertNull(svc.parseCrossKb("photosynthesis"));
        assertNull(svc.parseCrossKb("Energy Metabolism"));
        // Slug-shaped but non-numeric prefix stays single-KB.
        assertNull(svc.parseCrossKb("chapter/section"));
        // Numeric prefix but empty slug is not a valid cross-KB ref.
        assertNull(svc.parseCrossKb("123/"));
        assertNull(svc.parseCrossKb(null));
    }

    @Test
    void computeBrokenLinks_exemptsCrossKbTargets() {
        Set<String> outlinks = svc.extractOutlinks(
                "See [[123/photosynthesis]] and [[missing-local]] and [[known-local]].");
        // Only this KB's own slug is resolvable.
        Set<String> resolvable = Set.of("known-local");
        List<String> broken = svc.computeBrokenLinks(outlinks, resolvable);
        // Cross-KB target exempt; local unknown flagged; local known resolves.
        assertTrue(broken.contains("missing-local"));
        assertFalse(broken.contains("123/photosynthesis"),
                "cross-KB target must not be flagged broken by the single-KB lint");
        assertFalse(broken.contains("known-local"));
    }

    @Test
    void extractOutlinks_keepsCrossKbTargetVerbatim() {
        Set<String> outlinks = svc.extractOutlinks("Ref [[456/Some-Page|display]].");
        // Lowercased, alias stripped, prefix preserved.
        assertTrue(outlinks.contains("456/some-page"));
    }
}
