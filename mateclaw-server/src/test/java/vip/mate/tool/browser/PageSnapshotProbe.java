package vip.mate.tool.browser;

import cn.hutool.json.JSONObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import vip.mate.common.net.SsrfProperties;

import java.util.List;

/**
 * Manual end-to-end probe for {@link PageSnapshotScript}. Not a JUnit test — run via
 * {@code mvn -q test-compile exec:java -Dexec.mainClass=vip.mate.tool.browser.PageSnapshotProbe
 * -Dexec.classpathScope=test} (use {@code test-compile}, not {@code compile}, so this
 * test-scoped class is built).
 *
 * <p>Launches a real browser and drives the EXACT path the tool uses —
 * {@code page.querySelector("body").evaluate(PageSnapshotScript.SNAPSHOT_JS, opts)} —
 * against a known page. It exists to lock the most subtle failure mode found in
 * review: Playwright's {@code ElementHandle.evaluate} passes the element as the
 * FIRST POSITIONAL ARGUMENT (never as {@code this}). If the snapshot function is
 * ever reverted to read {@code this}, the walk starts from the wrong root and the
 * tree comes back empty (or throws) — this probe then fails loudly instead of the
 * regression shipping silently, the way the original {@code this}-based version did.
 *
 * <p>Asserts, on a fixed HTML page:
 * <ul>
 *   <li>references are assigned (non-empty) and materialised as {@code data-mate-ref};</li>
 *   <li>interactive elements appear with the right role + accessible name
 *       (incl. wrapping-label resolution and the {@code display:none} filter);</li>
 *   <li>{@code [data-mate-ref='e1']} resolves back to the expected element.</li>
 * </ul>
 * Exits non-zero on any failed assertion.
 */
public final class PageSnapshotProbe {

    private static final String HTML = """
            <!doctype html><html><body>
              <h1>Probe Form</h1>
              <label>Customer name: <input name="cn"></label>
              <a href="https://example.com/next">Learn more</a>
              <select><option>One</option><option value="2">Two</option></select>
              <button>Submit</button>
              <div style="display:none"><button>Hidden Button</button></div>
            </body></html>
            """;

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("=== PageSnapshotScript probe ===");
        BrowserProperties props = new BrowserProperties();
        BrowserLauncher launcher = new BrowserLauncher(props, new SsrfProperties());

        int exit = 0;
        try (Playwright pw = Playwright.create()) {
            BrowserLauncher.Result r = launcher.launch(pw, false);
            if (!r.isSuccess()) {
                System.err.println("FAIL: could not launch a browser: " + r.getFailureSummary());
                System.exit(1);
            }
            try (Browser browser = r.getBrowser()) {
                Page page = r.getPage();
                page.setContent(HTML);

                // EXACT tool path: element handle + Hutool JSONObject opts.
                ElementHandle root = page.querySelector("body");
                JSONObject opts = new JSONObject();
                opts.set("maxLen", 20_000);
                opts.set("includeNonInteractive", true);
                String json = (String) root.evaluate(PageSnapshotScript.SNAPSHOT_JS, opts);
                PageSnapshotScript.Result snap = PageSnapshotScript.Result.fromJson(json);

                System.out.println("tree:\n" + snap.tree());
                System.out.println("refs: " + snap.refs());

                List<String> refs = snap.refs();
                String tree = snap.tree();

                // The regression guard: the this-vs-first-arg bug makes this empty.
                check(!refs.isEmpty(), "references assigned (element passed as first arg, not `this`)");
                check(refs.size() >= 4, "at least 4 interactive refs (input, link, select, button), got " + refs.size());

                check(tree.contains("textbox \"Customer name:\""), "input resolves wrapping-label name");
                check(tree.contains("link \"Learn more\""), "anchor rendered as link with text");
                check(tree.contains("combobox"), "select rendered as combobox");
                check(tree.contains("button \"Submit\""), "button rendered with text");
                check(tree.contains("heading \"Probe Form\" [level=1]"), "h1 rendered as heading level 1");
                check(!tree.contains("Hidden Button"), "display:none subtree is filtered out");

                boolean e1Resolves = page.querySelector("[data-mate-ref='e1']") != null;
                check(e1Resolves, "[data-mate-ref='e1'] resolves back to a live element");

                if (failures == 0) {
                    System.out.println("\nALL CHECKS PASSED (" + refs.size() + " refs) via " + r.getStrategy());
                } else {
                    System.err.println("\n" + failures + " CHECK(S) FAILED");
                    exit = 1;
                }
            }
        } catch (Exception e) {
            System.err.println("FAIL: probe threw: " + e);
            e.printStackTrace();
            exit = 1;
        }
        System.exit(exit);
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what);
        if (!ok) {
            failures++;
        }
    }
}
