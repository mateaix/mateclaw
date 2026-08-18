<script setup lang="ts">
import { ArrowRight } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import type { AgentRunGroup, AgentRunWorker } from '@/composables/useAgentRunGroups'
import TeamRunProgress from '@/components/team-run/TeamRunProgress.vue'
import TeamRunStatus from '@/components/team-run/TeamRunStatus.vue'
import TeamRunRuntime from '@/components/team-run/TeamRunRuntime.vue'
import AgentRunWorkerRow from './AgentRunWorkerRow.vue'

withDefaults(defineProps<{
  groups: AgentRunGroup[]
  selectedRunId?: string | null
  selectedTaskId?: string | null
}>(), { selectedRunId: null, selectedTaskId: null })
const emit = defineEmits<{ 'open-run': [runId: string]; 'open-worker': [worker: AgentRunWorker] }>()
const { t } = useI18n()
</script>

<template>
  <section v-if="groups.length" class="agent-run-groups">
    <h2>{{ t('live.teamRuns.title') }}</h2>
    <article
      v-for="group in groups"
      :key="group.run.id"
      data-agent-run-group
      class="agent-run-group"
      :class="[`is-${group.state}`, { 'is-selected': selectedRunId === group.run.id }]"
    >
      <header class="agent-run-group__header">
        <button data-open-agent-run type="button" class="agent-run-group__open" @click="emit('open-run', group.run.id)">
          <span class="agent-run-group__copy">
            <strong>{{ group.run.title }}</strong>
            <span>{{ group.run.objective }}</span>
            <TeamRunStatus :status="group.run.status" :started-at="group.run.startedAt" :completed-at="group.run.completedAt" show-duration />
          </span>
          <TeamRunProgress :progress="group.run.progress" compact />
          <el-icon class="agent-run-group__arrow" :size="15"><ArrowRight /></el-icon>
        </button>
      </header>
      <div class="agent-run-group__lead" style="min-width: 0">
        <span>{{ t('live.teamRuns.lead') }}</span>
        <strong class="agent-run-group__lead-name">{{ group.leadRuntime?.agentName || group.run.leadAgentId }}</strong>
        <span class="agent-run-group__phase">{{ group.leadRuntime?.currentPhase || group.state }}</span>
      </div>
      <TeamRunRuntime :run="group.run" />
      <div v-if="group.workers.length" data-agent-run-workers>
        <AgentRunWorkerRow
          v-for="worker in group.workers"
          :key="worker.task.id"
          :worker="worker"
          :selected="selectedTaskId === worker.task.id"
          @open="emit('open-worker', $event)"
        />
      </div>
      <p v-else class="agent-run-group__empty">{{ t('live.teamRuns.noWorkers') }}</p>
    </article>
  </section>
</template>

<style scoped>
.agent-run-groups { display: grid; gap: 10px; margin-bottom: 18px; letter-spacing: 0; }
.agent-run-groups > h2 { margin: 0; color: var(--mc-text-primary); font-size: 14px; }
.agent-run-group { overflow: hidden; border: 1px solid var(--mc-border); border-left: 3px solid #718096; border-radius: 8px; background: var(--mc-bg-elevated); }
.agent-run-group.is-active, .agent-run-group.is-finalizing, .agent-run-group.is-selected { border-left-color: #16835b; }.agent-run-group.is-waiting, .agent-run-group.is-review { border-left-color: #b96c08; }.agent-run-group.is-stuck, .agent-run-group.is-failed { border-left-color: #c13d3d; }
.agent-run-group__open { display: grid; grid-template-columns: minmax(0, 1fr) auto 18px; align-items: center; gap: 12px; width: 100%; min-height: 68px; padding: 10px 12px; border: 0; background: transparent; color: inherit; cursor: pointer; text-align: left; letter-spacing: 0; }
.agent-run-group__open:hover { background: rgba(71, 85, 105, 0.04); }.agent-run-group__open:focus-visible { outline: 2px solid #16835b; outline-offset: -2px; }
.agent-run-group__copy { display: grid; min-width: 0; gap: 3px; }.agent-run-group__copy strong { color: var(--mc-text-primary); font-size: 13px; }.agent-run-group__copy > span { overflow: hidden; color: var(--mc-text-secondary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.agent-run-group__lead { display: grid; grid-template-columns: auto minmax(0,1fr) minmax(0,.6fr); align-items:center; gap: 8px; padding: 7px 10px; border-top: 1px solid var(--mc-border-light); background: rgba(71, 85, 105, 0.035); color: var(--mc-text-tertiary); font-size: 11px; }
.agent-run-group__lead-name,.agent-run-group__phase{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.agent-run-group__lead-name{color:var(--mc-text-secondary)}.agent-run-group__phase{text-align:right}
.agent-run-group__empty { margin: 0; padding: 12px; border-top: 1px solid var(--mc-border-light); color: var(--mc-text-tertiary); font-size: 11px; }
@media(max-width:520px){.agent-run-group__open{grid-template-columns:minmax(0,1fr) auto;gap:8px}.agent-run-group__arrow{display:none}.agent-run-group__lead{grid-template-columns:auto minmax(0,1fr)}.agent-run-group__phase{grid-column:2;text-align:left}.agent-run-group__copy>span{white-space:normal;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2}}
</style>
