package vip.mate.stt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the non-JSON response detection that keeps proxy/gateway HTML pages
 * from reaching Jackson as if they were API responses (issue #580 retest:
 * users saw a raw {@code JsonParseException: Unexpected character ('<')}
 * with no clue which endpoint produced it).
 */
class SttResponseDiagnosticsTest {

    @Test
    @DisplayName("looksLikeJson accepts objects and arrays, with whitespace and BOM")
    void looksLikeJson_acceptsJsonShapes() {
        assertTrue(SttResponseDiagnostics.looksLikeJson("{\"text\":\"hi\"}"));
        assertTrue(SttResponseDiagnostics.looksLikeJson("  \n {\"a\":1}"));
        assertTrue(SttResponseDiagnostics.looksLikeJson("[1,2]"));
        assertTrue(SttResponseDiagnostics.looksLikeJson("\uFEFF{\"a\":1}"));
    }

    @Test
    @DisplayName("looksLikeJson rejects HTML, SSE, plain text, empty and null")
    void looksLikeJson_rejectsNonJson() {
        assertFalse(SttResponseDiagnostics.looksLikeJson("<html><body>blocked</body></html>"));
        assertFalse(SttResponseDiagnostics.looksLikeJson("<?xml version=\"1.0\"?><Error/>"));
        assertFalse(SttResponseDiagnostics.looksLikeJson("data: {\"choices\":[]}\n\n"));
        assertFalse(SttResponseDiagnostics.looksLikeJson("404 page not found"));
        assertFalse(SttResponseDiagnostics.looksLikeJson(""));
        assertFalse(SttResponseDiagnostics.looksLikeJson("   "));
        assertFalse(SttResponseDiagnostics.looksLikeJson(null));
    }

    @Test
    @DisplayName("snippet collapses whitespace and truncates long bodies")
    void snippet_collapsesAndTruncates() {
        assertEquals("<html> <body> x </body> </html>",
                SttResponseDiagnostics.snippet("<html>\n  <body>\n x </body>\n</html>"));

        String longBody = "a".repeat(500);
        String snippet = SttResponseDiagnostics.snippet(longBody);
        assertEquals(SttResponseDiagnostics.MAX_SNIPPET_CHARS + 1, snippet.length());
        assertTrue(snippet.endsWith("…"));
    }

    @Test
    @DisplayName("snippet reports empty bodies explicitly")
    void snippet_emptyBody() {
        assertEquals("(空响应体)", SttResponseDiagnostics.snippet(null));
        assertEquals("(空响应体)", SttResponseDiagnostics.snippet(""));
        assertEquals("(空响应体)", SttResponseDiagnostics.snippet("   "));
    }
}
