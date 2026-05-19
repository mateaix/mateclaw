<template>
  <div class="settings-section">
    <div class="section-header">
      <h2 class="section-title">{{ t('skillCurator.title') }}</h2>
      <p class="section-desc">{{ t('skillCurator.desc') }}</p>
    </div>

    <div v-if="loading" class="settings-card state-card">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ t('common.loading') }}</span>
    </div>

    <div v-else-if="error" class="settings-card state-card state-card--error">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ error }}</span>
      <button class="btn-secondary" @click="load">{{ t('common.retry', 'Retry') }}</button>
    </div>

    <template v-else-if="status">
      <!-- Runtime state + control -->
      <div class="settings-card">
        <div class="state-pills">
          <span class="curator-pill" :class="status.config.enabled ? 'pill-on' : 'pill-off'">
            {{ t('skillCurator.stateEnabled') }}
          </span>
          <span class="curator-pill" :class="status.control.activated ? 'pill-on' : 'pill-muted'">
            {{ status.control.activated ? t('skillCurator.stateActivated') : t('skillCurator.statePreview') }}
          </span>
          <span v-if="status.control.paused" class="curator-pill pill-warn">
            {{ t('skillCurator.statePaused') }}
          </span>
        </div>
        <p class="curator-hint">
          {{ status.control.paused ? t('skillCurator.pausedHint')
            : status.control.activated ? t('skillCurator.activatedHint')
            : t('skillCurator.previewHint') }}
        </p>
        <div class="curator-actions">
          <button class="btn-secondary" :disabled="busy" @click="runDryRun">
            {{ t('skillCurator.runDryRun') }}
          </button>
          <button
            v-if="!status.control.activated"
            class="btn-primary" :disabled="busy"
            @click="setActivated(true)"
          >{{ t('skillCurator.activate') }}</button>
          <button
            v-else
            class="btn-secondary" :disabled="busy"
            @click="setActivated(false)"
          >{{ t('skillCurator.deactivate') }}</button>
          <button
            v-if="!status.control.paused"
            class="btn-secondary" :disabled="busy"
            @click="setPaused(true)"
          >{{ t('skillCurator.pause') }}</button>
          <button
            v-else
            class="btn-secondary" :disabled="busy"
            @click="setPaused(false)"
          >{{ t('skillCurator.resume') }}</button>
        </div>
      </div>

      <!-- Counts -->
      <div class="settings-card">
        <h3 class="card-title">{{ t('skillCurator.counts') }}</h3>
        <div class="count-grid">
          <div class="count-cell"><span class="count-num">{{ status.counts.active }}</span><span class="count-label">{{ t('skillCurator.active') }}</span></div>
          <div class="count-cell"><span class="count-num count-stale">{{ status.counts.stale }}</span><span class="count-label">{{ t('skillCurator.stale') }}</span></div>
          <div class="count-cell"><span class="count-num">{{ status.counts.archived }}</span><span class="count-label">{{ t('skillCurator.archived') }}</span></div>
          <div class="count-cell"><span class="count-num">{{ status.counts.pinned }}</span><span class="count-label">{{ t('skillCurator.pinned') }}</span></div>
          <div class="count-cell"><span class="count-num">{{ status.counts.blockedByBindings }}</span><span class="count-label">{{ t('skillCurator.blockedByBindings') }}</span></div>
        </div>
      </div>

      <!-- Config + control timestamps -->
      <div class="settings-card">
        <h3 class="card-title">{{ t('skillCurator.config') }}</h3>
        <dl class="kv-list">
          <div class="kv-row"><dt>{{ t('skillCurator.scope') }}</dt><dd><code>{{ status.config.scope }}</code></dd></div>
          <div class="kv-row"><dt>{{ t('skillCurator.staleAfter') }}</dt><dd>{{ status.config.staleAfterDays }}</dd></div>
          <div class="kv-row"><dt>{{ t('skillCurator.archiveAfter') }}</dt><dd>{{ status.config.archiveAfterDays }}</dd></div>
          <div class="kv-row"><dt>{{ t('skillCurator.cron') }}</dt><dd><code>{{ status.config.cron }}</code></dd></div>
          <div class="kv-row"><dt>{{ t('skillCurator.lastObservedAt') }}</dt><dd>{{ fmt(status.control.lastObservedAt) }}</dd></div>
          <div class="kv-row"><dt>{{ t('skillCurator.lastDryRunAt') }}</dt><dd>{{ fmt(status.control.lastDryRunAt) }}</dd></div>
          <div class="kv-row"><dt>{{ t('skillCurator.lastRunAt') }}</dt><dd>{{ fmt(status.control.lastRunAt) }}</dd></div>
          <div class="kv-row"><dt>{{ t('skillCurator.nextScheduledRun') }}</dt><dd>{{ fmt(status.control.nextScheduledRun) }}</dd></div>
        </dl>
      </div>

      <!-- Run reports -->
      <div class="settings-card">
        <h3 class="card-title">{{ t('skillCurator.reports') }}</h3>
        <p v-if="reports.length === 0" class="empty-note">{{ t('skillCurator.noReports') }}</p>
        <div v-else class="report-list">
          <button
            v-for="rid in reports"
            :key="rid"
            class="report-item"
            :class="{ active: selectedReportId === rid }"
            @click="openReport(rid)"
          >{{ rid }}</button>
        </div>
        <div v-if="selectedReport" class="report-detail">
          <div class="report-detail-row">
            <span class="curator-pill" :class="selectedReport.dryRun ? 'pill-muted' : 'pill-on'">
              {{ selectedReport.dryRun ? t('skillCurator.reportDryRun') : t('skillCurator.reportApplied') }}
            </span>
            <span class="report-meta">{{ t('skillCurator.reportScanned') }}: {{ selectedReport.scanned }}</span>
          </div>
          <div class="report-counts">
            <span>{{ t('skillCurator.reportPlanned') }}: stale {{ selectedReport.planned?.stale ?? 0 }} · archived {{ selectedReport.planned?.archived ?? 0 }} · reactivated {{ selectedReport.planned?.reactivated ?? 0 }}</span>
            <span>{{ t('skillCurator.reportApplied') }}: stale {{ selectedReport.applied?.stale ?? 0 }} · archived {{ selectedReport.applied?.archived ?? 0 }} · reactivated {{ selectedReport.applied?.reactivated ?? 0 }}</span>
          </div>
          <div v-if="(selectedReport.transitions || []).length > 0" class="report-transitions">
            <div class="report-transitions-head">{{ t('skillCurator.reportTransitions') }}</div>
            <div v-for="(tr, i) in selectedReport.transitions" :key="i" class="report-transition">
              <code>{{ tr.name }}</code>
              <span>{{ tr.from }} → {{ tr.to }}</span>
              <span class="report-meta">{{ tr.daysIdle }}d</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElIcon } from 'element-plus'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import { mcToast } from '@/composables/useMcToast'
