import { createApp, h, nextTick, reactive, ref, KeepAlive } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ConversationGoalsControl from '../ConversationGoalsControl.vue'
import { goalApi } from '@/api'
import en from '@/i18n/locales/en-US'

vi.mock('@/api', () => ({ goalApi: { history: vi.fn() } }))
vi.mock('@/components/agents/GoalsPanel.vue', () => ({ default: {
  props: ['goals', 'loading', 'error', 'hasMore'], emits: ['close', 'refresh', 'load-more'],
  template: '<section role="dialog"><p v-for="goal in goals" :key="goal.id">{{goal.id}} {{goal.status}}</p><span role="alert">{{error}}</span><button @click="$emit(\'close\')">Close</button><button @click="$emit(\'refresh\')">Refresh</button><button v-if="hasMore" @click="$emit(\'load-more\')">Older</button></section>',
} }))
const apps: ReturnType<typeof createApp>[] = []
async function flush() { await Promise.resolve(); await Promise.resolve(); await nextTick() }
function mount() {
  const props = reactive({ conversationId: 'owned-conversation' })
  const active = ref(true)
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp({ render: () => h(KeepAlive, null, () => active.value ? h(ConversationGoalsControl, props) : h('div')) })
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en } })); app.mount(host); apps.push(app)
  return { host, props, active }
}
async function click(host: HTMLElement, text: string) {
  Array.from(host.querySelectorAll('button')).find(b => b.textContent === text)!.click(); await flush()
}
afterEach(() => { apps.splice(0).forEach(a => a.unmount()); document.body.innerHTML = ''; vi.resetAllMocks() })

describe('conversation goal history', () => {
  it('loads only on demand and preserves paused/terminal goals and opaque cursors', async () => {
    const page = Array.from({ length: 20 }, (_, i) => ({ id: String(9223372036854775800n - BigInt(i)), status: i ? 'paused' : 'completed' }))
    vi.mocked(goalApi.history).mockResolvedValueOnce({ data: page } as any).mockResolvedValueOnce({ data: [] } as any)
    const { host } = mount(); expect(goalApi.history).not.toHaveBeenCalled()
    host.querySelector('button')!.click(); await flush()
    expect(host.textContent).toContain('completed'); expect(host.textContent).toContain('paused')
    await click(host, 'Older')
    expect(goalApi.history).toHaveBeenLastCalledWith('owned-conversation', page.at(-1)!.id)
    expect(host.textContent).not.toContain('Older')
  })
  it('discards delayed history when changing conversation or closing the panel', async () => {
    let resolve!: (value: any) => void
    vi.mocked(goalApi.history).mockImplementation(() => new Promise(r => { resolve = r }))
    const { host, props } = mount(); host.querySelector('button')!.click(); await flush()
    props.conversationId = 'new-conversation'; await flush()
    resolve({ data: [{ id: 'old', status: 'paused' }] }); await flush()
    expect(host.querySelector('[role=dialog]')).toBeNull(); expect(host.textContent).not.toContain('old')
    host.querySelector('button')!.click(); await flush(); await click(host, 'Close')
    resolve({ data: [{ id: 'late', status: 'completed' }] }); await flush()
    expect(host.querySelector('[role=dialog]')).toBeNull()
  })
  it('discards an in-flight page when a cached route is deactivated', async () => {
    let resolve!: (value: any) => void
    vi.mocked(goalApi.history).mockImplementation(() => new Promise(r => { resolve = r }))
    const { host, active } = mount(); host.querySelector('button')!.click(); await flush()
    active.value = false; await flush()
    resolve({ data: [{ id: 'hidden-route-goal', status: 'paused' }] }); await flush()
    active.value = true; await flush()
    expect(host.querySelector('[role=dialog]')).toBeNull()
    expect(host.textContent).not.toContain('hidden-route-goal')
  })
  it('clears the visible page after a refresh loses access', async () => {
    vi.mocked(goalApi.history).mockResolvedValueOnce({ data: [{ id: 'private-goal', status: 'paused' }] } as any)
      .mockRejectedValueOnce({ code: 403 })
    const { host } = mount(); host.querySelector('button')!.click(); await flush()
    expect(host.textContent).toContain('private-goal'); await click(host, 'Refresh')
    expect(host.textContent).not.toContain('private-goal'); expect(host.querySelector('[role=alert]')!.textContent).toContain('checking your access')
  })
})
