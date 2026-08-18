import { createApp, nextTick, type Component } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it } from 'vitest'
import type { TeamRun } from '@/api'
import TeamRunDrawer from '../TeamRunDrawer.vue'
import TeamRunsPanel from '../TeamRunsPanel.vue'

const messages = { teamRuns: {
  history: 'Run history', refresh: 'Refresh', loading: 'Loading runs', empty: 'No runs yet',
  loadError: 'Could not load runs', retryLoad: 'Retry', close: 'Close', partialNotice: 'Some tasks did not complete.',
  loadMore: 'Load more', loadingMore: 'Loading more', detailLoading: 'Loading run details', detailUnavailable: 'Details unavailable',
  status: { planning: 'Planning', running: 'Running', awaiting_review: 'Awaiting review', finalizing: 'Finalizing', completed: 'Completed', partial: 'Partial', failed: 'Failed', cancelled: 'Cancelled' },
  duration: { day: 'd', hour: 'h', minute: 'm', second: 's' }, progress: '{done} of {total} complete',
  tasks: 'Tasks', emptyTasks: 'No tasks', assignee: 'Assignee', dependencies: 'Dependencies', noDependencies: 'None',
  result: 'Result', noResult: 'No result', summary: 'Summary', noSummary: 'No summary', deliverables: 'Deliverables',
  noDeliverables: 'No deliverables', cancel: 'Cancel run', objective: 'Objective', taskProgress: 'Task progress',
  stopReason: 'Stop reason', openTask: 'Open task',
} }

function run(extra: Partial<TeamRun> = {}): TeamRun {
  return { id: '20', teamId: '10', workspaceId: '1', leadAgentId: '2', leadConversationId: 'lead', originMessageId: null,
    title: 'Research', objective: 'Collect evidence', status: 'running', finalSummary: null, stopReason: null,
    metadata: null, startedAt: '2026-08-13T10:00:00Z', completedAt: null, createTime: '2026-08-13T10:00:00Z',
    updateTime: null, progress: { total: 1, done: 0, failed: 0, inReview: 0, percent: 30 }, tasks: [], ...extra }
}

const apps: Array<ReturnType<typeof createApp>> = []
function mount(component: Component, props: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(component, props)
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: messages } }))
  app.mount(host)
  apps.push(app)
  return host
}
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); document.body.innerHTML = '' })

describe('TeamRunsPanel', () => {
  it('offers an explicit load-more state', async () => {
    let loaded = 0
    const host = mount(TeamRunsPanel, { runs: [run()], hasMore: true, onLoadMore: () => { loaded++ } })
    host.querySelector<HTMLButtonElement>('[data-team-runs-load-more]')!.click()
    await nextTick()
    expect(loaded).toBe(1)
    const loadingHost = mount(TeamRunsPanel, { runs: [run()], hasMore: true, loadingMore: true })
    expect(loadingHost.querySelector<HTMLButtonElement>('[data-team-runs-load-more]')!.disabled).toBe(true)
    expect(loadingHost.textContent).toContain('Loading more')
  })
  it('renders compact run rows and emits selection without flattening tasks', async () => {
    let selected = ''
    const host = mount(TeamRunsPanel, { runs: [run()], onSelectRun: (value: TeamRun) => { selected = value.id } })
    expect(host.textContent).toContain('Research')
    expect(host.querySelectorAll('[data-team-run-row]')).toHaveLength(1)
    expect(host.querySelector('[data-team-run-task-list]')).toBeNull()
    host.querySelector<HTMLButtonElement>('[data-team-run-row]')!.click()
    await nextTick()
    expect(selected).toBe('20')
  })

  it('renders loading, empty, and error states', () => {
    expect(mount(TeamRunsPanel, { runs: [], loading: true }).textContent).toContain('Loading runs')
    expect(mount(TeamRunsPanel, { runs: [] }).textContent).toContain('No runs yet')
    expect(mount(TeamRunsPanel, { runs: [], error: 'offline' }).textContent).toContain('Could not load runs')
  })
})

