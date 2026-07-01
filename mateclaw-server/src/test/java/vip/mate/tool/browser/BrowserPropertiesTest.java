package vip.mate.tool.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the configurable {@link BrowserProperties} fields: SSRF /
 * TLS relaxation toggles, Playwright timeouts, and snapshot length cap.
 *
 * <p>Defaults must keep deployments unchanged from before this feature:
 * strict SSRF, strict TLS, 30s timeouts, 20000-char snapshot cap.
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
    @DisplayName("Defaults: timeouts=30s, snapshotMaxLength=20000")
    void defaultsForTimeoutsAndSnapshot() {
        BrowserProperties props = new BrowserProperties();
        assertEquals(30, props.getDefaultTimeoutSeconds(),
                "defaultTimeoutSeconds must default to 30 (Playwright default)");
        assertEquals(30, props.getDefaultNavigationTimeoutSeconds(),
                "defaultNavigationTimeoutSeconds must default to 30 (Playwright default)");
        assertEquals(20_000, props.getSnapshotMaxLength(),
                "snapshotMaxLength must default to 20000 (legacy MAX_SNAPSHOT_LENGTH)");
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

    @Test
    @DisplayName("Setter round-trip: defaultTimeoutSeconds")
    void setterDefaultTimeoutSeconds() {
        BrowserProperties props = new BrowserProperties();
        props.setDefaultTimeoutSeconds(120);
        assertEquals(120, props.getDefaultTimeoutSeconds());
        props.setDefaultTimeoutSeconds(5);
        assertEquals(5, props.getDefaultTimeoutSeconds());
    }

    @Test
    @DisplayName("Setter round-trip: defaultNavigationTimeoutSeconds")
    void setterDefaultNavigationTimeoutSeconds() {
        BrowserProperties props = new BrowserProperties();
        props.setDefaultNavigationTimeoutSeconds(60);
        assertEquals(60, props.getDefaultNavigationTimeoutSeconds());
        props.setDefaultNavigationTimeoutSeconds(15);
        assertEquals(15, props.getDefaultNavigationTimeoutSeconds());
    }

    @Test
    @DisplayName("Setter round-trip: snapshotMaxLength")
    void setterSnapshotMaxLength() {
        BrowserProperties props = new BrowserProperties();
        props.setSnapshotMaxLength(5_000);
        assertEquals(5_000, props.getSnapshotMaxLength());
        props.setSnapshotMaxLength(100_000);
        assertEquals(100_000, props.getSnapshotMaxLength());
    }
}
