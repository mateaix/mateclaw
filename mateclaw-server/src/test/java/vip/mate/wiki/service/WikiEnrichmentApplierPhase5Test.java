package vip.mate.wiki.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.dto.EnrichmentPlan;
import vip.mate.wiki.dto.EnrichmentReplacement;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC §4 Phase 5 additions to {@link WikiEnrichmentApplier}:
 * <ol>
 *   <li>do not wrap text that already sits inside a fenced code block</li>
 *   <li>do not wrap text inside an inline {@code `code`} span</li>
 *   <li>when an allowed-slug whitelist is supplied, drop patches that
 *       target a slug outside the set (instead of failing the whole plan)</li>
 * </ol>
 * These complement the existing PR-5b coverage; new tests live in a separate
 * class so the originally-passing assertions stay independent.
 */
class WikiEnrichmentApplierPhase5Test {

    private static EnrichmentPlan plan(EnrichmentReplacement... rs) {
        return new EnrichmentPlan(List.of(rs));
    }

    @Test
    @DisplayName("does not wrap occurrences inside a fenced code block")
    void skipsFencedCode() {
        String src = "First, mention kubernetes in prose.\n\n" +
                "```\n" +
                "Then kubernetes inside a fence stays literal.\n" +
                "```\n" +
                "Closing kubernetes here too.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(src,
                plan(new EnrichmentReplacement("kubernetes", "[[kubernetes]]", 1)));
        assertInstanceOf(WikiEnrichmentApplier.Result.Applied.class, r);
        String out = r.content();
        // First prose occurrence: wrapped.
        assertTrue(out.startsWith("First, mention [[kubernetes]] in prose."),
                "first prose occurrence must wrap, got: " + out);
        // Inside fence: unchanged.
        assertTrue(out.contains("Then kubernetes inside a fence stays literal."),
                "fenced occurrence must remain literal, got: " + out);
        // Closing prose: NOT wrapped (occurrence=1 only).
        assertTrue(out.endsWith("Closing kubernetes here too."),
                "second prose occurrence not asked for, must remain literal, got: " + out);
    }

    @Test
    @DisplayName("wrap targets second prose occurrence when the first sits inside a fence")
    void firstWrapTargetsFirstNonCodeOccurrence() {
        String src = "```\nThe first kubernetes is in code.\n```\nThen kubernetes in prose.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(src,
                plan(new EnrichmentReplacement("kubernetes", "[[kubernetes]]", 1)));
        assertInstanceOf(WikiEnrichmentApplier.Result.Applied.class, r);
        String out = r.content();
        assertTrue(out.contains("The first kubernetes is in code."),
                "fenced occurrence remains literal, got: " + out);
        assertTrue(out.endsWith("Then [[kubernetes]] in prose."),
                "prose occurrence (the first non-code one) is what gets wrapped, got: " + out);
    }

    @Test
    @DisplayName("does not wrap inside inline `code` spans")
    void skipsInlineCode() {
        String src = "Show `kubernetes` as inline code; mention kubernetes in prose.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(src,
                plan(new EnrichmentReplacement("kubernetes", "[[kubernetes]]", 1)));
        assertInstanceOf(WikiEnrichmentApplier.Result.Applied.class, r);
        String out = r.content();
        assertTrue(out.contains("`kubernetes`"), "inline code must stay literal: " + out);
        assertTrue(out.contains("mention [[kubernetes]] in prose"), "prose wrapped: " + out);
    }

    @Test
    @DisplayName("whitelist drops patches whose target slug isn't allowed")
    void whitelistDropsUnknownSlugs() {
        String src = "Spring AI rocks; Linux too.";
        Set<String> allowed = Set.of("spring-ai");
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(
                src,
                plan(
                        new EnrichmentReplacement("Spring AI", "[[spring-ai|Spring AI]]", 1),
                        new EnrichmentReplacement("Linux", "[[linux|Linux]]", 1)
                ),
                WikiEnrichmentApplier.DEFAULT_MAX_REPLACEMENTS,
                allowed);
        assertInstanceOf(WikiEnrichmentApplier.Result.Applied.class, r);
        String out = r.content();
        // spring-ai is in the whitelist → wrapped.
        assertTrue(out.contains("[[spring-ai|Spring AI]]"),
                "whitelisted slug must wrap, got: " + out);
        // linux is NOT in the whitelist → silently dropped, original text intact.
        assertTrue(out.contains("Linux too"), "dropped patch must leave text untouched, got: " + out);
        assertTrue(!out.contains("[[linux"), "dropped patch must not produce a [[linux...]], got: " + out);
    }

    @Test
    @DisplayName("whitelist null disables the gate (legacy callers keep working)")
    void whitelistNullDisablesGate() {
        String src = "Linux is fine.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(
                src,
                plan(new EnrichmentReplacement("Linux", "[[linux|Linux]]", 1)),
                WikiEnrichmentApplier.DEFAULT_MAX_REPLACEMENTS,
                null);
        assertInstanceOf(WikiEnrichmentApplier.Result.Applied.class, r);
        assertEquals("[[linux|Linux]] is fine.", r.content());
    }

    @Test
    @DisplayName("whitelist empty drops every patch but keeps original content")
    void whitelistEmptyDropsAll() {
        String src = "Linux is fine.";
        WikiEnrichmentApplier.Result r = WikiEnrichmentApplier.apply(
                src,
                plan(new EnrichmentReplacement("Linux", "[[linux|Linux]]", 1)),
                WikiEnrichmentApplier.DEFAULT_MAX_REPLACEMENTS,
                Set.of());
        assertInstanceOf(WikiEnrichmentApplier.Result.Unchanged.class, r);
        assertEquals(src, r.content());
    }
}
