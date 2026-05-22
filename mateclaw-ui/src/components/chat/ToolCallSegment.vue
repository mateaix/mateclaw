<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { Loading, Select, CloseBold, ArrowDown, Document, Setting, Connection } from '@element-plus/icons-vue'
import { useToolLabel } from '@/composables/useToolLabel'
import type { MessageSegment } from '@/types'

const props = defineProps<{
  segment: MessageSegment
}>()

const { getToolLabel } = useToolLabel()

const expanded = ref(props.segment.status === 'running')

// running → completed 时自动折叠
watch(() => props.segment.status, (val) => {
  if (val !== 'running') expanded.value = false
})

/** Delegation segments are identified by the → prefix injected in useChat.ts. */
const isDelegation = computed(() => (props.segment.toolName || '').startsWith('→'))

const displayName = computed(() => {
  const raw = props.segment.toolName || ''
  // Strip the → prefix for delegation segments so getToolLabel works cleanly,
  // then prepend it back as a visual indicator.
  if (isDelegation.value) return `→ ${getToolLabel(raw.slice(1).trim())}`
  return getToolLabel(raw)
})

const truncatedArgs = computed(() => {
  const args = props.segment.toolArgs || ''
  if (!args) return ''
  try {
    const parsed = JSON.parse(args)
    const vals = Object.values(parsed).filter(v => typeof v === 'string') as string[]
    const s = vals.join(', ')
    return s.length > 80 ? s.slice(0, 80) + '...' : s
  } catch {
    // Multi-line delegation progress — show first line only in collapsed view
    const firstLine = args.split('\n')[0].trim()
    return firstLine.length > 80 ? firstLine.slice(0, 80) + '...' : firstLine
  }
})

const resultPreview = computed(() => {
  const r = props.segment.toolResult || ''
  return r.length <= 600 ? r : r.slice(0, 600) + '\n... [truncated]'
})

const isRead = computed(() => {
  const n = props.segment.toolName || ''
  return n.includes('read') || n.includes('Read')
})

const isSuccess = computed(() => props.segment.status === 'completed' && props.segment.toolSuccess !== false)
const isError = computed(() => props.segment.status === 'error' || props.segment.toolSuccess === false)
const isRunning = computed(() => props.segment.status === 'running')

// Nested subagent timeline relayed from the child conversation: the child's own
// plan checklist + the tools it called. Only present on delegation segments.
const childTimeline = computed(() => isDelegation.value ? props.segment.childTimeline : undefined)
const childPlan = computed(() => childTimeline.value?.plan)
const childTools = computed(() => childTimeline.value?.tools || [])
const hasChildActivity = computed(() =>
  !!childPlan.value || childTools.value.length > 0
)

// The body is expandable when there's any nested detail to show – either the
// child's activity timeline or the final result preview.
const hasBody = computed(() => hasChildActivity.value || !!props.segment.toolResult)

function childStepStatus(i: number): 'pending' | 'running' | 'completed' {
  const plan = childPlan.value
  if (!plan) return 'pending'
  const done = plan.stepResults?.[i]
  if (done?.status === 'completed') return 'completed'
  if (i === plan.currentStep) return 'running'
  return 'pending'
}

// Compact progress hint shown in the delegation header (e.g. "2/3" plan steps,
// or "4 tools") so collapsed delegations still convey what the subagent did.
const childProgress = computed(() => {
  const plan = childPlan.value
  if (plan?.steps?.length) {
    const done = plan.stepResults?.filter(r => r?.status === 'completed').length || 0
    return `${done}/${plan.steps.length}`
  }
  const n = childTools.value.length
  return n ? `${n} ${n === 1 ? 'tool' : 'tools'}` : ''
})
</script>

