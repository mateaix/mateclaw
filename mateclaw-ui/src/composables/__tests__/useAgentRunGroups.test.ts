import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LiveRunCard, LiveSnapshot, TeamRun, TeamRunTask } from '@/api'
import { teamApi, teamRunApi } from '@/api'
import { buildAgentWorkerChatRoute, projectAgentRunGroups, useAgentRunGroups } from '../useAgentRunGroups'

vi.mock('@/api', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api')>()
  return {
    ...original,
    teamApi: { ...original.teamApi, list: vi.fn() },
    teamRunApi: { ...original.teamRunApi, listByTeam: vi.fn(), listByTeamPage: vi.fn(), get: vi.fn() },
  }
})

const live = (conversationId: string, stuckReason: string | null = null): LiveRunCard => ({
  conversationId, agentId: 2, agentName: conversationId, agentIcon: null, username: null,
  currentPhase: 'tools', runningToolName: null, waitingReason: null, done: false, stopRequested: false,
  firstTokenReceived: true, subscriberCount: 1, queueLen: 0, ageMs: 60_000, msSinceLastEvent: 1_000,
  stuckReason, orphan: false, subagentCount: 0,
})
const task = (id: string, taskNumber: number, status: string, conversationId: string | null, blockedBy: string | null = null): TeamRunTask => ({
  id, teamId: '10', runId: '20', taskNumber, subject: `Task ${id}`, description: null,
  status, priority: 0, taskType: 'execution', assigneeAgentId: `agent-${id}`, ownerAgentId: null,
  blockedBy, requireApproval: false, progressPercent: null, progressStep: null, result: null, reason: null,
  conversationId, metadata: null, createTime: null, updateTime: null,
})
const run = (status: TeamRun['status'], tasks: TeamRunTask[]): TeamRun => ({
  id: '20', teamId: '10', workspaceId: '1', leadAgentId: 'lead-agent', leadConversationId: 'lead-conv',
  originMessageId: null, title: 'Launch research', objective: 'Prepare launch', status, finalSummary: null,
  stopReason: null, metadata: null, startedAt: '2026-08-13T10:00:00Z', completedAt: null,
  createTime: '2026-08-13T10:00:00Z', updateTime: '2026-08-13T10:01:00Z',
  progress: { total: tasks.length, done: 0, failed: 0, inReview: 0, percent: 20 }, tasks,
})
const snapshot = (runs: LiveRunCard[]): LiveSnapshot => ({
  runs, subagents: [], timestamp: 1, summary: { running: runs.length, stuck: 0, orphan: 0, queued: 0, subagentsActive: 0 },
})

describe('projectAgentRunGroups', () => {
  it('joins only explicit task and lead conversation ids and keeps non-team sessions separate', () => {
    const tasks = [
      task('1', 1, 'in_progress', 'worker-active'),
      task('2', 2, 'blocked', null, '["1"]'),
      task('3', 3, 'in_review', 'worker-review'),
      task('4', 4, 'in_progress', 'worker-stuck'),
      task('5', 5, 'cancelled', null),
    ]
    const result = projectAgentRunGroups(snapshot([
      live('lead-conv'), live('worker-active'), live('worker-review'), live('worker-stuck', 'idle_silent'),
      live('Task 1 child guessed name'), live('unrelated'),
    ]), [run('running', tasks)])

    expect(result.groups).toHaveLength(1)
    expect(result.groups[0].leadRuntime?.conversationId).toBe('lead-conv')
    expect(result.groups[0].workers.map(worker => `${worker.task.id}:${worker.state}`)).toEqual([
      '1:active', '2:waiting', '3:review', '4:stuck', '5:cancelled',
    ])
    expect(result.ungrouped.map(item => item.conversationId)).toEqual(['Task 1 child guessed name', 'unrelated'])
    expect(buildAgentWorkerChatRoute(result.groups[0], result.groups[0].workers[0])).toEqual({
      path: '/chat',
      query: {
        conversationId: 'worker-active', agentId: 'agent-1', teamRunId: '20', taskId: '1',
        teamId: '10', leadConversationId: 'lead-conv',
      },
    })
  })

  it('projects finalizing runs but excludes cancelled runs from the live view', () => {
    expect(projectAgentRunGroups(snapshot([]), [run('finalizing', []), run('cancelled', [])]).groups)
      .toHaveLength(1)
    expect(projectAgentRunGroups(snapshot([]), [run('finalizing', []), run('cancelled', [])]).groups[0].state)
      .toBe('finalizing')
  })
})

