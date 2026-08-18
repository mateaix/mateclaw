import { createApp, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it } from 'vitest'
import type { AgentRunGroup } from '@/composables/useAgentRunGroups'
import AgentRunGroups from '../AgentRunGroups.vue'

const messages = { live: { teamRuns: {
  title: 'Team runs', lead: 'Lead', elapsed: 'Elapsed', waiting: 'Waiting', active: 'Active', review: 'Review',
  stuck: 'Stuck', cancelled: 'Cancelled', finalizing: 'Finalizing', openRun: 'Open run', noWorkers: 'No worker tasks',
} }, teamRuns: { runtime: 'Runtime', liveness: { quiet: 'Quiet' }, status: { running: 'Running' }, duration: { day: 'd', hour: 'h', minute: 'm', second: 's' } } }
const group: AgentRunGroup = {
  run: {
    id: '20', teamId: '10', workspaceId: '1', leadAgentId: '2', leadConversationId: 'lead', originMessageId: null,
    title: 'Research', objective: 'Collect evidence', status: 'running', finalSummary: null, stopReason: null,
    metadata: null, startedAt: '2026-08-13T10:00:00Z', completedAt: null, createTime: null, updateTime: null,
    progress: { total: 1, done: 0, failed: 0, inReview: 0, percent: 10 }, tasks: [],
  },
  state: 'active', leadRuntime: null, workers: [{
    state: 'waiting', runtime: null,
    task: {
      id: '101', teamId: '10', runId: '20', taskNumber: 1, subject: 'Collect evidence', description: null,
      status: 'blocked', priority: 0, taskType: 'execution', assigneeAgentId: '3', ownerAgentId: null,
      blockedBy: '["100"]', requireApproval: false, progressPercent: null, progressStep: null, result: null,
      reason: null, conversationId: null, metadata: null, createTime: null, updateTime: null,
    },
  }],
}
const apps: Array<ReturnType<typeof createApp>> = []
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); document.body.innerHTML = '' })

describe('AgentRunGroups', () => {
  it('constrains long lead, worker and phase labels inside a 375px surface', () => {
    const longGroup = structuredClone(group)
    longGroup.run.leadAgentId = 'lead-agent-'.repeat(20)
    longGroup.workers[0].task.assigneeAgentId = 'worker-agent-'.repeat(20)
    longGroup.leadRuntime = { agentName: 'lead-name-'.repeat(20), currentPhase: 'phase-'.repeat(30) } as never
    const host = document.createElement('div'); host.style.width = '375px'; document.body.appendChild(host)
    const app = createApp(AgentRunGroups, { groups: [longGroup] })
    app.use(createI18n({ legacy: false, locale: 'en', messages: { en: messages } })); app.mount(host); apps.push(app)
    const lead = host.querySelector<HTMLElement>('.agent-run-group__lead')!
    const workerCopy = host.querySelector<HTMLElement>('.agent-run-worker__copy')!
    const workerState = host.querySelector<HTMLElement>('.agent-run-worker__state')!
    expect(['0', '0px']).toContain(getComputedStyle(lead).minWidth)
    expect(['0', '0px']).toContain(getComputedStyle(workerCopy).minWidth)
    expect(getComputedStyle(workerState).maxWidth).not.toBe('none')
    expect(lead.scrollWidth).toBeLessThanOrEqual(lead.clientWidth || 375)
  })
  it('hydrates selected run and emits route actions', async () => {
    const opened: string[] = []
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(AgentRunGroups, {
      groups: [group], selectedRunId: '20', selectedTaskId: '101', onOpenRun: (id: string) => opened.push(id),
    })
    app.use(createI18n({ legacy: false, locale: 'en', messages: { en: messages } }))
    app.mount(host)
    apps.push(app)

    expect(host.querySelector('[data-agent-run-group]')?.classList.contains('is-selected')).toBe(true)
    expect(host.querySelector('.agent-run-worker')?.classList.contains('is-selected')).toBe(true)
    host.querySelector<HTMLButtonElement>('[data-open-agent-run]')!.click()
    await nextTick()
    expect(opened).toEqual(['20'])
  })

  it('uses a stable class for the mobile-only run arrow', () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(AgentRunGroups, { groups: [group] })
    app.use(createI18n({ legacy: false, locale: 'en', messages: { en: messages } }))
    app.mount(host)
    apps.push(app)

    expect(host.querySelector('.agent-run-group__arrow')).not.toBeNull()
  })
})
