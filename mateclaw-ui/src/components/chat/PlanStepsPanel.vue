<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { Loading, Select, ArrowDown, View } from '@element-plus/icons-vue'
import type { PlanMeta } from '@/types'
import ExecutionDetailDialog from './ExecutionDetailDialog.vue'

const props = defineProps<{
  plan: PlanMeta
  isGenerating: boolean
}>()

const collapsed = ref(false)

const completedCount = computed(() =>
  props.plan.stepResults?.filter(r => r?.status === 'completed').length || 0
)

const allDone = computed(() =>
  completedCount.value === props.plan.steps.length && !props.isGenerating
)

type StepStatus = 'pending' | 'running' | 'completed'

const stepStatuses = computed<StepStatus[]>(() =>
  props.plan.steps.map((_, i) => {
    const result = props.plan.stepResults?.[i]
    if (result?.status === 'completed') return 'completed'
    if (i === props.plan.currentStep && props.isGenerating) return 'running'
    return 'pending'
  })
)

const expandedSteps = reactive(new Set<number>())

function toggleStep(index: number) {
  const result = props.plan.stepResults?.[index]
  if (!result?.result) return
  if (expandedSteps.has(index)) {
    expandedSteps.delete(index)
  } else {
    expandedSteps.add(index)
  }
}

function truncateResult(text: string, max: number): string {
  if (!text || text.length <= max) return text
  return text.slice(0, max) + '...'
}

// Full step-result viewer — the inline preview is capped at 500 chars; this opens
// the complete result so plan execution stays fully auditable.
const detailVisible = ref(false)
const detailIndex = ref(-1)
const detailResponse = computed(() => props.plan.stepResults?.[detailIndex.value]?.result || '')
const detailTitle = computed(() => {
  const i = detailIndex.value
  return i >= 0 ? `${i + 1}. ${props.plan.steps[i] || ''}` : ''
})
const detailStatus = computed<'completed' | 'error' | 'running'>(() => {
  const st = props.plan.stepResults?.[detailIndex.value]?.status
  if (st === 'failed' || st === 'error') return 'error'
  if (st === 'completed') return 'completed'
  return 'running'
})

function openDetail(index: number) {
  detailIndex.value = index
  detailVisible.value = true
}
</script>

<template>
  <div class="plan-panel" :class="{ 'is-done': allDone }">
    <!-- 标题栏 -->
    <div class="plan-panel__header" @click="collapsed = !collapsed">
      <span class="plan-panel__icon">
        <el-icon v-if="isGenerating && !allDone" class="is-loading" :size="14"><Loading /></el-icon>
        <el-icon v-else :size="14"><Select /></el-icon>
      </span>
      <span class="plan-panel__title">{{ $t('chat.executionPlan') }}</span>
      <span class="plan-panel__progress">({{ completedCount }}/{{ plan.steps.length }} {{ $t('chat.planDone') }})</span>
      <el-icon
        class="plan-panel__arrow"
        :class="{ 'is-open': !collapsed }"
        :size="12"
      ><ArrowDown /></el-icon>
    </div>

    <!-- 步骤列表 -->
    <Transition name="plan-slide">
      <div v-if="!collapsed" class="plan-panel__body">
        <div
          v-for="(step, i) in plan.steps"
          :key="i"
          class="plan-step"
          :class="{
            'is-pending': stepStatuses[i] === 'pending',
            'is-running': stepStatuses[i] === 'running',
            'is-completed': stepStatuses[i] === 'completed',
          }"
          @click="toggleStep(i)"
        >
          <div class="plan-step__header">
            <span class="plan-step__status">
              <el-icon v-if="stepStatuses[i] === 'running'" class="is-loading" :size="13"><Loading /></el-icon>
              <el-icon v-else-if="stepStatuses[i] === 'completed'" :size="13"><Select /></el-icon>
              <span v-else class="plan-step__dot"></span>
            </span>
            <span class="plan-step__index">{{ i + 1 }}.</span>
            <span class="plan-step__text">{{ step }}</span>
            <el-icon
              v-if="plan.stepResults?.[i]?.result"
              class="plan-step__detail"
              :title="$t('chat.detail.viewDetail')"
              :size="12"
              @click.stop="openDetail(i)"
            ><View /></el-icon>
            <el-icon
              v-if="plan.stepResults?.[i]?.result"
              class="plan-step__arrow"
              :class="{ 'is-open': expandedSteps.has(i) }"
              :size="11"
            ><ArrowDown /></el-icon>
          </div>

          <!-- 步骤结果（可展开预览，完整内容见详情弹层） -->
          <Transition name="plan-slide">
            <div v-if="expandedSteps.has(i) && plan.stepResults?.[i]?.result" class="plan-step__result">
              <pre>{{ truncateResult(plan.stepResults[i].result, 500) }}</pre>
            </div>
          </Transition>
        </div>
      </div>
    </Transition>

    <ExecutionDetailDialog
      v-model="detailVisible"
      :title="detailTitle"
      :status="detailStatus"
      :response="detailResponse"
    />
  </div>
