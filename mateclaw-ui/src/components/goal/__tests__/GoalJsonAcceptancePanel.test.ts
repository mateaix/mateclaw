import { createApp, h, nextTick, reactive } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import GoalJsonAcceptancePanel from '../GoalJsonAcceptancePanel.vue'
import { goalJsonAcceptanceApi } from '@/api/goalJsonAcceptance'
import en from '@/i18n/locales/en-US'
vi.mock('@/api/goalJsonAcceptance', () => ({ goalJsonAcceptanceApi: { get: vi.fn(), configure: vi.fn() } }))
const apps: ReturnType<typeof createApp>[] = []
const id = '9223372036854775801'
const requirement = (revision = '1') => ({ criterionKey: 'report-fields', artifactSlot: 'report', revision, requiredFields: ['summary'], configuredBy: 'alice' })
async function flush() { await Promise.resolve(); await Promise.resolve(); await nextTick() }
function mount(status = 'active') {
  const props = reactive({ goalId: id, status })
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp({ render: () => h(GoalJsonAcceptancePanel, props) })
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en } })); app.mount(host); apps.push(app)
  return { host, props }
}
async function open(host: HTMLElement) { host.querySelector<HTMLButtonElement>('[data-json-acceptance-toggle]')!.click(); await flush() }
async function fill(host: HTMLElement, selector: string, value: string) {
  const field = host.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!
  field.value = value; field.dispatchEvent(new Event('input')); await flush()
}
async function submit(host: HTMLElement) { host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush() }
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); document.body.innerHTML = ''; vi.resetAllMocks() })
describe('user JSON acceptance requirements', () => {
  it('loads without writing and requires an explicit save to opt in', async () => {
    vi.mocked(goalJsonAcceptanceApi.get).mockResolvedValue({ data: { required: false, status: 'active', requirements: [] } })
    vi.mocked(goalJsonAcceptanceApi.configure).mockResolvedValue({ data: requirement() })
    const { host } = mount(); expect(goalJsonAcceptanceApi.get).not.toHaveBeenCalled(); await open(host)
    expect(goalJsonAcceptanceApi.configure).not.toHaveBeenCalled()
    expect(host.textContent).toContain('cannot be switched off')
    await fill(host, '[data-json-requirement-key]', 'report-fields')
    await fill(host, '[data-json-requirement-slot]', 'report')
    await fill(host, '[data-json-requirement-fields]', 'summary\nsources')
    expect(goalJsonAcceptanceApi.configure).not.toHaveBeenCalled(); await submit(host)
    expect(goalJsonAcceptanceApi.configure).toHaveBeenCalledWith(id, 'report-fields', { expectedRevision: '0', artifactSlot: 'report', requiredFields: ['summary', 'sources'] })
    expect(host.querySelector('[data-json-acceptance-mode]')?.textContent).toContain('required before')
  })
  it('preserves opaque revisions and refuses to resubmit a stale edit before reload', async () => {
    const revision = '9223372036854775802'
    vi.mocked(goalJsonAcceptanceApi.get).mockResolvedValue({ data: { required: true, status: 'active', requirements: [requirement(revision)] } })
    vi.mocked(goalJsonAcceptanceApi.configure).mockRejectedValue({ code: 409 })
    const { host } = mount(); await open(host)
    host.querySelector<HTMLButtonElement>('[data-json-requirement-edit]')!.click(); await flush()
    await fill(host, '[data-json-requirement-fields]', 'appendix'); await submit(host)
    expect(goalJsonAcceptanceApi.configure).toHaveBeenCalledWith(id, 'report-fields', { expectedRevision: revision, artifactSlot: 'report', requiredFields: ['appendix'] })
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('Reload before saving')
    expect(host.querySelector<HTMLButtonElement>('[data-json-requirement-save]')!.disabled).toBe(true)
    await submit(host); expect(goalJsonAcceptanceApi.configure).toHaveBeenCalledTimes(1)
  })
  it('reloads a server-completed goal as read-only even while the parent status is stale', async () => {
    vi.mocked(goalJsonAcceptanceApi.get)
      .mockResolvedValueOnce({ data: { required: true, status: 'active', requirements: [requirement()] } })
      .mockResolvedValueOnce({ data: { required: true, status: 'completed', requirements: [requirement()] } })
    vi.mocked(goalJsonAcceptanceApi.configure).mockRejectedValue({ code: 409 })
    const { host, props } = mount(); await open(host)
    host.querySelector<HTMLButtonElement>('[data-json-requirement-edit]')!.click(); await flush(); await submit(host)
    host.querySelector<HTMLButtonElement>('[data-json-acceptance-refresh]')!.click(); await flush()
    expect(props.status).toBe('active')
    expect(host.querySelectorAll('[data-json-requirement]')).toHaveLength(1)
    expect(host.querySelector('form')).toBeNull()
    expect(host.querySelector('[data-json-requirement-edit]')).toBeNull()
    expect(host.querySelector('[data-json-acceptance-status]')?.textContent).toContain('Completed')
    expect(goalJsonAcceptanceApi.configure).toHaveBeenCalledTimes(1)
  })
  it('clears old requirements and drafts after access is revoked', async () => {
    vi.mocked(goalJsonAcceptanceApi.get).mockResolvedValue({ data: { required: true, status: 'active', requirements: [requirement()] } })
    vi.mocked(goalJsonAcceptanceApi.configure).mockRejectedValue({ code: 403 })
    const { host } = mount(); await open(host)
    host.querySelector<HTMLButtonElement>('[data-json-requirement-edit]')!.click(); await flush(); await submit(host)
    expect(host.querySelectorAll('[data-json-requirement]')).toHaveLength(0)
    expect(host.querySelector('form')).toBeNull()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('Access')
  })
  it('does not show an old goal read response after selection changes', async () => {
    let resolve!: (value: unknown) => void
    vi.mocked(goalJsonAcceptanceApi.get).mockImplementationOnce(() => new Promise(r => { resolve = r }) as never)
      .mockResolvedValueOnce({ data: { required: false, status: 'active', requirements: [] } })
    const { host, props } = mount(); await open(host); props.goalId = 'new'; await flush()
    resolve({ data: { required: true, status: 'active', requirements: [requirement()] } }); await flush()
    expect(host.querySelectorAll('[data-json-requirement]')).toHaveLength(0)
    expect(host.querySelector('[data-json-acceptance-mode]')?.textContent).toContain('has not selected')
  })
  it('does not apply a delayed save to a different goal', async () => {
    let resolve!: (value: unknown) => void
    vi.mocked(goalJsonAcceptanceApi.get).mockResolvedValueOnce({ data: { required: true, status: 'active', requirements: [requirement()] } })
      .mockResolvedValueOnce({ data: { required: false, status: 'active', requirements: [] } })
    vi.mocked(goalJsonAcceptanceApi.configure).mockImplementationOnce(() => new Promise(r => { resolve = r }) as never)
    const { host, props } = mount(); await open(host)
    host.querySelector<HTMLButtonElement>('[data-json-requirement-edit]')!.click(); await flush(); await submit(host)
    props.goalId = 'new'; await flush(); resolve({ data: requirement('2') }); await flush()
    expect(host.querySelectorAll('[data-json-requirement]')).toHaveLength(0)
    expect(host.querySelector('[data-json-acceptance-mode]')?.textContent).toContain('has not selected')
  })
  it('does not submit duplicate fields and presents terminal goals as read-only', async () => {
    vi.mocked(goalJsonAcceptanceApi.get).mockResolvedValue({ data: { required: true, status: 'active', requirements: [requirement()] } })
    const { host, props } = mount(); await open(host)
    host.querySelector<HTMLButtonElement>('[data-json-requirement-edit]')!.click(); await flush()
    await fill(host, '[data-json-requirement-fields]', 'summary\nsummary'); await submit(host)
    expect(goalJsonAcceptanceApi.configure).not.toHaveBeenCalled()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('unique')
    props.status = 'completed'; await flush(); expect(host.querySelector('form')).toBeNull()
    expect(host.querySelector('[data-json-requirement-edit]')).toBeNull()
  })
})
