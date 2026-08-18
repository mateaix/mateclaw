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
          <span class="curator-pill" :class="status.control.consolidate ? 'pill-on' : 'pill-muted'">
            {{ status.control.consolidate ? t('skillCurator.consolidateOn') : t('skillCurator.consolidateOff') }}
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
          <button
            class="btn-secondary" :disabled="busy"
            @click="setConsolidate(!status.control.consolidate)"
          >{{ status.control.consolidate ? t('skillCurator.disableConsolidate') : t('skillCurator.enableConsolidate') }}</button>
        </div>
        <p class="curator-hint">{{ t('skillCurator.consolidateHint') }}</p>
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

      <!-- Mined routines -->
      <div class="settings-card">
        <div class="card-head">
          <h3 class="card-title">{{ t('skillCurator.routines') }}</h3>
          <div class="curator-actions">
            <button class="btn-secondary" :disabled="busy" @click="mineNow">
              {{ t('skillCurator.routineMine') }}
            </button>
          </div>
        </div>
        <p class="curator-hint">
          {{ t('skillCurator.routinesHint', { occurrences: gates.minOccurrences, days: gates.minDistinctDays }) }}
        </p>

        <div class="filter-row">
          <button
            v-for="opt in routineFilters"
            :key="opt.value"
            class="filter-chip"
            :class="{ active: routineFilter === opt.value }"
            @click="setRoutineFilter(opt.value)"
          >{{ t(opt.label) }}</button>
        </div>

        <p v-if="routines.length === 0" class="empty-note">{{ t('skillCurator.noRoutines') }}</p>
        <ul v-else class="routine-list">
          <li v-for="r in routines" :key="r.id" class="routine-item">
            <div class="routine-main">
              <p class="routine-text">{{ r.representativeText || r.signature }}</p>
              <div class="routine-meta">
                <span class="curator-pill" :class="routinePillClass(r)">{{ routineStatusLabel(r) }}</span>
                <span>{{ t('skillCurator.routineOccurrences', { n: r.occurrenceCount }) }}</span>
                <span>{{ t('skillCurator.routineDays', { n: r.distinctDayCount }) }}</span>
                <span v-if="r.lastSeenAt">{{ t('skillCurator.routineLastSeen') }}: {{ r.lastSeenAt }}</span>
                <span v-if="r.promotedSkillName" class="routine-skill">
                  → <code>{{ r.promotedSkillName }}</code>
                </span>
              </div>
            </div>
            <div class="routine-actions">
              <button
                v-if="r.status === 'observing'"
                class="btn-secondary" :disabled="busy"
                @click="promoteRoutine(r)"
              >{{ r.qualified ? t('skillCurator.routinePromote') : t('skillCurator.routinePromoteEarly') }}</button>
              <button
                v-if="r.status === 'observing'"
                class="btn-secondary" :disabled="busy"
                @click="dismissRoutine(r)"
              >{{ t('skillCurator.routineDismiss') }}</button>
              <button
                v-if="r.status === 'dismissed'"
                class="btn-secondary" :disabled="busy"
                @click="reopenRoutine(r)"
              >{{ t('skillCurator.routineReopen') }}</button>
            </div>
          </li>
        </ul>
      </div>

      <!-- Skills outside curation -->
      <div class="settings-card">
        <div class="card-head">
          <h3 class="card-title">{{ t('skillCurator.unmanaged') }}</h3>
          <div class="curator-actions">
            <button class="btn-secondary" :disabled="busy" @click="loadUnmanaged">
              {{ t('common.refresh', 'Refresh') }}
            </button>
          </div>
        </div>
        <p class="curator-hint">{{ t('skillCurator.unmanagedHint') }}</p>

        <h4 class="roster-heading">{{ t('skillCurator.managedList') }}</h4>
        <p v-if="managed.length === 0" class="empty-note">{{ t('skillCurator.noManaged') }}</p>
        <ul v-else class="routine-list">
          <li v-for="m in managed" :key="m.id" class="routine-item">
            <div class="routine-main">
              <p class="routine-text">{{ m.name }}</p>
              <div class="routine-meta">
                <span>{{ m.reason }}</span>
                <span v-if="m.unobserved">{{ t('skillCurator.unobserved') }}</span>
                <span v-else-if="m.daysIdle != null">
                  {{ t('skillCurator.unmanagedDaysIdle', { n: m.daysIdle }) }}
                </span>
              </div>
            </div>
            <div class="routine-actions">
              <button class="btn-secondary" :disabled="busy" @click="release(m)">
                {{ t('skillCurator.release') }}
              </button>
            </div>
          </li>
        </ul>

        <h4 class="roster-heading">{{ t('skillCurator.unmanagedList') }}</h4>
        <p v-if="unmanaged.length === 0" class="empty-note">{{ t('skillCurator.noUnmanaged') }}</p>
        <ul v-else class="routine-list">
          <li v-for="u in unmanaged" :key="u.id" class="routine-item">
            <div class="routine-main">
              <p class="routine-text">{{ u.name }}</p>
              <div class="routine-meta">
                <span>{{ u.reason === 'predates-provenance'
                  ? t('skillCurator.unmanagedReasonLegacy')
                  : t('skillCurator.unmanagedReasonUser') }}</span>
                <span v-if="u.daysIdle != null">
                  {{ t('skillCurator.unmanagedDaysIdle', { n: u.daysIdle }) }}
                </span>
              </div>
            </div>
            <div class="routine-actions">
              <button class="btn-secondary" :disabled="busy" @click="adopt(u)">
                {{ t('skillCurator.adopt') }}
              </button>
            </div>
          </li>
        </ul>
      </div>

      <!-- Restore points -->
      <div class="settings-card">
        <div class="card-head">
          <h3 class="card-title">{{ t('skillCurator.snapshots') }}</h3>
          <div class="curator-actions">
            <button class="btn-secondary" :disabled="busy" @click="captureSnapshot">
              {{ t('skillCurator.snapshotCapture') }}
            </button>
          </div>
        </div>
        <p class="curator-hint">{{ t('skillCurator.snapshotsHint') }}</p>

        <p v-if="snapshots.length === 0" class="empty-note">{{ t('skillCurator.noSnapshots') }}</p>
        <ul v-else class="routine-list">
          <li v-for="s in snapshots" :key="s.id" class="routine-item">
            <div class="routine-main">
              <p class="routine-text">{{ s.reason }}</p>
              <div class="routine-meta">
                <span>{{ s.createdAt }}</span>
                <span>{{ t('skillCurator.snapshotSkillCount', { n: s.skillCount }) }}</span>
              </div>
            </div>
            <div class="routine-actions">
              <button class="btn-secondary" :disabled="busy" @click="restoreSnapshot(s)">
                {{ t('skillCurator.snapshotRestore') }}
              </button>
            </div>
          </li>
        </ul>
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
          <div v-if="(selectedReport.consolidations || []).length > 0" class="report-transitions">
            <div class="report-transitions-head">{{ t('skillCurator.reportConsolidations') }}</div>
            <div v-for="(c, i) in selectedReport.consolidations" :key="`c${i}`" class="report-transition">
              <code>{{ c.umbrella }}</code>
              <span>{{ c.umbrellaCreated ? t('skillCurator.consolidateCreate') : t('skillCurator.consolidateEdit') }} ⇐ {{ (c.absorbed || []).join(', ') }}</span>
              <span class="curator-pill" :class="c.applied ? 'pill-on' : 'pill-muted'">
                {{ c.applied ? t('skillCurator.reportApplied') : t('skillCurator.reportDryRun') }}
              </span>
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
import { ElMessageBox } from 'element-plus'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import { mcToast } from '@/composables/useMcToast'
import { skillApi } from '@/api/index'

