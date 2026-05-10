<template>
  <div class="wiki-sidebar mc-surface-card">
    <div class="sidebar-section sidebar-section--pages">
      <div class="sidebar-title-row">
        <h3 class="sidebar-label">
          {{ t('wiki.pages') }}
          <span class="count-badge">{{ store.pages.length }}</span>
        </h3>
        <div class="sidebar-title-actions">
          <button
            v-if="!batchMode"
            class="icon-btn" :title="t('wiki.batchSelect')"
            @click="batchMode = true"
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>
          </button>
          <button v-else class="icon-btn icon-btn--active" @click="exitBatchMode">✕</button>
        </div>
      </div>

      <div class="search-wrap">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input
          v-model="pageSearch"
          type="text"
          :placeholder="t('wiki.searchPages')"
          class="search-input"
        />
        <button v-if="pageSearch" class="search-clear" @click="pageSearch = ''">✕</button>
      </div>

      <div v-if="batchMode" class="batch-bar">
        <label class="batch-check-all">
          <input type="checkbox" :checked="allSelected" @change="toggleSelectAll" />
          <span>{{ t('wiki.selectAll') }}</span>
        </label>
        <button
          class="batch-delete-btn"
          :disabled="selectedSlugs.length === 0"
          @click="handleBatchDelete"
        >
          {{ t('common.delete') }} ({{ selectedSlugs.length }})
        </button>
      </div>

      <div v-if="store.selectedRawId" class="filter-banner">
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M22 3H2l8 9.46V19l4 2V12.46L22 3z"/></svg>
        {{ t('wiki.filteredByRaw') }}
        <button class="filter-clear-btn" @click="store.clearRawFilter(store.currentKB!.id)">✕</button>
      </div>

      <div class="page-list" v-if="!pageSearch" ref="pageListEl" @scroll="onPageListScroll">
        <div v-for="group in groupedPages" :key="group.type" class="page-group">
          <button
            class="group-header"
            @click="toggleGroup(group.type)"
          >
            <svg
              class="group-chevron"
              :class="{ expanded: !collapsedGroups.has(group.type) }"
              width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"
            ><polyline points="9 18 15 12 9 6"/></svg>
            <span class="group-label">{{ formatGroupLabel(group.type) }}</span>
            <span class="group-count">{{ group.pages.length }}</span>
          </button>
          <div v-if="!collapsedGroups.has(group.type)" class="group-items">
            <div
              v-for="page in paginatedGroupPages(group)" :key="page.slug"
              class="page-item"
              :class="{
                active: !batchMode && store.currentPage?.slug === page.slug,
                'page-item--system': page.pageType === 'system'
              }"
              @click="batchMode ? toggleSelect(page.slug) : openPage(page.slug)"
            >
              <input
                v-if="batchMode"
                type="checkbox"
                :checked="selectedSlugs.includes(page.slug)"
                :disabled="isProtectedPage(page)"
                class="page-checkbox"
                @click.stop="!isProtectedPage(page) && toggleSelect(page.slug)"
              />
              <div class="page-item-body">
                <div class="page-item-title">{{ page.title }}</div>
                <div class="page-item-meta">
                  <span v-if="page.lastUpdatedBy === 'manual'" class="edit-dot manual" title="Manual edit"></span>
                  <span v-if="page.pageType === 'system'" class="page-flag page-flag--system">{{ t('wiki.systemPageBadge') }}</span>
                  <span v-else-if="page.locked === 1" class="page-flag page-flag--locked">{{ t('wiki.lockedPageBadge') }}</span>
                  <span class="meta-text">v{{ page.version }}</span>
                </div>
              </div>
            </div>
            <button
              v-if="(groupPageLimit[group.type] || PAGE_STEP) < group.pages.length"
              class="load-more-btn"
              @click.stop="loadMoreGroup(group.type)"
            >
              {{ t('wiki.loadMore', { n: Math.min(PAGE_STEP, group.pages.length - (groupPageLimit[group.type] || PAGE_STEP)) }) }}
            </button>
          </div>
        </div>
        <div v-if="groupedPages.length === 0" class="empty-hint">{{ t('wiki.noPages') }}</div>

        <div class="archived-section">
          <button
            class="archived-toggle"
            :disabled="archivedLoading"
            @click="toggleArchived"
          >
            <svg
              class="archived-chevron"
              :class="{ expanded: archivedOpen }"
              width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"
            ><polyline points="9 18 15 12 9 6"/></svg>
            {{ t('wiki.archivedSection') }}
            <span v-if="archivedPages.length > 0" class="archived-count">{{ archivedPages.length }}</span>
          </button>
          <div v-if="archivedOpen" class="archived-items">
            <div v-if="archivedLoading" class="archived-empty">{{ t('common.loading') }}</div>
            <div v-else-if="archivedPages.length === 0" class="archived-empty">{{ t('wiki.noArchived') }}</div>
            <div v-else
                 v-for="page in archivedPages" :key="'arc:' + page.slug"
                 class="archived-item"
                 @click="openPage(page.slug)"
            >
              <div class="archived-item-body">
                <div class="archived-item-title">{{ page.title }}</div>
                <div class="archived-item-meta">v{{ page.version }} · {{ page.slug }}</div>
              </div>
              <button
                class="archived-restore-btn"
                :title="t('wiki.unarchive')"
                @click.stop="restoreArchivedPage(page.slug)"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <polyline points="3 7 12 12 21 7"/><path d="M3 7v10l9 5 9-5V7"/>
                </svg>
                {{ t('wiki.unarchive') }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="page-list" v-else>
        <div
          v-for="page in paginatedSearch" :key="page.slug"
          class="page-item"
          :class="{
            active: !batchMode && store.currentPage?.slug === page.slug,
            'page-item--system': page.pageType === 'system'
          }"
          @click="batchMode ? toggleSelect(page.slug) : openPage(page.slug)"
        >
          <input
            v-if="batchMode"
            type="checkbox"
            :checked="selectedSlugs.includes(page.slug)"
            :disabled="isProtectedPage(page)"
            class="page-checkbox"
            @click.stop="!isProtectedPage(page) && toggleSelect(page.slug)"
          />
          <div class="page-item-body">
            <div class="page-item-title">{{ page.title }}</div>
            <div class="page-item-meta">
              <span class="type-chip">{{ formatGroupLabel(page.pageType || 'other') }}</span>
              <span v-if="page.pageType === 'system'" class="page-flag page-flag--system">{{ t('wiki.systemPageBadge') }}</span>
              <span v-else-if="page.locked === 1" class="page-flag page-flag--locked">{{ t('wiki.lockedPageBadge') }}</span>
              <span class="meta-text">v{{ page.version }}</span>
            </div>
          </div>
        </div>
        <div v-if="filteredPages.length > searchPageLimit" class="pagination-row">
          <button class="load-more-btn" @click="searchPageLimit += PAGE_STEP">
            {{ t('wiki.loadMore', { n: Math.min(PAGE_STEP, filteredPages.length - searchPageLimit) }) }}
          </button>
        </div>
        <div v-if="filteredPages.length === 0" class="empty-hint">{{ t('wiki.noResults') }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore, isProtectedPage, type WikiPage } from '@/stores/useWikiStore'
