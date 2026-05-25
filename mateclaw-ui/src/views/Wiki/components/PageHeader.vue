<template>
  <div class="page-header-bar">
    <div class="page-header-left">
      <!-- Page type badge -->
      <span v-if="page.pageType" class="page-type-badge" :class="page.pageType">
        {{ t(`wiki.page.type.${page.pageType}`) || page.pageType }}
      </span>

      <h2 class="page-title">{{ page.title }}</h2>

      <div class="page-meta-row">
        <span class="meta-badge">v{{ page.version }}</span>
        <span class="meta-slug">{{ page.slug }}</span>
        <span class="kicker-dot" :class="page.lastUpdatedBy === 'manual' ? 'manual' : 'ai'" />
        <span class="meta-updater">
          {{ page.lastUpdatedBy === 'ai' ? t('wiki.generatedByAi') : t('wiki.editedManually') }}
        </span>
      </div>
    </div>

    <div class="page-header-right">
      <!-- Enrichment status -->
      <span v-if="isEnriched" class="enrichment-status enriched">
        <el-icon :size="12"><Link /></el-icon>
        {{ t('wiki.page.enriched') }}
      </span>
      <button v-else-if="canManageWiki" class="btn-mini-enrich" @click="$emit('enrich')">
        <el-icon :size="12"><Link /></el-icon>
        {{ t('wiki.page.enrich') }}
      </button>

      <!-- Source citation link -->
      <button v-if="sourceCount > 0" class="btn-citations" @click="$emit('viewCitations')">
        {{ t('wiki.page.citations', { count: sourceCount }) }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Link } from '@element-plus/icons-vue'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

const { t } = useI18n()
const workspace = useWorkspaceStore()

// Enriching a page (adding cross-links) is a write action — viewers only read.
const canManageWiki = computed(() => workspace.can('manage:wiki'))

const props = defineProps<{
  page: {
    title: string
    slug: string
    version: number
    lastUpdatedBy: string
    content?: string | null
    pageType?: string | null
    sourceRawIds?: string
  }
}>()

defineEmits<{
  viewCitations: []
  enrich: []
}>()

const isEnriched = computed(() =>
  /\[\[.+?\]\]/.test(props.page.content ?? '')
)

const sourceCount = computed(() => {
  if (!props.page.sourceRawIds) return 0
  try {
    return JSON.parse(props.page.sourceRawIds).length
  } catch {
    return 0
  }
})
</script>

<style scoped>
.page-header-bar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--mc-border-light);
}

.page-header-left { min-width: 0; }

.page-type-badge {
  display: inline-block;
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 6px;
}
.page-type-badge.entity { background: rgba(59,130,246,0.12); color: #3b82f6; }
.page-type-badge.concept { background: rgba(168,85,247,0.12); color: #a855f7; }
.page-type-badge.source { background: rgba(34,197,94,0.12); color: #22c55e; }
.page-type-badge.synthesis { background: rgba(234,179,8,0.12); color: #ca8a04; }

.page-title {
  font-size: clamp(22px, 3vw, 30px);
  line-height: 1.15;
  letter-spacing: -0.03em;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 0;
}

.page-meta-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 8px;
  font-size: 12px;
  color: var(--mc-text-secondary);
}
.meta-badge { padding: 2px 8px; background: var(--mc-bg-sunken); border-radius: 6px; font-weight: 600; font-size: 11px; }
.meta-slug { font-family: 'JetBrains Mono', monospace; font-size: 11px; opacity: 0.7; }
.kicker-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.kicker-dot.ai { background: var(--el-color-primary, #409eff); }
.kicker-dot.manual { background: var(--el-color-success, #67c23a); }
.meta-updater { font-size: 11px; text-transform: uppercase; letter-spacing: 0.04em; }

.page-header-right {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-end;
  flex-shrink: 0;
}

.enrichment-status {
  font-size: 11px;
  font-weight: 500;
  padding: 3px 10px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.enrichment-status.enriched { background: rgba(34,197,94,0.1); color: #22c55e; }

.btn-mini-enrich {
  font-size: 11px;
  padding: 3px 10px;
  border: 1px dashed var(--mc-primary);
  border-radius: 6px;
  background: transparent;
  color: var(--mc-primary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.btn-mini-enrich:hover { background: var(--mc-primary-bg); }

.btn-citations {
  font-size: 11px;
  padding: 3px 10px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  cursor: pointer;
}
.btn-citations:hover { background: var(--mc-bg-sunken); color: var(--mc-primary); }
</style>
