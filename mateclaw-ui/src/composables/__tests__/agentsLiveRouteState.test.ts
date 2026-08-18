import { describe, expect, it } from 'vitest'
import type { LiveSnapshot, TeamRun, TeamRunTask } from '@/api'
import { createAgentsLiveRouteHydrator, parseAgentsLiveRoute, reconcileAgentsLiveRoute } from '../agentsLiveRouteState'

const task = (id: string, conversationId: string | null): TeamRunTask => ({
  id, teamId: '10', runId: '20', taskNumber: 1, subject: id, description: null, status: 'in_progress',
  priority: 0, taskType: 'execution', assigneeAgentId: 'agent', ownerAgentId: null, blockedBy: null,
  requireApproval: false, progressPercent: null, progressStep: null, result: null, reason: null,
  conversationId, metadata: null, createTime: null, updateTime: null,
})
const run = (id: string, tasks: TeamRunTask[]): TeamRun => ({
  id, teamId: '10', workspaceId: '1', leadAgentId: 'lead', leadConversationId: 'lead-conv',
  originMessageId: null, title: id, objective: id, status: 'running', finalSummary: null, stopReason: null,
  metadata: null, startedAt: null, completedAt: null, createTime: null, updateTime: null,
  progress: { total: tasks.length, done: 0, failed: 0, inReview: 0, percent: 0 }, tasks,
})
const live = (...conversationIds: string[]): LiveSnapshot => ({
  runs: conversationIds.map(conversationId => ({
    conversationId, agentId: 1, agentName: conversationId, agentIcon: null, username: null,
    currentPhase: 'tools', runningToolName: null, waitingReason: null, done: false, stopRequested: false,
    firstTokenReceived: true, subscriberCount: 1, queueLen: 0, ageMs: 1, msSinceLastEvent: 1,
    stuckReason: null, orphan: false, subagentCount: 0,
  })),
  subagents: [], timestamp: 1,
  summary: { running: conversationIds.length, stuck: 0, orphan: 0, queued: 0, subagentsActive: 0 },
})

describe('agents live route state', () => {
  it('parses the whole route and reconciles browser navigation A to B to none', () => {
    const selectedRun = run('20', [task('A', 'worker-a'), task('B', 'worker-b')])
    expect(parseAgentsLiveRoute({ view: 'live', teamRunId: '20', taskId: 'A' })).toEqual({
      view: 'live', runId: '20', taskId: 'A', requiredRunId: '20',
    })
    expect(reconcileAgentsLiveRoute(parseAgentsLiveRoute({ view: 'live', teamRunId: '20', taskId: 'A' }), [selectedRun], live('worker-a', 'worker-b')).selectedTaskId).toBe('A')
    expect(reconcileAgentsLiveRoute(parseAgentsLiveRoute({ view: 'live', teamRunId: '20', taskId: 'B' }), [selectedRun], live('worker-a', 'worker-b')).selectedTaskId).toBe('B')
    expect(reconcileAgentsLiveRoute(parseAgentsLiveRoute({ view: 'live', teamRunId: '20' }), [selectedRun], live('worker-a', 'worker-b')).selectedTaskId).toBeNull()
  })

  it('preserves an ended task deep link and reports its worker as offline', () => {
    const selectedRun = run('20', [task('A', 'worker-a'), task('offline', 'worker-offline')])
    expect(reconcileAgentsLiveRoute(
      parseAgentsLiveRoute({ view: 'live', teamRunId: '20', taskId: 'offline' }), [selectedRun], live('worker-a'),
    )).toMatchObject({
      selectedRunId: '20', selectedTaskId: 'offline',
      selectedWorker: { taskId: 'offline', conversationId: 'worker-offline', online: false },
      replaceQuery: null,
    })
  })

  it('clears a task outside the selected run and clears both ids for an invalid run', () => {
    const selectedRun = run('20', [task('A', 'worker-a')])
    expect(reconcileAgentsLiveRoute(
      parseAgentsLiveRoute({ view: 'live', teamRunId: '20', taskId: 'other' }), [selectedRun], live('worker-a'),
    )).toMatchObject({ selectedRunId: '20', selectedTaskId: null, replaceQuery: { view: 'live', teamRunId: '20' } })
    expect(reconcileAgentsLiveRoute(
      parseAgentsLiveRoute({ view: 'live', teamRunId: 'missing', taskId: 'A' }), [selectedRun], live('worker-a'),
    )).toMatchObject({ selectedRunId: null, selectedTaskId: null, replaceQuery: { view: 'live' } })
  })

  it('ignores an older route hydration after a newer route finishes', async () => {
    const oldLoad = deferred<void>()
    const newLoad = deferred<void>()
    const refreshed: Array<string | null> = []
    const replaced: unknown[] = []
    const hydrator = createAgentsLiveRouteHydrator({
      invalidatePoll: () => {},
      ensureRun: (runId) => {
        refreshed.push(runId)
        return runId === 'old' ? oldLoad.promise : newLoad.promise
      },
      reconcile: route => ({
        selectedRunId: route.runId, selectedTaskId: route.taskId, selectedWorker: null,
        replaceQuery: route.runId === 'old' ? { view: 'live' } : null,
      }),
      replace: query => { replaced.push(query); return Promise.resolve() },
    })

    const oldHydration = hydrator.hydrate(parseAgentsLiveRoute({ view: 'live', teamRunId: 'old' }))
    const newHydration = hydrator.hydrate(parseAgentsLiveRoute({ view: 'live', teamRunId: 'new' }))
    newLoad.resolve()
    await newHydration
    oldLoad.resolve()
    await oldHydration

    expect(refreshed).toEqual(['old', 'new'])
    expect(replaced).toEqual([])
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
