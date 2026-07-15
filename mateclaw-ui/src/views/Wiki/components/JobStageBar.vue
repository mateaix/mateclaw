<template>
  <div class="job-stage-bar">
    <div class="stage-dots">
      <div
        v-for="(stage, idx) in stages"
        :key="stage.key"
        class="stage-dot-group"
      >
        <div
          class="stage-dot"
          :class="dotClass(stage.key)"
          :title="stage.key === currentStage && errorCode ? `${errorCode}: ${errorMessage}` : ''"
        />
        <span v-if="idx < stages.length - 1" class="stage-line" :class="{ done: isStageComplete(stage.key) }" />
      </div>
    </div>
    <div class="stage-labels">
      <div
        v-for="(stage, idx) in stages"
        :key="stage.key"
        class="stage-label-cell"
      >
        <span class="stage-label" :class="{ active: stage.key === currentStage && !isTerminal, done: stage.key === currentStage && isTerminal }">
          {{ t(`wiki.jobStage.${stage.key}`) }}
        </span>
      </div>
    </div>

    <!-- Model & progress info -->
    <div class="stage-info">
      <span v-if="currentModel" class="info-model">
        {{ t('wiki.jobInfo.currentModel') }}: {{ currentModel }}
      </span>
      <span v-if="isFallbackActive" class="info-fallback">
        ⚡ {{ t('wiki.jobInfo.fallbackActive') }}
      </span>
      <span v-if="pagesProgress" class="info-pages">
        {{ pagesProgress }}
      </span>
      <span v-if="elapsed" class="info-elapsed">
        {{ t('wiki.jobInfo.elapsedTime') }}: {{ elapsed }}
      </span>
    </div>

    <!-- Error state with actions -->
    <div v-if="status === 'failed'" class="stage-error">
      <span class="error-badge">❌ {{ t('wiki.jobStage.failed') }} — {{ errorCode }}</span>
      <div v-if="canManageWiki" class="error-actions">
        <button class="btn-mini" @click="$emit('reprocess')">{{ t('wiki.reprocess') }}</button>
        <button class="btn-mini btn-mini-alt" @click="$emit('repair')">{{ t('wiki.page.repair') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

const { t } = useI18n()
const workspace = useWorkspaceStore()

// Reprocess / repair are write actions — viewers see job status without them.
const canManageWiki = computed(() => workspace.can('manage:wiki'))

const props = defineProps<{
  stage: string
  status: string
  currentModel?: string
  isFallbackActive?: boolean
  errorCode?: string
  errorMessage?: string
  done?: number
  total?: number
  startedAt?: string
}>()

defineEmits<{
  reprocess: []
  repair: []
}>()

const stages = [
  { key: 'queued' },
  { key: 'routing' },
  { key: 'phase_a_running' },
  { key: 'phase_b_running' },
  { key: 'enriching' },
  { key: 'embedding' },
  { key: 'completed' },
]

const stageOrder = stages.map(s => s.key)

// Map backend stage values (including intermediates/terminals not shown as dots)
// to their logical position in the visible stage list.
const stageMapping: Record<string, string> = {
  phase_a_done: 'phase_b_running',  // between phase_a and phase_b → show as phase_b active
  failed: 'completed',              // terminal → all dots done up to failure point
  partial: 'completed',
  cancelled: 'completed',
}

const currentStage = computed(() => {
  const raw = props.stage
  return stageMapping[raw] ?? raw
})

// True when the job has reached any terminal state (dots should not pulse)
const isTerminal = computed(() =>
  props.status === 'completed' || props.status === 'failed' || props.status === 'partial' || props.status === 'cancelled'
)

// True specifically for failure terminals (dots show red instead of done)
const isTerminalFailure = computed(() =>
  props.status === 'failed' || props.status === 'partial' || props.status === 'cancelled'
)

function stageIndex(key: string): number {
  return stageOrder.indexOf(key)
}

function isStageComplete(key: string): boolean {
  const cur = stageIndex(currentStage.value)
  const target = stageIndex(key)
  if (cur < 0) return false
  return target < cur
}

function dotClass(key: string) {
  const cur = stageIndex(currentStage.value)
  const target = stageIndex(key)
  if (cur < 0) return 'pending' // unknown stage → all pending
  if (isTerminalFailure.value) {
    // Terminal failure: dots before failure point are done, failure point is red
    if (target < cur) return 'done'
    if (target === cur) return 'failed'
    return 'pending'
  }
  if (isTerminal.value) {
    // Successful terminal (completed): all dots up to and including current are done (no pulse)
    if (target <= cur) return 'done'
    return 'pending'
  }
  if (target < cur) return 'done'
  if (target === cur) return 'active'
  return 'pending'
}

const pagesProgress = computed(() => {
  if (props.total && props.total > 0) {
    return t('wiki.jobInfo.pagesProgress', { done: props.done ?? 0, total: props.total })
  }
  return ''
})

const elapsed = computed(() => {
  if (!props.startedAt) return ''
  const start = new Date(props.startedAt).getTime()
  const now = Date.now()
  const sec = Math.floor((now - start) / 1000)
  if (sec < 60) return `${sec}s`
  return `${Math.floor(sec / 60)}m ${sec % 60}s`
})
</script>

<style scoped>
.job-stage-bar {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 0;
}

.stage-dots {
  display: flex;
  align-items: center;
  gap: 0;
}

.stage-dot-group {
  display: flex;
  align-items: center;
  flex: 1;
}

/* The last cell only contains a dot (no trailing connector line). With
 * flex:1 it would reserve a full segment of empty space after the dot,
 * which makes the final stage look stranded — the connector going into
 * it appears to stop short and there's a visible blank gap on the right.
 * Pin the last cell to dot-width so the 6 connector lines distribute
 * evenly between the 7 dots and the final dot anchors to the right edge. */
.stage-dot-group:last-child {
  flex: 0 0 auto;
}

.stage-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  transition: all 0.3s;
}

.stage-dot.done { background: var(--mc-primary); }
.stage-dot.active { background: var(--mc-primary); box-shadow: 0 0 0 3px rgba(217,119,87,0.25); animation: pulse 1.5s infinite; }
.stage-dot.pending { background: var(--mc-border); }
.stage-dot.failed { background: var(--mc-danger); box-shadow: 0 0 0 3px rgba(245,108,108,0.2); }

@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 3px rgba(217,119,87,0.25); }
  50% { box-shadow: 0 0 0 5px rgba(217,119,87,0.1); }
}

