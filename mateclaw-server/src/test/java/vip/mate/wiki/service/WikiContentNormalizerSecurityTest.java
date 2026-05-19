package vip.mate.wiki.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial tests for {@link WikiContentNormalizer}.
 * <p>
 * Each test feeds hostile HTML through the html / htm / url normalization path
 * and asserts that no executable markup — tags, event-handler attributes, or
 * script/style bodies — survives into the normalized output, while legitimate
 * prose and headings are preserved. The normalized output is plain text by
 * contract, so it must contain no tag tokens at all.
 */
class WikiContentNormalizerSecurityTest {

    private WikiContentNormalizer normalizer;

    /** Any HTML start or end tag token ({@code <a}, {@code </a}). The output must contain none. */
    private static final Pattern TAG_TOKEN = Pattern.compile("</?[a-zA-Z]");

    /** Source types that route through the HTML cleanup path. */
    private static final String[] HTML_TYPES = {"html", "htm", "url"};

    @BeforeEach
    void setUp() {
        normalizer = new WikiContentNormalizer();
    }

    /** Asserts a hostile payload normalizes to text with no tag token and no event-handler attribute. */
    private void assertNoLiveMarkup(String label, String payload) {
        for (String type : HTML_TYPES) {
            String out = normalizer.normalize(type, payload);
            String lower = out.toLowerCase();
            assertFalse(TAG_TOKEN.matcher(out).find(),
                    label + " [" + type + "]: a tag token survived → " + out);
            assertFalse(lower.contains("<script"), label + " [" + type + "]: <script leaked → " + out);
            assertFalse(lower.contains("<style"), label + " [" + type + "]: <style leaked → " + out);
            assertFalse(lower.contains("<iframe"), label + " [" + type + "]: <iframe leaked → " + out);
            assertFalse(lower.contains("onerror="), label + " [" + type + "]: onerror= leaked → " + out);
            assertFalse(lower.contains("onload="), label + " [" + type + "]: onload= leaked → " + out);
            assertFalse(lower.contains("onclick="), label + " [" + type + "]: onclick= leaked → " + out);
            assertFalse(lower.contains("onmouseover="), label + " [" + type + "]: onmouseover= leaked → " + out);
        }
    }

    /** assertNoLiveMarkup, and additionally that the JavaScript payload marker is fully gone. */
    private void assertFullyStripped(String label, String payload, String jsMarker) {
        assertNoLiveMarkup(label, payload);
        for (String type : HTML_TYPES) {
            String out = normalizer.normalize(type, payload);
            assertFalse(out.contains(jsMarker),
                    label + " [" + type + "]: JS payload marker survived → " + out);
        }
    }

    // ───────────────────────── well-formed element attacks ─────────────────────────

    @Test
    @DisplayName("classic <script> in <head> is fully removed")
    void scriptInHead() {
        String p = "<html><head><script>STEAL_COOKIES()</script></head>"
                + "<body><h1>Doc</h1><p>Body text.</p></body></html>";
        assertFullyStripped("script-in-head", p, "STEAL_COOKIES");
        String out = normalizer.normalize("html", p);
        assertTrue(out.contains("# Doc"), "heading survives");
        assertTrue(out.contains("Body text."), "body survives");
    }

    @Test
    @DisplayName("img onerror handler does not survive")
    void imgOnError() {
        assertNoLiveMarkup("img-onerror",
                "<body><p>Before.</p><img src=x onerror=\"STEAL()\"><p>After.</p></body>");
    }

    @Test
    @DisplayName("svg onload handler does not survive")
    void svgOnLoad() {
        assertNoLiveMarkup("svg-onload",
                "<body><svg onload=\"STEAL()\"></svg><p>Visible.</p></body>");
    }

    @Test
    @DisplayName("iframe with javascript: src does not survive")
    void iframeJavascriptSrc() {
        assertNoLiveMarkup("iframe-js",
                "<body><iframe src=\"javascript:STEAL()\"></iframe><p>Visible.</p></body>");
    }

    @Test
    @DisplayName("mixed-case <ScRiPt> is still removed")
    void mixedCaseScript() {
        assertFullyStripped("mixed-case",
                "<body><h1>T</h1><ScRiPt>STEAL()</ScRiPt><p>Body.</p></body>", "STEAL");
    }

    @Test
    @DisplayName("anchor with javascript: href keeps only its visible text")
    void anchorJavascriptHref() {
        String p = "<body><a href=\"javascript:STEAL()\">click here</a></body>";
        assertNoLiveMarkup("anchor-js", p);
        String out = normalizer.normalize("html", p);
        assertTrue(out.contains("click here"), "anchor visible text survives");
        assertFalse(out.contains("javascript:"), "javascript: URI dropped");
    }

