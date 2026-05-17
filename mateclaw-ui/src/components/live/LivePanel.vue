<template>
  <div class="live-panel">
    <!-- Toolbar: live toggle (left) + status filters + sweep (right) -->
    <div class="live-toolbar">
      <button
        class="chip-btn"
        :class="{ 'is-paused': !autoRefresh }"
        :title="autoRefresh ? t('live.actions.pauseRefresh') : t('live.actions.resumeRefresh')"
        @click="toggleAutoRefresh"
      >
        <span class="chip-pulse" v-if="autoRefresh"></span>
        <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polygon points="5 3 19 12 5 21 5 3"/>
        </svg>
        <span>{{ autoRefresh ? t('live.actions.live') : t('live.actions.paused') }}</span>
      </button>

      <div v-if="showFilterRow" class="filter-row">
        <button
          v-for="opt in filterOptions"
          :key="opt.key"
          class="filter-chip"
          :class="{ 'is-active': activeFilter === opt.key, [`tone-${opt.tone}`]: true }"
          @click="activeFilter = opt.key"
        >
          <span class="filter-chip-label">{{ opt.label }}</span>
          <span class="filter-chip-count">{{ opt.count }}</span>
        </button>
      </div>

      <div class="toolbar-spacer"></div>

      <button
        v-if="(snapshot?.summary?.stuck ?? 0) > 0"
        class="chip-btn chip-btn-warm"
        @click="confirmSweep"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3 6h18"/>
          <path d="M19 6l-1.2 14a2 2 0 0 1-2 1.8H8.2a2 2 0 0 1-2-1.8L5 6"/>
          <path d="M10 11v6M14 11v6"/>
        </svg>
        {{ t('live.actions.tidyUp') }}
      </button>
    </div>

    <!-- Loading -->
    <div v-if="isInitialLoading" class="cards-grid">
      <div v-for="i in 3" :key="i" class="agent-card mc-surface-card agent-card-skeleton">
        <el-skeleton :rows="2" animated />
      </div>
    </div>

    <!-- Empty: nothing to see -->
    <div v-else-if="snapshot && snapshot.runs.length === 0" class="empty-still">
      <div class="empty-orb"></div>
      <div class="empty-line">{{ t('live.empty.allQuiet') }}</div>
      <div class="empty-hint">{{ t('live.empty.hint') }}</div>
    </div>

    <!-- Active runs -->
    <div v-else class="cards-grid">
      <article
        v-for="run in visibleRuns"
        :key="run.conversationId"
        class="agent-card mc-surface-card"
        :class="cardClass(run)"
        @click="openDetail(run)"
      >
        <!-- Top: avatar (with status ring) + name -->
        <div class="agent-card-top">
          <div class="agent-avatar-wrap" :class="ringClass(run)" :title="dotTitle(run)">
            <div class="agent-avatar" :style="avatarBgStyle(run)">
              <SkillIcon
                v-if="run.agentIcon"
                :value="run.agentIcon"
                :size="34"
                fallback="🤖"
              />
              <span v-else class="agent-avatar-letter">{{ avatarLetter(run) }}</span>
            </div>
          </div>
          <div class="agent-id">
            <div class="agent-name">{{ run.agentName || t('live.unknownAgent') }}</div>
            <div class="agent-owner" v-if="run.username">@{{ run.username }}</div>
          </div>
        </div>

        <!-- Status sentence + (when present) the tool chip standing
             on its own so a long tool name doesn't get ellipsis-eaten. -->
        <div class="agent-saying-row">
          <span class="agent-saying">{{ humanSentence(run) }}</span>
          <span v-if="run.runningToolName" class="tool-chip" :title="run.runningToolName">
            {{ run.runningToolName }}
          </span>
        </div>

        <!-- Meta: just the time + orphan hint (id moved to detail) -->
        <div class="agent-meta-row">
          <span class="meta-time">{{ formatAge(run.ageMs) }}</span>
          <span v-if="run.orphan && !run.stuckReason" class="meta-pill meta-pill-orphan" :title="t('live.orphanHint')">
            {{ t('live.orphan') }}
          </span>
        </div>
        <div class="agent-bar" v-if="showBar(run)">
          <div class="bar-fill" :style="progressFillStyle(run)"></div>
        </div>

        <!-- Foot: subagent stack (left) + action hierarchy (right) -->
        <div class="agent-card-foot">
          <div class="subagent-stack" v-if="childrenOf(run).length > 0">
            <div
              v-for="sub in childrenOf(run).slice(0, 3)"
              :key="sub.subagentId"
              class="subagent-chip"
              :style="avatarBgStyle(sub)"
              :title="sub.agentName || sub.subagentId"
            >
              <SkillIcon v-if="sub.agentIcon" :value="sub.agentIcon" :size="14" fallback="🤖" />
              <span v-else class="sub-chip-letter">{{ avatarLetter(sub) }}</span>
            </div>
            <div v-if="childrenOf(run).length > 3" class="subagent-chip subagent-overflow">
              +{{ childrenOf(run).length - 3 }}
            </div>
          </div>
          <div class="card-foot-actions">
            <button
              class="card-action card-action-soft"
              @click.stop="confirmStop(run)"
              :title="t('live.actions.stopHint')"
            >{{ t('live.actions.stop') }}</button>
            <button
              v-if="run.stuckReason"
              class="card-action card-action-strong"
              @click.stop="confirmRecycle(run)"
              :title="t('live.actions.endHint')"
            >{{ t('live.actions.endIt') }}</button>
          </div>
        </div>
      </article>
    </div>
  </div>

  <LiveFocusPanel
    :open="drawerOpen"
    :run="detail"
    :subagents="detail ? childrenOf(detail) : []"
    @close="closeDetail"
    @stop="confirmStop"
    @recycle="confirmRecycle"
    @interrupt-sub="confirmInterruptSub"
  />
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import SkillIcon from '@/components/common/SkillIcon.vue'
import LiveFocusPanel from '@/components/live/LiveFocusPanel.vue'
import { useLiveAgent } from '@/composables/useLiveAgent'
import { mcConfirm } from '@/components/common/useConfirm'
import { liveApi, type LiveSnapshot, type LiveRunCard, type LiveSubagentCard } from '@/api'

