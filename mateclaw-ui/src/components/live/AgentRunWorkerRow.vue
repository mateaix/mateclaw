<script setup lang="ts">
import { CircleCheck, Clock, DocumentChecked, Loading, VideoPause, Warning } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import type { AgentRunWorker, AgentWorkerState } from '@/composables/useAgentRunGroups'
import { taskDependencyIds } from '@/components/team-run/teamRunPresentation'

defineProps<{ worker: AgentRunWorker; selected?: boolean }>()
const emit = defineEmits<{ open: [worker: AgentRunWorker] }>()
const { t } = useI18n()
const icons: Record<AgentWorkerState, unknown> = {
  active: Loading, waiting: Clock, review: DocumentChecked, stuck: Warning,
  cancelled: VideoPause, completed: CircleCheck, failed: Warning,
}
</script>

<template>
  <button
    type="button"
    class="agent-run-worker"
    :class="[`is-${worker.state}`, { 'is-selected': selected }]"
    :disabled="!worker.task.conversationId"
    @click="emit('open', worker)"
  >
    <el-icon :class="{ 'is-loading': worker.state === 'active' }" :size="15"><component :is="icons[worker.state]" /></el-icon>
    <span class="agent-run-worker__copy" style="min-width: 0">
      <strong>{{ worker.task.taskNumber }}. {{ worker.task.subject }}</strong>
      <span>{{ worker.task.assigneeAgentId }}</span>
      <span v-if="taskDependencyIds(worker.task).length">
        {{ t('teamRuns.dependencies') }}: {{ taskDependencyIds(worker.task).join(', ') }}
      </span>
    </span>
    <span class="agent-run-worker__state" style="max-width: 96px">{{ t(`live.teamRuns.${worker.state}`) }}</span>
  </button>
</template>

<style scoped>
.agent-run-worker { display: grid; grid-template-columns: 20px minmax(0, 1fr) auto; align-items: center; gap: 8px; width: 100%; min-height: 48px; padding: 8px 10px; border: 0; border-top: 1px solid var(--mc-border-light); background: transparent; color: var(--mc-text-secondary); cursor: pointer; text-align: left; letter-spacing: 0; }
.agent-run-worker:hover:not(:disabled), .agent-run-worker.is-selected { background: rgba(27, 143, 104, 0.07); }
.agent-run-worker:focus-visible { outline: 2px solid #16835b; outline-offset: -2px; }
.agent-run-worker:disabled { cursor: default; opacity: 0.8; }
.agent-run-worker__copy { display: grid; min-width: 0; gap: 3px; }
.agent-run-worker__copy strong { color: var(--mc-text-primary); font-size: 12px; overflow-wrap: anywhere; }
.agent-run-worker__copy span, .agent-run-worker__state { min-width:0; overflow:hidden; color: var(--mc-text-tertiary); font-size: 11px; text-overflow:ellipsis; white-space:nowrap; }
.agent-run-worker.is-stuck { color: #c13d3d; }.agent-run-worker.is-review, .agent-run-worker.is-waiting { color: #a15c05; }.agent-run-worker.is-completed { color: #16835b; }
@media(max-width:520px){.agent-run-worker{grid-template-columns:20px minmax(0,1fr)}.agent-run-worker__state{grid-column:2;max-width:100%!important;justify-self:start}.agent-run-worker__copy strong{display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:2}}
</style>
