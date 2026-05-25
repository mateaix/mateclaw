package vip.mate.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manual end-to-end probe for BrowserLauncher. Not a JUnit test — run via
 * {@code mvn -q compile exec:java -Dexec.mainClass=vip.mate.tool.browser.BrowserLauncherManualProbe
 * -Dexec.classpathScope=test}
 *
 * <p>Exercises the real launcher on the host machine: creates a Playwright instance,
 * asks the launcher to pick a strategy, navigates to about:blank, screenshots, and
 * reports which strategy succeeded. Exits non-zero if nothing worked.
 */
public final class BrowserLauncherManualProbe {

    public static void main(String[] args) {
        System.out.println("=== BrowserLauncher probe ===");
        System.out.println("os.name = " + System.getProperty("os.name"));
        System.out.println("user    = " + System.getProperty("user.name"));

        BrowserProperties props = new BrowserProperties();
        BrowserLauncher launcher = new BrowserLauncher(props);

        System.out.println("\nCandidate paths on this OS:");
        for (Path p : BrowserLauncher.systemBrowserCandidates()) {
            System.out.printf("  %s  [%s]%n", p, Files.exists(p) ? "FOUND" : "missing");
        }

        System.out.println("\nDiagnostics report:");
        BrowserDiagnosticsService diag = new BrowserDiagnosticsService(props);
        BrowserDiagnosticsService.Report report = diag.run();
        System.out.println(BrowserDiagnosticsService.summarise(report));

        System.out.println("\nAttempting real launch via Playwright...");
        int exit = 0;
        try (Playwright pw = Playwright.create()) {
            BrowserLauncher.Result r = launcher.launch(pw, /* headed */ false);
            System.out.println("Launch trace:\n" + BrowserLauncher.formatTrace(r.getAttempts()));
            if (!r.isSuccess()) {
                System.err.println("FAIL: " + r.getFailureSummary());
                exit = 1;
            } else {
                try (Browser browser = r.getBrowser()) {
                    Page page = r.getPage();
                    page.navigate("about:blank");
                    byte[] png = page.screenshot();
                    Path shot = Paths.get(System.getProperty("java.io.tmpdir"),
                            "mateclaw-browser-probe-" + System.currentTimeMillis() + ".png");
                    Files.write(shot, png);
                    System.out.printf("OK via %s: page title='%s', screenshot=%d bytes -> %s%n",
                            r.getStrategy(), page.title(), png.length, shot);
                }
            }
        } catch (Exception e) {
            System.err.println("EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            exit = 2;
        }
        System.exit(exit);
    }

    private BrowserLauncherManualProbe() {}
}
