package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural coverage for the cascade-rewrite helpers in
 * {@link WikiLinkService}. Each test pins down one of the protective rules
 * the cascade delete/rename pipeline depends on:
 *
 * <ul>
 *   <li>bare {@code [[slug]]} demotes to the snapshot title, alias form
 *       keeps the alias text, miss-slugs untouched</li>
 *   <li>case-insensitive on the slug part only — aliases are display text
 *       and must not be matched against</li>
 *   <li>code fences and inline code are preserved literally — a doc that
 *       teaches wikilink syntax must not be rewritten on delete</li>
 *   <li>rename rewrites {@code [[a]]} → {@code [[b]]} and
 *       {@code [[a|alias]]} → {@code [[b|alias]]}</li>
 * </ul>
 */
class WikiLinkServiceCascadeTest {

    private final WikiLinkService svc = new WikiLinkService(new ObjectMapper());

    @Test
    void stripBareWikilinkUsesSnapshotTitle() {
        String out = svc.stripDeletedLink(
                "See [[deprecated-concept]] for context.",
                "deprecated-concept", "Deprecated Concept");
        assertEquals("See Deprecated Concept for context.", out);
    }

    @Test
    void stripAliasedWikilinkKeepsAlias() {
        String out = svc.stripDeletedLink(
                "More on [[deprecated-concept|that old idea]] later.",
                "deprecated-concept", "Deprecated Concept");
        assertEquals("More on that old idea later.", out);
    }

    @Test
    void stripIsCaseInsensitiveOnSlug() {
        String out = svc.stripDeletedLink(
                "Both [[Foo]] and [[FOO]] go away.",
                "foo", "Foo Page");
        assertEquals("Both Foo Page and Foo Page go away.", out);
    }

    @Test
    void stripLeavesUnrelatedWikilinksAlone() {
        String input = "[[keep-me]] stays; [[delete-me]] does not.";
        String out = svc.stripDeletedLink(input, "delete-me", "Delete Me");
        assertEquals("[[keep-me]] stays; Delete Me does not.", out);
    }

    @Test
    void stripSkipsFencedCodeBlocks() {
        String input = "Outside [[a]] gone.\n\n```\nInside [[a]] stays.\n```\n";
        String out = svc.stripDeletedLink(input, "a", "A Page");
        // outside replaced, inside literal
        assertTrue(out.startsWith("Outside A Page gone."),
                "outside should be replaced, got: " + out);
        assertTrue(out.contains("Inside [[a]] stays."),
                "fenced [[a]] must be preserved, got: " + out);
    }

    @Test
    void stripSkipsInlineCodeSpans() {
        String input = "Use `[[a]]` to link, e.g. [[a]] in prose.";
        String out = svc.stripDeletedLink(input, "a", "A Page");
        // inline-code [[a]] preserved, prose [[a]] demoted
        assertTrue(out.contains("`[[a]]`"),
                "inline code must be preserved, got: " + out);
        assertTrue(out.contains("A Page in prose"),
                "prose occurrence must be replaced, got: " + out);
    }

    @Test
    void stripFallsBackToSlugWhenSnapshotMissing() {
        String out = svc.stripDeletedLink("See [[a]] here.", "a", null);
        assertEquals("See a here.", out);
    }

    @Test
    void renameRewritesBareWikilink() {
        String out = svc.renameLink("Link to [[old-slug]].", "old-slug", "new-slug");
        assertEquals("Link to [[new-slug]].", out);
    }

    @Test
    void renameRewritesAliasedWikilink() {
        String out = svc.renameLink("Read [[old-slug|the manifesto]].", "old-slug", "new-slug");
        assertEquals("Read [[new-slug|the manifesto]].", out);
    }

    @Test
    void renameIsCaseInsensitiveOnSlug() {
        String out = svc.renameLink("Both [[OLD-slug]] and [[Old-Slug|x]] should move.",
                "old-slug", "new-slug");
        assertEquals("Both [[new-slug]] and [[new-slug|x]] should move.", out);
    }

    @Test
    void renameSkipsCodeBlocks() {
        String input = "Outside [[old]] moves.\n\n```\nInside [[old]] does not.\n```\n";
        String out = svc.renameLink(input, "old", "new");
        assertTrue(out.contains("Outside [[new]] moves."));
        assertTrue(out.contains("Inside [[old]] does not."));
    }

    @Test
    void renameLeavesUnrelatedWikilinksAlone() {
        String out = svc.renameLink("[[a]] and [[b]] are friends.", "a", "x");
        assertEquals("[[x]] and [[b]] are friends.", out);
    }
}
