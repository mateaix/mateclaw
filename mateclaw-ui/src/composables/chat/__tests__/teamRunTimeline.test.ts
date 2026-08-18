import { describe, expect, it } from 'vitest'
import { assembleTeamRunTimeline } from '../teamRunTimeline'
import type { TeamRun } from '@/api'
import type { Message } from '@/types'

const message = (id: string, role: Message['role'], content: string, metadata?: unknown): Message => ({
  id, conversationId: 'lead', role, content, contentParts: [], metadata: metadata as never,
})

const run = (id: string, originMessageId: string | null): TeamRun => ({
  id, teamId: `team-${id}`, workspaceId: 'workspace', leadAgentId: 'lead-agent',
  leadConversationId: 'lead', originMessageId, title: `Run ${id}`, objective: 'Work',
  status: 'running', finalSummary: null, stopReason: null, metadata: null,
  startedAt: null, completedAt: null, createTime: null, updateTime: null,
  progress: { total: 0, done: 0, failed: 0, inReview: 0, percent: 0 }, tasks: [],
})

const keys = (items: ReturnType<typeof assembleTeamRunTimeline>) => items.map(item =>
  item.type === 'message' ? `m:${item.message.id}` : `r:${item.run.id}`)

describe('assembleTeamRunTimeline', () => {
  it('keeps the origin user message and anchors its run immediately after it', () => {
    const messages = [
      message('1', 'assistant', 'before'),
      message('2', 'user', 'delegate'),
      message('3', 'assistant', 'after'),
    ]

    expect(keys(assembleTeamRunTimeline(messages, [run('10', '2')]))).toEqual([
      'm:1', 'm:2', 'r:10', 'm:3',
    ])
  })

  it('absorbs only same-run bookkeeping while preserving unrelated order', () => {
    const messages = [
      message('1', 'user', 'delegate'),
      message('2', 'user', 'protocol', { type: 'team_announce', runId: '10', taskId: '101' }),
      message('3', 'assistant', 'reply', { type: 'team_announce_reply', runId: '10', taskId: '101' }),
      message('4', 'assistant', 'unrelated'),
      message('5', 'user', '[System Message] legacy settlement'),
      message('6', 'user', 'unknown run', { type: 'team_announce', runId: '99' }),
    ]

    expect(keys(assembleTeamRunTimeline(messages, [run('10', '1')]))).toEqual([
      'm:1', 'r:10', 'm:4', 'm:5', 'm:6',
    ])
  })

  it('supports multiple runs sharing an origin and de-duplicates projections by string id', () => {
    const messages = [message('1', 'user', 'delegate'), message('2', 'assistant', 'done')]

    expect(keys(assembleTeamRunTimeline(messages, [
      run('10', '1'), run('11', '1'), run('10', '1'),
    ]))).toEqual(['m:1', 'r:10', 'r:11', 'm:2'])
  })

  it('uses the first bookkeeping position when paginated history omits the origin', () => {
    const messages = [
      message('20', 'assistant', 'older visible'),
      message('21', 'user', 'run update', { type: 'team_announce', runId: '10' }),
      message('22', 'assistant', 'later'),
    ]

    expect(keys(assembleTeamRunTimeline(messages, [run('10', '2')]))).toEqual([
      'm:20', 'r:10', 'm:22',
    ])
  })

  it('appends runs with neither a visible origin nor bookkeeping without changing messages', () => {
    const messages = [message('20', 'assistant', 'history'), message('21', 'user', 'question')]

    expect(keys(assembleTeamRunTimeline(messages, [run('10', null)]))).toEqual([
      'm:20', 'm:21', 'r:10',
    ])
    expect(messages).toHaveLength(2)
  })

  it('collapses a replayed ten-task lifecycle into one run while keeping task evidence in the projection', () => {
    const tasks = Array.from({ length: 10 }, (_, index) => ({
      id: String(100 + index), teamId: 'team-10', runId: '10', taskNumber: index + 1,
      subject: `Task ${index + 1}`, description: null, status: 'completed' as const, priority: 0,
      taskType: 'general', assigneeAgentId: `agent-${index + 1}`, ownerAgentId: null,
      blockedBy: null, requireApproval: false, progressPercent: 100, progressStep: null,
      result: `Evidence ${index + 1}`, reason: null, conversationId: `worker-${index + 1}`,
      metadata: null, createTime: null, updateTime: null,
    }))
    const lifecycle = tasks.flatMap(task => [
      message(`start-${task.id}`, 'system', 'started', {
        type: 'team_task_started', runId: '10', taskId: task.id, eventId: `event-${task.id}-start`,
      }),
      message(`done-${task.id}`, 'system', 'completed', {
        type: 'team_task_completed', runId: '10', taskId: task.id, eventId: `event-${task.id}-done`,
      }),
      message(`replay-${task.id}`, 'system', 'completed replay', {
        type: 'team_task_completed', runId: '10', taskId: task.id, eventId: `event-${task.id}-done`,
      }),
    ])
    const projectedRun = { ...run('10', 'origin'), tasks, progress: {
      total: 10, done: 10, failed: 0, inReview: 0, percent: 100,
    } }

    const items = assembleTeamRunTimeline([
      message('origin', 'user', 'Run ten tasks'),
      ...lifecycle,
      message('announce', 'assistant', 'final', {
        type: 'team_announce_reply', runId: '10', eventId: 'event-final',
      }),
    ], [projectedRun, projectedRun])

    expect(keys(items)).toEqual(['m:origin', 'r:10'])
    const runItems = items.filter(item => item.type === 'team-run')
    expect(runItems).toHaveLength(1)
    expect(runItems[0]?.type === 'team-run' && runItems[0].run.tasks).toHaveLength(10)
  })

  it('does not absorb lifecycle-like messages without a known matching run', () => {
    const messages = [
      message('1', 'system', 'missing run', { type: 'team_task_progress', taskId: '101', eventId: 'e1' }),
      message('2', 'system', 'unknown run', { type: 'team_task_completed', runId: '99', taskId: '101', eventId: 'e2' }),
      message('3', 'system', 'business system message', { type: 'audit_completed', runId: '10', eventId: 'e3' }),
      message('4', 'assistant', 'business reply', { runId: '10', eventId: 'e4' }),
    ]

    expect(keys(assembleTeamRunTimeline(messages, [run('10', null)]))).toEqual([
      'm:1', 'm:2', 'm:3', 'm:4', 'r:10',
    ])
  })
})
