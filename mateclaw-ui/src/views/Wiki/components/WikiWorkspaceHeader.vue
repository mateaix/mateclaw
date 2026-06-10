<template>
  <div class="workspace-header">
    <button class="back-btn" @click="$emit('back')">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
        <line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/>
      </svg>
      {{ t('wiki.library.backToLibrary') }}
    </button>
    <div class="workspace-title-block">
      <div class="workspace-title-row">
        <div class="workspace-icon" :style="iconStyle">{{ initial }}</div>
        <div class="workspace-title-text">
          <div class="workspace-title-line">
            <h1 class="workspace-title">{{ kb.name }}</h1>
            <span v-if="mode === 'manage'" class="workspace-mode-badge">{{ t('wiki.manageBadge') }}</span>
          </div>
          <p v-if="kb.description" class="workspace-desc">{{ kb.description }}</p>
        </div>
      </div>
    </div>
    <div v-if="mode === 'browse'" class="reading-toggle" role="tablist">
      <button
        type="button"
        class="reading-toggle-btn"
        :class="{ active: readingTab === 'pages' }"
        role="tab"
        :aria-selected="readingTab === 'pages'"
        @click="$emit('switch-reading', 'pages')"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        {{ t('wiki.pages') }}
      </button>
      <button
        type="button"
        class="reading-toggle-btn"
        :class="{ active: readingTab === 'graph' }"
        role="tab"
        :aria-selected="readingTab === 'graph'"
        @click="$emit('switch-reading', 'graph')"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="5" cy="6" r="2.5"/><circle cx="19" cy="6" r="2.5"/><circle cx="12" cy="18" r="2.5"/><path d="M7 7.5 10.5 16M17 7.5 13.5 16M6.7 6h10.6"/></svg>
        {{ t('wiki.graph.tab') }}
      </button>
      <!--
        Read-only viewers have no management view (the gear requires
        manage:wiki), so the raw-materials and recent-activity snapshot — both
        read-friendly surfaces — are offered here in the reading toggle instead.
        Managers reach them through the management view, so these stay hidden
        for them to keep the reading toggle focused on pages/graph.
      -->
      <template v-if="!canManage">
        <button
          type="button"
          class="reading-toggle-btn"
          :class="{ active: readingTab === 'raw' }"
          role="tab"
          :aria-selected="readingTab === 'raw'"
          @click="$emit('switch-reading', 'raw')"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2 2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
          {{ t('wiki.rawMaterials') }}
        </button>
        <button
          type="button"
          class="reading-toggle-btn"
          :class="{ active: readingTab === 'hotCache' }"
          role="tab"
          :aria-selected="readingTab === 'hotCache'"
          @click="$emit('switch-reading', 'hotCache')"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15 14"/></svg>
          {{ t('wiki.hotCache.tab') }}
        </button>
      </template>
    </div>
    <button
      v-if="mode === 'browse' && canManage"
      type="button"
      class="mode-switch-btn"
      :title="t('wiki.library.toManage')"
      @click="$emit('switch-mode', 'manage')"
    >
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="12" cy="12" r="3"/>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
      </svg>
      {{ t('wiki.library.toManage') }}
    </button>
    <button
      v-else-if="mode === 'manage'"
      type="button"
      class="mode-switch-btn"
      :title="t('wiki.library.toReading')"
      @click="$emit('switch-mode', 'browse')"
    >
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/>
        <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>
      </svg>
      {{ t('wiki.library.toReading') }}
    </button>
    <div class="workspace-meta">
      <span class="workspace-stat">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        {{ kb.pageCount }}
      </span>
      <span class="workspace-stat">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
        {{ kb.rawCount }}
      </span>
      <span class="kb-status-dot" :class="kb.status" :title="t(`wiki.status.${kb.status}`)"></span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { WikiKB } from '@/stores/useWikiStore'
import { kbAccent, kbAccentFg, kbInitial } from '../utils/kbVisual'

// Reading-view surfaces: pages + graph for everyone, plus raw materials and the
// recent-activity snapshot for read-only viewers who have no management view.
type ReadingTab = 'pages' | 'graph' | 'raw' | 'hotCache'

const props = defineProps<{
  kb: WikiKB
  mode: 'browse' | 'manage'
  canManage: boolean
  readingTab: ReadingTab
}>()
defineEmits<{
  (e: 'back'): void
  (e: 'switch-mode', mode: 'browse' | 'manage'): void
  (e: 'switch-reading', tab: ReadingTab): void
}>()

const { t } = useI18n()

const initial = computed(() => kbInitial(props.kb))
const iconStyle = computed(() => ({
  background: kbAccent(props.kb),
  color: kbAccentFg(props.kb),
}))
</script>

<style scoped>
.workspace-header {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 4px 4px 18px;
  flex-wrap: wrap;
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}
.back-btn:hover {
  color: var(--mc-primary);
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.workspace-title-block { flex: 1; min-width: 0; }
.workspace-title-row { display: flex; align-items: center; gap: 12px; min-width: 0; }
.workspace-icon {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 700;
  flex-shrink: 0;
}
.workspace-title-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.workspace-title-line { display: flex; align-items: center; gap: 8px; min-width: 0; }
.workspace-mode-badge {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-primary);
  background: var(--mc-primary-bg);
  border: 1px solid var(--mc-primary);
  padding: 1px 8px;
  border-radius: 9999px;
  line-height: 1.5;
}
.reading-toggle {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
}
.reading-toggle-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-secondary);
  border-radius: 9px;
  transition: all 0.15s;
}
.reading-toggle-btn:hover { color: var(--mc-text-primary); }
.reading-toggle-btn.active {
  color: var(--mc-primary);
  background: var(--mc-bg-elevated);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  font-weight: 600;
}
.mode-switch-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}
.mode-switch-btn:hover {
  color: var(--mc-primary);
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
}
.workspace-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 0;
  letter-spacing: -0.02em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.workspace-desc {
  font-size: 12.5px;
  color: var(--mc-text-tertiary);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.workspace-meta {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 8px 14px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
}
.workspace-stat {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  font-variant-numeric: tabular-nums;
}

.kb-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 9999px;
  flex-shrink: 0;
}
.kb-status-dot.active { background: var(--mc-success); }
.kb-status-dot.processing { background: var(--mc-primary); animation: pulse 1.4s ease-in-out infinite; }
.kb-status-dot.error { background: var(--mc-danger); }

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

@media (max-width: 980px) {
  .workspace-meta { width: 100%; justify-content: flex-start; }
}
</style>
