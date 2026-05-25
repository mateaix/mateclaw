package vip.mate.tool.builtin;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.tool.browser.BrowserDiagnosticsService;
import vip.mate.tool.browser.BrowserLauncher;
import vip.mate.tool.browser.UrlSafetyChecker;

import java.net.Socket;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * 浏览器自动化工具
 * 基于 Playwright Java，实现 action-based 浏览器自动化 API。
 * 支持 start / stop / open / snapshot / screenshot / click / type / eval / connect_cdp / list_cdp_targets。
 */
@Slf4j
@Component
public class BrowserUseTool {

    private static final long IDLE_TIMEOUT_MINUTES = 30;
    private static final int MAX_SNAPSHOT_LENGTH = 20_000;
    private static final int CDP_SCAN_PORT_MIN = 9000;
    private static final int CDP_SCAN_PORT_MAX = 10000;

    /** SSE broadcaster for pushing browser actions to the frontend in real time. */
    private final vip.mate.channel.web.ChatStreamTracker streamTracker;
    private final BrowserLauncher launcher;
    private final BrowserDiagnosticsService diagnostics;

    public BrowserUseTool(vip.mate.channel.web.ChatStreamTracker streamTracker,
                          BrowserLauncher launcher,
                          BrowserDiagnosticsService diagnostics) {
        this.streamTracker = streamTracker;
        this.launcher = launcher;
        this.diagnostics = diagnostics;
    }

    /**
     * 共享 Playwright 实例（Node.js 进程）。
     * Playwright.create() 启动一个 Node.js 子进程，耗时 1-2 秒。
     * 复用同一实例可将后续 start/connect_cdp 的延迟从 ~98s 降至 ~1s。
     */
    private volatile Playwright sharedPlaywright;
    private final Object playwrightLock = new Object();

    private final ConcurrentHashMap<String, BrowserSession> sessions = new ConcurrentHashMap<>();

