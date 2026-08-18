import { describe, expect, it } from 'vitest'
import { isConversationReadOnly, parseTeamMessageMetadata, resolveWorkerRunContext } from '../messageMetadata'
import type { TeamRun } from '@/api'
import type { Message } from '@/types'

const message = (overrides: Partial<Message> = {}): Message => ({
  id: '100',
  conversationId: 'lead-conversation',
  role: 'user',
  content: 'hello',
  contentParts: [],
  ...overrides,
})

const run = (overrides: Partial<TeamRun> = {}): TeamRun => ({
  id: '9007199254740991',
  teamId: '20',
  workspaceId: '30',
  leadAgentId: '40',
  leadConversationId: 'lead-conversation',
  originMessageId: '100',
  title: 'Research',
  objective: 'Research the launch',
  status: 'running',
  finalSummary: null,
  stopReason: null,
  metadata: null,
  startedAt: null,
  completedAt: null,
  createTime: null,
  updateTime: null,
  progress: { total: 1, done: 0, failed: 0, inReview: 0, percent: 0 },
  tasks: [{
    id: '501', teamId: '20', runId: '9007199254740991', taskNumber: 1,
    subject: 'Collect facts', description: null, status: 'in_progress', priority: 0,
    taskType: 'general', assigneeAgentId: '41', ownerAgentId: null, blockedBy: null,
    requireApproval: false, progressPercent: 10, progressStep: null, result: null,
    reason: null, conversationId: 'worker-conversation', metadata: null,
    createTime: null, updateTime: null,
  }],
  ...overrides,
})

describe('parseTeamMessageMetadata', () => {
  it('defensively parses double-encoded metadata and keeps ids as strings', () => {
    const metadata = JSON.stringify(JSON.stringify({
      type: 'team_run',
      runId: '9007199254740991',
      taskId: '501',
      originMessageId: '100',
    }))

    expect(parseTeamMessageMetadata(message({ metadata: metadata as never }))).toMatchObject({
      type: 'team_run',
      runId: '9007199254740991',
      taskId: '501',
      originMessageId: '100',
      isTeamRunProtocol: true,
      isTeamAnnounce: false,
    })
  })

  it('rejects unsafe numeric ids instead of rounding Snowflake values', () => {
    const parsed = parseTeamMessageMetadata(message({
      metadata: { type: 'team_run', runId: 9007199254740992 } as never,
    }))

    expect(parsed.runId).toBeUndefined()
  })

  it('preserves canonical action, conversation, and payload event identities', () => {
    expect(parseTeamMessageMetadata(message({
      conversationId: 'message-conversation',
      metadata: {
        type: 'team_task_progress',
        actionId: 'action-7',
        conversationId: 'worker-conversation',
        eventId: 'event-9',
      } as never,
    }))).toMatchObject({
      actionId: 'action-7',
      conversationId: 'worker-conversation',
      eventId: 'event-9',
    })

    expect(parseTeamMessageMetadata(message({
      conversationId: 'message-conversation',
      metadata: { type: 'team_task_progress', parentActionId: 'parent-8' } as never,
    }))).toMatchObject({
      actionId: 'parent-8',
      conversationId: 'message-conversation',
    })
  })

  it('uses the content prefix only for legacy null-run announcements', () => {
    const legacy = parseTeamMessageMetadata(message({ content: '[System Message] settled' }))
    const linked = parseTeamMessageMetadata(message({
      content: '[System Message] settled',
      metadata: { runId: '77' } as never,
    }))

    expect(legacy).toMatchObject({ isTeamAnnounce: true, isLegacyTeamAnnounce: true })
    expect(linked).toMatchObject({ isTeamAnnounce: false, isLegacyTeamAnnounce: false })
  })
})

describe('resolveWorkerRunContext', () => {
  it('uses explicit route ids and backend task mappings without conversation-name inference', () => {
    expect(resolveWorkerRunContext({
      messages: [], runs: [run()], conversationId: 'worker-conversation',
      routeRunId: '9007199254740991', routeTaskId: '501',
    })).toMatchObject({ runId: '9007199254740991', taskId: '501', source: 'route' })

    expect(resolveWorkerRunContext({
      messages: [], runs: [run()], conversationId: 'worker-conversation',
    })).toMatchObject({ runId: '9007199254740991', taskId: '501', source: 'projection' })

    expect(resolveWorkerRunContext({
      messages: [], runs: [], conversationId: 'team-task-501',
    })).toBeNull()

    expect(resolveWorkerRunContext({
      messages: [], runs: [run()], conversationId: 'different-conversation',
      routeRunId: '9007199254740991', routeTaskId: '501',
    })).toBeNull()
  })

  it('requires persisted metadata to match a projected worker task', () => {
    const metadataRun = run({
      id: '77',
      tasks: [{ ...run().tasks[0], id: '88', runId: '77' }],
    })
    expect(resolveWorkerRunContext({
      messages: [message({
        conversationId: 'worker-conversation',
        metadata: { runId: '77', taskId: '88', leadConversationId: 'lead' } as never,
      })],
      runs: [metadataRun],
      conversationId: 'worker-conversation',
    })).toEqual({
      runId: '77', taskId: '88', leadConversationId: 'lead-conversation', teamId: '20',
      source: 'metadata',
    })
    expect(resolveWorkerRunContext({
      messages: [message({ metadata: { runId: '77', taskId: '88' } as never })],
      runs: [], conversationId: 'worker-conversation',
    })).toBeNull()
  })

  it('does not treat run-aware announce bookkeeping as a worker conversation', () => {
    expect(resolveWorkerRunContext({
      messages: [message({
        conversationId: 'lead-conversation',
        metadata: { type: 'team_announce', runId: '9007199254740991', taskId: '501' } as never,
      })],
      runs: [run()], conversationId: 'lead-conversation',
    })).toBeNull()
  })
})

describe('isConversationReadOnly', () => {
  it('blocks sends only after worker context has been verified', () => {
    const verified = resolveWorkerRunContext({
      messages: [], runs: [run()], conversationId: 'worker-conversation',
      routeRunId: '9007199254740991', routeTaskId: '501',
    })

    expect(isConversationReadOnly(verified)).toBe(true)
    expect(isConversationReadOnly(null)).toBe(false)
  })
})
