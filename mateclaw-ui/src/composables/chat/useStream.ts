/**
 * SSE 流处理 Composable
 * 参考 @agentscope-ai/chat 的 Stream 实现，提供标准的 SSE 解析
 */
import { ref, computed } from 'vue'
import { handleAuthFailure, updateTokenFromHeader } from '@/utils/auth'
import { classifyHttpError, classifyNetworkError, type ChatErrorInfo } from '@/types/chatError'

export type SSEEventType =
  | 'content_delta'
  | 'thinking_delta'
  | 'message_complete'
  | 'done'
  | 'error'
  | 'session'
  | 'message_start'
  // Agent 事件
  | 'tool_call_started'
  | 'tool_call_completed'
  | 'phase'
  | 'plan_created'
  | 'plan_step_started'
  | 'plan_step_completed'
  // 审批事件
  | 'tool_approval_requested'
  | 'tool_approval_resolved'
  // 恢复/警告事件
  | 'warning'
  // Interrupt + Queue 事件
  | 'heartbeat'
  | 'turn_interrupt_requested'
  | 'turn_interrupted'
  | 'queued_input_accepted'
  | 'queued_input_started'
  // 异步任务事件
  | 'async_task_progress'
  | 'async_task_completed'
  // TTS 事件
  | 'tts_ready'
  // 浏览器执行事件
  | 'browser_action'
  // Agent 委派事件
  | 'delegation_start'
  | 'delegation_progress'
  | 'delegation_end'
  | 'delegation_child_complete'
  // Persistent goal events (RFC 48) — emitted by GoalEvaluationNode
  | 'goal_evaluated'
  | 'goal_followup'
  | 'goal_completed'
  | 'goal_exhausted'
  // Tool-side goal mutations (GoalManagementTool) — emitted when the
  // agent invokes setGoal / addGoalCriterion so the store can refresh
  // without a full page reload.
  | 'goal_created'
  | 'goal_updated'
  // Stream lifecycle + per-iteration boundaries (single-turn UX overhaul).
  // The parser handles arbitrary `event:` lines via parseEvent — these names
  // exist in the union purely so TypeScript callers can register handlers
  // with a typed `on(event, handler)` signature.
  | 'stream_started'
  | 'context_prepared'
  | 'llm_request_sent'
  | 'thinking_start'
  | 'thinking_end'
  | 'iteration_start'
  | 'iteration_end'
  | 'content_truncated'
  | 'tool_result_chunk'
  // Recovery affordance for non-transient errors (ERROR_FALLBACK turns)
  | 'feedback_event'
  // Context compaction lifecycle. Fired by ConversationWindowManager
  // around each compaction pass so the UI can show a progress chip
  // (start → pair_safe → summarize → done/skipped/failed). Payload
  // carries preTokens/postTokens/messagesSummarized/tailKept/etc.
  | 'compact_status'

export interface SSEEvent {
  type: SSEEventType
  data: any
  /**
   * Server-assigned per-conversation monotonic id (the SSE protocol's
   * native `id:` line). Frontend de-dupes by this value so a reconnect
   * replay doesn't double-process events the client already saw.
   * Absent on legacy events from servers that don't stamp ids.
   */
  id?: string
}

export interface UseStreamOptions {
  /** API 端点 */
  url: string
  /** 请求方法 */
  method?: 'POST' | 'GET'
  /** 请求头 */
  headers?: Record<string, string>
  /** 请求体 */
  body?: any
  /** 是否自动开始 */
  autoStart?: boolean
}

export interface UseStreamReturn {
  /** 是否连接中 */
  isConnected: import('vue').Ref<boolean>
  /** 是否正在接收数据 */
  isReceiving: import('vue').Ref<boolean>
  /** 当前错误 */
  error: import('vue').Ref<Error | null>
  /**
   * Highest SSE event id seen so far this connection. Pass back to the
   * server as {@code lastEventId} on reconnect to skip already-delivered
   * events and avoid duplicate handler dispatch.
   */
  lastEventId: import('vue').Ref<string | null>
  /** 连接流 */
  connect: (body?: any) => Promise<void>
  /** 断开连接 */
  disconnect: () => void
  /** 中止请求 */
  abort: () => void
  /** 注册事件处理器 */
  on: (event: SSEEventType, handler: (data: any) => void) => () => void
  /** 注册所有事件处理器 */
  onEvent: (handler: (event: SSEEvent) => void) => () => void
}

