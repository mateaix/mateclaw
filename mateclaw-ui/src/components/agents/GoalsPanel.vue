<template>
  <!--
    Active-goals panel — same right-anchored slide-in shell as PlanDetailPanel
    (backdrop blur + mc-surface-card + round close), replacing the default
    el-drawer chrome so both board popups share one design language.
  -->
  <Teleport to="body">
    <Transition name="pd-slide">
      <div v-if="open" class="pd-backdrop" @click.self="$emit('close')">
        <aside class="pd-panel mc-surface-card" role="dialog" aria-modal="true">
          <button class="pd-close" type="button" :aria-label="t('live.actions.close')" @click="$emit('close')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M6 6 L18 18 M18 6 L6 18"/></svg>
          </button>

          <div class="gp-head">
            <h2 class="gp-head__title">{{ t('plans.activeGoals') }}</h2>
          </div>

          <div v-if="loading" class="pd-loading">{{ t('common.loading') }}</div>
          <el-empty v-else-if="!goals.length" :description="t('plans.noGoals')" />

          <div v-else class="gp-list">
            <div v-for="goal in goals" :key="goal.id" class="gp-goal">
              <div class="gp-goal__title">{{ cleanGoal(goal.title) }}</div>
              <p v-if="showDesc(goal)" class="gp-goal__desc">{{ cleanGoal(goal.description) }}</p>

              <div class="gp-goal__score" v-if="goal.completionScore != null">
                <div class="gp-progress">
                  <div class="gp-progress__fill" :style="{ width: pct(goal) + '%' }"></div>
                </div>
                <span>{{ pct(goal) }}%</span>
              </div>

              <ul v-if="goal.criteria?.length" class="gp-criteria">
                <li v-for="c in goal.criteria" :key="c.id" :class="{ 'is-passed': c.passed }">
                  <span class="gp-check">
                    <svg v-if="c.passed" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
                    <svg v-else width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/></svg>
                  </span>
                  <span>{{ c.text }}</span>
                </li>
              </ul>

              <p v-if="goal.progressSummary" class="gp-goal__gap">{{ goal.progressSummary }}</p>
            </div>
          </div>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { watch, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Goal } from '@/api'

const props = defineProps<{
  open: boolean
  goals: Goal[]
  loading: boolean
}>()

const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()

// Strip the appended "[Follow-up guidance] ..." block so titles/descriptions
// read as the original objective, not the internal re-prompt.
function cleanGoal(text?: string | null): string {
  if (!text) return ''
  const i = text.indexOf('[Follow-up guidance]')
  return (i >= 0 ? text.slice(0, i) : text).trim()
}

// The description often duplicates the title (both seeded from the same goal
// text). Only show it when it adds something the title doesn't.
function showDesc(goal: Goal): boolean {
  const title = cleanGoal(goal.title)
  const desc = cleanGoal(goal.description)
  return !!desc && desc !== title
}

function pct(goal: Goal): number {
  return Math.round((goal.completionScore || 0) * 100)
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open) emit('close')
}

watch(() => props.open, (open) => {
  if (typeof document === 'undefined') return
  document.body.style.overflow = open ? 'hidden' : ''
})

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  if (typeof document !== 'undefined') document.body.style.overflow = ''
})
</script>

<style scoped>
/* ===== Shared right-panel shell (mirrors PlanDetailPanel) ===== */
.pd-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1500;
  display: flex;
  justify-content: flex-end;
  background: rgba(20, 14, 10, 0.42);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}
html.dark .pd-backdrop {
  background: rgba(0, 0, 0, 0.5);
}
.pd-slide-enter-active,
.pd-slide-leave-active {
  transition: opacity 0.22s ease, backdrop-filter 0.22s ease, -webkit-backdrop-filter 0.22s ease;
}
.pd-slide-enter-active .pd-panel,
.pd-slide-leave-active .pd-panel {
  transition: transform 0.3s cubic-bezier(0.22, 0.61, 0.36, 1);
}
.pd-slide-enter-from,
.pd-slide-leave-to {
  opacity: 0;
  backdrop-filter: blur(0px);
  -webkit-backdrop-filter: blur(0px);
}
.pd-slide-enter-from .pd-panel,
.pd-slide-leave-to .pd-panel {
  transform: translateX(24px);
}
.pd-panel {
  position: relative;
  width: 480px;
  max-width: 100%;
  height: 100vh;
  overflow-y: auto;
  padding: 28px 28px 32px;
  border-radius: 24px 0 0 24px;
  box-shadow: -28px 0 80px -24px rgba(0, 0, 0, 0.35);
}
.pd-close {
  position: absolute;
  top: 18px;
  right: 18px;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  border: none;
  background: var(--mc-bg-muted);
  color: var(--mc-text-tertiary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.18s ease, color 0.18s ease;
}
.pd-close:hover {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}
.pd-loading {
  font-size: 13px;
  color: var(--mc-text-tertiary);
}

/* ===== Header ===== */
.gp-head {
  margin: 2px 36px 20px 0;
}
.gp-head__title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--mc-text-primary);
}

/* ===== Goal cards ===== */
.gp-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.gp-goal {
  padding: 16px;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border-light);
  border-radius: var(--mc-radius-lg);
}
.gp-goal__title {
  font-size: 14px;
  font-weight: 700;
  color: var(--mc-text-primary);
  line-height: 1.45;
}
.gp-goal__desc {
  margin: 7px 0 0;
  font-size: 12.5px;
  color: var(--mc-text-secondary);
  line-height: 1.55;
}
.gp-goal__score {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 14px 0;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  font-variant-numeric: tabular-nums;
}
.gp-progress {
  flex: 1;
  height: 5px;
  border-radius: var(--mc-radius-full);
  background: var(--mc-bg-muted);
  overflow: hidden;
}
.gp-progress__fill {
  height: 100%;
  background: var(--mc-primary);
  transition: width 0.3s;
}
.gp-criteria {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.gp-criteria li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12.5px;
  color: var(--mc-text-tertiary);
  line-height: 1.45;
}
.gp-criteria li.is-passed {
  color: var(--mc-text-secondary);
}
.gp-check {
  display: inline-flex;
  align-items: center;
  color: var(--mc-text-quaternary);
  flex-shrink: 0;
  margin-top: 1px;
}
.gp-criteria li.is-passed .gp-check {
  color: var(--mc-success);
}
.gp-goal__gap {
  margin: 12px 0 0;
  padding-top: 12px;
  border-top: 1px dashed var(--mc-border);
  font-size: 12px;
  color: var(--mc-text-tertiary);
  line-height: 1.5;
}

@media (max-width: 600px) {
  .pd-panel {
    width: 100%;
    border-radius: 0;
    padding: 24px 20px 28px;
  }
}
</style>
