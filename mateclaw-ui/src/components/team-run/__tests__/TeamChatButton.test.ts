import { createApp, h, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TeamChatButton from '../TeamChatButton.vue'
import zh from '@/i18n/locales/zh-CN'
import en from '@/i18n/locales/en-US'

const cleanups: Array<() => void> = []
afterEach(() => cleanups.splice(0).forEach(cleanup => cleanup()))

async function mount(locale = 'en-US', disabled = false) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/teams', component: { render: () => null } }, { path: '/chat', component: { render: () => null } }],
  })
  await router.push('/teams?teamId=20&taskId=old-worker')
  const host = document.createElement('div')
  document.body.appendChild(host)
  const cardClick = vi.fn()
  const app = createApp({ render: () => h('div', { onClick: cardClick }, [h(TeamChatButton, { leadAgentId: '9007199254740993123', disabled })]) })
  app.use(router)
  app.use(createI18n({ legacy: false, locale, messages: { 'en-US': en, 'zh-CN': zh } }))
  app.mount(host)
  cleanups.push(() => { app.unmount(); host.remove() })
  await nextTick()
  return { host, router, cardClick }
}

describe('team chat entry', () => {
  it('opens a new lead chat without inheriting worker context or opening the team card', async () => {
    const { host, router, cardClick } = await mount()
    const link = host.querySelector<HTMLAnchorElement>('a')!
    expect(link.textContent).toContain('Chat with team')
    link.click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/chat'))
    expect(router.currentRoute.value.query).toEqual({ agentId: '9007199254740993123', action: 'newChat' })
    expect(cardClick).not.toHaveBeenCalled()
  })

  it('provides a Chinese label and a normal navigable URL', async () => {
    const { host } = await mount('zh-CN')
    const link = host.querySelector<HTMLAnchorElement>('a')!
    expect(link.textContent).toContain('与团队对话')
    expect(link.getAttribute('href')).toContain('agentId=9007199254740993123')
    expect(link.getAttribute('href')).toContain('action=newChat')
  })
  it('does not navigate to a different agent when the team lead is unavailable', async () => {
    const { host, router, cardClick } = await mount('en-US', true)
    expect(host.querySelector('a')).toBeNull()
    const button = host.querySelector<HTMLButtonElement>('button')!
    expect(button.disabled).toBe(true)
    expect(button.title).toContain('unavailable')
    button.click()
    await nextTick()
    expect(router.currentRoute.value.path).toBe('/teams')
    expect(cardClick).not.toHaveBeenCalled()
  })

})