const { t } = useI18n()

interface CuratorStatus {
  config: { enabled: boolean; scope: string; staleAfterDays: number; archiveAfterDays: number; cron: string }
  control: {
    activated: boolean; paused: boolean; consolidate: boolean
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
  consolidations?: Array<{ umbrella: string; umbrellaCreated: boolean; absorbed: string[]; applied: boolean; reason: string }>
}

/**
 * A mined recurring request. `id` and `agentId` stay strings for their whole
 * lifecycle — they are 19-digit snowflake ids and coercing them through a JS
 * number silently truncates the last digits.
 */
interface RoutineCandidate {
  id: string
  agentId: string | null
  signature: string
  representativeText: string | null
  occurrenceCount: number
  distinctDayCount: number
  status: 'observing' | 'promoted' | 'dismissed'
  promotedSkillName: string | null
  firstSeenAt: string | null
  lastSeenAt: string | null
  qualified: boolean
}

interface RoutineGates {
  minOccurrences: number
  minDistinctDays: number
  enabled: boolean
}

/** A restore point. `id` is a snowflake — keep it a string. */
interface SkillSnapshot {
  id: string
  reason: string
  skillCount: number
  createdAt: string | null
}

interface UnmanagedSkill {
  /** Snowflake id kept as a string end-to-end — 19 digits exceed Number precision. */
  id: string
  name: string
  description?: string | null
  lifecycleState?: string | null
  origin?: string | null
  reason: string
  /** No sweep has observed it yet, so its idle clock has not started. */
  unobserved?: boolean
  daysIdle?: number | null
}

const loading = ref(true)
const error = ref('')
const busy = ref(false)
const status = ref<CuratorStatus | null>(null)
const routines = ref<RoutineCandidate[]>([])
const gates = ref<RoutineGates>({ minOccurrences: 3, minDistinctDays: 3, enabled: true })
const routineFilter = ref<string>('observing')
const snapshots = ref<SkillSnapshot[]>([])
const unmanaged = ref<UnmanagedSkill[]>([])
const managed = ref<UnmanagedSkill[]>([])

const routineFilters = [
  { value: 'observing', label: 'skillCurator.routineFilterObserving' },
  { value: 'promoted', label: 'skillCurator.routineFilterPromoted' },
  { value: 'dismissed', label: 'skillCurator.routineFilterDismissed' },
  { value: '', label: 'skillCurator.routineFilterAll' },
]
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
    await Promise.all([loadReports(), loadRoutines(), loadSnapshots(), loadUnmanaged()])
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

async function setConsolidate(enabled: boolean) {
  busy.value = true
  try {
    const res: any = await skillApi.curatorConsolidate(enabled)
    status.value = res.data as CuratorStatus
    mcToast.success(t(enabled ? 'skillCurator.consolidateSuccess' : 'skillCurator.disableConsolidateSuccess'))
  } catch (e: any) {
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  } finally {
    busy.value = false
  }
}

// ==================== Routines ====================

async function loadRoutines() {
  try {
    const res: any = await skillApi.routines(routineFilter.value || undefined)
    routines.value = Array.isArray(res.data?.items) ? res.data.items : []
    if (res.data?.gates) gates.value = res.data.gates as RoutineGates
  } catch {
    routines.value = []
  }
}

async function setRoutineFilter(value: string) {
  routineFilter.value = value
  await loadRoutines()
}

function routineStatusLabel(r: RoutineCandidate): string {
  if (r.status === 'promoted') return t('skillCurator.routineStatusPromoted')
  if (r.status === 'dismissed') return t('skillCurator.routineStatusDismissed')
  return r.qualified ? t('skillCurator.routineStatusReady') : t('skillCurator.routineStatusObserving')
}

function routinePillClass(r: RoutineCandidate): string {
  if (r.status === 'promoted') return 'pill-on'
  if (r.status === 'dismissed') return 'pill-off'
  return r.qualified ? 'pill-warn' : 'pill-muted'
}

async function mineNow() {
  busy.value = true
  try {
    const res: any = await skillApi.routineMine()
    mcToast.success(t('skillCurator.routineMineSuccess', { n: res.data?.refreshed ?? 0 }))
    await loadRoutines()
  } catch (e: any) {
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  } finally {
    busy.value = false
  }
}

async function promoteRoutine(r: RoutineCandidate) {
  // Promotion spends an LLM call and writes a skill the agent will consult
  // from then on, so an early promotion is worth confirming explicitly.
  if (!r.qualified) {
    try {
      await ElMessageBox.confirm(
        t('skillCurator.routinePromoteEarlyConfirm', {
          occurrences: r.occurrenceCount,
          days: r.distinctDayCount,
        }),
        t('skillCurator.routinePromoteEarly'),
        { type: 'warning' },
      )
    } catch {
      return
    }
  }
  busy.value = true
  try {
    await skillApi.routinePromote(r.id)
    mcToast.success(t('skillCurator.routinePromoteSuccess'))
    await loadRoutines()
  } catch (e: any) {
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  } finally {
    busy.value = false
  }
}

async function dismissRoutine(r: RoutineCandidate) {
  busy.value = true
  try {
    await skillApi.routineDismiss(r.id)
    mcToast.success(t('skillCurator.routineDismissSuccess'))
    await loadRoutines()
  } catch (e: any) {
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  } finally {
    busy.value = false
  }
}

async function reopenRoutine(r: RoutineCandidate) {
  busy.value = true
  try {
    await skillApi.routineReopen(r.id)
    mcToast.success(t('skillCurator.routineReopenSuccess'))
    await loadRoutines()
  } catch (e: any) {
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  } finally {
    busy.value = false
  }
}

// ==================== Skills outside curation ====================

async function loadUnmanaged() {
  try {
    const [un, mg]: any[] = await Promise.all([
      skillApi.curatorUnmanaged(),
      skillApi.curatorManaged(),
    ])
    unmanaged.value = Array.isArray(un.data) ? un.data : []
    managed.value = Array.isArray(mg.data) ? mg.data : []
  } catch {
    unmanaged.value = []
    managed.value = []
  }
}

async function release(u: UnmanagedSkill) {
  busy.value = true
  try {
    await skillApi.curatorRelease([u.id])
    mcToast.success(t('skillCurator.releaseSuccess', { name: u.name }))
    await loadUnmanaged()
  } catch (e: any) {
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  } finally {
    busy.value = false
  }
}

async function adopt(u: UnmanagedSkill) {
  // Adoption does not grant a fresh idle window — an already-idle skill can
  // age out on the very next sweep — so state that before acting on it.
  try {
    await ElMessageBox.confirm(
      t('skillCurator.adoptConfirm', { name: u.name }),
      t('skillCurator.adopt'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    await skillApi.curatorAdopt([u.id])
    mcToast.success(t('skillCurator.adoptSuccess', { name: u.name }))
    await loadUnmanaged()
  } catch (e: any) {
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  } finally {
    busy.value = false
  }
}

// ==================== Restore points ====================

async function loadSnapshots() {
  try {
    const res: any = await skillApi.curatorSnapshots()
    snapshots.value = Array.isArray(res.data) ? res.data : []
  } catch {
    snapshots.value = []
  }
}

async function captureSnapshot() {
  busy.value = true
  try {
    await skillApi.curatorSnapshotCapture('manual')
    mcToast.success(t('skillCurator.snapshotCaptureSuccess'))
    await loadSnapshots()
  } catch (e: any) {
    mcToast.error(e?.message || t('skillCurator.actionFailed'))
  } finally {
    busy.value = false
  }
}

async function restoreSnapshot(s: SkillSnapshot) {
  // Overwrites every current skill body and lifecycle state, so never fire
  // this from a single click.
  try {
    await ElMessageBox.confirm(
      t('skillCurator.snapshotRestoreConfirm', { time: s.createdAt ?? '', n: s.skillCount }),
      t('skillCurator.snapshotRestore'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    const res: any = await skillApi.curatorSnapshotRestore(s.id)
    mcToast.success(t('skillCurator.snapshotRestoreSuccess', {
      restored: res.data?.restored ?? 0,
      missing: res.data?.missing ?? 0,
    }))
    await load()
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
.roster-heading {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--mc-text-secondary);
}
.roster-heading:first-of-type { margin-top: 8px; }
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

.card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.card-head .card-title { margin-bottom: 0; }

.filter-row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
.filter-chip { font-size: 12px; font-weight: 600; padding: 5px 12px; border-radius: 999px; border: 1px solid var(--mc-border); background: var(--mc-bg-elevated); color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; }
.filter-chip:hover { background: var(--mc-bg-sunken); }
.filter-chip.active { border-color: var(--mc-primary); color: var(--mc-primary); }

.routine-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.routine-item { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 12px 0; border-bottom: 1px solid var(--mc-border-light); }
.routine-item:last-child { border-bottom: none; }
.routine-main { min-width: 0; flex: 1; }
.routine-text { margin: 0 0 6px; font-size: 14px; font-weight: 600; color: var(--mc-text-primary); overflow-wrap: anywhere; }
.routine-meta { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; font-size: 12px; color: var(--mc-text-tertiary); }
.routine-skill code { font-family: var(--mc-font-mono, ui-monospace, Menlo, monospace); font-size: 12px; color: var(--mc-text-secondary); }
.routine-actions { display: flex; gap: 8px; flex-shrink: 0; flex-wrap: wrap; }

@media (max-width: 900px) {
  .routine-item { flex-direction: column; gap: 10px; }
  .routine-actions { width: 100%; }
  .kv-row { flex-direction: column; gap: 2px; }
}
</style>