// SSE 解析器 - 使用 TransformStream 风格
class SSEParser {
  private buffer = ''
  private readonly separator = '\n\n'

  parse(chunk: string): SSEEvent[] {
    this.buffer += chunk
    const events: SSEEvent[] = []
    
    // 分割事件块
    const parts = this.buffer.split(this.separator)
    
    // 保留最后一个不完整的部分
    this.buffer = parts.pop() || ''
    
    // 处理完整的事件块
    for (const part of parts) {
      const event = this.parseEvent(part)
      if (event) {
        events.push(event)
      }
    }
    
    return events
  }

  flush(): SSEEvent[] {
    if (!this.buffer.trim()) return []
    const event = this.parseEvent(this.buffer)
    this.buffer = ''
    return event ? [event] : []
  }

  private parseEvent(part: string): SSEEvent | null {
    const lines = part.split('\n')
    let eventType: SSEEventType = 'content_delta'
    let data: any = {}
    let hasData = false
    let eventId: string | undefined

    for (const line of lines) {
      if (!line.trim()) continue

      const colonIndex = line.indexOf(':')
      if (colonIndex === -1) continue

      const key = line.slice(0, colonIndex).trim()
      const value = line.slice(colonIndex + 1).trim()

      if (key === 'event') {
        eventType = value as SSEEventType
      } else if (key === 'id') {
        eventId = value
      } else if (key === 'data') {
        hasData = true
        try {
          data = JSON.parse(value)
        } catch {
          data = value
        }
      }
    }

    if (!hasData) return null
    return eventId !== undefined
      ? { type: eventType, data, id: eventId }
      : { type: eventType, data }
  }
}

