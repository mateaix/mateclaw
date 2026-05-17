<template>
  <Teleport to="body">
    <div v-if="modelValue" class="citation-overlay" @click.self="$emit('update:modelValue', false)">
      <div class="citation-drawer">
        <div class="drawer-header">
          <h3 class="drawer-title">{{ t('wiki.page.citations', { count: citations.length }) }}</h3>
          <button class="drawer-close" @click="$emit('update:modelValue', false)">✕</button>
        </div>
        <div v-if="loading" class="drawer-loading">{{ t('common.loading') }}</div>
        <div v-else-if="citations.length === 0" class="drawer-empty">
          {{ t('wiki.page.noCitations') }}
        </div>
        <div v-else class="citation-list">
          <div v-for="cit in citations" :key="cit.id" class="citation-item">
            <div class="citation-raw-title">{{ cit.rawTitle || t('wiki.page.citationUnknownSource') }}</div>
            <div class="citation-chunk-info">
              {{ cit.chunkOrdinal != null ? t('wiki.page.citationChunkN', { n: cit.chunkOrdinal }) : t('wiki.page.citationChunkUnknown') }}
              <span v-if="cit.startOffset != null" class="citation-offset">
                ({{ t('wiki.page.citationOffset', { start: cit.startOffset, end: cit.endOffset }) }})
              </span>
            </div>
            <div v-if="cit.snippet" class="citation-snippet">"{{ cit.snippet }}"</div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { wikiApi } from '@/api/index'

const { t } = useI18n()

const props = defineProps<{
  modelValue: boolean
  pageId: number | string
  kbId: number | string
}>()

defineEmits<{
  'update:modelValue': [value: boolean]
}>()

interface Citation {
  id: number
  chunkId: number
  rawTitle?: string
  chunkOrdinal?: number
  startOffset?: number
  endOffset?: number
  snippet?: string
}

const citations = ref<Citation[]>([])
const loading = ref(false)

async function fetchCitations() {
  if (!props.pageId) return
  loading.value = true
  try {
    const res: any = await wikiApi.getPageCitations(props.kbId, props.pageId)
    citations.value = res.data || res || []
  } catch {
    citations.value = []
  } finally {
    loading.value = false
  }
}

watch(() => [props.modelValue, props.pageId], ([open]) => {
  if (open) fetchCitations()
}, { immediate: true })
</script>

<style scoped>
.citation-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.3);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
}

.citation-drawer {
  width: min(420px, 90vw);
  height: 100%;
  background: var(--mc-bg-elevated);
  border-left: 1px solid var(--mc-border);
  box-shadow: -8px 0 24px rgba(0,0,0,0.1);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--mc-border-light);
}
.drawer-title { font-size: 16px; font-weight: 600; margin: 0; color: var(--mc-text-primary); }
.drawer-close { border: none; background: none; font-size: 18px; cursor: pointer; color: var(--mc-text-secondary); padding: 4px 8px; border-radius: 6px; }
.drawer-close:hover { background: var(--mc-bg-sunken); }

.drawer-loading, .drawer-empty {
  padding: 32px 20px;
  text-align: center;
  color: var(--mc-text-tertiary);
  font-size: 14px;
}

.citation-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.citation-item {
  padding: 12px 14px;
  background: var(--mc-bg-muted);
  border-radius: 10px;
  border: 1px solid var(--mc-border-light);
}
.citation-raw-title { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 4px; }
.citation-chunk-info { font-size: 11px; color: var(--mc-text-secondary); margin-bottom: 6px; }
.citation-offset { color: var(--mc-text-tertiary); }
.citation-snippet {
  font-size: 12px;
  color: var(--mc-text-secondary);
  line-height: 1.5;
  font-style: italic;
  max-height: 80px;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
