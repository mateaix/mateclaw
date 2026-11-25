import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { teamApi } from '@/api'
import TeamCoordinatorSettings from '../TeamCoordinatorSettings.vue'
import zh from '@/i18n/locales/zh-CN'
import en from '@/i18n/locales/en-US'

const cleanups: Array<() => void> = []
afterEach(() => { cleanups.splice(0).forEach(fn => fn()); vi.restoreAllMocks() })
async function mount(settings: string | null, runtime = 'native', onSaved = vi.fn()) {
  const host = document.createElement('div'); document.body.appendChild(host)
  const app = createApp(TeamCoordinatorSettings, { teamId: '9007199254740993123', settings, leadRuntimeType: runtime, onSaved })
  app.use(createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': en, 'zh-CN': zh } }))
  app.mount(host); cleanups.push(() => { app.unmount(); host.remove() }); await nextTick()
  return { host, onSaved }
}
describe('team coordinator settings', () => {
  it('defaults off and sends only a mode patch so unrelated settings stay intact', async () => {
    const update = vi.spyOn(teamApi, 'update').mockResolvedValue({ data: {} } as never)
    const { host, onSaved } = await mount('{"notification":{"enabled":false}}')
    const input = host.querySelector<HTMLInputElement>('input')!
    expect(input.checked).toBe(false)
    input.click()
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(update).toHaveBeenCalledWith('9007199254740993123', { settings: '{"coordinatorMode":true}' })
    expect(input.checked).toBe(true)
  })
  it('reverts on active-turn rejection and displays the error', async () => {
    vi.spyOn(teamApi, 'update').mockRejectedValue(new Error('The lead is currently running'))
    const { host, onSaved } = await mount(null)
    const input = host.querySelector<HTMLInputElement>('input')!
    input.click()
    await vi.waitFor(() => expect(host.textContent).toContain('The lead is currently running'))
    expect(input.checked).toBe(false)
    expect(onSaved).not.toHaveBeenCalled()
  })
  it('prevents enabling unsupported runtimes while still allowing recovery by turning off', async () => {
    const update = vi.spyOn(teamApi, 'update').mockResolvedValue({ data: {} } as never)
    const off = await mount(null, 'dsh')
    expect(off.host.querySelector<HTMLInputElement>('input')!.disabled).toBe(true)
    expect(off.host.textContent).toContain('native')
    const on = await mount('{"coordinatorMode":true}', 'dsh')
    expect(on.host.querySelector<HTMLInputElement>('input')!.disabled).toBe(false)
    on.host.querySelector<HTMLInputElement>('input')!.click()
    await vi.waitFor(() => expect(update).toHaveBeenCalledWith('9007199254740993123', { settings: '{"coordinatorMode":false}' }))
  })
})
