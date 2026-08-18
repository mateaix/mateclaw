<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CircleCheckFilled, CircleCloseFilled, Clock, Loading, Lock, RemoveFilled, View, WarningFilled } from '@element-plus/icons-vue'
import type { TeamRunTask } from '@/api'
import { orderTasksByDependencies, taskDependencyIds } from './teamRunPresentation'

const props = defineProps<{
  tasks: TeamRunTask[]
  selectedTaskId?: string | null
}>()

const emit = defineEmits<{
  'select-task': [task: TeamRunTask]
}>()

const { t, te } = useI18n()
const orderedTasks = computed(() => orderTasksByDependencies(props.tasks))
const byId = computed(() => new Map(props.tasks.map(task => [task.id, task])))

function statusLabel(status: string) {
  const key = `teams.status.${status}`
  return te(key) ? t(key) : status
}

function iconFor(status: string) {
  if (status === 'completed') return CircleCheckFilled
  if (status === 'failed') return CircleCloseFilled
  if (status === 'cancelled' || status === 'stale') return RemoveFilled
  if (status === 'in_review') return View
  if (status === 'in_progress') return Loading
  if (status === 'blocked') return Lock
  if (status === 'pending') return Clock
  return WarningFilled
}

function dependencyLabel(id: string) {
  const dependency = byId.value.get(id)
  return dependency ? `${dependency.taskNumber}. ${dependency.subject}` : id
}

function preview(value: string | null) {
  if (!value) return ''
  const normalized = value.replace(/\s+/g, ' ').trim()
  return normalized.length > 140 ? `${normalized.slice(0, 137)}...` : normalized
}
</script>

<template>
  <div v-if="orderedTasks.length" class="run-task-list">
    <button
      v-for="task in orderedTasks"
      :key="task.id"
      type="button"
      class="run-task-row"
      :class="{ 'is-selected': selectedTaskId === task.id }"
      :aria-pressed="selectedTaskId === task.id"
      @click="emit('select-task', task)"
    >
      <span class="run-task-row__state" :class="`is-${task.status}`">
        <el-icon :class="{ 'is-loading': task.status === 'in_progress' }" :size="15">
          <component :is="iconFor(task.status)" />
        </el-icon>
      </span>
      <span class="run-task-row__body">
        <span class="run-task-row__title">
          <span>{{ task.taskNumber }}. {{ task.subject }}</span>
          <span class="run-task-row__status">{{ statusLabel(task.status) }}</span>
        </span>
        <span class="run-task-row__meta">
          <span>{{ t('teamRuns.assignee') }}: {{ task.assigneeAgentId }}</span>
          <span v-if="taskDependencyIds(task).length">
            {{ t('teamRuns.dependencies') }}:
            {{ taskDependencyIds(task).map(dependencyLabel).join(', ') }}
          </span>
          <span v-else>{{ t('teamRuns.dependencies') }}: {{ t('teamRuns.noDependencies') }}</span>
        </span>
        <span v-if="preview(task.result)" class="run-task-row__result">{{ preview(task.result) }}</span>
      </span>
    </button>
  </div>
  <div v-else class="run-task-list__empty">{{ t('teamRuns.emptyTasks') }}</div>
</template>

<style scoped>
.run-task-list { display: grid; gap: 1px; }
.run-task-row {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 8px;
  width: 100%;
  padding: 10px 8px;
  border: 0;
  border-bottom: 1px solid var(--mc-border-light, #e7ebef);
  background: transparent;
  color: var(--mc-text-primary, #1f2937);
  cursor: pointer;
  text-align: left;
  letter-spacing: 0;
}
.run-task-row:hover, .run-task-row.is-selected { background: rgba(27, 143, 104, 0.07); }
.run-task-row:focus-visible { outline: 2px solid #1b8f68; outline-offset: -2px; }
.run-task-row__state { padding-top: 2px; color: #64748b; }
.run-task-row__state.is-completed { color: #16835b; }
.run-task-row__state.is-in_review, .run-task-row__state.is-blocked { color: #a15c05; }
.run-task-row__state.is-failed { color: #c13d3d; }
.run-task-row__body { min-width: 0; display: grid; gap: 5px; }
.run-task-row__title { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; font-weight: 600; }
.run-task-row__title > span:first-child { min-width: 0; overflow-wrap: anywhere; }
.run-task-row__status { flex: none; color: var(--mc-text-tertiary, #64748b); font-size: 11px; font-weight: 500; }
.run-task-row__meta { display: flex; flex-wrap: wrap; gap: 5px 14px; color: var(--mc-text-tertiary, #64748b); font-size: 11px; }
.run-task-row__result { color: var(--mc-text-secondary, #475569); font-size: 12px; line-height: 1.45; overflow-wrap: anywhere; }
.run-task-list__empty { padding: 22px 8px; color: var(--mc-text-tertiary, #64748b); font-size: 12px; text-align: center; }
</style>
