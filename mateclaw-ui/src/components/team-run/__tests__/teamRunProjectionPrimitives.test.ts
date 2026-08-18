import { createApp, nextTick, type Component } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it } from 'vitest'
import type { TeamRun } from '@/api'
import TeamRunOutcome from '../TeamRunOutcome.vue'
import TeamRunDeliverables from '../TeamRunDeliverables.vue'
import TeamRunAttention from '../TeamRunAttention.vue'
import TeamRunContributions from '../TeamRunContributions.vue'
import TeamRunRuntime from '../TeamRunRuntime.vue'
import TeamRunCard from '../TeamRunCard.vue'

const messages = { teamRuns: {
  outcome: 'Outcome', noSummary: 'No summary', deliverables: 'Deliverables', noDeliverables: 'No deliverables',
  attention: 'Needs attention', noAttention: 'No action needed', contributions: 'Contributions', runtime: 'Runtime',
  quality: { synthesized: 'Synthesized', fallback: 'Fallback', partial: 'Partial', pending: 'Pending' },
  liveness: { live: 'Live', quiet: 'Quiet', stalled: 'Stalled', terminal: 'Finished' },
  status: { running: 'Running', completed: 'Completed', failed: 'Failed', partial: 'Partial' },
  duration: { day: 'd', hour: 'h', minute: 'm', second: 's' }, progress: '{done} of {total} complete',
  expand: 'Expand', collapse: 'Collapse', objective: 'Objective', stopReason: 'Stop reason', cancel: 'Cancel',
  openTask: 'View task',
}, common: { retry: 'Retry', approve: 'Approve' } }

function run(extra: Partial<TeamRun> = {}): TeamRun {
  return {
    id: '20', teamId: '10', workspaceId: '1', leadAgentId: '2', leadConversationId: 'lead', originMessageId: null,
    title: 'Research', objective: 'Collect evidence', status: 'completed', finalSummary: '## Decision\n\nShip it.',
    stopReason: null, metadata: null, startedAt: '2026-08-13T10:00:00Z', completedAt: '2026-08-13T10:05:00Z',
    createTime: null, updateTime: null, projectionCompleteness: 'full', outcomeQuality: 'synthesized',
    deliverables: [{ id: 'd1', name: 'Report', url: '/api/v1/files/generated/report.pdf', type: 'pdf', sourceTaskIds: ['1'], sourceAgentIds: ['3'], createdAt: null, verificationStatus: 'available' }],
    contributions: [{ taskId: '1', agentId: '3', subject: 'Research', status: 'completed', durationSeconds: 30, lastActivityAt: null, resultSummary: 'Evidence gathered', conversationId: 'worker' }],
    attentionItems: [{ id: 'a1', type: 'review', severity: 'action', priority: 0, taskId: '1', message: 'Approve report', createdAt: null }],
    liveness: { state: 'terminal', lastActivityAt: '2026-08-13T10:05:00Z' },
    metrics: { durationSeconds: 300, totalTasks: 1, completedTasks: 1, failedTasks: 0, deliverableCount: 1 },
    progress: { total: 1, done: 1, failed: 0, inReview: 0, percent: 100 }, tasks: [], ...extra,
  }
}

const apps: Array<ReturnType<typeof createApp>> = []
function mount(component: Component, props: Record<string, unknown>) {
  const host = document.createElement('div'); document.body.appendChild(host)
  const app = createApp(component, props)
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: messages } })); app.mount(host); apps.push(app)
  return host
}
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); document.body.innerHTML = '' })