<template>
  <div class="seg-tool" :class="{ 'is-running': isRunning, 'is-success': isSuccess, 'is-error': isError }">
    <div class="seg-tool__header" @click="hasBody ? (expanded = !expanded) : null">
      <span class="seg-tool__status">
        <el-icon v-if="isRunning" class="is-loading" :size="13"><Loading /></el-icon>
        <el-icon v-else-if="isSuccess" :size="13"><Select /></el-icon>
        <el-icon v-else :size="13"><CloseBold /></el-icon>
      </span>
      <span class="seg-tool__type-icon">
        <el-icon v-if="isDelegation" :size="12"><Connection /></el-icon>
        <el-icon v-else-if="isRead" :size="12"><Document /></el-icon>
        <el-icon v-else :size="12"><Setting /></el-icon>
      </span>
      <span class="seg-tool__name">{{ displayName }}</span>
      <span v-if="isDelegation && childProgress" class="seg-tool__badge">{{ childProgress }}</span>
      <span v-if="truncatedArgs" class="seg-tool__args">{{ truncatedArgs }}</span>
      <el-icon
        v-if="hasBody"
        class="seg-tool__arrow"
        :class="{ 'is-open': expanded }"
        :size="11"
      ><ArrowDown /></el-icon>
    </div>
    <Transition name="seg-slide">
      <div v-if="expanded && hasBody" class="seg-tool__body">
        <!-- Nested subagent timeline (delegation segments) -->
        <div v-if="hasChildActivity" class="seg-child">
          <!-- The child agent's own plan checklist, if it ran in plan mode -->
          <div v-if="childPlan" class="seg-child__plan">
            <div
              v-for="(step, i) in childPlan.steps"
              :key="i"
              class="seg-child__step"
              :class="`is-${childStepStatus(i)}`"
            >
              <el-icon v-if="childStepStatus(i) === 'running'" class="is-loading" :size="11"><Loading /></el-icon>
              <el-icon v-else-if="childStepStatus(i) === 'completed'" :size="11"><Select /></el-icon>
              <span v-else class="seg-child__dot"></span>
              <span class="seg-child__step-text">{{ step }}</span>
            </div>
          </div>
          <!-- The tools the child agent called -->
          <div v-if="childTools.length" class="seg-child__tools">
            <div
              v-for="(t, i) in childTools"
              :key="i"
              class="seg-child__tool"
              :class="`is-${t.status}`"
            >
              <el-icon v-if="t.status === 'running'" class="is-loading" :size="11"><Loading /></el-icon>
              <el-icon v-else-if="t.status === 'completed'" :size="11"><Select /></el-icon>
              <el-icon v-else :size="11"><CloseBold /></el-icon>
              <span class="seg-child__tool-name">{{ getToolLabel(t.name) }}</span>
            </div>
          </div>
        </div>
        <!-- Final tool/agent result preview -->
        <pre v-if="segment.toolResult">{{ resultPreview }}</pre>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.seg-tool {
  margin: 2px 0;
  border-left: 3px solid var(--mc-border-light);
  border-radius: 0 var(--mc-radius-sm, 6px) var(--mc-radius-sm, 6px) 0;
  transition: all 0.25s cubic-bezier(0.25, 0.46, 0.45, 0.94);
}
.seg-tool:hover {
  background: var(--mc-bg-muted);
  transform: translateX(2px);
  box-shadow: 0 2px 8px rgba(217, 109, 70, 0.08);
}
.seg-tool.is-running {
  border-left-color: var(--mc-primary);
  background: var(--mc-primary-bg);
}
.seg-tool.is-success {
  border-left-color: var(--mc-success);
}
.seg-tool.is-error {
  border-left-color: var(--mc-danger);
}

.seg-tool__header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  font-size: 13px;
  cursor: pointer;
  color: var(--mc-text-secondary);
  user-select: none;
  transition: color 0.15s;
}
.seg-tool__header:hover {
  color: var(--mc-text-primary);
}

.seg-tool__status {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.is-success .seg-tool__status { color: var(--mc-success); }
.is-error .seg-tool__status { color: var(--mc-danger); }
.is-running .seg-tool__status { color: var(--mc-primary); }

.seg-tool__type-icon {
  display: flex;
  align-items: center;
  color: var(--mc-text-tertiary);
}

.seg-tool__name {
  font-weight: 500;
  color: var(--mc-text-primary);
  white-space: nowrap;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.seg-tool__args {
  flex: 1;
  min-width: 0;
  font-family: var(--mc-font-mono, 'SF Mono', 'Menlo', 'Consolas', monospace);
  font-size: 12px;
  color: var(--mc-text-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: rgba(217, 109, 70, 0.06);
  padding: 1px 5px;
  border-radius: 3px;
}

.seg-tool__arrow {
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
  transition: transform 0.2s;
  margin-left: auto;
}
.seg-tool__arrow.is-open {
  transform: rotate(180deg);
}

.seg-tool__body {
  padding: 0 10px 6px 22px;
}

.seg-tool__badge {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  background: var(--mc-bg-muted);
  border-radius: 8px;
  padding: 0 6px;
  line-height: 16px;
}

/* Nested subagent timeline */
.seg-child {
  margin-bottom: 6px;
}
.seg-child__plan {
  margin-bottom: 6px;
  padding-left: 4px;
  border-left: 2px solid var(--mc-border-light);
}
.seg-child__step,
.seg-child__tool {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--mc-text-tertiary);
}
.seg-child__step.is-running,
.seg-child__tool.is-running { color: var(--mc-primary); }
.seg-child__step.is-completed,
.seg-child__tool.is-completed { color: var(--mc-text-secondary); }
.seg-child__tool.is-error { color: var(--mc-danger); }
.seg-child__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--mc-text-quaternary, #c0c0c0);
  flex-shrink: 0;
  margin: 0 3px;
}
.seg-child__tools {
  padding-left: 6px;
}
.seg-tool__body pre {
  margin: 0;
  padding: 8px 10px;
  background: var(--mc-bg-sunken);
  border-radius: 4px;
  border: 1px solid var(--mc-border-light);
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.5;
  color: var(--mc-text-secondary);
  max-height: 300px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.seg-slide-enter-active, .seg-slide-leave-active {
  transition: all 0.2s ease;
}
.seg-slide-enter-from, .seg-slide-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
