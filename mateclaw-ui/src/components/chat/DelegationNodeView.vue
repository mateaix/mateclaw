<script setup lang="ts">
import { ref, computed } from 'vue'
import { Loading, Select, CloseBold, ArrowDown, Connection, WarningFilled, Clock } from '@element-plus/icons-vue'
import { useToolLabel } from '@/composables/useToolLabel'
import type { DelegationNode } from '@/types'

// Renders one subagent (depth >= 2) in the delegation tree, recursing into its
// own children. The depth-1 child is rendered by ToolCallSegment, which mounts
// this component for each grandchild.
const props = defineProps<{ node: DelegationNode }>()

const { getToolLabel } = useToolLabel()

const expanded = ref(props.node.status === 'running')

const isRunning = computed(() => props.node.status === 'running')
const isError = computed(() => props.node.status === 'error')
const isSuccess = computed(() => props.node.status === 'completed')
const isStalled = computed(() => isRunning.value && !!props.node.stale)
// Fire-and-forget delegation: runs detached, result via task_output later.
const isAsync = computed(() => !!props.node.async)

const plan = computed(() => props.node.plan)
const tools = computed(() => props.node.tools || [])
const children = computed(() => props.node.children || [])
const resultPreview = computed(() => {
  const r = props.node.result || ''
  return r.length <= 600 ? r : r.slice(0, 600) + '\n... [truncated]'
})

const hasBody = computed(() =>
  !!plan.value || tools.value.length > 0 || children.value.length > 0 || !!props.node.result
)

const progress = computed(() => {
  const p = plan.value
  if (p?.steps?.length) {
    const done = p.stepResults?.filter(r => r?.status === 'completed').length || 0
    return `${done}/${p.steps.length}`
  }
  const n = tools.value.length
  return n ? `${n} ${n === 1 ? 'tool' : 'tools'}` : ''
})

function stepStatus(i: number): 'pending' | 'running' | 'completed' {
  const p = plan.value
  if (!p) return 'pending'
  if (p.stepResults?.[i]?.status === 'completed') return 'completed'
  if (i === p.currentStep) return 'running'
  return 'pending'
}
</script>

<template>
  <div class="deleg-node" :class="{ 'is-running': isRunning, 'is-error': isError, 'is-success': isSuccess }">
    <div class="deleg-node__header" @click="hasBody ? (expanded = !expanded) : null">
      <span class="deleg-node__status">
        <el-icon v-if="isAsync" class="deleg-node__async" :title="$t('chat.subagentAsync')" :size="12"><Clock /></el-icon>
        <el-icon v-else-if="isRunning" class="is-loading" :size="12"><Loading /></el-icon>
        <el-icon v-else-if="isSuccess" :size="12"><Select /></el-icon>
        <el-icon v-else :size="12"><CloseBold /></el-icon>
      </span>
      <el-icon class="deleg-node__icon" :size="11"><Connection /></el-icon>
      <span class="deleg-node__name">{{ node.agentName }}</span>
      <span v-if="progress" class="deleg-node__badge">{{ progress }}</span>
      <el-icon v-if="isStalled" class="deleg-node__stale" :title="$t('chat.subagentStalled')" :size="11"><WarningFilled /></el-icon>
      <el-icon
        v-if="hasBody"
        class="deleg-node__arrow"
        :class="{ 'is-open': expanded }"
        :size="10"
      ><ArrowDown /></el-icon>
    </div>

    <Transition name="deleg-slide">
      <div v-if="expanded && hasBody" class="deleg-node__body">
        <!-- The subagent's own plan checklist -->
        <div v-if="plan" class="deleg-node__plan">
          <div
            v-for="(step, i) in plan.steps"
            :key="i"
            class="deleg-node__step"
            :class="`is-${stepStatus(i)}`"
          >
            <el-icon v-if="stepStatus(i) === 'running'" class="is-loading" :size="11"><Loading /></el-icon>
            <el-icon v-else-if="stepStatus(i) === 'completed'" :size="11"><Select /></el-icon>
            <span v-else class="deleg-node__dot"></span>
            <span class="deleg-node__step-text">{{ step }}</span>
          </div>
        </div>

        <!-- Tools the subagent called -->
        <div v-if="tools.length" class="deleg-node__tools">
          <div
            v-for="(t, i) in tools"
            :key="i"
            class="deleg-node__tool"
            :class="`is-${t.status}`"
          >
            <el-icon v-if="t.status === 'running'" class="is-loading" :size="11"><Loading /></el-icon>
            <el-icon v-else-if="t.status === 'completed'" :size="11"><Select /></el-icon>
            <el-icon v-else :size="11"><CloseBold /></el-icon>
            <span class="deleg-node__tool-name">{{ getToolLabel(t.name) }}</span>
          </div>
        </div>

        <!-- Recurse into deeper subagents -->
        <DelegationNodeView v-for="c in children" :key="c.subagentId" :node="c" />

        <!-- Final result preview -->
        <pre v-if="node.result" class="deleg-node__result">{{ resultPreview }}</pre>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.deleg-node {
  margin: 2px 0 2px 4px;
  padding-left: 6px;
  border-left: 2px solid var(--mc-border-light);
}
.deleg-node.is-running { border-left-color: var(--mc-primary); }
.deleg-node.is-success { border-left-color: var(--mc-success); }
.deleg-node.is-error { border-left-color: var(--mc-danger); }

