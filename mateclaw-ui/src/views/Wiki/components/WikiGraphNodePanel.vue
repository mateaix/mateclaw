<template>
  <div class="node-panel">
    <div class="node-panel-header">
      <span class="node-type-badge" :style="{ background: typeColor(page.pageType) }">
        {{ formatPageTypeLabel(page.pageType || 'other') }}
      </span>
      <button class="node-panel-close" @click="emit('close')">✕</button>
    </div>
    <div class="node-panel-title">{{ page.title }}</div>
    <div class="node-panel-summary">{{ page.summary }}</div>
    <div v-if="linkedPages.length > 0" class="node-panel-links">
      <div class="links-label">{{ t('wiki.graph.linksTo') }} ({{ linkedPages.length }})</div>
      <div class="links-list">
        <button
          v-for="link in linkedPages.slice(0, 8)"
          :key="link.slug"
          class="link-chip"
          @click="emit('open-page', link.slug)"
        >{{ link.title }}</button>
        <span v-if="linkedPages.length > 8" class="link-more">+{{ linkedPages.length - 8 }}</span>
      </div>
    </div>
    <button class="btn-open-page" @click="emit('open-page', page.slug)">
      {{ t('wiki.graph.openPage') }} →
    </button>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { WikiPage } from '@/stores/useWikiStore'
import { useWikiPageType } from '@/composables/useWikiPageType'

const { t } = useI18n()
const { typeColor, formatPageTypeLabel } = useWikiPageType()

defineProps<{
  page: WikiPage
  linkedPages: WikiPage[]
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'open-page', slug: string): void
}>()

</script>

<style scoped>
.node-panel {
  position: absolute;
  right: 12px;
  top: 12px;
  width: 240px;
  max-height: calc(100% - 24px);
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 14px;
  padding: 14px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.12);
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 10;
  overflow-y: auto;
}
.node-panel-header { display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; }
.node-type-badge {
  font-size: 10px;
  font-weight: 600;
  color: white;
  padding: 2px 8px;
  border-radius: 99px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.node-panel-close { border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); font-size: 12px; flex-shrink: 0; }
.node-panel-close:hover { color: var(--mc-text-primary); }
.node-panel-title { font-size: 14px; font-weight: 600; color: var(--mc-text-primary); flex-shrink: 0; }
.node-panel-summary {
  font-size: 12px;
  color: var(--mc-text-secondary);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
  flex-shrink: 0;
}
.node-panel-links { flex-shrink: 0; }
.links-label { font-size: 10px; font-weight: 600; color: var(--mc-text-tertiary); text-transform: uppercase; letter-spacing: 0.06em; margin-bottom: 4px; }
.links-list { display: flex; flex-wrap: wrap; gap: 4px; }
.link-chip {
  padding: 2px 8px;
  font-size: 11px;
  border: 1px solid var(--mc-border-light);
  border-radius: 99px;
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: all 0.12s;
}
.link-chip:hover { border-color: var(--mc-primary); color: var(--mc-primary); background: var(--mc-primary-bg); }
.link-more { font-size: 11px; color: var(--mc-text-tertiary); padding: 2px 4px; }
.btn-open-page {
  padding: 6px 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg-muted);
  color: var(--mc-primary);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
  text-align: center;
  flex-shrink: 0;
}
.btn-open-page:hover { background: var(--mc-primary-bg); border-color: var(--mc-primary); }
</style>
