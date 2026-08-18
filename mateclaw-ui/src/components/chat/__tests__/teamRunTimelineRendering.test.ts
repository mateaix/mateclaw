import { createApp, defineComponent, h, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TeamRun } from '@/api'
import type { Message } from '@/types'

vi.mock('../MessageBubble.vue', () => ({
  default: defineComponent({
    props: ['message', 'readonly'],
    setup: props => () => h('div', {
      'data-message-id': String(props.message.id),
      'data-readonly': String(Boolean(props.readonly)),
    }, props.message.content),
  }),
}))
vi.mock('../CompressionSummary.vue', () => ({ default: defineComponent({ setup: () => () => h('div') }) }))
vi.mock('../TeamAnnouncePanel.vue', () => ({
  default: defineComponent({ props: ['message'], setup: props => () => h('div', { 'data-announce-id': props.message.id }) }),
}))
vi.mock('@/composables/chat/useStickToBottom', () => ({
  useStickToBottom: () => ({
    scrollRef: { value: null }, contentRef: { value: null }, isAtBottom: { value: true },
    escapedFromLock: { value: false }, scrollToBottom: vi.fn(), resetLock: vi.fn(),
  }),
}))

import MessageList from '../MessageList.vue'

const messages = {
  chat: { loadingOlder: 'Loading', loadOlderMessages: 'Load older', scrollToBottom: 'Bottom' },
  teamRuns: {
    status: { planning: 'Planning', running: 'Running', awaiting_review: 'Review', finalizing: 'Finalizing', completed: 'Completed', partial: 'Partial', failed: 'Failed', cancelled: 'Cancelled' },
    duration: { day: 'd', hour: 'h', minute: 'm', second: 's' }, progress: '{done}/{total}', tasks: 'Tasks',
    emptyTasks: 'No tasks', assignee: 'Assignee', dependencies: 'Dependencies', noDependencies: 'None', result: 'Result',
    noResult: 'No result', summary: 'Summary', noSummary: 'No summary', deliverables: 'Deliverables', noDeliverables: 'None',
    cancel: 'Cancel', expand: 'Expand', collapse: 'Collapse', openTask: 'Open task', objective: 'Objective',
    taskProgress: 'Progress', stopReason: 'Stop reason',
  },
}

const message = (id: string, role: Message['role'], content: string, metadata?: unknown): Message => ({
  id, conversationId: 'lead', role, content, contentParts: [], metadata: metadata as never,
})
const run = (extra: Partial<TeamRun> = {}): TeamRun => ({
  id: '10', teamId: '20', workspaceId: '30', leadAgentId: '40', leadConversationId: 'lead',
  originMessageId: '1', title: 'Launch research', objective: 'Collect evidence', status: 'running',
  finalSummary: null, stopReason: null, metadata: null, startedAt: null, completedAt: null,
  createTime: null, updateTime: null, progress: { total: 0, done: 0, failed: 0, inReview: 0, percent: 0 }, tasks: [],
  ...extra,
})

const apps: Array<ReturnType<typeof createApp>> = []
function mount(props: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(MessageList, props)
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: messages } }))
  app.mount(host)
  apps.push(app)
  return host
}

afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
})

describe('MessageList team run timeline', () => {
  it('passes readonly state to every message action surface', () => {
    const host = mount({ messages: [message('1', 'assistant', 'result')], readonly: true })

    expect(host.querySelector('[data-message-id="1"]')?.getAttribute('data-readonly')).toBe('true')
  })

  it('preserves legacy rendering when teamRuns is not provided', () => {
    const host = mount({ messages: [
      message('1', 'user', 'hello'),
      message('2', 'user', '[System Message] settled'),
    ] })

    expect(host.querySelectorAll('[data-message-id]')).toHaveLength(1)
    expect(host.querySelector('[data-announce-id="2"]')).not.toBeNull()
    expect(host.querySelector('[data-team-run-toggle]')).toBeNull()
  })

  it('renders an anchored run, hides linked bookkeeping, and expands a deep link', async () => {
    const host = mount({
      messages: [
        message('1', 'user', 'delegate'),
        message('2', 'user', 'protocol', { type: 'team_announce', runId: '10', taskId: '501' }),
        message('3', 'assistant', 'unrelated'),
      ],
      teamRuns: [run()],
      expandedTeamRunId: '10',
    })
    await nextTick()

    expect(Array.from(host.querySelectorAll('[data-message-id]')).map(node => node.getAttribute('data-message-id'))).toEqual(['1', '3'])
    expect(host.querySelector('[data-team-run-toggle]')?.getAttribute('aria-expanded')).toBe('true')
    expect(host.textContent).toContain('Launch research')
  })

  it('renders one expandable run card for ten tasks and replayed lifecycle messages', async () => {
    const tasks = Array.from({ length: 10 }, (_, index) => ({
      id: String(500 + index), teamId: '20', runId: '10', taskNumber: index + 1,
      subject: `Evidence task ${index + 1}`, description: null, status: 'completed' as const,
      priority: 0, taskType: 'general', assigneeAgentId: `agent-${index + 1}`, ownerAgentId: null,
      blockedBy: null, requireApproval: false, progressPercent: 100, progressStep: null,
      result: `Result ${index + 1}`, reason: null, conversationId: `worker-${index + 1}`,
      metadata: null, createTime: null, updateTime: null,
    }))
    const lifecycleMessages = tasks.flatMap(task => [
      message(`progress-${task.id}`, 'system', 'progress', {
        type: 'team_task_progress', runId: '10', taskId: task.id, eventId: `progress-${task.id}`,
      }),
      message(`complete-${task.id}`, 'system', 'complete', {
        type: 'team_task_completed', runId: '10', taskId: task.id, eventId: `complete-${task.id}`,
      }),
      message(`replay-${task.id}`, 'system', 'complete replay', {
        type: 'team_task_completed', runId: '10', taskId: task.id, eventId: `complete-${task.id}`,
      }),
    ])
    const projectedRun = run({
      status: 'completed', tasks,
      progress: { total: 10, done: 10, failed: 0, inReview: 0, percent: 100 },
    })
    const host = mount({
      messages: [message('1', 'user', 'delegate ten tasks'), ...lifecycleMessages],
      teamRuns: [projectedRun, projectedRun],
    })
    await nextTick()

    expect(host.querySelectorAll('[data-team-run-toggle]')).toHaveLength(1)
    expect(host.querySelectorAll('[data-message-id]')).toHaveLength(1)
    expect(host.querySelectorAll('.run-task-row')).toHaveLength(0)

    host.querySelector<HTMLButtonElement>('[data-team-run-toggle]')!.click()
    await nextTick()

    expect(host.querySelectorAll('[data-team-run-toggle]')).toHaveLength(1)
    expect(host.querySelectorAll('.run-task-row')).toHaveLength(0)
    expect(host.querySelector('[data-team-run-outcome]')).not.toBeNull()
  })
})
