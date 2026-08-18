<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowDown } from '@element-plus/icons-vue'
import type { TeamRun, TeamRunTask } from '@/api'
import TeamRunDeliverySummary from './TeamRunDeliverySummary.vue'
import TeamRunProgress from './TeamRunProgress.vue'
import TeamRunStatus from './TeamRunStatus.vue'
import type { TeamRunRoute } from './teamRunPresentation'

const props = withDefaults(defineProps<{
  run: TeamRun
  expanded?: boolean
  canCancel?: boolean
  selectedTaskId?: string | null
}>(), {
  expanded: false,
  canCancel: false,
  selectedTaskId: null,
})

const emit = defineEmits<{
  toggle: [expanded: boolean]
  'select-task': [task: TeamRunTask]
  cancel: [runId: string]
  navigate: [route: TeamRunRoute]
}>()

const { t } = useI18n()
const isExpanded = ref(props.expanded)
const outcomePreview = computed(() => {
  const text = (props.run.finalSummary || '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[#>*_`|\[\]()~-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > 160 ? `${text.slice(0, 157)}...` : text
})
const deliverableCount = computed(() => props.run.metrics?.deliverableCount ?? props.run.deliverables?.length ?? 0)
const attentionCount = computed(() => props.run.attentionItems?.length ?? 0)
watch(() => props.expanded, value => { isExpanded.value = value })

function toggle() {
  isExpanded.value = !isExpanded.value
  emit('toggle', isExpanded.value)
}
</script>

<template>
  <article class="run-card" :class="`is-${run.status}`">
    <button
      data-team-run-toggle
      type="button"
      class="run-card__toggle"
      :aria-expanded="isExpanded"
      :aria-controls="`team-run-detail-${run.id}`"
      :aria-label="t(isExpanded ? 'teamRuns.collapse' : 'teamRuns.expand')"
      @click="toggle"
      @keydown.enter.space.prevent="toggle"
    >
      <span class="run-card__copy">
        <span class="run-card__title">{{ run.title }}</span>
        <span class="run-card__objective">{{ run.objective }}</span>
        <span v-if="outcomePreview" data-team-run-outcome-preview class="run-card__outcome">{{ outcomePreview }}</span>
        <span class="run-card__facts">
          <span v-if="run.outcomeQuality" data-team-run-outcome-quality>{{ t(`teamRuns.quality.${run.outcomeQuality}`) }}</span>
          <span data-team-run-deliverable-count>{{ deliverableCount }} {{ t('teamRuns.deliverables') }}</span>
          <span data-team-run-attention-count>{{ attentionCount }} {{ t('teamRuns.attention') }}</span>
        </span>
        <TeamRunStatus
          :status="run.status"
          :started-at="run.startedAt"
          :completed-at="run.completedAt"
          show-duration
        />
      </span>
      <span class="run-card__controls">
        <TeamRunProgress :progress="run.progress" compact />
        <el-icon class="run-card__arrow" :class="{ 'is-expanded': isExpanded }" :size="15"><ArrowDown /></el-icon>
      </span>
    </button>
    <TeamRunDeliverySummary
      v-if="isExpanded"
      :id="`team-run-detail-${run.id}`"
      :run="run"
    />
  </article>
</template>

<style scoped>
.run-card {
  width: 100%;
  overflow: hidden;
  border: 1px solid var(--mc-border, #d9e1e7);
  border-left: 3px solid #7b8794;
  border-radius: 8px;
  background: var(--mc-bg, #fff);
  letter-spacing: 0;
}
.run-card.is-running, .run-card.is-finalizing, .run-card.is-completed { border-left-color: #1b8f68; }
.run-card.is-awaiting_review, .run-card.is-partial { border-left-color: #b96c08; }
.run-card.is-failed { border-left-color: #c13d3d; }
.run-card__toggle { display: flex; align-items: center; justify-content: space-between; gap: 16px; width: 100%; min-height: 76px; padding: 12px 14px; border: 0; background: transparent; color: inherit; cursor: pointer; text-align: left; letter-spacing: 0; }
.run-card__toggle:hover { background: rgba(71, 85, 105, 0.035); }
.run-card__toggle:focus-visible { outline: 2px solid #1b8f68; outline-offset: -2px; }
.run-card__copy { display: grid; min-width: 0; gap: 4px; }
.run-card__title { color: var(--mc-text-primary, #1f2937); font-size: 14px; font-weight: 700; overflow-wrap: anywhere; }
.run-card__objective { display: -webkit-box; overflow: hidden; color: var(--mc-text-secondary, #475569); font-size: 12px; line-height: 1.45; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.run-card__outcome { display: -webkit-box; max-width: 72ch; overflow: hidden; color: var(--mc-text-secondary, #475569); font-size: 12px; line-height: 1.45; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.run-card__facts { display: flex; min-width: 0; flex-wrap: wrap; gap: 4px 10px; color: var(--mc-text-tertiary, #64748b); font-size: 11px; }
.run-card__controls { display: flex; align-items: center; gap: 10px; flex: none; }
.run-card__arrow { color: var(--mc-text-tertiary, #64748b); transition: transform 0.18s ease; }
.run-card__arrow.is-expanded { transform: rotate(180deg); }
@media (prefers-reduced-motion: reduce) { .run-card__arrow { transition: none; } }
@media (max-width: 520px) {
  .run-card__toggle { align-items: flex-start; gap: 8px; padding: 11px 10px; }
  .run-card__controls { gap: 5px; }
}
</style>
