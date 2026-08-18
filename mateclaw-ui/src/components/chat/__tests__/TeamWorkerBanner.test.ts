import { createApp } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it } from 'vitest'
import TeamWorkerBanner from '../TeamWorkerBanner.vue'

const apps: Array<ReturnType<typeof createApp>> = []

afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
})

describe('TeamWorkerBanner', () => {
  it('emits string-safe lead, team, and agent routes', () => {
    const routes: unknown[] = []
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(TeamWorkerBanner, {
      runId: '9007199254740991', taskId: '501', teamId: '20', leadConversationId: 'lead',
      onNavigate: (route: unknown) => routes.push(route),
    })
    app.use(createI18n({ legacy: false, locale: 'en', messages: { en: { teamRuns: {
      workerReadOnly: 'Worker conversation', workerReadOnlyDescription: 'Read only',
      backToLead: 'Lead chat', openInTeams: 'Teams', openInAgents: 'Agents',
    } } } }))
    app.mount(host)
    apps.push(app)

    host.querySelectorAll<HTMLButtonElement>('button').forEach(button => button.click())
    expect(routes).toEqual([
      { path: '/chat', query: { conversationId: 'lead', teamRunId: '9007199254740991' } },
      { path: '/teams', query: { teamId: '20', view: 'runs', runId: '9007199254740991', taskId: '501' } },
      { path: '/agents', query: { view: 'live', teamRunId: '9007199254740991', taskId: '501' } },
    ])
  })
})
