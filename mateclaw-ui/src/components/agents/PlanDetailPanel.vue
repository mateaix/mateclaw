<template>
  <!--
    Plan (task) detail — a right-anchored slide-in panel matching the project's
    custom focus-panel language (backdrop blur + mc-surface-card + round close),
    NOT the default el-drawer chrome. Content is laid out like a kanban task
    card: assignee + status header, KPI tiles, output, and a step timeline.
  -->
  <Teleport to="body">
    <Transition name="pd-slide">
      <div v-if="open && plan" class="pd-backdrop" @click.self="$emit('close')">
        <aside class="pd-panel mc-surface-card" role="dialog" aria-modal="true">
          <button class="pd-close" type="button" :aria-label="t('live.actions.close')" @click="$emit('close')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M6 6 L18 18 M18 6 L6 18"/></svg>
          </button>

          <!-- Header: assignee + status -->
          <div class="pd-head">
            <div class="pd-assignee">
              <div class="pd-avatar">
                <SkillIcon v-if="assigneeIcon" :value="assigneeIcon" :size="20" fallback="🤖" />
                <span v-else class="pd-avatar__letter">{{ letter(assigneeName) }}</span>
              </div>
              <div class="pd-assignee__id">
                <div class="pd-assignee__name">{{ assigneeName || t('plans.unknownAgent') }}</div>
                <div class="pd-assignee__cap">{{ t('plans.assignee') }}</div>
              </div>
            </div>
            <span class="pd-status" :class="`is-${plan.status}`">{{ statusLabel(plan.status) }}</span>
          </div>

          <!-- Title -->
          <h2 class="pd-title">{{ cleanGoal(plan.goal) }}</h2>

          <!-- KPI tiles -->
          <div class="pd-tiles">
            <div class="pd-tile">
              <div class="pd-tile__value">{{ plan.completedSteps }}/{{ plan.totalSteps }}</div>
              <div class="pd-tile__label">{{ t('plans.steps') }}</div>
            </div>
            <div class="pd-tile">
              <div class="pd-tile__value">{{ pct }}%</div>
              <div class="pd-tile__label">{{ t('plans.progress') }}</div>
            </div>
            <div class="pd-tile">
              <div class="pd-tile__value pd-tile__date">{{ shortDate(plan.createTime) }}</div>
              <div class="pd-tile__label">{{ t('plans.created') }}</div>
            </div>
          </div>

          <!-- Output / summary -->
          <section v-if="plan.summary" class="pd-section">
            <div class="pd-section__title">{{ t('plans.output') }}</div>
            <div class="pd-output markdown-body" v-html="summaryHtml"></div>
          </section>

          <!-- Steps timeline -->
          <section class="pd-section">
            <div class="pd-section__title">{{ t('plans.steps') }}</div>
            <div v-if="loading" class="pd-loading">{{ t('common.loading') }}</div>
            <ol v-else class="pd-timeline">
              <li
                v-for="step in (plan.steps ?? [])"
                :key="String(step.id)"
                class="pd-step"
                :class="{ 'is-open': expanded === String(step.id), 'has-result': !!step.result }"
                @click="toggle(step)"
              >
                <span class="pd-step__dot" :class="`is-${step.status}`"></span>
                <div class="pd-step__body">
                  <div class="pd-step__title"><b>{{ step.stepIndex + 1 }}.</b> {{ step.description }}</div>
                  <div v-if="delegatedAgentName(step)" class="pd-step__agent">
                    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
                    <span>{{ t('plans.delegatedTo') }} {{ delegatedAgentName(step) }}</span>
                  </div>
                  <div v-if="step.result && expanded === String(step.id)" class="pd-step__result markdown-body" v-html="resultHtml"></div>
                  <div v-else-if="step.result" class="pd-step__hint">{{ t('plans.viewResult') }}</div>
                </div>
              </li>
            </ol>
          </section>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import SkillIcon from '@/components/common/SkillIcon.vue'
