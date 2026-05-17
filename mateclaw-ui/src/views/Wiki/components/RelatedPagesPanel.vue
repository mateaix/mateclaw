<template>
  <div v-if="relatedPages.length > 0" class="related-panel">
    <h4 class="related-title">{{ t('wiki.relation.relatedPages') }} ({{ relatedPages.length }})</h4>
    <div class="related-list">
      <div
        v-for="rp in relatedPages"
        :key="rp.slug"
        class="related-item"
        @click="$emit('navigate', rp.slug)"
      >
        <div class="related-signals">
          <span
            v-for="sig in rp.signals"
            :key="sig"
            class="signal-tag"
            :class="sig"
          >
            {{ signalIcon(sig) }} {{ t(`wiki.relation.${sig}`) }}
          </span>
        </div>
        <div class="related-info">
          <span class="related-item-title">{{ rp.title }}</span>
          <span class="related-score">{{ Number(rp.score).toFixed(1) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { wikiApi } from '@/api/index'

const { t } = useI18n()

const props = defineProps<{
  kbId: number | string
  slug: string
}>()

defineEmits<{
  navigate: [slug: string]
}>()

interface RelatedPage {
  slug: string
  title: string
  summary: string
  score: number
  signals: string[]
}

const relatedPages = ref<RelatedPage[]>([])

function signalIcon(sig: string): string {
  switch (sig) {
    case 'shared_chunk': return '🔗'
    case 'shared_raw': return '📂'
    case 'direct_link': return '↗'
    case 'semantic_near': return '◎'
    default: return '•'
  }
}

async function fetchRelated() {
  if (!props.kbId || !props.slug) {
    relatedPages.value = []
    return
  }
  try {
    const res: any = await wikiApi.getRelatedPages(props.kbId, props.slug, 5)
    relatedPages.value = res.data || res || []
  } catch {
    relatedPages.value = []
  }
}

watch(() => [props.kbId, props.slug], fetchRelated, { immediate: true })
</script>

<style scoped>
.related-panel {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--mc-border-light);
}

.related-title {
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  color: var(--mc-text-secondary);
  margin-bottom: 10px;
  letter-spacing: 0.05em;
}

.related-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.related-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.15s;
  border: 1px solid var(--mc-border-light);
}
.related-item:hover {
  background: var(--mc-bg-muted);
  border-color: var(--mc-border);
}

.related-signals {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.signal-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 500;
}
.signal-tag.shared_chunk { background: rgba(59,130,246,0.1); color: #3b82f6; }
.signal-tag.shared_raw { background: rgba(168,85,247,0.1); color: #a855f7; }
.signal-tag.direct_link { background: rgba(34,197,94,0.1); color: #22c55e; }
.signal-tag.semantic_near { background: rgba(234,179,8,0.1); color: #ca8a04; }

.related-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.related-item-title { font-size: 13px; font-weight: 500; color: var(--mc-text-primary); }
.related-score { font-size: 11px; color: var(--mc-text-tertiary); font-variant-numeric: tabular-nums; }
</style>
