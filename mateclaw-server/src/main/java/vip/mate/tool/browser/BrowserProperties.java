package vip.mate.tool.browser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Browser launch configuration. Supports multiple fallback strategies so we can
 * launch a browser on machines where Playwright's bundled Chromium download is
 * unavailable (offline CI, corporate firewalls, minimal containers).
 *
 * <p>Precedence when launching (highest first):
 * <ol>
 *   <li>{@link #cdpUrl} — connect to an already-running Chrome via DevTools Protocol</li>
 *   <li>{@link #chromePath} or {@code CHROME_PATH} env — explicit executable</li>
 *   <li>{@link #channel} — Playwright channel ("chrome", "msedge", ...)</li>
 *   <li>Auto-detect system Chrome/Edge/Brave on well-known paths</li>
 *   <li>Playwright's bundled Chromium (requires {@code playwright install})</li>
 *   <li>External-process CDP launch (run system chrome with --remote-debugging-port and attach)</li>
 * </ol>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mateclaw.browser")
public class BrowserProperties {

    /** Pre-started Chrome CDP endpoint (e.g. http://127.0.0.1:9222). Highest priority when set. */
    private String cdpUrl = "";

    /** Absolute path to chrome.exe / google-chrome / msedge. Overrides channel/auto-detect. */
    private String chromePath = "";

    /** Playwright channel: chrome | msedge | chrome-beta | chrome-dev | msedge-beta | msedge-dev. */
    private String channel = "";

    /** Try system-installed browsers (channel + path scan) before Playwright's bundled Chromium. */
    private boolean preferSystem = true;

    /** Default headless for auto-started sessions. {@code action=start headed=true} overrides. */
    private boolean headless = true;

    /** Enable the last-resort strategy: spawn chrome --remote-debugging-port=0 and connect via CDP. */
    private boolean allowExternalCdpFallback = true;

    /** Connect timeout (seconds) for CDP / external-CDP attach. */
    private int cdpTimeoutSeconds = 20;

    /** Maximum concurrent browser sessions across all agents. Prevents runaway memory usage. */
    private int maxSessions = 5;

    /**
     * Block navigations to loopback, private, link-local and cloud-metadata hosts.
     * Narrow exceptions are configured via {@code mateclaw.security.ssrf-allowlist}.
     * Only takes effect when {@link #allowPrivateNetwork} is {@code false}.
     */
    private boolean ssrfCheckEnabled = true;

    /**
     * Permit the browser to reach loopback / private / link-local addresses
     * (127.0.0.1, 10.x, 192.168.x, 172.16-31.x, fc00::/7, ::1, …). Cloud-metadata
     * endpoints (169.254.169.254, fd00:ec2::254, …) stay blocked in every mode.
     *
     * <p>Scope: browser tool only — webhook / image-download SSRF guards still
     * enforce strict mode. Turn on for isolated LAN / on-prem deployments where
     * the agent must drive internal services (e.g. {@code http://192.168.x.x:port})
     * and has no path to the public internet. Leave off for internet-facing
     * deployments; the {@code ssrf-allowlist} is the narrower escape hatch there.
     */
    private boolean allowPrivateNetwork = false;

    /**
     * Whether Playwright should ignore HTTPS certificate errors when creating a
     * browser context. Useful for LAN deployments where internal services use
     * self-signed certificates. Defaults to {@code false} so the strict CA
     * validation chain is preserved on internet-facing deployments.
     *
     * <p>Effect:
     * <ul>
     *   <li>Sets {@code Browser.NewContextOptions.ignoreHTTPSErrors = true} for
     *       contexts created by {@link BrowserLauncher} via Playwright launch.</li>
     *   <li>When {@link #allowPrivateNetwork} is also {@code true}, additionally
     *       passes {@code --ignore-certificate-errors} / {@code --allow-running-insecure-content}
     *       to the Chromium command line — this covers CDP-attached external browsers
     *       whose existing contexts cannot be re-configured at the NewContext layer.</li>
     * </ul>
     * No effect on contexts pre-existing on a user-managed Chrome (action=connect_cdp
     * when Chrome already has tabs open) — those keep the Chrome process's own setting.
     */
    private boolean ignoreHttpsErrors = false;

    /** Viewport width (px) for launched browsers. */
    private int viewportWidth = 1280;

    /** Viewport height (px) for launched browsers. */
    private int viewportHeight = 800;
}

