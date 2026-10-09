import { createApp, defineComponent, h, reactive, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Message } from '@/types'

vi.mock('/logo/mateclaw_logo_s.png', () => ({ default: '/logo/mateclaw_logo_s.png' }))
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
vi.mock('@/api', () => ({ http: { get: vi.fn(), post: vi.fn() }, fetchAuthenticatedBlob: vi.fn() }))
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
import { http, fetchAuthenticatedBlob } from '@/api'
import { mcToast } from '@/composables/useMcToast'

const apps: Array<ReturnType<typeof createApp>> = []

function mountMessage(message: Message, locale = 'zh-CN') {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(MessageBubble, { message })
  app.use(createPinia())
  for (const name of ['el-icon', 'el-popover', 'model-viewer']) app.component(name, { template: '<span><slot /></span>' })
  app.use(createI18n({
    legacy: false,
    missingWarn: false,
    fallbackWarn: false,
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
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('structured tool history', () => {
  it.each(['segments', 'toolCalls'])('renders saved %s data as the same visible table', (source) => {
    const structuredContent = { mateclawUi: { version: 1, blocks: [{ type: 'table', data: { columns: [{ key: 'a', label: 'A' }], rows: [{ a: 'Saved result' }] } }] } }
    const metadata = source === 'segments'
      ? { segments: [{ id: 'tool', type: 'tool_call', status: 'completed', toolName: 'search', structuredContent }] }
      : { toolCalls: [{ name: 'search', status: 'completed', success: true, structuredContent }] }
    const host = mountMessage({ id: 'saved', conversationId: 'conv', role: 'assistant', content: '', contentParts: [], status: 'completed', metadata } as Message)
    expect(host.querySelector('td')?.textContent).toBe('Saved result')
    expect(host.querySelector('.seg-tool__body')).toBeNull()
    expect(host.querySelector('.seg-tool .tool-result-view')).toBeNull()
    expect(host.querySelector('.answer-tool-results td')?.textContent).toBe('Saved result')
  })
})

const richResult = { mateclawUi: { version: 1, blocks: [{ type: 'table', data: {
  columns: [{ key: 'a', label: 'A' }], rows: [{ a: 'Quarterly sales' }],
} }] } }
const successfulTool = (id: string) => ({ id, toolCallId: id, type: 'tool_call', status: 'completed',
  toolName: 'sales', toolSuccess: true, structuredContent: richResult })
const answerMessage = (status = 'completed', metadata: unknown = {}) => ({
  id: 'answer', conversationId: 'conv', role: 'assistant', content: 'Sales grew 50%',
  contentParts: [], status, metadata,
}) as Message

const weatherResult = {
  success: true,
  result: { city: '广州', weather: '阵雨', temperature: 28, humidity: 85 },
  error: null,
}

it('does not append ordinary MCP JSON when a live turn completes, while retaining tool details', async () => {
  const result = JSON.stringify(weatherResult)
  const tool = { ...successfulTool('weather'), toolName: 'get_weather',
    toolResult: result, structuredContent: weatherResult }
  const message = reactive(answerMessage('generating', { segments: [tool,
    { id: 'text', type: 'content', status: 'running', text: '广州有阵雨，28°C。' }],
  }))
  const host = mountMessage(message)
  message.status = 'completed'
  await nextTick()
  expect(host.querySelector('.segments-view')?.textContent).toContain('广州有阵雨，28°C。')
  expect(host.querySelector('.answer-tool-results')).toBeNull()
  host.querySelector<HTMLElement>('.seg-tool__header')!.click()
  await nextTick()
  expect(host.querySelector('.seg-tool pre')?.textContent).toBe(result)
})

it.each(['segments', 'toolCalls'])('does not append ordinary MCP JSON from saved %s alongside rich output', (source) => {
  const metadata = source === 'segments'
    ? { segments: [{ ...successfulTool('weather'), structuredContent: weatherResult }, successfulTool('rich')] }
    : { toolCalls: [
      { name: 'get_weather', toolCallId: 'weather', status: 'completed', success: true, structuredContent: weatherResult },
      { name: 'sales', toolCallId: 'rich', status: 'completed', success: true, structuredContent: richResult },
    ] }
  const host = mountMessage(answerMessage('completed', JSON.stringify(metadata)))
  expect(host.querySelectorAll('.answer-tool-results .tool-result-view')).toHaveLength(1)
  expect(host.querySelector('.answer-tool-results td')?.textContent).toBe('Quarterly sales')
  expect(host.querySelector('.answer-tool-results pre')).toBeNull()
})

it('shows rich results once after the final content when a live turn completes', async () => {
  const message = reactive(answerMessage('generating', { segments: [successfulTool('a'),
    { id: 'text', type: 'content', status: 'running', text: 'Sales grew 50%' }],
    toolCalls: [{ name: 'sales', toolCallId: 'a', status: 'completed', structuredContent: richResult }],
  }))
  const host = mountMessage(message)
  expect(host.querySelector('.tool-result-view')).toBeNull()
  message.status = 'completed'
  await nextTick()
  expect(host.querySelectorAll('.tool-result-view')).toHaveLength(1)
  const results = host.querySelector('.answer-tool-results')!
  const timeline = host.querySelector('.segments-view')!
  expect(timeline.textContent).toContain('Sales grew 50%')
  expect(timeline.compareDocumentPosition(results) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(host.querySelector('.seg-tool .tool-result-view')).toBeNull()
})

it.each(['completed', 'stopped', 'interrupted', 'failed'])('retains successful output for %s turns', (status) => {
  const metadata = { segments: [successfulTool('a'),
    { ...successfulTool('b'), status: 'error', toolSuccess: false },
    { ...successfulTool('c'), status: 'running' }],
  }
  const host = mountMessage(answerMessage(status, JSON.stringify(metadata)))
  expect(host.querySelectorAll('.answer-tool-results table')).toHaveLength(1)
})

it('deduplicates repeated IDs but preserves separate calls of the same tool', () => {
  const host = mountMessage(answerMessage('completed', { segments: [
    successfulTool('a'), successfulTool('a'), successfulTool('b'),
  ] }))
  expect(host.querySelectorAll('.answer-tool-results table')).toHaveLength(2)
})

it('does not promote unfinished or failed legacy toolCalls to successful results', () => {
  const host = mountMessage(answerMessage('stopped', { toolCalls: [
    { name: 'running', status: 'running', structuredContent: richResult },
    { name: 'approval', status: 'awaiting_approval', structuredContent: richResult },
    { name: 'failed', status: 'completed', success: false, structuredContent: richResult },
    { name: 'done', status: 'completed', success: true, structuredContent: richResult },
  ] }))
  expect(host.querySelectorAll('.answer-tool-results table')).toHaveLength(1)
})