    /**
     * RFC-063r §2.5 transition: ToolContext for the current invocation, set
     * at the @Tool entry point and read by {@link #broadcastBrowserEvent}.
     * Tool calls are serialized per ToolExecutionExecutor instance so this
     * volatile field is safe; the field is read-only inside the action
     * handlers.
     */
    private volatile ToolContext currentToolContext;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "browser-idle-watchdog");
        t.setDaemon(true);
        return t;
    });

    @Tool(description = """
        Control a browser (Playwright with multi-strategy launch: system Chrome/Edge channel, explicit path, bundled, or external CDP).
        Default is headless. Use headed=true with action=start for a visible window.
        Typical flow: start → open(url) → snapshot → click/type → stop.
        If start fails, run action=diagnose for a full report of what's missing and how to fix it.

        SCOPE — use this tool ONLY for tasks that require driving a real browser:
        clicking, typing into forms, taking screenshots, executing JS in page context, or
        interacting with sites that need a logged-in session. For plain web search or
        retrieving public page content, prefer the `search` tool — do not call `browser_use`
        as a search alternative.

        Supported actions:
        - start: Launch a new browser (tries system Chrome, system Edge, then Playwright bundled). Optional headed=true.
        - stop: Close browser. If connected via CDP, only disconnects (Chrome keeps running).
        - open: Navigate to a URL. Requires url parameter. Auto-starts browser if not running.
        - snapshot: Get page text content, interactive elements, and title.
        - screenshot: Take a screenshot. Optional path to save file; returns base64 if no path.
        - click: Click an element. Requires selector (CSS selector).
        - type: Type text into an element. Requires selector and text.
        - eval: Execute JavaScript on the page. Requires code parameter. Top-level await is supported; use `return` to surface a value.
        - connect_cdp: Connect to an existing Chrome via CDP. Requires url (e.g. "http://localhost:9222").
        - list_cdp_targets: Scan local ports (9000-10000) for CDP endpoints. Optional cdpPort for single port.
        - navigate_back: Go back in browser history.
        - diagnose: Run a self-check — reports which launch strategies are available and what to install if none are.
        """)
    public String browser_use(
            @ToolParam(description = "Action: start|stop|open|snapshot|screenshot|click|type|eval|connect_cdp|list_cdp_targets|navigate_back|diagnose") String action,
            @ToolParam(description = "URL to navigate to (for open), or CDP base URL (for connect_cdp, e.g. http://localhost:9222)", required = false) String url,
            @ToolParam(description = "CSS selector for target element (for click/type)", required = false) String selector,
            @ToolParam(description = "Text to type (for action=type)", required = false) String text,
            @ToolParam(description = "JavaScript code to execute (for action=eval). Top-level await is allowed; add `return` to return a value when the snippet uses await.", required = false) String code,
            @ToolParam(description = "File path to save screenshot (for action=screenshot)", required = false) String path,
            @ToolParam(description = "Launch visible browser window (for action=start, default false)", required = false) Boolean headed,
            @ToolParam(description = "Single CDP port to scan (for action=list_cdp_targets)", required = false) Integer cdpPort,
            // RFC-063r §2.5: hidden from LLM by JsonSchemaGenerator.
            @Nullable ToolContext ctx
    ) {
        // The conversationId resolution lives in broadcastBrowserEvent below;
        // capture the ctx into a field so the helper can read it without
        // passing it down every action handler. Race-free because tool calls
        // are serialized per executor.
        this.currentToolContext = ctx;
        if (action == null || action.isBlank()) {
            return error("action is required");
        }

        String sessionKey = "default";
        log.info("[BrowserUse] action={}, url={}, selector={}, headed={}, cdpPort={}", action, url, selector, headed, cdpPort);

        try {
            return switch (action.toLowerCase().trim()) {
                case "start" -> doStart(sessionKey, Boolean.TRUE.equals(headed));
                case "stop" -> doStop(sessionKey);
                case "open" -> doOpen(sessionKey, url);
                case "snapshot" -> doSnapshot(sessionKey);
                case "screenshot" -> doScreenshot(sessionKey, path);
                case "click" -> doClick(sessionKey, selector);
                case "type" -> doType(sessionKey, selector, text);
                case "eval" -> doEval(sessionKey, code);
                case "connect_cdp" -> doConnectCdp(sessionKey, url);
                case "list_cdp_targets" -> doListCdpTargets(cdpPort);
                case "navigate_back" -> doNavigateBack(sessionKey);
                case "diagnose" -> doDiagnose();
                default -> error("Unknown action: " + action + ". Supported: start, stop, open, snapshot, screenshot, click, type, eval, connect_cdp, list_cdp_targets, navigate_back, diagnose");
            };
        } catch (PlaywrightException e) {
            log.error("[BrowserUse] Playwright error: {}", e.getMessage());
            return error("Browser error: " + e.getMessage());
        } catch (Exception e) {
            log.error("[BrowserUse] Unexpected error: {}", e.getMessage(), e);
            return error("Unexpected error: " + e.getMessage());
        }
    }

    // ==================== Playwright Lifecycle ====================

    /**
     * 获取或创建共享 Playwright 实例（双重检查锁定）。
     * 首次调用约 1-2s（启动 Node.js），后续调用 ~0ms。
     *
     * <p>Issue #40: Playwright.create() spawns a Node.js driver subprocess by extracting
     * a bundled binary to a temp directory. On Windows this can fail when the user profile
     * path contains non-ASCII characters or when antivirus quarantines the extracted exe.
     * We wrap the failure with a message that points the LLM/user at action=diagnose so
     * they don't get a bare stack trace.
     */
    private Playwright getOrCreatePlaywright() {
        Playwright pw = sharedPlaywright;
        if (pw != null) {
            return pw;
        }
        synchronized (playwrightLock) {
            pw = sharedPlaywright;
            if (pw != null) {
                return pw;
            }
            log.info("[BrowserUse] Creating shared Playwright instance...");
            long start = System.currentTimeMillis();
            try {
                pw = Playwright.create();
            } catch (Throwable t) {
                String os = System.getProperty("os.name", "?");
                log.error("[BrowserUse] Playwright.create() failed on {}: {}", os, t.getMessage(), t);
                throw new PlaywrightException(
                        "Failed to start Playwright driver on " + os + ": " + t.getMessage()
                                + ". Common causes on Windows: (a) user profile path contains non-ASCII chars,"
                                + " (b) antivirus blocked the extracted driver exe, (c) %TEMP% is on a read-only volume."
                                + " Run action=diagnose for a full report.", t);
            }
            sharedPlaywright = pw;
            log.info("[BrowserUse] Playwright instance created in {}ms", System.currentTimeMillis() - start);
            return pw;
        }
    }

    // ==================== Browser Event Broadcasting ====================

    /**
     * 向前端广播浏览器操作事件（通过 SSE）
     */
    private void broadcastBrowserEvent(String action, boolean success, String url, String title,
                                        String screenshot, long durationMs) {
        String conversationId = ToolExecutionContext.conversationId(currentToolContext);
        if (conversationId == null || streamTracker == null) {
            return;
        }
        try {
            java.util.Map<String, Object> eventData = new java.util.LinkedHashMap<>();
            eventData.put("action", action);
            eventData.put("success", success);
            if (url != null) eventData.put("url", url);
            if (title != null) eventData.put("title", title);
            if (screenshot != null) eventData.put("screenshot", screenshot);
            eventData.put("durationMs", durationMs);
            eventData.put("timestamp", System.currentTimeMillis());
            streamTracker.broadcastObject(conversationId, "browser_action", eventData);
        } catch (Exception e) {
            log.debug("[BrowserUse] Failed to broadcast event: {}", e.getMessage());
        }
    }

    // ==================== Action Handlers ====================

    private String doStart(String sessionKey, boolean headed) {
        BrowserSession existing = sessions.get(sessionKey);
        if (existing != null && existing.isAlive()) {
            if (existing.headed == headed) {
                existing.touch();
                return ok("Browser already running (headed=" + headed + ")");
            }
            doStop(sessionKey);
        }

        int max = launcher.properties().getMaxSessions();
        if (max > 0 && sessions.size() >= max) {
            return error("Maximum browser sessions reached (" + max
                    + "). Stop an existing session first or raise mateclaw.browser.max-sessions.");
        }

        log.info("[BrowserUse] Starting browser via launcher (headed={})", headed);
        long startTime = System.currentTimeMillis();

        Playwright pw = getOrCreatePlaywright();
        BrowserLauncher.Result r = launcher.launch(pw, headed);

        if (!r.isSuccess()) {
            log.warn("[BrowserUse] All launch strategies failed:\n{}",
                    BrowserLauncher.formatTrace(r.getAttempts()));
            broadcastBrowserEvent("start", false, null, null, null,
                    System.currentTimeMillis() - startTime);
            JSONObject result = new JSONObject();
            result.set("ok", false);
            result.set("error", r.getFailureSummary());
            result.set("hint", "Run action=diagnose for a detailed report and fix suggestions.");
            return JSONUtil.toJsonPrettyStr(result);
        }

        BrowserSession session = new BrowserSession(r.getBrowser(), r.getContext(), r.getPage(),
                headed, r.isConnectedViaCdp(), r.getCdpUrl(),
                r.getUserDataDir(), r.getOwnedProcess());
        sessions.put(sessionKey, session);
        scheduleIdleCheck(sessionKey);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[BrowserUse] Browser started via {} in {}ms", r.getStrategy(), elapsed);
        broadcastBrowserEvent("start", true, null, null, null, elapsed);
        return ok("Browser started via " + r.getStrategy() + " (headed=" + headed + ") in "
                + elapsed + "ms. Use action=open with url to navigate.");
    }

    private String doConnectCdp(String sessionKey, String cdpUrl) {
        if (cdpUrl == null || cdpUrl.isBlank()) {
            return error("url is required for action=connect_cdp (e.g. http://127.0.0.1:9222)");
        }

        BrowserSession existing = sessions.get(sessionKey);
        if (existing != null) {
            doStop(sessionKey);
        }

        // Delegate to the launcher with the user-provided URL injected as a one-shot override.
        // The launcher handles URL normalisation (localhost → 127.0.0.1, protocol prefix).
        long startTime = System.currentTimeMillis();
        Playwright pw = getOrCreatePlaywright();
        String priorCdp = launcher.properties().getCdpUrl();
        launcher.properties().setCdpUrl(cdpUrl);
        BrowserLauncher.Result r;
        try {
            r = launcher.launch(pw, true);
        } finally {
            launcher.properties().setCdpUrl(priorCdp);
        }

        if (!r.isSuccess() || !r.isConnectedViaCdp()) {
            log.warn("[BrowserUse] CDP connect failed. Trace:\n{}",
                    BrowserLauncher.formatTrace(r.getAttempts()));
            return error("Failed to connect to CDP at " + cdpUrl + ": " + r.getFailureSummary());
        }

        // action=connect_cdp attaches to a user-managed Chrome — we did not spawn it,
        // so userDataDir / ownedProcess stay null and close() will only disconnect.
        BrowserSession session = new BrowserSession(r.getBrowser(), r.getContext(), r.getPage(),
                true, true, r.getCdpUrl(), null, null);
        sessions.put(sessionKey, session);
        scheduleIdleCheck(sessionKey);

        long elapsed = System.currentTimeMillis() - startTime;
        String title = r.getPage().title();
        String currentUrl = r.getPage().url();
        log.info("[BrowserUse] Connected to CDP at {} in {}ms (page: {} - {})",
                r.getCdpUrl(), elapsed, currentUrl, title);

        JSONObject result = new JSONObject();
        result.set("ok", true);
        result.set("cdpUrl", r.getCdpUrl());
        result.set("currentUrl", currentUrl);
        result.set("currentTitle", title);
        result.set("pagesCount", r.getContext().pages().size());
        result.set("message", "Connected to Chrome via CDP at " + r.getCdpUrl() + ". Current page: " + title);
        return JSONUtil.toJsonPrettyStr(result);
    }

    private String doDiagnose() {
        BrowserDiagnosticsService.Report report = diagnostics.run();
        JSONObject result = new JSONObject();
        result.set("ok", "healthy".equals(report.overall()) || "warning".equals(report.overall()));
        result.set("overall", report.overall());

        // Hutool's JSONUtil reflects on JavaBean-style getters and does not recognise
        // Java record accessors (r.id() vs r.getId()), so toJsonStr(record) yields {}.
        // Build the array by hand to keep the payload useful to the LLM.
        JSONArray findingsArr = new JSONArray();
        for (BrowserDiagnosticsService.Finding f : report.findings()) {
            JSONObject fo = new JSONObject();
            fo.set("id", f.id());
            fo.set("status", f.status() != null ? f.status().name() : null);
            fo.set("message", f.message());
            if (f.data() != null && !f.data().isEmpty()) {
                fo.set("data", f.data());
            }
            if (f.advice() != null) {
                fo.set("advice", f.advice());
            }
            findingsArr.add(fo);
        }
        result.set("findings", findingsArr);

        result.set("advice", report.advice());
        result.set("summary", BrowserDiagnosticsService.summarise(report));
        return JSONUtil.toJsonPrettyStr(result);
    }

    private String doListCdpTargets(Integer cdpPort) {
        log.info("[BrowserUse] Scanning for CDP targets (port={})", cdpPort);

        JSONArray targets = new JSONArray();

        if (cdpPort != null && cdpPort > 0) {
            // Scan single port
            JSONObject target = probeCdpPort(cdpPort);
            if (target != null) {
                targets.add(target);
            }
        } else {
            // Scan port range
            for (int port = CDP_SCAN_PORT_MIN; port <= CDP_SCAN_PORT_MAX; port++) {
                if (isPortOpen(port)) {
                    JSONObject target = probeCdpPort(port);
                    if (target != null) {
                        targets.add(target);
                    }
                }
            }
        }

        log.info("[BrowserUse] Found {} CDP target(s)", targets.size());

        JSONObject result = new JSONObject();
        result.set("ok", true);
        result.set("targets", targets);
        result.set("count", targets.size());
        if (targets.isEmpty()) {
            result.set("message", "No CDP targets found. Start Chrome with --remote-debugging-port=9222 first.");
        } else {
            result.set("message", "Found " + targets.size() + " CDP target(s). Use connect_cdp with the url to connect.");
        }
        return JSONUtil.toJsonPrettyStr(result);
    }

    private String doStop(String sessionKey) {
        BrowserSession session = sessions.remove(sessionKey);
        if (session == null) {
            return ok("No browser running");
        }

        // 取消空闲看门狗（避免 stop 后定时任务继续运行）
        ScheduledFuture<?> watchdog = session.idleWatchdog;
        if (watchdog != null && !watchdog.isDone()) {
            watchdog.cancel(false);
        }

        String cdpUrl = session.cdpUrl;
        boolean wasCdp = session.connectedViaCdp;
        session.close(); // Only closes Browser/Context, not the shared Playwright instance

        if (wasCdp) {
            log.info("[BrowserUse] Disconnected from CDP (Chrome keeps running at {})", cdpUrl);
            broadcastBrowserEvent("stop", true, null, null, null, 0);
            return ok("Disconnected from CDP. Chrome process at " + cdpUrl + " keeps running.");
        } else {
            log.info("[BrowserUse] Browser stopped");
            broadcastBrowserEvent("stop", true, null, null, null, 0);
            return ok("Browser stopped and resources released");
        }
    }

    private String doOpen(String sessionKey, String url) {
        if (url == null || url.isBlank()) {
            return error("url is required for action=open");
        }

        String normalizedUrl = url.trim();
        if (!normalizedUrl.matches("^https?://.*")) {
            normalizedUrl = "https://" + normalizedUrl;
        }

        if (launcher.properties().isSsrfCheckEnabled()) {
            try {
                UrlSafetyChecker.check(normalizedUrl);
            } catch (SecurityException se) {
                log.warn("[BrowserUse] SSRF check rejected url={}: {}", normalizedUrl, se.getMessage());
                return error(se.getMessage());
            }
        }

        BrowserSession session = getSession(sessionKey);
        if (session == null) {
            String startResp = doStart(sessionKey, false);
            session = getSession(sessionKey);
            if (session == null) {
                return startResp;
            }
        }

        session.touch();
        Page page = session.page;

        page.navigate(normalizedUrl);
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);

        String title = page.title();
        String currentUrl = page.url();

        log.info("[BrowserUse] Opened: {} (title={})", currentUrl, title);
        broadcastBrowserEvent("open", true, currentUrl, title, null, 0);

        JSONObject result = new JSONObject();
        result.set("ok", true);
        result.set("title", title);
        result.set("url", currentUrl);
        result.set("message", "Page loaded: " + title);
        return JSONUtil.toJsonPrettyStr(result);
    }

    private String doNavigateBack(String sessionKey) {
        BrowserSession session = requireSession(sessionKey);
        if (session == null) {
            return error("No browser running. Use action=start first.");
        }

        session.touch();
        session.page.goBack();

        String title = session.page.title();
        String url = session.page.url();

        log.info("[BrowserUse] Navigated back to: {} ({})", url, title);

        JSONObject result = new JSONObject();
        result.set("ok", true);
        result.set("title", title);
        result.set("url", url);
        result.set("message", "Navigated back to: " + title);
        return JSONUtil.toJsonPrettyStr(result);
    }

    private String doSnapshot(String sessionKey) {
        BrowserSession session = requireSession(sessionKey);
        if (session == null) {
            return error("No browser running. Use action=start first.");
        }

        session.touch();
        Page page = session.page;

        String title = page.title();
        String url = page.url();

        String textContent = page.evaluate("""
            (() => {
                function getVisibleText(node, depth) {
                    if (depth > 10) return '';
                    const results = [];
                    if (node.nodeType === Node.TEXT_NODE) {
                        const text = node.textContent.trim();
                        if (text) results.push(text);
                    } else if (node.nodeType === Node.ELEMENT_NODE) {
                        const el = node;
                        const style = window.getComputedStyle(el);
                        if (style.display === 'none' || style.visibility === 'hidden') return '';
                        const tag = el.tagName.toLowerCase();
                        if (['a', 'button', 'input', 'select', 'textarea'].includes(tag)) {
                            const id = el.id ? '#' + el.id : '';
                            const cls = el.className && typeof el.className === 'string'
                                ? '.' + el.className.trim().split(/\\s+/).slice(0, 2).join('.')
                                : '';
                            const text = el.textContent ? el.textContent.trim().substring(0, 80) : '';
                            const href = el.getAttribute('href') || '';
                            const placeholder = el.getAttribute('placeholder') || '';
                            const selector = tag + id + cls;
                            let desc = '[' + selector + ']';
                            if (text) desc += ' "' + text + '"';
                            if (href) desc += ' href=' + href;
                            if (placeholder) desc += ' placeholder=' + placeholder;
                            results.push(desc);
                        }
                        for (const child of el.childNodes) {
                            const childText = getVisibleText(child, depth + 1);
                            if (childText) results.push(childText);
                        }
                    }
                    return results.join('\\n');
                }
                const text = getVisibleText(document.body, 0);
                return text.substring(0, %d);
            })()
            """.formatted(MAX_SNAPSHOT_LENGTH)).toString();

        JSONObject result = new JSONObject();
        result.set("ok", true);
        result.set("title", title);
        result.set("url", url);
        result.set("content", textContent);
        return JSONUtil.toJsonPrettyStr(result);
    }

    private String doScreenshot(String sessionKey, String path) {
        BrowserSession session = requireSession(sessionKey);
        if (session == null) {
            return error("No browser running. Use action=start first.");
        }

        session.touch();
        Page page = session.page;

        Page.ScreenshotOptions opts = new Page.ScreenshotOptions().setFullPage(false);

        if (path != null && !path.isBlank()) {
            opts.setPath(Paths.get(path));
            page.screenshot(opts);
            log.info("[BrowserUse] Screenshot saved to: {}", path);

            JSONObject result = new JSONObject();
            result.set("ok", true);
            result.set("path", path);
            result.set("message", "Screenshot saved to " + path);
            return JSONUtil.toJsonPrettyStr(result);
        } else {
            byte[] bytes = page.screenshot(opts);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            log.info("[BrowserUse] Screenshot captured ({} bytes)", bytes.length);
            broadcastBrowserEvent("screenshot", true, null, null, base64, 0);

            JSONObject result = new JSONObject();
            result.set("ok", true);
            result.set("format", "png");
            result.set("base64", base64);
            result.set("size", bytes.length);
            result.set("message", "Screenshot captured (" + bytes.length + " bytes)");
            return JSONUtil.toJsonPrettyStr(result);
        }
    }

    private String doClick(String sessionKey, String selector) {
        if (selector == null || selector.isBlank()) {
            return error("selector is required for action=click");
        }

        BrowserSession session = requireSession(sessionKey);
        if (session == null) {
            return error("No browser running. Use action=start first.");
        }

        session.touch();
        Page page = session.page;

        page.click(selector);
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);

        String title = page.title();
        String url = page.url();

        log.info("[BrowserUse] Clicked: {} (page now: {})", selector, url);
        broadcastBrowserEvent("click", true, url, title, null, 0);

        JSONObject result = new JSONObject();
        result.set("ok", true);
        result.set("selector", selector);
        result.set("currentUrl", url);
        result.set("currentTitle", title);
        result.set("message", "Clicked element: " + selector);
        return JSONUtil.toJsonPrettyStr(result);
    }

    private String doType(String sessionKey, String selector, String text) {
        if (selector == null || selector.isBlank()) {
            return error("selector is required for action=type");
        }
        if (text == null) {
            return error("text is required for action=type");
        }

        BrowserSession session = requireSession(sessionKey);
        if (session == null) {
            return error("No browser running. Use action=start first.");
        }

        session.touch();
        Page page = session.page;

        page.fill(selector, text);

        log.info("[BrowserUse] Typed into: {} ({} chars)", selector, text.length());
        broadcastBrowserEvent("type", true, null, null, null, 0);

        JSONObject result = new JSONObject();
        result.set("ok", true);
        result.set("selector", selector);
        result.set("textLength", text.length());
        result.set("message", "Typed " + text.length() + " characters into " + selector);
        return JSONUtil.toJsonPrettyStr(result);
    }

    /** Detects the {@code await} keyword as a whole word to decide whether eval code needs an async wrapper. */
    private static final Pattern TOP_LEVEL_AWAIT = Pattern.compile("\\bawait\\b");

    /**
     * Playwright raises this exact message when a bare-expression eval contains a
     * top-level {@code return}. Such snippets are safe to retry inside an async
     * IIFE, where {@code return} surfaces the value.
     */
    private static boolean isIllegalReturn(PlaywrightException ex) {
        String m = ex.getMessage();
        return m != null && m.contains("Illegal return statement");
    }

    private String doEval(String sessionKey, String code) {
        if (code == null || code.isBlank()) {
            return error("code is required for action=eval");
        }

        BrowserSession session = requireSession(sessionKey);
        if (session == null) {
            return error("No browser running. Use action=start first.");
        }

        session.touch();
        Page page = session.page;

        // Playwright evaluates the supplied string as a plain expression, which
        // rejects both top-level `await` and top-level `return` ("SyntaxError:
        // Illegal return statement"). Snippets that use `await` are wrapped up
        // front in an async IIFE (valid for `await` and `return` alike).
        // A top-level `return` only fails at eval time, so we retry once wrapped
        // rather than pre-wrapping on a naive `return` match — that would mangle
        // bare expressions containing a nested return (e.g. arr.map(x => {
        // return x; })) into an IIFE with no top-level return, yielding undefined.
        String script = TOP_LEVEL_AWAIT.matcher(code).find()
                ? "(async () => {" + code + "})()"
                : code;
        Object evalResult;
        try {
            evalResult = page.evaluate(script);
        } catch (PlaywrightException ex) {
            if (script.equals(code) && isIllegalReturn(ex)) {
                log.debug("[BrowserUse] eval had a top-level return; retrying wrapped in async IIFE");
                script = "(async () => {" + code + "})()";
                evalResult = page.evaluate(script);
            } else {
                throw ex;
            }
        }
        String resultStr = evalResult != null ? evalResult.toString() : "null";

        if (resultStr.length() > 10_000) {
            resultStr = resultStr.substring(0, 10_000) + "\n... [truncated]";
        }

        log.info("[BrowserUse] Eval executed ({} chars result)", resultStr.length());

        JSONObject result = new JSONObject();
        result.set("ok", true);
        result.set("result", resultStr);
        return JSONUtil.toJsonPrettyStr(result);
    }

    // ==================== CDP Helpers ====================

    private boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 100);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject probeCdpPort(int port) {
        try {
            String jsonUrl = "http://127.0.0.1:" + port + "/json/version";
            String response = HttpUtil.get(jsonUrl, 2000);
            if (response != null && response.contains("webSocketDebuggerUrl")) {
                JSONObject version = JSONUtil.parseObj(response);
                JSONObject target = new JSONObject();
                target.set("port", port);
                target.set("url", "http://127.0.0.1:" + port);
                target.set("browser", version.getStr("Browser", "unknown"));
                target.set("webSocketDebuggerUrl", version.getStr("webSocketDebuggerUrl", ""));
                return target;
            }
        } catch (Exception e) {
            log.debug("[BrowserUse] Port {} is not a CDP endpoint: {}", port, e.getMessage());
        }
        return null;
    }

    // ==================== Session Management ====================

    private BrowserSession getSession(String sessionKey) {
        BrowserSession session = sessions.get(sessionKey);
        if (session != null && !session.isAlive()) {
            sessions.remove(sessionKey);
            session.close();
            return null;
        }
        return session;
    }

    private BrowserSession requireSession(String sessionKey) {
        return getSession(sessionKey);
    }

    private void scheduleIdleCheck(String sessionKey) {
        BrowserSession session = sessions.get(sessionKey);
        if (session == null) return;

        // 取消已有的看门狗（防止 start→stop→start 导致多个定时任务累积）
        ScheduledFuture<?> existing = session.idleWatchdog;
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            BrowserSession s = sessions.get(sessionKey);
            if (s == null) return;
            long idleMinutes = (System.currentTimeMillis() - s.lastActivity) / 60_000;
            if (idleMinutes >= IDLE_TIMEOUT_MINUTES) {
                log.info("[BrowserUse] Idle timeout ({}min), stopping session: {}", idleMinutes, sessionKey);
                doStop(sessionKey);
            }
        }, IDLE_TIMEOUT_MINUTES, 5, TimeUnit.MINUTES);

        session.idleWatchdog = future;
    }

    @PreDestroy
    public void cleanup() {
        log.info("[BrowserUse] Cleaning up all browser sessions");
        scheduler.shutdownNow();
        sessions.forEach((key, session) -> {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("[BrowserUse] Error closing session {}: {}", key, e.getMessage());
            }
        });
        sessions.clear();

        // Shutdown the shared Playwright Node.js process
        synchronized (playwrightLock) {
            if (sharedPlaywright != null) {
                try {
                    sharedPlaywright.close();
                    log.info("[BrowserUse] Shared Playwright instance closed");
                } catch (Exception e) {
                    log.warn("[BrowserUse] Error closing Playwright: {}", e.getMessage());
                }
                sharedPlaywright = null;
            }
        }
    }

    // ==================== Helper Methods ====================

    private String ok(String message) {
        JSONObject result = new JSONObject();
        result.set("ok", true);
        result.set("message", message);
        return JSONUtil.toJsonPrettyStr(result);
    }

    private String error(String message) {
        JSONObject result = new JSONObject();
        result.set("ok", false);
        result.set("error", message);
        return JSONUtil.toJsonPrettyStr(result);
    }

    // ==================== Inner Class ====================

    /**
     * 浏览器会话（不持有 Playwright 实例，Playwright 由外层共享管理）
     */
    private static class BrowserSession {
        final Browser browser;
        final BrowserContext context;
        volatile Page page;
        final boolean headed;
        final boolean connectedViaCdp;
        final String cdpUrl;
        /**
         * Temp profile directory we created for the EXTERNAL_CDP fallback.
         * Null when the session connected to a user-managed Chrome (CONFIG_CDP /
         * action=connect_cdp) or used a non-CDP launch strategy.
         */
        final java.nio.file.Path userDataDir;
        /**
         * Chrome subprocess we spawned ourselves for EXTERNAL_CDP. Null otherwise.
         * Closing the Playwright {@code Browser} only severs the CDP socket; the
         * actual Chrome process keeps running until we destroyForcibly() it here.
         */
        final Process ownedProcess;
        volatile long lastActivity;
        /** 空闲看门狗定时任务（stop 时取消，避免泄漏） */
        volatile ScheduledFuture<?> idleWatchdog;

        BrowserSession(Browser browser, BrowserContext context, Page page,
                        boolean headed, boolean connectedViaCdp, String cdpUrl,
                        java.nio.file.Path userDataDir, Process ownedProcess) {
            this.browser = browser;
            this.context = context;
            this.page = page;
            this.headed = headed;
            this.connectedViaCdp = connectedViaCdp;
            this.cdpUrl = cdpUrl;
            this.userDataDir = userDataDir;
            this.ownedProcess = ownedProcess;
            this.lastActivity = System.currentTimeMillis();
        }

        void touch() {
            this.lastActivity = System.currentTimeMillis();
        }

        boolean isAlive() {
            return browser != null && browser.isConnected();
        }

        /**
         * Close the session (does not touch the shared Playwright driver).
         * <ul>
         *   <li>User-managed CDP (ownedProcess == null): just disconnect — the user owns the Chrome process.</li>
         *   <li>Self-spawned CDP (ownedProcess != null): disconnect, then destroyForcibly() the Chrome we spawned,
         *       wait briefly for it to exit so Windows lockfiles are released, then deleteQuietly() the temp profile.</li>
         *   <li>Launch mode (connectedViaCdp == false): close context + browser; Playwright handles process teardown.</li>
         * </ul>
         */
        void close() {
            if (connectedViaCdp) {
                try {
                    if (browser != null) browser.close();
                } catch (Exception ignored) {}
                if (ownedProcess != null) {
                    try {
                        // Snapshot descendants BEFORE killing the parent. Chrome on Windows
                        // spawns ~6 child processes (renderer, GPU, network service, ...)
                        // that hold open handles inside the user-data-dir. destroyForcibly()
                        // only sends TerminateProcess to the parent; killing children must
                        // be done separately, otherwise the temp dir cannot be deleted and
                        // the orphaned chrome.exe instances keep running.
                        java.util.List<ProcessHandle> children = ownedProcess.descendants()
                                .toList();
                        ownedProcess.destroyForcibly();
                        for (ProcessHandle h : children) {
                            try { h.destroyForcibly(); } catch (Exception ignored) {}
                        }
                        ownedProcess.waitFor(5, TimeUnit.SECONDS);
                        for (ProcessHandle h : children) {
                            try { h.onExit().get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                    BrowserLauncher.deleteQuietly(userDataDir);
                }
            } else {
                try {
                    if (context != null) context.close();
                } catch (Exception ignored) {}
                try {
                    if (browser != null) browser.close();
                } catch (Exception ignored) {}
            }
        }
    }
}
