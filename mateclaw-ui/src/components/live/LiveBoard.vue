<template>
  <!--
    Live lifecycle board: the same real-time snapshot the grid renders, laid out
    across run/goal lifecycle columns instead of a flat grid. Running cards carry
    a goal-completion overlay (goal linked by conversationId); terminal goals
    populate the Done / Unmet columns. Read-only — status is execution-driven.
  -->
  <div class="live-board">
    <section v-for="col in columns" :key="col.key" class="lb-col" :class="`is-${col.key}`">
      <header class="lb-col__head">
        <span class="lb-dot" :class="`is-${col.key}`"></span>
        <span class="lb-col__title">{{ col.label }}</span>
        <span class="lb-col__count">{{ col.items.length }}</span>
        <span v-if="col.key === 'running' && (summary?.queued ?? 0) > 0" class="lb-col__sub">
          {{ t('live.board.queuedHint', { n: summary!.queued }) }}
        </span>
      </header>

      <div class="lb-col__body">
        <!-- Run cards (running / attention) -->
        <template v-if="col.kind === 'run'">
          <article
            v-for="run in (col.items as LiveRunCard[])"
            :key="run.conversationId"
            class="lb-card lb-card--run"
            :class="{ 'is-stuck': !!run.stuckReason, 'is-orphan': run.orphan && !run.stuckReason }"
            @click="$emit('open', run)"
          >
            <div class="lb-card__top">
              <div class="lb-avatar-wrap" :class="ringClass(run)">
                <div class="lb-avatar" :style="avatarBgStyle(run)">
                  <SkillIcon v-if="run.agentIcon" :value="run.agentIcon" :size="22" fallback="🤖" />
                  <span v-else class="lb-avatar__letter">{{ avatarLetter(run) }}</span>
                </div>
              </div>
              <div class="lb-card__id">
                <div class="lb-card__name">{{ run.agentName || t('live.unknownAgent') }}</div>
                <div class="lb-card__age">{{ formatAge(run.ageMs) }}</div>
              </div>
            </div>

            <div class="lb-card__saying">
              {{ humanSentence(run) }}
              <span v-if="run.runningToolName" class="lb-tool" :title="run.runningToolName">{{ run.runningToolName }}</span>
            </div>

            <!-- Goal overlay: the conversation's goal becomes a visible bar -->
            <div v-if="goalFor(run)" class="lb-goal">
              <div class="lb-goal__bar">
                <div class="lb-goal__fill" :style="{ width: goalPct(goalFor(run)) + '%' }"></div>
              </div>
              <span class="lb-goal__pct">{{ goalPct(goalFor(run)) }}%</span>
            </div>

            <div class="lb-card__foot">
              <div class="lb-subs" v-if="run.subagentCount > 0" :title="t('live.subagentsBadge', { n: run.subagentCount })">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
                {{ run.subagentCount }}
              </div>
              <div class="lb-actions">
                <button class="lb-act" @click.stop="$emit('stop', run)">{{ t('live.actions.stop') }}</button>
                <button v-if="run.stuckReason" class="lb-act lb-act--strong" @click.stop="$emit('recycle', run)">{{ t('live.actions.endIt') }}</button>
              </div>
            </div>
          </article>
        </template>

        <!-- Goal cards (done / failed) -->
        <template v-else>
          <article
            v-for="goal in (col.items as Goal[])"
            :key="goal.id"
            class="lb-card lb-card--goal"
            :class="`is-${col.key}`"
          >
            <div class="lb-card__name">{{ goal.title || t('live.board.noGoal') }}</div>
            <div v-if="goal.completionScore != null" class="lb-goal">
              <div class="lb-goal__bar">
                <div class="lb-goal__fill" :class="`is-${col.key}`" :style="{ width: goalPct(goal) + '%' }"></div>
              </div>
              <span class="lb-goal__pct">{{ goalPct(goal) }}%</span>
            </div>
            <p v-if="goal.progressSummary" class="lb-card__gap">{{ goal.progressSummary }}</p>
          </article>
        </template>

        <div v-if="!col.items.length" class="lb-col__empty">{{ t('live.board.emptyCol') }}</div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import SkillIcon from '@/components/common/SkillIcon.vue'
import { useLiveAgent } from '@/composables/useLiveAgent'
import type { LiveRunCard, LiveSummary, Goal } from '@/api'

const props = defineProps<{
  runs: LiveRunCard[]
  summary: LiveSummary | null
  goalByConv: Record<string, Goal>
  doneGoals: Goal[]
  failedGoals: Goal[]
}>()

defineEmits<{
  (e: 'open', run: LiveRunCard): void
  (e: 'stop', run: LiveRunCard): void
  (e: 'recycle', run: LiveRunCard): void
}>()

const { t } = useI18n()
const { avatarLetter, avatarBgStyle, ringClass, humanSentence, formatAge } = useLiveAgent()

const runningRuns = computed(() => props.runs.filter((r) => !r.stuckReason && !r.orphan))
const attentionRuns = computed(() =>
  props.runs
    .filter((r) => !!r.stuckReason || r.orphan)
    // Stuck (actionable) before orphan (merely unwatched).
    .sort((a, b) => (a.stuckReason ? 0 : 1) - (b.stuckReason ? 0 : 1)),
)

const columns = computed(() => [
  { key: 'running', kind: 'run', label: t('live.board.running'), items: runningRuns.value },
  { key: 'attention', kind: 'run', label: t('live.board.attention'), items: attentionRuns.value },
  { key: 'done', kind: 'goal', label: t('live.board.done'), items: props.doneGoals },
  { key: 'failed', kind: 'goal', label: t('live.board.failed'), items: props.failedGoals },
] as { key: string; kind: 'run' | 'goal'; label: string; items: LiveRunCard[] | Goal[] }[])

