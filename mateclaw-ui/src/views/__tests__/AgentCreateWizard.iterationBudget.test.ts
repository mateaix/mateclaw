import { createApp, defineComponent, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, expect, it, vi } from 'vitest'
import zhCN from '@/i18n/locales/zh-CN'
import { agentApi, agentBindingApi } from '@/api'
import AgentCreateWizard from '../AgentCreateWizard.vue'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/api', () => ({
  agentApi: { generate: vi.fn(), create: vi.fn() },
  agentBindingApi: { setSkills: vi.fn(), setTools: vi.fn() },
  toolApi: { listAvailable: vi.fn().mockResolvedValue({ data: [] }) },
  skillApi: { page: vi.fn().mockResolvedValue({ data: [] }) },
  wikiApi: { listBindableKBs: vi.fn().mockResolvedValue({ data: [] }) },
}))
vi.mock('@/composables/useMcToast', () => ({ mcToast: { error: vi.fn(), success: vi.fn() } }))
vi.mock('@/components/common/SkillIcon.vue', () => ({ default: defineComponent({ template: '<span />' }) }))
vi.mock('@/components/common/SkillIconPicker.vue', () => ({ default: defineComponent({ template: '<span />' }) }))
vi.mock('@/components/agent/WizardCapabilityPicker.vue', () => ({ default: defineComponent({ template: '<span />' }) }))

let app: ReturnType<typeof createApp> | undefined
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

it('creates the generated agent with a 100-round budget so complex tasks can exceed ten rounds', async () => {
  vi.mocked(agentApi.generate).mockResolvedValue({ data: { name: 'Research assistant' } } as never)
  vi.mocked(agentApi.create).mockResolvedValue({ data: { id: '123', name: 'Research assistant' } } as never)
  vi.mocked(agentBindingApi.setSkills).mockResolvedValue({} as never)
  vi.mocked(agentBindingApi.setTools).mockResolvedValue({} as never)
  const host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp(AgentCreateWizard)
  app.use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhCN } }))
  app.mount(host)

  const input = host.querySelector('textarea')!
  input.value = 'Research a complex task'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  host.querySelector<HTMLButtonElement>('.wiz-btn-primary')!.click()
  await vi.waitFor(() => expect(host.querySelector('.wiz-notice')).not.toBeNull())
  host.querySelector<HTMLButtonElement>('.wiz-actions .wiz-btn-primary')!.click()

  await vi.waitFor(() => expect(agentApi.create).toHaveBeenCalledWith(expect.objectContaining({
    name: 'Research assistant', maxIterations: 100,
  })))
})