    @Test
    @DisplayName("event-handler attribute on a heading is dropped, heading text kept")
    void eventHandlerOnHeading() {
        String p = "<body><h2 onclick=\"STEAL()\">Section Title</h2><p>x</p></body>";
        assertNoLiveMarkup("onclick-heading", p);
        String out = normalizer.normalize("html", p);
        assertTrue(out.contains("## Section Title"), "heading text and level survive");
    }

    @Test
    @DisplayName("nested / mutation script tags do not yield a live tag")
    void mutationScript() {
        assertNoLiveMarkup("mutation",
                "<body><scr<script>ipt>STEAL()</scr</script>ipt><p>Body.</p></body>");
    }

    @Test
    @DisplayName("noscript-wrapped script is removed")
    void noscriptWrappedScript() {
        assertNoLiveMarkup("noscript",
                "<body><noscript><script>STEAL()</script></noscript><p>Body.</p></body>");
    }

    @Test
    @DisplayName("object and embed elements do not survive")
    void objectAndEmbed() {
        assertNoLiveMarkup("object-embed",
                "<body><object data=\"javascript:STEAL()\"></object>"
                        + "<embed src=\"javascript:STEAL()\"><p>Body.</p></body>");
    }

    @Test
    @DisplayName("style block with a url() payload is removed")
    void styleBlockRemoved() {
        assertFullyStripped("style-url",
                "<html><head><style>body{background:url('STEAL')}</style></head>"
                        + "<body><p>Body.</p></body></html>", "STEAL");
    }

    // ──────────────── sniff-evasion: markup with no element children ────────────────

    @Test
    @DisplayName("event handler on the <html> skeleton tag does not survive")
    void handlerOnHtmlSkeleton() {
        assertNoLiveMarkup("html-skeleton-handler",
                "<html onmouseover=\"STEAL()\">plain body text only</html>");
    }

    @Test
    @DisplayName("event handler on the <body> skeleton tag does not survive")
    void handlerOnBodySkeleton() {
        assertNoLiveMarkup("body-skeleton-handler",
                "<body onload=\"STEAL()\">just text, no child elements</body>");
    }

    @Test
    @DisplayName("script hidden inside an HTML comment does not survive")
    void scriptHiddenInComment() {
        assertNoLiveMarkup("comment-script",
                "<!-- harmless --><body onload=\"STEAL()\"><!-- <script>STEAL()</script> --></body>");
    }

    // ─────────────────────── oversized payload: lossy strip path ───────────────────────

    @Test
    @DisplayName("oversized HTML beyond the parse cap still has its <script> stripped")
    void oversizedPayloadStripped() {
        String huge = "<script>STEAL_OVERSIZE()</script>\n" + "lorem ipsum dolor ".repeat(500_000);
        assertTrue(huge.length() > 8 * 1024 * 1024, "payload must exceed the 8 MB parse cap");
        String out = normalizer.normalize("html", huge);
        assertFalse(out.toLowerCase().contains("<script"), "<script must be stripped from oversized input");
        assertFalse(out.contains("STEAL_OVERSIZE"), "script body must be stripped from oversized input");
        assertTrue(out.contains("lorem ipsum"), "body text survives the lossy strip");
    }

    // ───────────────────── integrity: legitimate content must survive ─────────────────────

    @Test
    @DisplayName("already-extracted plain text keeps its heading line breaks")
    void extractedTextHeadingsPreserved() {
        // Shape produced by the upstream HTML extractor for an uploaded .html file:
        // tag-free, ATX headings already on their own lines.
        String extracted = "# Chapter One\nIntro paragraph.\n## Section A\nDetail text.";
        String out = normalizer.normalize("html", extracted);
        assertTrue(out.contains("# Chapter One"), "h1 line preserved");
        assertTrue(out.contains("## Section A"), "h2 line preserved");
        assertTrue(out.contains("Intro paragraph."), "body preserved");
        assertFalse(out.contains("# Chapter One Intro"), "heading must not be merged into the paragraph");
    }

    @Test
    @DisplayName("genuine HTML article keeps headings and prose")
    void legitArticleSurvives() {
        String p = "<html><body><h1>Guide</h1><p>First paragraph.</p>"
                + "<h2>Details</h2><p>Second paragraph.</p></body></html>";
        String out = normalizer.normalize("html", p);
        assertTrue(out.contains("# Guide"), "h1 survives");
        assertTrue(out.contains("## Details"), "h2 survives");
        assertTrue(out.contains("First paragraph."), "first paragraph survives");
        assertTrue(out.contains("Second paragraph."), "second paragraph survives");
    }
}
