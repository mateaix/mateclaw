import { describe, expect, it } from 'vitest'
import type { TeamRun, TeamRunTask } from '@/api'
import {
  buildAgentRunRoute,
  buildChatRunRoute,
  buildTeamRunRoute,
  buildWorkerChatRoute,
  extractRunDeliverables,
  formatRunDuration,
  getRunStatusPresentation,
  orderTasksByDependencies,
} from '../teamRunPresentation'

const task = (id: string, taskNumber: number, extra: Record<string, unknown> = {}): TeamRunTask => ({
  id,
  teamId: '10',
  runId: '20',
  taskNumber,
  subject: `Task ${id}`,
  description: null,
  status: 'pending',
  priority: 0,
  taskType: 'execution',
  assigneeAgentId: '30',
  ownerAgentId: null,
  blockedBy: null,
  requireApproval: false,
  progressPercent: null,
  progressStep: null,
  result: null,
  reason: null,
  conversationId: null,
  metadata: null,
  createTime: null,
  updateTime: null,
  ...extra,
})
const run = (extra: Partial<TeamRun> = {}): TeamRun => ({
  id: '20',
  teamId: '10',
  workspaceId: '1',
  leadAgentId: '30',
  leadConversationId: 'lead-1',
  originMessageId: null,
  title: 'Quarterly analysis',
  objective: 'Compare the quarter',
  status: 'running',
  finalSummary: null,
  stopReason: null,
  metadata: null,
  startedAt: '2026-08-13T10:00:00Z',
  completedAt: null,
  createTime: null,
  updateTime: null,
  progress: { total: 2, done: 0, failed: 0, inReview: 0, percent: 10 },
  tasks: [],
  ...extra,
})

describe('team run status presentation', () => {
  it.each([
    ['planning', 'neutral'],
    ['running', 'green'],
    ['awaiting_review', 'amber'],
    ['finalizing', 'green'],
    ['completed', 'green'],
    ['partial', 'amber'],
    ['failed', 'red'],
    ['cancelled', 'neutral'],
  ] as const)('maps %s to a label key and %s tone', (status, tone) => {
    expect(getRunStatusPresentation(status)).toEqual({
      labelKey: `teamRuns.status.${status}`,
      tone,
    })
  })
})

describe('formatRunDuration', () => {
  it('formats elapsed and completed runs with injected translated units', () => {
    const units = { day: 'D', hour: 'H', minute: 'M', second: 'S' }
    expect(formatRunDuration('2026-08-13T10:00:00Z', null, new Date('2026-08-13T10:02:05Z'), units))
      .toBe('2M 5S')
    expect(formatRunDuration('2026-08-12T08:00:00Z', '2026-08-13T10:03:00Z', undefined, units))
      .toBe('1D 2H')
  })

  it('returns an empty string for missing or invalid timestamps', () => {
    expect(formatRunDuration(null, null)).toBe('')
    expect(formatRunDuration('invalid', null)).toBe('')
  })
})

describe('extractRunDeliverables', () => {
  it('extracts valid run and task metadata entries and de-duplicates them', () => {
    const shared = { name: 'report.pdf', url: '/api/v1/files/generated/report.pdf', time: 'now' }
    const value = run({
      metadata: JSON.stringify({ deliverables: [shared, { name: '', url: '/invalid' }] }),
      tasks: [task('101', 1, {
        metadata: JSON.stringify({ deliverables: [shared, { name: 'data.csv', url: '/api/v1/files/generated/data.csv' }] }),
      })],
    })

    expect(extractRunDeliverables(value)).toEqual([
      { ...shared, taskId: undefined },
      { name: 'data.csv', url: '/api/v1/files/generated/data.csv', time: undefined, taskId: '101' },
    ])
  })

  it('tolerates malformed metadata', () => {
    expect(extractRunDeliverables(run({ metadata: '{', tasks: [task('1', 1, { metadata: 'bad' })] }))).toEqual([])
  })

  it('rejects executable, local-file and protocol-relative URLs', () => {
    const deliverables = [
      { name: 'web', url: 'https://example.com/report.pdf' },
      { name: 'local', url: '/api/v1/files/generated/report' },
      { name: 'script', url: 'javascript:alert(1)' },
      { name: 'data', url: 'data:text/html,unsafe' },
      { name: 'file', url: 'file:///tmp/private' },
      { name: 'host-relative', url: '//evil.example/payload' },
      { name: 'relative', url: 'downloads/report.pdf' },
    ]

    expect(extractRunDeliverables(run({ metadata: JSON.stringify({ deliverables }) })))
      .toEqual([
        { name: 'web', url: 'https://example.com/report.pdf', time: undefined, taskId: undefined },
        { name: 'local', url: '/api/v1/files/generated/report', time: undefined, taskId: undefined },
      ])
  })
})

describe('orderTasksByDependencies', () => {
  it('uses a stable topological order and accepts JSON dependency metadata', () => {
    const tasks = [
      task('3', 3, { blockedBy: '["1","2"]' }),
      task('2', 2, { blockedBy: '["1"]' }),
      task('4', 4),
      task('1', 1),
    ]

    expect(orderTasksByDependencies(tasks).map(item => item.id)).toEqual(['4', '1', '2', '3'])
  })

  it('keeps original relative order for cycles and does not mutate input', () => {
    const tasks = [task('2', 2, { blockedBy: '["1"]' }), task('1', 1, { blockedBy: '["2"]' })]

    expect(orderTasksByDependencies(tasks).map(item => item.id)).toEqual(['2', '1'])
    expect(tasks.map(item => item.id)).toEqual(['2', '1'])
  })
})

describe('team run route builders', () => {
  it('preserves all ids as strings', () => {
    expect(buildChatRunRoute('20', 'lead-1')).toEqual({
      path: '/chat',
      query: { conversationId: 'lead-1', teamRunId: '20' },
    })
    expect(buildAgentRunRoute('20', '101')).toEqual({
      path: '/agents',
      query: { view: 'live', teamRunId: '20', taskId: '101' },
    })
    expect(buildTeamRunRoute('10', '20', '101')).toEqual({
      path: '/teams',
      query: { teamId: '10', view: 'runs', runId: '20', taskId: '101' },
    })
    expect(buildWorkerChatRoute({
      conversationId: 'worker-1', agentId: '30', runId: '20', taskId: '101',
      teamId: '10', leadConversationId: 'lead-1',
    })).toEqual({
      path: '/chat',
      query: {
        agentId: '30', conversationId: 'worker-1', teamRunId: '20', taskId: '101',
        teamId: '10', leadConversationId: 'lead-1',
      },
    })
  })

  it('keeps legacy null-run transcripts editable by omitting teamRunId', () => {
    expect(buildWorkerChatRoute({
      conversationId: 'legacy-worker', agentId: '30', runId: null, taskId: '101',
      teamId: '10', leadConversationId: 'lead-1',
    })).toEqual({
      path: '/chat',
      query: {
        agentId: '30', conversationId: 'legacy-worker', taskId: '101',
        teamId: '10', leadConversationId: 'lead-1',
      },
    })
  })

  it('rejects numeric and blank ids at runtime', () => {
    expect(() => buildTeamRunRoute(10 as unknown as string, '20')).toThrow(TypeError)
    expect(() => buildAgentRunRoute(' ', '101')).toThrow(TypeError)
  })
})
