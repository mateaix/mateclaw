import { effectScope, nextTick, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { AxiosHeaders, type AxiosResponse } from 'axios'
import { useTeamRuns, type TeamRunsDependencies } from '../useTeamRuns'
import { teamRunApi, type TeamRun } from '@/api'
import type { TeamBoardEvent } from '@/composables/useTeamEvents'

const run = (id: string, teamId = 'team-1', status: TeamRun['status'] = 'running'): TeamRun => ({
  id, teamId, workspaceId: 'workspace', leadAgentId: 'lead-agent', leadConversationId: 'lead',
  originMessageId: '100', title: `Run ${id}`, objective: 'Work', status,
  finalSummary: null, stopReason: null, metadata: null, startedAt: null, completedAt: null,
  createTime: null, updateTime: null,
  progress: { total: 1, done: status === 'completed' ? 1 : 0, failed: 0, inReview: 0, percent: status === 'completed' ? 100 : 0 },
  tasks: [],
})

const flush = async () => {
  await Promise.resolve()
  await Promise.resolve()
  await nextTick()
}

function axiosResponse<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: new AxiosHeaders(), config: { headers: new AxiosHeaders() } }
}

describe('useTeamRuns', () => {
  it('uses the paged conversation API for the first page and cursor continuation', async () => {
    const page = vi.spyOn(teamRunApi, 'listByConversationPage')
      .mockResolvedValueOnce(axiosResponse({ items: [run('2')], nextCursor: 'older' }))
      .mockResolvedValueOnce(axiosResponse({ items: [run('1')], nextCursor: null }))
    const legacy = vi.spyOn(teamRunApi, 'listByConversation').mockResolvedValue({ data: [] } as never)
    const scope = effectScope()
    const state = scope.run(() => useTeamRuns(ref('lead')))!
    await flush()
    await state.loadMore()

    expect(page).toHaveBeenNthCalledWith(1, 'lead', { limit: 20 })
    expect(page).toHaveBeenNthCalledWith(2, 'lead', { cursor: 'older', limit: 20 })
    expect(legacy).not.toHaveBeenCalled()
    expect(state.runs.value.map(item => item.id)).toEqual(['2', '1'])
    scope.stop()
    vi.restoreAllMocks()
  })

  it('loads more conversation history by cursor and keeps every run reachable once', async () => {
    const dependencies: TeamRunsDependencies = {
      listByConversation: vi.fn()
        .mockResolvedValueOnce({ data: { items: [run('2'), run('1')], nextCursor: 'older' } })
        .mockResolvedValueOnce({ data: { items: [run('1'), run('0')], nextCursor: null } }),
      getRun: vi.fn(), subscribe: vi.fn(() => vi.fn()),
    }
    const scope = effectScope()
    const state = scope.run(() => useTeamRuns(ref('lead'), { dependencies }))!
    await flush()
    await state.loadMore()
    expect(dependencies.listByConversation).toHaveBeenNthCalledWith(2, 'lead', 'older')
    expect(state.runs.value.map(item => item.id)).toEqual(['2', '1', '0'])
    expect(state.nextCursor.value).toBeNull()
    scope.stop()
  })
  it('immediately resets pagination when conversation changes during loadMore and ignores the old page', async () => {
    let resolveOldPage!: (value: { data: { items: TeamRun[]; nextCursor: string | null } }) => void
    let resolveNewConversation!: (value: { data: { items: TeamRun[]; nextCursor: string | null } }) => void
    const oldPage = new Promise<{ data: { items: TeamRun[]; nextCursor: string | null } }>(resolve => { resolveOldPage = resolve })
    const newConversation = new Promise<{ data: { items: TeamRun[]; nextCursor: string | null } }>(resolve => { resolveNewConversation = resolve })
    const dependencies: TeamRunsDependencies = {
      listByConversation: vi.fn()
        .mockResolvedValueOnce({ data: { items: [run('10')], nextCursor: 'older-a' } })
        .mockReturnValueOnce(oldPage)
        .mockReturnValueOnce(newConversation),
      getRun: vi.fn(), subscribe: vi.fn(() => vi.fn()),
    }
    const conversationId = ref('A')
    const scope = effectScope()
    const state = scope.run(() => useTeamRuns(conversationId, { dependencies }))!
    await flush()
    const loadingOldPage = state.loadMore()

    conversationId.value = 'B'
    await nextTick()
    expect(state.nextCursor.value).toBeNull()
    expect(state.loadingMore.value).toBe(false)
    resolveNewConversation({ data: { items: [run('20', 'team-b')], nextCursor: 'older-b' } })
    await flush()
    resolveOldPage({ data: { items: [run('9')], nextCursor: null } })
    await loadingOldPage

    expect(state.runs.value.map(item => item.id)).toEqual(['20'])
    expect(state.nextCursor.value).toBe('older-b')
    expect(state.loadingMore.value).toBe(false)
    scope.stop()
  })
  it('keeps an existing full projection when loadMore returns an overlapping summary', async () => {
    const full = { ...run('10'), projectionCompleteness: 'full' as const, finalSummary: 'complete outcome' }
    const summary = { ...run('10'), projectionCompleteness: 'summary' as const, finalSummary: null }
    const dependencies: TeamRunsDependencies = {
      listByConversation: vi.fn()
        .mockResolvedValueOnce({ data: { items: [full], nextCursor: 'older' } })
        .mockResolvedValueOnce({ data: { items: [summary, run('9')], nextCursor: null } }),
      getRun: vi.fn(), subscribe: vi.fn(() => vi.fn()),
    }
    const scope = effectScope()
    const state = scope.run(() => useTeamRuns(ref('lead'), { dependencies }))!
    await flush()
    await state.loadMore()

    expect(state.runs.value.find(item => item.id === '10')).toEqual(full)
    expect(state.runs.value.map(item => item.id)).toEqual(['10', '9'])
    scope.stop()
  })
  it('hydrates the new paged conversation response', async () => {
    const dependencies: TeamRunsDependencies = {
      listByConversation: vi.fn().mockResolvedValue({ data: { items: [run('10')], nextCursor: 'cursor-2' } }),
      getRun: vi.fn(), subscribe: vi.fn(() => vi.fn()),
    }
    const scope = effectScope()
    const state = scope.run(() => useTeamRuns(ref('lead'), { dependencies }))!
    await flush()
    expect(state.runs.value.map(item => item.id)).toEqual(['10'])
    scope.stop()
  })
  it('hydrates by conversation, de-duplicates runs, and subscribes once per team', async () => {
    let onEvent: ((event: TeamBoardEvent) => void) | undefined
    const cleanup = vi.fn()
    const dependencies: TeamRunsDependencies = {
      listByConversation: vi.fn().mockResolvedValue({ data: [run('10'), run('10'), run('11')] }),
      getRun: vi.fn(),
      subscribe: vi.fn((_teamId, callback) => { onEvent = callback; return cleanup }),
    }
    const conversationId = ref('lead')
    const scope = effectScope()
    const state = scope.run(() => useTeamRuns(conversationId, { dependencies }))!

    await flush()

    expect(state.runs.value.map(item => item.id)).toEqual(['10', '11'])
    expect(dependencies.subscribe).toHaveBeenCalledTimes(1)
    expect(onEvent).toBeTypeOf('function')
    scope.stop()
    expect(cleanup).toHaveBeenCalledOnce()
  })

  it('merges a stream projection immediately and replaces it with refreshed detail', async () => {
    let onEvent: ((event: TeamBoardEvent) => void) | undefined
    const completed = run('10', 'team-1', 'completed')
    const dependencies: TeamRunsDependencies = {
      listByConversation: vi.fn().mockResolvedValue({ data: [run('10')] }),
      getRun: vi.fn().mockResolvedValue({ data: completed }),
      subscribe: vi.fn((_teamId, callback) => { onEvent = callback; return vi.fn() }),
    }
    const scope = effectScope()
    const state = scope.run(() => useTeamRuns(ref('lead'), { dependencies }))!
    await flush()

    onEvent!({ event: 'team_run_completed', data: {
      runId: '10', status: 'completed', progress: completed.progress,
    } })
    expect(state.runs.value[0].status).toBe('completed')
    await flush()
    expect(dependencies.getRun).toHaveBeenCalledWith('10')
    expect(state.runs.value[0]).toEqual(completed)
    scope.stop()
  })

  it('coalesces duplicate run events while a detail refresh is in flight', async () => {
    let onEvent: ((event: TeamBoardEvent) => void) | undefined
    let resolveDetail!: (value: { data: TeamRun }) => void
    const detail = new Promise<{ data: TeamRun }>(resolve => { resolveDetail = resolve })
    const dependencies: TeamRunsDependencies = {
      listByConversation: vi.fn().mockResolvedValue({ data: [run('10')] }),
      getRun: vi.fn().mockReturnValue(detail),
      subscribe: vi.fn((_teamId, callback) => { onEvent = callback; return vi.fn() }),
    }
    const scope = effectScope()
    scope.run(() => useTeamRuns(ref('lead'), { dependencies }))
    await flush()

    onEvent!({ id: '1', event: 'team_run_progress', data: { runId: '10' } })
    onEvent!({ id: '1', event: 'team_run_progress', data: { runId: '10' } })
    expect(dependencies.getRun).toHaveBeenCalledTimes(1)
    resolveDetail({ data: run('10') })
    await flush()
    scope.stop()
  })

  it('ignores team events belonging to another lead conversation', async () => {
    let onEvent: ((event: TeamBoardEvent) => void) | undefined
    const dependencies: TeamRunsDependencies = {
      listByConversation: vi.fn().mockResolvedValue({ data: [run('10')] }),
      getRun: vi.fn(),
      subscribe: vi.fn((_teamId, callback) => { onEvent = callback; return vi.fn() }),
    }
    const scope = effectScope()
    scope.run(() => useTeamRuns(ref('lead'), { dependencies }))
    await flush()

    onEvent!({ event: 'team_run_started', data: {
      runId: '99', leadConversationId: 'another-lead',
    } })
    await flush()

    expect(dependencies.getRun).not.toHaveBeenCalled()
    scope.stop()
  })

  it('loads a deep-linked run and cleans up old subscriptions on conversation changes', async () => {
    const cleanups = [vi.fn(), vi.fn()]
    const dependencies: TeamRunsDependencies = {
      listByConversation: vi.fn()
        .mockResolvedValueOnce({ data: [] })
        .mockResolvedValueOnce({ data: [run('20', 'team-2')] }),
      getRun: vi.fn().mockResolvedValue({ data: run('10') }),
      subscribe: vi.fn()
        .mockImplementationOnce(() => cleanups[0])
        .mockImplementationOnce(() => cleanups[1]),
    }
    const conversationId = ref('worker')
    const linkedRunId = ref<string | undefined>('10')
    const scope = effectScope()
    const state = scope.run(() => useTeamRuns(conversationId, { linkedRunId, dependencies }))!
    await flush()

    expect(state.runs.value.map(item => item.id)).toEqual(['10'])
    conversationId.value = 'lead-2'
    linkedRunId.value = undefined
    await flush()
    expect(cleanups[0]).toHaveBeenCalledOnce()
    expect(state.runs.value.map(item => item.id)).toEqual(['20'])
    scope.stop()
    expect(cleanups[1]).toHaveBeenCalledOnce()
  })

  it('validates a linked run with getRun even when conversation listing fails', async () => {
    const dependencies: TeamRunsDependencies = {
      listByConversation: vi.fn().mockRejectedValue(new Error('conversation runs unavailable')),
      getRun: vi.fn().mockResolvedValue({ data: run('10') }),
      subscribe: vi.fn(() => vi.fn()),
    }
    const scope = effectScope()
    const state = scope.run(() => useTeamRuns(ref('worker'), {
      linkedRunId: ref('10'), dependencies,
    }))!
    await flush()

    expect(dependencies.getRun).toHaveBeenCalledWith('10')
    expect(state.runs.value.map(item => item.id)).toEqual(['10'])
    scope.stop()
  })

  it('isolates deferred detail requests and subscription callbacks by conversation generation', async () => {
    const callbacks: Array<(event: TeamBoardEvent) => void> = []
    let resolveOld!: (value: { data: TeamRun }) => void
    let resolveFresh!: (value: { data: TeamRun }) => void
    const oldDetail = new Promise<{ data: TeamRun }>(resolve => { resolveOld = resolve })
    const freshDetail = new Promise<{ data: TeamRun }>(resolve => { resolveFresh = resolve })
    const dependencies: TeamRunsDependencies = {
      listByConversation: vi.fn().mockImplementation((conversationId: string) =>
        Promise.resolve({ data: [run('10', conversationId === 'A' ? 'team-a' : 'team-b')] })),
      getRun: vi.fn()
        .mockReturnValueOnce(oldDetail)
        .mockReturnValueOnce(freshDetail),
      subscribe: vi.fn((_teamId, callback) => {
        callbacks.push(callback)
        return vi.fn()
      }),
    }
    const conversationId = ref('A')
    const scope = effectScope()
    const state = scope.run(() => useTeamRuns(conversationId, { dependencies }))!
    await flush()

    callbacks[0]({ event: 'team_run_progress', data: { runId: '10' } })
    conversationId.value = 'B'
    await flush()
    callbacks[0]({ event: 'team_run_completed', data: { runId: '10', status: 'completed' } })
    expect(state.runs.value[0].status).toBe('running')

    conversationId.value = 'A'
    await flush()
    callbacks[2]({ event: 'team_run_progress', data: { runId: '10' } })
    expect(dependencies.getRun).toHaveBeenCalledTimes(2)

    resolveOld({ data: run('10', 'team-a', 'failed') })
    await flush()
    expect(state.runs.value[0].status).toBe('running')
    resolveFresh({ data: run('10', 'team-a', 'completed') })
    await flush()
    expect(state.runs.value[0].status).toBe('completed')
    scope.stop()
  })
})
