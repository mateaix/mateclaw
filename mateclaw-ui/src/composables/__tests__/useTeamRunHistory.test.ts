import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import { teamRunApi, type TeamRun } from '@/api'
import {
  buildTeamsRouteQuery,
  clearTeamsRunSelection,
  parseTeamsRouteQuery,
  reconcileTeamsRoute,
} from '../teamsRouteState'
import { sortTeamRuns, useTeamRunHistory } from '../useTeamRunHistory'

function run(id: string, createTime: string | null, status: TeamRun['status'] = 'running'): TeamRun {
  return {
    id, teamId: '10', workspaceId: '1', leadAgentId: '2', leadConversationId: 'lead', originMessageId: null,
    title: `Run ${id}`, objective: 'Objective', status, finalSummary: null, stopReason: null, metadata: null,
    startedAt: createTime, completedAt: null, createTime, updateTime: createTime,
    progress: { total: 0, done: 0, failed: 0, inReview: 0, percent: 0 }, tasks: [],
  }
}

function axiosResponse<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: new AxiosHeaders(), config: { headers: new AxiosHeaders() } }
}

describe('teams run routes', () => {
  it('hydrates string ids and defaults an opened team to runs', () => {
    expect(parseTeamsRouteQuery({ teamId: '10', runId: '20', taskId: '30' })).toEqual({
      teamId: '10', view: 'runs', runId: '20', taskId: '30',
    })
    expect(parseTeamsRouteQuery({ teamId: ['10'], view: 'unknown', runId: 20 })).toEqual({
      teamId: '10', view: 'runs', runId: null, taskId: null,
    })
    expect(parseTeamsRouteQuery({})).toEqual({ teamId: null, view: null, runId: null, taskId: null })
  })

  it('reconciles browser navigation from task A to B to no task without navigation writes', () => {
    const base = parseTeamsRouteQuery({ teamId: '10', view: 'runs', runId: '20', taskId: 'A' })
    const taskB = parseTeamsRouteQuery({ teamId: '10', view: 'runs', runId: '20', taskId: 'B' })
    const noTask = parseTeamsRouteQuery({ teamId: '10', view: 'runs', runId: '20' })
    const board = parseTeamsRouteQuery({ teamId: '10', view: 'board', runId: '20', taskId: 'B' })

    expect(reconcileTeamsRoute(base, taskB)).toMatchObject({
      selectedRunId: '20', selectedTaskId: 'B', taskAction: 'load',
    })
    expect(reconcileTeamsRoute(taskB, noTask)).toMatchObject({
      selectedRunId: '20', selectedTaskId: null, taskAction: 'close',
    })
    expect(reconcileTeamsRoute(taskB, board)).toMatchObject({
      selectedRunId: null, selectedTaskId: null, taskAction: 'close',
    })
  })

  it('builds stable route queries without coercing snowflake ids', () => {
    expect(buildTeamsRouteQuery('9007199254740993', 'runs', '9007199254740995', '9007199254740997'))
      .toEqual({ teamId: '9007199254740993', view: 'runs', runId: '9007199254740995', taskId: '9007199254740997' })
    expect(buildTeamsRouteQuery('10', 'members')).toEqual({ teamId: '10', view: 'members' })
    expect(clearTeamsRunSelection(parseTeamsRouteQuery({
      teamId: '10', view: 'runs', runId: '9007199254740995', taskId: '9007199254740997',
    }))).toEqual({ teamId: '10', view: 'runs' })
  })
})

