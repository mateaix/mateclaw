// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import messageBubbleSource from '../MessageBubble.vue?raw'

describe('MessageBubble superseded content display', () => {
  it('直接显示工具前预写内容，不渲染折叠提示或隐藏正文', () => {
    const source = messageBubbleSource

    expect(source).not.toContain('supersededPreviewCollapsed')
    expect(source).not.toContain('supersededPreviewExpanded')
    expect(source).not.toContain('toggleSupersededSegment')
    expect(source).not.toContain("v-if=\"!seg.superseded || isSupersededExpanded(seg.id)\"")
    expect(source).not.toContain('content-segment--superseded')
  })
})
