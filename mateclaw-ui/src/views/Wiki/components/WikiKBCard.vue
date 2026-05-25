<template>
  <div
    class="kb-card mc-surface-card"
    :class="{ 'kb-card--has-warn': failedJobCount > 0 }"
    role="button"
    tabindex="0"
    @click="$emit('open', kb.id)"
    @keydown.enter.prevent="$emit('open', kb.id)"
    @keydown.space.prevent="$emit('open', kb.id)"
  >
    <button
      v-if="canManageWiki"
      type="button"
      class="kb-card-delete"
      :title="t('wiki.library.deleteKB')"
      :aria-label="t('wiki.library.deleteKB')"
      @click.stop="$emit('delete', kb)"
      @keydown.enter.stop
      @keydown.space.stop
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="3 6 5 6 21 6"/>
        <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>
        <path d="M10 11v6"/><path d="M14 11v6"/>
        <path d="M9 6V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2"/>
      </svg>
    </button>
    <div class="kb-card-top">
      <div class="kb-card-icon" :style="iconStyle">{{ initial }}</div>
      <span
        class="kb-status-dot"
        :class="kb.status"
        :title="t(`wiki.status.${kb.status}`)"
      ></span>
    </div>
    <div class="kb-card-body">
      <h3 class="kb-card-name">{{ kb.name }}</h3>
      <p class="kb-card-desc" :class="{ 'kb-card-desc--muted': !kb.description }">
        {{ kb.description || t('wiki.library.noDescription') }}
      </p>
    </div>
    <div class="kb-card-footer">
      <div class="kb-card-stats">
        <span class="kb-card-stat">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
          {{ kb.pageCount }}
        </span>
        <span class="kb-card-stat">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
          {{ kb.rawCount }}
        </span>
      </div>
      <span class="kb-card-time">{{ relative }}</span>
    </div>
    <div v-if="failedJobCount > 0" class="kb-card-warn">
      ⚠ {{ t('wiki.stats.failedJobs', { count: failedJobCount }) }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { WikiKB } from '@/stores/useWikiStore'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { kbAccent, kbAccentFg, kbInitial, relativeTime } from '../utils/kbVisual'

const props = defineProps<{
  kb: WikiKB
  failedJobCount?: number
}>()

defineEmits<{
  (e: 'open', id: number): void
  (e: 'delete', kb: WikiKB): void
}>()

const { t, locale } = useI18n()
const workspace = useWorkspaceStore()

// Deleting a knowledge base requires manage:wiki; hide it from read-only viewers.
const canManageWiki = computed(() => workspace.can('manage:wiki'))

const failedJobCount = computed(() => props.failedJobCount ?? 0)
const initial = computed(() => kbInitial(props.kb))
const iconStyle = computed(() => ({
  background: kbAccent(props.kb),
  color: kbAccentFg(props.kb),
}))
const relative = computed(() => relativeTime(props.kb.updateTime, locale.value.startsWith('zh')))
</script>

<style scoped>
.kb-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px;
  text-align: left;
  cursor: pointer;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  transition: transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease;
  font-family: inherit;
}
.kb-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.10);
  border-color: var(--mc-border);
}
.kb-card:focus-visible { outline: 2px solid var(--mc-primary); outline-offset: 2px; }

.kb-card-delete {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-tertiary);
  cursor: pointer;
  opacity: 0;
  transform: translateY(-2px);
  transition: opacity 0.15s ease, transform 0.15s ease, color 0.15s ease, border-color 0.15s ease, background 0.15s ease;
  padding: 0;
  z-index: 1;
}
.kb-card:hover .kb-card-delete,
.kb-card:focus-within .kb-card-delete {
  opacity: 1;
  transform: translateY(0);
}
.kb-card-delete:hover {
  color: var(--mc-danger);
  border-color: rgba(245, 108, 108, 0.45);
  background: rgba(245, 108, 108, 0.08);
}
.kb-card-delete:focus-visible {
  outline: 2px solid var(--mc-danger);
  outline-offset: 2px;
  opacity: 1;
  transform: translateY(0);
}

.kb-card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.kb-card-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.02em;
  flex-shrink: 0;
}

.kb-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 9999px;
  flex-shrink: 0;
  margin-top: 6px;
}
.kb-status-dot.active { background: var(--mc-success); }
.kb-status-dot.processing { background: var(--mc-primary); animation: pulse 1.4s ease-in-out infinite; }
.kb-status-dot.error { background: var(--mc-danger); }

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.kb-card-body { display: flex; flex-direction: column; gap: 4px; min-height: 56px; }
.kb-card-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 0;
  letter-spacing: -0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.kb-card-desc {
  font-size: 12.5px;
  color: var(--mc-text-secondary);
  margin: 0;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.kb-card-desc--muted { color: var(--mc-text-tertiary); font-style: italic; }

.kb-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--mc-border-light);
}
.kb-card-stats { display: flex; align-items: center; gap: 10px; }
.kb-card-stat {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  font-variant-numeric: tabular-nums;
}
.kb-card-time {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  font-variant-numeric: tabular-nums;
}

.kb-card-warn {
  font-size: 11px;
  color: var(--mc-danger);
  background: rgba(245, 108, 108, 0.08);
  padding: 4px 8px;
  border-radius: 6px;
  margin-top: -2px;
}
.kb-card--has-warn { border-color: rgba(245, 108, 108, 0.3); }
</style>