import { wikiApi } from '@/api/index'

const emit = defineEmits<{ (e: 'open-page', slug: string): void }>()

const { t } = useI18n()
const store = useWikiStore()
const pageListEl = ref<HTMLElement | null>(null)

const PAGE_STEP = 20
const TYPE_ORDER = ['concept', 'technology', 'process', 'person', 'organization', 'product', 'place', 'event', 'term', 'other']

const pageSearch = ref('')
const searchPageLimit = ref(PAGE_STEP)
const groupPageLimit = reactive<Record<string, number>>({})
const collapsedGroups = reactive<Set<string>>(new Set())

const batchMode = ref(false)
const selectedSlugs = ref<string[]>([])

const archivedOpen = ref(false)
const archivedLoading = ref(false)
const archivedPages = ref<WikiPage[]>([])

const filteredPages = computed(() => {
  const q = pageSearch.value.toLowerCase()
  if (!q) return store.pages
  return store.pages.filter(
    (p) => p.title.toLowerCase().includes(q) || p.slug.toLowerCase().includes(q)
  )
})

const paginatedSearch = computed(() => filteredPages.value.slice(0, searchPageLimit.value))

const groupedPages = computed(() => {
  const map = new Map<string, typeof store.pages>()
  for (const page of store.pages) {
    const type = (page.pageType || 'other').toLowerCase()
    if (!map.has(type)) map.set(type, [])
    map.get(type)!.push(page)
  }
  return [...map.entries()]
    .sort(([a], [b]) => {
      const ia = TYPE_ORDER.indexOf(a) >= 0 ? TYPE_ORDER.indexOf(a) : 99
      const ib = TYPE_ORDER.indexOf(b) >= 0 ? TYPE_ORDER.indexOf(b) : 99
      return ia - ib
    })
    .map(([type, pages]) => ({ type, pages }))
})