</template>

<style scoped>
.plan-panel {
  margin: 4px 0 6px;
  border: 1px solid var(--mc-border-light);
  border-radius: var(--mc-radius-sm, 6px);
  overflow: hidden;
  transition: border-color 0.3s;
}
.plan-panel.is-done {
  border-color: var(--mc-success, #67c23a);
}

.plan-panel__header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: var(--mc-bg-muted, #f9f7f5);
  cursor: pointer;
  user-select: none;
  font-size: 13px;
  transition: background 0.15s;
}
.plan-panel__header:hover {
  background: var(--mc-bg-hover, #f0ece8);
}

.plan-panel__icon {
  display: flex;
  align-items: center;
  color: var(--mc-primary, #d96d46);
}
.plan-panel.is-done .plan-panel__icon {
  color: var(--mc-success, #67c23a);
}

.plan-panel__title {
  font-weight: 600;
  color: var(--mc-text-primary);
}

.plan-panel__progress {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin-left: 2px;
}

.plan-panel__arrow {
  margin-left: auto;
  color: var(--mc-text-tertiary);
  transition: transform 0.2s;
}
.plan-panel__arrow.is-open {
  transform: rotate(180deg);
}

.plan-panel__body {
  padding: 4px 0;
}

.plan-step {
  transition: background 0.15s;
}
.plan-step:hover {
  background: var(--mc-bg-muted, #f9f7f5);
}

.plan-step__header {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px 4px 12px;
  font-size: 13px;
  cursor: pointer;
  user-select: none;
}

.plan-step__status {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  width: 16px;
  justify-content: center;
}
.is-completed .plan-step__status { color: var(--mc-success, #67c23a); }
.is-running .plan-step__status { color: var(--mc-primary, #d96d46); }

.plan-step__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 1.5px solid var(--mc-text-quaternary, #c0bfbc);
  background: transparent;
}

.plan-step__index {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  font-weight: 500;
  flex-shrink: 0;
}

.plan-step__text {
  flex: 1;
  min-width: 0;
  color: var(--mc-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.is-running .plan-step__text {
  color: var(--mc-text-primary);
  font-weight: 500;
}
.is-completed .plan-step__text {
  color: var(--mc-text-tertiary);
}

.plan-step__detail {
  flex-shrink: 0;
  margin-left: auto;
  color: var(--mc-text-quaternary);
  cursor: pointer;
  transition: color 0.15s;
}
.plan-step__detail:hover {
  color: var(--mc-primary);
}

.plan-step__arrow {
  flex-shrink: 0;
  color: var(--mc-text-quaternary);
  transition: transform 0.2s;
}
.plan-step__arrow.is-open {
  transform: rotate(180deg);
}

.plan-step__result {
  padding: 0 10px 4px 38px;
}
.plan-step__result pre {
  margin: 0;
  padding: 6px 8px;
  background: var(--mc-bg-sunken, #f3f0ed);
  border-radius: 4px;
  border: 1px solid var(--mc-border-light);
  font-family: var(--mc-font-mono, 'SF Mono', 'Menlo', 'Consolas', monospace);
  font-size: 12px;
  line-height: 1.5;
  color: var(--mc-text-secondary);
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

/* Transitions */
.plan-slide-enter-active, .plan-slide-leave-active {
  transition: all 0.2s ease;
}
.plan-slide-enter-from, .plan-slide-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
