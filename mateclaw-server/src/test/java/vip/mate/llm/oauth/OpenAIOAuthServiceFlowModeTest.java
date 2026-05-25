package vip.mate.llm.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.llm.oauth.OpenAIOAuthService.OAuthFlowMode;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue: OAuth callback fails on Linux server deployment because the
 * redirect_uri is hardcoded to http://localhost:1455/auth/callback. When the
 * user's browser hits this URL it tries to reach the user's own machine, not
 * the remote MateClaw server, so the auth code never reaches the server.
 *
 * <p>Tests focus on the deployment-mode resolution logic (Host header heuristic
 * + config override + paste-URL parser). Network-bound paths (token exchange,
 * Keychain reads) are out of scope here — they need either a wiremock or live
 * fixtures.
 */
class OpenAIOAuthServiceFlowModeTest {

    private OpenAIOAuthService service;

    @BeforeEach
    void setUp() {
        // null collaborators OK because the helpers we exercise (resolveFlowMode,
        // completeFromPastedUrl up to state validation) don't touch them. The
        // compile-time @RequiredArgsConstructor accepts nulls.
        service = new OpenAIOAuthService(null, new ObjectMapper(), null);
    }

    @AfterEach
    void clearOverride() {
        System.clearProperty("mateclaw.oauth.openai.deployment-mode");
    }

    // ============== resolveFlowMode (private — accessed via reflection) ===

    private OAuthFlowMode invokeResolve(String host) throws Exception {
        Method m = OpenAIOAuthService.class.getDeclaredMethod("resolveFlowMode", String.class);
        m.setAccessible(true);
        return (OAuthFlowMode) m.invoke(service, host);
    }

    @Test
    @DisplayName("localhost variants resolve to LOCAL mode")
    void localhostHosts_resolveToLocal() throws Exception {
        assertEquals(OAuthFlowMode.LOCAL, invokeResolve("localhost"));
        assertEquals(OAuthFlowMode.LOCAL, invokeResolve("localhost:18088"));
        assertEquals(OAuthFlowMode.LOCAL, invokeResolve("127.0.0.1"));
        assertEquals(OAuthFlowMode.LOCAL, invokeResolve("127.0.0.1:18088"));
        assertEquals(OAuthFlowMode.LOCAL, invokeResolve("LocalHost"));   // case-insensitive
    }

    @Test
    @DisplayName("public hosts resolve to DEVICE_CODE (browser-agnostic, no callback server needed)")
    void publicHosts_resolveToDeviceCode() throws Exception {
        assertEquals(OAuthFlowMode.DEVICE_CODE, invokeResolve("mateclaw.example.com"));
        assertEquals(OAuthFlowMode.DEVICE_CODE, invokeResolve("api.mate.vip"));
        assertEquals(OAuthFlowMode.DEVICE_CODE, invokeResolve("api.mate.vip:443"));
        assertEquals(OAuthFlowMode.DEVICE_CODE, invokeResolve("192.168.1.10"),
                "private LAN IP — not localhost, browser still won't reach server's localhost");
        assertEquals(OAuthFlowMode.DEVICE_CODE, invokeResolve("10.0.0.5:8080"));
    }

    @Test
    @DisplayName("null/blank host falls back to LOCAL (legacy behaviour preservation)")
    void nullOrBlankHost_legacyLocal() throws Exception {
        assertEquals(OAuthFlowMode.LOCAL, invokeResolve(null));
        assertEquals(OAuthFlowMode.LOCAL, invokeResolve(""));
        assertEquals(OAuthFlowMode.LOCAL, invokeResolve("   "));
    }

    @Test
    @DisplayName("config override mateclaw.oauth.openai.deployment-mode=local forces LOCAL even on remote host")
    void configOverride_forcesLocal() throws Exception {
        System.setProperty("mateclaw.oauth.openai.deployment-mode", "local");
        assertEquals(OAuthFlowMode.LOCAL, invokeResolve("mateclaw.example.com"));
    }

