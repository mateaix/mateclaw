package vip.mate.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Multi-strategy browser launcher. Tries, in order: an existing CDP endpoint, a
 * user-configured executable, a Playwright channel, auto-detected system Chrome /
 * Edge / Brave, Playwright's bundled Chromium, and finally self-launching a system
 * browser with {@code --remote-debugging-port=0} and attaching over CDP (the same
 * pattern openfang uses).
 *
 * <p>Each attempt is recorded with its outcome so diagnostics can surface exactly
 * what failed and how the user can fix it.
 */
@Slf4j
@Component
public class BrowserLauncher {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");
    private static final boolean IS_MAC = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("mac");

    private final BrowserProperties props;

    public BrowserLauncher(BrowserProperties props) {
        this.props = props;
    }

    public BrowserProperties properties() {
        return props;
    }

    /**
     * Launch a browser session. Tries every available strategy until one succeeds.
     * The returned result always contains an {@code attempts} trace, even on success,
     * so callers can surface "what we ended up using".
     */
    public Result launch(Playwright pw, boolean headed) {
        List<Attempt> trace = new ArrayList<>();

        // 1. Explicit CDP endpoint — user manages the Chrome process
        String cdpUrl = props.getCdpUrl();
        if (cdpUrl != null && !cdpUrl.isBlank()) {
            Result r = tryCdp(pw, cdpUrl, trace, Strategy.CONFIG_CDP);
            if (r != null) return r;
        }

        // 2. Explicit executable path (property or env var)
        String explicitPath = firstNonBlank(props.getChromePath(), System.getenv("CHROME_PATH"));
        if (explicitPath != null) {
            Result r = tryExecutablePath(pw, explicitPath, headed, trace, Strategy.CONFIG_PATH);
            if (r != null) return r;
        }

        // 3. Explicit channel (chrome / msedge / etc.)
        String channel = props.getChannel();
        if (channel != null && !channel.isBlank()) {
            Result r = tryChannel(pw, channel, headed, trace, Strategy.CONFIG_CHANNEL);
            if (r != null) return r;
        }

        // 4. Prefer system browser via channel auto-detection (chrome, then msedge)
        if (props.isPreferSystem()) {
            for (String autoChannel : new String[]{"chrome", "msedge"}) {
                Result r = tryChannel(pw, autoChannel, headed, trace, Strategy.AUTO_CHANNEL);
                if (r != null) return r;
            }

            // 5. Scan well-known install paths and launch via executablePath
            for (Path candidate : systemBrowserCandidates()) {
                Result r = tryExecutablePath(pw, candidate.toString(), headed, trace, Strategy.AUTO_PATH);
                if (r != null) return r;
            }
        }

        // 6. Playwright's bundled Chromium (requires `playwright install`)
        Result bundled = tryBundled(pw, headed, trace);
        if (bundled != null) return bundled;

        // 7. Last resort: spawn system chrome with --remote-debugging-port=0 and attach via CDP.
        //    This bypasses Playwright's Node launcher entirely — useful when Playwright install is broken.
        if (props.isAllowExternalCdpFallback()) {
            Result external = tryExternalCdpLaunch(pw, headed, trace);
            if (external != null) return external;
        }

        // All strategies failed
        log.warn("[BrowserLauncher] All launch strategies failed. Trace:\n{}", formatTrace(trace));
        return Result.failure(trace, summariseFailure(trace));
    }

    // ==================== Strategy implementations ====================

    private Result tryCdp(Playwright pw, String url, List<Attempt> trace, Strategy strategy) {
        String normalized = normalizeCdpUrl(url);
        long t0 = System.currentTimeMillis();
        try {
            Browser browser = pw.chromium().connectOverCDP(normalized);
            BrowserContext context;
            Page page;
            List<BrowserContext> contexts = browser.contexts();
            if (!contexts.isEmpty()) {
                context = contexts.get(0);
                List<Page> pages = context.pages();
                page = pages.isEmpty() ? context.newPage() : pages.get(0);
            } else {
                context = browser.newContext();
                page = context.newPage();
            }
            long elapsed = System.currentTimeMillis() - t0;
            trace.add(Attempt.ok(strategy, "connectOverCDP(" + normalized + ")", elapsed));
            return Result.success(browser, context, page, true, normalized, strategy, trace);
        } catch (Exception e) {
            trace.add(Attempt.fail(strategy, "connectOverCDP(" + normalized + ")",
                    System.currentTimeMillis() - t0, e.getMessage()));
            return null;
        }
    }

