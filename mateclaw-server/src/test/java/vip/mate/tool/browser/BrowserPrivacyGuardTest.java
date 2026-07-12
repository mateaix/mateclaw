package vip.mate.tool.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BrowserPrivacyGuard} classification and blocking.
 * The audit mapper is never touched by {@code isSensitive}/{@code blockReason},
 * so a null mapper is fine here.
 */
class BrowserPrivacyGuardTest {

    private BrowserPrivacyGuard guardWith(List<String> sensitive, List<String> trusted) {
        BrowserProperties props = new BrowserProperties();
        props.getPrivacy().setSensitiveHosts(sensitive);
        props.getPrivacy().setTrustedHosts(trusted);
        return new BrowserPrivacyGuard(props, null);
    }

    @Test
    @DisplayName("Heuristic flags banking / webmail / login / admin pages")
    void heuristicFlagsSensitivePages() {
        BrowserPrivacyGuard g = guardWith(List.of(), List.of());
        assertTrue(g.isSensitive("https://www.chase.com/banking"));
        assertTrue(g.isSensitive("https://mail.google.com/mail/u/0"));
        assertTrue(g.isSensitive("https://github.com/login"));
        assertTrue(g.isSensitive("https://example.com/account/settings"));
        assertTrue(g.isSensitive("https://admin.example.com/"));
    }

    @Test
    @DisplayName("Ordinary content pages are not flagged")
    void ordinaryPagesNotFlagged() {
        BrowserPrivacyGuard g = guardWith(List.of(), List.of());
        assertFalse(g.isSensitive("https://example.com/products/42"));
        assertFalse(g.isSensitive("https://news.example.com/article/hello-world"));
        assertFalse(g.isSensitive(""));
        assertFalse(g.isSensitive(null));
    }

    @Test
    @DisplayName("trustedHosts overrides both the heuristic and sensitiveHosts")
    void trustedOverridesEverything() {
        BrowserPrivacyGuard g = guardWith(List.of("internal.corp"), List.of("mail.google.com"));
        assertFalse(g.isSensitive("https://mail.google.com/mail"));
        // subdomain of a trusted host is also trusted
        BrowserPrivacyGuard g2 = guardWith(List.of(), List.of("example.com"));
        assertFalse(g2.isSensitive("https://admin.example.com/login"));
    }

    @Test
    @DisplayName("sensitiveHosts adds hosts the heuristic would miss")
    void sensitiveHostsExtendHeuristic() {
        BrowserPrivacyGuard g = guardWith(List.of("intranet.corp"), List.of());
        assertTrue(g.isSensitive("https://intranet.corp/dashboard"));
        assertTrue(g.isSensitive("https://hr.intranet.corp/"));
    }

    @Test
    @DisplayName("blockReason only fires for a user-managed browser on a sensitive page")
    void blockReasonScope() {
        BrowserPrivacyGuard g = guardWith(List.of(), List.of());
        // sensitive page but self-spawned browser → allowed
        assertNull(g.blockReason(false, "https://github.com/login", "screenshot"));
        // user-managed browser but ordinary page → allowed
        assertNull(g.blockReason(true, "https://example.com/products", "eval"));
        // user-managed browser on a sensitive page → blocked
        assertNotNull(g.blockReason(true, "https://github.com/login", "eval"));
    }

    @Test
    @DisplayName("Disabling the guard allows everything")
    void disabledGuardAllows() {
        BrowserProperties props = new BrowserProperties();
        props.getPrivacy().setEnabled(false);
        BrowserPrivacyGuard g = new BrowserPrivacyGuard(props, null);
        assertNull(g.blockReason(true, "https://www.chase.com/banking", "screenshot"));
    }
}
