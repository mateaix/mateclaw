package vip.mate.tool.builtin;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.tool.browser.BrowserLauncher;
import vip.mate.tool.document.FilenameSanitizer;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.document.GeneratedFileLink;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Render arbitrary HTML to a PNG and return a one-time download URL.
 *
 * <p>Bridges the gap between HTML-producing skills (architecture diagrams,
 * infographics, dashboards) and IM channels whose native message types only
 * accept rasterised images. The PNG is stashed in {@link GeneratedFileCache}
 * with an {@code image/png} MIME so the per-channel sniff layer
 * ({@code WeComChannelAdapter}, {@code DingTalkChannelAdapter}, …) uploads it
 * as a native image attachment rather than a fallback file.
 */
@Slf4j
@Component
public class HtmlImageRenderTool {

    private static final String PNG_MIME = "image/png";
    private static final int DEFAULT_VIEWPORT_WIDTH = 1440;
    private static final int DEFAULT_VIEWPORT_HEIGHT = 900;
    private static final int MAX_VIEWPORT_DIMENSION = 4096;
    private static final int SET_CONTENT_TIMEOUT_MS = 15_000;

    private final GeneratedFileCache cache;

    private volatile Playwright sharedPlaywright;
    private final Object playwrightLock = new Object();

    public HtmlImageRenderTool(GeneratedFileCache cache) {
        this.cache = cache;
    }

    @Tool(description = """
        Render HTML to a PNG image and return a one-time download URL.

        Use this whenever the user wants an HTML artifact (architecture
        diagram, infographic, dashboard, mockup, ...) delivered as an
        *image* — especially when the chat is happening on an IM channel
        (WeCom / 企业微信, DingTalk, Feishu, Telegram, Discord) where users
        cannot click through a raw HTML link.

        The returned URL is `/api/v1/files/generated/<id>` with MIME
        `image/png`. Channel adapters detect this MIME and upload the
        bytes as a native image message, so the recipient sees an inline
        picture rather than a file attachment.

        Typical workflow when paired with an HTML-producing skill:
          1. write_file(filePath="diagram.html", content="<html>...")
          2. render_html_image(filePath="diagram.html", filename="diagram")
          3. return the markdown link to the user

        Or directly, without going through disk:
          1. render_html_image(html="<html>...", filename="diagram")

        Exactly one of `filePath` or `html` must be supplied. The link is
        valid for 10 minutes.
        """)
    public String render_html_image(
            @ToolParam(description = "Path to an HTML file on disk (workspace-relative or absolute). Mutually exclusive with `html`.", required = false)
            String filePath,
            @ToolParam(description = "Inline HTML source. Mutually exclusive with `filePath`.", required = false)
            String html,
            @ToolParam(description = "Output filename without extension, e.g. 'architecture'")
            String filename,
            @ToolParam(description = "Viewport width in px (default 1440, max 4096)", required = false)
            Integer width,
            @ToolParam(description = "Viewport height in px (default 900, max 4096). Ignored when fullPage=true except as initial layout hint.", required = false)
            Integer height,
            @ToolParam(description = "Capture full scrollable page (default true). Set false to only capture the viewport.", required = false)
            Boolean fullPage) {

        String source;
        try {
            source = resolveHtml(filePath, html);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.error("[HtmlImageRender] failed to load HTML: {}", e.getMessage(), e);
            return "Error: failed to load HTML — " + e.getMessage();
        }

        int vw = clampViewport(width, DEFAULT_VIEWPORT_WIDTH);
        int vh = clampViewport(height, DEFAULT_VIEWPORT_HEIGHT);
        boolean full = fullPage == null || fullPage;
        String displayName = FilenameSanitizer.sanitize(filename, "image", ".png") + ".png";

        byte[] pngBytes;
        try {
            pngBytes = renderToPng(source, vw, vh, full);
        } catch (Exception e) {
            log.error("[HtmlImageRender] render failed for {}: {}", displayName, e.getMessage(), e);
            String hint = e.getMessage() != null && e.getMessage().contains("Executable doesn't exist")
                    ? " Hint: run `mvn exec:java -e -Dexec.mainClass=\"com.microsoft.playwright.CLI\" -Dexec.args=\"install chromium\"` to install the bundled browser."
                    : "";
            return "Render failed: " + e.getMessage() + hint;
        }

        log.info("[HtmlImageRender] rendered {} ({} bytes, viewport={}x{}, fullPage={})",
                displayName, pngBytes.length, vw, vh, full);
        return GeneratedFileLink.resultZh(pngBytes, displayName, PNG_MIME, cache, "图片");
    }

    private String resolveHtml(String filePath, String inlineHtml) throws Exception {
        boolean hasPath = filePath != null && !filePath.isBlank();
        boolean hasInline = inlineHtml != null && !inlineHtml.isBlank();
        if (hasPath == hasInline) {
            throw new IllegalArgumentException(
                    "Provide exactly one of `filePath` or `html` (not both, not neither).");
        }
        if (hasPath) {
            Path path = WorkspacePathGuard.validatePath(filePath);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("HTML file not found: " + filePath);
            }
            if (Files.isDirectory(path)) {
                throw new IllegalArgumentException("Path is a directory, not a file: " + filePath);
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        return inlineHtml;
    }

    private byte[] renderToPng(String source, int viewportWidth, int viewportHeight, boolean fullPage) {
        Playwright pw = getOrCreatePlaywright();
        BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(BrowserLauncher.chromiumLaunchArgs());
        Browser browser = pw.chromium().launch(opts);
        try {
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(viewportWidth, viewportHeight)
                    .setDeviceScaleFactor(2.0));
            try {
                Page page = ctx.newPage();
                page.setContent(source, new Page.SetContentOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE)
                        .setTimeout(SET_CONTENT_TIMEOUT_MS));
                return page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(fullPage)
                        .setType(ScreenshotType.PNG));
            } finally {
                try { ctx.close(); } catch (Exception ignored) {}
            }
        } finally {
            try { browser.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Lazily create one Playwright instance per JVM. Playwright.create()
     * spawns a Node.js child process and costs ~1–2 s; keeping the instance
     * around means subsequent screenshots only pay the browser-launch cost.
     */
    private Playwright getOrCreatePlaywright() {
        Playwright local = sharedPlaywright;
        if (local != null) return local;
        synchronized (playwrightLock) {
            if (sharedPlaywright == null) {
                sharedPlaywright = Playwright.create();
            }
            return sharedPlaywright;
        }
    }

    private static int clampViewport(Integer requested, int fallback) {
        if (requested == null || requested <= 0) return fallback;
        return Math.min(requested, MAX_VIEWPORT_DIMENSION);
    }
}