export function useStream(options: UseStreamOptions): UseStreamReturn {
  const { url, method = 'POST', headers = {}, autoStart = false } = options

  const isConnected = ref(false)
  const isReceiving = ref(false)
  const error = ref<Error | null>(null)

  let abortController: AbortController | null = null
  let parser = new SSEParser()
  let streamTimeoutTimer: ReturnType<typeof setTimeout> | null = null
  // 提高到 120 秒（后端心跳每 10 秒一次，任何心跳都会重置此计时器）
  const STREAM_TIMEOUT_MS = 120_000
  
  // 事件处理器存储
  const eventHandlers = new Map<SSEEventType, Set<(data: any) => void>>()
  const globalHandlers = new Set<(event: SSEEvent) => void>()

  /**
   * Largest server event id seen on this stream. Echoed back in the
   * `lastEventId` request body field on reconnect so the server can
   * skip events the client has already processed.
   */
  const lastEventId = ref<string | null>(null)

  /**
   * Set of event ids already dispatched to handlers this connection.
   * Reconnect replays the same id'd events; without this set, handlers
   * fire twice and `iteration_start` / `thinking_delta` end up with
   * the wrong segment ordering. Cleared on connect() / disconnect().
   */
  const seenEventIds = new Set<string>()

  // 触发事件
  const emit = (event: SSEEvent) => {
    // De-dup by server-assigned id. Events without an id (legacy / heartbeat)
    // bypass — they're either idempotent or carry their own dedup logic.
    if (event.id) {
      if (seenEventIds.has(event.id)) {
        return
      }
      seenEventIds.add(event.id)
      // Track highest id for reconnect Last-Event-ID echo. SSE event ids are
      // per-conversation sequential counters issued by ChatStreamTracker — not
      // Snowflake — so coercing through Number is safe within JS's 2^53 ceiling
      // (a single conversation would need 9 quadrillion events to overflow).
      const incoming = Number(event.id) // snowflake-precision-ok: SSE sequence counter
      const current = lastEventId.value === null ? -1 : Number(lastEventId.value) // snowflake-precision-ok: SSE sequence counter
      if (!Number.isNaN(incoming) && incoming > current) {
        lastEventId.value = event.id
      }
    }

    // 全局处理器
    globalHandlers.forEach(handler => {
      try {
        handler(event)
      } catch (e) {
        console.error('Stream event handler error:', e)
      }
    })

    // 特定类型处理器
    const handlers = eventHandlers.get(event.type)
    if (handlers) {
      handlers.forEach(handler => {
        try {
          handler(event.data)
        } catch (e) {
          console.error('Stream event handler error:', e)
        }
      })
    }
  }

  // 处理 SSE 数据
  const processChunk = (chunk: string) => {
    const events = parser.parse(chunk)
    events.forEach(emit)
  }

  // 连接流
  // Conversation last seen by the dedup state. SSE ids are per-conversation
  // on the server, so a lastEventId carried over from a different conv would
  // mass-skip valid events on the new conv's reconnect (e.g. user processed
  // events 1..1000 in conv A, then reconnects to conv B which has events
  // 1..50 — the server filter `id <= 1000` would drop EVERY event in B).
  let lastDedupConversationId: string | null = null

  const connect = async (body?: any) => {
    // 断开已有连接
    disconnect()

    parser = new SSEParser()
    error.value = null

    const incomingConv = body?.conversationId ?? null
    const isReconnect = !!body?.reconnect
    // Reset dedup state when:
    //   - Fresh stream (not a reconnect) — always clears, matches the
    //     pre-fix behavior for normal sends.
    //   - Reconnect targeting a DIFFERENT conversation than the one whose
    //     ids are in our state — the per-conversation server semantics
    //     make a cross-conv lastEventId actively harmful.
    // Reconnect to the SAME conversation preserves dedup state so the
    // server can skip already-seen events on replay.
    const sameConv = isReconnect && lastDedupConversationId === incomingConv
    if (!sameConv) {
      seenEventIds.clear()
      lastEventId.value = null
    }
    lastDedupConversationId = incomingConv

    // Own the lastEventId injection here. Callers must NOT put their own
    // lastEventId in the body — connect() is the only layer that knows
    // whether the dedup state still applies to this conversation. Reading
    // a stale `stream.lastEventId.value` from outside (and embedding it
    // in the body before this point) would fail to clear after a conv
    // switch. We only inject when the dedup state is still relevant
    // (sameConv) AND we actually have an id to echo.
    if (sameConv && lastEventId.value !== null && body && body.reconnect) {
      const numericId = Number(lastEventId.value) // snowflake-precision-ok: SSE sequence counter
      if (!Number.isNaN(numericId)) {
        body = { ...body, lastEventId: numericId }
      }
    } else if (body && 'lastEventId' in body) {
      // Defensive: strip any caller-provided lastEventId so a refactor
      // can't reintroduce the cross-conv bug.
      const { lastEventId: _, ...rest } = body
      body = rest
    }
    
    try {
      abortController = new AbortController()
      
      // 自动从 localStorage 读取 token（与 http.ts 拦截器保持一致）
      const authHeaders: Record<string, string> = {}
      const token = localStorage.getItem('token')
      if (token) {
        authHeaders['Authorization'] = `Bearer ${token}`
      }

      const response = await fetch(url, {
        method,
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream',
          ...authHeaders,
          ...headers,
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: abortController.signal,
      })

      if (!response.ok) {
        // 尝试解析错误响应体（后端返回 JSON 格式错误信息）
        let errorMsg = `HTTP ${response.status}: ${response.statusText}`
        let errorBody: any = null
        try {
          errorBody = await response.json()
          if (errorBody.msg || errorBody.message) {
            errorMsg = errorBody.msg || errorBody.message
          }
        } catch {
          // 非 JSON 响应，使用默认错误信息
        }
        // 构建结构化错误信息
        const errorInfo = classifyHttpError(response.status, errorBody)
        errorInfo.requestId = response.headers.get('X-Request-Id')
          || errorBody?.requestId
          || errorInfo.requestId
        // 401 = authentication failure → log out (consistent with http.ts).
        // 403 = authorization failure (e.g. workspace permission denied) → keep session.
        if (response.status === 401) {
          handleAuthFailure()
        }
        throw Object.assign(new Error(errorMsg), { errorInfo })
      }

      // 滑动窗口续期：从响应头获取新 Token
      updateTokenFromHeader(response.headers)

      if (!response.body) {
        throw new Error('Response body is null')
      }

      isConnected.value = true
      isReceiving.value = true

      // 流级超时：若长时间无数据到达则中止
      const resetStreamTimeout = () => {
        if (streamTimeoutTimer) clearTimeout(streamTimeoutTimer)
        streamTimeoutTimer = setTimeout(() => {
          if (isReceiving.value && abortController) {
            abortController.abort()
            const timeoutInfo: ChatErrorInfo = {
              category: 'timeout',
              rawMessage: 'Stream timeout: no data received',
              retryable: true,
              timestamp: Date.now(),
            }
            error.value = Object.assign(new Error('Stream timeout'), { errorInfo: timeoutInfo })
            emit({ type: 'error', data: { message: 'Stream timeout', errorInfo: timeoutInfo } })
          }
        }, STREAM_TIMEOUT_MS)
      }
      resetStreamTimeout()

      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')

      // 读取循环
      const read = async () => {
        try {
          while (true) {
            const { done, value } = await reader.read()

            if (done) {
              break
            }

            resetStreamTimeout()
            const chunk = decoder.decode(value, { stream: true })
            processChunk(chunk)
          }

          // 处理剩余数据
          const remaining = decoder.decode()
          if (remaining) {
            processChunk(remaining)
          }
          
          // flush 剩余事件
          const flushEvents = parser.flush()
          flushEvents.forEach(emit)
          
        } catch (e) {
          if (e instanceof Error && e.name === 'AbortError') {
            // 用户主动中止，不是错误
            return
          }
          throw e
        } finally {
          isReceiving.value = false
          isConnected.value = false
          reader.releaseLock()
        }
      }

      await read()
      
    } catch (e) {
      if (e instanceof Error && e.name === 'AbortError') {
        return
      }
      error.value = e instanceof Error ? e : new Error(String(e))
      const errorInfo: ChatErrorInfo = (e as any)?.errorInfo
        || classifyNetworkError(error.value)
      emit({ type: 'error', data: { message: error.value.message, errorInfo } })
    } finally {
      if (streamTimeoutTimer) {
        clearTimeout(streamTimeoutTimer)
        streamTimeoutTimer = null
      }
      isReceiving.value = false
      isConnected.value = false
    }
  }

  // 断开连接
  const disconnect = () => {
    if (streamTimeoutTimer) {
      clearTimeout(streamTimeoutTimer)
      streamTimeoutTimer = null
    }
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    isConnected.value = false
    isReceiving.value = false
  }

  // 中止请求（别名）
  const abort = disconnect

  // 注册事件处理器
  const on = (event: SSEEventType, handler: (data: any) => void) => {
    if (!eventHandlers.has(event)) {
      eventHandlers.set(event, new Set())
    }
    eventHandlers.get(event)!.add(handler)

    // 返回取消订阅函数
    return () => {
      eventHandlers.get(event)?.delete(handler)
    }
  }

  // 注册全局事件处理器
  const onEvent = (handler: (event: SSEEvent) => void) => {
    globalHandlers.add(handler)
    return () => {
      globalHandlers.delete(handler)
    }
  }

  return {
    isConnected,
    isReceiving,
    error,
    lastEventId,
    connect,
    disconnect,
    abort,
    on,
    onEvent,
  }
}

export default useStream
