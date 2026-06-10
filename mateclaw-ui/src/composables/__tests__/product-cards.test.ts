// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { useMarkdownRenderer } from '../useMarkdownRenderer'

const { renderMarkdown } = useMarkdownRenderer()

// A representative ckjia_shopping_recommend payload, trimmed to the fields the
// SKILL.md contract asks the model to emit inside a ```product-cards fence.
const SAMPLE = `\`\`\`product-cards
[
  {
    "name": "华为畅享 70X 尊享版 256GB 曜金黑",
    "url": "https://union-click.jd.com/jdc?e=abc",
    "imageUrl": "https://img14.360buyimg.com/pop/jfs/t1/xxx.jpg",
    "price": 1699,
    "originalPrice": 1899,
    "lowestPrice": 1619,
    "platformLabel": "京东",
    "shopName": "华为（HUAWEI）",
    "purchaseAdvice": "长续航，预算内首选"
  }
]
\`\`\``

describe('product-cards rendering', () => {
  it('renders a fenced product-cards block as a clickable card grid', () => {
    const html = renderMarkdown(SAMPLE)
    expect(html).toContain('class="product-cards"')
    // Whole card is an anchor to the buy URL.
    expect(html).toContain('<a class="product-card"')
    expect(html).toContain('href="https://union-click.jd.com/jdc?e=abc"')
    expect(html).toContain('target="_blank"')
    // Image survives DOMPurify with lazy-load + no-referrer.
    expect(html).toContain('src="https://img14.360buyimg.com/pop/jfs/t1/xxx.jpg"')
    expect(html).toContain('referrerpolicy="no-referrer"')
    // Price is formatted; original price shows struck-through because it is higher.
    expect(html).toContain('¥1,699')
    expect(html).toContain('product-card__price-was')
    expect(html).toContain('华为畅享 70X 尊享版')
  })

  it('accepts an object wrapping a recommendations array', () => {
    const wrapped = '```product-cards\n{"recommendations":[{"name":"X","url":"https://x.test/p","price":10}]}\n```'
    const html = renderMarkdown(wrapped)
    expect(html).toContain('class="product-cards"')
    expect(html).toContain('¥10')
  })

  it('shows a loading placeholder for incomplete (streaming) JSON', () => {
    const partial = '```product-cards\n[{"name":"half'
    const html = renderMarkdown(partial)
    expect(html).toContain('product-cards--loading')
  })

  it('drops a javascript: url to a non-clickable card', () => {
    const evil = '```product-cards\n[{"name":"bad","url":"javascript:alert(1)"}]\n```'
    const html = renderMarkdown(evil)
    expect(html).toContain('product-card--nolink')
    expect(html).not.toContain('javascript:alert')
  })
})
