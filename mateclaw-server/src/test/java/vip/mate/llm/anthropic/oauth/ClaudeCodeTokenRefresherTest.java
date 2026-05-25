package vip.mate.llm.anthropic.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the response-parsing logic of {@link ClaudeCodeTokenRefresher}.
 * Network-bound paths (the actual POST to platform.claude.com) require either
 * a wiremock or live fixtures and are out of scope for unit tests.
 */
class ClaudeCodeTokenRefresherTest {

    private ClaudeCodeTokenRefresher refresher;

    @BeforeEach
    void setUp() {
        ClaudeCodeVersionDetector versionStub = new ClaudeCodeVersionDetector() {
            @Override
            public String get() { return "2.1.114"; }
        };
        refresher = new ClaudeCodeTokenRefresher(new ObjectMapper(), versionStub);
    }

    @Test
    @DisplayName("parseTokenResponse handles standard expires_in seconds")
    void parseTokenResponse_expiresIn() {
        long before = System.currentTimeMillis();
        String body = """
                { "access_token": "fresh-at", "refresh_token": "fresh-rt", "expires_in": 3600 }
                """;
        ClaudeCodeCredentials c = refresher.parseTokenResponse(body, "old-rt");
        assertEquals("fresh-at", c.accessToken());
        assertEquals("fresh-rt", c.refreshToken());
        // expires_in=3600 → expiresAt should be ~1h from now.
        long expectedMin = before + 3_590_000L;
        long expectedMax = System.currentTimeMillis() + 3_610_000L;
        assertTrue(c.expiresAtMs() >= expectedMin && c.expiresAtMs() <= expectedMax,
                "expiresAtMs " + c.expiresAtMs() + " out of expected range");
        assertEquals(ClaudeCodeCredentials.Source.REFRESH_RESPONSE, c.source());
    }

    @Test
    @DisplayName("parseTokenResponse uses absolute expires_at when provided")
    void parseTokenResponse_expiresAtMs() {
        // Some Anthropic deployments return expires_at as an absolute ms value.
        String body = """
                { "access_token": "at2", "expires_at": 1234567890000 }
                """;
        ClaudeCodeCredentials c = refresher.parseTokenResponse(body, "old-rt");
        assertEquals(1234567890000L, c.expiresAtMs());
    }

    @Test
    @DisplayName("parseTokenResponse falls back to old refresh_token when response omits one")
    void parseTokenResponse_keepsOldRefreshToken() {
        // Anthropic docs say refresh_token may be omitted on rotation-disabled
        // grants. We must NOT lose the original; otherwise the next refresh fails.
        String body = """
                { "access_token": "at3", "expires_in": 3600 }
                """;
        ClaudeCodeCredentials c = refresher.parseTokenResponse(body, "preserved-rt");
        assertEquals("preserved-rt", c.refreshToken());
    }

    @Test
    @DisplayName("parseTokenResponse rejects blank access_token")
    void parseTokenResponse_blankToken_throws() {
        // Edge case where Anthropic returns 200 with empty access_token —
        // surface as a domain error rather than persisting garbage.
        String body = """
                { "access_token": "", "expires_in": 3600 }
                """;
        MateClawException ex = assertThrows(MateClawException.class,
                () -> refresher.parseTokenResponse(body, "rt"));
        assertEquals("err.anthropic.refresh_failed", ex.getMsgKey());
    }

    @Test
    @DisplayName("parseTokenResponse wraps malformed JSON")
    void parseTokenResponse_badJson_throws() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> refresher.parseTokenResponse("not-json", "rt"));
        assertEquals("err.anthropic.refresh_failed", ex.getMsgKey());
    }

    @Test
    @DisplayName("refresh rejects blank refresh_token without making a network call")
    void refresh_blankInput_throws() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> refresher.refresh(""));
        // No network call made — the failure mode here is "no refresh available",
        // not "refresh attempt failed".
        assertNotEquals("err.anthropic.refresh_failed", ex.getMsgKey());
        assertEquals("err.anthropic.token_expired_no_refresh", ex.getMsgKey());
    }

    @Test
    @DisplayName("ENDPOINTS includes both platform.claude.com and console.anthropic.com")
    void endpoints_haveBothHosts() {
        // Constants pinned by RFC-062. If Anthropic deprecates one, change here
        // AND in the RFC; do not silently drop a fallback.
        assertTrue(ClaudeCodeTokenRefresher.ENDPOINTS.stream()
                .anyMatch(s -> s.contains("platform.claude.com")));
        assertTrue(ClaudeCodeTokenRefresher.ENDPOINTS.stream()
                .anyMatch(s -> s.contains("console.anthropic.com")));
    }
}
