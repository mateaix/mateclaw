// Wiki wikilink postprocess — resolves `[[slug]]` / `[[slug|display]]` markers
// in already-rendered markdown HTML into the three canonical link states the
// Wiki page viewer ships:
//
//   <a class="wiki-link"           data-slug=...>  active page hit
//   <a class="wiki-link wiki-link-archived" ...>   archived page hit
//   <span class="wiki-link-broken" title=...>      unresolved / unsafe target
//
// Two reasons this lives outside the viewer .vue:
//
//   1. The previous regex-based substitution was unsafe (interpolated raw
//      target into HTML attributes, didn't skip code blocks, guessed slugs by
//      lower-casing titles). Putting the new logic in a pure helper lets the
//      6 safety cases get covered by ordinary unit tests instead of mounting
//      the whole Vue component.
//   2. The DOM walker has to skip code/pre/kbd/samp subtrees. That's the only
//      "code block protection" needed once markdown has already produced
//      proper <pre><code> wrappers — no string-level sentinel substitution.

/**
 * Lightweight {slug, title, archived} entry. Shape mirrors the backend
 * `PageRef` DTO and the store's `WikiPageRef`. Kept local to avoid creating
 * a build dependency from this file onto the Pinia store.
 */
export interface WikilinkRef {
  slug: string
  title: string
  archived?: boolean
}

/** Result of resolving a single `[[...]]` target string. */
export type WikilinkResolution =
  | { kind: 'hit'; slug: string; display: string; archived: boolean }
  | { kind: 'broken'; display: string; reason: 'empty' | 'dangerous' | 'too-long' | 'unknown' }

/**
 * Tags whose contents must not be touched. `<pre>` and `<code>` cover fenced
 * and inline code blocks emitted by marked; `<kbd>` and `<samp>` are listed
 * for completeness so authors can show literal wikilink syntax in docs
 * without it being silently rewritten.
 */
const SKIP_TAGS = new Set(['PRE', 'CODE', 'KBD', 'SAMP'])

/**
 * Characters that turn a wikilink into an attribute-injection or HTML-context
 * escape risk. The list is intentionally narrow — slug values can legitimately
 * contain CJK and `-`, so we reject only what is unambiguously dangerous (HTML
 * delimiters, quote characters, backtick, line breaks, C0 / DEL control bytes).
 *
 * 0x00–0x1F (excluding TAB which is rare in slugs anyway) and 0x7F catch the
 * NUL / control-char family that broke an earlier draft of this RFC document
 * when someone wrote them literally instead of as escape text. If a real slug
 * needs a tab character, that is a backend bug worth surfacing.
 */
// eslint-disable-next-line no-control-regex
const DANGEROUS_CHAR_RE = /[<>"'`\n\r\x00-\x1F\x7F]/

/** Slug length cap. Backend `toSlug` produces slugs well under this. */
const MAX_SLUG_LEN = 256

/** `[[...]]` matcher used during text-node walking. Non-greedy. */
const WIKILINK_RE = /\[\[([^\]]+?)\]\]/g

/**
 * Resolve a single raw target into a render directive.
 *
 * The function is pure: no DOM access, no store reads. Tests drive it with
 * synthetic `refs` arrays to verify each of the six safety cases enumerated in
 * the RFC (script tag, double quote, nested brackets, multi `|`, empty, oversize).
 *
 * Resolution order:
 *   1. Empty / dangerous / oversize → broken span, raw never enters output
 *      attributes. The visible text falls back to the original `[[...]]`
 *      literal so users can spot the malformed content.
 *   2. Exact slug match against active refs.
 *   3. Title match (trim + case-insensitive) against active refs.
 *   4. Same two passes against archived refs — hit renders as archived.
 *   5. Otherwise broken.
 *
 * Title-fallback is kept because the RFC's migration plan allows older content
 * that still writes `[[Page Title]]` to keep resolving for six months while
 * the slug-first prompt rollout (Phase 3) replaces it. Once that window closes
 * the title branch can be deleted without any other code change.
 */
export function resolveWikilink(
  raw: string,
  refs: WikilinkRef[],
  archivedRefs: WikilinkRef[] = [],
): WikilinkResolution {
  const rawTrimmed = (raw ?? '').trim()
  const literal = `[[${raw ?? ''}]]`

  if (!rawTrimmed) {
    return { kind: 'broken', display: literal, reason: 'empty' }
  }
  if (DANGEROUS_CHAR_RE.test(rawTrimmed)) {
    return { kind: 'broken', display: literal, reason: 'dangerous' }
  }
  // Split [[target|display]] form. Only the first `|` is honoured; any extras
  // are kept verbatim in the display text and trigger the dangerous-char path
  // only if they collide with the rejection set (they don't, `|` is allowed).
  //
  // `explicitDisplay` is the empty string when the source uses the bare
  // `[[target]]` form. In that case the visible label falls back to the
  // resolved page's title (more readable than the slug). When the source
  // explicitly overrides via `|`, that override always wins.
  const pipeIdx = rawTrimmed.indexOf('|')
  const target = pipeIdx >= 0 ? rawTrimmed.slice(0, pipeIdx).trim() : rawTrimmed
  const explicitDisplay = pipeIdx >= 0 ? rawTrimmed.slice(pipeIdx + 1).trim() : ''

  if (!target) {
    return { kind: 'broken', display: literal, reason: 'empty' }
  }
  if (target.length > MAX_SLUG_LEN) {
    return { kind: 'broken', display: literal, reason: 'too-long' }
  }

  const lookupSlug = target.toLowerCase()
  const lookupTitle = target.trim().toLowerCase()

  for (const ref of refs) {
    if (ref.slug.toLowerCase() === lookupSlug) {
      return { kind: 'hit', slug: ref.slug, display: explicitDisplay || ref.title, archived: false }
    }
  }
  for (const ref of refs) {
    if (ref.title.trim().toLowerCase() === lookupTitle) {
      return { kind: 'hit', slug: ref.slug, display: explicitDisplay || ref.title, archived: false }
    }
  }
  for (const ref of archivedRefs) {
    if (ref.slug.toLowerCase() === lookupSlug) {
      return { kind: 'hit', slug: ref.slug, display: explicitDisplay || ref.title, archived: true }
    }
  }
  for (const ref of archivedRefs) {
    if (ref.title.trim().toLowerCase() === lookupTitle) {
      return { kind: 'hit', slug: ref.slug, display: explicitDisplay || ref.title, archived: true }
    }
  }
  return { kind: 'broken', display: literal, reason: 'unknown' }
}

