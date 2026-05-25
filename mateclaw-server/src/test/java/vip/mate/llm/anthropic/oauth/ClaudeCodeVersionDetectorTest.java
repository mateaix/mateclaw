package vip.mate.llm.anthropic.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Static-helper coverage for {@link ClaudeCodeVersionDetector#parseVersion}.
 *
 * <p>The {@code claude --version} output format has shifted between Claude Code
 * releases (early builds prefixed with the binary name; recent ones print just
 * the number). The regex must match both so MateClaw stays in sync without
 * manual config when users upgrade.
 */
class ClaudeCodeVersionDetectorTest {

    @Test
    @DisplayName("parseVersion accepts the modern bare-number format")
    void parseVersion_modern() {
        assertEquals("2.1.114", ClaudeCodeVersionDetector.parseVersion("2.1.114"));
        assertEquals("2.1.74", ClaudeCodeVersionDetector.parseVersion("2.1.74\n"));
    }

    @Test
    @DisplayName("parseVersion ignores trailing whitespace and extra suffix")
    void parseVersion_withSuffix() {
        assertEquals("2.1.114", ClaudeCodeVersionDetector.parseVersion("2.1.114 (Claude Code)"));
        assertEquals("2.1.114", ClaudeCodeVersionDetector.parseVersion("  2.1.114  "));
    }

    @Test
    @DisplayName("parseVersion accepts a two-segment version")
    void parseVersion_twoSegments() {
        // Some legacy --version outputs printed only major.minor.
        assertEquals("2.1", ClaudeCodeVersionDetector.parseVersion("2.1"));
    }

    @Test
    @DisplayName("parseVersion rejects non-numeric prefixes")
    void parseVersion_rejectsNonNumeric() {
        assertNull(ClaudeCodeVersionDetector.parseVersion("claude-code v2.1.114"));
        assertNull(ClaudeCodeVersionDetector.parseVersion(""));
        assertNull(ClaudeCodeVersionDetector.parseVersion(null));
        assertNull(ClaudeCodeVersionDetector.parseVersion("not a version"));
    }

    @Test
    @DisplayName("FALLBACK_VERSION constant is a real semver-shape string")
    void fallbackVersion_isSemver() {
        // Sanity-check the static fallback so a bad edit (e.g. typo) is caught
        // before it ships in a User-Agent header.
        String parsed = ClaudeCodeVersionDetector.parseVersion(ClaudeCodeVersionDetector.FALLBACK_VERSION);
        assertNotNull(parsed);
        assertEquals(ClaudeCodeVersionDetector.FALLBACK_VERSION, parsed);
    }
}
