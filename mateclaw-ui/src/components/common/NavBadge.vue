<template>
  <span
    v-if="visible"
    class="nav-badge"
    :class="[`nav-badge--${tone}`, badgeMode, { 'is-collapsed': collapsed }]"
    :title="title"
  >
    <template v-if="mode === 'count'">{{ display }}</template>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * Sidebar attention badge. Two modes:
 *   - dot: silent presence indicator (replaces the legacy backstage pulse).
 *   - count: small pill with an actionable number (e.g. pending approvals).
 *
 * When the host nav item collapses, the badge floats to the top-right corner
 * so it stays visible without competing with the icon.
 */
const props = withDefaults(
  defineProps<{
    /** Count to render; <= 0 hides the badge in count mode. */
    count?: number
    /** Force dot mode regardless of count (used for "something needs attention" signals without a number). */
    dot?: boolean
    /** Visual severity. urgent = red, warning = orange (matches the legacy backstage tint). */
    tone?: 'urgent' | 'warning'
    /** Whether the surrounding sidebar is collapsed; controls corner placement. */
    collapsed?: boolean
    /** Optional tooltip on hover. */
    title?: string
  }>(),
  {
    count: 0,
    dot: false,
    tone: 'urgent',
    collapsed: false,
    title: '',
  }
)

const mode = computed<'dot' | 'count'>(() => (props.dot ? 'dot' : 'count'))
const visible = computed(() => (mode.value === 'dot' ? true : props.count > 0))
const display = computed(() => (props.count > 99 ? '99+' : String(props.count)))
const badgeMode = computed(() => `nav-badge--${mode.value}`)
</script>

<style scoped>
.nav-badge {
  position: absolute;
  right: 14px;
  top: 50%;
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  pointer-events: none;
}

/* Tone — urgent (red) */
.nav-badge--urgent {
  --badge-bg: hsl(0, 75%, 55%);
  --badge-pulse: hsla(0, 75%, 55%, 0.55);
}

/* Tone — warning (orange) — matches the legacy backstage attention color */
.nav-badge--warning {
  --badge-bg: hsl(20, 80%, 55%);
  --badge-pulse: hsla(20, 80%, 55%, 0.55);
}

/* Dot mode: pulsing 8px circle */
.nav-badge--dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--badge-bg);
  animation: nav-badge-pulse 2.4s ease-in-out infinite;
}

/* Count mode: rounded pill, no pulse (the changing number is the signal) */
.nav-badge--count {
  min-width: 18px;
  height: 18px;
  padding: 0 6px;
  border-radius: 9px;
  color: #fff;
  background: var(--badge-bg);
}

@keyframes nav-badge-pulse {
  0%, 100% { transform: translateY(-50%) scale(1);    box-shadow: 0 0 0 0 var(--badge-pulse); }
  50%      { transform: translateY(-50%) scale(1.15); box-shadow: 0 0 0 6px transparent; }
}

/* Collapsed sidebar — float to the top-right corner. */
.nav-badge.is-collapsed {
  right: 6px;
  top: 6px;
  transform: none;
}
.nav-badge--dot.is-collapsed {
  animation-name: nav-badge-pulse-corner;
}
.nav-badge--count.is-collapsed {
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  font-size: 10px;
  border-radius: 8px;
}

@keyframes nav-badge-pulse-corner {
  0%, 100% { transform: scale(1);   box-shadow: 0 0 0 0 var(--badge-pulse); }
  50%      { transform: scale(1.2); box-shadow: 0 0 0 5px transparent; }
}
</style>