import { skillApi } from '@/api/index'

const { t } = useI18n()

interface CuratorStatus {
  config: { enabled: boolean; scope: string; staleAfterDays: number; archiveAfterDays: number; cron: string }
  control: {
    activated: boolean; paused: boolean
    lastObservedAt: string | null; lastDryRunAt: string | null
    lastRunAt: string | null; nextScheduledRun: string | null
  }
  counts: Record<string, number | string>
  lastReport: { id: string; url: string } | null
}

interface CuratorReport {
  dryRun: boolean
  scanned: number
  planned?: { stale: number; archived: number; reactivated: number }
  applied?: { stale: number; archived: number; reactivated: number }
  transitions?: Array<{ name: string; from: string; to: string; daysIdle: number }>
}

const loading = ref(true)
const error = ref('')
const busy = ref(false)
const status = ref<CuratorStatus | null>(null)
const reports = ref<string[]>([])
const selectedReportId = ref<string>('')
const selectedReport = ref<CuratorReport | null>(null)

function fmt(ts: string | null | undefined): string {
  if (!ts) return '—'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ts
  return d.toLocaleString()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res: any = await skillApi.curatorStatus()
    status.value = res.data as CuratorStatus
    await loadReports()
  } catch (e: any) {
    error.value = e?.message || t('skillCurator.loadFailed')
  } finally {
    loading.value = false
  }
}

async function loadReports() {
  try {
    const res: any = await skillApi.curatorReports()
    reports.value = Array.isArray(res.data) ? res.data : []
  } catch {
    reports.value = []
  }
}

async function openReport(runId: string) {
  selectedReportId.value = runId
  try {
    const res: any = await skillApi.curatorReport(runId)
    selectedReport.value = res.data as CuratorReport
  } catch (e: any) {
    selectedReport.value = null
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  }
}

async function runDryRun() {
  busy.value = true
  try {
    await skillApi.curatorDryRun()
    mcToast.success(t('skillCurator.dryRunSuccess'))
    await load()
  } catch (e: any) {
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  } finally {
    busy.value = false
  }
}

async function setActivated(activate: boolean) {
  busy.value = true
  try {
    const res: any = await skillApi.curatorActivate(activate)
    status.value = res.data as CuratorStatus
    mcToast.success(t(activate ? 'skillCurator.activateSuccess' : 'skillCurator.deactivateSuccess'))
  } catch (e: any) {
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  } finally {
    busy.value = false
  }
}

