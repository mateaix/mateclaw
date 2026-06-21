<template>
  <!--
    Team plan board: every employee's recent plans laid out as assignee swimlanes
    (rows = employee, columns = plan status). Cross-agent by default — the picker
    is a filter, not a requirement. Click a plan to open its step detail. Read-only;
    status is execution-driven.
  -->
  <div class="plan-board">
    <div class="pb-toolbar mc-surface-card">
      <div class="pb-agent-select">
        <AgentPickerDialog
          block
          clearable
          :model-value="filterAgentId || null"
          :agents="agentStore.agents"
          :placeholder="t('plans.allEmployees')"
          @change="onFilterChange"
        />
      </div>

      <div class="pb-toolbar__spacer"></div>

      <button
        class="pb-btn"
        :class="{ 'is-active': goalDrawerOpen }"
        @click="openGoalDrawer"
      >
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg>
        <span>{{ t('plans.goals') }}</span>
        <span v-if="activeGoals.length" class="pb-btn__badge">{{ activeGoals.length }}</span>
      </button>

      <button class="pb-btn pb-btn--icon" :title="t('common.refresh')" :disabled="loading" @click="reload">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" :class="{ 'pb-spin': loading }"><path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
      </button>
    </div>

    <div v-if="loading" class="pb-blank mc-surface-card">
      <span class="pb-hint">{{ t('common.loading') }}</span>
    </div>

    <div v-else-if="!lanes.length" class="pb-blank mc-surface-card">
      <el-empty :description="t('plans.noPlansAll')" />
    </div>

    <!-- Swimlanes -->
    <div v-else class="pb-swim mc-surface-card">
      <div class="pb-swim__head">
        <div class="pb-corner"></div>
        <div class="pb-cols">
          <div v-for="col in columns" :key="col.key" class="pb-colhead">
            <span class="pb-dot" :class="`is-${col.key}`"></span>
            <span>{{ col.label }}</span>
          </div>
        </div>
      </div>

      <div v-for="lane in lanes" :key="lane.key" class="pb-lane">
        <div class="pb-lane__label">
          <div class="pb-avatar">
            <SkillIcon v-if="lane.icon" :value="lane.icon" :size="20" fallback="🤖" />
            <span v-else class="pb-avatar__letter">{{ laneLetter(lane.name) }}</span>
          </div>
          <div class="pb-lane__id">
            <div class="pb-lane__name">{{ lane.name }}</div>
            <div class="pb-lane__count">{{ lane.plans.length }} {{ t('plans.plans') }}</div>
          </div>
        </div>

        <div class="pb-cols">
          <div v-for="col in columns" :key="col.key" class="pb-cell">
            <article
              v-for="group in visibleGroups(lane, col.key)"
              :key="group.key"
              class="pb-card"
              @click="openDetail(group.latest)"
            >
              <div class="pb-card__goal">{{ group.goal }}</div>
              <div class="pb-card__foot">
                <div class="pb-progress">
                  <div class="pb-progress__bar" :class="`is-${group.latest.status}`" :style="{ width: progressPct(group.latest) + '%' }"></div>
                </div>
                <span class="pb-card__steps">{{ group.latest.completedSteps }}/{{ group.latest.totalSteps }}</span>
                <span v-if="group.plans.length > 1" class="pb-card__runs" :title="t('plans.runs', { n: group.plans.length })">×{{ group.plans.length }}</span>
              </div>
              <!-- Sub-task breakdown: surfaces how many steps are still pending /
                   running, so an in-progress plan no longer hides its queued work. -->
              <div v-if="showDist(group.latest)" class="pb-card__dist">
                <span v-if="stepDist(group.latest).running" class="pb-distchip is-running">
                  {{ stepDist(group.latest).running }} {{ t('plans.col.running') }}
                </span>
                <span v-if="stepDist(group.latest).pending" class="pb-distchip is-pending">
                  {{ stepDist(group.latest).pending }} {{ t('plans.col.pending') }}
                </span>
                <span v-if="stepDist(group.latest).completed" class="pb-distchip is-completed">
                  {{ stepDist(group.latest).completed }} {{ t('plans.col.completed') }}
                </span>
              </div>
            </article>

            <button
              v-if="!expandedCells.has(cellKey(lane, col.key)) && hiddenCount(lane, col.key) > 0"
              class="pb-more"
              @click="toggleCell(lane, col.key)"
            >{{ t('plans.more', { n: hiddenCount(lane, col.key) }) }}</button>
            <button
              v-else-if="expandedCells.has(cellKey(lane, col.key)) && groupsIn(lane, col.key).length > GROUP_LIMIT"
              class="pb-more"
              @click="toggleCell(lane, col.key)"
            >{{ t('plans.collapse') }}</button>

            <div v-if="!groupsIn(lane, col.key).length" class="pb-cell__empty">—</div>
          </div>
        </div>
      </div>
    </div>

    <!-- Plan detail: custom right-side slide-in panel (matches focus-panel) -->
    <PlanDetailPanel
      :open="detailOpen"
      :plan="detailPlan"
      :loading="loadingDetail"
      :assignee-name="detailAssignee.name"
      :assignee-icon="detailAssignee.icon"
      @close="detailOpen = false"
    />

    <!-- Active goals: custom right-side panel (matches plan detail) -->
    <GoalsPanel
      :open="goalDrawerOpen"
      :goals="activeGoals"
      :loading="loadingGoals"
      @close="goalDrawerOpen = false"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAgentStore } from '@/stores/useAgentStore'
