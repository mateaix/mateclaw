// @vitest-environment happy-dom
import { createApp, defineComponent } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import AgentContext from '../AgentContext.vue'
import { agentApi, agentContextApi } from '@/api/index'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
}))

vi.mock('@/api/index', () => ({
  agentApi: {
    list: vi.fn(),
  },
  agentContextApi: {
    listFiles: vi.fn(),
    getFile: vi.fn(),
    saveFile: vi.fn(),
    deleteFile: vi.fn(),
    listPersonalFiles: vi.fn(),
    getPersonalFile: vi.fn(),
    getPromptFiles: vi.fn(),
    setPromptFiles: vi.fn(),
    exportMemorySnapshot: vi.fn(),
    previewImportMemorySnapshot: vi.fn(),
    applyImportMemorySnapshot: vi.fn(),
  },
}))

vi.mock('@/composables/useMcToast', () => ({
  mcToast: {
    error: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
  },
}))

vi.mock('@/components/common/useConfirm', () => ({
  mcConfirm: vi.fn(),
}))

vi.mock('@/utils/clipboard', () => ({
  copyToClipboard: vi.fn(),
}))

vi.mock('@/composables/useMermaidRenderer', () => ({
  handleMermaidDownload: vi.fn(() => false),
}))

vi.mock('@/composables/useMarkdownRenderer', () => ({
  useMarkdownRenderer: () => ({
    renderMarkdown: (value: string) => `<p>${value}</p>`,
  }),
}))

vi.mock('@/components/common/AgentPickerDialog.vue', () => ({
  default: defineComponent({
    name: 'AgentPickerDialogStub',
    props: {
      modelValue: [String, Number],
      agents: Array,
    },
    emits: ['update:modelValue'],
    template: '<div class="agent-picker-stub"></div>',
  }),
}))

const apps: Array<ReturnType<typeof createApp>> = []

function mountAgentContext() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(AgentContext)
  app.use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': {} } }))
  app.mount(host)
  apps.push(app)
  return host
}

async function eventually(assertion: () => void) {
  let lastError: unknown
  for (let i = 0; i < 20; i += 1) {
    await Promise.resolve()
    await new Promise(resolve => setTimeout(resolve, 0))
    try {
      assertion()
      return
    } catch (error) {
      lastError = error
    }
  }
  throw lastError
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(next => { resolve = next })
  return { promise, resolve }
}

describe('AgentContext previews', () => {
  beforeEach(() => {
    vi.mocked(agentApi.list).mockResolvedValue({
      data: [{ id: 'agent-1', name: '会议管理' }],
    } as never)
    vi.mocked(agentContextApi.getPromptFiles).mockResolvedValue({ data: ['AGENTS.md'] } as never)
    vi.mocked(agentContextApi.listPersonalFiles).mockResolvedValue({ data: [] } as never)
  })

  afterEach(() => {
    apps.splice(0).forEach(app => app.unmount())
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('reloads the selected file body when refreshing so split and preview modes show latest content', async () => {
    let version = 1
    vi.mocked(agentContextApi.listFiles).mockImplementation(async () => ({
      data: [{
        id: 'file-1',
        agentId: 'agent-1',
        filename: 'AGENTS.md',
        fileSize: version === 1 ? 8 : 14,
        enabled: true,
        sortOrder: 0,
        createTime: '2026-08-14T10:00:00',
        updateTime: version === 1 ? '2026-08-14T10:00:00' : '2026-08-14T10:01:00',
      }],
    }) as never)
    vi.mocked(agentContextApi.getFile).mockImplementation(async () => ({
      data: { content: version === 1 ? 'old content' : 'latest content' },
    }) as never)

    const host = mountAgentContext()
    await eventually(() => {
      expect(host.querySelector('.file-item')).not.toBeNull()
    })

    host.querySelector<HTMLElement>('.file-item')!.click()
    await eventually(() => {
      expect(host.querySelector<HTMLTextAreaElement>('.editor-textarea')?.value).toBe('old content')
    })
    host.querySelectorAll<HTMLButtonElement>('.preview-mode-btn')[2].click()
    await eventually(() => {
      expect(host.textContent).toContain('old content')
    })

    version = 2
    host.querySelectorAll<HTMLButtonElement>('.panel-actions .icon-btn')[3].click()

    await eventually(() => {
      expect(host.textContent).toContain('latest content')
    })
    expect(host.textContent).not.toContain('old content')
  })

  it('ignores stale file loads when the user selects another file before the first request resolves', async () => {
    const agentsLoaded = deferred<void>()
    vi.mocked(agentApi.list).mockImplementation(async () => {
      agentsLoaded.resolve()
      return { data: [{ id: 'agent-1', name: '会议管理' }] } as never
    })
    vi.mocked(agentContextApi.listFiles).mockResolvedValue({
      data: [
        {
          id: 'file-1',
          agentId: 'agent-1',
          filename: 'AGENTS.md',
          fileSize: 8,
          enabled: true,
          sortOrder: 0,
          createTime: '2026-08-14T10:00:00',
          updateTime: '2026-08-14T10:00:00',
        },
        {
          id: 'file-2',
          agentId: 'agent-1',
          filename: 'MEMORY.md',
          fileSize: 14,
          enabled: true,
          sortOrder: 1,
          createTime: '2026-08-14T10:00:00',
          updateTime: '2026-08-14T10:01:00',
        },
      ],
    } as never)

    const agentsFileLoaded = deferred<{ data: { content: string } }>()
    vi.mocked(agentContextApi.getFile).mockImplementation((_, filename) => {
      if (filename === 'AGENTS.md') {
        return agentsFileLoaded.promise as never
      }
      return Promise.resolve({ data: { content: 'memory latest' } }) as never
    })

    const host = mountAgentContext()
    await agentsLoaded.promise
    await eventually(() => {
      expect(host.querySelectorAll('.file-item')).toHaveLength(2)
    })

    const fileItems = host.querySelectorAll<HTMLElement>('.file-item')
    fileItems[0].click()
    fileItems[1].click()

    await eventually(() => {
      expect(host.querySelector<HTMLTextAreaElement>('.editor-textarea')?.value).toBe('memory latest')
    })

    agentsFileLoaded.resolve({ data: { content: 'agents stale' } })

    await eventually(() => {
      expect(host.querySelector<HTMLTextAreaElement>('.editor-textarea')?.value).toBe('memory latest')
    })
  })
})
