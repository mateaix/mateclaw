<template>
  <div v-if="isLoading && !hideBar" class="stream-loading-bar">
    <div class="stream-loading-content">
      <span class="loading-icon" :class="phaseIconClass">{{ phaseIcon }}</span>
      <div class="loading-copy">
        <span class="loading-text" :class="phaseTextClass">{{ statusText }}</span>
        <span v-if="runningToolName" class="loading-tool">{{ runningToolName }}</span>
      </div>
    </div>
    <div class="loading-right">
      <!-- 排队指示器 -->
      <span v-if="hasQueued" class="queued-badge">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/>
          <line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/>
        </svg>
        {{ t('chat.queuedBadge', { count: 1 }) }}
      </span>
      <div v-if="showStats" class="loading-stats">
        <span class="stat">{{ elapsedTime }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import type { PhaseEventData, StreamPhase } from '@/types'

/** Pre-token lifecycle stage from useChat. Drives the loading copy in the
 *  window between "user sent" and "first token landed". */
interface LifecycleStage {
  stage: 'connecting' | 'started' | 'context_prepared' | 'llm_request_sent' | 'streaming'
  detail?: any
  since: number
}

/** Latest compact_status SSE event from useChat. Renders an inline chip
 *  so the user can see that the pause is the window manager compacting
 *  history, not a network stall. */
interface CompactStatus {
  status: 'start' | 'pair_safe' | 'summarize' | 'done' | 'skipped' | 'failed'
  preTokens?: number
  postTokens?: number
  messagesIn?: number
  messagesSummarized?: number
  tailKept?: number
  toolResultsSpilled?: number
  reason?: string
  anchored?: boolean
  fromCache?: boolean
  movedFrom?: number
  movedTo?: number
  summaryBudget?: number
  trigger?: string
  fallbackKept?: number
  timestamp?: number
}

interface Props {
  isLoading: boolean
  toolCount?: number
  hideBar?: boolean
  showStats?: boolean
  completionTokens?: number
  promptTokens?: number
  /** 当前流阶段 */
  phase?: StreamPhase
  /** 最近一次阶段事件 */
  phaseInfo?: PhaseEventData | null
  /** 当前正在执行的工具名称 */
  runningToolName?: string
  /** 是否有排队消息 */
  hasQueued?: boolean
  /** Fine-grained pre-token stage. Preferred over `phase` while no token has arrived. */
  lifecycleStage?: LifecycleStage | null
  /** Latest compact_status event. When non-null and not 'done', the bar shows compaction copy. */
  compactStatus?: CompactStatus | null
}

const props = withDefaults(defineProps<Props>(), {
  toolCount: 0,
  hideBar: false,
  showStats: true,
  completionTokens: 0,
  promptTokens: 0,
  phase: 'thinking',
  phaseInfo: null,
  runningToolName: '',
  hasQueued: false,
  lifecycleStage: null,
  compactStatus: null,
})

const { t } = useI18n()

// 将 14 个内部阶段映射为 3 个面向用户的状态：思考中 / 执行中 / 撰写中
const userFacingPhase = computed(() => {
  switch (props.phase) {
    case 'preparing_context':
    case 'reading_memory':
    case 'reasoning':
    case 'thinking':
    case 'summarizing_observations':
    case 'queued':
    case 'reconnecting':
      return 'thinking'
    case 'executing_tool':
    case 'awaiting_approval':
      return 'working'
    case 'drafting_answer':
    case 'streaming':
    case 'finalizing':
      return 'writing'
    case 'failed':
      return 'failed'
    case 'interrupting':
    case 'stopped':
      return 'stopped'
    default:
      return 'thinking'
  }
})

const userPhaseI18nMap: Record<string, string> = {
  thinking: 'chat.streamThinking',
  working: 'chat.streamExecutingTool',
  writing: 'chat.streamGenerating',
  failed: 'chat.streamFailed',
  stopped: 'chat.streamStopped',
}

const lifecycleI18nMap: Record<string, string> = {
  connecting: 'chat.streamConnecting',
  started: 'chat.streamStarted',
  context_prepared: 'chat.streamContextPrepared',
  llm_request_sent: 'chat.streamLlmRequestSent',
}

/** True iff the pre-token lifecycle is active (no first delta yet). */
const inPreTokenWindow = computed(() => {
  const ls = props.lifecycleStage
  return !!ls && ls.stage !== 'streaming'
})

/**
 * Compaction copy. Takes priority over both lifecycleStage and phase
 * while the compactor is mid-pass (any status except done/skipped/failed)
 * because the user cares more about "we paused to compact" than the
 * underlying llm-request lifecycle. After done/skipped/failed we let the
 * regular phase text take over — the chip's transient hint suffices.
 */
const compactStatusText = computed(() => {
  const cs = props.compactStatus
  if (!cs) return ''
  switch (cs.status) {
    case 'start':
      return cs.preTokens
        ? t('chat.compactStartWithTokens', { tokens: formatTokens(cs.preTokens) })
        : t('chat.compactStart')
    case 'pair_safe':
      return t('chat.compactPairSafe')
    case 'summarize': {
      const n = cs.messagesSummarized ?? cs.messagesIn ?? 0
      return n > 0
        ? t('chat.compactSummarizeWithCount', { count: n })
        : t('chat.compactSummarize')
    }
    default:
      return ''
  }
})

const isCompactActive = computed(() => {
  const s = props.compactStatus?.status
  return s === 'start' || s === 'pair_safe' || s === 'summarize'
})

function formatTokens(n: number): string {
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k tokens`
  return `${n} tokens`
}

const statusText = computed(() => {
  // Compaction copy wins while a pass is in flight. Done/skipped/failed
  // fall through to the regular phase text so the chip releases focus.
  if (isCompactActive.value) {
    const cText = compactStatusText.value
    if (cText) return cText
  }
  // Prefer fine-grained pre-token text when no first delta has arrived yet.
  if (inPreTokenWindow.value && props.lifecycleStage) {
    const key = lifecycleI18nMap[props.lifecycleStage.stage]
    if (key) {
      const base = t(key)
      // Append "(Xs elapsed)" once the same stage has lingered for >= 5s, so
      // the user can see something is still happening even if the next stage
      // is delayed (e.g. a slow context_prepared → llm_request_sent gap).
      const sec = Math.floor((Date.now() - props.lifecycleStage.since) / 1000)
      if (sec >= 5 && stageTickSec.value >= 5) {
        return base + t('chat.streamElapsedSuffix', { sec: stageTickSec.value })
      }
      return base
    }
  }
  const key = userPhaseI18nMap[userFacingPhase.value]
  if (!key) return ''
  return t(key)
})

const phaseIcon = computed(() => {
  switch (userFacingPhase.value) {
    case 'thinking': return '◐'
    case 'working': return '⚙'
    case 'writing': return '▸'
    case 'failed': return '!'
    case 'stopped': return '⊘'
    default: return '◐'
  }
})

const phaseIconClass = computed(() => {
  switch (userFacingPhase.value) {
    case 'failed':
    case 'stopped':
      return 'icon-warning'
    default:
      return 'icon-active'
  }
})

const phaseTextClass = computed(() => {
  switch (userFacingPhase.value) {
    case 'failed':
    case 'stopped':
      return 'text-red'
    default:
      return ''
  }
})


// 耗时统计
const elapsedSeconds = ref(0)
const elapsedTime = ref('0s')

/** Seconds elapsed since the current lifecycleStage became active. Used by
 *  statusText to decide whether to append the "(Xs elapsed)" hint. Tick at
 *  1 Hz only while the pre-token window is open to keep idle cost minimal. */
const stageTickSec = ref(0)
let stageTimer: ReturnType<typeof setInterval> | null = null


let timerInterval: ReturnType<typeof setInterval> | null = null

watch(() => props.isLoading, (loading) => {
  if (loading) {
    if (timerInterval) clearInterval(timerInterval)
    elapsedSeconds.value = 0
    elapsedTime.value = '0s'

    timerInterval = setInterval(() => {
      elapsedSeconds.value += 1
      const secs = elapsedSeconds.value
      if (secs < 60) {
        elapsedTime.value = `${secs}s`
      } else {
        const mins = Math.floor(secs / 60)
        const remainSecs = secs % 60
        elapsedTime.value = `${mins}m ${remainSecs}s`
      }
    }, 1000)
  } else {
    if (timerInterval) {
      clearInterval(timerInterval)
      timerInterval = null
    }
  }
}, { immediate: true })


// Tick the stage-elapsed counter only while the pre-token window is open.
// Reset on every stage transition so the "(Xs elapsed)" hint reflects time
// in the *current* stage, not total time since send.
watch(() => props.lifecycleStage, (ls) => {
  if (stageTimer) { clearInterval(stageTimer); stageTimer = null }
  stageTickSec.value = 0
  if (ls && ls.stage !== 'streaming') {
    stageTimer = setInterval(() => {
      stageTickSec.value = Math.floor((Date.now() - ls.since) / 1000)
    }, 1000)
  }
}, { immediate: true })

onBeforeUnmount(() => {
  if (timerInterval) {
    clearInterval(timerInterval)
    timerInterval = null
  }
  if (stageTimer) {
    clearInterval(stageTimer)
    stageTimer = null
  }
})
</script>

<style scoped>
.stream-loading-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  font-size: 14px;
  animation: subtle-pulse 1.5s ease-in-out infinite;
  color: var(--mc-text-secondary, #64748b);
}

.stream-loading-content {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  color: var(--mc-primary, #d96d46);
}

.loading-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.loading-icon {
  font-size: 16px;
  font-weight: bold;
}

.icon-active {
  animation: icon-pulse 1.2s ease-in-out infinite;
  color: var(--mc-primary, #d96d46);
}

.icon-warning {
  color: var(--mc-danger, #ef4444);
  animation: none;
}

.loading-text {
  font-weight: 500;
  color: var(--mc-primary, #d96d46);
}

.text-red { color: var(--mc-danger, #ef4444); }

.loading-tool {
  align-self: flex-start;
  font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  background: var(--mc-primary-light, rgba(217, 119, 87, 0.1));
  padding: 1px 6px;
  border-radius: 4px;
  color: var(--mc-primary, #d96d46);
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.loading-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.queued-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--mc-info, #3b82f6);
  background: rgba(59, 130, 246, 0.08);
  padding: 2px 8px;
  border-radius: 10px;
}

.loading-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--mc-text-tertiary, #94a3b8);
}

.stat {
  display: flex;
  align-items: center;
}

@keyframes icon-pulse {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.7;
    transform: scale(1.1);
  }
}

@keyframes subtle-pulse {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0.85;
  }
}
</style>
