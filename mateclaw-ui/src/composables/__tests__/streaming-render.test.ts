// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { useMarkdownRenderer } from '../useMarkdownRenderer'

const { renderMarkdown } = useMarkdownRenderer()

// An unlabeled fenced code block. The default (final) render auto-detects the
// language via hljs.highlightAuto — the single most expensive step. The
// streaming render must skip it and emit escaped plain text instead.
const UNLABELED_CODE = `\`\`\`
function add(a, b) {
  return a + b
}
\`\`\``

describe('streaming markdown render mode', () => {
  it('skips code auto-highlight while streaming', () => {
    const streamed = renderMarkdown(UNLABELED_CODE, { streaming: true })
    // No highlight.js token spans in the streamed (cheap) render. The
    // structural `hljs-lines` gutter wrapper is always present, so we assert on
    // the token spans (hljs-keyword / hljs-string / …) specifically.
    expect(streamed).not.toContain('<span class="hljs-')
    // The code still shows, just as escaped plain text inside the gutter list.
    expect(streamed).toContain('function add')
  })

  it('auto-highlights the same block on the final (non-streaming) render', () => {
    const final = renderMarkdown(UNLABELED_CODE, { streaming: false })
    // highlight.js emits token classes once auto-detection runs.
    expect(final).toContain('<span class="hljs-')
  })

  it('respects an explicit language even while streaming', () => {
    const labeled = '```js\nconst x = 1\n```'
    const streamed = renderMarkdown(labeled, { streaming: true })
    // Explicit language uses single-grammar hljs.highlight (cheap), kept on.
    expect(streamed).toContain('<span class="hljs-')
  })

  it('does not serve a streaming render back to a later final render', () => {
    // Same source text rendered streaming-first then final: the final must not
    // be the cached low-fidelity streaming output.
    const md = '```\nlet y = 2\n```'
    const streamed = renderMarkdown(md, { streaming: true })
    const final = renderMarkdown(md, { streaming: false })
    expect(streamed).not.toContain('<span class="hljs-')
    expect(final).toContain('<span class="hljs-')
  })

  it('defers echarts to a placeholder while streaming, real block on final', () => {
    const md = '```echarts\n{"series":[{"type":"bar","data":[1,2,3]}]}\n```'
    const streamed = renderMarkdown(md, { streaming: true })
    expect(streamed).toContain('chart-loading')
    expect(streamed).not.toContain('echarts-block')
    expect(streamed).not.toContain('data-echarts-option')

    const final = renderMarkdown(md, { streaming: false })
    expect(final).toContain('class="echarts-block"')
    expect(final).toContain('data-echarts-option')
  })

  it('defers mermaid to a placeholder while streaming, real block on final', () => {
    const md = '```mermaid\ngraph TD; A-->B;\n```'
    const streamed = renderMarkdown(md, { streaming: true })
    expect(streamed).toContain('chart-loading')
    expect(streamed).not.toContain('mermaid-block')

    const final = renderMarkdown(md, { streaming: false })
    expect(final).toContain('class="mermaid-block"')
    expect(final).toContain('data-mermaid')
  })
})