const { t } = useI18n()
const {
  avatarLetter,
  avatarBgStyle,
  ringClass,
  dotTitle,
  humanSentence,
  formatAge,
} = useLiveAgent()

type FilterKey = 'all' | 'working' | 'attention' | 'quiet'

const snapshot = ref<LiveSnapshot | null>(null)
const isInitialLoading = ref(true)
const autoRefresh = ref(true)
const drawerOpen = ref(false)
const detail = ref<LiveRunCard | null>(null)
const activeFilter = ref<FilterKey>('all')
let timer: ReturnType<typeof setInterval> | null = null

function isWorking(r: LiveRunCard): boolean {
  return !r.stuckReason && !r.orphan
}
function isAttention(r: LiveRunCard): boolean {
  return !!r.stuckReason
}
function isQuiet(r: LiveRunCard): boolean {
  return r.orphan && !r.stuckReason
}

const filterOptions = computed(() => {
  const runs = snapshot.value?.runs ?? []
  const counts = {
    all: runs.length,
    working: runs.filter(isWorking).length,
    attention: runs.filter(isAttention).length,
    quiet: runs.filter(isQuiet).length,
  }
  // Always show All; only show secondary chips that have at least one match —
  // an empty chip is dead pixels.
  const opts: { key: FilterKey; label: string; count: number; tone: string }[] = [
    { key: 'all', label: t('live.filters.all'), count: counts.all, tone: 'neutral' },
  ]
  if (counts.working > 0) opts.push({ key: 'working', label: t('live.filters.working'), count: counts.working, tone: 'good' })
  if (counts.attention > 0) opts.push({ key: 'attention', label: t('live.filters.attention'), count: counts.attention, tone: 'warn' })
  if (counts.quiet > 0) opts.push({ key: 'quiet', label: t('live.filters.quiet'), count: counts.quiet, tone: 'muted' })
  return opts
})

/**
 * Filter row only earns its keep when there's variety to filter.
 * Pure-healthy fleets shouldn't pay the visual tax.
 */
const showFilterRow = computed(() => {
  const s = snapshot.value?.summary
  if (!s || s.running === 0) return false
  return s.stuck > 0 || s.orphan > 0
})