import { useStreamingMarkdown } from '@/composables/useStreamingMarkdown'
import { useAgentStore } from '@/stores/useAgentStore'
import type { Plan, SubPlan } from '@/types'

const props = defineProps<{
  open: boolean
  plan: Plan | null
  loading: boolean
  assigneeName?: string
  assigneeIcon?: string | null
}>()

const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()
const agentStore = useAgentStore()
const expanded = ref<string>('')

// Resolve the delegated agent's display name from its id via the agent store.
// Empty string when the step isn't delegated (runs with the parent agent).
function delegatedAgentName(step: SubPlan): string {
  if (step.assignedAgentId == null || step.assignedAgentId === '') return ''
  const a = agentStore.agents.find((x) => String(x.id) === String(step.assignedAgentId))
  return a?.name || ''
}

// Render the plan output and the expanded step result as markdown, reusing the
// same renderer the chat uses. `streaming = false` → full-fidelity one-shot
// render (code highlight + cache), since this content is already complete.
const expandedResultText = computed(() => {
  if (!expanded.value) return ''
  return (props.plan?.steps ?? []).find((s) => String(s.id) === expanded.value)?.result ?? ''
})
const { html: summaryHtml } = useStreamingMarkdown(() => props.plan?.summary ?? '', () => false)
const { html: resultHtml } = useStreamingMarkdown(() => expandedResultText.value, () => false)

const pct = computed(() => {
  const p = props.plan
  if (!p || !p.totalSteps) return 0
  return Math.round((p.completedSteps / p.totalSteps) * 100)
})

function statusLabel(status: string): string {
  return t(`plans.col.${status}`, status)
}

// Recover the user's actual request for the title. Plans persisted before the
// server-side scrub carry the fully-assembled prompt — a <memory-context> recall
// block, a scheduled-run wrapper whose payload follows [任务指令], and a trailing
// [Follow-up guidance] block. Strip all three so the title reads as the task.
// Mirrors the backend PlanGenerationNode.displayGoal scrubber; a no-op on clean goals.
function cleanGoal(goal: string): string {
  if (!goal) return ''
  let s = goal
    .replace(/<\s*memory-context\s*>[\s\S]*?<\s*\/\s*memory-context\s*>/gi, '')
    .replace(/<\/?\s*memory-context\s*>/gi, '')
  const task = s.lastIndexOf('[任务指令]')
  if (task >= 0) s = s.slice(task + '[任务指令]'.length)
  const followup = s.indexOf('[Follow-up guidance]')
  if (followup >= 0) s = s.slice(0, followup)
  s = s.trim()
  return s || goal.trim()
}

function letter(name?: string): string {
  return (name || '?').trim().charAt(0).toUpperCase()
}

function shortDate(ts?: string): string {
  if (!ts) return '—'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return '—'
  return `${d.getMonth() + 1}/${d.getDate()}`
}

function toggle(step: SubPlan) {
  if (!step.result) return
  expanded.value = expanded.value === String(step.id) ? '' : String(step.id)
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open) emit('close')
}

// Reset the expanded step whenever a different plan is shown.
watch(() => props.plan?.id, () => { expanded.value = '' })
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

/* Slide-in from the right */
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

/* Header */
.pd-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 4px 36px 18px 0;
}
.pd-assignee {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.pd-avatar {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  font-weight: 700;
  font-size: 15px;
}
.pd-avatar :deep(.skill-icon) { width: 100% !important; height: 100% !important; display: flex; align-items: center; justify-content: center; }
.pd-avatar :deep(.skill-icon__img) { width: 62%; height: 62%; object-fit: contain; }
.pd-avatar :deep(.skill-icon__glyph) { font-size: 20px; line-height: 1; }
.pd-assignee__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pd-assignee__cap {
  font-size: 10.5px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--mc-text-tertiary);
  margin-top: 2px;
}

