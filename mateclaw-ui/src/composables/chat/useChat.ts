/**
 * Unified chat composable.
 * Integrates useMessages, useStream, and useMessageQueue into a complete chat feature.
 *
 * Core mechanism (Interrupt + Queue + Resume model):
 * - New messages can be sent while a response is already generating.
 * - Interruptible phases (thinking/streaming/executing_tool): sends an interrupt request;
 *   the queued message resumes automatically after interruption.
 * - Non-interruptible phases: message is queued and auto-resumed when the current step ends.
 * - During approval: message is queued, approval flow is not interrupted.
 */
import { ref, computed } from 'vue'
import { useMessages } from './useMessages'
import { useStream } from './useStream'
import { useMessageQueue } from './useMessageQueue'
import { useGoalStore } from '@/stores/useGoalStore'
import type { Message, MessageContentPart, MessageSegment, StreamPhase, HeartbeatData, QueuedMessage, PhaseEventData, DelegationNode, DelegationToolEntry, PlanMeta } from '@/types'
import { classifyBackendError, type ChatErrorInfo } from '@/types/chatError'
import { http } from '@/api'

/**
 * Snapshot of a {@code compact_status} SSE event. Mirrors the payload built
 * by ConversationWindowManager.broadcastCompactStatus so the UI can render a
 * progress chip without each consumer reverse-engineering field names.
 */
export interface CompactStatusEvent {
  /** start | pair_safe | summarize | done | skipped | failed */
  status: 'start' | 'pair_safe' | 'summarize' | 'done' | 'skipped' | 'failed'
  /** Server clock when the event fired. */
  timestamp?: number
  /** Total prompt tokens before compaction began (start / done payloads). */
  preTokens?: number
  /** Total prompt tokens after the boundary lands (done payload). */
  postTokens?: number
  /** Messages in scope at start. */
  messagesIn?: number
  /** Messages folded into the structured summary (done payload). */
  messagesSummarized?: number
  /** Recent messages preserved verbatim (done payload). */
  tailKept?: number
  /** Tool-result bodies spilled to disk this turn (done payload). */
  toolResultsSpilled?: number
  /** Whether the first-user anchor was injected (done payload). */
  anchored?: boolean
  /** Why compaction was skipped or failed: insufficient_messages, pair_boundary_collapsed, summary_generation_failed, ... */
  reason?: string
  /** Pair-safety boundary moved from / to indices (pair_safe payload). */
  movedFrom?: number
  movedTo?: number
  /** Summary budget the LLM was asked to fit into (summarize payload). */
  summaryBudget?: number
  /** Trigger label baked in by the backend (start / done — currently token_threshold). */
  trigger?: string
  /** Tail kept fallback when summary generation failed. */
  fallbackKept?: number
  /** True when the boundary was served from the in-memory summary cache. */
  fromCache?: boolean
}

export interface UseChatOptions {
  /** Base API URL */
  baseUrl: string
  /** Auth token */
  token?: string
  /** Current thinking depth (reactive ref); when "off", thinking segments are suppressed */
  thinkingLevel?: import('vue').Ref<string>
  /**
   * Unified callback fired when the stream ends (done/error/stopped all trigger this).
   * The caller should perform history reconcile / persistence in this callback.
   */
  onStreamEnd?: (meta: StreamEndMeta) => void
}

/** Metadata emitted when a stream ends */
export interface StreamEndMeta {
  conversationId: string
  reason: 'completed' | 'stopped' | 'interrupted' | 'failed' | 'error' | 'awaiting_approval'
  /** Backend-persisted assistant message ID, if available */
  assistantMessageId?: number
  /** Whether the backend has already persisted the message */
  persisted?: boolean
  /** Total message count reported by the backend */
  messageCount?: number
}

export interface UseChatReturn {
  /** Message list */
  messages: import('vue').Ref<Message[]>
  /** Whether the assistant is currently generating */
  isGenerating: import('vue').ComputedRef<boolean>
  /** Current stream phase */
  streamPhase: import('vue').Ref<StreamPhase>
  /** Most recent phase event */
  phaseInfo: import('vue').Ref<PhaseEventData | null>
  /** Current error */
  error: import('vue').Ref<Error | null>
  /** Queued message waiting to be sent */
  queuedMessage: import('vue').Ref<QueuedMessage | null>
  /** Whether there is a queued message */
  hasQueued: import('vue').ComputedRef<boolean>
  /** Number of queued messages */
  queueSize: import('vue').ComputedRef<number>
  /** Latest heartbeat data */
  heartbeat: import('vue').Ref<HeartbeatData | null>
  /**
   * Latest compact_status SSE event for the active turn. Drives the in-prompt
   * compaction chip / boundary marker so the user can see "preparing context"
   * pauses (start → pair_safe → summarize → done/skipped/failed). Cleared back
   * to {@code null} when a turn finishes, so the chip auto-hides.
   */
  compactStatus: import('vue').Ref<CompactStatusEvent | null>
  /**
   * Fine-grained pre-token lifecycle stage. Drives the loading bar copy in the
   * window between "send pressed" and "first delta arrived". `null` once a
   * delta is observed (StreamLoadingBar then falls back to `phase`-derived text).
   */
  lifecycleStage: import('vue').Ref<{
    stage: 'connecting' | 'started' | 'context_prepared' | 'llm_request_sent' | 'streaming'
    detail?: any
    since: number
  } | null>
  /** Send a message (can be called while generating — automatically routes to interrupt/queue) */
  sendMessage: (content: string, options: SendMessageOptions) => Promise<void>
  /** Stop generation (user-initiated stop; does not auto-resume queued messages) */
  stopGeneration: () => void
  /** Cancel the queued message */
  cancelQueued: () => void
  /** Regenerate a message */
  regenerate: (messageId: string | number) => Promise<void>
  /** Add a message */
  addMessage: (message: Omit<Message, 'id' | 'createTime'> & { id?: string | number }) => Message
  /** Clear all messages */
  clearMessages: () => void
  /** Reconnect to a stream that is already running on the backend */
  reconnectStream: (conversationId: string) => Promise<void>
  /** Fully reset stream context — call when switching or creating a conversation */
  resetForNewConversation: () => void
}

export interface SendMessageOptions {
  /** Conversation ID */
  conversationId: string
  /** Agent ID */
  agentId: string | number
  /** Attachment list */
  attachments?: MessageContentPart[]
  /** Message content parts */
  contentParts?: MessageContentPart[]
  /** Thinking depth: off / low / medium / high / max */
  thinkingLevel?: string
  /** Provider id of the model picked for this conversation. */
  modelProvider?: string
  /** Model id picked for this conversation. Paired with modelProvider. */
  modelName?: string
}