describe('useTeamRunHistory', () => {
  it('uses the paged team API for the first page and cursor continuation', async () => {
    const page = vi.spyOn(teamRunApi, 'listByTeamPage')
      .mockResolvedValueOnce(axiosResponse({ items: [run('2', '2026-02-01')], nextCursor: 'older' }))
      .mockResolvedValueOnce(axiosResponse({ items: [run('1', '2026-01-01')], nextCursor: null }))
    const legacy = vi.spyOn(teamRunApi, 'listByTeam').mockResolvedValue({ data: [] } as never)
    const history = useTeamRunHistory({ subscribe: () => vi.fn() })

    await history.open('10')
    await history.loadMore()

    expect(page).toHaveBeenNthCalledWith(1, '10', { limit: 20 })
    expect(page).toHaveBeenNthCalledWith(2, '10', { cursor: 'older', limit: 20 })
    expect(legacy).not.toHaveBeenCalled()
    expect(history.runs.value.map(item => item.id)).toEqual(['2', '1'])
    vi.restoreAllMocks()
  })

  it('loads the next cursor page and merges duplicate runs without replacing newer details', async () => {
    const listByTeam = vi.fn()
      .mockResolvedValueOnce({ data: { items: [run('2', '2026-02-01'), run('1', '2026-01-01')], nextCursor: 'c2' } })
      .mockResolvedValueOnce({ data: { items: [run('1', '2026-01-01'), run('0', '2025-12-01')], nextCursor: null } })
    const history = useTeamRunHistory({ api: { listByTeam, get: vi.fn() }, subscribe: () => vi.fn() })
    await history.open('10')
    await history.loadMore()
    expect(listByTeam).toHaveBeenNthCalledWith(2, '10', 'c2')
    expect(history.runs.value.map(item => item.id)).toEqual(['2', '1', '0'])
    expect(history.nextCursor.value).toBeNull()
  })

  it('immediately resets pagination when the team changes during loadMore and ignores the old page', async () => {
    const oldPage = deferred<unknown>()
    const newTeam = deferred<unknown>()
    const listByTeam = vi.fn()
      .mockResolvedValueOnce({ data: { items: [run('10', '2026-02-01')], nextCursor: 'older-10' } })
      .mockReturnValueOnce(oldPage.promise)
      .mockReturnValueOnce(newTeam.promise)
    const history = useTeamRunHistory({ api: { listByTeam, get: vi.fn() }, subscribe: () => vi.fn() })
    await history.open('10')
    const loadingOldPage = history.loadMore()

    const openingNewTeam = history.open('20')
    expect(history.nextCursor.value).toBeNull()
    expect(history.loadingMore.value).toBe(false)
    newTeam.resolve({ data: { items: [{ ...run('20', '2026-03-01'), teamId: '20' }], nextCursor: 'older-20' } })
    await openingNewTeam
    oldPage.resolve({ data: { items: [run('9', '2026-01-01')], nextCursor: null } })
    await loadingOldPage

    expect(history.runs.value.map(item => item.id)).toEqual(['20'])
    expect(history.nextCursor.value).toBe('older-20')
    expect(history.loadingMore.value).toBe(false)
  })

  it('tracks detail loading and detail errors independently from list state', async () => {
    const detail = deferred<unknown>()
    const history = useTeamRunHistory({ api: { listByTeam: vi.fn().mockResolvedValue({ data: [] }), get: vi.fn().mockReturnValue(detail.promise) }, subscribe: () => vi.fn() })
    await history.open('10')
    const pending = history.refreshRun('1', '10')
    expect(history.detailLoading.value).toBe(true)
    expect(history.loading.value).toBe(false)
    detail.reject(new Error('detail unavailable'))
    await pending
    expect(history.detailError.value).toBe('detail unavailable')
    expect(history.error.value).toBeNull()
  })

  it('does not let a background SSE refresh for run B change run A drawer detail state', async () => {
    let callback: ((event: { event: string; data: Record<string, unknown> }) => void) | undefined
    const detailA = deferred<unknown>()
    const detailB = deferred<unknown>()
    const get = vi.fn((runId: string) => runId === 'A' ? detailA.promise : detailB.promise)
    const timers: Array<() => void> = []
    const history = useTeamRunHistory({
      api: {
        listByTeam: vi.fn().mockResolvedValue({ data: [
          { ...run('A', '2026-02-02'), projectionCompleteness: 'summary' },
          { ...run('B', '2026-02-01'), projectionCompleteness: 'summary' },
        ] }),
        get,
      },
      subscribe: (_teamId, handler) => { callback = handler; return vi.fn() },
      setTimeoutImpl: handler => { timers.push(handler); return handler },
    })
    await history.open('10')
    history.select('A')

    const selectedDetail = history.ensureSelectedRunDetail('A', null, '10')
    expect(history.detailLoading.value).toBe(true)
    callback?.({ event: 'team_run_progress', data: { runId: 'B' } })
    timers.at(-1)?.()
    await vi.waitFor(() => expect(get).toHaveBeenCalledWith('B'))

    detailB.reject(new Error('run B unavailable'))
    await vi.waitFor(() => expect(get).toHaveBeenCalledTimes(2))
    expect(history.detailLoading.value).toBe(true)
    expect(history.detailError.value).toBeNull()

    detailA.resolve({ data: { ...run('A', '2026-02-02'), projectionCompleteness: 'full' } })
    await selectedDetail
    expect(history.detailLoading.value).toBe(false)
    expect(history.detailError.value).toBeNull()
  })

  it('keeps a same-run foreground detail valid when a later silent SSE refresh fails first', async () => {
    let callback: ((event: { event: string; data: Record<string, unknown> }) => void) | undefined
    const foreground = deferred<unknown>()
    const background = deferred<unknown>()
    const get = vi.fn()
      .mockReturnValueOnce(foreground.promise)
      .mockReturnValueOnce(background.promise)
    const timers: Array<() => void> = []
    const summary = { ...run('A', '2026-02-02'), projectionCompleteness: 'summary' as const }
    const history = useTeamRunHistory({
      api: { listByTeam: vi.fn().mockResolvedValue({ data: [summary] }), get },
      subscribe: (_teamId, handler) => { callback = handler; return vi.fn() },
      setTimeoutImpl: handler => { timers.push(handler); return handler },
    })
    await history.open('10')
    history.select('A')

    const selectedDetail = history.ensureSelectedRunDetail('A', null, '10')
    callback?.({ event: 'team_run_progress', data: { runId: 'A' } })
    timers.at(-1)?.()
    await vi.waitFor(() => expect(get).toHaveBeenCalledTimes(2))

    background.reject(new Error('background unavailable'))
    await vi.waitFor(() => expect(history.detailLoading.value).toBe(true))
    foreground.resolve({ data: { ...summary, projectionCompleteness: 'full' as const, finalSummary: 'complete detail' } })
    await selectedDetail

    expect(history.selectedRun.value?.projectionCompleteness).toBe('full')
    expect(history.selectedRun.value?.finalSummary).toBe('complete detail')
    expect(history.detailLoading.value).toBe(false)
    expect(history.detailError.value).toBeNull()
  })
  it('hydrates a selected summary projection to full while preserving the selected task', async () => {
    const summary = { ...run('1', '2026-01-01'), projectionCompleteness: 'summary' }
    const full = { ...summary, projectionCompleteness: 'full', tasks: [{ id: 'task-1' }] }
    const get = vi.fn().mockResolvedValue({ data: full })
    const history = useTeamRunHistory({ api: { listByTeam: vi.fn().mockResolvedValue({ data: { items: [summary], nextCursor: null } }), get }, subscribe: () => vi.fn() })
    await history.open('10')
    history.select('1', 'task-1')
    await history.ensureSelectedRunDetail('1', 'task-1', '10')
    expect(get).toHaveBeenCalledWith('1')
    expect(history.selectedRun.value?.projectionCompleteness).toBe('full')
    expect(history.selectedTaskId.value).toBe('task-1')
  })

  it('does not let a stale summary hydration replace newer run and task selection', async () => {
    const detail = deferred<unknown>()
    const history = useTeamRunHistory({
      api: { listByTeam: vi.fn().mockResolvedValue({ data: [
        { ...run('1', '2026-01-01'), projectionCompleteness: 'summary' },
        { ...run('2', '2026-01-02'), projectionCompleteness: 'full' },
      ] }), get: vi.fn().mockReturnValue(detail.promise) }, subscribe: () => vi.fn(),
    })
    await history.open('10')
    history.select('1', 'task-a')
    const pending = history.ensureSelectedRunDetail('1', 'task-a', '10')
    history.select('2', 'task-b')
    detail.resolve({ data: { ...run('1', '2026-01-01'), projectionCompleteness: 'full' } })
    await pending
    expect(history.selectedRunId.value).toBe('2')
    expect(history.selectedTaskId.value).toBe('task-b')
    expect(history.detailLoading.value).toBe(false)
    expect(history.detailError.value).toBeNull()
  })

  it('keeps detail loading and errors scoped to the currently selected run', async () => {
    const detailA = deferred<unknown>()
    const detailB = deferred<unknown>()
    const get = vi.fn((id: string) => id === '1' ? detailA.promise : detailB.promise)
    const history = useTeamRunHistory({
      api: { listByTeam: vi.fn().mockResolvedValue({ data: [
        { ...run('1', '2026-01-01'), projectionCompleteness: 'summary' },
        { ...run('2', '2026-01-02'), projectionCompleteness: 'summary' },
      ] }), get }, subscribe: () => vi.fn(),
    })
    await history.open('10')

    history.select('1')
    const pendingA = history.ensureSelectedRunDetail('1', null, '10')
    history.select('2')
    const pendingB = history.ensureSelectedRunDetail('2', null, '10')
    detailA.reject(new Error('run A failed'))
    await pendingA

    expect(history.detailLoading.value).toBe(true)
    expect(history.detailError.value).toBeNull()

    detailB.resolve({ data: { ...run('2', '2026-01-02'), projectionCompleteness: 'full' } })
    await pendingB
    expect(history.detailLoading.value).toBe(false)
  })
  it('loads the new paged team history response', async () => {
    const history = useTeamRunHistory({
      api: { listByTeam: vi.fn().mockResolvedValue({ data: { items: [run('1', '2026-01-01')], nextCursor: 'next' } }), get: vi.fn() },
      subscribe: () => vi.fn(),
    })
    await history.open('10')
    expect(history.runs.value.map(item => item.id)).toEqual(['1'])
    expect(history.nextCursor.value).toBe('next')
  })
  it('keeps an SSE detail overlay when the initial list resolves later', async () => {
    let resolveList!: (value: unknown) => void
    let callback: ((event: { event: string; data: Record<string, unknown> }) => void) | undefined
    const listByTeam = vi.fn().mockReturnValue(new Promise(resolve => { resolveList = resolve }))
    const get = vi.fn().mockResolvedValue({ data: run('1', '2026-03-01', 'completed') })
    const timers: Array<() => void> = []
    const history = useTeamRunHistory({
      api: { listByTeam, get },
      subscribe: (_teamId, handler) => { callback = handler; return vi.fn() },
      setTimeoutImpl: handler => { timers.push(handler); return handler },
    })

    const loading = history.open('10')
    callback?.({ event: 'team_run_completed', data: { runId: '1' } })
    timers.at(-1)?.()
    await vi.waitFor(() => expect(get).toHaveBeenCalledOnce())
    resolveList({ data: [run('1', '2026-01-01', 'running'), run('2', '2026-02-01')] })
    await loading

    expect(history.runs.value.map(item => `${item.id}:${item.status}`)).toEqual(['1:completed', '2:running'])
  })

  it('does not merge a detail projection from another team', async () => {
    const history = useTeamRunHistory({
      api: {
        listByTeam: vi.fn().mockResolvedValue({ data: [] }),
        get: vi.fn().mockResolvedValue({ data: { ...run('9', '2026-03-01'), teamId: '20' } }),
      },
      subscribe: () => vi.fn(),
    })
    await history.open('10')

    expect(await history.refreshRun('9', '10')).toBeNull()
    expect(history.runs.value).toEqual([])
  })

  it('keeps the latest same-run detail when responses resolve in reverse order', async () => {
    const first = deferred<unknown>()
    const second = deferred<unknown>()
    const history = useTeamRunHistory({
      api: {
        listByTeam: vi.fn().mockResolvedValue({ data: [] }),
        get: vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise),
      },
      subscribe: () => vi.fn(),
    })
    await history.open('10')

    const olderRequest = history.refreshRun('1', '10')
    const newerRequest = history.refreshRun('1', '10')
    second.resolve({ data: run('1', '2026-04-01', 'completed') })
    await newerRequest
    first.resolve({ data: run('1', '2026-03-01', 'running') })
    await olderRequest

    expect(history.runs.value.map(item => item.status)).toEqual(['completed'])
  })

  it('ignores an older same-run refresh error after a newer request succeeds', async () => {
    const older = deferred<unknown>()
    const newer = deferred<unknown>()
    const history = useTeamRunHistory({
      api: {
        listByTeam: vi.fn().mockResolvedValue({ data: [] }),
        get: vi.fn().mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise),
      },
      subscribe: () => vi.fn(),
    })
    await history.open('10')

    const olderRequest = history.refreshRun('1', '10')
    const newerRequest = history.refreshRun('1', '10')
    newer.resolve({ data: run('1', '2026-04-01', 'completed') })
    await newerRequest
    older.reject(new Error('stale failure'))
    await olderRequest

    expect(history.runs.value.map(item => item.status)).toEqual(['completed'])
    expect(history.error.value).toBeNull()
  })

  it('invalidates same-run detail when closed and reopened', async () => {
    const stale = deferred<unknown>()
    const get = vi.fn().mockReturnValueOnce(stale.promise)
    const history = useTeamRunHistory({
      api: { listByTeam: vi.fn().mockResolvedValue({ data: [] }), get },
      subscribe: () => vi.fn(),
    })
    await history.open('10')
    const request = history.refreshRun('1', '10')
    history.close()
    await history.open('10')
    stale.resolve({ data: run('1', '2026-03-01', 'completed') })
    await request

    expect(history.runs.value).toEqual([])
  })

  it('loads independently, sorts newest first, and refreshes only the event run', async () => {
    let callback: ((event: { event: string; data: Record<string, unknown> }) => void) | undefined
    const listByTeam = vi.fn().mockResolvedValue({ data: [run('1', '2026-01-01'), run('2', '2026-02-01')] })
    const get = vi.fn().mockResolvedValue({ data: run('1', '2026-03-01', 'completed') })
    const timers: Array<() => void> = []
    const history = useTeamRunHistory({
      api: { listByTeam, get },
      subscribe: (_teamId, handler) => { callback = handler; return vi.fn() },
      setTimeoutImpl: handler => { timers.push(handler); return handler },
      clearTimeoutImpl: vi.fn(),
    })

    await history.open('10')
    expect(history.runs.value.map(item => item.id)).toEqual(['2', '1'])
    callback?.({ event: 'team_task_completed', data: { runId: '1', taskId: '50' } })
    callback?.({ event: 'team_run_completed', data: { runId: '1' } })
    expect(get).not.toHaveBeenCalled()
    timers.at(-1)?.()
    await nextTick()
    await Promise.resolve()

    expect(get).toHaveBeenCalledTimes(1)
    expect(history.runs.value.map(item => `${item.id}:${item.status}`)).toEqual(['1:completed', '2:running'])
  })

  it('ignores stale loads and cleans up subscriptions', async () => {
    let resolveA!: (value: unknown) => void
    const stop = vi.fn()
    const listByTeam = vi.fn()
      .mockReturnValueOnce(new Promise(resolve => { resolveA = resolve }))
      .mockResolvedValueOnce({ data: [{ ...run('2', '2026-02-01'), teamId: '20' }] })
    const history = useTeamRunHistory({
      api: { listByTeam, get: vi.fn() },
      subscribe: () => stop,
    })

    const loadingA = history.open('10')
    await history.open('20')
    resolveA({ data: [run('1', '2026-01-01')] })
    await loadingA
    expect(history.runs.value.map(item => item.id)).toEqual(['2'])

    history.close()
    expect(stop).toHaveBeenCalled()
    expect(history.runs.value).toEqual([])
  })
})

describe('sortTeamRuns', () => {
  it('keeps a stable order when timestamps match or are absent', () => {
    expect(sortTeamRuns([run('1', null), run('2', null), run('3', '2026-03-01')]).map(item => item.id))
      .toEqual(['3', '1', '2'])
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
