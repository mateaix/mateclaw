<template>
  <div class="graph-toolbar">
    <div class="graph-stats">
      <span class="stat-item">
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <circle cx="12" cy="12" r="3"/><circle cx="12" cy="12" r="10" stroke-width="1.5"/>
        </svg>
        {{ nodeCount }} {{ t('wiki.graph.nodes') }}
      </span>
      <span class="stat-item">
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        {{ edgeCount }} {{ t('wiki.graph.edges') }}
      </span>
      <span v-if="orphanCount > 0" class="stat-item stat-warn">
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
        </svg>
        {{ orphanCount }} {{ t('wiki.graph.orphans') }}
      </span>
    </div>

    <div class="graph-controls">
      <label class="filter-label">
        <input :checked="showOrphans" type="checkbox" @change="emit('update:showOrphans', ($event.target as HTMLInputElement).checked)" />
        {{ t('wiki.graph.showOrphans') }}
      </label>
      <select :value="typeFilter" class="type-select" @change="emit('update:typeFilter', ($event.target as HTMLSelectElement).value)">
        <option value="">{{ t('wiki.graph.allTypes') }}</option>
        <option v-for="type in availableTypes" :key="type" :value="type">
          {{ formatPageTypeLabel(type) }}
        </option>
      </select>
      <button class="btn-icon-sm" :title="t('wiki.graph.resetView')" @click="emit('reset')">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="23 4 23 10 17 10"/>
          <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
        </svg>
      </button>
      <button
        class="btn-icon-sm"
        :title="isFullscreen ? t('wiki.graph.exitFullscreen') : t('wiki.graph.fullscreen')"
        @click="emit('toggleFullscreen')"
      >
        <!-- Enter fullscreen icon -->
        <svg v-if="!isFullscreen" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/>
          <line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/>
        </svg>
        <!-- Exit fullscreen icon -->
        <svg v-else width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/>
          <line x1="10" y1="14" x2="3" y2="21"/><line x1="21" y1="3" x2="14" y2="10"/>
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useWikiPageType } from '@/composables/useWikiPageType'

const { t } = useI18n()
const { formatPageTypeLabel } = useWikiPageType()

defineProps<{
  nodeCount: number
  edgeCount: number
  orphanCount: number
  showOrphans: boolean
  typeFilter: string
  availableTypes: string[]
  isFullscreen: boolean
}>()

const emit = defineEmits<{
  (e: 'update:showOrphans', val: boolean): void
  (e: 'update:typeFilter', val: string): void
  (e: 'reset'): void
  (e: 'toggleFullscreen'): void
}>()
</script>

<style scoped>
.graph-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--mc-border-light);
  gap: 12px;
  flex-shrink: 0;
  flex-wrap: wrap;
}

.graph-stats { display: flex; align-items: center; gap: 12px; }
.stat-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}
.stat-warn { color: var(--mc-danger, #f56c6c); }

.graph-controls { display: flex; align-items: center; gap: 8px; }
.filter-label { display: flex; align-items: center; gap: 4px; font-size: 11px; color: var(--mc-text-secondary); cursor: pointer; }

.type-select {
  padding: 3px 8px;
  font-size: 11px;
  border: 1px solid var(--mc-border-light);
  border-radius: 7px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  cursor: pointer;
  outline: none;
}

.btn-icon-sm {
  width: 26px;
  height: 26px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  border-radius: 7px;
  cursor: pointer;
  color: var(--mc-text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}
.btn-icon-sm:hover { background: var(--mc-bg-sunken); color: var(--mc-primary); }
</style>
