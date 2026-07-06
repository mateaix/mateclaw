<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ContextUsageEvent } from '@/composables/chat/useChat'

const props = defineProps<{
  usage: ContextUsageEvent | null
}>()

const { t } = useI18n()

/** Compact token count, e.g. 31200 → "~31.2K". */
function fmtTokens(n: number): string {
  if (n <= 0) return '0'
  return n >= 1000 ? '~' + (n / 1000).toFixed(1) + 'K' : '~' + String(n)
}

const percent = computed(() => {
  const u = props.usage
  if (!u || u.windowTokens <= 0) return 0
  return Math.min(999, Math.round((u.usedTokens / u.windowTokens) * 1000) / 10)
})

/** Gauge color escalates as the window fills. */
const level = computed(() => {
  const p = percent.value
  if (p >= 90) return 'danger'
  if (p >= 75) return 'warn'
  return 'ok'
})

interface Segment { key: string; label: string; tokens: number; color: string }

const segments = computed<Segment[]>(() => {
  const u = props.usage
  if (!u) return []
  return [
    { key: 'system', label: t('chat.contextUsage.system'), tokens: u.systemTokens, color: 'var(--mc-ctx-system, #64748b)' },
    { key: 'tools', label: t('chat.contextUsage.tools'), tokens: u.toolsTokens, color: 'var(--mc-ctx-tools, #8b5cf6)' },
    { key: 'history', label: t('chat.contextUsage.history'), tokens: u.historyTokens, color: 'var(--mc-ctx-history, #f97316)' },
    { key: 'current', label: t('chat.contextUsage.current'), tokens: u.currentTokens, color: 'var(--mc-ctx-current, #0ea5e9)' },
  ]
})

/** Bar widths in % of the window (free space stays track-colored). */
const barSegments = computed(() => {
  const u = props.usage
  if (!u || u.windowTokens <= 0) return []
  // When over-committed (>100%), scale against usedTokens so segments still sum to 100.
  const denom = Math.max(u.windowTokens, u.usedTokens)
  return segments.value
    .filter(s => s.tokens > 0)
    .map(s => ({ ...s, width: Math.max(0.8, (s.tokens / denom) * 100) }))
})
</script>

<template>
  <el-popover
    v-if="usage && usage.windowTokens > 0"
    placement="top-end"
    trigger="click"
    :width="300"
    popper-class="mc-ctx-popover"
  >
    <template #reference>
      <button class="ctx-chip" :class="'ctx-chip--' + level" type="button" :title="$t('chat.contextUsage.chipTooltip')">
        <span class="ctx-chip__bar">
          <span class="ctx-chip__fill" :style="{ width: Math.min(100, percent) + '%' }" />
        </span>
        <span class="ctx-chip__pct">{{ percent }}%</span>
      </button>
    </template>

    <div class="ctx-panel">
      <div class="ctx-panel__head">
        <span class="ctx-panel__totals">
          {{ fmtTokens(usage.usedTokens) }} / {{ fmtTokens(usage.windowTokens) }}
        </span>
        <span class="ctx-panel__pct" :class="'ctx-panel__pct--' + level">{{ percent }}%</span>
        <span class="ctx-panel__title">{{ $t('chat.contextUsage.title') }}</span>
      </div>

      <div class="ctx-panel__track">
        <span
          v-for="s in barSegments"
          :key="s.key"
          class="ctx-panel__seg"
          :style="{ width: s.width + '%', background: s.color }"
        />
      </div>

      <div class="ctx-panel__rows">
        <div v-for="s in segments" :key="s.key" class="ctx-panel__row">
          <span class="ctx-panel__dot" :style="{ background: s.color }" />
          <span class="ctx-panel__label">{{ s.label }}</span>
          <span class="ctx-panel__value">{{ fmtTokens(s.tokens) }}</span>
        </div>
      </div>

      <div class="ctx-panel__foot">
        <span v-if="usage.willCompact" class="ctx-panel__compact-hint">{{ $t('chat.contextUsage.willCompact') }}</span>
        <span class="ctx-panel__estimate">{{ $t('chat.contextUsage.estimated') }}</span>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.ctx-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px;
  border: 1px solid var(--mc-border, #e5e7eb);
  border-radius: 10px;
  background: var(--mc-bg-elevated, #fff);
  cursor: pointer;
  line-height: 1.4;
}
.ctx-chip:hover { border-color: var(--mc-primary, #b4592d); }
.ctx-chip__bar {
  width: 44px;
  height: 5px;
  border-radius: 3px;
  background: var(--mc-border, #e5e7eb);
  overflow: hidden;
}
.ctx-chip__fill { display: block; height: 100%; border-radius: 3px; background: #10b981; transition: width .3s ease; }
.ctx-chip--warn .ctx-chip__fill { background: #f59e0b; }
.ctx-chip--danger .ctx-chip__fill { background: #ef4444; }
.ctx-chip__pct { font-size: 11px; font-variant-numeric: tabular-nums; color: var(--mc-text-secondary, #6b7280); }
.ctx-chip--danger .ctx-chip__pct { color: #ef4444; }

.ctx-panel { font-size: 13px; }
.ctx-panel__head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 10px; }
.ctx-panel__totals { font-weight: 600; font-variant-numeric: tabular-nums; }
.ctx-panel__pct--ok { color: #10b981; font-weight: 600; }
.ctx-panel__pct--warn { color: #f59e0b; font-weight: 600; }
.ctx-panel__pct--danger { color: #ef4444; font-weight: 600; }
.ctx-panel__title { color: var(--mc-text-secondary, #6b7280); margin-left: auto; }

.ctx-panel__track {
  display: flex;
  height: 6px;
  border-radius: 3px;
  background: var(--mc-border, #e5e7eb);
  overflow: hidden;
  margin-bottom: 10px;
}
.ctx-panel__seg { display: block; height: 100%; }

.ctx-panel__rows { display: flex; flex-direction: column; gap: 6px; }
.ctx-panel__row { display: flex; align-items: center; gap: 8px; }
.ctx-panel__dot { width: 9px; height: 9px; border-radius: 2px; flex: none; }
.ctx-panel__label { color: var(--mc-text-primary, #111827); }
.ctx-panel__value { margin-left: auto; font-variant-numeric: tabular-nums; color: var(--mc-text-secondary, #6b7280); }

.ctx-panel__foot { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.ctx-panel__compact-hint { color: #f59e0b; font-size: 12px; }
.ctx-panel__estimate { margin-left: auto; color: var(--mc-text-tertiary, #9ca3af); font-size: 11px; }
</style>
