import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { expect, it, vi } from 'vitest'
vi.mock('../MessageBubble.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../CompressionSummary.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../TeamAnnouncePanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/team-run/TeamRunCard.vue', () => ({ default: { template: '<div />' } }))
import MessageList from '../MessageList.vue'
it('exposes an Element for markdown observers even with the quote Teleport sibling', async () => {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp(MessageList, { messages: [] })
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: {} } }))
  const vm = app.mount(host) as unknown as { contentElement: HTMLElement | null }
  try {
    await nextTick()
    expect(vm.contentElement).toBeInstanceOf(HTMLElement)
    expect(vm.contentElement).toBe(host.querySelector('.message-list-content'))
    expect(() => vm.contentElement!.querySelectorAll('pre')).not.toThrow()
  } finally { app.unmount(); host.remove() }
})