describe('useAgentRunGroups hydration priority', () => {
  beforeEach(() => vi.resetAllMocks())

  it('keeps an explicit route run when a later snapshot refresh completes first', async () => {
    const routeDetail = deferred<unknown>()
    vi.mocked(teamApi.list).mockResolvedValue({ data: [{ team: { id: '10' } }] } as never)
    vi.mocked(teamRunApi.listByTeamPage).mockResolvedValue({ data: { items: [], nextCursor: null } } as never)
    vi.mocked(teamRunApi.get).mockReturnValue(routeDetail.promise as never)
    const groups = useAgentRunGroups(ref(snapshot([])))

    const routeLoad = groups.ensureRun('historical', 1)
    const pollLoad = groups.refreshForSnapshot()
    await pollLoad
    routeDetail.resolve({ data: { ...run('cancelled', [task('ended', 1, 'cancelled', 'worker-ended')]), id: 'historical' } })
    await routeLoad

    expect(groups.runs.value.map(item => item.id)).toContain('historical')
    expect(teamRunApi.get).toHaveBeenCalledTimes(1)
    expect(teamRunApi.get).toHaveBeenCalledWith('historical')
  })

  it('uses bounded active summary pages with at most three concurrent team requests', async () => {
    const firstTeamRuns = Array.from({ length: 3 }, (_, index) => {
      const runId = `run-${index}`
      return {
        ...run('running', [{
          ...task(`${index}`, 1, 'in_progress', `worker-${index}`),
          runId,
        }]),
        id: runId,
        projectionCompleteness: 'summary',
      }
    })
    const secondTeamRun = {
      ...run('running', [{
        ...task('3', 1, 'in_progress', 'worker-3'),
        teamId: '11', runId: 'run-3',
      }]),
      id: 'run-3', teamId: '11', projectionCompleteness: 'summary',
    }
    const teamIds = ['10', '11', '12', '13', '14']
    const gates = teamIds.map(() => deferred<unknown>())
    let active = 0
    let peak = 0
    vi.mocked(teamApi.list).mockResolvedValue({
      data: teamIds.map(id => ({ team: { id } })),
    } as never)
    vi.mocked(teamRunApi.listByTeamPage).mockImplementation((teamId) => {
      const index = teamIds.indexOf(teamId)
      active += 1
      peak = Math.max(peak, active)
      return gates[index].promise.finally(() => { active -= 1 }) as never
    })
    vi.mocked(teamRunApi.get).mockResolvedValue({ data: run('running', []) } as never)
    const groups = useAgentRunGroups(ref(snapshot([live('worker-0')])))

    const refresh = groups.refreshForSnapshot()
    const duplicate = groups.refreshForSnapshot()
    expect(duplicate).toBe(refresh)
    await vi.waitFor(() => expect(teamRunApi.listByTeamPage).toHaveBeenCalledTimes(3))
    gates[0].resolve({ data: { items: firstTeamRuns, nextCursor: null } })
    gates[1].resolve({ data: { items: [secondTeamRun], nextCursor: null } })
    gates[2].resolve({ data: { items: [], nextCursor: null } })
    await vi.waitFor(() => expect(teamRunApi.listByTeamPage).toHaveBeenCalledTimes(5))
    gates[3].resolve({ data: { items: [], nextCursor: null } })
    gates[4].resolve({ data: { items: [], nextCursor: null } })
    await Promise.all([refresh, duplicate])

    expect(teamApi.list).toHaveBeenCalledTimes(1)
    expect(peak).toBeLessThanOrEqual(3)
    expect(teamRunApi.listByTeamPage).toHaveBeenCalledTimes(5)
    for (const teamId of teamIds) {
      expect(teamRunApi.listByTeamPage).toHaveBeenCalledWith(teamId, { activeOnly: true, limit: 50 })
    }
    expect(teamRunApi.listByTeam).not.toHaveBeenCalled()
    expect(teamRunApi.get).not.toHaveBeenCalled()
    expect(groups.runs.value).toHaveLength(4)
    expect(groups.projection.value.groups[0].workers[0].task.conversationId).toBe('worker-0')
    expect(groups.projection.value.groups[0].workers[0].task.runId)
      .toBe(groups.projection.value.groups[0].run.id)
    expect(groups.projection.value.groups[0].workers[0].task.description).toBeNull()
    expect(groups.projection.value.groups[0].workers[0].task.result).toBeNull()

    await groups.refreshForSnapshot()
    expect(teamApi.list).toHaveBeenCalledTimes(2)
    expect(teamRunApi.listByTeamPage).toHaveBeenCalledTimes(10)
  })

  it('follows each team cursor until all bounded active pages are merged', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) => ({
      ...run('running', []), id: `run-${index}`, projectionCompleteness: 'summary',
    }))
    const finalRun = { ...run('running', []), id: 'run-50', projectionCompleteness: 'summary' }
    vi.mocked(teamApi.list).mockResolvedValue({ data: [{ team: { id: '10' } }] } as never)
    vi.mocked(teamRunApi.listByTeamPage)
      .mockResolvedValueOnce({ data: { items: firstPage, nextCursor: 'cursor-2' } } as never)
      .mockResolvedValueOnce({ data: { items: [finalRun], nextCursor: null } } as never)
    const groups = useAgentRunGroups(ref(snapshot([])))

    await groups.refreshForSnapshot()

    expect(teamRunApi.listByTeamPage).toHaveBeenNthCalledWith(1, '10', {
      activeOnly: true, limit: 50,
    })
    expect(teamRunApi.listByTeamPage).toHaveBeenNthCalledWith(2, '10', {
      activeOnly: true, cursor: 'cursor-2', limit: 50,
    })
    expect(groups.runs.value).toHaveLength(51)
    expect(teamRunApi.get).not.toHaveBeenCalled()
  })

  it('fails the refresh when a team page repeats its cursor', async () => {
    vi.mocked(teamApi.list).mockResolvedValue({ data: [{ team: { id: '10' } }] } as never)
    vi.mocked(teamRunApi.listByTeamPage)
      .mockResolvedValueOnce({ data: { items: [run('running', [])], nextCursor: 'loop' } } as never)
      .mockResolvedValueOnce({ data: { items: [], nextCursor: 'loop' } } as never)
    const groups = useAgentRunGroups(ref(snapshot([])))

    await expect(groups.refreshForSnapshot()).rejects.toThrow('cursor')

    expect(teamRunApi.listByTeamPage).toHaveBeenCalledTimes(2)
    expect(groups.runs.value).toEqual([])
    expect(groups.error.value).toContain('cursor')
  })

  it('stops claiming teams on first failure but settles started loads before releasing single-flight', async () => {
    const pending = deferred<unknown>()
    vi.mocked(teamApi.list)
      .mockResolvedValueOnce({
        data: ['10', '11', '12', '13'].map(id => ({ team: { id } })),
      } as never)
      .mockResolvedValueOnce({ data: [] } as never)
    vi.mocked(teamRunApi.listByTeamPage).mockImplementation((teamId) => {
      if (teamId === '10') return Promise.reject(new Error('team 10 failed')) as never
      if (teamId === '11') return pending.promise as never
      return Promise.resolve({ data: { items: [], nextCursor: null } }) as never
    })
    const groups = useAgentRunGroups(ref(snapshot([])))

    const first = groups.refreshForSnapshot()
    let settled = false
    void first.finally(() => { settled = true }).catch(() => undefined)
    await vi.waitFor(() => expect(teamRunApi.listByTeamPage).toHaveBeenCalledTimes(3))
    const duplicate = groups.refreshForSnapshot()

    expect(duplicate).toBe(first)
    expect(teamRunApi.listByTeamPage).toHaveBeenCalledTimes(3)
    expect(settled).toBe(false)

    pending.resolve({ data: { items: [], nextCursor: null } })
    await expect(first).rejects.toThrow('team 10 failed')
    expect(settled).toBe(true)
    expect(teamRunApi.listByTeamPage).toHaveBeenCalledTimes(3)

    const next = groups.refreshForSnapshot()
    expect(next).not.toBe(first)
    await next
    expect(teamApi.list).toHaveBeenCalledTimes(2)
  })

  it('does not publish a paged refresh that finishes after close', async () => {
    const page = deferred<unknown>()
    vi.mocked(teamApi.list).mockResolvedValue({ data: [{ team: { id: '10' } }] } as never)
    vi.mocked(teamRunApi.listByTeamPage).mockReturnValue(page.promise as never)
    const groups = useAgentRunGroups(ref(snapshot([])))

    const refresh = groups.refreshForSnapshot()
    await vi.waitFor(() => expect(teamRunApi.listByTeamPage).toHaveBeenCalledTimes(1))
    groups.close()
    page.resolve({ data: { items: [run('running', [])], nextCursor: null } })
    await refresh

    expect(groups.runs.value).toEqual([])
    expect(groups.loading.value).toBe(false)
    expect(groups.error.value).toBeNull()
  })
})

describe('useAgentRunGroups live scope', () => {
  it('does not place terminal runs in the live team groups', () => {
    const result = projectAgentRunGroups(snapshot([]), [
      run('completed', []),
      run('running', []),
    ])

    expect(result.groups.map(group => group.run.status)).toEqual(['running'])
  })

  it('does not claim active animation without credible liveness or runtime evidence', () => {
    const quiet = { ...run('running', [task('1', 1, 'in_progress', 'worker')]), liveness: { state: 'quiet' as const, lastActivityAt: null } }
    const result = projectAgentRunGroups(snapshot([]), [quiet])
    expect(result.groups[0].state).toBe('waiting')
    expect(result.groups[0].workers[0].state).toBe('waiting')
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
