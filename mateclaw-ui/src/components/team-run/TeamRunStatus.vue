<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CircleCheckFilled,
  CircleCloseFilled,
  Clock,
  Loading,
  RemoveFilled,
  View,
  WarningFilled,
} from '@element-plus/icons-vue'
import type { TeamRunStatus } from '@/api'
import { formatRunDuration, getRunStatusPresentation } from './teamRunPresentation'

const props = defineProps<{
  status: TeamRunStatus
  startedAt?: string | null
  completedAt?: string | null
  showDuration?: boolean
}>()

const { t } = useI18n()
const presentation = computed(() => getRunStatusPresentation(props.status))
const statusIcon = computed(() => ({
  planning: Clock,
  running: Loading,
  awaiting_review: View,
  finalizing: Loading,
  completed: CircleCheckFilled,
  partial: WarningFilled,
  failed: CircleCloseFilled,
  cancelled: RemoveFilled,
})[props.status])
const duration = computed(() => formatRunDuration(
  props.startedAt ?? null,
  props.completedAt ?? null,
  new Date(),
  {
    day: t('teamRuns.duration.day'),
    hour: t('teamRuns.duration.hour'),
    minute: t('teamRuns.duration.minute'),
    second: t('teamRuns.duration.second'),
  },
))
</script>

<template>
  <span class="run-status" :class="`is-${presentation.tone}`">
    <el-icon :class="{ 'is-loading': status === 'running' || status === 'finalizing' }" :size="14">
      <component :is="statusIcon" />
    </el-icon>
    <span>{{ t(presentation.labelKey) }}</span>
    <span v-if="showDuration && duration" class="run-status__duration">{{ duration }}</span>
  </span>
</template>

<style scoped>
.run-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 24px;
  color: var(--mc-text-secondary, #475569);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0;
  white-space: nowrap;
}
.run-status.is-green { color: #16835b; }
.run-status.is-amber { color: #a15c05; }
.run-status.is-red { color: #c13d3d; }
.run-status__duration {
  color: var(--mc-text-tertiary, #64748b);
  font-weight: 500;
}
</style>
