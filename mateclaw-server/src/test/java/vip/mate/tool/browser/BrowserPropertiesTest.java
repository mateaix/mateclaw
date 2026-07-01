package vip.mate.tool.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the new {@link BrowserProperties} fields:
 * {@code allowPrivateNetwork} and {@code ignoreHttpsErrors}.
 *
 * <p>Both fields must default to {@code false} so that deployments unchanged
 * from before this feature keep the strict SSRF / TLS posture.
 */
class BrowserPropertiesTest {

    @Test
    @DisplayName("Defaults: allowPrivateNetwork=false, ignoreHttpsErrors=false, ssrfCheckEnabled=true")
    void defaultsAreStrict() {
        BrowserProperties props = new BrowserProperties();
        assertFalse(props.isAllowPrivateNetwork(),
                "allowPrivateNetwork must default to false (strict SSRF mode)");
        assertFalse(props.isIgnoreHttpsErrors(),
                "ignoreHttpsErrors must default to false (strict TLS validation)");
        assertTrue(props.isSsrfCheckEnabled(),
                "ssrfCheckEnabled must remain true (untouched by this feature)");
    }

    @Test
    @DisplayName("Setter round-trip: allowPrivateNetwork")
    void setterAllowPrivateNetwork() {
        BrowserProperties props = new BrowserProperties();
        props.setAllowPrivateNetwork(true);
        assertTrue(props.isAllowPrivateNetwork());
        props.setAllowPrivateNetwork(false);
        assertFalse(props.isAllowPrivateNetwork());
    }

    @Test
    @DisplayName("Setter round-trip: ignoreHttpsErrors")
    void setterIgnoreHttpsErrors() {
        BrowserProperties props = new BrowserProperties();
        props.setIgnoreHttpsErrors(true);
        assertTrue(props.isIgnoreHttpsErrors());
        props.setIgnoreHttpsErrors(false);
        assertFalse(props.isIgnoreHttpsErrors());
    }
}