describe('TeamRunDrawer', () => {
  it('forwards Teams management attention actions while defaulting to read-only', async () => {
    const actions: string[] = []
    const attentionItems = [{ id: 'a', type: 'review', severity: 'action', priority: 0, taskId: '101', message: 'Review', createdAt: null }]
    const managed = mount(TeamRunDrawer, {
      run: run({ projectionCompleteness: 'full', attentionItems }), open: true, managementActions: true,
      onViewTask: (id: string) => actions.push(`view:${id}`),
      onApproveTask: (id: string) => actions.push(`approve:${id}`),
    })
    managed.querySelector<HTMLButtonElement>('[data-attention-view-task="101"]')!.click()
    managed.querySelector<HTMLButtonElement>('[data-attention-approve-task="101"]')!.click()
    await nextTick()
    expect(actions).toEqual(['view:101', 'approve:101'])
    const readonly = mount(TeamRunDrawer, { run: run({ projectionCompleteness: 'full', attentionItems }), open: true })
    expect(readonly.querySelector('[data-team-run-attention-actions]')).toBeNull()
  })

  it('shows incomplete detail state and exposes retry without rendering empty evidence', async () => {
    let retries = 0
    const host = mount(TeamRunDrawer, { run: run({ projectionCompleteness: 'summary' }), open: true, detailLoading: true, onRetryDetail: () => { retries++ } })
    expect(host.textContent).toContain('Loading run details')
    expect(host.querySelector('[data-team-run-task-evidence]')).toBeNull()
    const failed = mount(TeamRunDrawer, { run: run({ projectionCompleteness: 'summary' }), open: true, detailError: 'offline', onRetryDetail: () => { retries++ } })
    failed.querySelector<HTMLButtonElement>('[data-team-run-detail-retry]')!.click()
    await nextTick()
    expect(retries).toBe(1)
  })
  it('shows partial state and forwards close and cancel actions', async () => {
    let closed = 0
    let cancelled = ''
    const partialHost = mount(TeamRunDrawer, { run: run({ status: 'partial' }), open: true })
    expect(partialHost.textContent).toContain('Some tasks did not complete.')
    const host = mount(TeamRunDrawer, {
      run: run(), open: true, canCancel: true,
      onClose: () => { closed += 1 }, onCancel: (id: string) => { cancelled = id },
    })
    host.querySelector<HTMLButtonElement>('[data-team-run-cancel]')!.click()
    host.querySelector<HTMLButtonElement>('[data-team-run-drawer-close]')!.click()
    await nextTick()
    expect(cancelled).toBe('20')
    expect(closed).toBe(1)
  })

  it('cycles focus only at dialog boundaries, closes on Escape and returns focus', async () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    let closed = 0
    const host = mount(TeamRunDrawer, { run: run({
      deliverables: [{ id: 'd', name: 'Report', url: '/api/v1/files/generated/report.pdf', type: 'pdf', sourceTaskIds: [], sourceAgentIds: [], createdAt: null, verificationStatus: 'available' }],
    }), open: true, canCancel: true, onClose: () => { closed += 1 } })
    await nextTick()
    const close = host.querySelector<HTMLButtonElement>('[data-team-run-drawer-close]')!
    expect(document.activeElement).toBe(close)
    const link = host.querySelector<HTMLAnchorElement>('[data-team-run-deliverables] a')!
    const cancel = host.querySelector<HTMLButtonElement>('[data-team-run-cancel]')!
    link.focus()
    const middleTab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    link.dispatchEvent(middleTab)
    expect(middleTab.defaultPrevented).toBe(false)
    cancel.focus()
    cancel.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(close)
    close.focus()
    close.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(cancel)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await nextTick()
    expect(closed).toBe(1)
    expect(document.activeElement).toBe(opener)
  })
})
