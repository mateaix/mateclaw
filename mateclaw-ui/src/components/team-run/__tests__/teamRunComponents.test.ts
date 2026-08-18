import { createApp, nextTick, type Component } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TeamRun } from '@/api'
import TeamRunCard from '../TeamRunCard.vue'
import TeamRunDetail from '../TeamRunDetail.vue'

const messages = {
  teamRuns: {
    status: {
      planning: 'Planning', running: 'Running', awaiting_review: 'Awaiting review', finalizing: 'Finalizing',
      completed: 'Completed', partial: 'Partial', failed: 'Failed', cancelled: 'Cancelled',
    },
    duration: { day: 'd', hour: 'h', minute: 'm', second: 's' },
    progress: '{done} of {total} complete',
    tasks: 'Tasks', emptyTasks: 'No tasks in this run', assignee: 'Assignee', dependencies: 'Dependencies',
    noDependencies: 'None', result: 'Result', noResult: 'No result yet', summary: 'Summary',
    noSummary: 'No summary yet', outcome: 'Outcome', attention: 'Needs attention', noAttention: 'No action needed',
    deliverables: 'Deliverables', noDeliverables: 'No deliverables',
    cancel: 'Cancel run', expand: 'Expand run', collapse: 'Collapse run', openTask: 'Open task',
    objective: 'Objective', taskProgress: 'Task progress', stopReason: 'Stop reason',
    quality: { synthesized: 'Synthesized', fallback: 'Fallback', partial: 'Partial', pending: 'Pending' },
  },
}

function sampleRun(extra: Partial<TeamRun> = {}): TeamRun {
  return {
    id: '20', teamId: '10', workspaceId: '1', leadAgentId: '30', leadConversationId: 'lead-1',
    originMessageId: null, title: 'Research launch', objective: 'Prepare launch research', status: 'running',
    finalSummary: null, stopReason: null, metadata: null, startedAt: '2026-08-13T10:00:00Z', completedAt: null,
    createTime: null, updateTime: null, progress: { total: 0, done: 0, failed: 0, inReview: 0, percent: 0 },
    tasks: [], ...extra,
  }
}

const mounted: Array<ReturnType<typeof createApp>> = []

function mount(component: Component, props: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(component, props)
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: messages } }))
  app.mount(host)
  mounted.push(app)
  return host
}

afterEach(() => {
  mounted.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
})

describe('TeamRunCard', () => {
  it('renders a bounded collapsed delivery preview and canonical counts', () => {
    const long = `## Decision\n\n${'evidence '.repeat(80)}`
    const host = mount(TeamRunCard, { run: sampleRun({
      finalSummary: long, outcomeQuality: 'fallback', projectionCompleteness: 'summary',
      metrics: { durationSeconds: 30, totalTasks: 3, completedTasks: 2, failedTasks: 1, deliverableCount: 4 },
      attentionItems: [{ id: 'a', type: 'failure', severity: 'error', priority: 1, taskId: null, message: 'Review', createdAt: null }],
    }) })
    const preview = host.querySelector('[data-team-run-outcome-preview]')!
    expect(preview.textContent!.length).toBeLessThanOrEqual(163)
    expect(host.querySelector('[data-team-run-deliverable-count]')?.textContent).toContain('4')
    expect(host.querySelector('[data-team-run-attention-count]')?.textContent).toContain('1')
    expect(host.querySelector('[data-team-run-outcome-quality]')?.textContent).toContain('Fallback')
    expect(host.querySelector('[data-team-run-outcome]')).toBeNull()
  })
  it.each([
    ['planning', 'Planning'],
    ['running', 'Running'],
    ['awaiting_review', 'Awaiting review'],
    ['partial', 'Partial'],
    ['failed', 'Failed'],
    ['cancelled', 'Cancelled'],
  ] as const)('renders the %s run state', (status, label) => {
    const host = mount(TeamRunCard, { run: sampleRun({ status }) })

    expect(host.textContent).toContain(label)
  })

  it('uses focused Enter and Space activation without click fallback', async () => {
    const toggles: boolean[] = []
    const host = mount(TeamRunCard, { run: sampleRun(), onToggle: (value: boolean) => toggles.push(value) })
    const toggle = host.querySelector<HTMLButtonElement>('[data-team-run-toggle]')!

    expect(toggle.tagName).toBe('BUTTON')
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    toggle.focus()
    expect(document.activeElement).toBe(toggle)
    const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    toggle.dispatchEvent(enter)
    await nextTick()

    expect(enter.defaultPrevented).toBe(true)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(host.textContent).toContain('No summary yet')
    expect(host.querySelector('[data-team-run-task-list]')).toBeNull()
    expect(toggles).toEqual([true])

    const space = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true })
    toggle.dispatchEvent(space)
    await nextTick()
    expect(space.defaultPrevented).toBe(true)
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(toggles).toEqual([true, false])
  })
})