.pd-status {
  flex-shrink: 0;
  padding: 4px 11px;
  border-radius: var(--mc-radius-full);
  font-size: 12px;
  font-weight: 600;
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
}
.pd-status.is-running { background: var(--mc-primary-bg); color: var(--mc-primary); }
.pd-status.is-completed { background: var(--mc-success); color: #fff; }
.pd-status.is-failed { background: var(--mc-danger-bg); color: var(--mc-danger); }

/* Title */
.pd-title {
  margin: 0 0 18px;
  font-size: 18px;
  font-weight: 700;
  line-height: 1.45;
  letter-spacing: -0.01em;
  color: var(--mc-text-primary);
}

/* KPI tiles */
.pd-tiles {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 22px;
}
.pd-tile {
  padding: 13px 10px 11px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
  text-align: center;
  min-width: 0;
}
html.dark .pd-tile {
  background: rgba(255, 255, 255, 0.04);
}
.pd-tile__value {
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--mc-text-primary);
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}
.pd-tile__date {
  font-size: 16px;
}
.pd-tile__label {
  font-size: 10.5px;
  color: var(--mc-text-tertiary);
  letter-spacing: 0.05em;
  margin-top: 7px;
}

/* Sections */
.pd-section {
  margin-top: 22px;
}
.pd-section__title {
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--mc-text-tertiary);
  font-weight: 600;
  margin-bottom: 12px;
}
.pd-output {
  padding: 14px 16px;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border-light);
  border-radius: var(--mc-radius-md);
  font-size: 13px;
  line-height: 1.7;
  color: var(--mc-text-secondary);
  word-break: break-word;
}
.pd-output :deep(> :first-child) { margin-top: 0; }
.pd-output :deep(> :last-child) { margin-bottom: 0; }
.pd-loading {
  font-size: 13px;
  color: var(--mc-text-tertiary);
}

/* Timeline */
.pd-timeline {
  list-style: none;
  margin: 0;
  padding: 0;
}
.pd-step {
  position: relative;
  display: flex;
  gap: 12px;
  padding-bottom: 16px;
}
.pd-step::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 16px;
  bottom: 0;
  width: 2px;
  background: var(--mc-border-light);
}
.pd-step:last-child { padding-bottom: 0; }
.pd-step:last-child::before { display: none; }
.pd-step.has-result { cursor: pointer; }
.pd-step__dot {
  position: relative;
  z-index: 1;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-top: 3px;
  background: var(--mc-text-tertiary);
  box-shadow: 0 0 0 3px var(--mc-bg-surface);
}
.pd-step__dot.is-pending { background: var(--mc-text-tertiary); }
.pd-step__dot.is-running { background: var(--mc-primary); }
.pd-step__dot.is-completed { background: var(--mc-success); }
.pd-step__dot.is-failed { background: var(--mc-danger); }
.pd-step__body {
  min-width: 0;
  flex: 1;
}
.pd-step__title {
  font-size: 13px;
  line-height: 1.5;
  color: var(--mc-text-primary);
  word-break: break-word;
}
.pd-step__result {
  margin-top: 8px;
  padding: 10px 12px;
  background: var(--mc-bg-sunken);
  border-radius: var(--mc-radius-md);
  font-size: 12px;
  line-height: 1.6;
  color: var(--mc-text-secondary);
  word-break: break-word;
  max-height: 280px;
  overflow-y: auto;
}
.pd-step__result :deep(> :first-child) { margin-top: 0; }
.pd-step__result :deep(> :last-child) { margin-bottom: 0; }
.pd-step__hint {
  margin-top: 5px;
  font-size: 11.5px;
  color: var(--mc-primary);
}
.pd-step__agent {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 5px;
  padding: 1px 8px;
  border-radius: var(--mc-radius-full);
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  font-size: 11px;
  font-weight: 600;
}

@media (max-width: 600px) {
  .pd-panel {
    width: 100%;
    border-radius: 0;
    padding: 24px 20px 28px;
  }
}
</style>
