<script setup lang="ts">
import { RefreshRight } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import type { TeamRun } from '@/api'
import TeamRunProgress from './TeamRunProgress.vue'
import TeamRunStatus from './TeamRunStatus.vue'

withDefaults(defineProps<{
  runs: TeamRun[]
  loading?: boolean
  error?: string | null
  selectedRunId?: string | null
  hasMore?: boolean
  loadingMore?: boolean
}>(), { loading: false, error: null, selectedRunId: null, hasMore: false, loadingMore: false })

const emit = defineEmits<{
  refresh: []
  'select-run': [run: TeamRun]
  'load-more': []
}>()
const { t } = useI18n()
</script>

<template>
  <section class="runs-panel" aria-live="polite">
    <header class="runs-panel__header">
      <h2>{{ t('teamRuns.history') }}</h2>
      <button type="button" class="runs-panel__refresh" :aria-label="t('teamRuns.refresh')" @click="emit('refresh')">
        <el-icon :size="15"><RefreshRight /></el-icon>
      </button>
    </header>
    <div v-if="loading && runs.length === 0" class="runs-panel__state">{{ t('teamRuns.loading') }}</div>
    <div v-else-if="error && runs.length === 0" class="runs-panel__state is-error">
      <span>{{ t('teamRuns.loadError') }}</span>
      <button type="button" @click="emit('refresh')">{{ t('teamRuns.retryLoad') }}</button>
    </div>
    <div v-else-if="runs.length === 0" class="runs-panel__state">{{ t('teamRuns.empty') }}</div>
    <div v-else class="runs-panel__list">
      <button
        v-for="run in runs"
        :key="run.id"
        data-team-run-row
        type="button"
        class="run-row"
        :class="{ 'is-selected': selectedRunId === run.id }"
        @click="emit('select-run', run)"
      >
        <span class="run-row__copy">
          <strong>{{ run.title }}</strong>
          <span>{{ run.objective }}</span>
          <TeamRunStatus :status="run.status" :started-at="run.startedAt" :completed-at="run.completedAt" show-duration />
        </span>
        <TeamRunProgress :progress="run.progress" compact />
      </button>
    </div>
    <p v-if="error && runs.length > 0" class="runs-panel__stale">{{ t('teamRuns.loadError') }}</p>
    <button v-if="hasMore" data-team-runs-load-more type="button" class="runs-panel__more" :disabled="loadingMore" @click="emit('load-more')">
      {{ loadingMore ? t('teamRuns.loadingMore') : t('teamRuns.loadMore') }}
    </button>
  </section>
</template>

<style scoped>
.runs-panel { width: 100%; letter-spacing: 0; }
.runs-panel__header { display: flex; align-items: center; justify-content: space-between; padding-bottom: 10px; border-bottom: 1px solid var(--mc-border-light, #e7ebef); }
.runs-panel__header h2 { margin: 0; color: var(--mc-text-primary); font-size: 14px; }
.runs-panel__refresh { display: grid; width: 30px; height: 30px; place-items: center; border: 1px solid var(--mc-border); border-radius: 6px; background: var(--mc-bg-elevated); color: var(--mc-text-secondary); cursor: pointer; }
.runs-panel__list { display: grid; gap: 6px; padding-top: 10px; }
.run-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; width: 100%; min-height: 72px; padding: 11px 12px; border: 1px solid var(--mc-border); border-left: 3px solid #718096; border-radius: 8px; background: var(--mc-bg-elevated); color: inherit; cursor: pointer; text-align: left; letter-spacing: 0; }
.run-row:hover, .run-row.is-selected { border-left-color: #16835b; background: rgba(27, 143, 104, 0.055); }
.run-row:focus-visible, .runs-panel__refresh:focus-visible { outline: 2px solid #16835b; outline-offset: 2px; }
.run-row__copy { display: grid; min-width: 0; gap: 4px; }
.run-row__copy strong { color: var(--mc-text-primary); font-size: 13px; overflow-wrap: anywhere; }
.run-row__copy > span { display: -webkit-box; overflow: hidden; color: var(--mc-text-secondary); font-size: 12px; line-height: 1.4; -webkit-box-orient: vertical; -webkit-line-clamp: 1; }
.runs-panel__state { display: grid; min-height: 180px; place-content: center; gap: 8px; color: var(--mc-text-tertiary); font-size: 13px; text-align: center; }
.runs-panel__state.is-error, .runs-panel__stale { color: #b53535; }
.runs-panel__state button { border: 0; background: transparent; color: #16795a; cursor: pointer; }
.runs-panel__stale { margin: 8px 0 0; font-size: 12px; }
.runs-panel__more { display:block; width:100%; margin-top:10px; min-height:34px; border:1px solid var(--mc-border); border-radius:6px; background:var(--mc-bg-elevated); color:#16795a; cursor:pointer }.runs-panel__more:disabled{cursor:wait;opacity:.65}
</style>
