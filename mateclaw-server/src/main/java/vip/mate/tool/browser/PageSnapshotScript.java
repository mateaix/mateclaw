package vip.mate.tool.browser;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Accessibility-tree page snapshot for browser automation.
 *
 * <p>Replaces the older visible-text dump with a compact accessibility tree in
 * which every interactive element is tagged with a stable reference handle
 * ({@code @e1}, {@code @e2}, ...). The reference is materialised as a
 * {@code data-mate-ref} attribute on the live DOM node, so a follow-up
 * click/type action can address the element by {@code [data-mate-ref='eN']}
 * instead of forcing the model to guess a brittle CSS selector.
 *
 * <p>Why an injected attribute rather than a framework-native snapshot: the
 * attribute approach is independent of the browser-driver version, survives
 * driver upgrades, and produces a selector that plugs straight into the
 * existing click/type plumbing. References stay valid only for the snapshot
 * that produced them — a navigation wipes the attributes, so a stale reference
 * naturally resolves to "not found" and the caller is told to re-snapshot.
 */
public final class PageSnapshotScript {

    private PageSnapshotScript() {
    }

    /**
     * Injected snapshot function. Runs as {@code root.evaluate(SNAPSHOT_JS, opts)}.
     * Playwright's {@code ElementHandle.evaluate} invokes the function as
     * {@code fn(element, arg)} — the scoped root element is the FIRST positional
     * parameter (NOT {@code this}, which Playwright never binds to the element),
     * and the caller's {@code opts} object is the second. {@code opts} is
     * {@code {maxLen, includeNonInteractive}}.
     *
     * <p>Returns a JSON string {@code {tree, truncated, refs}} where:
     * <ul>
     *   <li>{@code tree} — indented accessibility tree text for the model;</li>
     *   <li>{@code truncated} — true when output was cut at the length budget;</li>
     *   <li>{@code refs} — the list of reference ids assigned this snapshot.</li>
     * </ul>
     */
    public static final String SNAPSHOT_JS = """
            (rootEl, opts) => {
                const maxLen = opts.maxLen;
                const includeNon = opts.includeNonInteractive;
                const budget = { remaining: maxLen, truncated: false };
                let counter = 0;
                const refs = [];

                // Wipe references from a prior snapshot so ids never collide
                // across generations and a navigated-away page leaves nothing behind.
                document.querySelectorAll('[data-mate-ref]').forEach(function (n) {
                    n.removeAttribute('data-mate-ref');
                });

                function isVisible(el) {
                    const style = window.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
                    return el.offsetWidth > 0 || el.offsetHeight > 0 || el.getClientRects().length > 0;
                }

                function isInteractive(el) {
                    const tag = el.tagName.toLowerCase();
                    if (['a', 'button', 'input', 'select', 'textarea', 'summary'].includes(tag)) return true;
                    const role = el.getAttribute('role');
                    if (role && ['button', 'link', 'checkbox', 'radio', 'tab', 'menuitem',
                        'switch', 'textbox', 'combobox', 'option', 'searchbox', 'slider'].includes(role)) return true;
                    if (el.hasAttribute('onclick')) return true;
                    if (el.isContentEditable) return true;
                    const ti = el.getAttribute('tabindex');
                    if (ti !== null && ti !== '-1') return true;
                    return false;
                }

                function roleOf(el) {
                    const explicit = el.getAttribute('role');
                    if (explicit) return explicit;
                    const tag = el.tagName.toLowerCase();
                    switch (tag) {
                        case 'a': return el.hasAttribute('href') ? 'link' : 'generic';
                        case 'button': return 'button';
                        case 'select': return 'combobox';
                        case 'textarea': return 'textbox';
                        case 'summary': return 'button';
                        case 'input': {
                            const t = (el.getAttribute('type') || 'text').toLowerCase();
                            if (t === 'checkbox') return 'checkbox';
                            if (t === 'radio') return 'radio';
                            if (t === 'submit' || t === 'button' || t === 'reset') return 'button';
                            if (t === 'search') return 'searchbox';
                            if (t === 'hidden') return null;
                            return 'textbox';
                        }
                        case 'h1': case 'h2': case 'h3': case 'h4': case 'h5': case 'h6': return 'heading';
                        case 'li': return 'listitem';
                        case 'ul': case 'ol': return 'list';
                        case 'nav': return 'navigation';
                        case 'img': return 'img';
                        default: return null;
                    }
                }

                function nameOf(el) {
                    const aria = el.getAttribute('aria-label');
                    if (aria) return aria.trim();
                    const labelledby = el.getAttribute('aria-labelledby');
                    if (labelledby) {
                        const target = document.getElementById(labelledby);
                        if (target) return (target.textContent || '').trim();
                    }
                    const tag = el.tagName.toLowerCase();
                    if (tag === 'input' || tag === 'textarea') {
                        const ph = el.getAttribute('placeholder');
                        if (ph) return ph.trim();
                        if (el.value) return String(el.value).trim();
                        if (el.id) {
                            const lab = document.querySelector('label[for="' + (window.CSS ? CSS.escape(el.id) : el.id) + '"]');
                            if (lab) return (lab.textContent || '').trim();
                        }
                        // Wrapping label: <label>Customer name: <input></label> — common
                        // and has no for= link, so climb to the nearest label ancestor.
                        const wrap = el.closest('label');
                        if (wrap) {
                            const wt = (wrap.textContent || '').trim().replace(/\\s+/g, ' ');
                            if (wt) return wt;
                        }
                        return '';
                    }
                    if (tag === 'img') {
                        const alt = el.getAttribute('alt');
                        if (alt) return alt.trim();
                    }
                    const title = el.getAttribute('title');
                    if (title) return title.trim();
                    const txt = el.textContent ? el.textContent.trim().replace(/\\s+/g, ' ') : '';
                    return txt;
                }

                function clip(s, n) {
                    if (!s) return '';
                    return s.length > n ? s.substring(0, n) + '…' : s;
                }

                const lines = [];

                function emit(text) {
                    if (budget.remaining <= 0) { budget.truncated = true; return false; }
                    if (text.length + 1 > budget.remaining) {
                        budget.truncated = true;
                        budget.remaining = 0;
                        return false;
                    }
                    lines.push(text);
                    budget.remaining -= (text.length + 1);
                    return true;
                }

                function walk(el, depth) {
                    if (depth > 20 || budget.remaining <= 0) return;
                    if (!isVisible(el)) return;

                    const role = roleOf(el);
                    const interactive = isInteractive(el);
                    let line = null;

                    if (interactive && role !== 'generic' && role !== null) {
                        counter += 1;
                        const ref = 'e' + counter;
                        el.setAttribute('data-mate-ref', ref);
                        refs.push(ref);
                        const nm = clip(nameOf(el), 100);
                        line = role + (nm ? ' "' + nm + '"' : '') + ' @' + ref;
                    } else if (includeNon && role && role !== 'generic') {
                        const nm = clip(nameOf(el), 100);
                        if (nm || role === 'list' || role === 'navigation') {
                            let extra = '';
                            if (role === 'heading') {
                                const lvl = el.getAttribute('aria-level')
                                    || (el.tagName.length === 2 ? el.tagName.charAt(1) : '');
                                if (lvl) extra = ' [level=' + lvl + ']';
                            }
                            line = role + (nm ? ' "' + nm + '"' : '') + extra;
                        }
                    }

                    if (line !== null) {
                        if (!emit('  '.repeat(Math.min(depth, 10)) + '- ' + line)) return;
                    }

                    const childDepth = line !== null ? depth + 1 : depth;
                    for (const child of el.children) {
                        if (budget.remaining <= 0) break;
                        walk(child, childDepth);
                    }
                }

                walk(rootEl, 0);
                return JSON.stringify({ tree: lines.join('\\n'), truncated: budget.truncated, refs: refs });
            }
            """;

    /** Parsed result of a snapshot evaluation. */
    public record Result(String tree, boolean truncated, List<String> refs) {
        public static Result fromJson(String json) {
            JSONObject obj = JSONUtil.parseObj(json);
            String tree = obj.getStr("tree", "");
            boolean truncated = obj.getBool("truncated", false);
            List<String> refs = new ArrayList<>();
            JSONArray arr = obj.getJSONArray("refs");
            if (arr != null) {
                for (Object o : arr) {
                    if (o != null) {
                        refs.add(o.toString());
                    }
                }
            }
            return new Result(tree, truncated, refs);
        }
    }

    /** Build the deterministic attribute selector for a reference id. */
    public static String selectorForRef(String ref) {
        return "[data-mate-ref='" + ref + "']";
    }
}
