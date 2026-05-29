// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import {
  resolveWikilink,
  postprocessWikilinks,
  type WikilinkRef,
} from '../wikilink'

// Compact ref fixture used by most tests. Active refs only — archived cases
// have their own fixtures.
const REFS: WikilinkRef[] = [
  { slug: 'machine-learning-basics', title: '机器学习基础' },
  { slug: 'transformer-architecture', title: 'Transformer Architecture' },
  { slug: 'react-overview', title: 'React Overview' },
]

const ARCHIVED_REFS: WikilinkRef[] = [
  { slug: 'deprecated-concept', title: 'Deprecated Concept' },
]

// ---------------------------------------------------------------------------
// resolveWikilink — pure resolution semantics (no DOM)
// ---------------------------------------------------------------------------
describe('resolveWikilink — happy path', () => {
  it('resolves an exact slug match to a hit', () => {
    const r = resolveWikilink('machine-learning-basics', REFS)
    expect(r).toEqual({
      kind: 'hit',
      slug: 'machine-learning-basics',
      display: '机器学习基础',
      archived: false,
    })
  })

  it('resolves an exact title match to a hit', () => {
    const r = resolveWikilink('Transformer Architecture', REFS)
    expect(r.kind).toBe('hit')
    if (r.kind === 'hit') expect(r.slug).toBe('transformer-architecture')
  })

  it('honours [[slug|display]] alias form', () => {
    const r = resolveWikilink('machine-learning-basics|入门指南', REFS)
    expect(r).toEqual({
      kind: 'hit',
      slug: 'machine-learning-basics',
      display: '入门指南',
      archived: false,
    })
  })

  it('resolves an archived target with archived=true', () => {
    const r = resolveWikilink('deprecated-concept', REFS, ARCHIVED_REFS)
    expect(r.kind).toBe('hit')
    if (r.kind === 'hit') {
      expect(r.archived).toBe(true)
      expect(r.slug).toBe('deprecated-concept')
    }
  })

  it('prefers active refs over archived refs on slug clash', () => {
    const active: WikilinkRef[] = [{ slug: 'foo', title: 'Active Foo' }]
    const archived: WikilinkRef[] = [{ slug: 'foo', title: 'Archived Foo' }]
    const r = resolveWikilink('foo', active, archived)
    expect(r.kind).toBe('hit')
    if (r.kind === 'hit') expect(r.archived).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// 6 safety cases — every one of these MUST degrade to a broken span and MUST
// NOT inject the raw target into any attribute or eval-context. The viewer's
// XSS surface depends on this resolver returning 'broken' for these inputs.
// ---------------------------------------------------------------------------
describe('resolveWikilink — safety cases', () => {
  it('safety 1: <script> tag in target → broken (dangerous)', () => {
    const r = resolveWikilink('<script>alert(1)</script>', REFS)
    expect(r.kind).toBe('broken')
    if (r.kind === 'broken') expect(r.reason).toBe('dangerous')
  })

  it('safety 2: double quote in target → broken (dangerous)', () => {
    const r = resolveWikilink('foo"onmouseover=alert(1)', REFS)
    expect(r.kind).toBe('broken')
    if (r.kind === 'broken') expect(r.reason).toBe('dangerous')
  })

  it('safety 3: adjacent [[a]] [[b]] inside one raw → broken target name', () => {
    // The resolver only ever sees the content between a single pair of [[ ]].
    // If a malformed source contains `[[a]] [[b`, the regex matches `[[a]]`
    // cleanly and the resolver receives 'a'. We instead test the worse case
    // where an open `[[` leaks INTO the raw value via a malformed source.
    const r = resolveWikilink('foo [[ bar', REFS)
    expect(r.kind).toBe('broken') // unknown slug 'foo [[ bar' (no danger char)
  })

  it('safety 4: multiple `|` characters split on first only, no injection', () => {
    const r = resolveWikilink('machine-learning-basics|a|b|c', REFS)
    expect(r.kind).toBe('hit')
    if (r.kind === 'hit') expect(r.display).toBe('a|b|c')
  })

  it('safety 5: empty raw → broken (empty)', () => {
    expect(resolveWikilink('', REFS).kind).toBe('broken')
    expect(resolveWikilink('   ', REFS).kind).toBe('broken')
    expect(resolveWikilink('|display-only', REFS).kind).toBe('broken')
  })

  it('safety 6: oversize slug (>256 chars) → broken (too-long)', () => {
    const big = 'a'.repeat(300)
    const r = resolveWikilink(big, REFS)
    expect(r.kind).toBe('broken')
    if (r.kind === 'broken') expect(r.reason).toBe('too-long')
  })

  it('rejects control characters (NUL, US, DEL)', () => {
    expect(resolveWikilink('slug\x00ish', REFS).kind).toBe('broken')
    expect(resolveWikilink('slug\x1Fish', REFS).kind).toBe('broken')
    expect(resolveWikilink('slug\x7Fish', REFS).kind).toBe('broken')
  })

  it('rejects backtick (template-literal escape vector)', () => {
    expect(resolveWikilink('slug`evil', REFS).kind).toBe('broken')
  })

  it('rejects newlines (would break attribute serialisation)', () => {
    expect(resolveWikilink('slug\nfoo', REFS).kind).toBe('broken')
    expect(resolveWikilink('slug\rfoo', REFS).kind).toBe('broken')
  })
})

// ---------------------------------------------------------------------------
// postprocessWikilinks — DOM walker behaviour
// ---------------------------------------------------------------------------
describe('postprocessWikilinks — DOM behaviour', () => {
  function setup(html: string): HTMLElement {
    const root = document.createElement('div')
    root.innerHTML = html
    return root
  }

  it('replaces a hit into <a class="wiki-link" data-slug>', () => {
    const root = setup('<p>See [[machine-learning-basics]] for more.</p>')
    postprocessWikilinks(root, (raw) => resolveWikilink(raw, REFS, ARCHIVED_REFS))
    const a = root.querySelector('a.wiki-link') as HTMLAnchorElement
    expect(a).not.toBeNull()
    expect(a.getAttribute('data-slug')).toBe('machine-learning-basics')
    expect(a.textContent).toBe('机器学习基础')
    expect(a.getAttribute('href')).toBeNull()
  })

  it('replaces an archived hit into <a class="wiki-link wiki-link-archived">', () => {
    const root = setup('<p>See [[deprecated-concept]].</p>')
    postprocessWikilinks(root, (raw) => resolveWikilink(raw, REFS, ARCHIVED_REFS))
    const a = root.querySelector('a.wiki-link.wiki-link-archived') as HTMLAnchorElement
    expect(a).not.toBeNull()
    expect(a.getAttribute('data-slug')).toBe('deprecated-concept')
    expect(a.getAttribute('title')).toBe('Archived page')
  })

  it('replaces a miss into <span class="wiki-link-broken"> with no clickable surface', () => {
    const root = setup('<p>See [[unknown-page]] sometime.</p>')
    postprocessWikilinks(root, (raw) => resolveWikilink(raw, REFS, ARCHIVED_REFS))
    const span = root.querySelector('span.wiki-link-broken') as HTMLSpanElement
    expect(span).not.toBeNull()
    expect(root.querySelector('a')).toBeNull()
    // Display falls back to the literal [[...]] so the malformed source is
    // visible to the reader.
    expect(span.textContent).toBe('[[unknown-page]]')
  })

  it('does not interpolate dangerous raw into attributes', () => {
    // The realistic vector: a text node carries the literal `<script>` chars,
    // produced by markdown rendering (DOMPurify strips actual <script> tags
    // upstream, so what reaches the postprocess is text). Build the DOM with
    // textContent rather than innerHTML so the test doesn't accidentally make
    // happy-dom parse the literal as a script element before we run.
    const root = document.createElement('div')
    const p = document.createElement('p')
    p.textContent = 'Bad: [[<script>alert(1)</script>]] stay safe.'
    root.appendChild(p)
    expect(root.querySelector('script')).toBeNull() // sanity — text-node form
    postprocessWikilinks(root, (raw) => resolveWikilink(raw, REFS, ARCHIVED_REFS))
    expect(root.querySelector('script')).toBeNull()
    expect(root.querySelector('a')).toBeNull()
    const span = root.querySelector('span.wiki-link-broken') as HTMLSpanElement
    expect(span).not.toBeNull()
    // The attribute carrying user data is the title; verify it's the rejection
    // reason, not the raw payload.
    expect(span.getAttribute('title')).toMatch(/dangerous/)
    // Visible label is the literal [[...]] — readers see the malformed source
    // verbatim instead of having it silently swallowed.
    expect(span.textContent).toContain('<script>')
  })

  it('skips <code> and <pre> subtrees', () => {
    const root = setup(
      '<p>Outside [[machine-learning-basics]] active.</p>' +
        '<pre><code>Inside [[machine-learning-basics]] kept literal.</code></pre>',
    )
    postprocessWikilinks(root, (raw) => resolveWikilink(raw, REFS, ARCHIVED_REFS))
    // Outside text → replaced into <a>
    const a = root.querySelector('p a.wiki-link')
    expect(a).not.toBeNull()
    // Inside <pre><code> → still literal
    const code = root.querySelector('pre code') as HTMLElement
    expect(code.textContent).toContain('[[machine-learning-basics]]')
    expect(code.querySelector('a')).toBeNull()
  })

  it('skips inline <code>', () => {
    const root = setup('<p>This is <code>[[inline]]</code> example.</p>')
    postprocessWikilinks(root, (raw) => resolveWikilink(raw, REFS, ARCHIVED_REFS))
    const code = root.querySelector('code') as HTMLElement
    expect(code.textContent).toBe('[[inline]]')
    expect(code.querySelector('a')).toBeNull()
    expect(code.querySelector('span')).toBeNull()
  })

  it('handles multiple wikilinks in one paragraph', () => {
    const root = setup(
      '<p>See [[machine-learning-basics]] and [[transformer-architecture]] and [[unknown-x]].</p>',
    )
    postprocessWikilinks(root, (raw) => resolveWikilink(raw, REFS, ARCHIVED_REFS))
    expect(root.querySelectorAll('a.wiki-link').length).toBe(2)
    expect(root.querySelectorAll('span.wiki-link-broken').length).toBe(1)
  })

  it('is idempotent — running twice does not double-wrap', () => {
    const root = setup('<p>See [[machine-learning-basics]] first.</p>')
    const resolver = (raw: string) => resolveWikilink(raw, REFS, ARCHIVED_REFS)
    postprocessWikilinks(root, resolver)
    const firstHtml = root.innerHTML
    postprocessWikilinks(root, resolver)
    expect(root.innerHTML).toBe(firstHtml)
    expect(root.querySelectorAll('a.wiki-link').length).toBe(1)
  })
})