.stage-line {
  flex: 1;
  height: 2px;
  background: var(--mc-border);
  margin: 0 2px;
  transition: background 0.3s;
}
.stage-line.done { background: var(--mc-primary); }

/* Labels mirror the dot-row's flex structure so each label cell is the
 * same width as its matching dot cell. The last cell pins to dot-width
 * (matching .stage-dot-group:last-child above), and inside every cell the
 * label is centered horizontally over the dot at the cell's left edge by
 * shifting it half the dot width and translating back by half its own
 * width — keeping label text centered above each dot at any container
 * width. */
.stage-labels {
  display: flex;
  align-items: flex-start;
}
.stage-label-cell {
  flex: 1;
  display: flex;
  justify-content: flex-start;
  overflow: visible;
}
.stage-label-cell:last-child {
  flex: 0 0 auto;
}
.stage-label {
  font-size: 9px;
  color: var(--mc-text-tertiary);
  text-align: center;
  white-space: nowrap;
  margin-left: 5px;
  transform: translateX(-50%);
}
.stage-label.active { color: var(--mc-primary); font-weight: 600; }
.stage-label.done { color: var(--mc-success, #5a8a5a); font-weight: 600; }

.stage-info {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11px;
  color: var(--mc-text-secondary);
}
.info-fallback { color: var(--mc-primary); font-weight: 500; }
.info-pages { font-variant-numeric: tabular-nums; }

.stage-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  background: var(--mc-danger-bg);
  border-radius: 8px;
}
.error-badge { font-size: 11px; color: var(--mc-danger); font-weight: 500; }
.error-actions { display: flex; gap: 6px; }
.btn-mini {
  padding: 3px 10px;
  font-size: 11px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  cursor: pointer;
}
.btn-mini:hover { background: var(--mc-bg-sunken); }
.btn-mini-alt { color: var(--mc-primary); border-color: var(--mc-primary); }
</style>