/**
 * Build the DOM element for a resolution result.
 *
 * Always uses `document.createElement` + `textContent` + `setAttribute`. No
 * `innerHTML` writes anywhere — the previous regex-based substitution path
 * concatenated raw target strings into HTML and was the original source of
 * the bug class this RFC closes.
 */
function buildLinkElement(
  doc: Document,
  resolution: WikilinkResolution,
): HTMLElement {
  if (resolution.kind === 'hit') {
    const a = doc.createElement('a')
    a.className = resolution.archived ? 'wiki-link wiki-link-archived' : 'wiki-link'
    a.setAttribute('data-slug', resolution.slug)
    // No href: the page viewer hooks click via the global `wiki-link` listener
    // and routes through the Pinia store. Adding a real href would expose the
    // app to middle-click "open in new tab" 404s since the route layer is SPA.
    a.setAttribute('role', 'link')
    a.setAttribute('tabindex', '0')
    if (resolution.archived) {
      a.setAttribute('title', 'Archived page')
    }
    a.textContent = resolution.display
    return a
  }
  const span = doc.createElement('span')
  span.className = 'wiki-link-broken'
  span.setAttribute('title', `Target not found (${resolution.reason})`)
  span.textContent = resolution.display
  return span
}

/**
 * Walk the rendered article DOM and replace every `[[...]]` token inside a
 * text node with the appropriate `<a>` or `<span>` element.
 *
 * The walker uses {@link TreeWalker} with `NodeFilter.SHOW_TEXT` so we only
 * ever look at text nodes — element nodes and their attributes are not even
 * candidates for substitution. The filter additionally rejects any text node
 * whose ancestor chain crosses a {@link SKIP_TAGS} element, so code blocks,
 * inline code and the other docstring-style tags remain literal.
 *
 * The function is idempotent: text nodes that no longer match `[[...]]` are
 * skipped, and previously-inserted `<a>`/`<span>` elements have no text-node
 * children carrying the original syntax (it is consumed by the regex split).
 */
export function postprocessWikilinks(
  root: HTMLElement,
  resolver: (raw: string) => WikilinkResolution,
  doc: Document = root.ownerDocument ?? document,
): void {
  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      // Reject text nodes inside any of the skip tags. Walking ancestors is
      // cheap because the markdown tree depth is bounded by Marked's grammar.
      let parent: Node | null = node.parentNode
      while (parent && parent !== root) {
        if (parent.nodeType === 1 /* ELEMENT_NODE */) {
          const tag = (parent as Element).tagName
          if (SKIP_TAGS.has(tag)) return NodeFilter.FILTER_REJECT
        }
        parent = parent.parentNode
      }
      return WIKILINK_RE.test(node.nodeValue ?? '')
        ? NodeFilter.FILTER_ACCEPT
        : NodeFilter.FILTER_REJECT
    },
  })

  // Collect first, mutate second — mutating the tree while walking it would
  // skip siblings or revisit nodes.
  const targets: Text[] = []
  let cur = walker.nextNode()
  while (cur) {
    targets.push(cur as Text)
    cur = walker.nextNode()
  }

  for (const textNode of targets) {
    const original = textNode.nodeValue ?? ''
    // Reset regex state — the regex is module-level with /g, so `lastIndex`
    // carries over between text nodes if we don't.
    WIKILINK_RE.lastIndex = 0
    const fragment = doc.createDocumentFragment()
    let lastIdx = 0
    let match: RegExpExecArray | null
    while ((match = WIKILINK_RE.exec(original)) !== null) {
      if (match.index > lastIdx) {
        fragment.appendChild(doc.createTextNode(original.slice(lastIdx, match.index)))
      }
      const resolution = resolver(match[1])
      fragment.appendChild(buildLinkElement(doc, resolution))
      lastIdx = match.index + match[0].length
    }
    if (lastIdx < original.length) {
      fragment.appendChild(doc.createTextNode(original.slice(lastIdx)))
    }
    textNode.parentNode?.replaceChild(fragment, textNode)
  }
}