function goalFor(run: LiveRunCard): Goal | undefined {
  return props.goalByConv[run.conversationId]
}

function goalPct(goal?: Goal): number {
  if (!goal || goal.completionScore == null) return 0
  return Math.round((goal.completionScore || 0) * 100)
}
</script>

<style scoped>
.live-board {
  display: grid;
  grid-template-columns: repeat(4, minmax(220px, 1fr));
  gap: 14px;
}

.lb-col {
  display: flex;
  flex-direction: column;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border-light);
  border-radius: var(--mc-radius-lg);
  min-height: 140px;
}
.lb-col__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  border-bottom: 1px solid var(--mc-border-light);
}
.lb-col__count {
  min-width: 20px;
  padding: 0 6px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: var(--mc-radius-full);
  background: var(--mc-bg-muted);
  color: var(--mc-text-tertiary);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.lb-col__sub {
  margin-left: auto;
  font-size: 11px;
  font-weight: 500;
  color: var(--mc-text-tertiary);
}
.lb-col__body {
  flex: 1;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
  max-height: calc(100vh - 360px);
}
.lb-col__empty {
  text-align: center;
  color: var(--mc-text-quaternary);
  font-size: 12.5px;
  padding: 14px 0;
}

/* Column accent dots */
.lb-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.lb-dot.is-running { background: hsl(155, 55%, 50%); }
.lb-dot.is-attention { background: hsl(20, 80%, 55%); }
.lb-dot.is-done { background: var(--mc-success); }
.lb-dot.is-failed { background: var(--mc-danger); }

/* Cards */
.lb-card {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-md);
  padding: 11px 12px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.lb-card--run {
  cursor: pointer;
}
.lb-card--run:hover {
  border-color: var(--mc-border-strong);
  box-shadow: var(--mc-shadow-soft);
}
.lb-card--run.is-stuck {
  border-color: hsla(20, 80%, 55%, 0.45);
}
.lb-card--run.is-orphan {
  border-color: hsla(265, 50%, 60%, 0.3);
}

.lb-card__top {
  display: flex;
  align-items: center;
  gap: 10px;
}
.lb-avatar-wrap {
  position: relative;
  flex-shrink: 0;
  padding: 3px;
  margin: -3px;
}
.lb-avatar-wrap::before {
  content: '';
  position: absolute;
  inset: 1px;
  border-radius: 12px;
  border: 2px solid transparent;
  pointer-events: none;
}
.lb-avatar-wrap.ring-healthy::before { border-color: hsla(155, 55%, 50%, 0.7); }
.lb-avatar-wrap.ring-stuck::before { border-color: hsla(20, 80%, 55%, 0.8); }
.lb-avatar-wrap.ring-orphan::before { border-color: hsla(265, 50%, 60%, 0.6); }
.lb-avatar-wrap.ring-thinking::before { border-color: hsla(155, 55%, 55%, 0.5); }
.lb-avatar {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  font-weight: 700;
  font-size: 14px;
}
.lb-avatar :deep(.skill-icon) {
  width: 100% !important;
  height: 100% !important;
  display: flex;
  align-items: center;
  justify-content: center;
}
.lb-avatar :deep(.skill-icon__img) { width: 62%; height: 62%; object-fit: contain; }
.lb-avatar :deep(.skill-icon__glyph) { font-size: 18px; line-height: 1; }
.lb-card__id {
  min-width: 0;
  flex: 1;
}
.lb-card__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lb-card__age {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  font-variant-numeric: tabular-nums;
  margin-top: 2px;
}
.lb-card__saying {
  margin-top: 9px;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--mc-text-secondary);
}
.lb-tool {
  display: inline-block;
  margin-left: 4px;
  padding: 1px 6px;
  border-radius: var(--mc-radius-sm);
  background: var(--mc-accent-soft);
  color: var(--mc-accent);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

/* Goal overlay / goal cards */
.lb-goal {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 9px;
}
.lb-goal__bar {
  flex: 1;
  height: 4px;
  border-radius: var(--mc-radius-full);
  background: var(--mc-bg-muted);
  overflow: hidden;
}
.lb-goal__fill {
  height: 100%;
  background: var(--mc-primary);
  transition: width 0.3s;
}
.lb-goal__fill.is-done { background: var(--mc-success); }
.lb-goal__fill.is-failed { background: var(--mc-danger); }
.lb-goal__pct {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  font-variant-numeric: tabular-nums;
}
.lb-card__gap {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: var(--mc-text-tertiary);
  line-height: 1.45;
}

.lb-card__foot {
  display: flex;
  align-items: center;
  margin-top: 10px;
}
.lb-subs {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  font-variant-numeric: tabular-nums;
}
.lb-actions {
  margin-left: auto;
  display: flex;
  gap: 6px;
}
.lb-act {
  padding: 4px 10px;
  font-size: 11.5px;
  border-radius: var(--mc-radius-full);
  border: 1px solid var(--mc-border-light);
  background: transparent;
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.lb-act:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
  border-color: var(--mc-border);
}
.lb-act--strong {
  border-color: hsla(20, 80%, 55%, 0.5);
  color: hsl(20, 75%, 45%);
}
.lb-act--strong:hover {
  background: hsla(20, 80%, 55%, 0.12);
}

@media (max-width: 1100px) {
  .live-board {
    grid-template-columns: repeat(2, minmax(180px, 1fr));
  }
}
</style>