const allSelected = computed(() =>
  filteredPages.value.length > 0 && selectedSlugs.value.length === filteredPages.value.length
)

watch(() => store.currentKB?.id, () => {
  searchPageLimit.value = PAGE_STEP
  Object.keys(groupPageLimit).forEach(k => delete groupPageLimit[k])
  collapsedGroups.clear()
  archivedOpen.value = false
  archivedPages.value = []
  pageSearch.value = ''
  exitBatchMode()
})

watch(() => pageSearch.value, () => { searchPageLimit.value = PAGE_STEP })

function toggleGroup(type: string) {
  if (collapsedGroups.has(type)) collapsedGroups.delete(type)
  else collapsedGroups.add(type)
}

function loadMoreGroup(type: string) {
  groupPageLimit[type] = (groupPageLimit[type] || PAGE_STEP) + PAGE_STEP
}

function paginatedGroupPages(group: { type: string; pages: any[] }) {
  const limit = groupPageLimit[group.type] || PAGE_STEP
  return group.pages.slice(0, limit)
}

function formatGroupLabel(type: string): string {
  if (!type) return t('wiki.pageTypes.other')
  const key = `wiki.pageTypes.${type.toLowerCase()}`
  const translated = t(key)
  return translated === key ? (type.charAt(0).toUpperCase() + type.slice(1)) : translated
}

function toggleSelect(slug: string) {
  const idx = selectedSlugs.value.indexOf(slug)
  if (idx >= 0) selectedSlugs.value.splice(idx, 1)
  else selectedSlugs.value.push(slug)
}

function toggleSelectAll() {
  if (allSelected.value) selectedSlugs.value = []
  else selectedSlugs.value = filteredPages.value.map(p => p.slug)
}

function exitBatchMode() {
  batchMode.value = false
  selectedSlugs.value = []
}

async function handleBatchDelete() {
  if (selectedSlugs.value.length === 0 || !store.currentKB) return
  const confirmed = confirm(t('wiki.confirmBatchDelete', { count: selectedSlugs.value.length }))
  if (!confirmed) return
  try {
    await wikiApi.batchDeletePages(store.currentKB.id, selectedSlugs.value)
    exitBatchMode()
    await store.fetchPages(store.currentKB.id)
  } catch (e: any) {
    alert(e?.message || 'Batch delete failed')
  }
}

async function toggleArchived() {
  archivedOpen.value = !archivedOpen.value
  if (archivedOpen.value && archivedPages.value.length === 0 && store.currentKB) {
    archivedLoading.value = true
    try {
      const res: any = await wikiApi.listArchivedPages(store.currentKB.id)
      archivedPages.value = (res?.data || res || []) as WikiPage[]
    } catch (e) {
      console.error('[Wiki] Failed to load archived pages', e)
      archivedPages.value = []
    } finally {
      archivedLoading.value = false
    }
  }
}

