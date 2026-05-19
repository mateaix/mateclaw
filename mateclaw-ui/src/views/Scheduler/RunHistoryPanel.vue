<template>
  <div class="history-panel">
    <div v-if="loading" class="state-block">
      <span class="state-spinner" aria-hidden="true" />
      <span>{{ t('common.loading') }}</span>
    </div>

    <div v-else-if="loadError" class="state-block state-block--error">
      <span class="state-icon">!</span>
      <p>{{ loadError }}</p>
      <button class="btn-secondary" @click="refresh">{{ t('common.retry') }}</button>
    </div>

    <div v-else-if="!runs.length" class="state-block">
      <span class="state-icon empty">&#9203;</span>
      <p class="state-title">{{ t('scheduler.history.empty') }}</p>
      <p class="state-desc">{{ t('scheduler.history.emptyDesc') }}</p>
    </div>

    <div v-else class="table-wrap">
      <table class="data-table">
        <colgroup>
          <col style="width: 26%; min-width: 180px" />
          <col style="width: 12%; min-width: 96px" />
          <col style="width: 14%; min-width: 110px" />
          <col style="width: 20%; min-width: 150px" />
          <col style="width: 14%; min-width: 100px" />
          <col style="width: 14%; min-width: 90px" />
        </colgroup>
        <thead>
          <tr>
            <th>{{ t('scheduler.history.columns.job') }}</th>
            <th>{{ t('scheduler.history.columns.trigger') }}</th>
            <th>{{ t('scheduler.history.columns.status') }}</th>
            <th>{{ t('scheduler.history.columns.startedAt') }}</th>
            <th>{{ t('scheduler.history.columns.duration') }}</th>
            <th>{{ t('scheduler.history.columns.tokens') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="run in runs" :key="run.id" class="data-row">
            <td>
              <div class="job-name" :title="jobName(run.cronJobId)">{{ jobName(run.cronJobId) }}</div>
              <div v-if="run.errorMessage" class="run-error" :title="run.errorMessage">
                {{ run.errorMessage }}
              </div>
            </td>
            <td>
              <span class="trigger-pill" :class="'trigger-' + (run.triggerType || 'scheduled')">
                {{ triggerLabel(run.triggerType) }}
              </span>
            </td>
            <td>
              <span class="status-badge" :class="'status-' + (run.status || 'running')">
                {{ statusLabel(run.status) }}
              </span>
            </td>
            <td class="time-cell" :title="formatTime(run.startedAt)">{{ formatTime(run.startedAt) }}</td>
            <td class="time-cell">{{ formatDuration(run.startedAt, run.finishedAt) }}</td>
            <td class="time-cell">{{ run.tokenUsage != null ? run.tokenUsage : '-' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { dashboardApi } from '@/api'
import { useCronJobStore } from '@/stores/useCronJobStore'

/** One row of cron run history, mirroring the backend CronJobRunEntity. */
interface CronJobRun {
  id: string | number
  cronJobId: string | number
  conversationId?: string
  status?: string
  triggerType?: string
  startedAt?: string
  finishedAt?: string
  errorMessage?: string
  tokenUsage?: number | null
}

const { t } = useI18n()
const store = useCronJobStore()

// Report the run count up to the Scheduler shell for the tab badge.
const emit = defineEmits<{ count: [number] }>()

const runs = ref<CronJobRun[]>([])
const loading = ref(false)
const loadError = ref('')

async function refresh() {
  loading.value = true
  loadError.value = ''
  try {
    // The cron job list resolves run.cronJobId to a human-readable name.
    if (!store.jobs.length) await store.fetchJobs()
    const res: any = await dashboardApi.recentRuns(50)
    runs.value = (res?.data || res || []) as CronJobRun[]
  } catch (e: any) {
    runs.value = []
    loadError.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

// Resolve a run's cron job id to its name. Compared as strings so 19-digit
// Snowflake ids never lose precision through a Number coercion.
function jobName(cronJobId: string | number): string {
  const match = store.jobs.find((j) => String(j.id) === String(cronJobId))
  return match?.name || t('scheduler.history.unknownJob', { id: cronJobId })
}

function statusLabel(status?: string): string {
  const key = `scheduler.history.status.${status || 'running'}`
  const localized = t(key, '')
  return localized && localized !== key ? localized : (status || '-')
}

function triggerLabel(type?: string): string {
  const key = `scheduler.history.triggerType.${type || 'scheduled'}`
  const localized = t(key, '')
  return localized && localized !== key ? localized : (type || '-')
}

function formatTime(datetime?: string): string {
  if (!datetime) return '-'
  try {
    const d = new Date(datetime)
    return isNaN(d.getTime()) ? datetime : d.toLocaleString()
  } catch {
    return datetime
  }
}

// Compact run duration: ms / s / "Nm Ns" / "Nh Nm". A run still in flight
// (no finishedAt) shows an em dash rather than a misleading zero.
function formatDuration(start?: string, end?: string): string {
  if (!start || !end) return '—'
  const ms = new Date(end).getTime() - new Date(start).getTime()
  if (isNaN(ms) || ms < 0) return '—'
  if (ms < 1000) return `${ms}ms`
  const totalSec = Math.floor(ms / 1000)
  if (totalSec < 60) return `${totalSec}s`
  const min = Math.floor(totalSec / 60)
  const sec = totalSec % 60
  if (min < 60) return sec ? `${min}m ${sec}s` : `${min}m`
  const hour = Math.floor(min / 60)
  return `${hour}h ${min % 60}m`
}

onMounted(refresh)
watch(() => runs.value.length, (n) => emit('count', n), { immediate: true })

// The Scheduler shell owns the "Refresh" header button for this tab.
defineExpose({ refresh })
</script>

<style scoped>
.history-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.btn-secondary {
  padding: 8px 16px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
}
.btn-secondary:hover { background: var(--mc-bg-sunken); }

/* Loading / error / empty share one centered block. */
.state-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 56px 24px;
  color: var(--mc-text-tertiary);
  text-align: center;
}
.state-icon { font-size: 30px; line-height: 1; }
.state-icon.empty { font-size: 34px; }
.state-block--error .state-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  font-size: 18px;
  font-weight: 700;
  background: var(--mc-danger-bg, rgba(239, 68, 68, 0.12));
  color: var(--mc-danger, rgb(239, 68, 68));
}
.state-title { font-size: 14px; font-weight: 600; color: var(--mc-text-secondary); margin: 0; }
.state-desc { font-size: 13px; margin: 0; max-width: 380px; }
.state-spinner {
  width: 22px;
  height: 22px;
  border: 2px solid var(--mc-border);
  border-top-color: var(--mc-primary);
  border-radius: 50%;
  animation: history-spin 0.7s linear infinite;
}
@keyframes history-spin {
  to { transform: rotate(360deg); }
}

.table-wrap {
  width: 100%;
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--mc-bg-elevated) 96%, white 4%) 0%, var(--mc-bg-elevated) 100%);
  border: 1px solid var(--mc-border);
  border-radius: 24px;
  overflow-x: auto;
  overflow-y: hidden;
  box-shadow: 0 20px 48px rgba(128, 84, 60, 0.08);
}

.data-table { width: 100%; table-layout: fixed; border-collapse: collapse; }
.data-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 12px 16px;
  text-align: left;
  font-size: 11px;
  font-weight: 700;
  color: var(--mc-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  background: color-mix(in srgb, var(--mc-bg-sunken) 86%, white 14%);
  border-bottom: 1px solid var(--mc-border);
  white-space: nowrap;
}
.data-row { border-bottom: 1px solid var(--mc-border-light); transition: background 0.1s; }
.data-row:hover { background: var(--mc-bg-sunken); }
.data-row:last-child { border-bottom: none; }
.data-table td { padding: 14px 16px; font-size: 14px; color: var(--mc-text-primary); vertical-align: top; }

.job-name {
  font-weight: 600;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.run-error {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--mc-danger, rgb(239, 68, 68));
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time-cell { font-size: 13px; color: var(--mc-text-secondary); }

.trigger-pill,
.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}
.trigger-pill { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.trigger-manual { background: var(--mc-primary-bg); color: var(--mc-primary); }

.status-running { background: rgba(59, 130, 246, 0.12); color: rgb(59, 130, 246); }
.status-completed,
.status-succeeded { background: rgba(34, 197, 94, 0.12); color: rgb(34, 197, 94); }
.status-failed { background: rgba(239, 68, 68, 0.12); color: rgb(239, 68, 68); }
</style>
