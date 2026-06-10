<template>
  <div class="wiki-workspace">
    <WikiWorkspaceHeader
      :kb="kb"
      :mode="store.workspaceMode"
      :can-manage="canManageWiki"
      :reading-tab="readingTab"
      @back="store.backToLibrary()"
      @switch-mode="store.setWorkspaceMode($event)"
      @switch-reading="activeTab = $event"
    />

    <!--
      Broken-link banner — surfaces lint state so the user can trigger a scan
      or view results. Only shown in the reading (browse) view; the management
      view is config/raw surfaces where page-lint state isn't relevant.
    -->
    <template v-if="store.workspaceMode === 'browse'">
      <WikiBrokenLinksBanner @view="brokenPanelOpen = true" />
      <WikiBrokenLinksPanel :open="brokenPanelOpen" @close="brokenPanelOpen = false" />
    </template>

    <div class="wiki-layout">
      <!--
        Page directory tree — reading-view navigation for the page viewer only.
        Hidden on the graph (the graph is its own navigation) and in manage
        mode, so the content area spans full width in both.
      -->
      <WikiPageSidebar
        v-if="store.workspaceMode === 'browse' && activeTab === 'pages'"
        @open-page="onOpenPage"
      />

      <div class="wiki-content mc-surface-card">
        <div class="wiki-content-body">
          <!--
            Tab strip is the management-view navigation only. In the reading
            view the page/graph switch lives in the header (segmented control),
            so no tab strip here.
          -->
          <div v-if="store.workspaceMode === 'manage'" class="content-tabs">
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

          <div v-if="activeTab === 'advanced'" class="tab-content">
            <WikiAdvancedPanel />
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
import WikiAdvancedPanel from './WikiAdvancedPanel.vue'
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

// Reading-view surfaces (driven by the header toggle) vs management-only surfaces
// (driven by the manage-view tab strip). 'raw' and 'hotCache' appear in both: in
// the reading toggle for read-only viewers, and in the management tab strip for
// managers — so the union spans every key activeTab can take.
type ReadingTab = 'pages' | 'graph' | 'raw' | 'hotCache'
type WikiTab = ReadingTab | 'config' | 'transformations' | 'advanced'

const activeTab = ref<WikiTab>('pages')
const brokenPanelOpen = ref(false)

// Narrow activeTab to the reading subset for the header toggle's highlight. In
// browse mode activeTab is always a reading key; the management-only keys map to
// 'pages' as a harmless default (the toggle isn't rendered in manage mode).
const readingTab = computed<ReadingTab>(() => {
  const t = activeTab.value
  return t === 'graph' || t === 'raw' || t === 'hotCache' ? t : 'pages'
})

// When a page becomes the currentPage (e.g. via the global wikilink click
// handler that lands on /wiki?kbId=X&slug=Y), switch the tab to 'pages' so
// the viewer is the thing the user sees. Only meaningful in the reading view —
// the management view has no 'pages' tab.
watch(() => store.currentPage, (page) => {
  if (page && store.workspaceMode === 'browse') activeTab.value = 'pages'
})

// Management-view tab strip. The reading view (page + graph) is driven by the
// header's segmented control instead, so it has no tabs here. Entry into manage
// mode is gated by manage:wiki, but guard here too so a read-only viewer never
// sees management tabs even if the mode is somehow forced.
const tabs = computed<{ key: WikiTab; label: string }[]>(() => {
  if (!canManageWiki.value) return []
  return [
    { key: 'raw', label: t('wiki.sources.tab') },
    { key: 'config', label: t('wiki.config') },
    { key: 'transformations', label: t('wiki.transformations.tab') },
    { key: 'advanced', label: t('wiki.adv.tab') },
    { key: 'hotCache', label: t('wiki.hotCache.tab') },
  ]
})

// Snap to each view's default tab whenever the KB or the view mode changes, so
// the user never lands on a stale tab (or one that doesn't exist in this mode).
watch(
  () => [store.currentKB?.id, store.workspaceMode],
  () => { activeTab.value = store.workspaceMode === 'manage' ? 'raw' : 'pages' },
  { immediate: true },
)

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
