package vip.mate.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the informative cleared-tool-output placeholder — name, size and
 * first-line gist survive the clearing so the model can judge whether a
 * re-run is worthwhile.
 */
class InformativeClearedPlaceholderTest {

    @Test
    @DisplayName("placeholder carries tool name, original size and first-line gist")
    void carriesNameSizeAndGist() {
        String body = "total 47 tests, 0 failures\n<full test output...>";
        String placeholder = ConversationWindowManager.buildInformativeCleared("run_tests", body);
        assertTrue(placeholder.contains("run_tests"));
        assertTrue(placeholder.contains(body.length() + " chars"));
        assertTrue(placeholder.contains("total 47 tests, 0 failures"));
        assertTrue(placeholder.contains("call the tool again"));
    }

    @Test
    @DisplayName("long first line is capped at 80 chars")
    void longGistCapped() {
        String body = "x".repeat(500);
        String placeholder = ConversationWindowManager.buildInformativeCleared("read_file", body);
        assertTrue(placeholder.contains("x".repeat(80) + "…"));
        assertFalse(placeholder.contains("x".repeat(81)));
    }

    @Test
    @DisplayName("null / blank bodies degrade gracefully")
    void nullAndBlankBodies() {
        String placeholder = ConversationWindowManager.buildInformativeCleared(null, null);
        assertTrue(placeholder.contains("tool"));
        assertTrue(placeholder.contains("0 chars"));
        assertFalse(placeholder.contains("began:"));

        String blank = ConversationWindowManager.buildInformativeCleared("shell", "\n\n  \n");
        assertFalse(blank.contains("began:"));
    }
}
