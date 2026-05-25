package vip.mate.llm.anthropic.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the orchestration logic of {@link ClaudeCodeOAuthService} — the
 * decision tree for "return cached token" / "refresh + persist" / "fail with
 * actionable error". Uses test-double subclasses for Reader / Refresher /
 * Writer to avoid hitting the filesystem or network.
 */
class ClaudeCodeOAuthServiceTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("getValidToken returns existing token when still valid")
    void getValidToken_cached() {
        ClaudeCodeCredentials valid = new ClaudeCodeCredentials(
                "still-good", "rt", System.currentTimeMillis() + 600_000L,
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        ClaudeCodeOAuthService svc = serviceWith(valid, /* refreshShouldBeCalled */ false);
        assertEquals("still-good", svc.getValidToken());
    }

    @Test
    @DisplayName("getValidToken refreshes when within buffer window")
    void getValidToken_refreshesNearExpiry() {
        // Token expires in 30s; buffer is 60s → must refresh.
        ClaudeCodeCredentials nearExpiry = new ClaudeCodeCredentials(
                "old-token", "rt", System.currentTimeMillis() + 30_000L,
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);

        AtomicReference<String> capturedPreviousToken = new AtomicReference<>();
        AtomicReference<ClaudeCodeCredentials> capturedWritten = new AtomicReference<>();

        ClaudeCodeCredentialsReader reader = stubReader(nearExpiry);
        ClaudeCodeTokenRefresher refresher = stubRefresher(rt -> new ClaudeCodeCredentials(
                "fresh-token", "fresh-rt", System.currentTimeMillis() + 3_600_000L,
                ClaudeCodeCredentials.Source.REFRESH_RESPONSE));
        ClaudeCodeCredentialsWriter writer = stubWriter((prev, creds) -> {
            capturedPreviousToken.set(prev);
            capturedWritten.set(creds);
            return true;
        });

        ClaudeCodeOAuthService svc = new ClaudeCodeOAuthService(reader, refresher, writer);
        assertEquals("fresh-token", svc.getValidToken());

        // Writer must receive the prior access token (for concurrency check)
        // AND the credential pinned to the original source — not REFRESH_RESPONSE.
        assertEquals("old-token", capturedPreviousToken.get());
        assertNotNull(capturedWritten.get());
        assertEquals("fresh-token", capturedWritten.get().accessToken());
        assertEquals(ClaudeCodeCredentials.Source.CREDENTIALS_FILE, capturedWritten.get().source(),
                "write must target the source the credential was originally read from");
    }

    @Test
    @DisplayName("getValidToken throws actionable error when no credentials on disk")
    void getValidToken_noCredentials() {
        ClaudeCodeOAuthService svc = new ClaudeCodeOAuthService(
                stubReader(null),
                stubRefresher(rt -> { throw new IllegalStateException("should not be called"); }),
                stubWriter((prev, creds) -> { throw new IllegalStateException("should not be called"); }));

        MateClawException ex = assertThrows(MateClawException.class, svc::getValidToken);
        assertEquals("err.anthropic.no_claude_code", ex.getMsgKey());
    }

    @Test
    @DisplayName("getValidToken throws when token expired and no refresh available")
    void getValidToken_expiredNoRefresh() {
        ClaudeCodeCredentials expired = new ClaudeCodeCredentials(
                "expired", "", System.currentTimeMillis() - 60_000L,
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        ClaudeCodeOAuthService svc = serviceWith(expired, false);
        MateClawException ex = assertThrows(MateClawException.class, svc::getValidToken);
        assertEquals("err.anthropic.token_expired_no_refresh", ex.getMsgKey());
    }

    @Test
    @DisplayName("getValidToken still returns fresh token when persistence fails")
    void getValidToken_writeFailureNonFatal() {
        // Writer returning false (e.g. concurrent-write detected) must NOT
        // turn into a request failure — the in-memory token is still good.
        ClaudeCodeCredentials nearExpiry = new ClaudeCodeCredentials(
                "stale", "rt", System.currentTimeMillis() - 60_000L,
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        ClaudeCodeOAuthService svc = new ClaudeCodeOAuthService(
                stubReader(nearExpiry),
                stubRefresher(rt -> new ClaudeCodeCredentials(
                        "refreshed", "rt2", System.currentTimeMillis() + 600_000L,
                        ClaudeCodeCredentials.Source.REFRESH_RESPONSE)),
                stubWriter((prev, creds) -> false));
        assertEquals("refreshed", svc.getValidToken());
    }

    @Test
    @DisplayName("isLoggedIn reflects on-disk state without triggering refresh")
    void isLoggedIn() {
        ClaudeCodeCredentials valid = new ClaudeCodeCredentials(
                "tok", "rt", System.currentTimeMillis() + 600_000L,
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        assertTrue(serviceWith(valid, false).isLoggedIn());

        // Expired token → not logged in (we don't auto-refresh from a status check).
        ClaudeCodeCredentials expired = new ClaudeCodeCredentials(
                "tok", "rt", System.currentTimeMillis() - 60_000L,
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        assertFalse(serviceWith(expired, false).isLoggedIn());

        // No file → not logged in.
        ClaudeCodeOAuthService noCreds = new ClaudeCodeOAuthService(
                stubReader(null),
                stubRefresher(rt -> { throw new IllegalStateException(); }),
                stubWriter((p, c) -> { throw new IllegalStateException(); }));
        assertFalse(noCreds.isLoggedIn());
    }

    @Test
    @DisplayName("getStatus surfaces source + expiry without exposing the token")
    void getStatus_disconnected() {
        ClaudeCodeOAuthService svc = new ClaudeCodeOAuthService(
                stubReader(null),
                stubRefresher(rt -> { throw new IllegalStateException(); }),
                stubWriter((p, c) -> { throw new IllegalStateException(); }));
        ClaudeCodeOAuthService.OAuthStatus status = svc.getStatus();
        assertFalse(status.connected());
        assertFalse(status.expired());
    }

    @Test
    @DisplayName("getStatus reports expired flag correctly")
    void getStatus_expired() {
        ClaudeCodeCredentials expired = new ClaudeCodeCredentials(
                "tok", "rt", System.currentTimeMillis() - 1_000L,
                ClaudeCodeCredentials.Source.MACOS_KEYCHAIN);
        ClaudeCodeOAuthService svc = serviceWith(expired, false);
        ClaudeCodeOAuthService.OAuthStatus status = svc.getStatus();
        assertTrue(status.connected());
        assertTrue(status.expired());
        assertEquals(ClaudeCodeCredentials.Source.MACOS_KEYCHAIN, status.source());
    }

    /* ---------- Test-double helpers ---------- */

    /** Build a service whose reader returns the given credentials and whose refresher/writer fail loudly if invoked. */
    private ClaudeCodeOAuthService serviceWith(ClaudeCodeCredentials creds, boolean expectRefresh) {
        return new ClaudeCodeOAuthService(
                stubReader(creds),
                stubRefresher(rt -> {
                    if (!expectRefresh) {
                        throw new IllegalStateException("refresher should not have been called");
                    }
                    return new ClaudeCodeCredentials("refreshed", "rt2",
                            System.currentTimeMillis() + 3_600_000L,
                            ClaudeCodeCredentials.Source.REFRESH_RESPONSE);
                }),
                stubWriter((prev, c) -> {
                    if (!expectRefresh) {
                        throw new IllegalStateException("writer should not have been called");
                    }
                    return true;
                }));
    }

    private ClaudeCodeCredentialsReader stubReader(ClaudeCodeCredentials toReturn) {
        return new ClaudeCodeCredentialsReader(mapper) {
            @Override
            public Optional<ClaudeCodeCredentials> read() {
                return Optional.ofNullable(toReturn);
            }
        };
    }

    @FunctionalInterface
    private interface RefreshFn {
        ClaudeCodeCredentials apply(String refreshToken);
    }

    private ClaudeCodeTokenRefresher stubRefresher(RefreshFn fn) {
        ClaudeCodeVersionDetector ver = new ClaudeCodeVersionDetector() {
            @Override
            public String get() { return "2.1.114"; }
        };
        return new ClaudeCodeTokenRefresher(mapper, ver) {
            @Override
            public ClaudeCodeCredentials refresh(String refreshToken) {
                return fn.apply(refreshToken);
            }
        };
    }

    @FunctionalInterface
    private interface WriteFn {
        boolean apply(String previousAccessToken, ClaudeCodeCredentials creds);
    }

    private ClaudeCodeCredentialsWriter stubWriter(WriteFn fn) {
        return new ClaudeCodeCredentialsWriter(mapper) {
            @Override
            public boolean write(String previousAccessToken, ClaudeCodeCredentials refreshed) {
                return fn.apply(previousAccessToken, refreshed);
            }
        };
    }
}
