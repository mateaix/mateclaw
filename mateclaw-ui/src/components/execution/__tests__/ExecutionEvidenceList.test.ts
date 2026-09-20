import { createApp, h, nextTick, reactive } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ExecutionEvidenceList from '../ExecutionEvidenceList.vue'
import { executionEvidenceApi } from '@/api/executionEvidence'
import en from '@/i18n/locales/en-US'
vi.mock('@/api/executionEvidence', () => ({ executionEvidenceApi: { list: vi.fn(), get: vi.fn(), checkJson: vi.fn() } }))
const apps: ReturnType<typeof createApp>[] = []
const row = (id: string) => ({ id, attemptId: '9223372036854775801', conversationId: 'one', toolName: 'execCommand', state: 'SUCCEEDED', effectOutcome: 'CONFIRMED', kind: 'COMMAND_EXIT', result: 'OBSERVED', sourceLevel: 'RUNTIME', validity: 'UNKNOWN', summary: 'Exit code: 0', observedAt: '2026-09-07T12:00:00Z', expiresAt: null, artifactRef: null, artifactDigest: null })
async function flush() { await Promise.resolve(); await Promise.resolve(); await nextTick() }
function mount() {
 const props = reactive({ conversationId: 'one', goalId: '9223372036854775802' })
 const host = document.createElement('div'); document.body.append(host)
 const app = createApp({ render: () => h(ExecutionEvidenceList, props) })
 app.use(createI18n({ legacy: false, locale: 'en', messages: { en } })); app.mount(host); apps.push(app)
 return { host, props }
}
afterEach(() => { apps.splice(0).forEach(a => a.unmount()); document.body.innerHTML = ''; vi.resetAllMocks() })
describe('execution evidence', () => {
 it('loads lazily, labels command exits as observations, and deduplicates exact string IDs across pages', async () => {
  vi.mocked(executionEvidenceApi.list).mockResolvedValueOnce({ data: { items: [row('9223372036854775803')], nextCursor: 'next' } } as never).mockResolvedValueOnce({ data: { items: [row('9223372036854775803'), row('9223372036854775804')], nextCursor: null } } as never)
  const { host } = mount(); expect(executionEvidenceApi.list).not.toHaveBeenCalled()
  host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  expect(host.textContent).toContain('Observed'); expect(host.textContent).toContain('does not verify'); expect(host.textContent).toContain('Exit code: 0')
  expect(host.textContent).not.toContain('Check passed')
  host.querySelector<HTMLButtonElement>('[data-evidence-more]')!.click(); await flush()
  expect(host.querySelectorAll('[data-evidence-item]')).toHaveLength(2)
  expect(host.textContent).toContain('9223372036854775804')
  expect(executionEvidenceApi.list).toHaveBeenLastCalledWith({ conversationId: 'one', goalId: '9223372036854775802', teamTaskId: undefined, cursor: 'next', limit: 20 })
 })
 it('shows permission failure separately from empty results', async () => {
  vi.mocked(executionEvidenceApi.list).mockRejectedValue({ code: 403 })
  const { host } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('permission')
  expect(host.textContent).not.toContain('No execution evidence')
 })
 it.each([[401, false], [403, true], [404, false]])('clears prior evidence after a denied list request (%s, more=%s)', async (code, more) => {
  vi.mocked(executionEvidenceApi.list).mockResolvedValueOnce({ data: { items: [{ ...row('old'), summary: 'previous sensitive evidence' }], nextCursor: 'next' } } as never)
    .mockRejectedValueOnce({ code })
    .mockResolvedValueOnce({ data: { items: [row('restored')], nextCursor: null } } as never)
  const { host } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  const button = more ? host.querySelector<HTMLButtonElement>('[data-evidence-more]')!
    : host.querySelector<HTMLButtonElement>('.execution-evidence__body > button')!
  button.click(); await flush()
  expect(host.querySelectorAll('[data-evidence-item]')).toHaveLength(0)
  expect(host.textContent).not.toContain('previous sensitive evidence')
  expect(host.querySelector('[data-evidence-more]')).toBeNull()
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('permission')
  host.querySelector<HTMLButtonElement>('.execution-evidence__body > button')!.click(); await flush()
  expect(host.querySelector('[data-evidence-item]')?.getAttribute('data-evidence-item')).toBe('restored')
 })
 it('keeps the last loaded page for retry after a transient list error', async () => {
  vi.mocked(executionEvidenceApi.list).mockResolvedValueOnce({ data: { items: [row('old')], nextCursor: 'next' } } as never)
    .mockRejectedValueOnce({ response: { status: 500 } })
  const { host } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  host.querySelector<HTMLButtonElement>('.execution-evidence__body > button')!.click(); await flush()
  expect(host.querySelector('[data-evidence-item]')?.getAttribute('data-evidence-item')).toBe('old')
  expect(host.querySelector('[data-evidence-more]')).not.toBeNull()
 })
 it('ignores a stale response after the selected conversation changes', async () => {
  let resolve!: (value: unknown) => void
  vi.mocked(executionEvidenceApi.list).mockImplementationOnce(() => new Promise(r => { resolve = r }) as never).mockResolvedValueOnce({ data: { items: [row('new')], nextCursor: null } } as never)
  const { host, props } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  props.conversationId = 'two'; await flush()
  resolve({ data: { items: [row('old')], nextCursor: null } }); await flush()
  expect(host.querySelector('[data-evidence-item]')?.getAttribute('data-evidence-item')).toBe('new')
 })
 it('refreshes the selected detail lazily and displays a changed artifact as stale', async () => {
  vi.mocked(executionEvidenceApi.list).mockResolvedValue({ data: { items: [row('artifact')], nextCursor: null } } as never)
  vi.mocked(executionEvidenceApi.get).mockResolvedValue({ data: { ...row('artifact'), validity: 'STALE' } } as never)
  const { host } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  expect(executionEvidenceApi.get).not.toHaveBeenCalled()
  const details = host.querySelector('details')!; details.open = true; details.dispatchEvent(new Event('toggle')); await flush()
  expect(executionEvidenceApi.get).toHaveBeenCalledWith('artifact')
  expect(host.textContent).toContain(en.executionEvidence.validity.STALE)
 })
 it('drops a detail response after the conversation changes', async () => {
  let resolve!: (value: unknown) => void
  vi.mocked(executionEvidenceApi.list).mockResolvedValueOnce({ data: { items: [row('old')], nextCursor: null } } as never)
    .mockResolvedValueOnce({ data: { items: [row('new')], nextCursor: null } } as never)
  vi.mocked(executionEvidenceApi.get).mockImplementationOnce(() => new Promise(r => { resolve = r }) as never)
  const { host, props } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  const details = host.querySelector('details')!; details.open = true; details.dispatchEvent(new Event('toggle')); await flush()
  props.conversationId = 'two'; await flush()
  resolve({ data: { ...row('old'), summary: 'stale sensitive detail' } }); await flush()
  expect(host.textContent).not.toContain('stale sensitive detail')
  expect(host.querySelector('[data-evidence-item]')?.getAttribute('data-evidence-item')).toBe('new')
 })
 it('removes the old row when detail authorization is revoked', async () => {
  vi.mocked(executionEvidenceApi.list).mockResolvedValue({ data: { items: [row('old')], nextCursor: null } } as never)
  vi.mocked(executionEvidenceApi.get).mockRejectedValue({ code: 404 })
  const { host } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  const details = host.querySelector('details')!; details.open = true; details.dispatchEvent(new Event('toggle')); await flush()
  expect(host.querySelectorAll('[data-evidence-item]')).toHaveLength(0)
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('permission')
 })

 it('runs an explicit JSON check and clears the result when requirements change', async () => {
  const artifact = { ...row('artifact'), kind: 'ARTIFACT_SNAPSHOT', artifactRef: 'file', artifactDigest: 'hash' }
  vi.mocked(executionEvidenceApi.list).mockResolvedValue({ data: { items: [artifact], nextCursor: null } } as never)
  vi.mocked(executionEvidenceApi.checkJson).mockResolvedValue({ data: { recipeId: 'json-required-fields', recipeRevision: 1,
    status: 'MATCH', requiredFields: ['report'], missingFields: [], checkedAt: '2026-09-13T14:00:00Z', acceptanceEligible: false } } as never)
  const { host } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  expect(executionEvidenceApi.checkJson).not.toHaveBeenCalled()
  const field = host.querySelector<HTMLTextAreaElement>('[data-json-fields]')!
  field.value = 'report'; field.dispatchEvent(new Event('input')); await flush()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  expect(executionEvidenceApi.checkJson).toHaveBeenCalledWith('artifact', ['report'])
  expect(host.querySelector('[data-json-result]')?.textContent).toContain('All listed fields are present')
  expect(host.querySelector('[data-json-result]')?.textContent).toContain('does not complete or verify a goal')
  field.value = 'appendix'; field.dispatchEvent(new Event('input')); await flush()
  expect(host.querySelector('[data-json-result]')).toBeNull()
 })
 it('discards a JSON check response after changing conversations', async () => {
  let resolve!: (value: unknown) => void
  vi.mocked(executionEvidenceApi.list).mockResolvedValueOnce({ data: { items: [{ ...row('artifact'), kind: 'ARTIFACT_SNAPSHOT', artifactRef: 'file' }], nextCursor: null } } as never)
    .mockResolvedValueOnce({ data: { items: [row('new')], nextCursor: null } } as never)
  vi.mocked(executionEvidenceApi.checkJson).mockImplementationOnce(() => new Promise(r => { resolve = r }) as never)
  const { host, props } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  const field = host.querySelector<HTMLTextAreaElement>('[data-json-fields]')!
  field.value = 'report'; field.dispatchEvent(new Event('input')); await flush()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  props.conversationId = 'two'; await flush()
  resolve({ data: { status: 'MATCH', missingFields: [], checkedAt: 'old' } }); await flush()
  expect(host.querySelector('[data-json-result]')).toBeNull()
  expect(host.textContent).not.toContain('All listed fields are present')
 })
 it.each([false, true])('invalidates JSON results when artifact details are refreshed (pending=%s)', async (pending) => {
  const artifact = { ...row('artifact'), kind: 'ARTIFACT_SNAPSHOT', artifactRef: 'file', artifactDigest: 'hash' }
  const result = { data: { status: 'MATCH', missingFields: [], checkedAt: 'old', acceptanceEligible: false } }
  let resolveCheck!: (value: unknown) => void
  vi.mocked(executionEvidenceApi.list).mockResolvedValue({ data: { items: [artifact], nextCursor: null } } as never)
  vi.mocked(executionEvidenceApi.get).mockResolvedValue({ data: { ...artifact, validity: 'STALE' } } as never)
  vi.mocked(executionEvidenceApi.checkJson).mockImplementationOnce(() => new Promise(r => { resolveCheck = r }) as never)
    .mockResolvedValueOnce({ data: { ...result.data, status: 'STALE', checkedAt: 'new' } } as never)
  const { host } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  const field = host.querySelector<HTMLTextAreaElement>('[data-json-fields]')!
  field.value = 'report'; field.dispatchEvent(new Event('input')); await flush()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  if (!pending) {
    resolveCheck(result); await flush()
    expect(host.querySelector('[data-json-result]')?.textContent).toContain('All listed fields are present')
  }
  const details = host.querySelector('details')!; details.open = true; details.dispatchEvent(new Event('toggle')); await flush()
  expect(host.textContent).toContain(en.executionEvidence.validity.STALE)
  if (pending) { resolveCheck(result); await flush() }
  expect(host.querySelector('[data-json-result]')).toBeNull()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  expect(executionEvidenceApi.checkJson).toHaveBeenCalledTimes(2)
  expect(host.querySelector('[data-json-result]')?.textContent).toContain('new')
  expect(host.querySelector('[data-json-result]')?.textContent).not.toContain('All listed fields are present')
 })
 it('ignores an obsolete check rejection while a replacement check is pending', async () => {
  const artifact = { ...row('artifact'), kind: 'ARTIFACT_SNAPSHOT', artifactRef: 'file' }
  let rejectOld!: (reason: unknown) => void
  let resolveNew!: (value: unknown) => void
  vi.mocked(executionEvidenceApi.list).mockResolvedValue({ data: { items: [artifact], nextCursor: null } } as never)
  vi.mocked(executionEvidenceApi.get).mockResolvedValue({ data: artifact } as never)
  vi.mocked(executionEvidenceApi.checkJson).mockImplementationOnce(() => new Promise((_, reject) => { rejectOld = reject }) as never)
    .mockImplementationOnce(() => new Promise(resolve => { resolveNew = resolve }) as never)
  const { host } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  const field = host.querySelector<HTMLTextAreaElement>('[data-json-fields]')!
  field.value = 'report'; field.dispatchEvent(new Event('input')); await flush()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  const details = host.querySelector('details')!; details.open = true; details.dispatchEvent(new Event('toggle')); await flush()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  expect(executionEvidenceApi.checkJson).toHaveBeenCalledTimes(2)
  rejectOld({ code: 403 }); await flush()
  expect(host.querySelector('[data-evidence-item]')).not.toBeNull()
  expect(host.querySelector<HTMLButtonElement>('[data-json-check]')!.disabled).toBe(true)
  expect(host.querySelector('[role="alert"]')).toBeNull()
  resolveNew({ data: { status: 'MATCH', missingFields: [], checkedAt: 'new', acceptanceEligible: false } }); await flush()
  expect(host.querySelector('[data-json-result]')?.textContent).toContain('new')
  expect(host.querySelector<HTMLButtonElement>('[data-json-check]')!.disabled).toBe(false)
 })
 it('rejects empty JSON requirements locally without reading the file', async () => {
  vi.mocked(executionEvidenceApi.list).mockResolvedValue({ data: { items: [{ ...row('artifact'), kind: 'ARTIFACT_SNAPSHOT', artifactRef: 'file' }], nextCursor: null } } as never)
  const { host } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  expect(executionEvidenceApi.checkJson).not.toHaveBeenCalled()
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('unique field names')
 })

 it('removes artifact details when JSON check authorization is revoked', async () => {
  vi.mocked(executionEvidenceApi.list).mockResolvedValue({ data: { items: [{ ...row('artifact'), kind: 'ARTIFACT_SNAPSHOT', artifactRef: 'file' }], nextCursor: null } } as never)
  vi.mocked(executionEvidenceApi.checkJson).mockRejectedValue({ code: 403 })
  const { host } = mount(); host.querySelector<HTMLButtonElement>('[data-evidence-toggle]')!.click(); await flush()
  const field = host.querySelector<HTMLTextAreaElement>('[data-json-fields]')!
  field.value = 'report'; field.dispatchEvent(new Event('input')); await flush()
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true })); await flush()
  expect(host.querySelector('[data-evidence-item]')).toBeNull()
  expect(host.querySelector('[data-json-result]')).toBeNull()
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('permission')
 })

})
