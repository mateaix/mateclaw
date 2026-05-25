package vip.mate.llm.anthropic.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Header-construction guarantees for OAuth-authenticated Anthropic requests.
 *
 * <p>The two non-negotiable invariants Anthropic's edge enforces:
 * <ol>
 *   <li>{@code anthropic-beta} must contain both {@code claude-code-20250219}
 *       AND {@code oauth-2025-04-20}, comma-joined (no spaces).</li>
 *   <li>{@code User-Agent} must be the bare {@code claude-cli/<ver>} —
 *       NOT {@code claude-cli/<ver> (external, cli)}. The {@code (external, cli)}
 *       suffix is what third-party clients append, and Anthropic uses it as a
 *       fingerprint to rate-limit the anti-abuse path.
 *       Real Claude Code emits the bare form via the official JS SDK.</li>
 * </ol>
 */
class ClaudeCodeApiHeadersTest {

    private ClaudeCodeApiHeaders headers;

    @BeforeEach
    void setUp() {
        // Stub detector returns a stable version string so assertions stay deterministic.
        ClaudeCodeVersionDetector stub = new ClaudeCodeVersionDetector() {
            @Override
            public String get() { return "2.1.114"; }
        };
        headers = new ClaudeCodeApiHeaders(stub);
    }

    @Test
    @DisplayName("allBetas: common betas appear before OAuth-only betas")
    void allBetas_orderedCommonFirst() {
        String result = headers.allBetas();
        int oauthIdx = result.indexOf("oauth-2025-04-20");
        int interleavedIdx = result.indexOf("interleaved-thinking-2025-05-14");
        assertTrue(oauthIdx >= 0, "oauth beta missing");
        assertTrue(interleavedIdx >= 0, "interleaved-thinking beta missing");
        assertTrue(interleavedIdx < oauthIdx, "common betas must precede OAuth-only betas");
    }

    @Test
    @DisplayName("allBetas: comma-joined with no whitespace")
    void allBetas_commaJoined() {
        String result = headers.allBetas();
        // Anthropic's edge is strict — a stray space breaks the header parser.
        assertTrue(result.contains("claude-code-20250219"));
        assertTrue(result.contains("oauth-2025-04-20"));
        assertTrue(result.contains(","));
        assertEquals(-1, result.indexOf(", "));
        assertEquals(-1, result.indexOf(" ,"));
    }

    @Test
    @DisplayName("userAgent: bare claude-cli/<ver> (no suffix — anti-abuse fingerprint)")
    void userAgent_format() {
        // Critical: must NOT contain "(external, cli)" — see class javadoc.
        assertEquals("claude-cli/2.1.114", headers.userAgent());
    }

    @Test
    @DisplayName("xApp: returns the literal cli identifier")
    void xApp() {
        assertEquals("cli", headers.xApp());
    }

    @Test
    @DisplayName("bearerAuth: prepends Bearer prefix exactly once")
    void bearerAuth() {
        assertEquals("Bearer abc123", headers.bearerAuth("abc123"));
    }
}
