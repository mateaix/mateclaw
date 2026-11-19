import { computed, createApp, defineComponent, h } from 'vue'
import { createI18n } from 'vue-i18n'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Message } from '@/types'
import zhCN from '@/i18n/locales/zh-CN'
import enUS from '@/i18n/locales/en-US'

vi.mock('@/composables/useStreamingMarkdown', () => ({
  useStreamingMarkdown: (content: () => string) => ({
    html: computed(content),
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
import { http, fetchAuthenticatedBlob } from '@/api'
import { mcToast } from '@/composables/useMcToast'

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
          incompleteTitle: zhCN.chat.incompleteTitle,
          incompleteDescription: zhCN.chat.incompleteDescription,
          iterationLimitTitle: zhCN.chat.iterationLimitTitle,
          iterationLimitDescription: zhCN.chat.iterationLimitDescription,
          stopped: '已被用户手动中止',
          interrupted: '已中断并继续处理下一条消息',
        },
      },
      en: {
        chat: {
          incompleteTitle: enUS.chat.incompleteTitle,
          incompleteDescription: enUS.chat.incompleteDescription,
          iterationLimitTitle: enUS.chat.iterationLimitTitle,
          iterationLimitDescription: enUS.chat.iterationLimitDescription,
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


describe('MessageBubble TTS (#646)', () => {
  function clickReadAloud() {
    const host = mountMessage({ id: 'tts', conversationId: 'conv', role: 'assistant',
      content: 'Hello', contentParts: [], status: 'completed' } as Message)
    const button = host.querySelector<HTMLButtonElement>('[title="chat.ttsPlay"]')!
    expect(button).not.toBeNull()
    button.click()
    return button
  }

  it('plays the unwrapped synthesis response and releases audio on stop', async () => {
    vi.mocked(http.post).mockResolvedValue({ success: true, audioUrl: '/api/v1/chat/files/conv/tts.mp3' } as never)
    vi.mocked(fetchAuthenticatedBlob).mockResolvedValue(new Blob(['audio']))
    const play = vi.fn().mockResolvedValue(undefined)
    const pause = vi.fn()
    vi.stubGlobal('Audio', class { play = play; pause = pause })
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:tts')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const button = clickReadAloud()
    await vi.waitFor(() => expect(play).toHaveBeenCalledOnce())
    expect(fetchAuthenticatedBlob).toHaveBeenCalledWith('/api/v1/chat/files/conv/tts.mp3')
    button.click()
    expect(pause).toHaveBeenCalledOnce()
    expect(revoke).toHaveBeenCalledWith('blob:tts')
  })

  it('shows backend synthesis errors instead of silently doing nothing', async () => {
    vi.mocked(http.post).mockResolvedValue({ success: false, error: 'DashScope: invalid voice' } as never)
    clickReadAloud()
    await vi.waitFor(() => expect(mcToast.error).toHaveBeenCalledWith('DashScope: invalid voice'))
  })

  it('shows audio download failures and resets the loading state', async () => {
    vi.mocked(http.post).mockResolvedValue({ success: true, audioUrl: '/audio' } as never)
    vi.mocked(fetchAuthenticatedBlob).mockRejectedValue(new Error('Fetch failed: 403'))
    const button = clickReadAloud()
    await vi.waitFor(() => expect(mcToast.error).toHaveBeenCalledWith('Fetch failed: 403'))
    expect(button.disabled).toBe(false)
  })
  it('releases the audio URL when playback is rejected', async () => {
    vi.mocked(http.post).mockResolvedValue({ success: true, audioUrl: '/audio' } as never)
    vi.mocked(fetchAuthenticatedBlob).mockResolvedValue(new Blob(['audio']))
    const pause = vi.fn()
    vi.stubGlobal('Audio', class { pause = pause; play = vi.fn().mockRejectedValue(new Error('Playback blocked')) })
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:rejected')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    clickReadAloud()
    await vi.waitFor(() => expect(mcToast.error).toHaveBeenCalledWith('Playback blocked'))
    expect(pause).toHaveBeenCalledOnce()
    expect(revoke).toHaveBeenCalledWith('blob:rejected')
  })

  it('does not start playback when the component unmounts during synthesis', async () => {
    let finish!: (value: any) => void
    vi.mocked(http.post).mockReturnValue(new Promise(resolve => { finish = resolve }))
    clickReadAloud()
    apps.pop()!.unmount()
    finish({ success: true, audioUrl: '/audio' })
    await Promise.resolve()
    await Promise.resolve()
    expect(fetchAuthenticatedBlob).not.toHaveBeenCalled()
  })

})


describe('MessageBubble incomplete answer (#660)', () => {
  it.each(['zh-CN', 'en'])('does not diagnose token-budget exhaustion as repetition (%s)', locale => {
    const host = mountMessage({ id: 'budget', conversationId: 'conv', role: 'assistant',
      content: '模型在输出最终答案前已耗尽输出 token 预算', contentParts: [],
      status: 'completed', metadata: { finishReason: 'incomplete' } } as Message, locale)
    const title = host.querySelector('.incomplete-card__title')?.textContent || ''
    expect(title).not.toMatch(/重复|repeated|repetition/i)
    expect(host.textContent).toContain('token')
    expect(host.querySelector('.incomplete-card__retry')).not.toBeNull()
  })
})

describe('MessageBubble iteration limit (#674)', () => {
  it.each(['zh-CN', 'en'])('explains the incomplete task and recovery options (%s)', locale => {
    const host = mountMessage({ id: 'limit', conversationId: 'conv', role: 'assistant',
      content: 'Partial answer', contentParts: [], status: 'completed',
      metadata: { finishReason: 'max_iterations_reached' } } as Message, locale)

    const card = host.querySelector('.iteration-limit-card')
    expect(card).not.toBeNull()
    expect(card!.textContent).toContain(locale === 'zh-CN' ? '任务尚未完成' : 'Task is incomplete')
    expect(card!.textContent).toContain(locale === 'zh-CN' ? '最大迭代次数' : 'Max Iterations')
    expect(host.textContent).toContain('Partial answer')
    expect(host.querySelector('.incomplete-card__retry')).toBeNull()
  })

  it('renders the limit notice for segmented history with serialized metadata', () => {
    const host = mountMessage({ id: 'history-limit', conversationId: 'conv', role: 'assistant',
      content: '', contentParts: [], status: 'completed', metadata: JSON.stringify({
        finishReason: 'max_iterations_reached',
        segments: [
          { id: 'tool', type: 'tool_call', toolName: 'search', status: 'completed', seq: 0 },
          { id: 'content', type: 'content', text: 'Saved partial answer', status: 'completed', seq: 1 },
        ],
      }) } as Message)

    expect(host.querySelector('.segments-view')).not.toBeNull()
    expect(host.querySelector('.iteration-limit-card')).not.toBeNull()
    expect(host.textContent).toContain('Saved partial answer')
  })

  it.each(['normal', 'incomplete', 'evidence_insufficient'])('does not show an iteration warning for %s', finishReason => {
    const host = mountMessage({ id: 'other', conversationId: 'conv', role: 'assistant',
      content: 'Answer', contentParts: [], status: 'completed', metadata: { finishReason } } as Message)
    expect(host.querySelector('.iteration-limit-card')).toBeNull()
  })

  it('suppresses an intermediate limit notice while the goal is continuing', () => {
    const host = mountMessage({ id: 'continuing', conversationId: 'conv', role: 'assistant',
      content: 'Working', contentParts: [], status: 'generating',
      metadata: { finishReason: 'max_iterations_reached' } } as Message)
    expect(host.querySelector('.iteration-limit-card')).toBeNull()
  })
})
