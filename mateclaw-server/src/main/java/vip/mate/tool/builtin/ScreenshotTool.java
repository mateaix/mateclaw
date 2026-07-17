package vip.mate.tool.builtin;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import vip.mate.auth.service.AuthService;
import vip.mate.tool.browser.BrowserLauncher;
import vip.mate.tool.document.FilenameSanitizer;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.document.GeneratedFileLink;
import vip.mate.workspace.artifact.service.WorkspaceArtifactService;

/**
 * Built-in tool: capture a screenshot of a MateClaw admin-console page and
 * return an embeddable image URL.
 *
 * <p>Purpose: let content skills illustrate a how-to article with <b>real</b>
 * product screenshots. The console lives behind JWT auth, so the tool mints a
 * short-lived token for the calling user, injects it into {@code localStorage}
 * before the SPA boots (Playwright init script), navigates to the requested
 * <b>same-origin relative path</b>, and screenshots the rendered page. The PNG
 * is stashed in {@link GeneratedFileCache}; the returned {@code /api/v1/files/
 * generated/<id>} URL can be embedded directly as {@code ![](url)} in a
 * gzh_package Markdown body.
 *
 * <p>Security: only relative in-app paths ({@code /chat}, {@code /channels}, …)
 * are allowed — no scheme/host, so the tool cannot be aimed at arbitrary hosts
 * (no SSRF). The injected token is a normal user token scoped to whoever is
 * driving the conversation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScreenshotTool {

    private static final String PNG_MIME = "image/png";
    private static final int DEFAULT_WIDTH = 1440;
    private static final int DEFAULT_HEIGHT = 900;
    private static final int NAV_TIMEOUT_MS = 20_000;
    private static final int DEFAULT_SETTLE_MS = 2500;
    private static final int MAX_SETTLE_MS = 8000;

    private final GeneratedFileCache cache;
    private final AuthService authService;
    private final WorkspaceArtifactService artifactService;

    @Value("${server.port:18088}")
    private int serverPort;

    @Tool(name = "capture_screenshot", description = """
        Capture a screenshot of a MateClaw admin-console page and return an
        embeddable image URL. Use this to put REAL product screenshots into a
        how-to / tutorial article (e.g. steps of using 内容工作室).

        `path` must be a relative in-app path starting with '/', e.g. '/chat',
        '/channels', '/agents', '/skills'. External URLs are rejected.

        The returned URL is `/api/v1/files/generated/<id>` (image/png). Embed it
        in a gzh_package Markdown body as `![说明](URL)` so the packaged article
        shows the real screenshot instead of a 【截图】placeholder.
        """)
    public String capture_screenshot(
            @ToolParam(description = "Relative in-app path, e.g. /chat, /channels, /agents, /skills")
            String path,
            @ToolParam(description = "Capture the full scrollable page (default false = just the viewport)", required = false)
            Boolean fullPage,
            @ToolParam(description = "Output filename without extension, e.g. 'step1-console'", required = false)
            String filename,
            @ToolParam(description = "Extra settle wait in ms after load for the SPA to render (default 2500, max 8000)", required = false)
            Integer waitMs,
            @Nullable ToolContext ctx) {

        if (path == null || path.isBlank()) {
            return "Error: path is required (a relative in-app path like /chat).";
        }
        String p = path.trim();
        if (p.contains("://") || p.startsWith("//") || !p.startsWith("/")) {
            return "Error: only relative in-app paths are allowed (must start with '/', no scheme/host). Got: " + path;
        }

        String token = mintToken();
        if (token == null || token.isBlank()) {
            return "Error: could not mint an auth token to render the console (no resolvable user).";
        }

        String url = "http://127.0.0.1:" + serverPort + p;
        int settle = waitMs == null ? DEFAULT_SETTLE_MS : Math.min(Math.max(waitMs, 0), MAX_SETTLE_MS);
        boolean full = fullPage != null && fullPage;
        String displayName = FilenameSanitizer.sanitize(filename, "screenshot", ".png") + ".png";

        byte[] png;
        try {
            png = render(url, token, full, settle);
        } catch (Exception e) {
            log.warn("[Screenshot] capture failed for {}: {}", p, e.getMessage());
            String hint = e.getMessage() != null && e.getMessage().contains("Executable doesn't exist")
                    ? " Hint: install the bundled browser (Playwright chromium)."
                    : "";
            return "Error: screenshot failed — " + e.getMessage() + hint;
        }

        log.info("[Screenshot] captured {} ({} bytes, fullPage={})", p, png.length, full);
        return GeneratedFileLink.resultZh(png, displayName, PNG_MIME, cache, "截图", ctx, artifactService);
    }

    private byte[] render(String url, String token, boolean fullPage, int settleMs) {
        try (Playwright pw = Playwright.create()) {
            BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(BrowserLauncher.chromiumLaunchArgs());
            Browser browser = pw.chromium().launch(opts);
            try {
                BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setViewportSize(DEFAULT_WIDTH, DEFAULT_HEIGHT)
                        .setDeviceScaleFactor(2.0)
                        .setLocale("zh-CN"));
                // Seed the JWT before any app script runs so the SPA boots
                // authenticated. Playwright evaluates the init script as-is, so it
                // must be raw statements — a "() => {...}" arrow would only be
                // defined, never called, leaving localStorage untouched (and the
                // SPA would render the login page instead).
                context.addInitScript("try { window.localStorage.setItem('token', '"
                        + token + "'); } catch (e) {}");
                try {
                    Page page = context.newPage();
                    page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(NAV_TIMEOUT_MS));
                    // The console holds an SSE stream, so 'networkidle' can never
                    // settle — use a fixed render delay instead.
                    page.waitForTimeout(settleMs);
                    return page.screenshot(new Page.ScreenshotOptions()
                            .setFullPage(fullPage)
                            .setType(ScreenshotType.PNG));
                } finally {
                    try { context.close(); } catch (Exception ignored) {}
                }
            } finally {
                try { browser.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** Mint a token for the current user, falling back to the default admin. */
    @Nullable
    private String mintToken() {
        String username = currentUsername();
        String token = username != null ? authService.renewToken(username) : null;
        if (token == null) {
            token = authService.renewToken("admin");
        }
        return token;
    }

    @Nullable
    private String currentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getName() != null
                    && !"anonymousUser".equals(auth.getName())) {
                return auth.getName();
            }
        } catch (Exception ignored) {
            // Async agent thread may have no security context — fall back to admin.
        }
        return null;
    }
}