/**
 * Triage order: stuck first (you have to do something), then orphan (a run
 * that nobody is watching), then working (healthy & boring). Within each
 * tier, the older run wins — a 5-minute stuck run is more urgent than one
 * stuck for 8 seconds.
 *
 * Without this, runs are listed in whatever order the snapshot returned
 * them, and the one card you actually need to look at can be 4 rows down.
 */
function tierOf(r: LiveRunCard): number {
  if (r.stuckReason) return 0
  if (r.orphan) return 1
  return 2
}

const visibleRuns = computed<LiveRunCard[]>(() => {
  const runs = snapshot.value?.runs ?? []
  const filtered = (() => {
    switch (activeFilter.value) {
      case 'working': return runs.filter(isWorking)
      case 'attention': return runs.filter(isAttention)
      case 'quiet': return runs.filter(isQuiet)
      default: return runs
    }
  })()
  return [...filtered].sort((a, b) => {
    const ta = tierOf(a)
    const tb = tierOf(b)
    if (ta !== tb) return ta - tb
    return b.ageMs - a.ageMs
  })
})

// If the filter the user picked no longer matches any rows (e.g., the stuck
// run resolved itself between refreshes), drop back to All so the panel
// doesn't go inexplicably empty.
watch(filterOptions, opts => {
  if (!opts.some(o => o.key === activeFilter.value)) {
    activeFilter.value = 'all'
  }
})

function cardClass(run: LiveRunCard) {
  return {
    'is-stuck': !!run.stuckReason,
    'is-orphan': run.orphan && !run.stuckReason,
    'is-healthy': !run.stuckReason && !run.orphan,
  }
}

/**
 * Bar only appears once a run has been going long enough that elapsed time
 * starts to matter. Under 30s is "barely started" — rendering 1px of bar
 * adds visual noise without informing the user.
 */
function showBar(run: LiveRunCard): boolean {
  return run.ageMs > 30_000
}

function progressFillStyle(run: LiveRunCard) {
  // Map age to 0..100% over the 5-minute window. Beyond that we just stay full.
  const pct = Math.min(100, ((run.ageMs - 30_000) / 270_000) * 100)
  return { width: `${Math.max(4, pct)}%` }
}

function childrenOf(run: LiveRunCard): LiveSubagentCard[] {
  return snapshot.value?.subagents.filter(s => s.parentConversationId === run.conversationId) ?? []
}

function openDetail(run: LiveRunCard) {
  // Clicking the same card while the panel is open closes it — toggle.
  // Otherwise swap content and (re)open. The focus panel handles its own
  // enter/leave animations, so all we manage here is the open boolean and
  // which run is bound.
  if (drawerOpen.value && detail.value?.conversationId === run.conversationId) {
    drawerOpen.value = false
    return
  }
  detail.value = run
  drawerOpen.value = true
}

function closeDetail() {
  drawerOpen.value = false
}

async function refresh() {
  try {
    const res: any = await liveApi.snapshot()
    snapshot.value = (res?.data ?? res) as LiveSnapshot
    if (detail.value && snapshot.value) {
      const fresh = snapshot.value.runs.find(r => r.conversationId === detail.value!.conversationId)
      if (fresh) detail.value = fresh
    }
  } catch (e: any) {
    if (isInitialLoading.value) mcToast.error(e?.message || t('live.errors.loadFailed'))
  } finally {
    isInitialLoading.value = false
  }
}

function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value
  if (autoRefresh.value) {
    refresh()
    timer = setInterval(refresh, 5000)
  } else if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function confirmStop(run: LiveRunCard) {
  const ok = await mcConfirm({
    title: t('live.confirm.stopTitle'),
    message: t('live.confirm.stopBody', { name: run.agentName || t('live.unknownAgent') }),
    confirmText: t('live.actions.stop'),
    cancelText: t('common.cancel'),
    tone: 'primary',
  })
  if (!ok) return
  try {
    await liveApi.stop(run.conversationId)
    mcToast.success(t('live.toast.stopped'))
    refresh()
  } catch (e: any) {
    mcToast.error(e?.message || t('live.errors.loadFailed'))
  }
}