async function restoreArchivedPage(slug: string) {
  if (!store.currentKB) return
  try {
    await wikiApi.unarchivePage(store.currentKB.id, slug)
    archivedPages.value = archivedPages.value.filter(p => p.slug !== slug)
    await store.fetchPages(store.currentKB.id)
  } catch (e: any) {
    console.error('[Wiki] Unarchive failed', e)
    alert(e?.message || 'Unarchive failed')
  }
}

function openPage(slug: string) {
  emit('open-page', slug)
}

function onPageListScroll() {
  const el = pageListEl.value
  if (!el) return
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 60) {
    for (const group of groupedPages.value) {
      const limit = groupPageLimit[group.type] || PAGE_STEP
      if (limit < group.pages.length) {
        loadMoreGroup(group.type)
        break
      }
    }
  }
}
</script>

<style scoped>
.wiki-sidebar {
  width: 272px;
  min-width: 272px;
  overflow-y: auto;
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}

.sidebar-section { display: flex; flex-direction: column; gap: 8px; }
.sidebar-section--pages { flex: 1; min-height: 0; display: flex; flex-direction: column; gap: 6px; overflow: hidden; }

.sidebar-label {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--mc-text-tertiary);
  padding: 0 4px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.count-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 16px;
  padding: 0 5px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
  border-radius: 9999px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0;
}

.search-wrap {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 10px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  color: var(--mc-text-tertiary);
  transition: border-color 0.15s;
}
.search-wrap:focus-within { border-color: var(--mc-primary); }
.search-input { flex: 1; border: none; background: transparent; font-size: 12px; color: var(--mc-text-primary); outline: none; }
.search-input::placeholder { color: var(--mc-text-tertiary); }
.search-clear { border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); font-size: 11px; padding: 0; line-height: 1; }
.search-clear:hover { color: var(--mc-text-secondary); }

.sidebar-title-row { display: flex; justify-content: space-between; align-items: center; padding: 0 4px; }
.sidebar-title-actions { display: flex; gap: 4px; }
.icon-btn {
  width: 24px;
  height: 24px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  border-radius: 7px;
  cursor: pointer;
  color: var(--mc-text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  transition: all 0.15s;
}
.icon-btn:hover { background: var(--mc-bg-sunken); color: var(--mc-primary); }
.icon-btn--active { color: var(--mc-primary); border-color: var(--mc-primary); }

.page-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }

.page-group { display: flex; flex-direction: column; }
.group-header {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 6px;
  border: none;
  background: none;
  cursor: pointer;
  border-radius: 8px;
  transition: background 0.12s;
  width: 100%;
  text-align: left;
}
.group-header:hover { background: var(--mc-bg-muted); }
.group-chevron { color: var(--mc-text-tertiary); transition: transform 0.18s; flex-shrink: 0; }
.group-chevron.expanded { transform: rotate(90deg); }
.group-label { font-size: 11px; font-weight: 600; color: var(--mc-text-secondary); flex: 1; }
.group-count {
  font-size: 10px;
  padding: 1px 5px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
  border-radius: 9999px;
  font-variant-numeric: tabular-nums;
}
.group-items { display: flex; flex-direction: column; gap: 1px; padding-left: 12px; }

