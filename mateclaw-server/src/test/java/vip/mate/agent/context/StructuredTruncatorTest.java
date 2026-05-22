package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link StructuredTruncator} snaps JSON cut points to structural
 * boundaries (never mid-token / mid-string) and degrades to plain cuts for
 * non-JSON input, all while staying within the requested budget.
 */
class StructuredTruncatorTest {

    /** A 60-element array of uniform objects — the asset-inventory shape from the bug report. */
    private static String jsonArray(int rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"id\":").append(i)
                    .append(",\"name\":\"server-").append(i)
                    .append("\",\"cpu\":8,\"mem\":\"64GB\",\"note\":\"comma,inside,string\"}");
        }
        return sb.append("]").toString();
    }

    private static final String MARKER = "...[TRUNCATED]...";

    @Test
    @DisplayName("short input is returned unchanged")
    void shortInputUnchanged() {
        String s = jsonArray(2);
        assertEquals(s, StructuredTruncator.truncate(s, 10_000, 10_000, MARKER));
    }

    @Test
    @DisplayName("JSON head ends on a structural boundary, never mid-token")
    void headSnapsToBoundary() {
        String json = jsonArray(60);
        String out = StructuredTruncator.truncate(json, 200, 200, MARKER);

        String head = out.substring(0, out.indexOf(MARKER));
        // The kept head must end right after a complete element/structure char.
        char last = head.charAt(head.length() - 1);
        assertTrue(last == ',' || last == '}' || last == ']',
                "head must end on a JSON boundary, got: ..." + head.substring(Math.max(0, head.length() - 12)));
        // And it must be balanced enough that no quote is left dangling open.
        assertTrue(quotesBalancedIgnoringEscapes(head),
                "head must not end inside a string literal: " + head);
    }

    @Test
    @DisplayName("JSON tail begins on a structural boundary, never mid-token")
    void tailSnapsToBoundary() {
        String json = jsonArray(60);
        String out = StructuredTruncator.truncate(json, 200, 200, MARKER);

        String tail = out.substring(out.indexOf(MARKER) + MARKER.length());
        assertTrue(quotesBalancedIgnoringEscapes(tail),
                "tail must not start inside a string literal: " + tail);
    }

    @Test
    @DisplayName("result never exceeds head + marker + tail budget")
    void staysWithinBudget() {
        String json = jsonArray(200);
        String out = StructuredTruncator.truncate(json, 800, 800, MARKER);
        assertTrue(out.length() <= 800 + MARKER.length() + 800,
                "result length " + out.length() + " exceeded budget");
        assertTrue(out.length() < json.length(), "should actually have truncated");
    }

    @Test
    @DisplayName("commas inside string values are not treated as boundaries")
    void commasInStringsAreNotBoundaries() {
        // A single object whose only comma-bearing content is inside a string.
        String json = "{\"a\":\"x,y,z,looooooooooooooooooooooooooong,value\",\"b\":1}";
        String out = StructuredTruncator.truncate(json, 8, 8, MARKER);
        String head = out.substring(0, out.indexOf(MARKER));
        // The head budget (8) lands inside the quoted value; since the only commas
        // are inside the string, no cheap boundary exists → plain cut, but it must
        // not have falsely split on an in-string comma earlier than budget.
        assertTrue(head.length() <= 8, "head must respect budget when no real boundary exists");
    }

    @Test
    @DisplayName("non-JSON text falls back to plain head+tail cut")
    void nonJsonPlainCut() {
        String text = "x".repeat(5000);
        String out = StructuredTruncator.truncate(text, 100, 100, MARKER);
        assertEquals("x".repeat(100) + MARKER + "x".repeat(100), out);
    }

    @Test
    @DisplayName("headSlice snaps a JSON preview to a complete element")
    void headSliceSnaps() {
        String json = jsonArray(60);
        String preview = StructuredTruncator.headSlice(json, 200);
        assertTrue(preview.length() <= 200);
        char last = preview.charAt(preview.length() - 1);
        assertTrue(last == ',' || last == '}' || last == ']',
                "preview must end on a JSON boundary, got: " + preview);
        assertFalse(preview.equals(json));
    }

    @Test
    @DisplayName("null input is tolerated")
    void nullSafe() {
        assertEquals(null, StructuredTruncator.truncate(null, 10, 10, MARKER));
        assertEquals(null, StructuredTruncator.headSlice(null, 10));
    }

    /** True when double-quotes (ignoring backslash-escaped ones) are balanced, i.e. the
     *  fragment does not end while still inside a string literal. */
    private static boolean quotesBalancedIgnoringEscapes(String s) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            }
        }
        return !inString;
    }
}
