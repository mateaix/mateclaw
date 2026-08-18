<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TeamRunProgress } from '@/api'

const props = defineProps<{
  progress: TeamRunProgress
  compact?: boolean
}>()

const { t } = useI18n()
const percent = computed(() => Math.max(0, Math.min(100, Math.round(props.progress.percent || 0))))
const style = computed(() => ({ '--run-progress': `${percent.value * 3.6}deg` }))
</script>

<template>
  <div
    class="run-progress"
    :class="{ 'is-compact': compact }"
    :aria-label="t('teamRuns.progress', { done: progress.done, total: progress.total })"
    :aria-valuenow="percent"
    aria-valuemin="0"
    aria-valuemax="100"
    role="progressbar"
  >
    <span class="run-progress__ring" :style="style"><span>{{ percent }}</span></span>
    <span v-if="!compact" class="run-progress__counts">
      {{ t('teamRuns.progress', { done: progress.done, total: progress.total }) }}
    </span>
  </div>
</template>

<style scoped>
.run-progress {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 44px;
  min-height: 44px;
  letter-spacing: 0;
}
.run-progress__ring {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  border-radius: 50%;
  background: conic-gradient(#1b8f68 var(--run-progress), #d9e1e7 0);
  position: relative;
}
.run-progress__ring::before {
  content: '';
  position: absolute;
  inset: 4px;
  border-radius: 50%;
  background: var(--mc-bg, #fff);
}
.run-progress__ring > span {
  position: relative;
  font-size: 10px;
  font-weight: 700;
  color: var(--mc-text-primary, #1f2937);
}
.run-progress__ring > span::after { content: '%'; }
.run-progress__counts {
  color: var(--mc-text-tertiary, #64748b);
  font-size: 12px;
  white-space: nowrap;
}
.run-progress.is-compact { width: 44px; }
</style>