import AgentPickerDialog from '@/components/common/AgentPickerDialog.vue'
import SkillIcon from '@/components/common/SkillIcon.vue'
import PlanDetailPanel from '@/components/agents/PlanDetailPanel.vue'
import GoalsPanel from '@/components/agents/GoalsPanel.vue'
import { planApi, goalApi, type Goal } from '@/api'
import type { Plan } from '@/types'

const { t } = useI18n()
const agentStore = useAgentStore()

type PlanStatus = 'pending' | 'running' | 'completed' | 'failed'
interface Lane { key: string; name: string; icon?: string | null; plans: Plan[]; latest: number }
/** A column card: one cleaned goal, with every plan that re-ran it (latest first). */
interface PlanGroup { key: string; goal: string; plans: Plan[]; latest: Plan }

// Goal follow-up re-enters planning each turn, so a single objective can spawn
// many near-identical plans. Collapse them per column into one card (×N badge)
// instead of a wall of duplicates — closer to a "task with N runs" than N tasks.
const GROUP_LIMIT = 6

const plans = ref<Plan[]>([])
const loading = ref(false)
const filterAgentId = ref<string>('')
const expandedCells = ref<Set<string>>(new Set())

const detailOpen = ref(false)
const detailPlan = ref<Plan | null>(null)
const loadingDetail = ref(false)

const detailAssignee = computed<{ name: string; icon?: string | null }>(() => {
  const p = detailPlan.value
  if (!p) return { name: '' }
  const meta = agentById.value.get(String(p.agentId))
  return { name: meta?.name || t('plans.unknownAgent'), icon: meta?.icon }
})

const goalDrawerOpen = ref(false)
const loadingGoals = ref(false)
const activeGoals = ref<Goal[]>([])

const columns = computed<{ key: PlanStatus; label: string }[]>(() => [
  { key: 'pending', label: t('plans.col.pending') },
  { key: 'running', label: t('plans.col.running') },
  { key: 'completed', label: t('plans.col.completed') },
  { key: 'failed', label: t('plans.col.failed') },
])

const agentById = computed(() => {
  const m = new Map<string, { name: string; icon?: string | null }>()
  for (const a of agentStore.agents) m.set(String(a.id), { name: a.name, icon: a.icon })
  return m
})

// Group plans into assignee lanes; only employees with at least one plan get a
// lane. Optional picker narrows to a single employee. Lanes ordered by most
// recent plan activity so the busy ones surface first.
const lanes = computed<Lane[]>(() => {
  const src = filterAgentId.value
    ? plans.value.filter((p) => String(p.agentId) === filterAgentId.value)
    : plans.value
  const map = new Map<string, Lane>()
  for (const p of src) {
    const key = String(p.agentId)
    let lane = map.get(key)
    if (!lane) {
      const meta = agentById.value.get(key)
      lane = { key, name: meta?.name || t('plans.unknownAgent'), icon: meta?.icon, plans: [], latest: 0 }
      map.set(key, lane)
    }
    lane.plans.push(p)
    const ts = p.createTime ? new Date(p.createTime).getTime() : 0
    if (ts > lane.latest) lane.latest = ts
  }
  return [...map.values()].sort((a, b) => b.latest - a.latest)
})

function planTs(p: Plan): number {
  return p.createTime ? new Date(p.createTime).getTime() : 0
}

// Drop the appended "[Follow-up guidance] ..." block so re-runs of one objective
// share a title — and therefore a group.
function cleanGoal(goal?: string): string {
  if (!goal) return ''
  const i = goal.indexOf('[Follow-up guidance]')
  return (i >= 0 ? goal.slice(0, i) : goal).trim()
}

