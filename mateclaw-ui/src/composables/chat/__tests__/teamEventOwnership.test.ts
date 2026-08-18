import { describe, expect, it } from 'vitest'
import {
  canonicalTeamEventKey,
  classifyTeamEventOwnership,
  discoveredTeamTaskKey,
  shouldShowInGlobalTeamFeed,
} from '../teamEventOwnership'

describe('team event ownership', () => {
  it('assigns normal lifecycle events to the run or task projection', () => {
    expect(classifyTeamEventOwnership({
      id: 'evt-run', event: 'team_run_progress', data: { runId: '10' },
    })).toBe('run')
    expect(classifyTeamEventOwnership({
      id: 'evt-legacy-run', event: 'team_run', data: { runId: '10' },
    })).toBe('run')
    expect(classifyTeamEventOwnership({
      id: 'evt-task', event: 'team_task_progress', data: { runId: '10', taskId: '101' },
    })).toBe('task')
    expect(classifyTeamEventOwnership({
      id: 'evt-final', event: 'team_announce', data: { runId: '10' },
    })).toBe('run')
    expect(classifyTeamEventOwnership({
      id: 'evt-final-start', event: 'team_announce_start', data: { runId: '10' },
    })).toBe('run')
    expect(classifyTeamEventOwnership({
      id: 'evt-business', event: 'invoice_failed', data: { runId: '10' },
    })).toBe('unowned')
  })

  it('deduplicates replay by stable run and event identifiers', () => {
    const first = canonicalTeamEventKey({
      id: '9007199254740993', event: 'team_task_completed', data: { runId: '10', taskId: '101' },
    })
    const replay = canonicalTeamEventKey({
      id: '9007199254740993', event: 'team_task_completed', data: { runId: '10', taskId: '101' },
    })

    expect(first).toBe('stream:run=10|task=101:9007199254740993')
    expect(replay).toBe(first)
    expect(canonicalTeamEventKey({
      id: 'stream-7', event: 'workspace_status', data: {},
    })).toBe('stream:stream-7')
    expect(canonicalTeamEventKey({ event: 'team_task_completed', data: { runId: '10' } })).toBeNull()
  })

  it('normalizes mirrored action and payload event identities across conversations', () => {
    const parent = canonicalTeamEventKey({
      id: 'stream-1', event: 'team_task_in_review',
      data: { runId: '10', taskId: '101', actionId: 'action-7', conversationId: 'lead' },
    })
    const worker = canonicalTeamEventKey({
      id: 'stream-2', event: 'team_task_in_review',
      data: { runId: '10', taskId: '101', actionId: 'action-7', conversationId: 'worker' },
    })
    const sse = canonicalTeamEventKey({
      id: 'stream-3', event: 'team_task_in_review',
      data: { runId: '10', taskId: '101', actionId: 'action-7' },
    })
    expect(parent).toBe('action:run=10|task=101:team_task_in_review:action-7')
    expect(worker).toBe(parent)
    expect(sse).toBe(parent)
    expect(canonicalTeamEventKey({
      event: 'team_task_in_review', data: { actionId: 'action-8', conversationId: 'lead' },
    })).not.toBe(parent)

    const leadEvent = canonicalTeamEventKey({
      id: 'one', event: 'team_task_progress', data: { eventId: 'event-9', conversationId: 'lead' },
    })
    expect(leadEvent).toBe('event:conversation=lead:event-9')
    expect(canonicalTeamEventKey({
      id: 'two', event: 'team_task_progress', data: { eventId: 'event-9', conversationId: 'lead' },
    })).toBe(leadEvent)
    expect(canonicalTeamEventKey({
      id: 'two', event: 'team_task_progress', data: { eventId: 'event-9', conversationId: 'worker' },
    })).not.toBe(leadEvent)
    expect(canonicalTeamEventKey({
      event: 'team_task_progress', data: { eventId: 'event-9', runId: '10', taskId: '101' },
    })).toBe('event:run=10|task=101:event-9')
  })

  it('keeps lifecycle transitions for the same action distinct', () => {
    const approval = canonicalTeamEventKey({
      event: 'team_task_approval_required',
      data: { runId: '10', taskId: '101', actionId: 'action-7', conversationId: 'lead' },
    })
    const completed = canonicalTeamEventKey({
      event: 'team_task_completed',
      data: { runId: '10', taskId: '101', actionId: 'action-7', conversationId: 'worker' },
    })

    expect(approval).toBe('action:run=10|task=101:team_task_approval_required:action-7')
    expect(completed).toBe('action:run=10|task=101:team_task_completed:action-7')
    expect(completed).not.toBe(approval)
  })

  it('scopes the same action and lifecycle event to its run and task', () => {
    const firstTask = canonicalTeamEventKey({
      event: 'team_task_completed',
      data: { runId: '10', taskId: '101', actionId: 'action-7' },
    })

    expect(canonicalTeamEventKey({
      event: 'team_task_completed',
      data: { runId: '10', taskId: '102', actionId: 'action-7' },
    })).not.toBe(firstTask)
    expect(canonicalTeamEventKey({
      event: 'team_task_completed',
      data: { runId: '11', taskId: '101', actionId: 'action-7' },
    })).not.toBe(firstTask)
  })

  it('scopes stream replay ids by conversation before run and task fallbacks', () => {
    expect(canonicalTeamEventKey({
      id: '7', event: 'team_task_progress', data: { conversationId: 'lead' },
    })).toBe('stream:conversation=lead:7')
    expect(canonicalTeamEventKey({
      id: '7', event: 'team_task_progress', data: { conversationId: 'worker' },
    })).toBe('stream:conversation=worker:7')
    expect(canonicalTeamEventKey({
      id: '7', event: 'team_task_progress', data: { runId: '10', taskId: '101' },
    })).toBe('stream:run=10|task=101:7')
  })

  it('uses action or conversation evidence for known team ownership when run ids are unavailable', () => {
    expect(classifyTeamEventOwnership({
      event: 'team_task_in_review', data: { actionId: 'action-7', conversationId: 'worker' },
    })).toBe('task')
    expect(classifyTeamEventOwnership({
      event: 'team_run_progress', data: { eventId: 'event-9', conversationId: 'lead' },
    })).toBe('run')
    expect(classifyTeamEventOwnership({ event: 'unknown_team_signal', data: { actionId: 'action-7' } }))
      .toBe('unowned')
  })

  it('keeps only user-action exceptions in the global feed', () => {
    const visible = [
      'team_task_failed', 'team_task_blocked', 'team_task_in_review',
      'team_task_review_requested', 'team_task_approval_required',
      'team_task_rejected', 'team_task_stale',
    ]
    for (const event of visible) {
      expect(shouldShowInGlobalTeamFeed({ event, data: { runId: '10', taskId: '101' } }), event).toBe(true)
    }
    for (const event of ['team_task_created', 'team_task_started', 'team_task_progress', 'team_task_completed']) {
      expect(shouldShowInGlobalTeamFeed({ event, data: { runId: '10', taskId: '101' } }), event).toBe(false)
    }
    expect(shouldShowInGlobalTeamFeed({ event: 'workspace_failed', data: {} })).toBe(true)
  })

  it('requires structured ids and rejects entities outside the known projection', () => {
    const known = {
      runIds: new Set(['10']),
      taskKeys: new Set(['10:101']),
      conversationIds: new Set(['lead', 'worker']),
    }

    expect(classifyTeamEventOwnership({
      event: 'team_run_progress', data: { runId: '10' },
    }, known)).toBe('run')
    expect(classifyTeamEventOwnership({
      event: 'team_task_progress', data: { runId: '10', taskId: '101' },
    }, known)).toBe('task')
    expect(classifyTeamEventOwnership({
      event: 'team_run_progress', data: {},
    }, known)).toBe('unowned')
    expect(classifyTeamEventOwnership({
      event: 'team_task_progress', data: { runId: '10' },
    }, known)).toBe('unowned')
    expect(classifyTeamEventOwnership({
      event: 'team_run_progress', data: { runId: '99' },
    }, known)).toBe('unowned')
    expect(classifyTeamEventOwnership({
      event: 'team_task_progress', data: { runId: '10', taskId: '999' },
    }, known)).toBe('unowned')
    expect(classifyTeamEventOwnership({
      event: 'team_task_progress', data: { actionId: 'action-7', conversationId: 'worker' },
    }, known)).toBe('task')
    expect(classifyTeamEventOwnership({
      event: 'team_task_progress', data: { actionId: 'action-7', conversationId: 'other' },
    }, known)).toBe('unowned')
  })

  it('keeps malformed and unknown team events visible in the global feed', () => {
    const known = {
      runIds: new Set(['10']),
      taskKeys: new Set(['10:101']),
    }

    expect(shouldShowInGlobalTeamFeed({
      event: 'team_task_progress', data: { runId: '10' },
    }, known)).toBe(true)
    expect(shouldShowInGlobalTeamFeed({
      event: 'team_task_progress', data: { runId: '99', taskId: '101' },
    }, known)).toBe(true)
  })

  it('discovers the first normal task event only under a known run', () => {
    const knownRuns = new Set(['10'])

    for (const event of [
      'team_task_created', 'team_task_started', 'team_task_progress',
      'team_task_dispatched', 'team_task_completed', 'team_task_cancelled',
    ]) {
      expect(discoveredTeamTaskKey({
        event, data: { runId: '10', taskId: '101' },
      }, knownRuns), event).toBe('10:101')
    }
    expect(discoveredTeamTaskKey({
      event: 'team_task_progress', data: { runId: '99', taskId: '101' },
    }, knownRuns)).toBeNull()
    expect(discoveredTeamTaskKey({
      event: 'team_task_progress', data: { runId: '10' },
    }, knownRuns)).toBeNull()
    for (const event of [
      'team_task_failed', 'team_task_blocked', 'team_task_in_review',
      'team_task_approval_required', 'team_task_rejected', 'team_task_stale',
    ]) {
      expect(discoveredTeamTaskKey({
        event, data: { runId: '10', taskId: '101' },
      }, knownRuns), event).toBeNull()
    }

    const firstProgress = {
      event: 'team_task_progress', data: { runId: '10', taskId: '202' },
    }
    const incrementalTasks = new Set<string>()
    const discovered = discoveredTeamTaskKey(firstProgress, knownRuns)
    if (discovered) incrementalTasks.add(discovered)
    expect(classifyTeamEventOwnership(firstProgress, {
      runIds: knownRuns,
      taskKeys: incrementalTasks,
    })).toBe('task')
    expect(shouldShowInGlobalTeamFeed(firstProgress, {
      runIds: knownRuns,
      taskKeys: incrementalTasks,
    })).toBe(false)
  })
})