    private Result tryExecutablePath(Playwright pw, String path, boolean headed,
                                     List<Attempt> trace, Strategy strategy) {
        if (!Files.exists(Path.of(path))) {
            trace.add(Attempt.fail(strategy, "executablePath=" + path, 0, "file not found"));
            return null;
        }
        long t0 = System.currentTimeMillis();
        try {
            BrowserType.LaunchOptions opts = baseLaunchOptions(headed)
                    .setExecutablePath(Path.of(path));
            Browser browser = pw.chromium().launch(opts);
            Result r = wrapLocalBrowser(browser, strategy, "executablePath=" + path,
                    System.currentTimeMillis() - t0, trace);
            return r;
        } catch (PlaywrightException e) {
            trace.add(Attempt.fail(strategy, "executablePath=" + path,
                    System.currentTimeMillis() - t0, e.getMessage()));
            return null;
        }
    }

    private Result tryChannel(Playwright pw, String channel, boolean headed,
                              List<Attempt> trace, Strategy strategy) {
        long t0 = System.currentTimeMillis();
        try {
            BrowserType.LaunchOptions opts = baseLaunchOptions(headed).setChannel(channel);
            Browser browser = pw.chromium().launch(opts);
            return wrapLocalBrowser(browser, strategy, "channel=" + channel,
                    System.currentTimeMillis() - t0, trace);
        } catch (PlaywrightException e) {
            trace.add(Attempt.fail(strategy, "channel=" + channel,
                    System.currentTimeMillis() - t0, e.getMessage()));
            return null;
        }
    }

    private Result tryBundled(Playwright pw, boolean headed, List<Attempt> trace) {
        long t0 = System.currentTimeMillis();
        try {
            Browser browser = pw.chromium().launch(baseLaunchOptions(headed));
            return wrapLocalBrowser(browser, Strategy.BUNDLED, "playwright-bundled-chromium",
                    System.currentTimeMillis() - t0, trace);
        } catch (PlaywrightException e) {
            trace.add(Attempt.fail(Strategy.BUNDLED, "playwright-bundled-chromium",
                    System.currentTimeMillis() - t0, e.getMessage()));
            return null;
        }
    }