async function setPaused(paused: boolean) {
  busy.value = true
  try {
    const res: any = paused ? await skillApi.curatorPause() : await skillApi.curatorResume()
    status.value = res.data as CuratorStatus
    mcToast.success(t(paused ? 'skillCurator.pauseSuccess' : 'skillCurator.resumeSuccess'))
  } catch (e: any) {
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.settings-section { width: 100%; }
.section-header { display: flex; flex-direction: column; gap: 6px; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }

.settings-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; padding: 18px; box-shadow: 0 8px 24px rgba(124, 63, 30, 0.04); width: 100%; margin-bottom: 16px; }
.card-title { margin: 0 0 14px; font-size: 15px; font-weight: 700; color: var(--mc-text-primary); }

.state-card { display: flex; align-items: center; gap: 10px; color: var(--mc-text-secondary); }
.state-card--error { color: var(--el-color-danger); }

.state-pills { display: flex; gap: 8px; flex-wrap: wrap; }
.curator-pill { padding: 3px 12px; border-radius: 999px; font-size: 12px; font-weight: 600; }
.pill-on { color: #1e8e3e; background: rgba(46, 160, 67, 0.14); }
.pill-off { color: var(--mc-text-tertiary); background: var(--mc-bg-sunken); }
.pill-muted { color: var(--mc-text-secondary); background: var(--mc-bg-sunken); }
.pill-warn { color: #b9770e; background: rgba(243, 156, 18, 0.16); }

.curator-hint { margin: 12px 0 14px; font-size: 13px; color: var(--mc-text-secondary); line-height: 1.5; }
.curator-actions { display: flex; gap: 10px; flex-wrap: wrap; }

.btn-secondary { border: 1px solid var(--mc-border); border-radius: 10px; padding: 7px 14px; font-size: 13px; font-weight: 600; cursor: pointer; background: var(--mc-bg-elevated); color: var(--mc-text-primary); transition: all 0.15s; }
.btn-secondary:hover:not(:disabled) { background: var(--mc-bg-sunken); }
.btn-primary { border: 1px solid var(--mc-primary); border-radius: 10px; padding: 7px 14px; font-size: 13px; font-weight: 600; cursor: pointer; background: var(--mc-primary); color: #fff; transition: all 0.15s; }
.btn-primary:hover:not(:disabled) { background: var(--mc-primary-hover); }
.btn-secondary:disabled, .btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }

.count-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 12px; }
.count-cell { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 14px 8px; background: var(--mc-bg-sunken); border-radius: 12px; }
.count-num { font-size: 24px; font-weight: 700; color: var(--mc-text-primary); }
.count-stale { color: #b9770e; }
.count-label { font-size: 12px; color: var(--mc-text-secondary); }

.kv-list { margin: 0; display: flex; flex-direction: column; }
.kv-row { display: flex; justify-content: space-between; gap: 16px; padding: 9px 0; border-bottom: 1px solid var(--mc-border-light); }
.kv-row:last-child { border-bottom: none; }
.kv-row dt { font-size: 13px; color: var(--mc-text-secondary); }
.kv-row dd { margin: 0; font-size: 13px; color: var(--mc-text-primary); font-weight: 600; }
.kv-row code { font-family: var(--mc-font-mono, ui-monospace, Menlo, monospace); font-size: 12px; }

.empty-note { margin: 0; font-size: 13px; color: var(--mc-text-tertiary); }
.report-list { display: flex; gap: 8px; flex-wrap: wrap; }
.report-item { font-family: var(--mc-font-mono, ui-monospace, Menlo, monospace); font-size: 12px; padding: 5px 10px; border-radius: 8px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; }
.report-item:hover { border-color: var(--mc-text-tertiary); }
.report-item.active { border-color: var(--mc-primary); color: var(--mc-primary); }

.report-detail { margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--mc-border-light); display: flex; flex-direction: column; gap: 10px; }
.report-detail-row { display: flex; align-items: center; gap: 12px; }
.report-meta { font-size: 12px; color: var(--mc-text-tertiary); }
.report-counts { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--mc-text-secondary); }
.report-transitions-head { font-size: 12px; font-weight: 700; color: var(--mc-text-secondary); margin-bottom: 6px; }
.report-transition { display: flex; gap: 12px; align-items: center; font-size: 13px; color: var(--mc-text-primary); padding: 4px 0; }
.report-transition code { font-family: var(--mc-font-mono, ui-monospace, Menlo, monospace); font-size: 12px; }

@media (max-width: 900px) {
  .kv-row { flex-direction: column; gap: 2px; }
}
</style>
