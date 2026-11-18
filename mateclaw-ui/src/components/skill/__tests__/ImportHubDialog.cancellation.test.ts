import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
const mocks = vi.hoisted(() => ({ startInstall: vi.fn(), getStatus: vi.fn(), cancelInstall: vi.fn(), info: vi.fn(), warning: vi.fn() }))
vi.mock('@/api/index', () => ({ skillInstallApi: mocks }))
vi.mock('@/composables/useMcToast', () => ({ mcToast: { info: mocks.info, warning: mocks.warning, error: vi.fn(), success: vi.fn() } }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
import ImportHubDialog from '../ImportHubDialog.vue'
let cleanup = () => {}
const task = (cancellable = true) => ({ taskId:'install',bundleUrl:'url',status:'INSTALLING',cancellable })
async function flush() { for(let i=0;i<5;i++) await Promise.resolve(); await nextTick() }
async function mount() {
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp(ImportHubDialog,{ visible:true }); app.mount(host)
  cleanup = () => { app.unmount(); host.remove() }
  const input = host.querySelector<HTMLInputElement>('.form-input')!; input.value = 'https://github.com/test/skill'; input.dispatchEvent(new Event('input')); await nextTick()
  const start = [...host.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.includes('skills.import.install') && !b.textContent?.includes('installing'))!
  start.click(); await flush(); return host
}
beforeEach(() => { vi.useFakeTimers(); vi.clearAllMocks(); mocks.startInstall.mockResolvedValue({data:task()}); mocks.cancelInstall.mockResolvedValue({}); })
afterEach(() => { cleanup(); vi.clearAllTimers(); vi.useRealTimers() })
it('cancels immediately and ignores an older in-flight installing poll', async () => {
  let resolve!: (value: unknown) => void
  mocks.getStatus.mockImplementation(() => new Promise(r => { resolve=r }))
  const host = await mount(); vi.advanceTimersByTime(1500); await flush()
  host.querySelector<HTMLButtonElement>('.btn-cancel')!.click(); await flush()
  expect(mocks.info).toHaveBeenCalledWith('skills.import.cancelled')
  resolve({data:task()}); await flush()
  expect(host.querySelector('.progress-status')?.className).toContain('status-cancelled')
})
it('hides cancellation after commit starts and stops polling on unmount', async () => {
  mocks.startInstall.mockResolvedValue({data:task(false)})
  const host = await mount(); expect(host.querySelector('.btn-cancel')).toBeNull()
  cleanup(); cleanup = () => {}; vi.advanceTimersByTime(3000); await flush()
  expect(mocks.getStatus).not.toHaveBeenCalled()
})
it('does not announce cancellation when backend refuses it', async () => {
  mocks.cancelInstall.mockRejectedValue(new Error('committing'))
  mocks.getStatus.mockResolvedValue({data:task(false)})
  const host = await mount(); host.querySelector<HTMLButtonElement>('.btn-cancel')!.click(); await flush()
  expect(mocks.info).not.toHaveBeenCalled(); expect(mocks.warning).toHaveBeenCalledWith('skills.import.cancelUnavailable')
  expect(host.querySelector('.btn-cancel')).toBeNull()
})

it('does not start polling when a pending start response arrives after unmount', async () => {
  let resolve!: (value: unknown) => void
  mocks.startInstall.mockImplementationOnce(() => new Promise(r => { resolve=r }))
  await mount(); cleanup(); cleanup = () => {}
  resolve({data:task()}); await flush(); vi.advanceTimersByTime(3000); await flush()
  expect(mocks.getStatus).not.toHaveBeenCalled()
})
it('a rejected old poll cannot stop polling a newer task', async () => {
  let reject!: (reason: unknown) => void
  mocks.getStatus.mockImplementationOnce(() => new Promise((_resolve,r) => { reject=r }))
  const host = await mount(); vi.advanceTimersByTime(1500); await flush()
  host.querySelector<HTMLButtonElement>('.btn-cancel')!.click(); await flush()
  mocks.startInstall.mockResolvedValueOnce({data:{...task(),taskId:'next-install'}})
  mocks.getStatus.mockResolvedValue({data:{...task(),taskId:'next-install'}})
  const start = [...host.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent?.includes('skills.import.install') && !b.textContent?.includes('installing'))!
  start.click(); await flush(); reject(new Error('old request failed')); await flush()
  vi.advanceTimersByTime(1500); await flush()
  expect(mocks.getStatus).toHaveBeenCalledTimes(2)
  expect(host.querySelector('.progress-status')?.className).toContain('status-installing')
})
