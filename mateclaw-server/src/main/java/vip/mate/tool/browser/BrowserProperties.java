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

    /**
     * Default Playwright action timeout in seconds. Applies to every
     * {@code page.click / page.fill / page.waitForLoadState} call after the
     * browser context is created. Increase for slow LAN / large-page scenarios.
     */
    private int defaultTimeoutSeconds = 30;

    /**
     * Default Playwright navigation timeout in seconds. Applies to
     * {@code page.navigate} and load-state waits. Increase for slow networks.
     */
    private int defaultNavigationTimeoutSeconds = 30;

    /**
     * Hard cap on the textual snapshot returned by {@code action=snapshot}.
     * Content beyond this length is dropped with a {@code truncated:true} flag
     * and a hint suggesting the {@code selector} parameter. Note: results
     * larger than the framework spill threshold (~8000 chars) are further
     * spilt to disk by {@code ToolResultStorage}; keep this value reasonable
     * to avoid forcing every snapshot through the spill-and-preview path.
     */
    private int snapshotMaxLength = 20_000;

    /**
     * Whether {@code action=snapshot} includes non-interactive structural nodes
     * (headings, list items, navigation, images) in the accessibility tree.
     * Interactive elements always get a reference handle; structural nodes are
     * emitted without one, purely to give the model page context. Turn off to
     * produce a terser tree of only actionable elements.
     */
    private boolean snapshotIncludeNonInteractive = true;

    /** Privacy guard for sessions attached to a user's own logged-in browser (action=connect_cdp). */
    private Privacy privacy = new Privacy();

    /** Raw DevTools Protocol escape hatch (action=cdp) configuration. */
    private Cdp cdp = new Cdp();

    /**
     * Controls {@code action=cdp}, which forwards a raw Chrome DevTools Protocol
     * command. Constrained by a method allowlist; content-reading methods are
     * additionally subject to {@link Privacy} on user-managed browsers.
     */
    @Data
    public static class Cdp {
        /** Master switch for action=cdp. */
        private boolean enabled = true;

        /**
         * Allowed CDP methods. An entry is either an exact method
         * ({@code "Page.navigate"}) or a domain wildcard ({@code "Input.*"}).
         * Defaults to safe actuation methods; extend for advanced automation.
         * Content-reading methods stay guarded by {@link Privacy} even if added.
         */
        private java.util.List<String> allowedMethods = new java.util.ArrayList<>(java.util.List.of(
                "Input.*",
                "Page.navigate", "Page.reload", "Page.bringToFront",
                "Page.getNavigationHistory", "Page.navigateToHistoryEntry"));
    }

    /**
     * When the browser tool is attached to a user-managed Chrome (connected via
     * CDP, process not spawned by us), that Chrome may have banking / email /
     * internal-admin tabs open. This guard refuses content-reading actions
     * (screenshot / eval / full snapshot) on pages that look sensitive, so
     * private content is not funnelled into the model / persisted. It never
     * affects headless or self-spawned browsers.
     */
    @Data
    public static class Privacy {
        /** Master switch. When false, no sensitive-page blocking happens. */
        private boolean enabled = true;

        /**
         * Extra hosts to always treat as sensitive (exact host or any subdomain),
         * on top of the built-in heuristic. E.g. {@code intranet.corp.example}.
         */
        private java.util.List<String> sensitiveHosts = new java.util.ArrayList<>();

        /**
         * Hosts to always treat as safe (exact host or any subdomain). Overrides
         * both the heuristic and {@link #sensitiveHosts}. Use to un-block a page
         * the heuristic flagged that you know is fine to read.
         */
        private java.util.List<String> trustedHosts = new java.util.ArrayList<>();
    }
}

