import { createApp, h } from 'vue'
import { createI18n } from 'vue-i18n'
import { it, expect } from 'vitest'
import TeamChatMembers from '../TeamChatMembers.vue'
import TeamTaskBriefing from '@/components/team-run/TeamTaskBriefing.vue'
it('renders separate busy and disabled states, plain descriptions, and only enabled buttons', () => {
  const host = document.createElement('div')
  const app = createApp(TeamChatMembers, {
    team: { name: 'Team' },
    members: [
      {
        agentId: '2',
        name: 'Jane',
        description: '<b>Writer</b>',
        enabled: false,
        activity: 'busy',
      },
      { agentId: '3', name: 'Sam', enabled: true, activity: 'unknown' },
    ],
    coordinatorMode: false,
  } as any)
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: {} } }))
  app.mount(host)
  expect(host.textContent).toContain('<b>Writer</b>')
  expect(host.querySelector('b')).toBeNull()
  expect(host.textContent).toContain('chatTeam.busy')
  expect(host.textContent).toContain('chatTeam.disabled')
  expect(host.querySelectorAll('button:disabled')).toHaveLength(1)
  app.unmount()
})
it('shows saved briefing as plain text and hides legacy or malformed snapshots', () => {
  for (const [metadata, shown] of [
    [
      JSON.stringify({
        briefing: {
          schemaVersion: 1,
          objective: '<img src=x>',
          description: 'saved',
        },
      }),
      true,
    ],
    ['{}', false],
    ['bad', false],
    [JSON.stringify({ briefing: { schemaVersion: 1, constraints: 4 } }), false],
  ] as const) {
    const host = document.createElement('div'),
      app = createApp({ render: () => h(TeamTaskBriefing, { metadata }) })
    app.use(createI18n({ legacy: false, locale: 'en', messages: { en: {} } }))
    app.mount(host)
    expect(!!host.querySelector('section')).toBe(shown)
    expect(host.querySelector('img')).toBeNull()
    if (shown) expect(host.textContent).toContain('<img src=x>')
    app.unmount()
  }
})