    /**
     * Spawn a system browser ourselves with {@code --remote-debugging-port=0}, parse stderr
     * to recover the actual DevTools WebSocket URL, then attach via Playwright's CDP client.
     * This is the openfang pattern — it sidesteps Playwright's Node-based launcher entirely,
     * so it still works when `playwright install` has not been run or Node is flaky.
     */
    private Result tryExternalCdpLaunch(Playwright pw, boolean headed, List<Attempt> trace) {
        long t0 = System.currentTimeMillis();
        Path browserBin = null;
        for (Path candidate : systemBrowserCandidates()) {
            if (Files.exists(candidate)) {
                browserBin = candidate;
                break;
            }
        }
        if (browserBin == null) {
            trace.add(Attempt.fail(Strategy.EXTERNAL_CDP, "external-chrome-spawn",
                    System.currentTimeMillis() - t0, "no system browser executable found"));
            return null;
        }

        // Issue #40 — On Windows (and macOS) Chrome refuses to spawn a second instance against
        // the default user-data-dir if the user already has Chrome open: the new chrome.exe just
        // forwards its argv to the running instance and exits, so stderr never prints
        // "DevTools listening on ...". An isolated profile dir avoids that conflict and is also
        // what the openfang launcher does.
        Path userDataDir;
        try {
            userDataDir = Files.createTempDirectory("mateclaw-cdp-profile-");
        } catch (Exception e) {
            trace.add(Attempt.fail(Strategy.EXTERNAL_CDP, browserBin.toString(),
                    System.currentTimeMillis() - t0,
                    "failed to create isolated user-data-dir: " + e.getMessage()));
            return null;
        }

        List<String> command = new ArrayList<>();
        command.add(browserBin.toString());
        command.add("--remote-debugging-port=0");
        command.add("--user-data-dir=" + userDataDir.toAbsolutePath());
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-extensions");
        command.add("--disable-background-networking");
        if (props.isHeadless() && !headed) {
            command.add("--headless=new");
        }
        if (isRunningAsRoot() || IS_WINDOWS) {
            command.add("--no-sandbox");
        }
        if (isRunningInContainer()) {
            command.add("--disable-dev-shm-usage");
        }
        command.add("about:blank");

        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
        // SECURITY: don't leak the parent process's secrets (API keys, etc.) into chrome.
        // Keep only the vars Chrome actually needs to run. openfang does the same via env_clear.
        java.util.Map<String, String> env = pb.environment();
        java.util.Map<String, String> keep = new java.util.LinkedHashMap<>();
        for (String key : new String[]{"PATH", "HOME", "USERPROFILE", "SYSTEMROOT", "TEMP", "TMP", "TMPDIR",
                "APPDATA", "LOCALAPPDATA", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "DISPLAY", "WAYLAND_DISPLAY"}) {
            String v = env.get(key);
            if (v != null) keep.put(key, v);
        }
        env.clear();
        env.putAll(keep);

        Process proc;
        try {
            proc = pb.start();
        } catch (Exception e) {
            deleteQuietly(userDataDir);
            trace.add(Attempt.fail(Strategy.EXTERNAL_CDP, browserBin + " --remote-debugging-port",
                    System.currentTimeMillis() - t0, "spawn failed: " + e.getMessage()));
            return null;
        }

        String wsUrl;
        try {
            wsUrl = readDevToolsUrl(proc, props.getCdpTimeoutSeconds());
        } catch (Exception e) {
            proc.destroyForcibly();
            deleteQuietly(userDataDir);
            trace.add(Attempt.fail(Strategy.EXTERNAL_CDP, browserBin.toString(),
                    System.currentTimeMillis() - t0, e.getMessage()));
            return null;
        }

        // Derive http base — Playwright's connectOverCDP accepts ws:// directly, but http:// is safer.
        String cdpBase = wsUrl.replaceFirst("^ws://", "http://").replaceFirst("/devtools/.*", "");
        try {
            Browser browser = pw.chromium().connectOverCDP(cdpBase);
            BrowserContext context = browser.contexts().isEmpty()
                    ? browser.newContext()
                    : browser.contexts().get(0);
            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            long elapsed = System.currentTimeMillis() - t0;
            trace.add(Attempt.ok(Strategy.EXTERNAL_CDP, browserBin + " + connectOverCDP(" + cdpBase + ")", elapsed));
            // Hand ownership of `proc` and `userDataDir` to the caller — the session that
            // consumes this Result is responsible for destroyForcibly() on the process and
            // deleteQuietly() on the dir when it stops. Spring's @PreDestroy on BrowserUseTool
            // closes every active session on shutdown, so a clean JVM exit will not leak.
            return Result.successOwned(browser, context, page, cdpBase, Strategy.EXTERNAL_CDP, trace,
                    userDataDir, proc);
        } catch (Exception e) {
            proc.destroyForcibly();
            deleteQuietly(userDataDir);
            trace.add(Attempt.fail(Strategy.EXTERNAL_CDP, "connectOverCDP(" + cdpBase + ")",
                    System.currentTimeMillis() - t0, e.getMessage()));
            return null;
        }
    }

