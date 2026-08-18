package vip.mate.tool.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserWaitConditionTest {

    @Test
    @DisplayName("parses selector wait and caps timeout")
    void parsesSelectorAndCapsTimeout() {
        BrowserWaitCondition parsed = BrowserWaitCondition.parse("selector", "#ready", null, null, 120, 30);

        assertEquals(BrowserWaitCondition.Kind.SELECTOR, parsed.kind());
        assertEquals("#ready", parsed.target());
        assertEquals(30_000, parsed.timeoutMillis());
    }

    @Test
    @DisplayName("parses text wait from text parameter")
    void parsesTextFromTextParameter() {
        BrowserWaitCondition parsed = BrowserWaitCondition.parse("text", null, "Saved", null, null, 30);

        assertEquals(BrowserWaitCondition.Kind.TEXT, parsed.kind());
        assertEquals("Saved", parsed.target());
        assertEquals(30_000, parsed.timeoutMillis());
    }

    @Test
    @DisplayName("parses load state aliases")
    void parsesLoadStateAlias() {
        BrowserWaitCondition parsed = BrowserWaitCondition.parse("load_state", null, null, "domcontentloaded", 5, 30);

        assertEquals(BrowserWaitCondition.Kind.LOAD_STATE, parsed.kind());
        assertEquals("domcontentloaded", parsed.target());
        assertEquals(5_000, parsed.timeoutMillis());
    }

    @Test
    @DisplayName("requires target for selector wait")
    void requiresSelectorTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> BrowserWaitCondition.parse("selector", " ", null, null, 5, 30));
    }

    @Test
    @DisplayName("rejects unknown conditions")
    void rejectsUnknownCondition() {
        assertThrows(IllegalArgumentException.class,
                () -> BrowserWaitCondition.parse("sleep", null, null, null, 5, 30));
    }
}