async function confirmRecycle(run: LiveRunCard) {
  const ok = await mcConfirm({
    title: t('live.confirm.endTitle', { name: run.agentName || t('live.unknownAgent') }),
    message: t('live.confirm.endBody', { name: run.agentName || t('live.unknownAgent') }),
    confirmText: t('live.actions.endIt'),
    cancelText: t('common.cancel'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await liveApi.recycle(run.conversationId)
    mcToast.success(t('live.toast.ended'))
    drawerOpen.value = false
    refresh()
  } catch (e: any) {
    mcToast.error(e?.message || t('live.errors.loadFailed'))
  }
}

async function confirmInterruptSub(sub: LiveSubagentCard) {
  const ok = await mcConfirm({
    title: t('live.confirm.subTitle'),
    message: t('live.confirm.subBody', { name: sub.agentName || sub.subagentId }),
    confirmText: t('live.actions.stop'),
    cancelText: t('common.cancel'),
    tone: 'primary',
  })
  if (!ok) return
  try {
    await liveApi.interruptSubagent(sub.subagentId)
    mcToast.success(t('live.toast.subStopped'))
    refresh()
  } catch (e: any) {
    mcToast.error(e?.message || t('live.errors.loadFailed'))
  }
}

async function confirmSweep() {
  const stuckCount = snapshot.value?.summary.stuck ?? 0
  const ok = await mcConfirm({
    title: t('live.confirm.sweepTitle'),
    message: t('live.confirm.sweepBody', { n: stuckCount }),
    confirmText: t('live.actions.tidyUp'),
    cancelText: t('common.cancel'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    const res: any = await liveApi.sweep()
    const recycled = res?.data?.recycled ?? 0
    mcToast.success(t('live.toast.swept', { n: recycled }))
    refresh()
  } catch (e: any) {
    mcToast.error(e?.message || t('live.errors.loadFailed'))
  }
}

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 5000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.live-panel {
  --card-radius: 24px;
}

/* ===== Toolbar: live toggle + status filters + sweep ===== */
.live-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
}

.toolbar-spacer {
  flex: 1;
  min-width: 0;
}

/* ===== Filter chip row (kanban-inspired, soft) ===== */
.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--mc-border-light);
  background: transparent;
  color: var(--mc-text-secondary);
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease, border-color 0.18s ease;
  font-family: inherit;
  letter-spacing: -0.005em;
}

.filter-chip:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
  border-color: var(--mc-border);
}

.filter-chip-count {
  font-family: ui-monospace, SFMono-Regular, 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 10.5px;
  font-variant-numeric: tabular-nums;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--mc-bg-muted);
  color: var(--mc-text-tertiary);
  letter-spacing: 0.02em;
  border: 1px solid transparent;
}

.filter-chip:hover .filter-chip-count {
  background: var(--mc-bg-sunken);
}

.filter-chip.is-active {
  background: var(--mc-text-primary);
  color: var(--mc-bg-elevated);
  border-color: var(--mc-text-primary);
}

.filter-chip.is-active .filter-chip-count {
  background: rgba(255, 255, 255, 0.16);
  color: var(--mc-bg-elevated);
}

html.dark .filter-chip.is-active {
  /* Inverted-pill on a warm-dark surface looks too harsh as pure white;
     soften with the elevated surface token (which already adapts). */
  background: var(--mc-text-primary);
  color: var(--mc-bg);
  border-color: var(--mc-text-primary);
}

html.dark .filter-chip.is-active .filter-chip-count {
  background: rgba(0, 0, 0, 0.22);
  color: var(--mc-bg);
}

/* Active-filter accent tints — stay readable in both themes */
.filter-chip.is-active.tone-warn {
  background: hsl(20, 75%, 48%);
  border-color: hsl(20, 75%, 48%);
  color: #fff;
}

html.dark .filter-chip.is-active.tone-warn {
  background: hsl(20, 70%, 52%);
  border-color: hsl(20, 70%, 52%);
  color: #fff;
}

.filter-chip.is-active.tone-good {
  background: hsl(155, 45%, 38%);
  border-color: hsl(155, 45%, 38%);
  color: #fff;
}

html.dark .filter-chip.is-active.tone-good {
  background: hsl(155, 45%, 46%);
  border-color: hsl(155, 45%, 46%);
  color: #fff;
}

/* ===== Header chips ===== */
.chip-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 14px;
  border-radius: 999px;
  border: 1px solid var(--mc-border-light);
  background: rgba(255, 255, 255, 0.6);
  color: var(--mc-text-secondary);
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.18s ease;
  backdrop-filter: blur(8px);
}