// Group a column's plans by cleaned goal, newest run first; groups ordered by
// most-recent activity.
function groupsIn(lane: Lane, status: PlanStatus): PlanGroup[] {
  const map = new Map<string, Plan[]>()
  for (const p of lane.plans) {
    if (p.status !== status) continue
    const key = cleanGoal(p.goal) || String(p.id)
    const arr = map.get(key)
    if (arr) arr.push(p)
    else map.set(key, [p])
  }
  const groups: PlanGroup[] = [...map.entries()].map(([key, ps]) => {
    const sorted = [...ps].sort((a, b) => planTs(b) - planTs(a))
    return { key, goal: cleanGoal(sorted[0].goal) || t('plans.untitled'), plans: sorted, latest: sorted[0] }
  })
  return groups.sort((a, b) => planTs(b.latest) - planTs(a.latest))
}

function cellKey(lane: Lane, status: PlanStatus): string {
  return `${lane.key}:${status}`
}

function visibleGroups(lane: Lane, status: PlanStatus): PlanGroup[] {
  const all = groupsIn(lane, status)
  return expandedCells.value.has(cellKey(lane, status)) ? all : all.slice(0, GROUP_LIMIT)
}

function hiddenCount(lane: Lane, status: PlanStatus): number {
  return Math.max(0, groupsIn(lane, status).length - GROUP_LIMIT)
}

function toggleCell(lane: Lane, status: PlanStatus) {
  const key = cellKey(lane, status)
  const next = new Set(expandedCells.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedCells.value = next
}

function progressPct(plan: Plan): number {
  if (!plan.totalSteps) return 0
  return Math.round((plan.completedSteps / plan.totalSteps) * 100)
}

// Sub-task status breakdown for a plan card. Plan-execute runs steps
// sequentially, so at most one step is "running" at any time; the rest of the
// not-yet-done steps are pending. Derived from the plan summary fields so the
// list endpoint stays cheap (no per-plan step fetch).
function stepDist(plan: Plan): { pending: number; running: number; completed: number } {
  const total = plan.totalSteps ?? 0
  const completed = Math.min(plan.completedSteps ?? 0, total)
  const running = plan.status === 'running' && completed < total ? 1 : 0
  const pending = Math.max(0, total - completed - running)
  return { pending, running, completed }
}

// Only worth showing for multi-step plans that are still active; a finished or
// single-step plan is already fully described by the progress bar.
function showDist(plan: Plan): boolean {
  return (plan.totalSteps ?? 0) > 1 && (plan.status === 'running' || plan.status === 'pending')
}

function laneLetter(name: string): string {
  return (name || '?').trim().charAt(0).toUpperCase()
}

async function reload() {
  loading.value = true
  try {
    if (!agentStore.agents.length) await agentStore.fetchAgents()
    const res: any = await planApi.listAll(200)
    plans.value = (res?.data ?? []) as Plan[]
  } finally {
    loading.value = false
  }
}

function onFilterChange(value: string | number | null) {
  filterAgentId.value = value == null ? '' : String(value)
}

async function openDetail(plan: Plan) {
  detailPlan.value = plan
  detailOpen.value = true
  loadingDetail.value = true
  try {
    const res: any = await planApi.get(String(plan.id))
    detailPlan.value = (res?.data ?? plan) as Plan
  } finally {
    loadingDetail.value = false
  }
}

async function openGoalDrawer() {
  goalDrawerOpen.value = true
  await loadGoals()
}

async function loadGoals() {
  loadingGoals.value = true
  try {
    const res: any = await goalApi.list({ status: 'active', limit: 100 })
    const all = (res?.data ?? []) as Goal[]
    activeGoals.value = filterAgentId.value
      ? all.filter((g) => String(g.agentId) === filterAgentId.value)
      : all
  } finally {
    loadingGoals.value = false
  }
}

onMounted(reload)
</script>

<style scoped>
.plan-board {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* Toolbar */
.pb-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
}
.pb-agent-select {
  width: 220px;
}
.pb-toolbar__spacer {
  flex: 1;
}
.pb-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-md);
  font-size: 13.5px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
}
.pb-btn:hover:not(:disabled) {
  border-color: var(--mc-border-strong);
}
.pb-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.pb-btn.is-active {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
}
.pb-btn--icon {
  padding: 8px 11px;
}
.pb-btn__badge {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--mc-radius-full);
  background: var(--mc-primary);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}
