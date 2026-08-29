import { createApp, defineComponent, h } from 'vue'
import { createI18n } from 'vue-i18n'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Message } from '@/types'

vi.mock('@/composables/useStreamingMarkdown', () => ({
  useStreamingMarkdown: (content: unknown) => ({
    renderedContent: content,
    copyCode: vi.fn(),
  }),
}))
vi.mock('@/composables/useAuthenticatedAttachment', () => ({
  useAuthenticatedAttachment: () => ({
    blobUrls: {},
    loadAllImages: vi.fn(),
    loadAllVideos: vi.fn(),
    loadAllAudios: vi.fn(),
    loadAllModels: vi.fn(),
    downloadFile: vi.fn(),
    openImage: vi.fn(),
    getDisplayUrl: vi.fn((url: string) => url),
    revokeAll: vi.fn(),
  }),
}))
vi.mock('@/composables/useMcToast', () => ({
  mcToast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))
vi.mock('@/composables/useToolLabel', () => ({
  useToolLabel: () => ({ getToolLabel: (name: string) => name }),
}))
vi.mock('@/api', () => ({ http: { get: vi.fn(), post: vi.fn() } }))
vi.mock('@/utils/clipboard', () => ({ copyToClipboard: vi.fn() }))
vi.mock('@/utils/generatedFileLinks', () => ({
  buildGeneratedFileNameMap: vi.fn(() => new Map()),
  linkifyGeneratedFileUrls: vi.fn((content: string) => content),
}))
vi.mock('@/utils/lazyModelViewer', () => ({ ensureModelViewer: vi.fn() }))
vi.mock('../preview/previewKind', () => ({ previewKindOf: vi.fn(() => null) }))
vi.mock('../preview/previewBus', () => ({ openFilePreview: vi.fn() }))
vi.mock('@/components/goal/GoalAvatarRing.vue', () => ({
  default: defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }),
}))
vi.mock('../ToolCallSegment.vue', () => ({
  default: defineComponent({ props: ['segment'], setup: props => () => h('div', props.segment.toolName) }),
}))
vi.mock('../ThinkingSegment.vue', () => ({
  default: defineComponent({ props: ['segment'], setup: props => () => h('div', props.segment.thinkingText) }),
}))
vi.mock('../ContentSegment.vue', () => ({
  default: defineComponent({ props: ['segment'], setup: props => () => h('div', props.segment.text) }),
}))
vi.mock('../PlanStepsPanel.vue', () => ({ default: defineComponent({ setup: () => () => null }) }))
vi.mock('../BrowserTimeline.vue', () => ({ default: defineComponent({ setup: () => () => null }) }))
vi.mock('../TypingCursor.vue', () => ({ default: defineComponent({ setup: () => () => null }) }))
vi.mock('../UserMessageContent.vue', () => ({
  default: defineComponent({ props: ['content'], setup: props => () => h('div', props.content) }),
}))

import MessageBubble from '../MessageBubble.vue'

const apps: Array<ReturnType<typeof createApp>> = []

function mountMessage(message: Message, locale = 'zh-CN') {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(MessageBubble, { message })
  app.use(createPinia())
  app.use(createI18n({
    legacy: false,
    locale,
    messages: {
      'zh-CN': {
        chat: {
          stopped: '已被用户手动中止',
          interrupted: '已中断并继续处理下一条消息',
        },
      },
      en: {
        chat: {
          stopped: 'Stopped manually by user',
          interrupted: 'Interrupted and continued with the next message',
        },
      },
    },
  }))
  app.mount(host)
  apps.push(app)
  return host
}

afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
})

describe('MessageBubble stop indicator', () => {
  it('shows a manual-stop status bar for segmented stopped assistant messages', () => {
    const host = mountMessage({
      id: '2',
      conversationId: 'conv',
      role: 'assistant',
      content: '',
      contentParts: [],
      status: 'stopped',
      metadata: {
        segments: [
          { id: 'think-1', type: 'thinking', status: 'completed', thinkingText: '分析中', seq: 0 },
          { id: 'tool-1', type: 'tool_call', status: 'completed', toolName: 'shell', seq: 1 },
        ],
      },
    })

    expect(host.querySelector('.segments-view')).not.toBeNull()
    expect(host.textContent).toContain('已被用户手动中止')
    expect(host.querySelector('.stopped-indicator--stopped')).not.toBeNull()
  })

  it('distinguishes interrupted turns from user-stopped turns', () => {
    const host = mountMessage({
      id: '3',
      conversationId: 'conv',
      role: 'assistant',
      content: 'partial',
      contentParts: [],
      status: 'interrupted',
    })

    expect(host.textContent).toContain('已中断并继续处理下一条消息')
    expect(host.querySelector('.stopped-indicator--interrupted')).not.toBeNull()
  })
})
