<template>
  <div class="wiki-library">
    <div class="mc-page-header">
      <div>
        <div class="mc-page-kicker">{{ t('wiki.kicker') }}</div>
        <h1 class="mc-page-title">{{ t('nav.wiki') }}</h1>
        <p class="mc-page-desc">{{ t('wiki.desc') }}</p>
      </div>
      <button v-if="canManageWiki" class="btn-primary page-cta" @click="$emit('create')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        {{ t('wiki.createKB') }}
      </button>
    </div>

    <div class="library-toolbar mc-surface-card">
      <div class="library-count">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
        {{ t('wiki.library.countLabel', { count: filtered.length }) }}
      </div>
      <div class="library-search">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input
          v-model="searchInput"
          type="text"
          :placeholder="t('wiki.library.searchPlaceholder')"
          class="library-search-input"
        />
        <button v-if="searchInput" class="library-search-clear" @click="searchInput = ''">✕</button>
      </div>
      <div class="library-sort">
        <select v-model="sortInput" class="library-sort-select">
          <option value="recent">{{ t('wiki.library.sortRecent') }}</option>
          <option value="name">{{ t('wiki.library.sortName') }}</option>
          <option value="pages">{{ t('wiki.library.sortPages') }}</option>
        </select>
      </div>
    </div>

    <div v-if="loading" class="library-message">{{ t('common.loading') }}</div>

    <div v-else-if="kbs.length === 0" class="library-empty mc-surface-card">
      <div class="library-empty-icon">
        <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2">
          <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>
        </svg>
      </div>
      <h3 class="library-empty-title">{{ t('wiki.library.empty') }}</h3>
      <p class="library-empty-hint">{{ t('wiki.library.emptyHint') }}</p>
      <button v-if="canManageWiki" class="btn-primary" @click="$emit('create')">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        {{ t('wiki.createKB') }}
      </button>
    </div>

    <div v-else-if="filtered.length === 0" class="library-message">
      {{ t('wiki.library.noMatch') }}
    </div>

    <template v-else>
      <div class="kb-grid">
        <WikiKBCard
          v-for="kb in pageItems"
          :key="kb.id"
          :kb="kb"
          :failed-job-count="kbStats[kb.id]?.failedJobCount || 0"
          @open="$emit('open', $event)"
          @delete="$emit('delete', $event)"
        />
      </div>

      <div class="library-pager">
        <McPagination
          v-model:page="page"
          v-model:size="size"
          :total="filtered.length"
          :sizes="[12, 24, 48]"
          hide-on-single-page
        />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { WikiKB } from '@/stores/useWikiStore'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import McPagination from '@/components/common/McPagination.vue'
import WikiKBCard from './WikiKBCard.vue'

interface KBStats {
  failedJobCount: number
  [k: string]: any
}

const props = defineProps<{
  kbs: WikiKB[]
  kbStats: Record<number, KBStats>
  loading: boolean
}>()

defineEmits<{
  (e: 'open', id: number): void
  (e: 'create'): void
  (e: 'delete', kb: WikiKB): void
}>()

const { t } = useI18n()
const workspace = useWorkspaceStore()

// view:wiki users without manage:wiki may browse but not create knowledge bases.
const canManageWiki = computed(() => workspace.can('manage:wiki'))

const searchInput = ref('')
type SortMode = 'recent' | 'name' | 'pages'
const sortInput = ref<SortMode>('recent')

const page = ref(1)
const size = ref(12)

const filtered = computed<WikiKB[]>(() => {
  const q = searchInput.value.trim().toLowerCase()
  let list = props.kbs.slice()
  if (q) {
    list = list.filter(kb =>
      kb.name.toLowerCase().includes(q) ||
      (kb.description || '').toLowerCase().includes(q)
    )
  }
  if (sortInput.value === 'name') {
    list.sort((a, b) => a.name.localeCompare(b.name))
  } else if (sortInput.value === 'pages') {
    list.sort((a, b) => (b.pageCount || 0) - (a.pageCount || 0))
  } else {
    list.sort((a, b) => {
      const ta = a.updateTime ? new Date(a.updateTime).getTime() : 0
      const tb = b.updateTime ? new Date(b.updateTime).getTime() : 0
      return tb - ta
    })
  }
  return list
})

// Reset to page 1 whenever the filtered/sorted set changes shape — otherwise
// you can land on an out-of-range page after a search.
watch([searchInput, sortInput], () => { page.value = 1 })
watch(() => props.kbs.length, () => { page.value = 1 })

const pageItems = computed(() => {
  const start = (page.value - 1) * size.value
  return filtered.value.slice(start, start + size.value)
})
</script>

<style scoped>
.wiki-library { display: flex; flex-direction: column; }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: var(--mc-shadow-soft); transition: opacity 0.15s; }
.btn-primary:hover { opacity: 0.9; }

.library-toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 14px;
  margin-bottom: 18px;
}
.library-count {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  padding-right: 14px;
  border-right: 1px solid var(--mc-border-light);
}
.library-search {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  color: var(--mc-text-tertiary);
  transition: border-color 0.15s;
  max-width: 360px;
}
.library-search:focus-within { border-color: var(--mc-primary); }
.library-search-input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 13px;
  color: var(--mc-text-primary);
  outline: none;
  min-width: 0;
}
.library-search-input::placeholder { color: var(--mc-text-tertiary); }
.library-search-clear { border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); font-size: 12px; padding: 0; line-height: 1; }
.library-search-clear:hover { color: var(--mc-text-secondary); }

.library-sort { margin-left: auto; }
.library-sort-select {
  padding: 7px 12px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  outline: none;
  transition: border-color 0.15s;
}
.library-sort-select:focus { border-color: var(--mc-primary); }

.library-message {
  text-align: center;
  padding: 48px 16px;
  color: var(--mc-text-tertiary);
  font-size: 14px;
}

.library-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 60px 20px;
  gap: 14px;
}
.library-empty-icon { color: var(--mc-text-tertiary); opacity: 0.6; }
.library-empty-title { font-size: 18px; font-weight: 700; color: var(--mc-text-primary); margin: 0; }
.library-empty-hint { font-size: 13px; color: var(--mc-text-tertiary); margin: 0 0 8px; max-width: 380px; line-height: 1.5; }

.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  padding-bottom: 4px;
}

.library-pager {
  margin-top: 18px;
  display: flex;
  justify-content: center;
}

@media (max-width: 980px) {
  .library-toolbar { flex-wrap: wrap; }
  .library-search { max-width: 100%; }
  .library-sort { margin-left: 0; }
}
</style>
