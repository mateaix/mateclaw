package vip.mate.llm.anthropic.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the pure-data invariants of {@link ClaudeCodeCredentials} —
 * specifically the {@code isValid(buffer)} expiry math and {@code canRefresh}
 * predicate. Exercising these here means downstream services can rely on the
 * record without re-implementing the same checks.
 */
class ClaudeCodeCredentialsTest {

    @Test
    @DisplayName("isValid: blank access token always invalid")
    void isValid_blankToken_false() {
        assertFalse(creds("", "rt", System.currentTimeMillis() + 60_000).isValid(0L));
        assertFalse(creds(null, "rt", System.currentTimeMillis() + 60_000).isValid(0L));
    }

    @Test
    @DisplayName("isValid: expiresAt=0 means no expiry — always valid when token present")
    void isValid_zeroExpiry_alwaysValid() {
        assertTrue(creds("at", "rt", 0L).isValid(60_000L));
    }

    @Test
    @DisplayName("isValid: returns false within buffer window")
    void isValid_withinBuffer_false() {
        long now = System.currentTimeMillis();
        // Token expires in 30s; buffer is 60s → invalid (must refresh before expiry).
        assertFalse(creds("at", "rt", now + 30_000L).isValid(60_000L));
    }

    @Test
    @DisplayName("isValid: returns true outside buffer window")
    void isValid_outsideBuffer_true() {
        long now = System.currentTimeMillis();
        // Token expires in 5 minutes; 60s buffer → still valid.
        assertTrue(creds("at", "rt", now + 300_000L).isValid(60_000L));
    }

    @Test
    @DisplayName("canRefresh: requires non-blank refresh token")
    void canRefresh() {
        assertTrue(creds("at", "rt", 0L).canRefresh());
        assertFalse(creds("at", "", 0L).canRefresh());
        assertFalse(creds("at", null, 0L).canRefresh());
    }

    private static ClaudeCodeCredentials creds(String at, String rt, long expiresAt) {
        return new ClaudeCodeCredentials(at, rt, expiresAt, ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
    }
}
