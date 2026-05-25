package vip.mate.wiki.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.dto.EnrichmentPlan;
import vip.mate.wiki.dto.EnrichmentReplacement;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-051 PR-5b: pin the enrichment applier's invariants.
 * <p>
 * Most failures here are silent corruptions — paragraph drops, translations,
 * tone shifts. The applier guarantees they can't happen by construction;
 * these tests guarantee the applier itself doesn't drift.
 */
class WikiEnrichmentApplierTest {

    private static EnrichmentPlan plan(EnrichmentReplacement... rs) {
        return new EnrichmentPlan(List.of(rs));
    }

    @Test
    @DisplayName("happy path: bare wikilink wraps when original equals the slug")
    void wrapsFirstOccurrenceBareWhenSlugMatches() {
        String src = "Containers run on linux nodes; on linux the daemon is systemd.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(src,
                plan(new EnrichmentReplacement("linux", "[[linux]]", 1)));
        assertInstanceOf(WikiEnrichmentApplier.Result.Applied.class, r);
        assertEquals("Containers run on [[linux]] nodes; on linux the daemon is systemd.", r.content());
    }

    @Test
    @DisplayName("alias form is the only safe way to wrap when display differs from slug")
    void aliasRequiredWhenDisplayDiffersFromSlug() {
        // Bare [[spring-ai]] would change "Spring AI" → "spring-ai" — that's a visible
        // mutation, so the applier rejects it. The alias form preserves the original text.
        WikiEnrichmentApplier.Result rejected = WikiEnrichmentApplier.apply("Spring AI rocks",
                plan(new EnrichmentReplacement("Spring AI", "[[spring-ai]]", 1)));
        assertInstanceOf(WikiEnrichmentApplier.Result.Rejected.class, rejected);

        WikiEnrichmentApplier.Result applied = WikiEnrichmentApplier.apply("Spring AI rocks",
                plan(new EnrichmentReplacement("Spring AI", "[[spring-ai|Spring AI]]", 1)));
        assertInstanceOf(WikiEnrichmentApplier.Result.Applied.class, applied);
        assertEquals("[[spring-ai|Spring AI]] rocks", applied.content());
    }

    @Test
    @DisplayName("alias form preserves visible text")
    void aliasFormVisible() {
        String src = "Use Spring AI here.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(src,
                plan(new EnrichmentReplacement("Spring AI", "[[spring-ai|Spring AI]]", 1)));
        assertInstanceOf(WikiEnrichmentApplier.Result.Applied.class, r);
        assertEquals("Use [[spring-ai|Spring AI]] here.", r.content());
        assertEquals(WikiEnrichmentApplier.stripWikilinks(r.content()), src);
    }

    @Test
    @DisplayName("nth occurrence wraps only that occurrence")
    void wrapsSpecificOccurrence() {
        String src = "Linux. Linux. Linux.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(src,
                plan(new EnrichmentReplacement("Linux", "[[linux|Linux]]", 2)));
        assertEquals("Linux. [[linux|Linux]]. Linux.", r.content());
    }

    @Test
    @DisplayName("multiple replacements apply right-to-left so offsets stay valid")
    void multipleReplacementsAreOrderIndependent() {
        String src = "Spring AI uses Linux on the server.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(src, plan(
                new EnrichmentReplacement("Spring AI", "[[spring-ai|Spring AI]]", 1),
                new EnrichmentReplacement("Linux", "[[linux|Linux]]", 1)
        ));
        assertEquals("[[spring-ai|Spring AI]] uses [[linux|Linux]] on the server.", r.content());
    }

    @Test
    @DisplayName("rejects: replacement isn't a wikilink form")
    void rejectsNonWikilinkReplacement() {
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply("Spring AI rocks",
                plan(new EnrichmentReplacement("Spring AI", "spring ai is great", 1)));
        assertInstanceOf(WikiEnrichmentApplier.Result.Rejected.class, r);
    }

    @Test
    @DisplayName("rejects: visible text doesn't match original (would mutate prose)")
    void rejectsVisibleMismatch() {
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply("Spring AI rocks",
                // Replacement label "Spring AI Alibaba" != original "Spring AI"
                plan(new EnrichmentReplacement("Spring AI", "[[spring-ai|Spring AI Alibaba]]", 1)));
        assertInstanceOf(WikiEnrichmentApplier.Result.Rejected.class, r);
    }

    @Test
    @DisplayName("skips occurrences that fall inside an existing wikilink")
    void skipsInsideExistingWikilink() {
        String src = "We mention [[spring-ai|Spring AI]] once, then Spring AI again.";
        // Occurrence 1 should be the SECOND visible "Spring AI" (the one outside [[..]]).
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(src,
                plan(new EnrichmentReplacement("Spring AI", "[[spring-ai|Spring AI]]", 1)));
        assertEquals("We mention [[spring-ai|Spring AI]] once, then [[spring-ai|Spring AI]] again.", r.content());
    }

    @Test
    @DisplayName("missing occurrence is silently skipped (page may have changed)")
    void missingOccurrenceSkipped() {
        String src = "Only one Linux mention here.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(src,
                plan(new EnrichmentReplacement("Linux", "[[linux|Linux]]", 5)));
        assertInstanceOf(WikiEnrichmentApplier.Result.Unchanged.class, r);
        assertEquals(src, r.content());
    }

    @Test
    @DisplayName("rejects when too many replacements are proposed")
    void rejectsExcess() {
        EnrichmentReplacement[] arr = new EnrichmentReplacement[51];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = new EnrichmentReplacement("x", "[[x]]", i + 1);
        }
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply("x", plan(arr));
        assertInstanceOf(WikiEnrichmentApplier.Result.Rejected.class, r);
    }

    @Test
    @DisplayName("round-trip invariant holds for arbitrary plans")
    void stripIdentityHolds() {
        String src = "Spring AI talks to Linux servers via SSH.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(src, plan(
                new EnrichmentReplacement("Spring AI", "[[spring-ai|Spring AI]]", 1),
                new EnrichmentReplacement("Linux", "[[linux|Linux]]", 1),
                new EnrichmentReplacement("SSH", "[[ssh|SSH]]", 1)
        ));
        assertInstanceOf(WikiEnrichmentApplier.Result.Applied.class, r);
        assertEquals(src, WikiEnrichmentApplier.stripWikilinks(r.content()));
    }

    @Test
    @DisplayName("empty plan returns Unchanged with original content")
    void emptyPlanIsUnchanged() {
        String src = "Nothing to wrap here.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(src, new EnrichmentPlan(List.of()));
        assertInstanceOf(WikiEnrichmentApplier.Result.Unchanged.class, r);
        assertEquals(src, r.content());
    }

    @Test
    @DisplayName("stripWikilinks handles both bare and alias forms")
    void stripBoth() {
        assertEquals("Spring AI is great",
                WikiEnrichmentApplier.stripWikilinks("[[spring-ai|Spring AI]] is great"));
        assertEquals("spring-ai is great",
                WikiEnrichmentApplier.stripWikilinks("[[spring-ai]] is great"));
        assertEquals("plain text", WikiEnrichmentApplier.stripWikilinks("plain text"));
    }
}
