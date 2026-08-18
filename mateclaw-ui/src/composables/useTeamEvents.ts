import { isHigherSseEventId, RecentSseEventIds } from './sseEventIds'
import { canonicalTeamEventKey } from './chat/teamEventOwnership'

/** One parsed SSE frame before JSON decoding. */
export interface TeamSseFrame {
  id?: string
  event: string
  data: string
}

export interface TeamSseParseResult {
  frames: TeamSseFrame[]
  remainder: string
}

/** Parse all complete SSE frames and retain the incomplete trailing bytes. */
export function parseTeamSseFrames(input: string): TeamSseParseResult {
  const frames: TeamSseFrame[] = []
  let remainder = input

  for (;;) {
    const separator = /\r\n\r\n|\n\n|\r\r/.exec(remainder)
    if (!separator || separator.index == null) break
    const rawFrame = remainder.slice(0, separator.index)
    remainder = remainder.slice(separator.index + separator[0].length)

    let event = 'message'
    let id: string | undefined
    const data: string[] = []
    for (const line of rawFrame.split(/\r\n|\r|\n/)) {
      if (!line || line.startsWith(':')) continue
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      let value = colon < 0 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') event = value || 'message'
      else if (field === 'data') data.push(value)
      else if (field === 'id' && !value.includes('\0')) id = value
    }
    frames.push({ ...(id === undefined ? {} : { id }), event, data: data.join('\n') })
  }

  return { frames, remainder }
}

export interface TeamBoardEvent {
  id?: string
  event: string
  data: Record<string, unknown>
}

export interface TeamEventSubscriptionOptions {
  fetchImpl?: typeof fetch
  storage?: Pick<Storage, 'getItem'>
  retryBaseMs?: number
  retryMaxMs?: number
  seenEventLimit?: number
  maxBufferBytes?: number
  setTimeoutImpl?: (callback: () => void, delay: number) => unknown
  clearTimeoutImpl?: (handle: unknown) => void
}

/** Subscribe to team events with resumable, de-duplicated SSE reconnection. */
export function subscribeTeamEvents(
  teamId: string,
  onEvent: (event: TeamBoardEvent) => void,
  options: TeamEventSubscriptionOptions = {},
): () => void {
  const fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis)
  const storage = options.storage ?? localStorage
  const retryBaseMs = options.retryBaseMs ?? 1_000
  const retryMaxMs = options.retryMaxMs ?? 30_000
  const maxBufferBytes = Math.max(1_024, options.maxBufferBytes ?? 1_048_576)
  const setTimeoutImpl = options.setTimeoutImpl
    ?? ((callback, delay) => globalThis.setTimeout(callback, delay))
  const clearTimeoutImpl = options.clearTimeoutImpl
    ?? (handle => globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>))

  let stopped = false
  let controller: AbortController | null = null
  let retryTimer: unknown
  let retryAttempt = 0
  let lastEventId: string | undefined
  const seenEventIds = new RecentSseEventIds(options.seenEventLimit)

  const scheduleReconnect = () => {
    if (stopped) return
    const delay = Math.min(retryBaseMs * (2 ** retryAttempt), retryMaxMs)
    retryAttempt += 1
    retryTimer = setTimeoutImpl(() => {
      retryTimer = undefined
      void connect()
    }, delay)
  }

  const dispatchFrames = (frames: TeamSseFrame[]) => {
    for (const frame of frames) {
      if (stopped) return
      if (frame.id !== undefined) {
        if (isHigherSseEventId(frame.id, lastEventId)) lastEventId = frame.id
      }
      if (frame.event === 'heartbeat' || !frame.data) continue
      try {
        const event: TeamBoardEvent = {
          ...(frame.id === undefined ? {} : { id: frame.id }),
          event: frame.event,
          data: JSON.parse(frame.data) as Record<string, unknown>,
        }
        const canonicalKey = canonicalTeamEventKey(event)
        if (canonicalKey && seenEventIds.has(canonicalKey)) continue
        if (canonicalKey) seenEventIds.add(canonicalKey)
        onEvent(event)
        retryAttempt = 0
      } catch {
        // Ignore malformed or non-JSON board events.
      }
    }
  }

  const connect = async () => {
    if (stopped) return
    const activeController = new AbortController()
    controller = activeController
    try {
      const headers: Record<string, string> = { Accept: 'text/event-stream' }
      const token = storage.getItem('token')
      const workspaceId = storage.getItem('mc-workspace-id')
      if (token) headers.Authorization = `Bearer ${token}`
      if (workspaceId) headers['X-Workspace-Id'] = workspaceId
      if (lastEventId !== undefined) headers['Last-Event-ID'] = lastEventId

      const response = await fetchImpl(`/api/v1/teams/${teamId}/events`, {
        headers,
        signal: activeController.signal,
      })
      if (!response.ok || !response.body) throw new Error('Team event stream unavailable')

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let discardingOversizedFrame = false
      let discardBoundaryTail = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (stopped) break
        if (done) {
          buffer += decoder.decode()
          const parsed = parseTeamSseFrames(buffer)
          dispatchFrames(parsed.frames)
          break
        }
        let chunk = decoder.decode(value, { stream: true })
        if (discardingOversizedFrame) {
          const discardInput = discardBoundaryTail + chunk
          const separator = /\r\n\r\n|\n\n|\r\r/.exec(discardInput)
          if (!separator || separator.index == null) {
            discardBoundaryTail = discardInput.slice(-3)
            continue
          }
          chunk = discardInput.slice(separator.index + separator[0].length)
          discardingOversizedFrame = false
          discardBoundaryTail = ''
        }
        buffer += chunk
        const parsed = parseTeamSseFrames(buffer)
        buffer = parsed.remainder
        dispatchFrames(parsed.frames)
        if (buffer.length > maxBufferBytes) {
          discardBoundaryTail = buffer.slice(-3)
          buffer = ''
          discardingOversizedFrame = true
        }
      }
    } catch {
      // A dropped stream follows the same reconnect path as a clean EOF.
    } finally {
      if (controller === activeController) controller = null
    }
    scheduleReconnect()
  }

  void connect()

  return () => {
    stopped = true
    controller?.abort()
    controller = null
    if (retryTimer !== undefined) {
      clearTimeoutImpl(retryTimer)
      retryTimer = undefined
    }
  }
}