html.dark .chip-btn {
  background: rgba(255, 255, 255, 0.04);
}

.chip-btn:hover {
  background: var(--mc-surface-overlay);
  color: var(--mc-text-primary);
  border-color: var(--mc-border);
}

.chip-btn.is-paused {
  color: var(--mc-text-tertiary);
  opacity: 0.8;
}

.chip-pulse {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: hsl(140, 55%, 50%);
  animation: chip-pulse 2.4s ease-in-out infinite;
}

@keyframes chip-pulse {
  0%, 100% { box-shadow: 0 0 0 0 hsla(140, 55%, 50%, 0.5); }
  50%      { box-shadow: 0 0 0 5px hsla(140, 55%, 50%, 0); }
}

.chip-btn-warm {
  background: linear-gradient(135deg, hsla(28, 90%, 60%, 0.14), hsla(20, 90%, 55%, 0.2));
  color: hsl(20, 70%, 40%);
  border-color: hsla(20, 80%, 55%, 0.3);
}

.chip-btn-warm:hover {
  background: linear-gradient(135deg, hsla(28, 90%, 60%, 0.22), hsla(20, 90%, 55%, 0.3));
  color: hsl(20, 75%, 35%);
}

/* ===== Cards grid ===== */
.cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 20px;
}

.agent-card {
  padding: 24px;
  border-radius: var(--card-radius);
  cursor: pointer;
  position: relative;
  transition: transform 0.28s cubic-bezier(0.22, 0.61, 0.36, 1),
              box-shadow 0.28s ease,
              border-color 0.28s ease;
}

.agent-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 16px 48px -16px rgba(0, 0, 0, 0.16),
              0 4px 12px -4px rgba(0, 0, 0, 0.06);
}

.agent-card.is-stuck {
  border-color: hsla(20, 80%, 55%, 0.45);
  background: linear-gradient(180deg, hsla(28, 100%, 96%, 0.95), hsla(20, 100%, 92%, 0.98));
}

html.dark .agent-card.is-stuck {
  background: linear-gradient(180deg, hsla(20, 35%, 22%, 0.96), hsla(15, 30%, 18%, 0.98));
}

.agent-card.is-orphan {
  border-color: hsla(265, 50%, 60%, 0.28);
}

.agent-card-skeleton {
  cursor: default;
}

/* ===== Top row: avatar (with status ring) + name ===== */
.agent-card-top {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 16px;
}

/*
 * Status ring lives on a wrap because the inner avatar must keep
 * `overflow: hidden` (to clip its icon). The ring renders via ::before
 * on the wrap, free to extend beyond the avatar's box.
 */
.agent-avatar-wrap {
  position: relative;
  flex-shrink: 0;
  display: inline-flex;
  /* Reserve room around the avatar so the ring breath doesn't get clipped
     by the parent flex item bounds. */
  padding: 4px;
  margin: -4px;
}

.agent-avatar-wrap::before {
  content: '';
  position: absolute;
  inset: 1px;
  border-radius: 19px; /* avatar 16 + 3 outset */
  border: 2px solid transparent;
  pointer-events: none;
  z-index: 0;
  /* Both transform (scale) and opacity get animated; will-change hints the
     compositor so we don't repaint the whole card on each frame. */
  will-change: transform, opacity;
}

.agent-avatar-wrap.ring-healthy::before {
  border-color: hsl(155, 55%, 50%);
  animation: ring-breathe 3s ease-in-out infinite;
}

.agent-avatar-wrap.ring-stuck::before {
  border-color: hsl(20, 80%, 55%);
  border-width: 2px;
  animation: ring-breathe-slow 6s ease-in-out infinite;
}

.agent-avatar-wrap.ring-orphan::before {
  border-color: hsla(265, 50%, 60%, 0.6);
  animation: ring-breathe-faint 8s ease-in-out infinite;
}

/*
 * Thinking: the agent is alive but hasn't streamed yet. A spinner-style
 * arc — top + right border tinted, the rest transparent — slowly rotates.
 * Communicates "working on something" without committing to a colour
 * outcome. Once the first token lands, ringClass flips to ring-healthy.
 */
