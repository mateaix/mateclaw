<template>
  <!--
    Modal-style drawer that lists every page in the KB with at least one
    broken outlink. Each row shows the page title, slug, the failing target
    strings, and a button to jump into the source page so the author can fix
    the content. Pure read view — no inline editing in v1 (the user clicks
    through to the page editor, fixes the wikilink, save re-syncs broken_links).
  -->
  <Teleport to="body">
    <div v-if="open" class="lint-panel-backdrop" @click.self="$emit('close')">
      <div class="lint-panel">
        <header class="lint-panel-header">
          <h3 class="lint-panel-title">{{ t('wiki.lint.panelTitle') }}</h3>
          <button class="close-btn" @click="$emit('close')" :aria-label="t('common.close')">×</button>
        </header>

        <div v-if="!report" class="lint-panel-empty">
          {{ t('wiki.lint.noReport') }}
        </div>

        <div v-else-if="report.totalBrokenRefs === 0" class="lint-panel-empty lint-panel-empty--clean">
          {{ t('wiki.lint.cleanResult', { pages: report.totalPages }) }}
        </div>

        <div v-else class="lint-panel-body">
          <div class="lint-stats">
            <i18n-t keypath="wiki.lint.brokenSummary" tag="span">
              <template #refs><strong>{{ report.totalBrokenRefs }}</strong></template>
              <template #pages><strong>{{ report.pagesWithBrokenLinks }}</strong></template>
            </i18n-t>
          </div>
          <ul class="lint-page-list">
            <li v-for="row in report.pages" :key="row.slug" class="lint-page-row">
              <div class="lint-page-head">
                <button class="lint-page-link" @click="onOpenPage(row.slug)">
                  {{ row.title }}
                </button>
                <code class="lint-page-slug">{{ row.slug }}</code>
              </div>
              <ul class="lint-ref-list">
                <li v-for="ref in row.brokenRefs" :key="ref" class="lint-ref-tag">
                  <code>[[{{ ref }}]]</code>
                </li>
              </ul>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore } from '@/stores/useWikiStore'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const { t } = useI18n()
const store = useWikiStore()

const report = computed(() => store.brokenLinksReport)

async function onOpenPage(slug: string) {
  if (!store.currentKB) return
  await store.loadPage(Number(store.currentKB.id), slug)
  emit('close')
}
</script>

<style scoped>
.lint-panel-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.32);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 8vh 16px;
  z-index: 1100;
}
.lint-panel {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 14px;
  width: 100%;
  max-width: 640px;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.18);
}
.lint-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--mc-border-light);
}
.lint-panel-title { font-size: 15px; font-weight: 600; margin: 0; color: var(--mc-text-primary); }
.close-btn {
  border: none;
  background: transparent;
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  color: var(--mc-text-tertiary);
}
.close-btn:hover { color: var(--mc-text-primary); }

.lint-panel-empty {
  padding: 32px 24px;
  text-align: center;
  color: var(--mc-text-secondary);
  font-size: 14px;
}
.lint-panel-empty--clean { color: var(--el-color-success-dark-2, #529b2e); }

.lint-panel-body { overflow-y: auto; padding: 12px 18px 18px; }
.lint-stats { font-size: 13px; color: var(--mc-text-secondary); margin-bottom: 10px; }
.lint-page-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.lint-page-row {
  padding: 10px 12px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
}
.lint-page-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.lint-page-link {
  border: none;
  background: none;
  padding: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--mc-primary);
  cursor: pointer;
  text-align: left;
}
.lint-page-link:hover { text-decoration: underline; }
.lint-page-slug { font-size: 11.5px; color: var(--mc-text-tertiary); }
.lint-ref-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.lint-ref-tag code {
  display: inline-block;
  padding: 2px 8px;
  background: var(--el-color-warning-light-9, #fdf6ec);
  color: var(--el-color-warning-dark-2, #b88230);
  border: 1px solid var(--el-color-warning-light-5, #f0c78a);
  border-radius: 6px;
  font-size: 12px;
}
</style>
