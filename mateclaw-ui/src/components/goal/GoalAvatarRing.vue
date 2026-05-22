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
 * Design notes (v2 cut after manual QA):
 * - The ring sits {@link RING_GAP} px outside the avatar so it reads as a
 *   halo, not a border welded onto the skin. The previous 1-px gap fused
 *   the ring into the orange logo and looked like accidental clipping.
 * - The track uses a neutral, near-transparent gray instead of the brand
 *   orange so it doesn't compete with the orange logo for attention. The
 *   fill stays brand-orange because the user *should* see progress.
 * - A subtle drop-shadow lifts the ring outside the avatar's flat plane
 *   so the halo metaphor reads even at small sizes (34 px default).
 */
const props = defineProps<{
  conversationId: string | null | undefined
  size?: number
  showFollowupMark?: boolean
}>()

const goalStore = useGoalStore()

const RING_GAP = 5
const STROKE_WIDTH = 2

const size = computed(() => props.size ?? 34)
const radius = computed(() => size.value / 2 + RING_GAP)
const ringSize = computed(() => size.value + RING_GAP * 2 + STROKE_WIDTH)
const ringOffset = computed(() => `-${RING_GAP + STROKE_WIDTH / 2}px`)
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
      :style="{ top: ringOffset, left: ringOffset }"
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
  /* inline-flex so the slot content (assistant logo img, 30 px) centers
   * inside the 34-px wrapper. Plain inline-block left the logo at the
   * top-left corner while the SVG ring rendered centered on the wrapper
   * — visually the logo looked offset toward upper-left of the ring. */
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.avatar-with-ring .ring {
  position: absolute;
  pointer-events: none;
  transform: rotate(-90deg);
  transition: transform 220ms cubic-bezier(0.4, 0, 0.2, 1);
  /* overflow:visible — SVG's default `hidden` would clip the stroke and
   * the drop-shadow glow at the viewBox edge. */
  overflow: visible;
  /* Some upstream rule (couldn't track down — possibly a UA default for
   * SVG or a global focus-ring style) was applying a 1px box-shadow on
   * the SVG element itself, rendering as a square outline regardless of
   * the circle inside. Explicitly clear it here. */
  box-shadow: none !important;
}
/* Hover invites — the ring lifts and brightens, telegraphing "you can
 * inspect me". Stays subtle to keep the steady-state quiet. */
.avatar-with-ring.has-goal:hover .ring {
  transform: rotate(-90deg) scale(1.06);
}

/* Track is the always-shown ghost of the ring — it is what tells the
 * user "this conversation has a goal" even when progress is 0%. It uses
 * the same hue as the fill (currentColor) at low opacity so the ring
 * reads as a single object: one shape, one color, two intensities.
 *
 * Why not neutral gray: gray + brand-orange logo + brand-orange fill
 * was three colors fighting in 34 px. With the same hue, the track
 * recedes naturally when the fill arc passes over it.
 */
.ring-track {
  stroke: currentColor;
  stroke-opacity: 0.45;
  stroke-width: 2;
}

.ring-fill {
  stroke-width: 2;
  transition: stroke-dashoffset 600ms ease, stroke 200ms ease, filter 200ms ease;
  /* Tiny glow lifts the arc off the flat plane so the halo metaphor
   * reads at 34 px without making the ring look heavy. */
  filter: drop-shadow(0 0 1.5px currentColor);
}
.avatar-with-ring.has-goal:hover .ring-fill {
  filter: drop-shadow(0 0 3px currentColor);
}

/* Both track and fill inherit color from these classes — track via
 * currentColor at low opacity, fill via explicit stroke + filter glow. */
.avatar-with-ring.has-goal { color: #d97757; }
.avatar-with-ring.has-goal .ring-fill.stroke-evaluating,
.avatar-with-ring.is-evaluating { color: #b6905b; }
.avatar-with-ring.has-goal .ring-fill.stroke-completed { color: #2f8a6d; }
.avatar-with-ring.has-goal .ring-fill.stroke-exhausted { color: #c5663d; }

.stroke-active { stroke: #d97757; }
.stroke-evaluating { stroke: #b6905b; }
.stroke-completed { stroke: #2f8a6d; }
.stroke-exhausted { stroke: #c5663d; }

/* Breathing halo only while the evaluator is in flight. Sized to sit
 * outside the new ring radius so it doesn't bleed into the avatar. */
.avatar-with-ring.is-evaluating::before {
  content: '';
  position: absolute;
  top: -9px;
  left: -9px;
  right: -9px;
  bottom: -9px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(182, 144, 91, 0.32) 0%, transparent 65%);
  animation: goal-breathe 1.6s ease-in-out infinite;
  pointer-events: none;
}
@keyframes goal-breathe {
  0%, 100% { transform: scale(0.85); opacity: 0.5; }
  50% { transform: scale(1.08); opacity: 1; }
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
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}

/* Tooltip: shown on hover only — keeps the steady state quiet. */
.goal-tip {
  visibility: hidden;
  opacity: 0;
  position: absolute;
  left: calc(100% + 14px);
  top: 50%;
  transform: translateY(-50%);
  white-space: nowrap;
  max-width: 360px;
  text-overflow: ellipsis;
  overflow: hidden;
  background: var(--mc-text-primary, #1d1612);
  color: var(--mc-bg-elevated, #ffffff);
  padding: 7px 12px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.4;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.22);
  transition: opacity 150ms ease, transform 150ms ease;
  z-index: 10;
  pointer-events: none;
}
.avatar-with-ring:hover .goal-tip {
  visibility: visible;
  opacity: 1;
  transform: translateY(-50%) translateX(2px);
}
</style>
