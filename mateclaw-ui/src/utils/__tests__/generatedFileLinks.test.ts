import { describe, expect, it } from 'vitest'
import { buildGeneratedFileNameMap, linkifyGeneratedFileUrls } from '../generatedFileLinks'

const FILES = [
  { name: '智能体技术培训_红色版.pptx', url: 'http://localhost:55793/api/v1/files/generated/ac38623b-7ed8-41f5-a80a-1e6761240ae0' },
  { name: 'report.docx', url: '/api/v1/files/generated/11111111-2222-3333-4444-555555555555' },
]

describe('buildGeneratedFileNameMap', () => {
  it('maps ids from absolute and relative urls', () => {
    const map = buildGeneratedFileNameMap(FILES)
    expect(map.get('ac38623b-7ed8-41f5-a80a-1e6761240ae0')).toBe('智能体技术培训_红色版.pptx')
    expect(map.get('11111111-2222-3333-4444-555555555555')).toBe('report.docx')
  })

  it('tolerates junk input', () => {
    expect(buildGeneratedFileNameMap(null).size).toBe(0)
    expect(buildGeneratedFileNameMap([{ name: 'x' }, { url: '/api/v1/files/generated/abc' }]).size).toBe(0)
  })
})

describe('linkifyGeneratedFileUrls', () => {
  const names = buildGeneratedFileNameMap(FILES)

  it('wraps a bare absolute url into [name](url)', () => {
    const text = '下载链接：http://localhost:55793/api/v1/files/generated/ac38623b-7ed8-41f5-a80a-1e6761240ae0（链接 10 分钟内有效）'
    expect(linkifyGeneratedFileUrls(text, names)).toBe(
      '下载链接：[智能体技术培训_红色版.pptx](http://localhost:55793/api/v1/files/generated/ac38623b-7ed8-41f5-a80a-1e6761240ae0)（链接 10 分钟内有效）',
    )
  })

  it('wraps a bare relative url', () => {
    expect(linkifyGeneratedFileUrls('见 /api/v1/files/generated/11111111-2222-3333-4444-555555555555', names))
      .toBe('见 [report.docx](/api/v1/files/generated/11111111-2222-3333-4444-555555555555)')
  })

  it('leaves an existing markdown link untouched', () => {
    const text = '[自定义标题](/api/v1/files/generated/11111111-2222-3333-4444-555555555555)'
    expect(linkifyGeneratedFileUrls(text, names)).toBe(text)
  })

  it('leaves angle-bracket autolinks untouched', () => {
    const text = '<http://localhost:55793/api/v1/files/generated/ac38623b-7ed8-41f5-a80a-1e6761240ae0>'
    expect(linkifyGeneratedFileUrls(text, names)).toBe(text)
  })

  it('leaves unknown ids untouched', () => {
    const text = '/api/v1/files/generated/99999999-0000-0000-0000-000000000000'
    expect(linkifyGeneratedFileUrls(text, names)).toBe(text)
  })

  it('short-circuits when there is nothing to do', () => {
    expect(linkifyGeneratedFileUrls('普通文本', names)).toBe('普通文本')
    expect(linkifyGeneratedFileUrls('有 url /api/v1/files/generated/abc', new Map())).toBe('有 url /api/v1/files/generated/abc')
  })
})