describe('Team Run projection primitives', () => {
  it('filters unsafe canonical deliverables before rendering', () => {
    const value = run({ deliverables: [
      { id: 'safe', name: 'Safe', url: '/api/v1/files/generated/safe.pdf', type: 'pdf', sourceTaskIds: [], sourceAgentIds: [], createdAt: null, verificationStatus: 'available' },
      { id: 'bad', name: 'Bad', url: 'javascript:alert(1)', type: 'html', sourceTaskIds: [], sourceAgentIds: [], createdAt: null, verificationStatus: 'available' },
    ] })
    const host = mount(TeamRunDeliverables, { run: value })
    expect(host.textContent).toContain('Safe')
    expect(host.textContent).not.toContain('Bad')
    expect(host.querySelectorAll('a')).toHaveLength(1)
  })
  it('renders canonical outcome, deliverables, attention, contributions and terminal runtime', async () => {
    const value = run()
    const hosts = [
      mount(TeamRunOutcome, { run: value }), mount(TeamRunDeliverables, { run: value }),
      mount(TeamRunAttention, { run: value }), mount(TeamRunContributions, { run: value }),
      mount(TeamRunRuntime, { run: value }),
    ]
    await nextTick()
    expect(hosts[0].querySelector('h2')?.textContent).toBe('Decision')
    expect(hosts[1].textContent).toContain('Report')
    expect(hosts[2].textContent).toContain('Approve report')
    expect(hosts[3].textContent).toContain('Evidence gathered')
    expect(hosts[4].textContent).toContain('Finished')
    expect(hosts[4].querySelector('.is-loading')).toBeNull()
  })

  it('keeps the expanded chat card outcome-first without raw task evidence', async () => {
    const host = mount(TeamRunCard, { run: run(), expanded: true })
    await nextTick()
    expect(host.querySelector('[data-team-run-outcome]')).not.toBeNull()
    expect(host.querySelector('[data-team-run-deliverables]')).not.toBeNull()
    expect(host.querySelector('[data-team-run-attention]')).not.toBeNull()
    expect(host.querySelector('[data-team-run-task-list]')).toBeNull()
  })

  it('emits management recovery actions by attention type', async () => {
    const actions: string[] = []
    const value = run({ attentionItems: [
      { id: 'failed', type: 'failure', severity: 'error', priority: 1, taskId: '1', message: 'Failed', createdAt: null },
      { id: 'stale', type: 'stale', severity: 'error', priority: 2, taskId: '2', message: 'Stale', createdAt: null },
      { id: 'review', type: 'review', severity: 'action', priority: 0, taskId: '3', message: 'Review', createdAt: null },
      { id: 'blocked', type: 'blocked', severity: 'error', priority: 3, taskId: '4', message: 'Dependency pending', createdAt: null },
    ] })
    const host = mount(TeamRunAttention, {
      run: value,
      managementActions: true,
      onViewTask: (id: string) => actions.push(`view:${id}`),
      onRetryTask: (id: string) => actions.push(`retry:${id}`),
      onApproveTask: (id: string) => actions.push(`approve:${id}`),
    })
    host.querySelector<HTMLButtonElement>('[data-attention-view-task="1"]')!.click()
    host.querySelector<HTMLButtonElement>('[data-attention-retry-task="1"]')!.click()
    host.querySelector<HTMLButtonElement>('[data-attention-retry-task="2"]')!.click()
    host.querySelector<HTMLButtonElement>('[data-attention-approve-task="3"]')!.click()
    host.querySelector<HTMLButtonElement>('[data-attention-view-task="4"]')!.click()
    await nextTick()
    expect(actions).toEqual(['view:1', 'retry:1', 'retry:2', 'approve:3', 'view:4'])
  })

  it('keeps shared attention cards read-only outside Teams management context', () => {
    const host = mount(TeamRunAttention, { run: run() })
    expect(host.querySelector('[data-team-run-attention-actions]')).toBeNull()
    expect(host.querySelector('button')).toBeNull()
  })

  it('disables and marks only the pending task action as busy', () => {
    const value = run({ attentionItems: [{ id: 'failed', type: 'failed', severity: 'error', priority: 1, taskId: '1', message: 'Failed', createdAt: null }] })
    const host = mount(TeamRunAttention, { run: value, managementActions: true, pendingActions: ['1:retry'] })
    const retry = host.querySelector<HTMLButtonElement>('[data-attention-retry-task="1"]')!
    const view = host.querySelector<HTMLButtonElement>('[data-attention-view-task="1"]')!
    expect(retry.disabled).toBe(true)
    expect(retry.getAttribute('aria-busy')).toBe('true')
    expect(view.disabled).toBe(false)
  })
})
