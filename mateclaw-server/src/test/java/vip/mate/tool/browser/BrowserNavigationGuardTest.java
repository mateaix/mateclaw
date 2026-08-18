package vip.mate.tool.browser;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserNavigationGuardTest {

    @Test
    @DisplayName("blocks unsafe CDP Page.navigate URLs before dispatch")
    void blocksUnsafeCdpNavigate() {
        JsonObject params = new JsonObject();
        params.addProperty("url", "http://169.254.169.254/latest/meta-data");

        assertThrows(SecurityException.class,
                () -> BrowserNavigationGuard.checkCdp("Page.navigate", params, List.of(), false));
    }

    @Test
    @DisplayName("ignores non-navigation CDP methods")
    void ignoresNonNavigationCdpMethod() {
        JsonObject params = new JsonObject();
        params.addProperty("url", "http://169.254.169.254/latest/meta-data");

        assertDoesNotThrow(
                () -> BrowserNavigationGuard.checkCdp("Runtime.evaluate", params, List.of(), false));
    }

    @Test
    @DisplayName("blocks obvious JavaScript navigation to unsafe URL")
    void blocksUnsafeEvalNavigation() {
        assertThrows(SecurityException.class,
                () -> BrowserNavigationGuard.checkEval(
                        "window.location.href = 'http://169.254.169.254/latest/meta-data'",
                        List.of(), false));
    }

    @Test
    @DisplayName("blocks obvious JavaScript network calls to unsafe URL")
    void blocksUnsafeEvalFetch() {
        assertThrows(SecurityException.class,
                () -> BrowserNavigationGuard.checkEval(
                        "return await fetch(\"http://169.254.169.254/latest/meta-data\")",
                        List.of(), false));
    }

    @Test
    @DisplayName("allows inert URL strings without navigation intent")
    void allowsInertUrlString() {
        assertDoesNotThrow(
                () -> BrowserNavigationGuard.checkEval(
                        "const note = 'http://169.254.169.254/latest/meta-data'; return note.length;",
                        List.of(), false));
    }
}
