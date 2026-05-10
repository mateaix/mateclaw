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
          <h1 class="workspace-title">{{ kb.name }}</h1>
          <p v-if="kb.description" class="workspace-desc">{{ kb.description }}</p>
        </div>
      </div>
    </div>
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

const props = defineProps<{ kb: WikiKB }>()
defineEmits<{ (e: 'back'): void }>()

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