.pb-spin {
  animation: pb-spin 0.8s linear infinite;
}
@keyframes pb-spin {
  to { transform: rotate(360deg); }
}

/* Blank */
.pb-blank {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 24px;
}
.pb-hint {
  padding: 16px;
  font-size: 13px;
  color: var(--mc-text-tertiary);
}

/* Swimlanes */
.pb-swim {
  padding: 8px 8px 12px;
  overflow-x: auto;
}
.pb-swim__head,
.pb-lane {
  display: flex;
  gap: 12px;
  min-width: 760px;
}
.pb-corner,
.pb-lane__label {
  width: 168px;
  flex-shrink: 0;
}
.pb-cols {
  display: flex;
  flex: 1;
  gap: 12px;
  min-width: 0;
}
.pb-colhead {
  flex: 1;
  min-width: 150px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 10px 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary);
}

/* Lane */
.pb-lane {
  border-top: 1px solid var(--mc-border-light);
  padding: 12px 0;
}
.pb-lane__label {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 10px;
}
.pb-avatar {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  font-weight: 700;
  font-size: 14px;
}
.pb-avatar :deep(.skill-icon) { width: 100% !important; height: 100% !important; display: flex; align-items: center; justify-content: center; }
.pb-avatar :deep(.skill-icon__img) { width: 62%; height: 62%; object-fit: contain; }
.pb-avatar :deep(.skill-icon__glyph) { font-size: 18px; line-height: 1; }
.pb-lane__id {
  min-width: 0;
}
.pb-lane__name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pb-lane__count {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  margin-top: 2px;
}

/* Cell */
.pb-cell {
  flex: 1;
  min-width: 150px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border-light);
  border-radius: var(--mc-radius-md);
}
.pb-cell__empty {
  text-align: center;
  color: var(--mc-text-quaternary);
  font-size: 13px;
  padding: 6px 0;
}

/* Status dots + tags */
.pb-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--mc-text-tertiary);
}
.pb-dot.is-pending { background: var(--mc-text-tertiary); }
.pb-dot.is-running { background: var(--mc-primary); }
.pb-dot.is-completed { background: var(--mc-success); }
.pb-dot.is-failed { background: var(--mc-danger); }

/* Plan card */
.pb-card {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-md);
  padding: 10px 11px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.pb-card:hover {
  border-color: var(--mc-border-strong);
  box-shadow: var(--mc-shadow-soft);
}
.pb-card__goal {
  font-size: 12.5px;
  line-height: 1.45;
  color: var(--mc-text-primary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.pb-card__runs {
  flex-shrink: 0;
  padding: 0 6px;
  height: 16px;
  line-height: 16px;
  border-radius: var(--mc-radius-full);
  background: var(--mc-bg-muted);
  color: var(--mc-text-tertiary);
  font-size: 10.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.pb-more {
  margin-top: 2px;
  padding: 6px 8px;
  border: 1px dashed var(--mc-border);
  border-radius: var(--mc-radius-md);
  background: transparent;
  color: var(--mc-text-tertiary);
  font-size: 11.5px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.pb-more:hover {
  background: var(--mc-bg-hover);
  color: var(--mc-text-secondary);
  border-color: var(--mc-border-strong);
}
.pb-card__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 9px;
}

/* Sub-task breakdown chips */
.pb-card__dist {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 8px;
}
.pb-distchip {
  display: inline-flex;
  align-items: center;
  padding: 1px 7px;
  border-radius: var(--mc-radius-full);
  font-size: 10.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 16px;
  background: var(--mc-bg-muted);
  color: var(--mc-text-tertiary);
}
.pb-distchip.is-running {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
}
.pb-distchip.is-pending {
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
}
.pb-distchip.is-completed {
  background: var(--mc-success-bg, var(--mc-bg-muted));
  color: var(--mc-success);
}
.pb-card__steps {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  font-variant-numeric: tabular-nums;
}

/* Progress */
.pb-progress {
  flex: 1;
  height: 4px;
  border-radius: var(--mc-radius-full);
  background: var(--mc-bg-muted);
  overflow: hidden;
}
.pb-progress__bar {
  height: 100%;
  background: var(--mc-success);
  transition: width 0.3s;
}
.pb-progress__bar.is-running { background: var(--mc-primary); }
.pb-progress__bar.is-pending { background: var(--mc-text-tertiary); }
.pb-progress__bar.is-failed { background: var(--mc-danger); }

@media (max-width: 900px) {
  .pb-agent-select {
    width: 160px;
  }
}
</style>
