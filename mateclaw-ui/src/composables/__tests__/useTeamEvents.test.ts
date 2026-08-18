import { describe, expect, it, vi } from 'vitest'
import {
  parseTeamSseFrames,
  subscribeTeamEvents,
  type TeamBoardEvent,
} from '@/composables/useTeamEvents'

function response(body: string): Response {
  const bytes = new TextEncoder().encode(body)
  let delivered = false
  return {
    ok: true,
    body: {
      getReader: () => ({
        read: async () => {
          if (delivered) return { done: true, value: undefined }
          delivered = true
          return { done: false, value: bytes }
        },
      }),
    },
  } as unknown as Response
}

function dependencies(fetchImpl: typeof fetch) {
  const timers: Array<{ callback: () => void; delay: number }> = []
  return {
    timers,
    options: {
      fetchImpl,
      storage: { getItem: () => null },
      retryBaseMs: 100,
      retryMaxMs: 1_000,
      setTimeoutImpl: (callback: () => void, delay: number) => {
        const timer = { callback, delay }
        timers.push(timer)
        return timer
      },
      clearTimeoutImpl: vi.fn(),
    },
  }
}

describe('parseTeamSseFrames', () => {
  it('parses CRLF frames with ids and multiline data while retaining partial input', () => {
    const parsed = parseTeamSseFrames(
      'id: 9007199254740993\r\nevent: team_run_progress\r\n'
      + 'data: first line\r\ndata: second line\r\n\r\nid: 2\r\ndata: partial',
    )

    expect(parsed.frames).toEqual([{
      id: '9007199254740993',
      event: 'team_run_progress',
      data: 'first line\nsecond line',
    }])
    expect(parsed.remainder).toBe('id: 2\r\ndata: partial')
  })
})

