import { createApp, h, nextTick, reactive } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ManagedGoalJsonVersions from '../ManagedGoalJsonVersions.vue'
import { goalJsonAcceptanceApi as api, type ManagedJsonSnapshot } from '@/api/goalJsonAcceptance'
import en from '@/i18n/locales/en-US'
vi.mock('@/api/goalJsonAcceptance', () => ({ goalJsonAcceptanceApi: { snapshot: vi.fn(), publish: vi.fn(), check: vi.fn(), version: vi.fn() } }))
const apps: ReturnType<typeof createApp>[] = []
const goalId = '9223372036854775801', revision = '9223372036854775802', generation = '9223372036854775803'
const requirement = { criterionKey: 'report-fields', artifactSlot: 'report', revision, requiredFields: ['summary'], configuredBy: 'alice' }
const artifact = { artifactId: 'version-id', artifactSlot: 'report', generation, sha256: 'a'.repeat(64), byteLength: 16, producerKind: 'user', createdAt: '2026-09-14T14:00:00Z', expiresAt: '2026-09-15T14:00:00Z' }
function state(published = false): ManagedJsonSnapshot {
  return { required: true, status: 'active', versionCount: published ? 1 : 0, requirements: [requirement],
    slots: [{ artifactSlot: 'report', generation: published ? generation : '0', current: published ? artifact : null }],
    checks: [{ criterionKey: requirement.criterionKey, requirementRevision: revision, artifactId: published ? artifact.artifactId : null, generation: published ? generation : null, status: published ? 'UNBOUND' : 'NO_ARTIFACT', acceptanceEligible: false }] }
}
async function flush() { for (let i = 0; i < 8; i++) await Promise.resolve(); await nextTick() }
function mount() {
  const props = reactive({ goalId, status: 'active', requirements: [requirement] })
  const lost = vi.fn(), host = document.createElement('div'); document.body.append(host)
  const app = createApp({ render: () => h(ManagedGoalJsonVersions, { ...props, onAccessLost: lost }) })
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en } })); app.mount(host); apps.push(app)
  return { host, props, lost }
}
async function open(host: HTMLElement) { host.querySelector<HTMLButtonElement>('[data-json-versions-toggle]')!.click(); await flush() }
async function draft(host: HTMLElement, value: string) { const input = host.querySelector<HTMLTextAreaElement>('[data-json-publish-content]')!; input.value = value; input.dispatchEvent(new Event('input')); await flush() }
async function submit(host: HTMLElement) { host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush() }
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); document.body.innerHTML = ''; vi.resetAllMocks() })
describe('managed JSON versions and checks', () => {
  it('loads only on expansion and publishes only on explicit submit', async () => {
    vi.mocked(api.snapshot).mockResolvedValueOnce({ data: state() }).mockResolvedValue({ data: state(true) })
    vi.mocked(api.publish).mockResolvedValue({ data: artifact })
    const { host } = mount(); expect(api.snapshot).not.toHaveBeenCalled(); await open(host)
    await draft(host, '{"summary":false}'); expect(api.publish).not.toHaveBeenCalled(); await submit(host)
    expect(api.publish).toHaveBeenCalledWith(goalId, 'report', { expectedGeneration: '0', jsonContent: '{"summary":false}' })
    expect(host.textContent).toContain('has not been checked')
    expect(host.textContent).toContain('Version published')
  })
  it('checks the exact visible requirement revision and artifact generation', async () => {
    const matched = state(true); matched.checks[0] = { ...matched.checks[0]!, status: 'MATCH', acceptanceEligible: true }
    vi.mocked(api.snapshot).mockResolvedValueOnce({ data: state(true) }).mockResolvedValue({ data: matched })
    vi.mocked(api.check).mockResolvedValue({ data: { ...matched.checks[0]!, missingFields: [], recipeId: 'json-required-fields', recipeRevision: 1, checkedAt: artifact.createdAt, expiresAt: artifact.expiresAt } })
    const { host } = mount(); await open(host)
    host.querySelector<HTMLButtonElement>('[data-json-managed-check]')!.click(); await flush()
    expect(api.check).toHaveBeenCalledWith(goalId, 'report-fields', { expectedRequirementRevision: revision, artifactId: 'version-id', expectedGeneration: generation })
    expect(host.querySelector('[data-json-binding-status]')?.textContent).toContain('Current binding matches')
  })
  it('blocks repeated writes after a generation conflict until reload', async () => {
    vi.mocked(api.snapshot).mockResolvedValue({ data: state(true) }); vi.mocked(api.publish).mockRejectedValue({ code: 409 })
    const { host } = mount(); await open(host); await draft(host, '{}'); await submit(host)
    expect(host.querySelector<HTMLButtonElement>('[data-json-publish]')!.disabled).toBe(true)
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('Reload before')
    await submit(host); expect(api.publish).toHaveBeenCalledTimes(1)
  })
  it('clears versions and drafts and notifies the parent after access revocation', async () => {
    vi.mocked(api.snapshot).mockResolvedValue({ data: state(true) }); vi.mocked(api.version).mockRejectedValue({ code: 403 })
    const { host, lost } = mount(); await open(host); await draft(host, '{"private":"draft"}')
    host.querySelector<HTMLButtonElement>('[data-json-version-inspect]')!.click(); await flush()
    expect(lost).toHaveBeenCalledOnce(); expect(host.querySelector('form')).toBeNull()
    expect(host.querySelector('[data-json-managed-requirement]')).toBeNull(); expect(host.textContent).not.toContain('private')
  })
  it('never inserts delayed version content after changing goals', async () => {
    vi.mocked(api.snapshot).mockResolvedValueOnce({ data: state(true) }).mockResolvedValue({ data: state() })
    let resolve!: (value: unknown) => void
    vi.mocked(api.version).mockImplementation(() => new Promise(r => { resolve = r }) as never)
    const { host, props } = mount(); await open(host)
    host.querySelector<HTMLButtonElement>('[data-json-version-inspect]')!.click(); await flush()
    props.goalId = 'new-goal'; await flush(); resolve({ data: { artifact, jsonContent: 'old private body' } }); await flush()
    expect(host.querySelector('[data-json-version-content]')).toBeNull(); expect(host.textContent).not.toContain('old private body')
  })
  it('does not apply a delayed publication response to another goal', async () => {
    vi.mocked(api.snapshot).mockResolvedValue({ data: state() })
    let resolve!: (value: unknown) => void
    vi.mocked(api.publish).mockImplementation(() => new Promise(r => { resolve = r }) as never)
    const { host, props } = mount(); await open(host); await draft(host, '{}'); await submit(host)
    props.goalId = 'next-goal'; await flush(); resolve({ data: artifact }); await flush()
    expect(host.textContent).not.toContain('Version published')
    expect(api.snapshot).toHaveBeenLastCalledWith('next-goal')
  })
  it('renders stored content as text and blocks malformed object drafts', async () => {
    vi.mocked(api.snapshot).mockResolvedValue({ data: state(true) })
    vi.mocked(api.version).mockResolvedValue({ data: { artifact, jsonContent: '{"summary":"<img src=x onerror=alert(1)>"}' } })
    const { host } = mount(); await open(host)
    host.querySelector<HTMLButtonElement>('[data-json-version-inspect]')!.click(); await flush()
    expect(host.querySelector('pre')?.textContent).toContain('<img'); expect(host.querySelector('img')).toBeNull()
    await draft(host, '[]'); await submit(host); expect(api.publish).not.toHaveBeenCalled()
  })
  it('uses server terminal status and quota to prevent writes', async () => {
    const terminal = state(true); terminal.status = 'completed'
    vi.mocked(api.snapshot).mockResolvedValueOnce({ data: terminal })
    const first = mount(); await open(first.host)
    expect(first.host.querySelector('form')).toBeNull(); expect(first.host.querySelector('[data-json-managed-check]')).toBeNull()
    const full = state(true); full.versionCount = 32; vi.mocked(api.snapshot).mockResolvedValueOnce({ data: full })
    const second = mount(); await open(second.host)
    expect(second.host.querySelector<HTMLButtonElement>('[data-json-publish]')!.disabled).toBe(true)
    expect(second.host.textContent).toContain('version limit is reached')
  })
})