export function useChat(options: UseChatOptions): UseChatReturn {
  const { baseUrl, token, onStreamEnd } = options
  const thinkingLevelRef = options.thinkingLevel

  /**
   * Authenticated fetch wrapper — reads the token from localStorage (consistent with useStream / http.ts).
   */
  const fetchWithAuth = (url: string, init: RequestInit = {}): Promise<Response> => {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(init.headers as Record<string, string> || {}),
    }
    const storedToken = localStorage.getItem('token')
    if (storedToken) headers.Authorization = `Bearer ${storedToken}`
    if (token) headers.Authorization = `Bearer ${token}`
    return fetch(url, { ...init, headers })
  }

  const error = ref<Error | null>(null)
  const currentAssistantId = ref<string | null>(null)
  /** Fallback timer for stopGeneration — must be cleared when a new stream starts to avoid killing the new connection */
  let stopFallbackTimer: ReturnType<typeof setTimeout> | null = null
  const streamPhase = ref<StreamPhase>('idle')
  const phaseInfo = ref<PhaseEventData | null>(null)
  /**
   * Latest compact_status event for the current turn. Reset to null on
   * stream end and on every conversation switch so the chip auto-hides.
   * "done" events are kept on screen for a short interval by the consumer
   * (see StreamLoadingBar / CompactStatusBadge) rather than being cleared
   * immediately, so the user gets a chance to see the result.
   */
  const compactStatus = ref<CompactStatusEvent | null>(null)

  /** All segments of the current assistant message (for segmented display) */
  const currentSegments = ref<MessageSegment[]>([])
  const segIdCounter = { value: 0 }
  const genSegId = () => `seg-${Date.now()}-${segIdCounter.value++}`

  /**
   * Fine-grained lifecycle stage exposed to the UI for the "connecting → started
   * → context_prepared → llm_request_sent → streaming" loading bar. Reset on
   * every new turn; transitions to `streaming` implicitly when the first
   * thinking/content delta lands.
   */
  const lifecycleStage = ref<{
    stage: 'connecting' | 'started' | 'context_prepared' | 'llm_request_sent' | 'streaming'
    detail?: any
    since: number
  } | null>(null)

  /** Helper: tag a freshly-created segment with the active iteration / scope. */
  function applyIterationTags(seg: MessageSegment) {
    const stash = currentSegments.value as any
    const idx = stash._currentIteration
    if (typeof idx === 'number') seg.iterationIndex = idx
    const subId = stash._currentSubagentId
    if (subId) seg.subagentId = subId
  }

  /** Unique ID for the current turn — prevents flushSegmentsToMessage from writing stale segments to a new message */
  let activeTurnId = ''

  /** Reset streaming state for the current turn — must be called before creating a new assistant placeholder */
  function resetCurrentTurnState() {
    currentSegments.value = []
    segIdCounter.value = 0
    activeTurnId = `turn-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
  }

  /** Sync current segments into the assistant message metadata (used for real-time rendering) */
  const flushSegmentsToMessage = () => {
    if (!currentAssistantId.value || currentSegments.value.length === 0) return
    const msg = getMessage(currentAssistantId.value)
    if (!msg) return
    // Guard: only write to the message created in the current turn to avoid stale segment pollution
    if ((msg as any)._turnId && (msg as any)._turnId !== activeTurnId) return
    const metadata = parseMetadata((msg as any).metadata)
    updateMessage(currentAssistantId.value, {
      ...msg,
      metadata: { ...metadata, segments: [...currentSegments.value] }
    } as any)
  }
  const heartbeat = ref<HeartbeatData | null>(null)
  /** Track which conversation the current stream belongs to */
  let streamConversationId = ''
  /** Returns true if the event belongs to an expired conversation (prevents stale stream events from polluting a new session) */
  function isStaleEvent(data: any): boolean {
    const eventConvId = data?.conversationId
    if (eventConvId && streamConversationId && eventConvId !== streamConversationId) {
      return true
    }
    return false
  }
  /** Set of already-processed approval pendingIds (idempotency dedup) */
  const processedApprovalIds = new Set<string>()

  /**
   * Parse metadata — handles JSON strings loaded from the database.
   */
  const parseMetadata = (metadata: any): any => {
    if (!metadata) return {}
    if (typeof metadata === 'string') {
      try {
        let parsed = JSON.parse(metadata)
        // Handle double-encoded JSON (DB metadata is a string; Jackson may escape it again)
        if (typeof parsed === 'string') {
          try { parsed = JSON.parse(parsed) } catch { /* ignore */ }
        }
        return parsed
      } catch (e) {
        console.warn('[useChat] Failed to parse metadata:', e)
        return {}
      }
    }
    return metadata
  }

  /**
   * Expire stale awaiting_approval UI state when the stream ends with error/done but the approval
   * is no longer active. Must use updateMessage to trigger Vue reactivity — mutating nested fields
   * alone is not sufficient.
   */
  const expirePendingApprovals = (finalStatus: 'completed' | 'failed' | 'stopped') => {
    for (const m of messages.value) {
      if (m.role !== 'assistant') continue
      const metadata = parseMetadata((m as any).metadata)
      const pendingApproval = metadata?.pendingApproval
      const hasPendingApproval = pendingApproval?.status === 'pending_approval'
      const isAwaitingApprovalMsg = m.status === 'awaiting_approval' || metadata?.currentPhase === 'awaiting_approval'
      if (!hasPendingApproval && !isAwaitingApprovalMsg) continue
      if (m.id === undefined || m.id === null) continue

      const toolCalls = Array.isArray(metadata?.toolCalls)
        ? metadata.toolCalls.map((tc: any) => (
            tc?.status === 'running' || tc?.status === 'awaiting_approval'
              ? { ...tc, status: 'completed' }
              : tc
          ))
        : metadata?.toolCalls

      updateMessage(m.id, {
        ...m,
        status: m.status === 'awaiting_approval' ? finalStatus : m.status,
        metadata: {
          ...metadata,
          currentPhase: undefined,
          runningToolName: undefined,
          toolCalls,
          pendingApproval: hasPendingApproval
            ? { ...pendingApproval, status: 'expired' }
            : pendingApproval,
        },
      } as any)
    }
  }

  // Message management
  const {
    messages,
    isGenerating,
    addMessage,
    updateMessage,
    appendMessageContent,
    setMessageStatus,
    createUserMessage,
    createAssistantMessage,
    clearMessages,
    getMessage,
  } = useMessages({
    onComplete: () => {
      // Do not clear currentAssistantId here — the 'done' event handles cleanup
    },
  })

  // Message queue
  const messageQueue = useMessageQueue()

  // Stream connection (inject auth + workspace headers, consistent with the axios interceptor)
  const streamHeaders: Record<string, string> = {}
  if (token) {
    streamHeaders['Authorization'] = `Bearer ${token}`
  }
  const wsId = localStorage.getItem('mc-workspace-id')
  if (wsId) {
    streamHeaders['X-Workspace-Id'] = wsId
  }
  const stream = useStream({
    url: `${baseUrl}/api/v1/chat/stream`,
    headers: streamHeaders,
  })

  // Goal store is referenced from several stream handlers (message_start
  // for followup attribution, message_complete for the evaluating halo,
  // plus the dedicated goal_* events below). Resolve once up front so
  // the handlers don't each pull their own copy.
  const goalStore = useGoalStore()

  // ===== Async-task lifecycle bridge =====
  // Generative tools (music / video / image) return a taskId synchronously and
  // finish asynchronously via `async_task_completed`. If the upstream provider
  // is slow (MiniMax music ~2-3 min) the agent's reasoning turn finishes long
  // before the audio is ready, the SSE stream emits `done`, and any later
  // `async_task_completed` event lands on a closed emitter. We track which
  // taskIds are still pending here, and when `done` fires with non-empty set
  // we re-attach to the same conversation's stream so buffered + future async
  // events can flow through. ChatStreamTracker.attach was extended (RFC P0)
  // to keep the new emitter subscribed even when state.done=true.
  const pendingAsyncTaskIds = new Set<string>()
  // 16-hex taskId emitted by AsyncTaskService.createTask. Tool result text
  // varies — `taskId=xxx` is the canonical form (music/video/image), but
  // earlier video/image versions used 中文「任务 ID: xxx」 and old strings may
  // still flow through if the LLM cached them. Match both defensively.
  const TASK_ID_PATTERNS: RegExp[] = [
    /taskId[=:"\s]+([a-f0-9]{16})/i,
    /任务\s*ID[=:"\s]+([a-f0-9]{16})/i,
    /task[_\s]*id[=:"\s]+([a-f0-9]{16})/i,
  ]
  const ASYNC_TOOL_NAMES = new Set(['music_generate', 'video_generate', 'image_generate', 'model3d_generate'])
  let reconnectingForAsyncTasks = false

  function extractTaskId(result: unknown): string | null {
    if (typeof result !== 'string') return null
    for (const re of TASK_ID_PATTERNS) {
      const m = result.match(re)
      if (m) return m[1]
    }
    return null
  }

  // ===== SSE event handlers =====

  stream.on('content_delta', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      appendMessageContent(currentAssistantId.value, data.delta || '', 'text')
      if (['thinking', 'reasoning', 'drafting_answer', 'preparing_context'].includes(streamPhase.value)) {
        streamPhase.value = 'streaming'
      }
      // First visible delta — flip lifecycleStage so the loading bar drops the
      // pre-stream messaging and yields to the per-phase status text.
      if (lifecycleStage.value && lifecycleStage.value.stage !== 'streaming') {
        lifecycleStage.value = { stage: 'streaming', since: Date.now() }
      }
      // Segments: append to the current running content segment, or create a new one
      const segs = currentSegments.value
      let contentSeg = segs.findLast((s: MessageSegment) => s.type === 'content' && s.status === 'running')
      if (!contentSeg) {
        // Close any running thinking segment first
        const thinkingSeg = segs.findLast((s: MessageSegment) => s.type === 'thinking' && s.status === 'running')
        if (thinkingSeg) thinkingSeg.status = 'completed'
        contentSeg = { id: genSegId(), type: 'content', status: 'running', text: '', timestamp: Date.now() }
        applyIterationTags(contentSeg)
        segs.push(contentSeg)
        flushSegmentsToMessage() // sync once when a new content segment is created
      }
      contentSeg.text = (contentSeg.text || '') + (data.delta || '')
    }
  })

  stream.on('thinking_delta', (data) => {
    if (isStaleEvent(data)) return
    // Suppress thinking display when thinkingLevel=off
    if (options.thinkingLevel?.value === 'off') return
    if (currentAssistantId.value) {
      appendMessageContent(currentAssistantId.value, data.delta || '', 'thinking')
      if (streamPhase.value !== 'summarizing_observations') {
        streamPhase.value = options.thinkingLevel?.value === 'off' ? 'streaming' : 'thinking'
      }
      // Segments: per-round thinking. When tool_call_started / phase change closes the running
      // thinking segment (status='completed'), a fresh thinking_delta opens a new segment instead
      // of reopening the closed one. Without this split, multi-round ReAct (3 reasoning + 2
      // summarizing rounds) accumulates 9K+ chars in a single bubble.
      const segs = currentSegments.value
      let thinkSeg = segs.findLast((s: MessageSegment) =>
        s.type === 'thinking' && s.status === 'running'
      )
      if (!thinkSeg) {
        thinkSeg = { id: genSegId(), type: 'thinking', status: 'running', thinkingText: '', timestamp: Date.now() }
        applyIterationTags(thinkSeg)
        // Append in timeline order (interleaved with tool_calls) — old behavior unshift'd to top,
        // but with per-round splitting that misorders rounds 2+ relative to their tool calls.
        segs.push(thinkSeg)
        flushSegmentsToMessage()
      }
      thinkSeg.thinkingText = (thinkSeg.thinkingText || '') + (data.delta || '')
      // First thinking delta — same lifecycle flip as content_delta.
      if (lifecycleStage.value && lifecycleStage.value.stage !== 'streaming') {
        lifecycleStage.value = { stage: 'streaming', since: Date.now() }
      }
    }
  })

  stream.on('message_start', (data) => {
    if (isStaleEvent(data)) return
    if (data?.role !== 'assistant') return
    const currentMsg = currentAssistantId.value ? getMessage(currentAssistantId.value) : null
    if (currentMsg?.role === 'assistant') {
      if (currentMsg.status !== 'generating') {
        setMessageStatus(currentAssistantId.value!, 'generating')
      }
      return
    }

    // Only create a placeholder here if one does not already exist (the normal path creates it in sendMessage)
    resetCurrentTurnState()
    const assistantMessage = createAssistantMessage('', streamConversationId)
    ;(assistantMessage as any)._turnId = activeTurnId
    currentAssistantId.value = assistantMessage.id as string

    // Auto-followup attribution: if the goal evaluator just decided to
    // inject a followup, the message that just opened belongs to that
    // turn. Stamp it so MessageBubble can render the small ↻ glyph.
    if (streamConversationId && goalStore.consumePendingFollowup(streamConversationId)) {
      goalStore.markFollowupMessage(streamConversationId, String(assistantMessage.id))
    }
  })

  stream.on('warning', (data) => {
    if (isStaleEvent(data)) return
    console.warn('[Chat] Warning from server:', data.delta || data.message || data)
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        const warnings = metadata?.warnings || []
        warnings.push(data.delta || data.message || String(data))
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, warnings }
        } as any)
      }
    }
  })

  stream.on('message_complete', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg?.status === 'failed') {
        // Do not clear currentAssistantId — the 'done' event handles cleanup
        return
      }
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        if (metadata?.toolCalls) {
          const toolCalls = [...metadata.toolCalls]
          let needsUpdate = false
          for (let i = 0; i < toolCalls.length; i++) {
            if (toolCalls[i].status !== 'completed') {
              toolCalls[i] = { ...toolCalls[i], status: 'completed' }
              needsUpdate = true
            }
          }
          if (needsUpdate) {
            updateMessage(currentAssistantId.value, {
              ...msg,
              status: data.status || 'completed',
              metadata: { ...metadata, toolCalls }
            } as any)
            // Do not clear currentAssistantId here — the 'done' event does it
            return
          }
        }
      }
      setMessageStatus(currentAssistantId.value, data.status || 'completed')
      // Do not clear currentAssistantId here — the 'done' event does it
    }

    // Segments: mark all running segments as completed and persist to message metadata
    if (currentAssistantId.value && currentSegments.value.length > 0) {
      currentSegments.value.forEach((s: MessageSegment) => { if (s.status === 'running') s.status = 'completed' })
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, segments: [...currentSegments.value] }
        } as any)
      }
    }

    // Auto TTS: trigger when message_complete arrives with status=completed
    if (data.status === 'completed' && data.hasContent && currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg?.content && streamConversationId) {
        triggerAutoTts(streamConversationId, msg.content)
      }
    }

    // Goal-evaluator breathing halo: when an assistant message finishes
    // and this conversation has an active goal, the backend's evaluation
    // node runs next. Flip the per-conv flag so GoalAvatarRing paints the
    // breathing halo until `goal_evaluated` resets it.
    //
    // Skip when:
    //   - the conversation has no active goal (ordinary turn, no halo)
    //   - the evaluator already fired in this turn (SSE order under the
    //     structured stream is goal_evaluated → done → message_complete,
    //     so re-arming here would leave the halo stuck on after the
    //     evaluator already cleared it)
    if (
      data.status === 'completed'
      && streamConversationId
      && goalStore.activeGoal(streamConversationId)
      && !goalStore.recentlyEvaluated(streamConversationId)
    ) {
      goalStore.markEvaluating(streamConversationId, true)
    }
  })

  stream.on('done', (data) => {
    if (isStaleEvent(data)) return

    if (currentAssistantId.value) {
      const existingMsg = getMessage(currentAssistantId.value)
      if (existingMsg?.status !== 'failed') {
        setMessageStatus(currentAssistantId.value, data.status || 'completed')
      }

      // Update token counts + replace the local temp ID with the backend-persisted ID (critical: enables reconcile by ID)
      const msgIndex = messages.value.findIndex(m => m.id === currentAssistantId.value)
      if (msgIndex >= 0) {
        const msg = messages.value[msgIndex]
        if (data.promptTokens !== undefined) msg.promptTokens = data.promptTokens
        if (data.completionTokens !== undefined) msg.completionTokens = data.completionTokens
        if (data.runtimeModel) msg.runtimeModel = data.runtimeModel
        if (data.runtimeProvider) msg.runtimeProvider = data.runtimeProvider
        // Replace the local temp ID with the backend-persisted ID so reconcile can match by ID
        if (data.assistantMessageId) {
          msg.id = data.assistantMessageId
        }
        // Merge server-authoritative segment annotations (carries fields the
        // live SSE path can't compute, like the 'superseded' marker the
        // backend's SegmentSupersedeDetector writes onto pre-tool model
        // claims that the actual tool result replaced). The local segments
        // keep their content / status; the server segments only contribute
        // their annotation fields.
        //
        // Matching: client and server use DIFFERENT id schemes (client uses
        // timestamp-based ids like `seg-1778744207326-0`; server uses
        // `co-0 / to-1 / th-2` from its accumulator). They DO produce
        // segments in the same temporal order from the same event stream,
        // so we pair by (type, intra-type index): the N-th content/tool/
        // thinking segment locally aligns with the N-th of the same type
        // on the server. Extra local-only segments (rare streaming
        // artifacts that the server pruned) end up unmatched and pass
        // through untouched — no risk of mislabelling.
        if (Array.isArray(data.segments) && data.segments.length > 0) {
          const metadata = parseMetadata((msg as any).metadata)
          const localSegs = (metadata?.segments as any[]) || []
          if (localSegs.length > 0) {
            const serverByTypeIndex = new Map<string, any>()
            const serverTypeCount = new Map<string, number>()
            for (const s of data.segments as any[]) {
              if (!s || typeof s !== 'object' || typeof s.type !== 'string') continue
              const idx = serverTypeCount.get(s.type) || 0
              serverByTypeIndex.set(`${s.type}#${idx}`, s)
              serverTypeCount.set(s.type, idx + 1)
            }
            if (serverByTypeIndex.size > 0) {
              const localTypeCount = new Map<string, number>()
              const merged = localSegs.map((local: any) => {
                if (!local || typeof local.type !== 'string') return local
                const idx = localTypeCount.get(local.type) || 0
                localTypeCount.set(local.type, idx + 1)
                const remote = serverByTypeIndex.get(`${local.type}#${idx}`)
                if (!remote) return local
                const next = { ...local }
                if (remote.superseded !== undefined) next.superseded = remote.superseded
                if (remote.supersededBySegmentId !== undefined) {
                  next.supersededBySegmentId = remote.supersededBySegmentId
                }
                if (remote.supersededReason !== undefined) {
                  next.supersededReason = remote.supersededReason
                }
                return next
              })
              ;(msg as any).metadata = { ...(metadata || {}), segments: merged }
            }
          }
        }
        messages.value[msgIndex] = { ...msg }
      }
      currentAssistantId.value = null
    }

    streamPhase.value = data.status === 'awaiting_approval' ? 'awaiting_approval'
      : data.status === 'stopped' ? 'stopped' : 'completed'
    if (data.status !== 'awaiting_approval') {
      phaseInfo.value = null
      compactStatus.value = null
      lifecycleStage.value = null
      expirePendingApprovals(data.status === 'stopped' ? 'stopped' : 'completed')
    }

    // Safety cleanup for queue state (no-op if queued_input_started already handled it)
    if (!messageQueue.hasQueued.value) {
      // Queue already empty — phase cannot linger at 'queued'
    } else if (data.status === 'stopped') {
      // User-initiated stop — discard queued message
      messageQueue.clear()
    }

    // Fire unified onStreamEnd
    const reason = data.status === 'stopped' ? 'stopped'
      : data.status === 'interrupted' ? 'interrupted'
      : data.status === 'awaiting_approval' ? 'awaiting_approval'
      : 'completed'
    onStreamEnd?.({
      conversationId: data.conversationId || streamConversationId,
      reason,
      assistantMessageId: data.assistantMessageId,
      persisted: data.persisted,
      messageCount: data.messageCount,
    })

    // Re-attach SSE if any generative task is still in flight, so the eventual
    // async_task_completed event reaches us live (otherwise the user has to
    // refresh). Skip if we're already in a reconnect cycle, or for non-completed
    // terminal statuses where reconnect is misleading.
    const reconnectableStatus = !data.status
      || data.status === 'completed'
      || data.status === 'idle'
    if (reconnectableStatus
        && !reconnectingForAsyncTasks
        && pendingAsyncTaskIds.size > 0
        && streamConversationId) {
      const targetConv = streamConversationId
      reconnectingForAsyncTasks = true
      // Defer one tick so the current 'done' handler chain finishes before
      // disconnect() fires inside connect().
      // lastEventId is NOT passed here — connect() owns the per-conversation
      // dedup state and injects its own lastEventId only when the target
      // conversation matches what the dedup state was tracking.
      setTimeout(() => {
        stream.connect({
          conversationId: targetConv,
          reconnect: true,
        })
          .catch(() => { /* swallow — handled by stream.error event */ })
          .finally(() => { reconnectingForAsyncTasks = false })
      }, 50)
    }
  })

  let errorFired = false
  stream.on('error', (data) => {
    if (isStaleEvent(data)) return
    // Always carry data.message as rawMessage, so the inline error card can
    // surface the actual reason ("无权操作该会话" etc.) instead of the generic
    // unknown.description template. classifyBackendError already does this
    // when errorType is present; the fallback path used to drop it.
    const errorInfo: ChatErrorInfo = data.errorInfo
      || (data.errorType
        ? classifyBackendError(data)
        : { category: 'unknown', rawMessage: data.message, retryable: true, timestamp: Date.now() })
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        updateMessage(currentAssistantId.value, {
          ...msg,
          status: 'failed',
          errorInfo,
        } as any)
      } else {
        setMessageStatus(currentAssistantId.value, 'failed')
      }
      currentAssistantId.value = null
    }
    const errorMessage = data.message || '请求失败'
    error.value = new Error(errorMessage)
    streamPhase.value = 'idle'
    phaseInfo.value = null
    compactStatus.value = null
    lifecycleStage.value = null
    // Clear queue on error to avoid stale state
    messageQueue.clear()
    expirePendingApprovals('failed')

    if (errorFired) return
    errorFired = true
    onStreamEnd?.({
      conversationId: data.conversationId || streamConversationId,
      reason: 'error',
      assistantMessageId: data.assistantMessageId,
      persisted: data.persisted,
      messageCount: data.messageCount,
    })
  })

  // ===== Agent event handlers =====

  // Body of tool_call_started. Used directly and reused once (split out as a
  // function ⟶ no logic duplication).
  function handleToolCallStarted(data: any) {
    if (isStaleEvent(data)) return
    streamPhase.value = 'executing_tool'
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        const toolCalls = metadata?.toolCalls || []
        toolCalls.push({
          toolCallId: data.toolCallId || '',
          name: data.toolName,
          arguments: data.arguments,
          status: 'running',
          startTime: data.timestamp || Date.now()
        })
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, toolCalls, currentPhase: 'executing_tool', runningToolName: data.toolName }
        } as any)
      }
      // Segments: close any running thinking/content segment, then push a new tool_call segment.
      // Carry toolCallId so completes can pair back precisely; falling back to toolName-based
      // pairing strands the first card with a permanent spinner whenever the LLM fires multiple
      // calls of the same tool (observed with execute_shell_command + python3 retries).
      const segs = currentSegments.value
      const runningSeg = segs.findLast((s: MessageSegment) => s.status === 'running' && (s.type === 'thinking' || s.type === 'content'))
      if (runningSeg) runningSeg.status = 'completed'
      const toolSeg: MessageSegment = {
        id: genSegId(), type: 'tool_call', status: 'running',
        toolCallId: data.toolCallId || '',
        toolName: data.toolName, toolArgs: data.arguments,
        timestamp: data.timestamp || Date.now(),
      }
      applyIterationTags(toolSeg)
      segs.push(toolSeg)
      flushSegmentsToMessage()
    }
  }

  // Body of tool_call_completed — see handleToolCallStarted.
  function handleToolCallCompleted(data: any) {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        const toolCalls = [...(metadata?.toolCalls || [])]
        // Match by toolCallId when available, fall back to "first running" for legacy events.
        let target = -1
        if (data.toolCallId) {
          target = toolCalls.findIndex((tc: any) => tc.toolCallId === data.toolCallId && tc.status === 'running')
        }
        if (target < 0) {
          target = toolCalls.findIndex((tc: any) => tc.status === 'running' && tc.name === data.toolName)
        }
        if (target >= 0) {
          toolCalls[target] = {
            ...toolCalls[target],
            result: data.result,
            success: data.success,
            status: 'completed'
          }
        }
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, toolCalls, runningToolName: undefined }
        } as any)
      }
      // Segments: prefer toolCallId match, fall back to first-running by toolName.
      const segs = currentSegments.value
      let toolSeg: MessageSegment | undefined
      if (data.toolCallId) {
        toolSeg = segs.find((s: MessageSegment) =>
          s.type === 'tool_call' && s.status === 'running' && s.toolCallId === data.toolCallId)
      }
      if (!toolSeg) {
        toolSeg = segs.find((s: MessageSegment) =>
          s.type === 'tool_call' && s.status === 'running' && s.toolName === data.toolName)
      }
      if (toolSeg) {
        toolSeg.status = data.success !== false ? 'completed' : 'error'
        toolSeg.toolResult = data.result
        toolSeg.toolSuccess = data.success
      }
      flushSegmentsToMessage()
    }

    // Track async generative tools whose taskId is in the result string.
    if (data.success !== false && ASYNC_TOOL_NAMES.has(data.toolName)) {
      const taskId = extractTaskId(data.result)
      if (taskId) {
        pendingAsyncTaskIds.add(taskId)
      }
    }
  }

  stream.on('tool_call_started', handleToolCallStarted)
  stream.on('tool_call_completed', handleToolCallCompleted)

  // ===== Browser action events =====

  stream.on('browser_action', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        const browserActions = [...(metadata?.browserActions || [])]
        browserActions.push({
          action: data.action,
          success: data.success,
          url: data.url,
          title: data.title,
          screenshot: data.screenshot,
          durationMs: data.durationMs,
          timestamp: data.timestamp || Date.now()
        })
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, browserActions }
        } as any)
      }
    }
  })

  // Live recovery affordance event. Emitted by the graph when a turn
  // ends in a non-transient error (ERROR_FALLBACK). Carries
  // { errorType, errorMessage, actions } — actions is the ordered list
  // of buttons the failed-bubble card should render. Persisting onto
  // metadata.feedbackEvent matches the post-reload code path in
  // MessageBubble (which reads the same metadata key) so the card
  // appears immediately during the live stream AND survives a refresh.
  stream.on('feedback_event', (data) => {
    if (isStaleEvent(data)) return
    if (!currentAssistantId.value) return
    const msg = getMessage(currentAssistantId.value)
    if (!msg) return
    const metadata = parseMetadata((msg as any).metadata)
    updateMessage(currentAssistantId.value, {
      ...msg,
      metadata: {
        ...metadata,
        feedbackEvent: {
          errorType: data.errorType,
          errorMessage: data.errorMessage,
          actions: data.actions,
          timestamp: data.timestamp || Date.now(),
        },
      },
    } as any)
  })

  // Context-compaction progress. Fires before the LLM call when the window
  // manager has to evict old turns to fit budget. The chip uses this to show
  // the user that an unexpected pause is the planner thinking about
  // context, not a network stall.
  stream.on('compact_status', (data) => {
    if (isStaleEvent(data)) return
    compactStatus.value = { ...data } as CompactStatusEvent
  })

  stream.on('phase', (data) => {
    if (isStaleEvent(data)) return
    const phase = data.phase as StreamPhase
    if (phase) {
      streamPhase.value = phase
      phaseInfo.value = { ...data, phase }
    }
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        // Dedup: skip updateMessage if the phase hasn't changed, to avoid unnecessary Vue reactivity
        if (metadata.currentPhase === data.phase) return
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: { ...metadata, currentPhase: data.phase }
        } as any)
      }
      // Close any running thinking/content segment on phase transition so the next thinking_delta
      // (e.g. summarizing → reasoning) starts a fresh round-scoped segment instead of growing the
      // previous one unbounded.
      const segs = currentSegments.value
      for (const seg of segs) {
        if (seg.status === 'running' && (seg.type === 'thinking' || seg.type === 'content')) {
          seg.status = 'completed'
        }
      }
    }
  })

  // ===== Agent delegation events =====
  // Delegations form a tree: a depth-1 child is a top-level tool_call segment
  // (keyed by its subagentId); depth-2+ subagents are DelegationNode entries
  // nested under an ancestor via parentSubagentId. Every delegation_* event
  // carries subagentId/parentSubagentId/depth so the flat event stream can be
  // reassembled into that tree on the frontend (see DelegationNodeView.vue).

  type DelegContainer = { plan?: PlanMeta; tools?: DelegationToolEntry[]; children?: DelegationNode[] }

  /** A depth-1 delegation segment, looked up by its subagentId (or childConversationId). */
  function findDelegSegment(segs: MessageSegment[], subagentId?: string, childConvId?: string): MessageSegment | undefined {
    if (subagentId) {
      const byId = segs.find(s => s.type === 'tool_call' && s.id === subagentId)
      if (byId) return byId
    }
    if (childConvId) return segs.find(s => s.type === 'tool_call' && s.id === childConvId)
    return undefined
  }

  /** Recursively find a DelegationNode by subagentId. */
  function findNode(nodes: DelegationNode[] | undefined, subagentId: string): DelegationNode | undefined {
    if (!nodes) return undefined
    for (const n of nodes) {
      if (n.subagentId === subagentId) return n
      const deep = findNode(n.children, subagentId)
      if (deep) return deep
    }
    return undefined
  }

  function ensureTimeline(seg: MessageSegment): DelegContainer {
    const t = (seg.childTimeline ||= {})
    if (!t.tools) t.tools = []
    if (!t.children) t.children = []
    return t
  }

  function ensureNodeContainer(node: DelegationNode): DelegContainer {
    if (!node.tools) node.tools = []
    if (!node.children) node.children = []
    return node
  }

  /** Resolve the progress container (plan/tools/children) for a subagent at any depth. */
  function resolveContainer(segs: MessageSegment[], subagentId?: string, childConvId?: string): DelegContainer | undefined {
    const seg = findDelegSegment(segs, subagentId, childConvId)
    if (seg) return ensureTimeline(seg)
    if (subagentId) {
      for (const s of segs) {
        const node = findNode(s.childTimeline?.children, subagentId)
        if (node) return ensureNodeContainer(node)
      }
    }
    return undefined
  }

  /** Mark a subagent (segment or nested node) complete by subagentId. */
  function markDelegComplete(segs: MessageSegment[], subagentId: string | undefined, childConvId: string | undefined,
                             success: boolean, resultPreview?: string, durationMs?: number): boolean {
    const seg = findDelegSegment(segs, subagentId, childConvId)
    if (seg) {
      seg.status = success ? 'completed' : 'error'
      seg.toolSuccess = success
      if (resultPreview) seg.toolResult = resultPreview
      if (durationMs) seg.toolArgs = (seg.toolArgs || '').trimEnd() + ` (${Math.round(durationMs / 1000)}s)`
      return true
    }
    if (subagentId) {
      for (const s of segs) {
        const node = findNode(s.childTimeline?.children, subagentId)
        if (node) {
          node.status = success ? 'completed' : 'error'
          if (resultPreview) node.result = resultPreview
          if (durationMs) node.durationMs = durationMs
          return true
        }
      }
    }
    return false
  }

  /** Create a depth-1 segment (top of tree) or a nested DelegationNode (deeper). */
  function addDelegation(segs: MessageSegment[], info: any, opts: { async?: boolean } = {}) {
    const subagentId: string | undefined = info.subagentId
    const parentSubagentId: string | undefined = info.parentSubagentId
    const agentName: string = info.childAgentName || 'Agent'
    const depth: number = info.depth || 1
    const task: string = info.task || ''

    if (parentSubagentId) {
      // depth-2+: attach under the parent subagent's container.
      const parent = resolveContainer(segs, parentSubagentId)
      if (!parent) return
      if (!findNode(parent.children, subagentId || '')) {
        parent.children!.push({
          subagentId: subagentId || genSegId(),
          agentName, status: 'running', depth, task,
          tools: [], children: [],
          ...(opts.async ? { async: true } : {})
        })
      }
      return
    }
    // depth-1: a top-level segment, keyed by subagentId for stable lookup.
    // Dedup against SSE replay / a duplicated start re-creating the same segment.
    const segId = subagentId || info.childConversationId || genSegId()
    if (segs.some(s => s.type === 'tool_call' && s.id === segId)) return
    segs.push({
      id: segId,
      type: 'tool_call',
      status: 'running',
      toolName: `→ ${agentName}`,
      toolArgs: task,
      childTimeline: { tools: [], children: [] },
      timestamp: Date.now(),
      ...(opts.async ? { delegationAsync: true } : {})
    })
  }

  stream.on('delegation_start', (data) => {
    if (isStaleEvent(data)) return
    streamPhase.value = 'executing_tool'
    if (!currentAssistantId.value) return
    const segs = currentSegments.value

    if (data.parallel && Array.isArray(data.children)) {
      // Only close a running root-level content/thinking segment when these are
      // top-level (depth-1) delegations; nested ones don't touch the root timeline.
      const topLevel = data.children.some((c: any) => !c.parentSubagentId)
      if (topLevel) {
        const running = segs.findLast((s: MessageSegment) => s.status === 'running' && (s.type === 'thinking' || s.type === 'content'))
        if (running) running.status = 'completed'
      }
      for (const child of data.children) addDelegation(segs, child)
    } else {
      if (!data.parentSubagentId) {
        const running = segs.findLast((s: MessageSegment) => s.status === 'running' && (s.type === 'thinking' || s.type === 'content'))
        if (running) running.status = 'completed'
      }
      addDelegation(segs, data)
    }
    flushSegmentsToMessage()
  })

  // Fire-and-forget delegation. The parent agent keeps running after spawning,
  // so unlike delegation_start we do NOT close the parent's running content/thinking
  // segment. The child runs detached and its result is fetched later via task_output,
  // so it is marked async and rendered as "running in background" rather than a
  // spinner that never resolves on this turn.
  stream.on('delegation_async_spawned', (data) => {
    if (isStaleEvent(data)) return
    if (!currentAssistantId.value) return
    addDelegation(currentSegments.value, data, { async: true })
    flushSegmentsToMessage()
  })

  stream.on('delegation_progress', (data) => {
    if (isStaleEvent(data)) return
    if (!currentAssistantId.value) return
    const segs = currentSegments.value

    const container = resolveContainer(segs, data.subagentId, data.childConversationId)
    if (!container) return
    if (!container.tools) container.tools = []

    // Normalize data.data: the backend relays the child event's JSON payload as
    // an object; be defensive for older backends that sent a JSON string.
    const rawPayload = data.data
    const childData: Record<string, any> = rawPayload && typeof rawPayload === 'object'
      ? rawPayload
      : (() => { try { return JSON.parse(String(rawPayload || '{}')) } catch { return {} } })()

    switch (data.originalEvent) {
      case 'tool_call_started': {
        const name = childData?.toolName || ''
        if (name) container.tools.push({ name, status: 'running' })
        break
      }
      case 'tool_call_completed': {
        const name = childData?.toolName || ''
        const ok = childData?.success !== false
        const entry = [...container.tools].reverse()
          .find(t => t.name === name && t.status === 'running')
        if (entry) entry.status = ok ? 'completed' : 'error'
        break
      }
      case 'plan_created': {
        const steps = childData?.steps
        if (Array.isArray(steps)) {
          container.plan = { planId: childData?.planId ?? '', steps, currentStep: 0, stepResults: [] }
        }
        break
      }
      case 'plan_step_started': {
        if (container.plan && typeof childData?.index === 'number') {
          container.plan.currentStep = childData.index
        }
        break
      }
      case 'plan_step_completed': {
        if (container.plan && typeof childData?.index === 'number') {
          const results = [...(container.plan.stepResults || [])]
          results[childData.index] = { result: childData.result ?? '', status: 'completed' }
          container.plan.stepResults = results
        }
        break
      }
    }
    flushSegmentsToMessage()
  })

  // Per-child completion: fires as soon as each individual child agent finishes,
  // before the overall delegation_end, so the user sees incremental progress.
  stream.on('delegation_child_complete', (data) => {
    if (isStaleEvent(data)) return
    if (!currentAssistantId.value) return
    markDelegComplete(currentSegments.value, data.subagentId, data.childConversationId,
      !!data.success, data.resultPreview, data.durationMs)
    flushSegmentsToMessage()
  })

  stream.on('delegation_end', (data) => {
    if (isStaleEvent(data)) return
    if (!currentAssistantId.value) return
    const segs = currentSegments.value
    if (data.parallel) {
      if (Array.isArray(data.childResults) && data.childResults.length > 0) {
        for (const cr of data.childResults) {
          // delegation_child_complete usually closed each child already; only
          // patch those still running (e.g. a timed-out child).
          const seg = findDelegSegment(segs, cr.subagentId, cr.childConversationId)
          const stillRunning = seg ? seg.status === 'running'
            : !!(cr.subagentId && findNode(segs.flatMap(s => s.childTimeline?.children || []), cr.subagentId)?.status === 'running')
          if (stillRunning) {
            markDelegComplete(segs, cr.subagentId, cr.childConversationId, !!cr.success,
              cr.error || undefined, cr.durationMs)
          }
        }
      } else {
        // Legacy fallback: mark all remaining running top-level delegations.
        segs.filter((s: MessageSegment) =>
          s.type === 'tool_call' && s.status === 'running' && s.toolName?.startsWith('→'))
          .forEach((s: MessageSegment) => { s.status = data.success ? 'completed' : 'error' })
      }
    } else {
      markDelegComplete(segs, data.subagentId, data.childConversationId,
        !!data.success, data.resultPreview, data.durationMs)
    }
    flushSegmentsToMessage()
  })

  // Heartbeat watchdog flagged a subagent as making no observable progress.
  // Mark the matching segment/node stale so the timeline can surface it; the
  // subagent stays "running" (stale ≠ finished — it may still recover).
  stream.on('subagent_stale', (data) => {
    if (isStaleEvent(data)) return
    if (!currentAssistantId.value || !data.subagentId) return
    const segs = currentSegments.value
    const seg = findDelegSegment(segs, data.subagentId)
    if (seg) {
      seg.delegationStale = true
    } else {
      for (const s of segs) {
        const node = findNode(s.childTimeline?.children, data.subagentId)
        if (node) { node.stale = true; break }
      }
    }
    flushSegmentsToMessage()
  })

  // ===== Stream lifecycle (pre-token) events =====
  // These give the loading bar substantive status text in the gap between
  // "user pressed send" and "first token arrived" — eliminating the dead air
  // where the user only saw a spinner with no progress signal.
  stream.on('stream_started', (data) => {
    if (isStaleEvent(data)) return
    lifecycleStage.value = { stage: 'started', since: Date.now() }
  })

  stream.on('context_prepared', (data) => {
    if (isStaleEvent(data)) return
    lifecycleStage.value = { stage: 'context_prepared', detail: data, since: Date.now() }
  })

  stream.on('llm_request_sent', (data) => {
    if (isStaleEvent(data)) return
    lifecycleStage.value = { stage: 'llm_request_sent', detail: data, since: Date.now() }
  })

  // ===== Per-iteration boundaries (single-turn UX overhaul) =====
  stream.on('iteration_start', (data) => {
    if (isStaleEvent(data)) return
    // Force-close any running thinking/content segments — guarantees no segment
    // belonging to the next iteration is appended onto the previous one's tail.
    const segs = currentSegments.value
    for (const seg of segs) {
      if (seg.status === 'running' && (seg.type === 'thinking' || seg.type === 'content')) {
        seg.status = 'completed'
      }
    }
    // Stash iteration / scope on the array so segment factories pick them up.
    ;(currentSegments.value as any)._currentIteration = data.index ?? 0
    ;(currentSegments.value as any)._currentScope = data.scope ?? 'parent'
    ;(currentSegments.value as any)._currentSubagentId = data.subagentId
    flushSegmentsToMessage()
  })

  stream.on('iteration_end', (data) => {
    if (isStaleEvent(data)) return
    // Sentence-level repetition warning runs on the backend (content_truncated
    // event); we don't recompute it here. Just close any still-running
    // thinking/content segments belonging to the iteration that's wrapping up.
    const segs = currentSegments.value
    for (const seg of segs) {
      if (seg.status === 'running' && (seg.type === 'thinking' || seg.type === 'content')) {
        seg.status = 'completed'
      }
    }
    flushSegmentsToMessage()
  })

  stream.on('thinking_start', (data) => {
    if (isStaleEvent(data)) return
    // Pure analytics signal — thinking_delta will create the segment as needed.
    if (currentAssistantId.value) streamPhase.value = 'thinking'
  })

  stream.on('thinking_end', (data) => {
    if (isStaleEvent(data)) return
    // Auto-collapse decisions belong to ThinkingSegment.vue. We deliberately
    // do not flip status here — thinking_delta after thinking_end is rare but
    // valid (e.g. a late provider chunk), and we want it to extend the same
    // segment rather than open a new one.
  })

  stream.on('content_truncated', (data) => {
    if (isStaleEvent(data)) return
    // Mark the running content segment with a repetition warning so the UI
    // can render an inline informational banner.
    const segs = currentSegments.value
    const contentSeg = segs.findLast((s: MessageSegment) => s.type === 'content' && s.status === 'running')
    if (contentSeg) {
      contentSeg.repetitionWarning = data.reason
      contentSeg.truncatedChars = data.truncatedChars
    }
    flushSegmentsToMessage()
  })

  stream.on('tool_result_chunk', (data) => {
    if (isStaleEvent(data)) return
    // Streamed tool result delta — append to the matching tool_call segment.
    // tool_call_completed still fires separately and carries the canonical
    // success/result fields; this just lets large results stream in instead
    // of arriving as one giant blob.
    const segs = currentSegments.value
    const toolSeg = segs.find((s: MessageSegment) =>
      s.type === 'tool_call' && s.toolCallId === data.ref)
    if (toolSeg) {
      toolSeg.toolResult = (toolSeg.toolResult || '') + (data.delta || '')
      // Note: data.final = true just means the buffer for this tool's result
      // is exhausted. Final success/error state still arrives via
      // tool_call_completed, so we don't terminate the segment here.
    }
    flushSegmentsToMessage()
  })

  // Delegation batch envelopes are unpacked server-side into individual
  // delegation_progress events (see DelegateAgentTool.relayBatchEnvelope),
  // so the frontend only handles delegation_progress — no batch handler needed.

  stream.on('plan_created', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        updateMessage(currentAssistantId.value, {
          ...msg,
          metadata: {
            ...metadata,
            plan: { planId: data.planId, steps: data.steps, currentStep: 0 }
          }
        } as any)
      }
    }
  })

  stream.on('plan_step_started', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        if (metadata?.plan) {
          updateMessage(currentAssistantId.value, {
            ...msg,
            metadata: {
              ...metadata,
              plan: { ...metadata.plan, currentStep: data.index }
            }
          } as any)
        }
      }
    }
  })

  stream.on('plan_step_completed', (data) => {
    if (isStaleEvent(data)) return
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        if (metadata?.plan) {
          const plan = { ...metadata.plan }
          const stepResults = [...(plan.stepResults || [])]
          stepResults[data.index] = { result: data.result, status: 'completed' }
          updateMessage(currentAssistantId.value, {
            ...msg,
            metadata: {
              ...metadata,
              plan: { ...plan, stepResults }
            }
          } as any)
        }
      }
    }
  })

  // ===== Tool approval events (with idempotency dedup) =====

  stream.on('tool_approval_requested', (data) => {
    if (isStaleEvent(data)) return
    // Idempotency: process each pendingId only once
    if (data.pendingId && processedApprovalIds.has(data.pendingId)) {
      // duplicate approval ignored
      return
    }
    if (data.pendingId) processedApprovalIds.add(data.pendingId)

    streamPhase.value = 'awaiting_approval'

    let targetId = currentAssistantId.value
    if (!targetId) {
      const assistantMessages = messages.value.filter(m => m.role === 'assistant')
      if (assistantMessages.length > 0) {
        targetId = assistantMessages[assistantMessages.length - 1].id as string
      }
    }
    if (!targetId) {
      resetCurrentTurnState()
      const placeholder = createAssistantMessage('', streamConversationId)
      ;(placeholder as any)._turnId = activeTurnId
      targetId = placeholder.id as string
      currentAssistantId.value = targetId
    }

    const msg = getMessage(targetId)
    if (msg) {
      const metadata = parseMetadata((msg as any).metadata)
      const toolCalls = [...(metadata?.toolCalls || [])]
      for (let i = 0; i < toolCalls.length; i++) {
        if (toolCalls[i].status === 'running') {
          toolCalls[i] = { ...toolCalls[i], status: 'awaiting_approval' }
        }
      }
      updateMessage(targetId, {
        ...msg,
        status: 'awaiting_approval',
        metadata: {
          ...metadata,
          currentPhase: 'awaiting_approval',
          toolCalls,
          pendingApproval: {
            pendingId: data.pendingId,
            toolName: data.toolName,
            arguments: data.arguments,
            reason: data.reason,
            status: 'pending_approval',
            findings: data.findings || undefined,
            maxSeverity: data.maxSeverity || undefined,
            summary: data.summary || undefined,
          }
        }
      } as any)
    }
  })

  stream.on('tool_approval_resolved', (data) => {
    if (isStaleEvent(data)) return
    const targetMsg = messages.value.findLast((m) => {
      if (m.role !== 'assistant') return false
      const metadata = parseMetadata((m as any).metadata)
      return metadata?.pendingApproval?.pendingId === data.pendingId
    })
    if (targetMsg) {
      const targetId = targetMsg.id as string
      const msg = getMessage(targetId)
      if (msg) {
        const metadata = parseMetadata((msg as any).metadata)
        if (metadata?.pendingApproval) {
          // RFC-067 §4.10: every still-pending tool call on this gate message
          // must surface as a terminal state — both deny AND approve. RFC-067
          // §3 guarantees "one turn at most one pending", so any running/
          // awaiting entry IS the resolved one — skip strict (name, arguments)
          // matching since JSON formatting can drift between the live SSE
          // buffer and pendingApproval.arguments.
          //   approve → success=true  + result='[已批准]' → green ✓ on the gate
          //             row; the actual execution result is in the replayed
          //             assistant message that follows.
          //   deny    → success=false + result='[已拒绝]' → red ✗.
          // Both branches must also flip metadata.segments[] entries because
          // MessageBubble renders the timeline via ToolCallSegment.vue (driven
          // by metadata.segments, not metadata.toolCalls).
          const approved = data.decision === 'approved'
          const successFlag = approved
          const resultText = approved ? '[已批准]' : '[已拒绝]'
          const toolCalls = (metadata?.toolCalls || []).map((tc: any) => {
            const wasPending = tc.status === 'awaiting_approval' || tc.status === 'running'
            if (wasPending) {
              return { ...tc, status: 'completed', success: successFlag, result: resultText }
            }
            return tc
          })
          const segments = (metadata?.segments || []).map((seg: any) => {
            if (seg.type !== 'tool_call') return seg
            const wasPending = seg.status === 'running' || seg.status === 'awaiting_approval'
            if (wasPending) {
              return { ...seg, status: 'completed', toolSuccess: successFlag, toolResult: resultText }
            }
            return seg
          })
          // Sync currentSegments.value so the live streaming buffer agrees
          // with the persisted message metadata.
          for (let i = 0; i < currentSegments.value.length; i++) {
            const liveSeg: any = currentSegments.value[i]
            if (liveSeg.type !== 'tool_call') continue
            const wasPending = liveSeg.status === 'running' || liveSeg.status === 'awaiting_approval'
            if (wasPending) {
              liveSeg.status = 'completed'
              liveSeg.toolSuccess = successFlag
              liveSeg.toolResult = resultText
            }
          }
          updateMessage(targetId, {
            ...msg,
            status: 'completed',
            metadata: {
              ...metadata,
              currentPhase: 'completed',
              toolCalls,
              segments,
              pendingApproval: {
                ...metadata.pendingApproval,
                status: approved ? 'approved' : 'denied'
              }
            }
          } as any)
        }
      }
    }
    streamPhase.value = data.decision === 'approved' ? 'streaming' : 'completed'
  })

  // ===== Heartbeat events =====

  stream.on('heartbeat', (data: HeartbeatData) => {
    heartbeat.value = data
    // Heartbeat arrival means the connection is alive; useStream resets the timeout automatically.
    // Update phase from heartbeat only when the frontend doesn't have a more precise phase yet.
    if (data.currentPhase && streamPhase.value !== 'interrupting') {
      const phaseMap: Record<string, StreamPhase> = {
        'preparing_context': 'preparing_context',
        'reading_memory': 'reading_memory',
        'reasoning': 'reasoning',
        'drafting_answer': 'drafting_answer',
        'summarizing_observations': 'summarizing_observations',
        'thinking': 'thinking',
        'streaming': 'streaming',
        'executing_tool': 'executing_tool',
        'awaiting_approval': 'awaiting_approval',
        'finalizing': 'finalizing',
        'failed': 'failed',
      }
      const mapped = phaseMap[data.currentPhase]
      if (mapped) streamPhase.value = mapped
    }
    // Use heartbeat queueLength to reconcile local queue state.
    // Only clear when the message has been acknowledged by the backend (status=sending),
    // to avoid discarding a message whose interrupt request is still in flight.
    if (data.queueLength === 0 && messageQueue.hasQueued.value
        && messageQueue.queuedMessage.value?.status === 'sending') {
      messageQueue.clear()
    }
  })

  // ===== Interrupt + Queue events =====

  stream.on('turn_interrupt_requested', () => {
    streamPhase.value = 'interrupting'
  })

  stream.on('turn_interrupted', (data) => {
    if (isStaleEvent(data)) return
    // Current turn has been interrupted. Wait for the backend to resume with the queued message.
    // If the backend will auto-resume, the frontend does nothing extra.
    // Edge case: backend has no queued message but frontend does (should not happen in practice).
    if (data.hasQueuedMessage) {
      streamPhase.value = 'queued'
    }
  })

  stream.on('queued_input_accepted', (data) => {
    // Backend confirmed receipt of the queued message — mark as 'sending' to allow heartbeat cleanup
    messageQueue.markSending()
    streamPhase.value = 'queued'
  })

  stream.on('queued_input_started', (data) => {
    if (isStaleEvent(data)) return
    // Backend has started processing the queued message.
    // 1. Create the user message first (previous turn is now complete so ordering is correct)
    const queued = messageQueue.dequeue()
    const messageContent = data.message || queued?.content || ''
    if (messageContent) {
      const convId = data.conversationId || streamConversationId
      createUserMessage(messageContent, queued?.contentParts, convId)
    }
    // 2. Create the assistant placeholder message
    resetCurrentTurnState()
    const convId2 = data.conversationId || streamConversationId
    const assistantMessage = createAssistantMessage('', convId2)
    ;(assistantMessage as any)._turnId = activeTurnId
    currentAssistantId.value = assistantMessage.id as string
    streamPhase.value = options.thinkingLevel?.value === 'off' ? 'streaming' : 'thinking'
    phaseInfo.value = null
    // New turn — reset lifecycle so the loading bar shows pre-token progress.
    lifecycleStage.value = { stage: 'connecting', since: Date.now() }
  })

  // ===== Async task completion events (video / image / music generation) =====
  stream.on('async_task_completed', (data) => {
    if (isStaleEvent(data)) return
    if (!streamConversationId) return

    // Mark this taskId resolved so done-after-pending reconnect logic
    // doesn't keep the SSE alive longer than needed.
    if (data.taskId) pendingAsyncTaskIds.delete(data.taskId)

    // Failure path — surface error so user knows the task is over.
    if (!data.success) {
      const taskLabel = data.taskType === 'music_generation' ? '音乐'
        : data.taskType === 'video_generation' ? '视频'
        : data.taskType === 'image_generation' ? '图片'
        : '任务'
      addMessage({
        role: 'assistant',
        content: `${taskLabel}生成失败: ${data.errorMessage || '未知错误'}`,
        contentParts: [],
        status: 'completed',
        conversationId: streamConversationId,
      })
      return
    }

    let mediaPart: MessageContentPart | null = null
    if (data.videoUrl) {
      mediaPart = {
        type: 'video',
        fileUrl: data.videoUrl,
        fileName: `video_${data.taskId}.mp4`,
        contentType: 'video/mp4',
      } as MessageContentPart
    } else if (data.imageUrl) {
      mediaPart = {
        type: 'image',
        fileUrl: data.imageUrl,
        fileName: `image_${data.taskId}.png`,
        contentType: 'image/png',
      } as MessageContentPart
    } else if (data.audioUrl) {
      const fmt = (data.format || 'mp3') as string
      mediaPart = {
        type: 'audio',
        fileUrl: data.audioUrl,
        fileName: `music_${data.taskId}.${fmt}`,
        contentType: fmt === 'wav' ? 'audio/wav' : 'audio/mpeg',
      } as MessageContentPart
    } else if (data.modelUrl) {
      const fmt = ((data.format || 'glb') as string).toLowerCase()
      mediaPart = {
        type: 'model3d',
        fileUrl: data.modelUrl,
        fileName: `model_${data.taskId}.${fmt}`,
        contentType: fmt === 'obj' ? 'model/obj'
          : fmt === 'fbx' ? 'model/fbx'
          : fmt === 'usdz' ? 'model/vnd.usdz+zip'
          : 'model/gltf-binary',
      } as MessageContentPart
    }

    if (!mediaPart) return

    // Prefer appending to the current assistant message (avoids media appearing above the text reply)
    if (currentAssistantId.value) {
      const msg = getMessage(currentAssistantId.value)
      if (msg) {
        const existingParts = (msg as any).contentParts || []
        updateMessage(currentAssistantId.value, {
          contentParts: [...existingParts, mediaPart],
        } as any)
        return
      }
    }

    // Fallback: agent already finished — create a standalone message
    addMessage({
      role: 'assistant',
      content: '',
      contentParts: [mediaPart],
      status: 'completed',
      conversationId: streamConversationId,
    })
  })

  // ===== Auto TTS =====
  let ttsAutoModeCache: string | null = null
  let ttsCacheExpiry = 0

  async function triggerAutoTts(conversationId: string, text: string) {
    try {
      // Cache settings for 5 minutes to avoid a request on every message
      const now = Date.now()
      if (!ttsAutoModeCache || now > ttsCacheExpiry) {
        const res: any = await http.get('/system-settings')
        ttsAutoModeCache = res.data?.ttsAutoMode || 'off'
        ttsCacheExpiry = now + 5 * 60 * 1000
      }
      if (ttsAutoModeCache !== 'always') return
      // Kick off backend synthesis; the backend broadcasts tts_ready via SSE when done
      http.post('/tts/synthesize', { conversationId, text }).catch(() => {})
    } catch {
      // Silently ignore TTS errors — it's a best-effort feature
    }
  }

  // ===== Auto TTS: listen for tts_ready events =====
  stream.on('tts_ready', (data) => {
    if (data.audioUrl) {
      const token = localStorage.getItem('token') || ''
      fetch(data.audioUrl, { headers: { Authorization: `Bearer ${token}` } })
        .then(res => res.blob())
        .then(blob => {
          const url = URL.createObjectURL(blob)
          const audio = new Audio(url)
          audio.onended = () => URL.revokeObjectURL(url)
          audio.play().catch(() => URL.revokeObjectURL(url))
        })
        .catch(() => {})
    }
  })

  // ===== Goal events =====
  // Forward goal evaluator emissions to the goal store. The store owns
  // the active-goal cache + the per-conv "evaluating" flag that drives
  // the avatar ring's breathing halo.

  stream.on('goal_evaluated', (data) => {
    if (isStaleEvent(data)) return
    const cid = data?.conversationId || streamConversationId
    if (cid) goalStore.handleSseEvent(cid, 'goal_evaluated', data)
  })

  stream.on('goal_followup', (data) => {
    if (isStaleEvent(data)) return
    const cid = data?.conversationId || streamConversationId
    if (cid) goalStore.handleSseEvent(cid, 'goal_followup', data)
  })

  stream.on('goal_completed', (data) => {
    if (isStaleEvent(data)) return
    const cid = data?.conversationId || streamConversationId
    if (cid) goalStore.handleSseEvent(cid, 'goal_completed', data)
  })

  stream.on('goal_exhausted', (data) => {
    if (isStaleEvent(data)) return
    const cid = data?.conversationId || streamConversationId
    if (cid) goalStore.handleSseEvent(cid, 'goal_exhausted', data)
  })

  stream.on('goal_created', (data) => {
    if (isStaleEvent(data)) return
    const cid = data?.conversationId || streamConversationId
    if (cid) goalStore.handleSseEvent(cid, 'goal_created', data)
  })

  stream.on('goal_updated', (data) => {
    if (isStaleEvent(data)) return
    const cid = data?.conversationId || streamConversationId
    if (cid) goalStore.handleSseEvent(cid, 'goal_updated', data)
  })

  // ===== Send message (supports sending while generating) =====

  const sendMessage = async (content: string, options: SendMessageOptions) => {
    const { conversationId, agentId, attachments = [], contentParts = [] } = options

    // Approval commands bypass the interrupt logic
    const isApprovalCommand = /^\/(approve|deny)$/i.test(content.trim())

    // ===== Sending while generating: route to interrupt / queue path =====
    // _skipQueueRoute is set by the stale-state recovery path (when /interrupt
    // returned queued=false because the backend stream is gone). It forces a
    // single direct fresh-send attempt regardless of isGenerating, with a hard
    // cap on recursive entries to prevent infinite loops if something is
    // genuinely stuck.
    const skipQueueRoute = (options as any)._skipQueueRoute === true
    if (isGenerating.value && !isApprovalCommand && !skipQueueRoute) {
      return await handleInterruptOrQueue(content, options)
    }

    // ===== Normal send path =====
    // Clear the previous stop fallback timer to avoid killing the new connection
    if (stopFallbackTimer) {
      clearTimeout(stopFallbackTimer)
      stopFallbackTimer = null
    }
    // Disconnect the old stream when switching conversations to prevent event pollution
    if (streamConversationId && streamConversationId !== conversationId) {
      stream.disconnect()
      currentAssistantId.value = null
    }
    error.value = null
    errorFired = false
    streamConversationId = conversationId
    streamPhase.value = thinkingLevelRef?.value === 'off' ? 'streaming' : 'thinking'
    phaseInfo.value = null
    // Begin pre-token lifecycle. Subsequent stream_started / context_prepared /
    // llm_request_sent events override this; first delta clears it.
    lifecycleStage.value = { stage: 'connecting', since: Date.now() }

    try {
      if (!isApprovalCommand) {
        createUserMessage(content, contentParts, conversationId)
      }

      resetCurrentTurnState()
      const assistantMessage = createAssistantMessage('', conversationId)
      ;(assistantMessage as any)._turnId = activeTurnId
      currentAssistantId.value = assistantMessage.id as string

      // contentParts already includes file entries from buildOutgoingParts — do not re-merge attachments
      const body: Record<string, any> = {
        agentId,
        message: content,
        conversationId,
        contentParts,
      }
      if (options.thinkingLevel) {
        body.thinkingLevel = options.thinkingLevel
      }
      // Per-conversation model: the backend pins it onto the conversation row
      // so switching the model here never leaks into other conversations.
      if (options.modelProvider && options.modelName) {
        body.modelProvider = options.modelProvider
        body.modelName = options.modelName
      }
      await stream.connect(body)
    } catch (e) {
      error.value = e instanceof Error ? e : new Error(String(e))
      streamPhase.value = 'idle'
      throw e
    }
  }

  /**
   * Send a new message while one is already generating.
   * - Interruptible phases (thinking/streaming/executing_tool): send an interrupt request.
   * - Non-interruptible phases (awaiting_approval): queue the message.
   */
  const handleInterruptOrQueue = async (content: string, options: SendMessageOptions) => {
    const { conversationId, agentId } = options

    // Do not create the user message immediately — wait for queued_input_started so the
    // user message appears after the previous turn's reply, preserving correct ordering.
    // Add to the local queue now (saves contentParts for delayed creation).
    messageQueue.enqueue(content, options.contentParts, conversationId)

    try {
      const res = await fetchWithAuth(`${baseUrl}/api/v1/chat/${conversationId}/interrupt`, {
        method: 'POST',
        body: JSON.stringify({
          message: content,
          agentId,
          contentParts: options.contentParts || [],
        }),
      })
      const result = await res.json()

      if (result.data?.interrupted) {
        // Interruptible: backend initiated the interrupt; queued message will auto-resume
        streamPhase.value = 'interrupting'
        messageQueue.markSending()
      } else if (result.data?.queued) {
        // Non-interruptible but queued: will auto-resume when the current step ends.
        // (Backend now also returns queued=true while state.done is still within
        // its retention window — see ChatStreamTracker.enqueueMessage. This
        // closes the race that previously caused the new submit to bypass the
        // queue, race the previous turn's `done` handler, and merge into the
        // previous user message bubble.)
        streamPhase.value = 'queued'
      } else {
        // queued=false now means there's no producer to drain into:
        //   - state == null   → conversation cleaned up post-retention
        //   - state.done       → stream's doOnComplete already fired and
        //                         no consumer remains to call startQueuedMessage
        // Either way, accepting another queue entry would silently park
        // the message in memory until cleanup; instead, drop the local
        // queue entry and restart as a fresh send. Pass _skipQueueRoute
        // so the recursive sendMessage takes the normal path even if the
        // frontend's isGenerating still reads true (e.g. the previous
        // turn's `done` event hasn't landed yet) — without this flag the
        // recursion loops back into handleInterruptOrQueue and gets the
        // same queued=false response.
        console.warn('[useChat] interrupt returned queued=false (RunState gone or done); restarting as fresh send')
        const stale = messageQueue.dequeue()
        const restartParts = stale?.contentParts ?? options.contentParts
        // setTimeout(0) yields to any in-flight `done` handler that's
        // about to flip isGenerating itself; the _skipQueueRoute is the
        // belt-and-braces guard for the case where it never lands.
        setTimeout(() => {
          sendMessage(content, {
            ...options,
            contentParts: restartParts,
            _skipQueueRoute: true,
          } as SendMessageOptions & { _skipQueueRoute: boolean }).catch(err => {
            console.error('[useChat] restart-after-stale-interrupt failed:', err)
            error.value = err instanceof Error ? err : new Error(String(err))
          })
        }, 0)
      }
    } catch (e) {
      console.error('[useChat] Interrupt request failed:', e)
      // Interrupt failed: the backend never received the message, so the heartbeat/queue mechanism
      // cannot be relied upon. Fall back to making the message locally visible + clear the queue
      // to prevent silent message loss.
      const failedQueued = messageQueue.dequeue()
      if (failedQueued) {
        createUserMessage(failedQueued.content, failedQueued.contentParts, conversationId)
      }
      error.value = new Error('Failed to queue message, please resend')
    }
  }

  // Stop generation (user-initiated; does not auto-resume queued messages).
  //
  // Design: do not disconnect the SSE immediately — send a stop signal first and wait for the
  // backend to return a 'done' event. This ensures onStreamEnd fires and message/conversation
  // state is updated correctly.
  // A 3-second fallback timeout guards against 'done' never arriving due to network issues.
  const stopGeneration = async () => {
    // Freeze identifiers and install the fallback timer before any await, so a concurrent
    // resetForNewConversation cannot clear context out from under us.
    const convId = streamConversationId
    const assistantId = currentAssistantId.value

    // Only stop when the frontend is actively involved in the stream (receiving SSE /
    // reconnecting / awaiting approval). As a bystander we must not kill another user's run.
    const activelyStreaming = isGenerating.value
        || streamPhase.value === 'reconnecting'
        || streamPhase.value === 'awaiting_approval'

    if (!activelyStreaming) {
      // Not actually streaming — let the caller go straight to resetForNewConversation
      return
    }

    // Cancel queued message first
    messageQueue.clear()

    // Mark as stopped immediately so the UI gives instant feedback
    streamPhase.value = 'stopped'
    phaseInfo.value = null
    compactStatus.value = null

    // Install fallback timer before any await so it is not missed by a concurrent resetForNewConversation
    if (stopFallbackTimer) clearTimeout(stopFallbackTimer)
    stopFallbackTimer = setTimeout(() => {
      stopFallbackTimer = null
      console.warn('[useChat] Stop fallback: done event not received within 3s, force cleanup')
      // Only disconnect if the stream still belongs to the old conversation — avoids killing a new session's stream
      if (streamConversationId === convId || !streamConversationId) {
        stream.disconnect()
      }
      if (currentAssistantId.value === assistantId && assistantId) {
        setMessageStatus(assistantId, 'stopped')
        currentAssistantId.value = null
      }
      onStreamEnd?.({
        conversationId: convId,
        reason: 'stopped',
      })
    }, 3000)

    // Cancel the fallback timer when the done/error event arrives
    const unsubscribe = stream.on('done', () => {
      if (stopFallbackTimer) { clearTimeout(stopFallbackTimer); stopFallbackTimer = null }
      unsubscribe()
    })
    const unsubscribeError = stream.on('error', () => {
      if (stopFallbackTimer) { clearTimeout(stopFallbackTimer); stopFallbackTimer = null }
      unsubscribeError()
    })

    // Send the backend stop request (fire-and-forget, does not block resetForNewConversation)
    if (convId) {
      fetchWithAuth(`${baseUrl}/api/v1/chat/${convId}/stop`, {
        method: 'POST',
      }).catch(e => {
        console.warn('[useChat] Stop API failed:', e)
      })
    }
  }

  // Cancel the queued message
  const cancelQueued = () => {
    messageQueue.cancel()
    // Notify backend (fire-and-forget)
    if (streamConversationId) {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' }
      if (token) headers.Authorization = `Bearer ${token}`
      // No dedicated cancel-queue API — the stop semantic covers this case
    }
    if (streamPhase.value === 'queued') {
      streamPhase.value = isGenerating.value ? 'streaming' : 'idle'
    }
  }

  // Reconnect to a stream that is already running on the backend
  const reconnectStream = async (conversationId: string) => {
    if (isGenerating.value) return

    // Clear any leftover stop fallback timer
    if (stopFallbackTimer) { clearTimeout(stopFallbackTimer); stopFallbackTimer = null }
    streamPhase.value = 'reconnecting'
    streamConversationId = conversationId
    error.value = null
    errorFired = false
    phaseInfo.value = null

    resetCurrentTurnState()

    // Remove trailing empty assistant messages left over from a killed run or a stale placeholder.
    // Prevents "two bubbles" appearing when the reconnect creates a new streaming placeholder.
    while (messages.value.length > 0) {
      const tail = messages.value[messages.value.length - 1]
      if (tail && tail.role === 'assistant'
          && tail.conversationId === conversationId
          && !tail.content
          && (!tail.contentParts || tail.contentParts.length === 0)) {
        messages.value.pop()
      } else {
        break
      }
    }

    const assistantMessage = createAssistantMessage('', conversationId)
    ;(assistantMessage as any)._turnId = activeTurnId
    currentAssistantId.value = assistantMessage.id as string

    try {
      // connect() owns lastEventId injection — it knows whether the dedup
      // state still applies to this conversation. Passing it from out here
      // would race the per-conv reset that connect does and could leak a
      // different conv's id into this reconnect.
      await stream.connect({
        conversationId,
        reconnect: true,
      })
    } catch (e) {
      console.error('[useChat] Reconnect failed:', e)
      // Reconnect failed — clean up the placeholder message
      const msgIndex = messages.value.findIndex(m => m.id === currentAssistantId.value)
      if (msgIndex >= 0) {
        const msg = messages.value[msgIndex]
        // Remove the placeholder if it has no content
        if (!msg.content && (!msg.contentParts || msg.contentParts.length === 0)) {
          messages.value.splice(msgIndex, 1)
        } else {
          setMessageStatus(currentAssistantId.value!, 'completed')
        }
      }
      currentAssistantId.value = null
      streamPhase.value = 'idle'
      error.value = e instanceof Error ? e : new Error('重连失败: ' + String(e))
    }
  }

  // Regenerate a message
  const regenerate = async (messageId: string | number) => {
    const message = getMessage(messageId)
    if (!message) return

    const index = messages.value.findIndex(m => m.id === messageId)
    if (index <= 0) return

    const userMessage = messages.value[index - 1]
    if (userMessage.role !== 'user') return

    messages.value = messages.value.filter(m => m.id !== messageId)

    const text = userMessage.contentParts
      .filter(p => p.type === 'text')
      .map(p => p.text || '')
      .join('\n') || userMessage.content || ''

    await sendMessage(text, {
      conversationId: userMessage.conversationId,
      agentId: '',
    })
  }

  /** Fully reset stream context — call when switching or creating a conversation to prevent state pollution */
  const resetForNewConversation = () => {
    stream.disconnect()
    streamConversationId = ''
    currentAssistantId.value = null
    currentSegments.value = []
    segIdCounter.value = 0
    streamPhase.value = 'idle'
    phaseInfo.value = null
    compactStatus.value = null
    lifecycleStage.value = null
    error.value = null
    messageQueue.clear()
    if (stopFallbackTimer) {
      clearTimeout(stopFallbackTimer)
      stopFallbackTimer = null
    }
  }

  return {
    messages,
    isGenerating,
    streamPhase,
    phaseInfo,
    error,
    queuedMessage: messageQueue.queuedMessage,
    hasQueued: messageQueue.hasQueued,
    queueSize: messageQueue.queueSize,
    heartbeat,
    compactStatus,
    lifecycleStage,
    sendMessage,
    stopGeneration,
    cancelQueued,
    regenerate,
    addMessage,
    clearMessages,
    reconnectStream,
    resetForNewConversation,
  }
}

export default useChat