.deleg-node__header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 1.9;
  color: var(--mc-text-secondary);
  cursor: pointer;
  user-select: none;
}
.deleg-node__status { display: flex; align-items: center; flex-shrink: 0; }
.is-success .deleg-node__status { color: var(--mc-success); }
.is-error .deleg-node__status { color: var(--mc-danger); }
.is-running .deleg-node__status { color: var(--mc-primary); }

.deleg-node__icon { color: var(--mc-text-tertiary); flex-shrink: 0; }
.deleg-node__name {
  font-weight: 500;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 180px;
}
.deleg-node__badge {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  background: var(--mc-bg-muted);
  border-radius: 8px;
  padding: 0 6px;
  line-height: 16px;
}
.deleg-node__stale {
  flex-shrink: 0;
  color: var(--mc-warning, #e6a23c);
}
.deleg-node__async {
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
}
.deleg-node__arrow {
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
  transition: transform 0.2s;
  margin-left: auto;
}
.deleg-node__arrow.is-open { transform: rotate(180deg); }

.deleg-node__body { padding: 2px 0 2px 4px; }
.deleg-node__plan {
  margin-bottom: 4px;
  padding-left: 4px;
  border-left: 2px solid var(--mc-border-light);
}
.deleg-node__step,
.deleg-node__tool {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--mc-text-tertiary);
}
.deleg-node__step.is-running,
.deleg-node__tool.is-running { color: var(--mc-primary); }
.deleg-node__step.is-completed,
.deleg-node__tool.is-completed { color: var(--mc-text-secondary); }
.deleg-node__tool.is-error { color: var(--mc-danger); }
.deleg-node__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--mc-text-quaternary, #c0c0c0);
  flex-shrink: 0;
  margin: 0 3px;
}
.deleg-node__tools { padding-left: 6px; }
.deleg-node__result {
  margin: 4px 0 0;
  padding: 6px 8px;
  background: var(--mc-bg-sunken);
  border-radius: 4px;
  border: 1px solid var(--mc-border-light);
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
  font-size: 11px;
  line-height: 1.5;
  color: var(--mc-text-secondary);
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.deleg-slide-enter-active, .deleg-slide-leave-active { transition: all 0.2s ease; }
.deleg-slide-enter-from, .deleg-slide-leave-to { opacity: 0; transform: translateY(-3px); }
</style>