.page-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.12s;
  border: 1px solid transparent;
}
.page-item:hover { background: var(--mc-bg-muted); }
.page-item.active { background: var(--mc-primary-bg); border-color: rgba(217, 109, 70, 0.12); }
.page-item-body { flex: 1; min-width: 0; }
.page-item-title { font-size: 12px; color: var(--mc-text-primary); font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.page-item-meta { display: flex; align-items: center; gap: 4px; margin-top: 2px; }
.meta-text { font-size: 10px; color: var(--mc-text-tertiary); }
.type-chip { font-size: 10px; padding: 1px 5px; background: var(--mc-bg-sunken); border-radius: 4px; color: var(--mc-text-tertiary); }
.edit-dot { width: 5px; height: 5px; border-radius: 9999px; flex-shrink: 0; }
.edit-dot.manual { background: var(--mc-primary); }
.page-checkbox { flex-shrink: 0; cursor: pointer; }
.page-checkbox:disabled { cursor: not-allowed; opacity: 0.4; }

.page-flag { font-size: 10px; padding: 1px 6px; border-radius: 99px; font-weight: 600; }
.page-flag--system { background: var(--mc-primary-bg); color: var(--mc-primary); border: 1px solid var(--mc-primary); }
.page-flag--locked { background: var(--mc-bg-elevated); color: var(--mc-text-secondary); border: 1px solid var(--mc-border-light); }

.page-item--system { border-left: 2px solid var(--mc-primary); padding-left: 6px; }

.archived-section { margin-top: 12px; border-top: 1px dashed var(--mc-border-light); padding-top: 8px; }
.archived-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 4px;
  background: none;
  border: none;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  text-align: left;
}
.archived-toggle:hover { color: var(--mc-text-secondary); }
.archived-toggle:disabled { cursor: wait; opacity: 0.5; }
.archived-chevron { transition: transform 0.15s; flex-shrink: 0; }
.archived-chevron.expanded { transform: rotate(90deg); }
.archived-count {
  margin-left: auto;
  padding: 0 6px;
  background: var(--mc-bg-sunken);
  border-radius: 99px;
  font-weight: 600;
  font-size: 10px;
}

.archived-items { display: flex; flex-direction: column; gap: 2px; padding-top: 4px; }
.archived-empty { padding: 6px 4px; font-size: 11px; font-style: italic; color: var(--mc-text-tertiary); }
.archived-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  opacity: 0.7;
  transition: opacity 0.1s, background 0.1s;
}
.archived-item:hover { opacity: 1; background: var(--mc-bg-muted); }
.archived-item-body { flex: 1; min-width: 0; }
.archived-item-title { font-size: 12px; font-weight: 500; color: var(--mc-text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.archived-item-meta { font-size: 10px; color: var(--mc-text-tertiary); margin-top: 1px; }

.archived-restore-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border: 1px solid var(--mc-border-light);
  border-radius: 6px;
  background: none;
  color: var(--mc-text-secondary);
  font-size: 11px;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.15s;
}
.archived-restore-btn:hover { color: var(--mc-primary); border-color: var(--mc-primary); background: var(--mc-primary-bg); }

.load-more-btn {
  width: 100%;
  padding: 6px;
  border: 1px dashed var(--mc-border-light);
  background: none;
  border-radius: 8px;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  transition: all 0.15s;
  margin-top: 2px;
}
.load-more-btn:hover { border-color: var(--mc-primary); color: var(--mc-primary); background: var(--mc-primary-bg); }

.pagination-row { padding: 4px 0; }

.batch-bar { display: flex; justify-content: space-between; align-items: center; padding: 5px 8px; background: var(--mc-bg-muted); border-radius: 8px; }
.batch-check-all { display: flex; align-items: center; gap: 5px; font-size: 11px; color: var(--mc-text-secondary); cursor: pointer; }
.batch-check-all input { cursor: pointer; }
.batch-delete-btn { padding: 3px 10px; border: none; border-radius: 6px; font-size: 11px; font-weight: 600; cursor: pointer; background: rgba(245, 108, 108, 0.1); color: var(--el-color-danger, #f56c6c); }
.batch-delete-btn:hover:not(:disabled) { background: rgba(245, 108, 108, 0.2); }
.batch-delete-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.empty-hint { font-size: 13px; color: var(--mc-text-tertiary); padding: 12px 4px; }

.filter-banner {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  background: var(--mc-primary-bg);
  border: 1px solid rgba(217,109,70,0.2);
  border-radius: 8px;
  font-size: 11px;
  color: var(--mc-primary);
  font-weight: 500;
}
.filter-clear-btn {
  margin-left: auto;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-primary);
  font-size: 11px;
  padding: 0 2px;
  opacity: 0.7;
  line-height: 1;
}
.filter-clear-btn:hover { opacity: 1; }

@media (max-width: 980px) {
  .wiki-sidebar { width: 100%; min-width: 0; max-height: 320px; }
}
</style>
