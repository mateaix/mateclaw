package vip.mate.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

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
 * Manual probe for the EXTERNAL_CDP cleanup path. Mirrors the production launch +
 * close logic without going through the private launcher path, so we can verify on
 * a real Windows machine that:
 *
 * <ol>
 *   <li>Chrome spawned with {@code --user-data-dir=<temp>} prints "DevTools listening on..."
 *       to stderr (so {@code readDevToolsUrl} can parse it) — fix #1.</li>
 *   <li>After the session closes (browser disconnect → process destroyForcibly →
 *       wait → deleteQuietly), the temp profile dir is fully removed — follow-up cleanup fix.</li>
 * </ol>
 *
 * <p>Run via:
 * {@code mvn -f mateclaw-server/pom.xml exec:java
 *   -Dexec.mainClass=vip.mate.tool.browser.ExternalCdpCleanupProbe -Dexec.classpathScope=test}
 */
public final class ExternalCdpCleanupProbe {

    public static void main(String[] args) throws Exception {
        System.out.println("=== ExternalCdpCleanupProbe ===");
        System.out.println("os.name = " + System.getProperty("os.name"));

        Path browserBin = pickBrowserBin();
        if (browserBin == null) {
            System.err.println("FAIL: no Chrome/Edge/Brave found via systemBrowserCandidates");
            System.exit(1);
            return;
        }
        System.out.println("Browser binary: " + browserBin);

        Path userDataDir = Files.createTempDirectory("mateclaw-cdp-probe-");
        System.out.println("Temp profile:   " + userDataDir);

        // Same flag set as BrowserLauncher.tryExternalCdpLaunch.
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> command = new ArrayList<>();
        command.add(browserBin.toString());
        command.add("--remote-debugging-port=0");
        command.add("--user-data-dir=" + userDataDir.toAbsolutePath());
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-extensions");
        command.add("--disable-background-networking");
        command.add("--headless=new");
        if (isWindows) command.add("--no-sandbox");
        command.add("about:blank");

        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
        Process proc = pb.start();
        System.out.println("Chrome PID:     " + proc.pid());

        String wsUrl = readDevToolsUrl(proc, 20);
        System.out.println("Got DevTools:   " + wsUrl);
        String cdpBase = wsUrl.replaceFirst("^ws://", "http://").replaceFirst("/devtools/.*", "");

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().connectOverCDP(cdpBase);
            BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            page.navigate("about:blank");
            System.out.println("Page loaded:    title='" + page.title() + "'");

            // === Mirror BrowserSession.close() for the EXTERNAL_CDP path ===
            long t0 = System.currentTimeMillis();
            try { browser.close(); } catch (Exception ignored) {}
            try {
                List<ProcessHandle> children = proc.descendants().toList();
                System.out.println("Chrome children: " + children.size());
                proc.destroyForcibly();
                for (ProcessHandle h : children) {
                    try { h.destroyForcibly(); } catch (Exception ignored) {}
                }
                proc.waitFor(5, TimeUnit.SECONDS);
                for (ProcessHandle h : children) {
                    try { h.onExit().get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            BrowserLauncher.deleteQuietly(userDataDir);
            long elapsedMs = System.currentTimeMillis() - t0;
            System.out.printf("Cleanup ran in %dms%n", elapsedMs);
        }

        // Verify the dir is gone.
        if (Files.exists(userDataDir)) {
            long leftBytes = sizeOf(userDataDir);
            long leftFiles;
            try (var s = Files.walk(userDataDir)) { leftFiles = s.count() - 1; }
            System.err.printf("LEAK: profile dir still exists with %d files / %d bytes -> %s%n",
                    leftFiles, leftBytes, userDataDir);
            System.err.println("Remaining files:");
            try (var s = Files.walk(userDataDir)) {
                s.filter(Files::isRegularFile).forEach(p ->
                        System.err.println("  " + userDataDir.relativize(p)));
            }
            System.exit(2);
        } else {
            System.out.println("CLEAN: profile dir fully deleted ✓");
        }
    }

    private static Path pickBrowserBin() {
        for (Path candidate : BrowserLauncher.systemBrowserCandidates()) {
            if (Files.exists(candidate)) return candidate;
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
                        throw new IllegalStateException("Chrome exited early. stderr=" + accumulated);
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
        throw new IllegalStateException("Timed out waiting for 'DevTools listening on'");
    }

    private static long sizeOf(Path dir) {
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (Exception e) { return 0; }
            }).sum();
        } catch (Exception e) {
            return -1;
        }
    }

    private ExternalCdpCleanupProbe() {}
}