.agent-avatar-wrap.ring-thinking::before {
  border-color: transparent;
  border-top-color: hsl(155, 55%, 55%);
  border-right-color: hsla(155, 55%, 55%, 0.4);
  animation: ring-spin 1.6s linear infinite;
}

/* Dark-mode tuning — rings need slightly higher lightness to sit on warm dark surfaces */
html.dark .agent-avatar-wrap.ring-healthy::before {
  border-color: hsl(155, 55%, 60%);
}

html.dark .agent-avatar-wrap.ring-stuck::before {
  border-color: hsl(20, 75%, 62%);
}

html.dark .agent-avatar-wrap.ring-orphan::before {
  border-color: hsla(265, 55%, 70%, 0.7);
}

html.dark .agent-avatar-wrap.ring-thinking::before {
  border-top-color: hsl(155, 55%, 65%);
  border-right-color: hsla(155, 55%, 65%, 0.4);
}

@keyframes ring-breathe {
  0%, 100% { opacity: 0.85; transform: scale(1); }
  50%      { opacity: 0.35; transform: scale(1.06); }
}

@keyframes ring-breathe-slow {
  0%, 100% { opacity: 1;   transform: scale(1); }
  50%      { opacity: 0.55; transform: scale(1.07); }
}

@keyframes ring-breathe-faint {
  0%, 100% { opacity: 0.5; transform: scale(1); }
  50%      { opacity: 0.3; transform: scale(1.015); }
}

@keyframes ring-spin {
  to { transform: rotate(360deg); }
}

.agent-avatar {
  width: 50px;
  height: 50px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 19px;
  letter-spacing: -0.02em;
  flex-shrink: 0;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.5),
              0 1px 2px rgba(0, 0, 0, 0.04);
  overflow: hidden;
}

html.dark .agent-avatar {
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.08);
}

.agent-avatar :deep(.skill-icon) {
  width: 100% !important;
  height: 100% !important;
  display: flex;
  align-items: center;
  justify-content: center;
}

.agent-avatar :deep(.skill-icon__glyph) {
  font-size: 28px;
  line-height: 1;
}

.agent-avatar :deep(.skill-icon__img) {
  width: 60%;
  height: 60%;
  object-fit: contain;
}

.agent-avatar :deep(.skill-icon__svg svg) {
  width: 60%;
  height: 60%;
}

.agent-avatar-letter {
  font-size: 19px;
  font-weight: 700;
}

.agent-id {
  flex: 1;
  min-width: 0;
}

.agent-name {
  font-weight: 600;
  font-size: 16px;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  letter-spacing: -0.01em;
  line-height: 1.3;
}

.agent-owner {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin-top: 3px;
  letter-spacing: 0.01em;
}

/*
 * The empty-state orb still wants its slow breath. Keep this single keyframe
 * around even though the per-card breathing dots are gone — it's used by
 * `.empty-orb` below.
 */
@keyframes breathe-healthy {
  0%, 100% { opacity: 1;    transform: scale(1); }
  50%      { opacity: 0.7;  transform: scale(0.85); }
}

/* ===== Saying + tool chip ===== */
.agent-saying-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  min-height: 22px;
}

.agent-saying {
  font-size: 14.5px;
  line-height: 1.55;
  color: var(--mc-text-secondary);
  letter-spacing: -0.005em;
}

.agent-card.is-stuck .agent-saying {
  color: hsl(20, 70%, 35%);
  font-weight: 500;
}

html.dark .agent-card.is-stuck .agent-saying {
  color: hsl(28, 80%, 76%);
}

/*
 * Tool chip — monospace, accent-soft tinted. The tool name was previously
 * baked into the sentence and got truncated on narrow cards; promoting it
 * to its own chip makes it ellipsis-resistant and visually weighty. It also
 * stops competing with the sentence for the same line of attention.
 */
.tool-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: 6px;
  background: var(--mc-accent-soft);
  color: var(--mc-accent);
  font-family: ui-monospace, SFMono-Regular, 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 11.5px;
  font-weight: 500;
  letter-spacing: 0.01em;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid transparent;
}

html.dark .tool-chip {
  background: rgba(92, 166, 157, 0.14);
  color: hsl(170, 35%, 70%);
  border-color: rgba(92, 166, 157, 0.18);
}

