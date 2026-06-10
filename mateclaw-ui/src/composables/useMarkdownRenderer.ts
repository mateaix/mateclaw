import { Marked } from 'marked'
import type { Tokens } from 'marked'
import hljs from 'highlight.js'
import DOMPurify from 'dompurify'

// ---------------------------------------------------------------------------
// Language metadata
// ---------------------------------------------------------------------------
const LANG_DISPLAY: Record<string, string> = {
  js: 'JavaScript', javascript: 'JavaScript', ts: 'TypeScript', typescript: 'TypeScript',
  py: 'Python', python: 'Python', java: 'Java', kt: 'Kotlin', kotlin: 'Kotlin',
  go: 'Go', rust: 'Rust', rs: 'Rust', rb: 'Ruby', ruby: 'Ruby',
  cpp: 'C++', c: 'C', cs: 'C#', csharp: 'C#', swift: 'Swift',
  sh: 'Shell', bash: 'Bash', zsh: 'Zsh', shell: 'Shell',
  sql: 'SQL', html: 'HTML', css: 'CSS', scss: 'SCSS', less: 'LESS',
  json: 'JSON', xml: 'XML', yaml: 'YAML', yml: 'YAML', toml: 'TOML',
  md: 'Markdown', markdown: 'Markdown', dockerfile: 'Dockerfile',
  vue: 'Vue', jsx: 'JSX', tsx: 'TSX', php: 'PHP', lua: 'Lua',
}

const KNOWN_LANGS = [
  'typescript', 'javascript', 'python', 'kotlin', 'csharp', 'dockerfile',
  'markdown', 'shell', 'swift', 'rust', 'ruby', 'bash', 'scss', 'less',
  'yaml', 'toml', 'html', 'java', 'json', 'css', 'cpp', 'xml', 'vue',
  'jsx', 'tsx', 'php', 'lua', 'sql', 'zsh', 'yml', 'go', 'kt', 'rs',
  'rb', 'cs', 'ts', 'js', 'py', 'sh', 'md', 'c',
]

function extractLang(raw: string): string {
  if (!raw) return ''
  const lower = raw.toLowerCase()
  if (hljs.getLanguage(lower)) return lower
  for (const lang of KNOWN_LANGS) {
    if (lower.startsWith(lang) && lower.length > lang.length) return lang
  }
  return lower
}

function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

// ---------------------------------------------------------------------------
// Code block thresholds (must match `useMarkdownRenderer` doc comments)
// ---------------------------------------------------------------------------
/** Lines >= this trigger collapsible <details> wrap. */
const COLLAPSE_LINE_THRESHOLD = 20
/** JSON blob char count >= this triggers collapse even when line count is low. */
const COLLAPSE_JSON_CHAR_THRESHOLD = 800

// ---------------------------------------------------------------------------
// Link safety
// ---------------------------------------------------------------------------
/**
 * Scheme whitelist. Only http(s), mailto, fragment, and same-origin paths
 * (absolute `/...`, relative `./...` / `../...`) are permitted. Everything
 * else (javascript:, data:, vbscript:, file:, …) is degraded to plain text.
 */
