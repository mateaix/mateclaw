package vip.mate.wiki.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Single source of truth for wikilink extraction and resolution-state
 * computation. The page viewer's TypeScript {@code resolveWikilink} mirrors
 * the matching semantics; both must stay in lockstep so users do not see a
 * visibly-working link that the lint marks as broken (or vice-versa).
 * <p>
 * Resolution rule (intentionally narrow):
 *
 * <ul>
 *   <li>Extract every {@code [[target]]} or {@code [[target|display]]} from
 *       content, skipping fenced and inline code spans.</li>
 *   <li>For each occurrence keep only {@code target.toLowerCase().trim()} —
 *       no {@link WikiPageService#canonicalSlug} fuzzy collapse, no
 *       title→slug guessing. The lint flags a link as broken iff no active
 *       KB page has {@code page.slug.equalsIgnoreCase(target)}.</li>
 * </ul>
 *
 * The strict comparison surfaces real authoring mistakes (typo in slug,
 * stale ref to a renamed page) rather than silently papering over them with
 * canonical-form coercion. Phase 1's frontend resolver keeps a title
 * fallback for legacy {@code [[Page Title]]} content so the visible link
 * still navigates, but that fallback is intentionally absent here — title-
 * form authors are expected to migrate as the slug-first prompt rollout
 * lands in Phase 3.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiLinkService {

    /**
     * Matches every {@code [[...]]} occurrence. Non-greedy on the inside so
     * pathological inputs like {@code [[a]] [[b]]} resolve as two separate
     * links rather than one giant link {@code "a]] [[b"}.
     */
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]]+?)]]");

    /**
     * Matches a cross-KB wikilink target of the form {@code kbId/slug}, where
     * {@code kbId} is a numeric knowledge-base id and {@code slug} is a page
     * slug inside that KB. A plain single-KB slug never contains {@code /}, so
     * this pattern is unambiguous — historical {@code [[slug]]} /
     * {@code [[Title]]} content is unaffected.
     */
    private static final Pattern CROSS_KB = Pattern.compile("^(\\d+)/(.+)$");

    /**
     * Matches a fenced code block. Anchored to {@code ^```} on a line so a
     * stray triple-backtick mid-paragraph does not flip the world into "in
     * code" mode and swallow real wikilinks for the rest of the document.
     * Captures the opening fence and content lazily; the matched range is
     * removed wholesale before wikilink extraction.
     */
    private static final Pattern FENCED_CODE = Pattern.compile(
            "(?m)^```[\\s\\S]*?^```", Pattern.MULTILINE);

    /**
     * Matches inline {@code `...`} spans. Non-greedy so adjacent inline spans
     * are handled as separate matches.
     */
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*?`");

    /** Hard cap matching the frontend's MAX_SLUG_LEN — see wikilink.ts. */
    private static final int MAX_TARGET_LEN = 256;

    private final ObjectMapper objectMapper;

    /**
     * Extract every wikilink target string from {@code content}, normalised
     * to lowercase + trimmed, with code blocks stripped first.
     * <p>
     * Returns an insertion-ordered set so callers that serialize to JSON get
     * a stable order (helps diffability of {@code broken_links} fields across
     * scans and makes audit logs easier to read).
     *
     * @param content full markdown body; {@code null} or blank returns empty
     * @return targets as written (before {@code |} alias), lowercased
     */
    public Set<String> extractOutlinks(String content) {
        if (content == null || content.isBlank()) return Collections.emptySet();

        // Strip code first so inline / fenced examples that show literal
        // [[wikilink]] syntax stay literal. Replacement with an equal-length
        // run of spaces would be more correct (preserves positions for any
        // future error reporting) but isn't worth the complexity here — we
        // only need the targets.
        String stripped = FENCED_CODE.matcher(content).replaceAll("");
        stripped = INLINE_CODE.matcher(stripped).replaceAll("");

        Set<String> targets = new LinkedHashSet<>();
        Matcher m = WIKILINK.matcher(stripped);
        while (m.find()) {
            String raw = m.group(1).trim();
            if (raw.isEmpty()) continue;
            int pipe = raw.indexOf('|');
            String target = (pipe >= 0 ? raw.substring(0, pipe) : raw).trim();
            if (target.isEmpty() || target.length() > MAX_TARGET_LEN) continue;
            // Lowercase here so {@link #computeBrokenLinks} can do exact
            // equality against {@code page.slug.toLowerCase()} without an
            // extra normalisation step per page.
            targets.add(target.toLowerCase(Locale.ROOT));
        }
        return targets;
    }

    /**
     * Compute the broken subset of {@code outlinks} given the KB's set of
     * resolvable link-target keys. {@code resolvableKeysLower} is expected to
     * be already lowercased and to contain BOTH page slugs and page titles
     * (see {@link #resolvableTargetKeys}) — callers compute it once per scan
     * and reuse it across pages.
     * <p>
     * A target counts as broken only when it matches neither a slug nor a
     * title, mirroring the page viewer's {@code resolveWikilink}. Matching on
     * slugs alone would report every {@code [[Page Title]]} reference to an
     * existing page as broken even though the viewer renders it as a working
     * link.
     *
     * @return targets that resolve to no existing page, in the same insertion
     *         order as {@code outlinks}
     */
    public List<String> computeBrokenLinks(Set<String> outlinks, Set<String> resolvableKeysLower) {
        if (outlinks == null || outlinks.isEmpty()) return Collections.emptyList();
        if (resolvableKeysLower == null) resolvableKeysLower = Collections.emptySet();
        List<String> broken = new ArrayList<>();
        for (String t : outlinks) {
            // Cross-KB targets ([[kbId/slug]]) can't be validated against this
            // KB's key set — checking existence would need a cross-KB query.
            // Exempt well-formed cross-KB refs from the single-KB broken-link
            // rule so they aren't false-flagged; the target KB's viewer surfaces
            // a real page-not-found on click if the slug is stale.
            if (parseCrossKb(t) != null) continue;
            if (!resolvableKeysLower.contains(t)) broken.add(t);
        }
        return broken;
    }

    /**
     * Parse a cross-KB wikilink target {@code kbId/slug} into its parts, or
     * {@code null} when {@code target} is a plain single-KB slug/title.
     * Pure function — no I/O, no existence check.
     *
     * @param target the wikilink target (before any {@code |alias}); may be
     *               already lowercased by {@link #extractOutlinks}
     * @return {@link CrossKbRef} when the target has a numeric KB prefix, else null
     */
    public CrossKbRef parseCrossKb(String target) {
        if (target == null || target.isBlank()) return null;
        Matcher m = CROSS_KB.matcher(target.trim());
        if (!m.matches()) return null;
        try {
            long kbId = Long.parseLong(m.group(1));
            String slug = m.group(2).trim();
            if (slug.isEmpty()) return null;
            return new CrossKbRef(kbId, slug);
        } catch (NumberFormatException e) {
            // kbId overflowed long — treat as a plain (broken) single-KB target.
            return null;
        }
    }

    /** A parsed cross-KB wikilink target: target KB id + page slug. */
    public record CrossKbRef(long kbId, String slug) {}

    /**
     * Convenience: extract + compute in one call. Used from
     * {@code WikiPageService.save/update} where both fields are written in
     * the same transaction. {@code resolvableKeysLower} should carry slugs
     * and titles — see {@link #resolvableTargetKeys}.
     */
    public LinkAnalysis analyze(String content, Set<String> resolvableKeysLower) {
        Set<String> outlinks = extractOutlinks(content);
        List<String> broken = computeBrokenLinks(outlinks, resolvableKeysLower);
        return new LinkAnalysis(new ArrayList<>(outlinks), broken);
    }

    /** Pair returned by {@link #analyze(String, Set)}. */
    public record LinkAnalysis(List<String> outgoingLinks, List<String> brokenLinks) {}

    /** Serialize a list to JSON for persistence. Best-effort: never throws. */
    public String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("[WikiLink] Failed to serialize list to JSON, falling back to empty: {}", e.getMessage());
            return "[]";
        }
    }

    /** Parse a JSON array back into a list. Best-effort: never throws. */
    public List<String> fromJsonArray(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[WikiLink] Failed to parse JSON array, treating as empty: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Compute the lowercase slug set for a KB from a pre-loaded page list.
     * Centralised so both single-page save paths and the KB-wide scan use the
     * same definition of "active page".
     */
    public Set<String> lowercaseSlugSet(List<WikiPageEntity> pages) {
        if (pages == null || pages.isEmpty()) return Collections.emptySet();
        return pages.stream()
                .map(WikiPageEntity::getSlug)
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Build the set of resolvable wikilink-target keys for a KB from a
     * pre-loaded page list — the union of each page's lowercased slug AND its
     * trimmed-lowercased title.
     * <p>
     * This is the broken-link counterpart to {@link #lowercaseSlugSet} and is
     * what {@link #computeBrokenLinks} should be fed: it mirrors the page
     * viewer's {@code resolveWikilink}, which resolves a {@code [[target]]}
     * against an exact slug OR an exact title before declaring it broken.
     * Using slugs alone flags every {@code [[Page Title]]} reference to an
     * existing page as broken even though the viewer renders it as a working
     * link — a false positive that is pervasive when slugs are transliterated
     * (e.g. a CJK title {@code 光合作用} stored under the pinyin slug
     * {@code guanghe-zuoyong}).
     * <p>
     * Titles are trimmed before lowercasing to match {@code extractOutlinks},
     * which trims a {@code [[ Page Title ]]} target before recording it.
     *
     * @param pages active pages (callers filter out archived); both slug and
     *              title columns must be loaded
     * @return mutable set of lowercased slug + title keys; empty for null/empty
     */
    public Set<String> resolvableTargetKeys(List<WikiPageEntity> pages) {
        if (pages == null || pages.isEmpty()) return new HashSet<>();
        Set<String> keys = new HashSet<>(pages.size() * 2);
        for (WikiPageEntity p : pages) {
            if (p == null) continue;
            String slug = p.getSlug();
            if (slug != null && !slug.isBlank()) {
                keys.add(slug.toLowerCase(Locale.ROOT));
            }
            String title = p.getTitle();
            if (title != null && !title.isBlank()) {
                keys.add(title.trim().toLowerCase(Locale.ROOT));
            }
        }
        return keys;
    }

    // ============================================================
    // Cascade rewrite — used by page delete + rename to update referrers
    // ============================================================

    /**
     * Strip every {@code [[deletedSlug]]} or {@code [[deletedSlug|alias]]}
     * occurrence in {@code content}, replacing the wikilink with plain text:
     *
     * <ul>
     *   <li>{@code [[deletedSlug]]} → {@code snapshotDisplay} (the deleted
     *       page's last known title, or the slug itself if title missing)</li>
     *   <li>{@code [[deletedSlug|alias]]} → {@code alias} (the author's
     *       chosen display text wins)</li>
     * </ul>
     *
     * Mirrors {@link #extractOutlinks} on every protective axis: code blocks
     * are skipped via the same fenced/inline strip-and-restore dance below,
     * matching is exact case-insensitive on the slug only (never on the
     * alias), and at-most-one {@code |} is honoured so a malformed
     * {@code [[a|b|c]]} keeps the b|c suffix as alias text rather than
     * collapsing.
     */
    public String stripDeletedLink(String content, String deletedSlug, String snapshotDisplay) {
        if (content == null || content.isEmpty()) return content;
        if (deletedSlug == null || deletedSlug.isBlank()) return content;
        String targetLower = deletedSlug.toLowerCase(Locale.ROOT);
        String fallback = (snapshotDisplay != null && !snapshotDisplay.isBlank())
                ? snapshotDisplay : deletedSlug;
        return rewriteWikilinks(content, (slugLower, alias) -> {
            if (!slugLower.equals(targetLower)) return null; // unchanged
            // No href to preserve — the link is being demoted to plain text.
            return (alias != null && !alias.isBlank()) ? alias : fallback;
        });
    }

    /**
     * Rewrite every {@code [[oldSlug]]} or {@code [[oldSlug|alias]]} so the
     * target becomes {@code newSlug}. Preserves the wikilink form — only the
     * slug part changes, the alias (if any) is kept verbatim. Used when a
     * page is renamed and every referrer must follow.
     */
    public String renameLink(String content, String oldSlug, String newSlug) {
        if (content == null || content.isEmpty()) return content;
        if (oldSlug == null || oldSlug.isBlank() || newSlug == null || newSlug.isBlank()) return content;
        String oldLower = oldSlug.toLowerCase(Locale.ROOT);
        return rewriteWikilinks(content, (slugLower, alias) -> {
            if (!slugLower.equals(oldLower)) return null;
            // Return the full replacement string for this wikilink occurrence
            // (still a wikilink, just with a different target).
            return (alias != null && !alias.isBlank())
                    ? "[[" + newSlug + "|" + alias + "]]"
                    : "[[" + newSlug + "]]";
        });
    }

    /**
     * Decides what to do with one wikilink during reconciliation. Receives the
     * original-case target (before any {@code |alias}) and the explicit alias
     * (or {@code null}); returns {@code null} to leave the link untouched, or
     * the literal replacement text — plain text to demote a dangling link, or a
     * re-formed {@code [[coverSlug|display]]} to redirect it to a covering page.
     */
    @FunctionalInterface
    public interface LinkReconciler {
        String reconcile(String target, String alias);
    }

    /**
     * Walk {@code content} and hand every wikilink to {@code reconciler}, used
     * by the post-ingestion pass that redirects or demotes links the model
     * wrote to concepts that never became their own page. Mirrors
     * {@link #rewriteWikilinks} (code spans preserved verbatim) but passes the
     * original-case target so the reconciler can build readable display text.
     */
    public String reconcileLinks(String content, LinkReconciler reconciler) {
        if (content == null || content.isEmpty()) return content;
        List<Region> regions = splitByCode(content);
        StringBuilder out = new StringBuilder(content.length() + 16);
        for (Region r : regions) {
            if (r.isCode) { out.append(r.text); continue; }
            Matcher m = WIKILINK.matcher(r.text);
            int last = 0;
            while (m.find()) {
                out.append(r.text, last, m.start());
                String raw = m.group(1).trim();
                int pipe = raw.indexOf('|');
                String target = (pipe >= 0 ? raw.substring(0, pipe) : raw).trim();
                String alias = pipe >= 0 ? raw.substring(pipe + 1).trim() : null;
                String replacement = target.isEmpty() ? null : reconciler.reconcile(target, alias);
                out.append(replacement == null ? m.group() : replacement);
                last = m.end();
            }
            out.append(r.text, last, r.text.length());
        }
        return out.toString();
    }

    /**
     * Walk {@code content} replacing wikilinks via {@code rewriter}. Code
     * spans are detected and restored verbatim — replacement only happens in
     * "narrative" regions so a doc literally showing {@code [[foo]]} inside
     * a code fence is never silently mutated.
     * <p>
     * The rewriter receives the lowercased slug and the raw alias (or
     * {@code null}). It returns either:
     * <ul>
     *   <li>{@code null} to leave the wikilink unchanged (caller is not
     *       interested in this slug), or</li>
     *   <li>the literal replacement string — typically plain text for a
     *       strip, or a re-formed {@code [[newSlug]]} for a rename.</li>
     * </ul>
     */
    private String rewriteWikilinks(String content,
                                    java.util.function.BiFunction<String, String, String> rewriter) {
        // Split content into alternating "narrative" and "code" regions so we
        // can apply the rewriter only to narrative. The same fenced+inline
        // patterns the extractor uses, but here we preserve the matched code
        // text verbatim instead of stripping it.
        List<Region> regions = splitByCode(content);
        StringBuilder out = new StringBuilder(content.length() + 16);
        for (Region r : regions) {
            if (r.isCode) {
                out.append(r.text);
                continue;
            }
            Matcher m = WIKILINK.matcher(r.text);
            int last = 0;
            while (m.find()) {
                out.append(r.text, last, m.start());
                String raw = m.group(1).trim();
                String target;
                String alias;
                int pipe = raw.indexOf('|');
                if (pipe >= 0) {
                    target = raw.substring(0, pipe).trim();
                    alias = raw.substring(pipe + 1).trim();
                } else {
                    target = raw;
                    alias = null;
                }
                String replacement = null;
                if (!target.isEmpty()) {
                    replacement = rewriter.apply(target.toLowerCase(Locale.ROOT), alias);
                }
                if (replacement == null) {
                    out.append(m.group());
                } else {
                    out.append(replacement);
                }
                last = m.end();
            }
            out.append(r.text, last, r.text.length());
        }
        return out.toString();
    }

    /** Linear scan that splits content into alternating narrative + code regions. */
    private List<Region> splitByCode(String content) {
        List<Region> result = new ArrayList<>();
        if (content == null || content.isEmpty()) return result;
        // Run fenced first, then inline within each non-code piece.
        List<Region> afterFenced = splitOne(content, FENCED_CODE);
        for (Region r : afterFenced) {
            if (r.isCode) { result.add(r); continue; }
            result.addAll(splitOne(r.text, INLINE_CODE));
        }
        return result;
    }

    private List<Region> splitOne(String text, Pattern codePattern) {
        List<Region> out = new ArrayList<>();
        Matcher m = codePattern.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.add(new Region(text.substring(last, m.start()), false));
            out.add(new Region(m.group(), true));
            last = m.end();
        }
        if (last < text.length()) out.add(new Region(text.substring(last), false));
        return out;
    }

    /** Narrative-vs-code text region used by {@link #rewriteWikilinks}. */
    private record Region(String text, boolean isCode) {}
}