    /**
     * Best-effort recursive delete with a single short retry. Per-file failures are
     * swallowed so a single locked file (Windows: Chrome leaves lockfiles open briefly
     * after exit) does not abort cleanup of the rest of the directory.
     *
     * <p>The retry is necessary because Chrome's GPU process and segmentation_platform
     * DB take a few hundred milliseconds longer than the parent chrome.exe to flush
     * and release file handles even after destroyForcibly() — without the retry,
     * ~6 files (ShaderCache, ukm_db) are typically left on disk per session on Windows.
     *
     * <p>Public so {@code BrowserSession.close()} can reuse it without duplicating logic.
     */
    public static void deleteQuietly(Path dir) {
        if (dir == null) return;
        deleteOnce(dir);
        if (!Files.exists(dir)) return;
        // Second pass: give lingering Chrome subprocesses ~500ms to release locks.
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        deleteOnce(dir);
    }

    private static void deleteOnce(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }

    // ==================== Helpers ====================

    private BrowserType.LaunchOptions baseLaunchOptions(boolean headed) {
        BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(!headed);
        List<String> args = chromiumLaunchArgs();
        if (!args.isEmpty()) {
            opts.setArgs(args);
        }
        return opts;
    }

    private Result wrapLocalBrowser(Browser browser, Strategy strategy, String desc,
                                    long elapsedMs, List<Attempt> trace) {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(props.getViewportWidth(), props.getViewportHeight())
                .setLocale("zh-CN"));
        Page page = context.newPage();
        trace.add(Attempt.ok(strategy, desc, elapsedMs));
        return Result.success(browser, context, page, false, null, strategy, trace);
    }

    public static List<String> chromiumLaunchArgs() {
        List<String> args = new ArrayList<>();
        boolean inContainer = isRunningInContainer();
        boolean asRoot = isRunningAsRoot();
        if (IS_WINDOWS || inContainer || asRoot) {
            args.add("--no-sandbox");
        }
        if (inContainer) {
            args.add("--disable-dev-shm-usage");
        }
        if (IS_WINDOWS) {
            args.add("--disable-gpu");
        }
        // Chromium does not honor the JVM proxy selector / system properties, so
        // route it explicitly when a global proxy is active. Bypass loopback so
        // the local CDP endpoint and local services stay reachable.
        String proxyServer = vip.mate.system.proxy.ProxyManager.chromeProxyServer();
        if (proxyServer != null && !proxyServer.isBlank()) {
            args.add("--proxy-server=" + proxyServer);
            args.add("--proxy-bypass-list=<-loopback>");
        }
        return args;
    }

    /** Platform-specific candidate paths — same list openfang uses, extended for issue #40. */
    public static List<Path> systemBrowserCandidates() {
        List<Path> paths = new ArrayList<>();
        if (IS_WINDOWS) {
            String pf = System.getenv("ProgramFiles");
            String pf86 = System.getenv("ProgramFiles(x86)");
            String local = System.getenv("LOCALAPPDATA");
            for (String root : new String[]{pf, pf86}) {
                if (root == null || root.isBlank()) continue;
                paths.add(Path.of(root, "Google", "Chrome", "Application", "chrome.exe"));
                paths.add(Path.of(root, "Microsoft", "Edge", "Application", "msedge.exe"));
                paths.add(Path.of(root, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"));
            }
            if (local != null && !local.isBlank()) {
                paths.add(Path.of(local, "Google", "Chrome", "Application", "chrome.exe"));
                paths.add(Path.of(local, "Microsoft", "Edge", "Application", "msedge.exe"));
                // Chinese Chromium-based browsers — they ignore Playwright's "chrome" channel
                // detection but launch fine via executablePath. They tend to install per-user.
                paths.add(Path.of(local, "360ChromeX", "Chrome", "Application", "360ChromeX.exe"));
                paths.add(Path.of(local, "360Chrome", "Chrome", "Application", "360chrome.exe"));
                paths.add(Path.of(local, "360se6", "Application", "360se.exe"));
                paths.add(Path.of(local, "Tencent", "QQBrowser", "QQBrowser.exe"));
                paths.add(Path.of(local, "sogouexplorer", "SogouExplorer.exe"));
            }
            // Issue #40: many users install Chrome/Edge to a non-system drive (D:, E:, ...).
            // Scan only mounted drives to avoid spurious 5s "no media" timeouts on empty letters.
            for (java.io.File drive : java.io.File.listRoots()) {
                String letter = drive.getPath();
                if (letter == null || letter.isBlank()) continue;
                if (!drive.exists()) continue;
                String upper = letter.toUpperCase(Locale.ROOT);
                if (upper.startsWith("C:")) continue; // already covered by ProgramFiles env vars
                for (String pfDir : new String[]{"Program Files", "Program Files (x86)"}) {
                    paths.add(Path.of(letter, pfDir, "Google", "Chrome", "Application", "chrome.exe"));
                    paths.add(Path.of(letter, pfDir, "Microsoft", "Edge", "Application", "msedge.exe"));
                    paths.add(Path.of(letter, pfDir, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"));
                }
            }
        } else if (IS_MAC) {
            paths.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
            paths.add(Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"));
            paths.add(Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
            paths.add(Path.of("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"));
        } else {
            // Linux
            paths.add(Path.of("/usr/bin/google-chrome"));
            paths.add(Path.of("/usr/bin/google-chrome-stable"));
            paths.add(Path.of("/usr/bin/chromium"));
            paths.add(Path.of("/usr/bin/chromium-browser"));
            paths.add(Path.of("/snap/bin/chromium"));
            paths.add(Path.of("/usr/bin/microsoft-edge"));
            paths.add(Path.of("/usr/bin/microsoft-edge-stable"));
            paths.add(Path.of("/usr/bin/brave-browser"));
        }
        return paths;
    }

    public static boolean isRunningInContainer() {
        try {
            if (Files.exists(Path.of("/.dockerenv"))) return true;
            Path cgroup = Path.of("/proc/1/cgroup");
            if (Files.exists(cgroup)) {
                String content = Files.readString(cgroup);
                return content.contains("docker") || content.contains("kubepods") || content.contains("containerd");
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean isRunningAsRoot() {
        if (IS_WINDOWS) return false;
        try {
            Path self = Path.of("/proc/self/status");
            if (Files.exists(self)) {
                for (String line : Files.readAllLines(self)) {
                    if (line.startsWith("Uid:")) {
                        String[] parts = line.split("\\s+");
                        return parts.length > 1 && "0".equals(parts[1]);
                    }
                }
            }
            String userName = System.getProperty("user.name", "");
            return "root".equals(userName);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalizeCdpUrl(String url) {
        String s = url.trim();
        if (!s.startsWith("http")) {
            s = "http://" + s;
        }
        s = s.replace("://localhost:", "://127.0.0.1:");
        s = s.replace("://localhost/", "://127.0.0.1/");
        if (s.endsWith("://localhost")) {
            s = s.replace("://localhost", "://127.0.0.1");
        }
        return s;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String readDevToolsUrl(Process proc, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder accumulated = new StringBuilder();
            String line;
            while (System.currentTimeMillis() < deadline) {
                if (!reader.ready()) {
                    if (!proc.isAlive()) {
                        throw new IllegalStateException(
                                "Chromium exited before printing DevTools URL. stderr=" + accumulated);
                    }
                    Thread.sleep(50);
                    continue;
                }
                line = reader.readLine();
                if (line == null) break;
                accumulated.append(line).append('\n');
                int idx = line.indexOf("DevTools listening on ");
                if (idx >= 0) {
                    return line.substring(idx + "DevTools listening on ".length()).trim();
                }
            }
        }
        throw new IllegalStateException(
                "Timed out (" + timeoutSeconds + "s) waiting for 'DevTools listening on' from chromium stderr");
    }

    public static String formatTrace(List<Attempt> trace) {
        StringBuilder sb = new StringBuilder();
        for (Attempt a : trace) {
            sb.append(String.format("  [%s] %s  %-7s %dms  %s%n",
                    a.strategy(), a.ok() ? "\u2713" : "\u2717", a.strategy().name(),
                    a.elapsedMs(), a.ok() ? a.detail() : (a.detail() + " \u2014 " + a.error())));
        }
        return sb.toString();
    }

    private static String summariseFailure(List<Attempt> trace) {
        StringBuilder sb = new StringBuilder("Browser launch failed. Tried: ");
        for (int i = 0; i < trace.size(); i++) {
            if (i > 0) sb.append("; ");
            Attempt a = trace.get(i);
            sb.append(a.strategy().name()).append(" ").append(a.ok() ? "ok" : "(" + brief(a.error()) + ")");
        }
        return sb.toString();
    }

    private static String brief(String err) {
        if (err == null) return "unknown";
        String first = err.lines().findFirst().orElse(err);
        return first.length() > 120 ? first.substring(0, 120) + "..." : first;
    }

    // ==================== Types ====================

    public enum Strategy {
        /** User-configured CDP endpoint (mateclaw.browser.cdp-url). */
        CONFIG_CDP,
        /** User-configured executable path (mateclaw.browser.chrome-path or CHROME_PATH env). */
        CONFIG_PATH,
        /** User-configured channel (mateclaw.browser.channel). */
        CONFIG_CHANNEL,
        /** Auto-detected Playwright channel (chrome, msedge). */
        AUTO_CHANNEL,
        /** Auto-detected system browser on well-known install paths. */
        AUTO_PATH,
        /** Playwright's bundled Chromium (requires `playwright install`). */
        BUNDLED,
        /** Spawn system chrome with --remote-debugging-port=0 and attach via CDP. */
        EXTERNAL_CDP
    }

    public record Attempt(Strategy strategy, String detail, long elapsedMs, boolean ok, String error) {
        static Attempt ok(Strategy s, String d, long ms) { return new Attempt(s, d, ms, true, null); }
        static Attempt fail(Strategy s, String d, long ms, String e) { return new Attempt(s, d, ms, false, e); }
    }

    @Getter
    public static final class Result {
        private final Browser browser;
        private final BrowserContext context;
        private final Page page;
        private final boolean connectedViaCdp;
        private final String cdpUrl;
        private final Strategy strategy;
        private final List<Attempt> attempts;
        private final boolean success;
        private final String failureSummary;
        /**
         * Temp profile directory created for {@link Strategy#EXTERNAL_CDP}.
         * Null for every other strategy (caller does not own the user data).
         * The session that consumes this Result is responsible for deleting the
         * directory after it stops the browser; {@link BrowserLauncher#deleteQuietly}
         * is provided as the canonical implementation.
         */
        private final Path userDataDir;
        /**
         * The Chrome subprocess we spawned for {@link Strategy#EXTERNAL_CDP}.
         * Null otherwise. The session must {@code destroyForcibly()} it on stop —
         * closing the Playwright {@code Browser} only severs the CDP connection.
         */
        private final Process ownedProcess;

        private Result(Browser browser, BrowserContext context, Page page,
                       boolean connectedViaCdp, String cdpUrl, Strategy strategy,
                       List<Attempt> attempts, boolean success, String failureSummary,
                       Path userDataDir, Process ownedProcess) {
            this.browser = browser;
            this.context = context;
            this.page = page;
            this.connectedViaCdp = connectedViaCdp;
            this.cdpUrl = cdpUrl;
            this.strategy = strategy;
            this.attempts = attempts;
            this.success = success;
            this.failureSummary = failureSummary;
            this.userDataDir = userDataDir;
            this.ownedProcess = ownedProcess;
        }

        static Result success(Browser browser, BrowserContext context, Page page,
                              boolean cdp, String cdpUrl, Strategy strategy, List<Attempt> attempts) {
            return new Result(browser, context, page, cdp, cdpUrl, strategy, attempts, true, null, null, null);
        }

        static Result successOwned(Browser browser, BrowserContext context, Page page,
                                   String cdpUrl, Strategy strategy, List<Attempt> attempts,
                                   Path userDataDir, Process ownedProcess) {
            return new Result(browser, context, page, true, cdpUrl, strategy, attempts, true, null,
                    userDataDir, ownedProcess);
        }

        static Result failure(List<Attempt> attempts, String summary) {
            return new Result(null, null, null, false, null, null, attempts, false, summary, null, null);
        }
    }
}
