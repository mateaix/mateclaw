<template>
  <div class="wiki-workspace">
    <WikiWorkspaceHeader :kb="kb" @back="store.backToLibrary()" />

    <!--
      Broken-link banner — surfaces lint state across every tab so the user
      can trigger a scan or view results from any browsing context. Pinned
      between the header and the tab layout so it's the first thing they see
      after entering the KB.
    -->
    <WikiBrokenLinksBanner @view="brokenPanelOpen = true" />
    <WikiBrokenLinksPanel :open="brokenPanelOpen" @close="brokenPanelOpen = false" />

    <div class="wiki-layout">
      <WikiPageSidebar @open-page="onOpenPage" />

      <div class="wiki-content mc-surface-card">
        <div class="wiki-content-body">
          <div class="content-tabs">
            <button
              v-for="tab in tabs" :key="tab.key"
              class="tab-btn" :class="{ active: activeTab === tab.key }"
              @click="activeTab = tab.key"
            >
              {{ tab.label }}
            </button>
          </div>

          <div v-if="activeTab === 'raw'" class="tab-content">
            <RawMaterialPanel />
          </div>

          <div v-if="activeTab === 'pages'" class="tab-content">
            <WikiPageViewer v-if="store.currentPage" />
            <div v-else class="empty-state">
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>
              </svg>
              <p>{{ t('wiki.selectPage') }}</p>
            </div>
          </div>

          <div v-if="activeTab === 'graph'" class="tab-content tab-content--graph">
            <WikiGraphView :pages="store.pages" @open-page="onOpenPage" />
          </div>

          <div v-if="activeTab === 'config'" class="tab-content tab-content--config">
            <WikiConfig />
          </div>

          <div v-if="activeTab === 'hotCache'" class="tab-content tab-content--hot-cache">
            <HotCachePanel />
          </div>
          <div v-if="activeTab === 'transformations'" class="tab-content">
            <TransformationsPanel />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore, type WikiKB } from '@/stores/useWikiStore'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import RawMaterialPanel from './RawMaterialPanel.vue'
import WikiPageViewer from './WikiPageViewer.vue'
import WikiConfig from './WikiConfig.vue'
import WikiGraphView from './WikiGraphView.vue'
import HotCachePanel from './HotCachePanel.vue'
import TransformationsPanel from './TransformationsPanel.vue'
import WikiWorkspaceHeader from './WikiWorkspaceHeader.vue'
import WikiPageSidebar from './WikiPageSidebar.vue'
import WikiBrokenLinksBanner from './WikiBrokenLinksBanner.vue'
import WikiBrokenLinksPanel from './WikiBrokenLinksPanel.vue'

defineProps<{ kb: WikiKB }>()

const { t } = useI18n()
const store = useWikiStore()
const workspace = useWorkspaceStore()

// Read-only viewers (view:wiki without manage:wiki) only get the browsing tabs;
// the processing-config and transformations tabs are management surfaces.
const canManageWiki = computed(() => workspace.can('manage:wiki'))

const activeTab = ref('raw')
const brokenPanelOpen = ref(false)

// When a page becomes the currentPage (e.g. via the global wikilink click
// handler that lands on /wiki?kbId=X&slug=Y), switch the tab to 'pages' so
// the viewer is the thing the user sees. Without this, the workspace stays
// on the default 'raw' tab and the page silently loads off-screen.
watch(() => store.currentPage, (page) => {
  if (page) activeTab.value = 'pages'
})

const tabs = computed(() => {
  const list = [
    { key: 'raw', label: t('wiki.rawMaterials') },
    { key: 'pages', label: t('wiki.pages') },
    { key: 'graph', label: t('wiki.graph.tab') },
  ]
  if (canManageWiki.value) {
    list.push({ key: 'config', label: t('wiki.config') })
    list.push({ key: 'transformations', label: t('wiki.transformations.tab') })
  }
  list.push({ key: 'hotCache', label: t('wiki.hotCache.tab') })
  return list
})

// When the user picks a different KB from the library, snap back to the
// raw-materials tab so they don't land on stale state from the previous KB.
watch(() => store.currentKB?.id, () => { activeTab.value = 'raw' })

async function onOpenPage(slug: string) {
  if (!store.currentKB) return
  await store.loadPage(store.currentKB.id, slug)
  activeTab.value = 'pages'
}
</script>

<style scoped>
.wiki-workspace {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.wiki-layout { display: flex; gap: 16px; flex: 1; min-height: 0; overflow: hidden; }

.wiki-content { flex: 1; overflow: hidden; min-width: 0; padding: 16px; display: flex; flex-direction: column; min-height: 0; }
.wiki-content-body { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.content-tabs { display: inline-flex; gap: 4px; padding: 4px; background: var(--mc-bg-muted); border-radius: 14px; margin-bottom: 14px; border: 1px solid var(--mc-border-light); align-self: flex-start; }
.tab-btn { padding: 7px 14px; border: none; background: none; cursor: pointer; font-size: 13px; color: var(--mc-text-secondary); border-radius: 10px; transition: all 0.15s; font-weight: 500; }
.tab-btn:hover { color: var(--mc-text-primary); }
.tab-btn.active { color: var(--mc-primary); background: var(--mc-bg-elevated); box-shadow: 0 1px 4px rgba(0,0,0,0.08); font-weight: 600; }
.tab-content { flex: 1; min-height: 0; overflow-y: auto; padding-right: 2px; }
.tab-content--config { overflow: hidden; padding-right: 0; }
.tab-content--graph { overflow: hidden; padding: 0; }

.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; min-height: 200px; color: var(--mc-text-tertiary); text-align: center; }
.empty-state p { font-size: 14px; }

@media (max-width: 980px) {
  .wiki-layout { flex-direction: column; overflow: visible; }
  .wiki-content { overflow: visible; }
  .tab-content { overflow: visible; }
  .tab-content--config { overflow: visible; }
}
</style>