    @Test
    @DisplayName("config override =device_code forces DEVICE_CODE even on localhost")
    void configOverride_forcesDeviceCode() throws Exception {
        System.setProperty("mateclaw.oauth.openai.deployment-mode", "device_code");
        assertEquals(OAuthFlowMode.DEVICE_CODE, invokeResolve("localhost"));

        // 'server' kept as alias for backwards compatibility (now points to DEVICE_CODE)
        System.setProperty("mateclaw.oauth.openai.deployment-mode", "server");
        assertEquals(OAuthFlowMode.DEVICE_CODE, invokeResolve("localhost"));
    }

    @Test
    @DisplayName("config override =manual_paste forces MANUAL_PASTE")
    void configOverride_forcesManualPaste() throws Exception {
        System.setProperty("mateclaw.oauth.openai.deployment-mode", "manual_paste");
        assertEquals(OAuthFlowMode.MANUAL_PASTE, invokeResolve("localhost"));
        assertEquals(OAuthFlowMode.MANUAL_PASTE, invokeResolve("api.mate.vip"));
    }

    @Test
    @DisplayName("config override 'auto' or unknown falls back to heuristic")
    void configOverride_autoFallsThrough() throws Exception {
        System.setProperty("mateclaw.oauth.openai.deployment-mode", "auto");
        assertEquals(OAuthFlowMode.LOCAL, invokeResolve("localhost"));
        assertEquals(OAuthFlowMode.DEVICE_CODE, invokeResolve("api.mate.vip"));

        System.setProperty("mateclaw.oauth.openai.deployment-mode", "garbage");
        assertEquals(OAuthFlowMode.LOCAL, invokeResolve("localhost"));
    }

    // ============== completeFromPastedUrl ================================

    @Test
    @DisplayName("completeFromPastedUrl rejects empty / null input")
    void pastedUrl_emptyRejected() {
        assertThrows(MateClawException.class, () -> service.completeFromPastedUrl(null));
        assertThrows(MateClawException.class, () -> service.completeFromPastedUrl(""));
        assertThrows(MateClawException.class, () -> service.completeFromPastedUrl("   "));
    }

    @Test
    @DisplayName("completeFromPastedUrl rejects URL without query string")
    void pastedUrl_noQueryRejected() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.completeFromPastedUrl("http://localhost:1455/auth/callback"));
        assertTrue(ex.getMessage().contains("查询参数"));
    }

    @Test
    @DisplayName("completeFromPastedUrl rejects URL missing code")
    void pastedUrl_missingCode() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.completeFromPastedUrl(
                        "http://localhost:1455/auth/callback?state=xyz"));
        assertTrue(ex.getMessage().contains("code"));
    }

    @Test
    @DisplayName("completeFromPastedUrl rejects URL missing state")
    void pastedUrl_missingState() {
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.completeFromPastedUrl(
                        "http://localhost:1455/auth/callback?code=abc"));
        assertTrue(ex.getMessage().contains("state"));
    }

    @Test
    @DisplayName("completeFromPastedUrl strips fragment after #")
    void pastedUrl_stripsFragment() {
        // Should successfully extract code and state, but throw because
        // state isn't in pendingStates map (no real authorize was called).
        // We're verifying the parser gets past the parsing stage.
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.completeFromPastedUrl(
                        "http://localhost:1455/auth/callback?code=abc&state=xyz#fragment"));
        // The error must be from exchangeToken (state not in pendingStates),
        // not from a parsing failure.
        assertTrue(ex.getMsgKey() != null && ex.getMsgKey().contains("oauth_state_invalid"),
                "Expected state validation failure (parser succeeded), got: " + ex.getMessage());
    }

    @Test
    @DisplayName("completeFromPastedUrl handles URL-encoded code values")
    void pastedUrl_handlesEncodedValues() {
        // The exchangeToken stage will fail, but parser must have decoded
        // the percent-encoded characters before getting there.
        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.completeFromPastedUrl(
                        "http://localhost:1455/auth/callback?code=abc%2B123&state=test%3Dvalue"));
        // Should fail at state validation, not parsing
        assertTrue(ex.getMsgKey() != null && ex.getMsgKey().contains("oauth_state_invalid"),
                "Parser should accept percent-encoded values; got: " + ex.getMessage());
    }
}
