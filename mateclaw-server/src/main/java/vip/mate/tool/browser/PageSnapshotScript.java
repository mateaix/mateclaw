package vip.mate.tool.browser;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
                const refInfos = [];

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

                function normalizeName(s) {
                    if (!s) return '';
                    return s.length > 100 ? s.substring(0, 100) + '…' : s;
                }

                function stateOf(el, ref, role, name) {
                    const tag = el.tagName.toLowerCase();
                    const info = {
                        ref: ref,
                        role: role || '',
                        name: name || '',
                        tag: tag,
                        type: el.getAttribute('type') || '',
                        href: el.getAttribute('href') || '',
                        value: '',
                        checked: !!el.checked,
                        selected: !!el.selected,
                        disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
                        expanded: el.getAttribute('aria-expanded') === null ? null : el.getAttribute('aria-expanded') === 'true'
                    };
                    if (tag === 'input' || tag === 'textarea' || tag === 'select') {
                        info.value = String(el.value || '');
                    }
                    return info;
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
                        const nm = normalizeName(nameOf(el));
                        refInfos.push(stateOf(el, ref, role, nm));
                        line = role + (nm ? ' "' + nm + '"' : '') + ' @' + ref;
                    } else if (includeNon && role && role !== 'generic') {
                        const nm = normalizeName(nameOf(el));
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
                return JSON.stringify({ tree: lines.join('\\n'), truncated: budget.truncated, refs: refs, refInfos: refInfos });
            }
            """;

    /** Evaluate on an element handle to capture the same core fingerprint used by snapshots. */
    public static final String REF_FINGERPRINT_JS = """
            (el, ref) => {
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
                    return el.textContent ? el.textContent.trim().replace(/\\s+/g, ' ').substring(0, 100) : '';
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
                        default: return null;
                    }
                }
                function normalizeName(s) {
                    if (!s) return '';
                    return s.length > 100 ? s.substring(0, 100) + '…' : s;
                }
                const tag = el.tagName.toLowerCase();
                return JSON.stringify({
                    ref: ref || el.getAttribute('data-mate-ref') || '',
                    role: roleOf(el) || '',
                    name: normalizeName(nameOf(el)),
                    tag: tag,
                    type: el.getAttribute('type') || '',
                    href: el.getAttribute('href') || '',
                    value: (tag === 'input' || tag === 'textarea' || tag === 'select') ? String(el.value || '') : '',
                    checked: !!el.checked,
                    selected: !!el.selected,
                    disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
                    expanded: el.getAttribute('aria-expanded') === null ? null : el.getAttribute('aria-expanded') === 'true'
                });
            }
            """;

    /** Parsed result of a snapshot evaluation. */
    public record Result(String tree, boolean truncated, List<String> refs,
                         Map<String, RefFingerprint> refInfos) {
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
            Map<String, RefFingerprint> refInfos = new LinkedHashMap<>();
            JSONArray infoArr = obj.getJSONArray("refInfos");
            if (infoArr != null) {
                for (Object o : infoArr) {
                    if (o instanceof JSONObject info) {
                        RefFingerprint fp = RefFingerprint.fromJson(info);
                        if (fp.ref() != null && !fp.ref().isBlank()) {
                            refInfos.put(fp.ref(), fp);
                        }
                    }
                }
            }
            return new Result(tree, truncated, refs, Map.copyOf(refInfos));
        }
    }

    public record RefFingerprint(String ref, String role, String name, String tag, String type,
                                 String href, String value, boolean checked, boolean selected,
                                 boolean disabled, Boolean expanded) {
        public static RefFingerprint fromJson(JSONObject obj) {
            return new RefFingerprint(
                    obj.getStr("ref", ""),
                    obj.getStr("role", ""),
                    obj.getStr("name", ""),
                    obj.getStr("tag", ""),
                    obj.getStr("type", ""),
                    obj.getStr("href", ""),
                    obj.getStr("value", ""),
                    obj.getBool("checked", false),
                    obj.getBool("selected", false),
                    obj.getBool("disabled", false),
                    obj.get("expanded") == null ? null : obj.getBool("expanded", false));
        }

        public boolean sameCoreIdentity(RefFingerprint other) {
            if (other == null) {
                return false;
            }
            return Objects.equals(normalize(role), normalize(other.role))
                    && Objects.equals(normalize(name), normalize(other.name))
                    && Objects.equals(normalize(tag), normalize(other.tag))
                    && Objects.equals(normalize(type), normalize(other.type));
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
        }
    }

    /** Build the deterministic attribute selector for a reference id. */
    public static String selectorForRef(String ref) {
        return "[data-mate-ref='" + ref + "']";
    }
}
