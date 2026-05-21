<script setup lang="ts">
import { computed } from 'vue'
import { useGoalStore } from '@/stores/useGoalStore'

/**
 * The Jobs-cut UI primitive: a ring + halo around the assistant avatar.
 *
 * Renders nothing when no active goal exists for the conversation. When a
 * goal is active, draws an SVG ring whose fill matches completion score,
 * adds a breathing halo while the evaluator is in flight, and exposes a
 * hover tooltip with the title + most recent gap text.
 *
 * The avatar itself is rendered by the parent (MessageBubble) inside the
 * default slot; this component is a position-relative wrapper.
 */
const props = defineProps<{
  conversationId: string | null | undefined
  size?: number
  showFollowupMark?: boolean
}>()

const goalStore = useGoalStore()

const size = computed(() => props.size ?? 34)
const ringSize = computed(() => size.value + 6)
const radius = computed(() => size.value / 2 + 1)
const circumference = computed(() => 2 * Math.PI * radius.value)

const goal = computed(() =>
  props.conversationId ? goalStore.activeGoal(props.conversationId) : null,
)
const fraction = computed(() => {
  if (!props.conversationId) return 0
  return goalStore.progressFraction(props.conversationId) ?? 0
})
const evaluating = computed(() =>
  props.conversationId ? goalStore.isEvaluating(props.conversationId) : false,
)

const dashOffset = computed(() => {
  if (!goal.value) return circumference.value
  return circumference.value * (1 - fraction.value)
})

const ringStrokeClass = computed(() => {
  if (!goal.value) return ''
  if (goal.value.status === 'completed') return 'stroke-completed'
  if (goal.value.status === 'exhausted') return 'stroke-exhausted'
  if (evaluating.value) return 'stroke-evaluating'
  return 'stroke-active'
})

const tooltip = computed(() => {
  if (!goal.value) return ''
  const parts = [goal.value.title]
  if (goal.value.progressSummary) {
    parts.push(goal.value.progressSummary)
  }
  return parts.join(' · ')
})
</script>

<template>
  <div
    class="avatar-with-ring"
    :class="{ 'is-evaluating': evaluating, 'has-goal': !!goal }"
    :style="{ width: `${size}px`, height: `${size}px` }"
  >
    <slot></slot>
    <svg
      v-if="goal"
      class="ring"
      :width="ringSize"
      :height="ringSize"
      :viewBox="`0 0 ${ringSize} ${ringSize}`"
      :style="{ top: `-3px`, left: `-3px` }"
      aria-hidden="true"
    >
      <circle
        class="ring-track"
        :cx="ringSize / 2"
        :cy="ringSize / 2"
        :r="radius"
        fill="none"
      />
      <circle
        class="ring-fill"
        :class="ringStrokeClass"
        :cx="ringSize / 2"
        :cy="ringSize / 2"
        :r="radius"
        fill="none"
        :stroke-dasharray="circumference"
        :stroke-dashoffset="dashOffset"
        stroke-linecap="round"
      />
    </svg>
    <span v-if="showFollowupMark" class="followup-mark" :title="$t('goal.autoFollowup')">↻</span>
    <span v-if="goal && tooltip" class="goal-tip">{{ tooltip }}</span>
  </div>
</template>

<style scoped>
.avatar-with-ring {
  position: relative;
  flex-shrink: 0;
  display: inline-block;
}
.avatar-with-ring .ring {
  position: absolute;
  pointer-events: none;
  transform: rotate(-90deg);
}
.ring-track {
  stroke: rgba(217, 119, 87, 0.16);
  stroke-width: 2;
}
.ring-fill {
  stroke-width: 2;
  transition: stroke-dashoffset 600ms ease, stroke 200ms ease;
}
.stroke-active { stroke: #d97757; }
.stroke-evaluating { stroke: #b6905b; }
.stroke-completed { stroke: #2f8a6d; }
.stroke-exhausted { stroke: #c5663d; }

/* Breathing halo only while the evaluator is in flight. */
.avatar-with-ring.is-evaluating::before {
  content: '';
  position: absolute;
  top: -6px;
  left: -6px;
  right: -6px;
  bottom: -6px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(182, 144, 91, 0.30) 0%, transparent 70%);
  animation: goal-breathe 1.6s ease-in-out infinite;
  pointer-events: none;
}
@keyframes goal-breathe {
  0%, 100% { transform: scale(0.85); opacity: 0.6; }
  50% { transform: scale(1.05); opacity: 1; }
}

.followup-mark {
  position: absolute;
  bottom: -2px;
  right: -2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--mc-bg-elevated, #ffffff);
  border: 1px solid var(--mc-border-light, #ebe3db);
  color: var(--mc-text-tertiary, #9b7d6c);
  font-size: 9px;
  line-height: 12px;
  text-align: center;
  font-weight: 600;
}

/* Tooltip: shown on hover only — keeps the steady state quiet. */
.goal-tip {
  visibility: hidden;
  opacity: 0;
  position: absolute;
  left: calc(100% + 12px);
  top: 50%;
  transform: translateY(-50%);
  white-space: nowrap;
  max-width: 320px;
  text-overflow: ellipsis;
  overflow: hidden;
  background: var(--mc-text-primary, #1d1612);
  color: var(--mc-bg-elevated, #ffffff);
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 12px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.18);
  transition: opacity 150ms ease;
  z-index: 10;
  pointer-events: none;
}
.avatar-with-ring:hover .goal-tip {
  visibility: visible;
  opacity: 1;
}
</style>