describe('TeamRunDetail', () => {
  it('forwards attention recovery actions only in management context', async () => {
    const actions: string[] = []
    const attentionItems = [{ id: 'a', type: 'failed', severity: 'error', priority: 1, taskId: '101', message: 'Failed', createdAt: null }]
    const host = mount(TeamRunDetail, {
      run: sampleRun({ attentionItems }), managementActions: true,
      onViewTask: (id: string) => actions.push(`view:${id}`),
      onRetryTask: (id: string) => actions.push(`retry:${id}`),
    })
    host.querySelector<HTMLButtonElement>('[data-attention-view-task="101"]')!.click()
    host.querySelector<HTMLButtonElement>('[data-attention-retry-task="101"]')!.click()
    await nextTick()
    expect(actions).toEqual(['view:101', 'retry:101'])
    expect(mount(TeamRunDetail, { run: sampleRun({ attentionItems }) }).querySelector('button[data-attention-view-task]')).toBeNull()
  })

  it('drills into and focuses task evidence inside the detail instead of requesting the legacy modal', async () => {
    const scrollIntoView = vi.fn()
    HTMLElement.prototype.scrollIntoView = scrollIntoView
    const viewed: string[] = []
    const selected: string[] = []
    const task = { id: '101', teamId: '10', runId: '20', taskNumber: 1, subject: 'Blocked evidence', description: 'Wait for dependency', status: 'blocked', priority: 0, taskType: 'execution', assigneeAgentId: '31', ownerAgentId: null, blockedBy: '100', requireApproval: false, progressPercent: 0, progressStep: null, result: null, reason: 'Dependency pending', conversationId: 'worker', metadata: null, createTime: null, updateTime: null }
    const host = mount(TeamRunDetail, {
      run: sampleRun({ tasks: [task], attentionItems: [{ id: 'blocked', type: 'blocked', severity: 'error', priority: 1, taskId: '101', message: 'Dependency pending', createdAt: null }] }),
      managementActions: true,
      onViewTask: (id: string) => viewed.push(id),
      onSelectTask: (value: { id: string }) => selected.push(value.id),
    })

    host.querySelector<HTMLButtonElement>('[data-attention-view-task="101"]')!.click()
    await nextTick()

    const detail = host.querySelector<HTMLElement>('[data-team-run-selected-task]')!
    expect(detail.textContent).toContain('Blocked evidence')
    expect(document.activeElement).toBe(detail)
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'nearest' })
    expect(viewed).toEqual(['101'])
    expect(selected).toEqual([])
  })

  it('renders summary, task drilldown and emits cancel', async () => {
    let cancelled = 0
    const task = {
      id: '101', teamId: '10', runId: '20', taskNumber: 1, subject: 'Collect evidence', description: 'Read sources',
      status: 'completed', priority: 0, taskType: 'execution', assigneeAgentId: '31', ownerAgentId: null,
      blockedBy: null, requireApproval: false, progressPercent: 100, progressStep: null,
      result: 'Evidence collected', reason: null, conversationId: 'worker-1', metadata: null,
      createTime: null, updateTime: null,
    }
    const host = mount(TeamRunDetail, {
      run: sampleRun({ finalSummary: 'Launch is viable', progress: { total: 1, done: 1, failed: 0, inReview: 0, percent: 100 }, tasks: [task] }),
      canCancel: true,
      selectedTaskId: '101',
      onCancel: () => { cancelled += 1 },
    })

    expect(host.textContent).toContain('Launch is viable')
    expect(host.textContent).toContain('Evidence collected')
    host.querySelector<HTMLButtonElement>('[data-team-run-cancel]')!.click()
    await nextTick()
    expect(cancelled).toBe(1)
  })

  it('renders markdown in the run summary instead of one flattened paragraph', async () => {
    const host = mount(TeamRunDetail, {
      run: sampleRun({
        status: 'completed',
        finalSummary: '## 结论\n\n| 项目 | 状态 |\n| --- | --- |\n| 任务 | **完成** |',
      }),
    })
    await nextTick()

    expect(host.querySelector('[data-team-run-outcome] h2')?.textContent).toBe('结论')
    expect(host.querySelector('[data-team-run-outcome] table')).not.toBeNull()
    expect(host.querySelector('[data-team-run-outcome] strong')?.textContent).toBe('完成')
  })

  it('renders task description and result through shared reading surfaces', async () => {
    const task = { id: '101', teamId: '10', runId: '20', taskNumber: 1, subject: 'Evidence', description: '## Method', status: 'completed', priority: 0, taskType: 'execution', assigneeAgentId: '31', ownerAgentId: null, blockedBy: null, requireApproval: false, progressPercent: 100, progressStep: null, result: '```ts\nconst ok = true\n```', reason: null, conversationId: 'worker', metadata: null, createTime: null, updateTime: null }
    const host = mount(TeamRunDetail, { run: sampleRun({ tasks: [task] }), selectedTaskId: '101' })
    await nextTick()
    expect(host.querySelectorAll('[data-team-run-task-markdown]')).toHaveLength(2)
    expect(host.querySelector('[data-team-run-task-markdown] h2')?.textContent).toBe('Method')
    expect(host.querySelector('[data-team-run-task-markdown] pre code')).not.toBeNull()
  })
})