describe('subscribeTeamEvents', () => {
  it('reconnects with Last-Event-ID and de-duplicates replayed ids', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response('id: 7\nevent: team_task_progress\ndata: {"runId":"10","taskId":"101","step":1}\n\n'))
      .mockResolvedValueOnce(response(
        'id: 7\nevent: team_task_progress\ndata: {"runId":"10","taskId":"101","step":1}\n\n'
        + 'id: 8\r\nevent: team_run_progress\r\ndata: {"runId":"10","step":2}\r\n\r\n',
      )) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const events: Array<{ id?: string; event: string }> = []
    const stop = subscribeTeamEvents('9007199254740995', event => events.push(event), options)

    await vi.waitFor(() => expect(timers).toHaveLength(1))
    timers.shift()!.callback()
    await vi.waitFor(() => expect(events).toHaveLength(2))

    expect(events.map(event => event.id)).toEqual(['7', '8'])
    const secondRequest = vi.mocked(fetchImpl).mock.calls[1][1] as RequestInit
    expect(secondRequest.headers).toMatchObject({ 'Last-Event-ID': '7' })
    stop()
  })

  it('merges the same run event across three replay paths exactly once', async () => {
    const replay = 'id: 77\nevent: team_task_completed\ndata: {"runId":"10","taskId":"101"}\n\n'
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(replay))
      .mockResolvedValueOnce(response(replay))
      .mockResolvedValueOnce(response(replay)) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const events: Array<{ id?: string; event: string }> = []
    const stop = subscribeTeamEvents('team-1', event => events.push(event), options)

    await vi.waitFor(() => expect(timers).toHaveLength(1))
    timers.shift()!.callback()
    await vi.waitFor(() => expect(timers).toHaveLength(1))
    timers.shift()!.callback()
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(3))

    expect(events).toHaveLength(1)
    stop()
  })

  it('deduplicates one action mirrored through parent worker and team streams', async () => {
    const fetchImpl = vi.fn().mockResolvedValueOnce(response(
      'id: 1\nevent: team_task_in_review\ndata: {"runId":"10","taskId":"101","actionId":"a7","conversationId":"lead"}\n\n'
      + 'id: 2\nevent: team_task_in_review\ndata: {"runId":"10","taskId":"101","actionId":"a7","conversationId":"worker"}\n\n'
      + 'id: 3\nevent: team_task_in_review\ndata: {"runId":"10","taskId":"101","actionId":"a7"}\n\n',
    )) as unknown as typeof fetch
    const { options } = dependencies(fetchImpl)
    const events: string[] = []
    const stop = subscribeTeamEvents('team-1', event => events.push(String(event.data.conversationId ?? 'team')), options)

    await vi.waitFor(() => expect(events).toEqual(['lead']))
    stop()
  })

  it('delivers lifecycle transitions that share an action id', async () => {
    const fetchImpl = vi.fn().mockResolvedValueOnce(response(
      'id: 1\nevent: team_task_approval_required\ndata: {"runId":"10","taskId":"101","actionId":"a7","conversationId":"lead"}\n\n'
      + 'id: 2\nevent: team_task_completed\ndata: {"runId":"10","taskId":"101","actionId":"a7","conversationId":"worker"}\n\n',
    )) as unknown as typeof fetch
    const { options } = dependencies(fetchImpl)
    const events: string[] = []
    const stop = subscribeTeamEvents('team-1', event => events.push(event.event), options)

    await vi.waitFor(() => expect(events).toEqual([
      'team_task_approval_required',
      'team_task_completed',
    ]))
    stop()
  })

  it('does not merge different actions or conversation-scoped stream events', async () => {
    const fetchImpl = vi.fn().mockResolvedValueOnce(response(
      'id: 7\nevent: team_task_in_review\ndata: {"actionId":"a1","conversationId":"lead"}\n\n'
      + 'id: 8\nevent: team_task_in_review\ndata: {"actionId":"a2","conversationId":"lead"}\n\n'
      + 'id: 9\nevent: team_task_progress\ndata: {"conversationId":"lead"}\n\n'
      + 'id: 9\nevent: team_task_progress\ndata: {"conversationId":"worker"}\n\n',
    )) as unknown as typeof fetch
    const { options } = dependencies(fetchImpl)
    const events: string[] = []
    const stop = subscribeTeamEvents('team-1', event => events.push(`${event.data.actionId ?? 'progress'}:${event.data.conversationId}`), options)

    await vi.waitFor(() => expect(events).toEqual(['a1:lead', 'a2:lead', 'progress:lead', 'progress:worker']))
    stop()
  })

  it('does not merge unscoped actions that reuse an action id on different stream events', async () => {
    const fetchImpl = vi.fn().mockResolvedValueOnce(response(
      'id: 7\nevent: team_task_in_review\ndata: {"actionId":"local-1"}\n\n'
      + 'id: 8\nevent: team_task_in_review\ndata: {"actionId":"local-1"}\n\n',
    )) as unknown as typeof fetch
    const { options } = dependencies(fetchImpl)
    const ids: string[] = []
    const stop = subscribeTeamEvents('team-1', event => ids.push(event.id!), options)

    await vi.waitFor(() => expect(ids).toEqual(['7', '8']))
    stop()
  })

  it('drops an oversized incomplete remainder without losing preceding complete frames', async () => {
    const fetchImpl = vi.fn().mockResolvedValueOnce(response(
      `id: 1\nevent: team_run_progress\ndata: {"runId":"1"}\n\ndata: ${'x'.repeat(2_000)}`,
    )) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const events: TeamBoardEvent[] = []
    const stop = subscribeTeamEvents('team-1', event => events.push(event), { ...options, maxBufferBytes: 1_024 })

    await vi.waitFor(() => expect(timers).toHaveLength(1))
    expect(events.map(event => event.id)).toEqual(['1'])
    stop()
  })

  it('accepts a large network chunk made of complete bounded frames', async () => {
    const frames = Array.from({ length: 30 }, (_, index) =>
      `id: ${index}\nevent: team_run_progress\ndata: {"runId":"${index}","detail":"${'x'.repeat(80)}"}\n\n`,
    ).join('')
    const fetchImpl = vi.fn().mockResolvedValueOnce(response(frames)) as unknown as typeof fetch
    const { options } = dependencies(fetchImpl)
    const events: TeamBoardEvent[] = []
    const stop = subscribeTeamEvents('team-1', event => events.push(event), { ...options, maxBufferBytes: 1_024 })

    await vi.waitFor(() => expect(events).toHaveLength(30))
    stop()
  })

  it('recovers on the same stream after discarding an oversized incomplete remainder', async () => {
    const chunks = [
      new TextEncoder().encode(`data: ${'x'.repeat(2_000)}`),
      new TextEncoder().encode('discarded tail\n\nid: 2\nevent: team_run_progress\ndata: {"runId":"2"}\n\n'),
    ]
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => ({ read: () => chunks.length
        ? Promise.resolve({ done: false, value: chunks.shift()! })
        : new Promise(() => {}) }) },
    } as unknown as Response) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const ids: string[] = []
    const stop = subscribeTeamEvents('team-1', event => ids.push(event.id!), { ...options, maxBufferBytes: 1_024 })

    await vi.waitFor(() => expect(ids).toEqual(['2']))
    expect(timers).toHaveLength(0)
    expect(fetchImpl).toHaveBeenCalledOnce()
    stop()
  })

  it('does not dispatch a read that resolves after the subscription stops', async () => {
    let resolveRead!: (value: ReadableStreamReadResult<Uint8Array>) => void
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => ({ read: () => new Promise(resolve => { resolveRead = resolve }) }) },
    } as unknown as Response) as unknown as typeof fetch
    const { options } = dependencies(fetchImpl)
    const onEvent = vi.fn()
    const stop = subscribeTeamEvents('team-1', onEvent, options)
    await vi.waitFor(() => expect(resolveRead).toBeTypeOf('function'))

    stop()
    resolveRead({ done: false, value: new TextEncoder().encode('id: 9\nevent: team_run_progress\ndata: {"runId":"10"}\n\n') })
    await Promise.resolve()

    expect(onEvent).not.toHaveBeenCalled()
  })

  it('does not merge equal event ids that belong to different runs', async () => {
    const fetchImpl = vi.fn().mockResolvedValueOnce(response(
      'id: 77\nevent: team_run_progress\ndata: {"runId":"10"}\n\n'
      + 'id: 77\nevent: team_run_progress\ndata: {"runId":"11"}\n\n',
    )) as unknown as typeof fetch
    const { options } = dependencies(fetchImpl)
    const runIds: string[] = []
    const stop = subscribeTeamEvents('team-1', event => runIds.push(String(event.data.runId)), options)

    await vi.waitFor(() => expect(runIds).toEqual(['10', '11']))
    stop()
  })

  it('does not globally merge compatibility events without an event id', async () => {
    const frame = 'event: team_task_progress\ndata: {"runId":"10","taskId":"101"}\n\n'
    const fetchImpl = vi.fn().mockResolvedValueOnce(response(frame + frame)) as unknown as typeof fetch
    const { options } = dependencies(fetchImpl)
    const events: string[] = []
    const stop = subscribeTeamEvents('team-1', event => events.push(event.event), options)

    await vi.waitFor(() => expect(events).toHaveLength(2))
    stop()
  })

  it('deduplicates a reconnect replay by stream id when run id is absent', async () => {
    const replay = 'id: 88\nevent: workspace_status\ndata: {"status":"ready"}\n\n'
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(replay))
      .mockResolvedValueOnce(response(replay)) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const events: string[] = []
    const stop = subscribeTeamEvents('team-1', event => events.push(event.event), options)

    await vi.waitFor(() => expect(timers).toHaveLength(1))
    timers.shift()!.callback()
    await vi.waitFor(() => expect(timers).toHaveLength(1))

    expect(events).toEqual(['workspace_status'])
    stop()
  })

  it('uses exponential backoff for consecutive disconnects', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new Error('offline')) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const stop = subscribeTeamEvents('10', vi.fn(), options)

    await vi.waitFor(() => expect(timers.map(timer => timer.delay)).toEqual([100]))
    timers[0].callback()
    await vi.waitFor(() => expect(timers.map(timer => timer.delay)).toEqual([100, 200]))
    timers[1].callback()
    await vi.waitFor(() => expect(timers.map(timer => timer.delay)).toEqual([100, 200, 400]))
    stop()
  })

  it('delivers a higher id from a recreated server stream', async () => {
    const highId = '1850000000000000000'
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response('id: 7\nevent: team_task_progress\ndata: {"step":1}\n\n'))
      .mockResolvedValueOnce(response(
        `id: ${highId}\nevent: team_run_progress\ndata: {"step":2}\n\n`,
      )) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const ids: string[] = []
    const stop = subscribeTeamEvents('10', event => ids.push(event.id!), options)

    await vi.waitFor(() => expect(timers).toHaveLength(1))
    timers.shift()!.callback()
    await vi.waitFor(() => expect(ids).toEqual(['7', highId]))

    stop()
  })

  it('bounds the seen id cache without moving Last-Event-ID backwards', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(
        'id: 100\nevent: update\ndata: {"step":1}\n\n'
        + 'id: 101\nevent: update\ndata: {"step":2}\n\n'
        + 'id: 102\nevent: update\ndata: {"step":3}\n\n'
        + 'id: 100\nevent: update\ndata: {"step":4}\n\n',
      ))
      .mockResolvedValueOnce(response('')) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const ids: string[] = []
    const stop = subscribeTeamEvents('10', event => ids.push(event.id!), {
      ...options,
      seenEventLimit: 2,
    })

    await vi.waitFor(() => expect(timers).toHaveLength(1))
    expect(ids).toEqual(['100', '101', '102', '100'])
    timers.shift()!.callback()
    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2))

    const secondRequest = vi.mocked(fetchImpl).mock.calls[1][1] as RequestInit
    expect(secondRequest.headers).toMatchObject({ 'Last-Event-ID': '102' })
    stop()
  })

  it('does not reconnect after aborting an active request', async () => {
    const fetchImpl = vi.fn((_url: string | URL | Request, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
      })) as unknown as typeof fetch
    const { timers, options } = dependencies(fetchImpl)
    const stop = subscribeTeamEvents('10', vi.fn(), options)

    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledOnce())
    stop()
    await Promise.resolve()

    expect(timers).toHaveLength(0)
    expect(fetchImpl).toHaveBeenCalledOnce()
  })
})