const SAFE_LINK_RE = /^(https?:|mailto:|#|\/|\.\/|\.\.\/)/i

// ---------------------------------------------------------------------------
// LaTeX pre-processor
// ---------------------------------------------------------------------------
// `$$ ... $$` (block) and `$ ... $` (inline) are extracted from raw markdown
// and replaced with HTML placeholders that survive marked + DOMPurify. The
// post-render KaTeX composable (useKatexRenderer) finds them by class +
// data-tex attribute and mounts the typeset output.
//
// We deliberately walk the source character-by-character rather than running
// a global regex, so that fenced/inline code blocks are skipped — otherwise
// dollar signs inside Bash snippets or JSON blobs would be misinterpreted.

function preprocessLatex(text: string): string {
  let out = ''
  let i = 0
  let inFence = false
  let fenceMarker = ''
  while (i < text.length) {
    // Detect fence open/close at line start.
    if (i === 0 || text[i - 1] === '\n') {
      const fenceMatch = /^(```+|~~~+)([^\n]*)/.exec(text.slice(i))
      if (fenceMatch) {
        const marker = fenceMatch[1]
        if (!inFence) {
          inFence = true
          fenceMarker = marker
        } else if (marker.length >= fenceMarker.length && marker[0] === fenceMarker[0]) {
          inFence = false
          fenceMarker = ''
        }
        out += fenceMatch[0]
        i += fenceMatch[0].length
        continue
      }
    }
    if (inFence) {
      out += text[i++]
      continue
    }

    // Inline code: copy verbatim until the matching backtick run.
    if (text[i] === '`') {
      let n = 0
      while (text[i + n] === '`') n++
      const tickRun = '`'.repeat(n)
      const close = text.indexOf(tickRun, i + n)
      if (close < 0) {
        // Unmatched — treat the rest as text but still advance past the ticks.
        out += text[i++]
        continue
      }
      out += text.slice(i, close + n)
      i = close + n
      continue
    }

    // LaTeX-style block math: \[...\]  — must be checked BEFORE marked sees
    // the source, because CommonMark eats the backslash escape (`\[ → [`)
    // and the marker would be lost. LLMs (DeepSeek, Qwen, Claude) emit this
    // form heavily for display equations.
    if (text[i] === '\\' && text[i + 1] === '[') {
      const close = text.indexOf('\\]', i + 2)
      // Bound length so a stray `\[` doesn't swallow the rest of the doc.
      if (close > 0 && close - i < 800) {
        const tex = text.slice(i + 2, close)
        out += `\n\n<div class="katex-block" data-tex="${encodeURIComponent(tex)}"></div>\n\n`
        i = close + 2
        continue
      }
    }
    // LaTeX-style inline math: \(...\)
    if (text[i] === '\\' && text[i + 1] === '(') {
      const close = text.indexOf('\\)', i + 2)
      if (close > 0 && close - i < 400) {
        const tex = text.slice(i + 2, close)
        out += `<span class="katex-inline" data-tex="${encodeURIComponent(tex)}"></span>`
        i = close + 2
        continue
      }
    }
    // Block math: $$...$$
    if (text[i] === '$' && text[i + 1] === '$') {
      const close = text.indexOf('$$', i + 2)
      if (close > 0) {
        const tex = text.slice(i + 2, close)
        // Wrap in newlines so marked treats the placeholder as its own block,
        // not glued onto a surrounding paragraph (which would make <div> a
        // direct child of <p> — invalid HTML the browser silently splits).
        out += `\n\n<div class="katex-block" data-tex="${encodeURIComponent(tex)}"></div>\n\n`
        i = close + 2
        continue
      }
    }
    // Inline math: $...$  — require non-whitespace adjacent to the dollars
    // so that "$5.99" or "saved $10" are NOT treated as math.
    if (text[i] === '$') {
      const m = /^\$([^$\n]+?)\$(?!\d)/.exec(text.slice(i))
      if (m && !/^\s/.test(m[1]) && !/\s$/.test(m[1])) {
        const tex = m[1]
        out += `<span class="katex-inline" data-tex="${encodeURIComponent(tex)}"></span>`
        i += m[0].length
        continue
      }
    }

    out += text[i++]
  }
  return out
}

// ---------------------------------------------------------------------------
// Product cards
// ---------------------------------------------------------------------------
/** Shape the model is asked to emit inside a ```product-cards fence. */
interface ProductCard {
  name?: string
  url?: string
  imageUrl?: string
  price?: number | string
  originalPrice?: number | string
  lowestPrice?: number | string
  platformLabel?: string
  shopName?: string
  purchaseAdvice?: string
}

/** Format a numeric/string amount as `¥1,234` (drops a trailing `.0`). */
function formatPrice(v: number | string | undefined): string {
  if (v === undefined || v === null || v === '') return ''
  const n = typeof v === 'number' ? v : Number(String(v).replace(/[^\d.]/g, ''))
  if (!Number.isFinite(n)) return ''
  const s = Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/\.0+$/, '')
  return '¥' + s.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/**
 * Render a ```product-cards fenced JSON block into a clickable card grid.
 *
 * Accepts a bare array or an object wrapping the array under
 * `recommendations` / `products` / `items`. While streaming, the JSON is
 * frequently incomplete — we swallow the parse error and show a lightweight
 * loading placeholder rather than dumping half a JSON blob into the bubble.
 */
function renderProductCards(rawCode: string): string {
  let items: ProductCard[] = []
  try {
    const parsed = JSON.parse(rawCode)
    if (Array.isArray(parsed)) items = parsed
    else if (parsed && typeof parsed === 'object') {
      items = parsed.recommendations || parsed.products || parsed.items || []
    }
  } catch {
    return '<div class="product-cards product-cards--loading">'
      + '<span class="product-cards__dot"></span>'
      + '<span class="product-cards__dot"></span>'
      + '<span class="product-cards__dot"></span>'
      + '</div>'
  }
  if (!Array.isArray(items) || items.length === 0) return ''

  const cards = items.map((it) => {
    const href = typeof it.url === 'string' && SAFE_LINK_RE.test(it.url) ? it.url : ''
    const name = escapeHtml(String(it.name ?? '').trim()) || '商品'
    const img = typeof it.imageUrl === 'string' && /^https?:/i.test(it.imageUrl) ? it.imageUrl : ''
    const now = formatPrice(it.price)
    const wasNum = typeof it.originalPrice === 'number' ? it.originalPrice : Number(it.originalPrice)
    const nowNum = typeof it.price === 'number' ? it.price : Number(it.price)
    const showWas = Number.isFinite(wasNum) && Number.isFinite(nowNum) && wasNum > nowNum
    const was = showWas ? formatPrice(it.originalPrice) : ''
    const low = formatPrice(it.lowestPrice)
    const platform = escapeHtml(String(it.platformLabel ?? '').trim())
    const shop = escapeHtml(String(it.shopName ?? '').trim())
    const advice = escapeHtml(String(it.purchaseAdvice ?? '').trim())

    // target/rel (anchor) and referrerpolicy/loading (img) are re-applied by the
    // afterSanitizeAttributes hook — DOMPurify strips them here regardless.
    const media = img
      ? `<div class="product-card__media"><img src="${escapeHtml(img)}" alt="${name}"></div>`
      : `<div class="product-card__media product-card__media--empty"></div>`
    const meta = [platform, shop].filter(Boolean).join(' · ')
    const priceLine = now
      ? `<div class="product-card__price"><span class="product-card__price-now">${now}</span>`
        + (was ? `<span class="product-card__price-was">${was}</span>` : '')
        + `</div>`
      : ''
    // The whole card is the anchor, but a visible CTA makes the "tap to buy"
    // affordance explicit (an `<a>` can't legally wrap a `<button>`, so this is
    // a styled span). Only shown when there's a real buy URL.
    const platformWord = platform || '商家'
    const buyCta = href
      ? `<span class="product-card__buy">去${platformWord}购买<span class="product-card__buy-arrow">→</span></span>`
      : ''
    const body = `<div class="product-card__body">`
      + `<div class="product-card__name">${name}</div>`
      + priceLine
      + (meta ? `<div class="product-card__meta">${meta}</div>` : '')
      + (low ? `<div class="product-card__low">历史最低 ${low}</div>` : '')
      + (advice ? `<div class="product-card__advice">${advice}</div>` : '')
      + buyCta
      + `</div>`

    if (href) {
      return `<a class="product-card" href="${escapeHtml(href)}">${media}${body}</a>`
    }
    return `<div class="product-card product-card--nolink">${media}${body}</div>`
  }).join('')

  return `<div class="product-cards">${cards}</div>`
}

// ---------------------------------------------------------------------------
// Custom renderer (marked v15 requires a plain object — class instances are
// NOT dispatched).
// ---------------------------------------------------------------------------
const customRenderer = {
  code({ text, lang }: { type: string; raw: string; text: string; lang?: string }): string {
    const rawCode = text || ''
    const infoStr = (lang || '').split(/\s/)[0]

    // Mermaid: ship raw source through a placeholder div for the
    // useMermaidRenderer post-mount step. Skip syntax highlighting entirely.
    // The header (lang label + Copy + Download SVG) is part of the placeholder
    // so users can copy the diagram source even before render completes; the
    // composable paints the SVG into `.mermaid-block__body`.
    if (infoStr === 'mermaid') {
      const encoded = encodeURIComponent(rawCode)
      const copySvg = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`
      const downloadSvg = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>`
      return `<div class="mermaid-block" data-mermaid="${encoded}">`
        + `<div class="mermaid-block__header">`
        + `<span class="mermaid-block__lang">Mermaid</span>`
        + `<span class="mermaid-block__actions">`
        + `<button class="code-block__copy" type="button" data-code="${encoded}" aria-label="Copy diagram source">`
        + copySvg
        + `<span class="code-block__copy-text">Copy</span>`
        + `</button>`
        + `<button class="mermaid-block__download" type="button" data-mermaid-download="1" aria-label="Download SVG">`
        + downloadSvg
        + `<span class="mermaid-block__download-text">SVG</span>`
        + `</button>`
        + `</span>`
        + `</div>`
        + `<div class="mermaid-block__body"></div>`
        + `</div>`
    }

    // ECharts: same pattern, mounted by useEChartsRenderer.
    if (infoStr === 'echarts') {
      return `<div class="echarts-block" data-echarts-option="${encodeURIComponent(rawCode)}"></div>`
    }

    // Product cards: a ```product-cards fenced block carries a JSON array (or an
    // object wrapping `recommendations` / `products` / `items`) of shopping
    // recommendations. We render it inline as a clickable card grid — image,
    // name, price, platform — so price-comparison results show up as real cards
    // in the chat instead of a markdown list. Pure HTML, no post-mount step.
    if (infoStr === 'product-cards') {
      return renderProductCards(rawCode)
    }

    const detectedLang = extractLang(infoStr)
    const hasLanguage = !!detectedLang && !!hljs.getLanguage(detectedLang)

    let highlighted: string
    try {
      highlighted = hasLanguage
        ? hljs.highlight(rawCode, { language: detectedLang }).value
        : hljs.highlightAuto(rawCode).value
    } catch {
      highlighted = escapeHtml(rawCode)
    }

    const langLabel = LANG_DISPLAY[detectedLang] || detectedLang || 'Code'
    const encodedCode = encodeURIComponent(rawCode)
    const langClass = hasLanguage ? ` language-${detectedLang}` : ''

    // Split into one <li> per source line so CSS counter renders the gutter.
    // We trim a trailing empty line if highlight.js produced one (common when
    // the user's fenced block ends with a newline), to avoid a blank tail row.
    const rawLines = highlighted.split('\n')
    if (rawLines.length && rawLines[rawLines.length - 1] === '') rawLines.pop()
    const lineCount = rawLines.length || 1
    const linesHtml = `<ol class="hljs-lines">${rawLines.map(l => `<li>${l || ' '}</li>`).join('')}</ol>`

    const isJson = detectedLang === 'json'
    const isLongJson = isJson && rawCode.length >= COLLAPSE_JSON_CHAR_THRESHOLD
    const shouldCollapse = lineCount >= COLLAPSE_LINE_THRESHOLD || isLongJson
    // Default-open for normal long code (the user wants to see it; the
    // collapsible header is just an opt-in fold). Default-closed only for
    // giant JSON blobs, which are typically noisy tool-call output.
    const openByDefault = !isLongJson

    // Header content: lang badge (left) — line-count badge (only shown when
    // collapsed) — copy button (right). We render the SAME inner content into
    // either a <div class="code-block__header"> (non-collapsible) or directly
    // into <summary class="code-block__header"> (collapsible). Nesting a div
    // inside <summary> caused weird browser-native height behavior and made
    // the header visibly inflate; flattening fixes it.
    const headerInner = `<span class="code-block__lang">${escapeHtml(langLabel)}</span>`
      + `<span class="code-block__lines">${lineCount} lines</span>`
      + `<button class="code-block__copy" type="button" data-code="${encodedCode}" aria-label="Copy code">`
      + `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`
      + `<span class="code-block__copy-text">Copy</span>`
      + `</button>`

    const codeBody = `<pre><code class="hljs${langClass}">${linesHtml}</code></pre>`

    if (shouldCollapse) {
      const openAttr = openByDefault ? ' open' : ''
      return `<details class="code-block code-block--collapsible"${openAttr}>`
        + `<summary class="code-block__header">${headerInner}</summary>`
        + codeBody
        + `</details>`
    }
    return `<div class="code-block">`
      + `<div class="code-block__header">${headerInner}</div>`
      + codeBody
      + `</div>`
  },

  link({ href, title, tokens }: Tokens.Link): string {
    // marked v15 passes already-parsed inline tokens; render them ourselves so
    // that the inner content keeps any bold/italic formatting from `[**x**](u)`.
    const innerHtml = (this as unknown as { parser: { parseInline: (t: unknown[]) => string } })
      .parser.parseInline(tokens)

    if (!href || !SAFE_LINK_RE.test(href)) {
      // Dangerous scheme — render the inner content as plain content (no anchor).
      return innerHtml
    }
    // Defense against LLMs hallucinating a host on tool-returned download URLs.
    // /api/v1/files/generated/<id> is always same-origin; multiple models have
    // been observed prepending bogus schemes/hosts (https://localhost:8080,
    // https://ai-tools-system.com, …) when echoing the URL back, breaking the
    // download. Strip any prepended scheme://host so the link works regardless
    // of what the model wrote.
    const hostStripped = /^https?:\/\/[^/]+(\/api\/v1\/files\/generated\/.+)$/i.exec(href)
    const safeHref = hostStripped ? hostStripped[1] : href

    let extra = ''
    try {
      const url = new URL(safeHref, typeof window !== 'undefined' ? window.location.href : 'http://localhost/')
      if (typeof window !== 'undefined' && url.origin !== window.location.origin) {
        extra = ' target="_blank" rel="noopener noreferrer"'
      }
    } catch {
      // Malformed URL — treat as same-origin (relative link path).
    }
    const titleAttr = title ? ` title="${escapeHtml(title)}"` : ''
    return `<a href="${escapeHtml(safeHref)}"${titleAttr}${extra}>${innerHtml}</a>`
  },
}

// ---------------------------------------------------------------------------
// marked instance
// ---------------------------------------------------------------------------
const markedInstance = new Marked({
  gfm: true,
  breaks: true,
  renderer: customRenderer,
})

// ---------------------------------------------------------------------------
// DOMPurify config — allow Markdown + custom blocks (code-block, KaTeX/Mermaid
// placeholders) and the inline copy SVG button.
// ---------------------------------------------------------------------------
const purifyConfig = {
  ADD_ATTR: [
    'target', 'rel', 'class',
    'data-code', 'data-echarts-option', 'data-wiki-title', 'data-slug',
    'data-tex', 'data-mermaid', 'data-mermaid-download',
    'x1', 'y1', 'x2', 'y2',
    'aria-label', 'open',
    'type', 'viewBox', 'fill', 'stroke', 'stroke-width', 'd',
    'x', 'y', 'width', 'height', 'rx', 'ry', 'points',
  ],
  ADD_TAGS: [
    'input', 'button', 'svg', 'path', 'rect', 'polyline', 'circle', 'line',
    'span', 'details', 'summary',
  ],
  // Defence in depth: even if a malicious href slips past our link()
  // override, DOMPurify drops anything outside this whitelist.
  ALLOWED_URI_REGEXP: /^(?:https?:|mailto:|#|\/|\.\/|\.\.\/)/i,
}

// The custom ALLOWED_URI_REGEXP above also vets non-URI attribute *values*, so
// DOMPurify strips `target="_blank"`, `rel="noopener"`, `referrerpolicy="..."`
// etc. (their values don't match the URL whitelist). For product cards we need
// those back: the buy link must open in a new tab instead of navigating away
// from the chat, and marketplace CDN thumbnails (e.g. 360buyimg) are hotlink-
// protected and only load with `referrer-policy: no-referrer`. An
// afterSanitizeAttributes hook re-applies them with fixed, safe values —
// attributes set inside this hook are NOT re-validated, so this is the
// canonical DOMPurify pattern. Scoped strictly to product-card nodes so no
// other rendered markdown changes behaviour.
let productCardHookRegistered = false
function ensureProductCardHook(): void {
  if (productCardHookRegistered) return
  productCardHookRegistered = true
  DOMPurify.addHook('afterSanitizeAttributes', (node: Element) => {
    if (!node || typeof node.tagName !== 'string') return
    const tag = node.tagName.toLowerCase()
    if (tag === 'a' && node.classList?.contains('product-card')) {
      node.setAttribute('target', '_blank')
      node.setAttribute('rel', 'noopener noreferrer')
    } else if (tag === 'img' && typeof node.closest === 'function' && node.closest('.product-cards')) {
      node.setAttribute('referrerpolicy', 'no-referrer')
      node.setAttribute('loading', 'lazy')
      node.setAttribute('decoding', 'async')
    }
  })
}
ensureProductCardHook()

// ---------------------------------------------------------------------------
// LRU render cache
// ---------------------------------------------------------------------------
// Streaming token-by-token defeats this (each delta produces a new key) but
// scrolling history and re-renders of completed messages are common, and the
// cost of marked + highlight.js + DOMPurify is non-trivial for long messages.
const RENDER_CACHE = new Map<string, string>()
const RENDER_CACHE_CAP = 200

function cacheKey(text: string, wikilink: WikilinkMode): string {
  // Compact key — collisions on the order of 10^-6 in single-conversation
  // scope, and a false hit only causes a "stale" render of unchanged content
  // (no security implication since cached values are sanitized HTML).
  // The wikilink mode is part of the key so a 'none' caller cannot read back
  // a 'legacy'-substituted cached entry of the same source.
  return `${wikilink}:${text.length}:${text.slice(0, 40)}:${text.slice(-40)}`
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Wikilink handling mode for {@link useMarkdownRenderer}.
 *
 * - `'legacy'` (default): pre-markdown string substitution of `[[Title]]` into
 *   `<a class="wiki-link" data-wiki-title="...">`, dispatching the global
 *   `wiki-link-click` event when clicked. Kept for chat / other views that
 *   already rely on this behaviour.
 * - `'none'`: skip wikilink substitution entirely. Use this when the caller
 *   wants to walk the rendered DOM itself and resolve `[[...]]` against an
 *   authoritative `{slug, title}` index — the dedicated path used by the Wiki
 *   page viewer, where the legacy "guess slug from title" approach is unsafe.
 */
export type WikilinkMode = 'legacy' | 'none'

export interface RenderMarkdownOptions {
  /** How to handle `[[...]]` syntax. Defaults to `'legacy'`. */
  wikilink?: WikilinkMode
}

export function useMarkdownRenderer() {
  function renderMarkdown(content: string, opts?: RenderMarkdownOptions): string {
    if (!content) return ''
    const wikilink: WikilinkMode = opts?.wikilink ?? 'legacy'
    const k = cacheKey(content, wikilink)
    const cached = RENDER_CACHE.get(k)
    if (cached !== undefined) {
      // Refresh LRU position — re-insert at the tail.
      RENDER_CACHE.delete(k)
      RENDER_CACHE.set(k, cached)
      return cached
    }

    // 1. LaTeX placeholders (skips fenced/inline code).
    const withLatex = preprocessLatex(content)
    // 2. Wiki link substitution: [[Title]] → <a class="wiki-link" …>.
    //    Skipped in 'none' mode so the caller can do its own DOM postprocess.
    const withWikiLinks =
      wikilink === 'none'
        ? withLatex
        : // Split `[[slug|display]]` into slug + display halves so the
          // `data-wiki-title` attribute carries the slug ALONE (the cross-KB
          // lookup keys off that) and the visible label is the display text
          // (the alias an author chose). The earlier single-capture regex
          // copied the whole bracket interior — including the literal `|` —
          // into both, producing `data-wiki-title="slug|display"` lookups
          // that the backend would never resolve.
          withLatex.replace(
            /\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g,
            (_match, slug: string, alias?: string) => {
              const target = slug.trim().replace(/"/g, '&quot;')
              const visible = (alias?.trim() || slug.trim()).replace(/"/g, '&quot;')
              return (
                '<a class="wiki-link" href="#" data-wiki-title="' + target +
                '" onclick="window.dispatchEvent(new CustomEvent(\'wiki-link-click\',{detail:{title:\'' +
                target.replace(/'/g, "\\'") +
                '\'}}));return false">' + visible + '</a>'
              )
            },
          )
    // 3. Marked → 4. DOMPurify.
    const rawHtml = markedInstance.parse(withWikiLinks) as string
    const result = DOMPurify.sanitize(rawHtml, purifyConfig)

    // Evict oldest entry when at capacity (Map preserves insertion order).
    if (RENDER_CACHE.size >= RENDER_CACHE_CAP) {
      const oldestKey = RENDER_CACHE.keys().next().value
      if (oldestKey !== undefined) RENDER_CACHE.delete(oldestKey)
    }
    RENDER_CACHE.set(k, result)
    return result
  }

  function escapeText(text: string): string {
    return escapeHtml(text)
  }

  return {
    renderMarkdown,
    escapeText,
    markedInstance,
  }
}

// Direct singletons for tests / advanced callers.
export { markedInstance, purifyConfig }
export default markedInstance
