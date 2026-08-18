import { describe, expect, it, vi } from 'vitest'
import type { LiveSnapshot } from '@/api'
import { createAgentsLiveRouteHydrator, parseAgentsLiveRoute } from '../agentsLiveRouteState'
import { useLiveSnapshot } from '../useLiveSnapshot'

const snapshot = (conversationId: string): LiveSnapshot => ({
  runs: [{
    conversationId, agentId: 1, agentName: conversationId, agentIcon: null, username: null,
    currentPhase: 'tools', runningToolName: null, waitingReason: null, done: false, stopRequested: false,
    firstTokenReceived: true, subscriberCount: 1, queueLen: 0, ageMs: 1, msSinceLastEvent: 1,
    stuckReason: null, orphan: false, subagentCount: 0,
  }],
  subagents: [], timestamp: 1,
  summary: { running: 1, stuck: 0, orphan: 0, queued: 0, subagentsActive: 0 },
})

describe('useLiveSnapshot', () => {
  it('ignores an older overlapping response and refreshes runs only for the latest snapshot', async () => {
    const older = deferred<unknown>()
    const newer = deferred<unknown>()
    const load = vi.fn().mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    const refreshRuns = vi.fn().mockResolvedValue(undefined)
    const live = useLiveSnapshot({ load, refreshRuns })

    const olderRequest = live.refresh()
    const newerRequest = live.refresh()
    newer.resolve({ data: snapshot('new') })
    await newerRequest
    older.resolve({ data: snapshot('old') })
    await olderRequest

    expect(live.snapshot.value?.runs[0].conversationId).toBe('new')
    expect(refreshRuns).toHaveBeenCalledTimes(1)
    expect(refreshRuns).toHaveBeenCalledWith()
  })

  it('does not let a poll started before route hydration load or reconcile the old run', async () => {
    const poll = deferred<unknown>()
    const routeLoad = deferred<void>()
    const refreshRuns = vi.fn().mockImplementation(runId => runId === 'run-new' ? routeLoad.promise : Promise.resolve())
    const live = useLiveSnapshot({ load: vi.fn().mockReturnValue(poll.promise), refreshRuns })
    const reconcile = vi.fn().mockReturnValue({
      selectedRunId: 'run-new', selectedTaskId: null, selectedWorker: null, replaceQuery: null,
    })
    const hydrator = createAgentsLiveRouteHydrator({
      invalidatePoll: live.invalidate,
      ensureRun: (runId) => refreshRuns(runId),
      reconcile,
      replace: vi.fn(),
    })

    const oldPoll = live.refresh()
    const routeHydration = hydrator.hydrate(parseAgentsLiveRoute({ view: 'live', teamRunId: 'run-new' }))
    routeLoad.resolve()
    await routeHydration
    poll.resolve({ data: snapshot('old') })

    expect(await oldPoll).toBe(false)
    expect(refreshRuns.mock.calls).toEqual([['run-new']])
    expect(reconcile).toHaveBeenCalledOnce()
    expect(live.snapshot.value).toBeNull()
  })

  it('stops blocking the initial view when the team run history is slow', async () => {
    const history = deferred<void>()
    const refreshRuns = vi.fn().mockReturnValue(history.promise)
    const live = useLiveSnapshot({ load: vi.fn().mockResolvedValue({ data: snapshot('live') }), refreshRuns })

    const request = live.refresh()
    await Promise.resolve()

    expect(live.snapshot.value?.runs[0].conversationId).toBe('live')
    expect(live.loading.value).toBe(false)
    expect(await request).toBe(true)

    history.resolve()
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