.agent-card.is-stuck .tool-chip {
  background: hsla(20, 100%, 90%, 0.65);
  color: hsl(20, 75%, 38%);
  border-color: hsla(20, 80%, 55%, 0.25);
}

html.dark .agent-card.is-stuck .tool-chip {
  background: hsla(20, 60%, 30%, 0.4);
  color: hsl(28, 80%, 78%);
  border-color: hsla(20, 70%, 55%, 0.3);
}

/* ===== Meta + bar ===== */
.agent-meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin-bottom: 10px;
}

.meta-time {
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.01em;
}

.meta-pill {
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--mc-bg-muted);
  font-size: 11px;
  letter-spacing: 0.01em;
  border: 1px solid var(--mc-border-light);
}

.meta-pill-orphan {
  background: hsla(265, 50%, 60%, 0.1);
  color: hsl(265, 45%, 50%);
  border-color: hsla(265, 50%, 60%, 0.2);
}

.agent-bar {
  height: 2px;
  border-radius: 1px;
  background: var(--mc-bg-muted);
  overflow: hidden;
  margin-bottom: 14px;
}

.bar-fill {
  height: 100%;
  background: linear-gradient(90deg, hsl(155, 55%, 65%), hsl(170, 50%, 55%));
  border-radius: 1px;
  transition: width 0.6s ease;
}

.is-stuck .bar-fill {
  background: linear-gradient(90deg, hsl(28, 80%, 60%), hsl(20, 80%, 55%));
}

/* ===== Foot: subagent stack (left) + actions (right) ===== */
.agent-card-foot {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 4px;
}

.card-foot-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

/*
 * Subagent stack — overlapping circular avatars, like an assignee column.
 * Up to 3 visible; the rest collapse into a "+N" chip. Subagents are good
 * news, they deserve faces, not a count.
 */
.subagent-stack {
  display: inline-flex;
  align-items: center;
}

.subagent-chip {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: -0.02em;
  border: 2px solid var(--mc-bg-elevated);
  margin-left: -7px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
  overflow: hidden;
  cursor: default;
}

.subagent-chip:first-child {
  margin-left: 0;
}

.subagent-chip :deep(.skill-icon) {
  width: 100% !important;
  height: 100% !important;
  display: flex;
  align-items: center;
  justify-content: center;
}

.subagent-chip :deep(.skill-icon__glyph) {
  font-size: 12px;
  line-height: 1;
}

.sub-chip-letter {
  font-size: 10px;
  line-height: 1;
}

.subagent-overflow {
  background: var(--mc-bg-muted) !important;
  color: var(--mc-text-tertiary);
  font-family: ui-monospace, SFMono-Regular, 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 9.5px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

html.dark .subagent-chip {
  border-color: var(--mc-bg-elevated);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
}

html.dark .subagent-overflow {
  background: rgba(255, 255, 255, 0.06) !important;
}

.card-action {
  padding: 6px 14px;
  font-size: 12.5px;
  border-radius: 999px;
  border: 1px solid var(--mc-border-light);
  background: transparent;
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: all 0.18s ease;
  font-weight: 500;
}

.card-action:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
  border-color: var(--mc-border);
}

.card-action-strong {
  border-color: hsla(20, 80%, 55%, 0.5);
  color: hsl(20, 75%, 45%);
  background: hsla(20, 100%, 95%, 0.5);
}

html.dark .card-action-strong {
  background: hsla(20, 80%, 30%, 0.15);
}

.card-action-strong:hover {
  background: hsla(20, 80%, 55%, 0.12);
  color: hsl(20, 75%, 40%);
}

/* ===== Empty state ===== */
.empty-still {
  text-align: center;
  padding: 90px 20px;
}

.empty-orb {
  width: 72px;
  height: 72px;
  margin: 0 auto 24px;
  border-radius: 50%;
  background: radial-gradient(circle at 35% 35%, hsla(155, 55%, 70%, 0.55), hsla(155, 55%, 50%, 0.18));
  animation: breathe-healthy 4s ease-in-out infinite;
}

.empty-line {
  font-size: 18px;
  font-weight: 500;
  color: var(--mc-text-primary);
  margin-bottom: 8px;
  letter-spacing: -0.01em;
}

.empty-hint {
  font-size: 13px;
  color: var(--mc-text-tertiary);
}
</style>
