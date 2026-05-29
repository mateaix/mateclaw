<template>
  <div class="page-viewer" v-if="store.currentPage">
    <!-- RFC-033: Enhanced header with page_type, enrichment status, citations -->
    <PageHeader
      :page="store.currentPage"
      @view-citations="citationDrawerOpen = true"
      @enrich="handleEnrich"
    />

    <!-- Summary Card -->
    <div v-if="store.currentPage.summary && !editing" class="page-summary">
      <div class="summary-label">Summary</div>
      <p class="summary-text">{{ store.currentPage.summary }}</p>
    </div>

    <!-- Actions bar — write actions, hidden for read-only viewers -->
    <div v-if="canManageWiki" class="page-actions-bar">
      <button class="btn-secondary btn-sm" @click="editing = !editing">
        {{ editing ? t('common.cancel') : t('common.edit') }}
      </button>
      <button v-if="editing" class="btn-primary btn-sm" @click="saveEdit">
        {{ t('common.save') }}
      </button>
      <button v-if="!editing" class="btn-secondary btn-sm btn-action" @click="handleEnrich">
        <el-icon><Link /></el-icon>
        {{ t('wiki.page.enrich') }}
      </button>
      <button v-if="!editing" class="btn-secondary btn-sm btn-action" @click="handleRepair">
        <el-icon><SetUp /></el-icon>
        {{ t('wiki.page.repair') }}
      </button>
      <!-- RFC-051 PR-8: hide delete on protected pages (system / locked). -->
      <button
        v-if="!editing && !isProtected"
        class="btn-secondary btn-sm btn-delete"
        @click="handleDelete"
      >
        {{ t('common.delete') }}
      </button>
      <span v-if="!editing && isSystem" class="system-badge" :title="t('wiki.systemPageHint')">
        {{ t('wiki.systemPageBadge') }}
      </span>
      <span v-if="!editing && isLockedNotSystem" class="locked-badge" :title="t('wiki.lockedPageHint')">
        {{ t('wiki.lockedPageBadge') }}
      </span>
    </div>

    <!-- Content -->
    <article
      v-if="!editing"
      ref="articleRef"
      class="page-content markdown-body"
      v-html="renderedContent"
    ></article>
    <textarea v-else v-model="editContent" class="page-editor" rows="30"></textarea>

    <!-- Click-to-zoom overlay for inline images. Bound after each render via attach(). -->
    <ImageLightbox ref="lightboxRef" />

    <!-- RFC-033: Related Pages Panel (replaces backlinks) -->
    <RelatedPagesPanel
      v-if="!editing && store.currentKB"
      :kb-id="store.currentKB.id"
      :slug="store.currentPage.slug"
      @navigate="openPage"
    />

    <!-- Legacy backlinks (kept as fallback) -->
    <div v-if="!editing && backlinks.length > 0" class="backlinks-section">
      <h4 class="backlinks-title">{{ t('wiki.backlinks') }} ({{ backlinks.length }})</h4>
      <div class="backlinks-list">
        <span
          v-for="bl in backlinks" :key="bl.slug"
          class="backlink-tag"
          @click="openPage(bl.slug)"
        >
          {{ bl.title }}
        </span>
      </div>
    </div>

    <!-- RFC-033: Citation drawer -->
    <CitationDrawer
      v-if="store.currentKB"
      v-model="citationDrawerOpen"
      :page-id="store.currentPage.id"
      :kb-id="store.currentKB.id"
    />

    <!-- Enrich toast -->
    <div v-if="enrichToast" class="enrich-toast">
      ✦ {{ enrichToast }}
      <button class="toast-dismiss" @click="enrichToast = ''">✕</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore, isProtectedPage, type WikiPage } from '@/stores/useWikiStore'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { wikiApi } from '@/api/index'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import { postprocessWikilinks, resolveWikilink, type WikilinkRef } from '@/composables/wikilink'
import { Link, SetUp } from '@element-plus/icons-vue'
import PageHeader from './PageHeader.vue'
import RelatedPagesPanel from './RelatedPagesPanel.vue'
import CitationDrawer from './CitationDrawer.vue'
import ImageLightbox from './ImageLightbox.vue'

const { t } = useI18n()
const store = useWikiStore()
const workspace = useWorkspaceStore()
const { renderMarkdown } = useMarkdownRenderer()

// Editing, enriching, repairing and deleting pages all require manage:wiki.
// Read-only viewers get the rendered page without the action bar.
const canManageWiki = computed(() => workspace.can('manage:wiki'))

const editing = ref(false)
const editContent = ref('')
const backlinks = ref<WikiPage[]>([])
const citationDrawerOpen = ref(false)
const enrichToast = ref('')

// Refs for the lightbox post-render binding step.
const articleRef = ref<HTMLElement | null>(null)
const lightboxRef = ref<{ attach: (el: HTMLElement | null) => void } | null>(null)

// RFC-051 PR-8: protection state for delete-button gating + badge rendering.
const isSystem = computed(() => store.currentPage?.pageType === 'system')
const isProtected = computed(() => isProtectedPage(store.currentPage))
const isLockedNotSystem = computed(() => isProtected.value && !isSystem.value)

// Render the markdown WITHOUT the renderer's built-in wikilink substitution.
// `wikilink: 'none'` keeps the raw `[[...]]` tokens intact so the DOM
// postprocess below can resolve them against the authoritative pageRefs
// index rather than against `store.pages` (which is filtered by rawId and
// would silently break cross-material links). See RFC 55 §1.3 / Phase 1.
const renderedContent = computed(() => {
  if (!store.currentPage?.content) return ''
  return renderMarkdown(store.currentPage.content, { wikilink: 'none' })
})

// Project the store's pageRefs into the resolver's lightweight shape. Pulling
// `archived: false` explicitly (the store's WikiPageRef already carries it,
// but the active list is guaranteed non-archived by the backend filter) keeps
// the resolver's TypeScript types simple.
const activeRefs = computed<WikilinkRef[]>(() =>
  store.pageRefs.map((p) => ({ slug: p.slug, title: p.title, archived: false })),
)
const archivedRefs = computed<WikilinkRef[]>(() =>
  store.archivedPageRefs.map((p) => ({ slug: p.slug, title: p.title, archived: true })),
)

// Postprocess wikilinks after v-html settles. Runs on every content swap and
// also whenever the resolution index changes (e.g. user navigated away, a
// page was created in the background, archived refs finally loaded), so links
// that started life as `wiki-link-broken` upgrade to active hits on a re-walk.
async function runWikilinkPostprocess() {
  await nextTick()
  const root = articleRef.value
  if (!root) return
  const refs = activeRefs.value
  const archived = archivedRefs.value
  postprocessWikilinks(root, (raw) => resolveWikilink(raw, refs, archived))
}

// Bind the image lightbox to the rendered article on every content swap.
// Awaits a microtask so v-html has a chance to repopulate the DOM, then
// asks the lightbox to walk <img> tags and attach click handlers. Already-
// bound elements are skipped by the lightbox itself.
watch(renderedContent, async () => {
  await runWikilinkPostprocess()
  lightboxRef.value?.attach(articleRef.value)
})

// Re-run wikilink resolution when the refs index changes — a sibling page
// created or restored from archive should upgrade existing broken spans on
// the open page to active links without forcing the user to re-navigate.
watch([activeRefs, archivedRefs], async () => {
  await runWikilinkPostprocess()
})

watch(() => store.currentPage, async (page) => {
  if (page && store.currentKB) {
    editing.value = false
    editContent.value = page.content || ''
    try {
      const res: any = await wikiApi.getBacklinks(store.currentKB.id, page.slug)
      backlinks.value = res.data || []
    } catch {
      backlinks.value = []
    }
  }
}, { immediate: true })

async function saveEdit() {
  if (!store.currentKB || !store.currentPage) return
  await wikiApi.updatePage(store.currentKB.id, store.currentPage.slug, editContent.value)
  await store.loadPage(store.currentKB.id, store.currentPage.slug)
  editing.value = false
}

async function handleDelete() {
  if (!store.currentKB || !store.currentPage) return
  // Surface the referrer count before deletion so the user knows how many
  // pages will be unlinked in cascade. backlinks already loads on page
  // open (see the currentPage watcher), so this read is local — no extra
  // round-trip. The cascade runs on the backend regardless of UI prompt
  // wording; this is purely advisory.
  const refCount = backlinks.value.length
  const baseMessage = t('wiki.confirmDelete', { title: store.currentPage.title })
  const withRefs = refCount > 0
    ? `${baseMessage}\n\n${t('wiki.confirmDeleteRefs', { count: refCount })}`
    : baseMessage
  const confirmed = confirm(withRefs)
  if (!confirmed) return
  try {
    await wikiApi.deletePage(store.currentKB.id, store.currentPage.slug)
    store.currentPage = null
    // Keep the active raw-material filter so the list doesn't jump to all pages.
    // Refresh pageRefs + broken-link report too — both can change after a
    // delete because cascade-rewrite may upgrade or downgrade other pages'
    // resolution states.
    await Promise.all([
      store.fetchPages(store.currentKB.id, store.selectedRawId),
      store.fetchPageRefs(store.currentKB.id),
      store.loadBrokenLinksReport(store.currentKB.id),
    ])
  } catch (e: any) {
    alert(e?.message || 'Delete failed')
  }
}

async function handleEnrich() {
  if (!store.currentKB || !store.currentPage) return
  try {
    await wikiApi.enrichPage(store.currentKB.id, store.currentPage.slug)
    enrichToast.value = t('wiki.page.enrich') + '…'
    setTimeout(() => { enrichToast.value = '' }, 5000)
  } catch (e: any) {
    console.error('[WikiViewer] Enrich failed:', e)
  }
}

async function handleRepair() {
  if (!store.currentKB || !store.currentPage) return
  try {
    await wikiApi.repairPage(store.currentKB.id, store.currentPage.slug)
    enrichToast.value = t('wiki.page.repair') + '…'
    setTimeout(async () => {
      enrichToast.value = ''
      if (store.currentKB && store.currentPage) {
        await store.loadPage(store.currentKB.id, store.currentPage.slug)
      }
    }, 5000)
  } catch (e: any) {
    console.error('[WikiViewer] Repair failed:', e)
  }
}

async function openPage(slug: string) {
  if (!store.currentKB) return
  await store.loadPage(store.currentKB.id, slug)
}

onMounted(() => {
  // Lazily load archived refs once per KB so the postprocess can label any
  // existing links to archived targets as such instead of treating them as
  // broken. The store dedupes repeated calls, so this is cheap on revisits.
  if (store.currentKB) {
    store.fetchArchivedPageRefs(store.currentKB.id)
  }
  // Global click delegation — both `wiki-link` and `wiki-link wiki-link-archived`
  // share the same data-slug contract and the same routing through openPage.
  // `wiki-link-broken` lacks the class entirely so its clicks are no-ops.
  document.addEventListener('click', (e) => {
    const target = e.target as HTMLElement
    if (target.classList.contains('wiki-link')) {
      const slug = target.dataset.slug
      if (slug) openPage(slug)
    }
  })
})
</script>

<style scoped>
.page-viewer {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.btn-primary { display: flex; align-items: center; gap: 6px; padding: 8px 16px; background: var(--mc-primary); color: white; border: none; border-radius: 10px; font-size: 14px; font-weight: 500; cursor: pointer; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary.btn-sm { padding: 6px 14px; font-size: 13px; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-secondary.btn-sm { padding: 6px 14px; font-size: 13px; }
.btn-secondary.btn-delete { color: var(--el-color-danger, #f56c6c); }
.btn-secondary.btn-delete:hover { background: var(--el-color-danger-light-9, #fef0f0); border-color: var(--el-color-danger-light-5, #fab6b6); }
.btn-secondary.btn-action { color: var(--mc-primary); }

.page-actions-bar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }

/* RFC-051 PR-8: protection badges shown next to action buttons. */
.system-badge,
.locked-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: 99px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
  user-select: none;
}
.system-badge {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  border: 1px solid var(--mc-primary);
}
.locked-badge {
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  border: 1px solid var(--mc-border-light);
}

/* Summary */
.page-summary { padding: 16px 20px; background: var(--mc-bg-muted); border-radius: 12px; border-left: 3px solid var(--mc-primary); }
.summary-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.1em; color: var(--mc-text-secondary); margin-bottom: 6px; }
.summary-text { font-size: 14px; color: var(--mc-text-primary); line-height: 1.7; margin: 0; }

/* Content */
.page-content { font-size: 15px; line-height: 1.8; color: var(--mc-text-primary); padding: 2px 2px 0; }
.page-content :deep(p) { margin: 0 0 1.05em; }
.page-content :deep(h1) { font-size: 26px; font-weight: 700; margin: 28px 0 14px; color: var(--mc-text-primary); letter-spacing: -0.03em; }
.page-content :deep(h2) { font-size: 21px; font-weight: 700; margin: 24px 0 10px; color: var(--mc-text-primary); letter-spacing: -0.025em; }
.page-content :deep(h3) { font-size: 18px; font-weight: 700; margin: 20px 0 8px; color: var(--mc-text-primary); }
.page-content :deep(li) { margin-left: 24px; list-style: disc; }
.page-content :deep(code) { background: var(--mc-bg-sunken); padding: 2px 6px; border-radius: 6px; font-size: 0.85em; }
.page-content :deep(pre code) { background: none; padding: 0; }
.page-content :deep(blockquote) { border-left: 3px solid var(--mc-primary); padding: 8px 16px; margin: 12px 0; color: var(--mc-text-secondary); background: var(--mc-bg-muted); border-radius: 0 10px 10px 0; }
.page-content :deep(table) { width: 100%; border-collapse: collapse; margin: 12px 0; font-size: 14px; }
.page-content :deep(th), .page-content :deep(td) { padding: 8px 12px; border: 1px solid var(--mc-border-light); text-align: left; }
.page-content :deep(th) { background: var(--mc-bg-sunken); font-weight: 600; }
.page-content :deep(hr) { border: none; border-top: 1px solid var(--mc-border-light); margin: 20px 0; }
.page-content :deep(img) { max-width: 100%; border-radius: 10px; }
.page-content :deep(.wiki-link) { color: var(--mc-primary); text-decoration: none; cursor: pointer; border-bottom: 1px dashed var(--mc-primary); }
.page-content :deep(.wiki-link:hover) { text-decoration: underline; }
/* Archived target — still clickable to view/restore, but visually de-emphasised
   to match the archived state semantics from elsewhere in the wiki UI. */
.page-content :deep(.wiki-link.wiki-link-archived) {
  color: var(--mc-text-tertiary);
  border-bottom-color: var(--mc-text-tertiary);
  border-bottom-style: dotted;
  font-style: italic;
}
.page-content :deep(.wiki-link.wiki-link-archived:hover) { color: var(--mc-text-secondary); }
/* Broken target — no click, no href, no request. Dashed underline + muted tone
   tells the reader the wikilink couldn't be resolved without committing to
   navigation that would 404. Tooltip shows the rejection reason. */
.page-content :deep(.wiki-link-broken) {
  color: var(--mc-text-tertiary);
  text-decoration: underline dashed var(--mc-text-tertiary);
  text-underline-offset: 3px;
  cursor: help;
}

/* Editor */
.page-editor { width: 100%; min-height: 60vh; padding: 16px; border: 1px solid var(--mc-border); border-radius: 14px; font-family: 'JetBrains Mono', monospace; font-size: 14px; line-height: 1.65; resize: vertical; background: var(--mc-bg-elevated); color: var(--mc-text-primary); outline: none; }
.page-editor:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }

/* Backlinks */
.backlinks-section { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--mc-border); }
.backlinks-title { font-size: 12px; font-weight: 600; text-transform: uppercase; color: var(--mc-text-secondary); margin-bottom: 8px; letter-spacing: 0.05em; }
.backlinks-list { display: flex; flex-wrap: wrap; gap: 6px; }
.backlink-tag { padding: 5px 10px; background: var(--mc-bg-sunken); border-radius: 9999px; font-size: 12px; cursor: pointer; color: var(--mc-primary); transition: background 0.15s; }
.backlink-tag:hover { background: var(--mc-primary-bg); }

/* Enrich toast */
.enrich-toast {
  position: fixed;
  bottom: 24px;
  right: 24px;
  padding: 10px 16px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-primary);
  border-radius: 12px;
  font-size: 13px;
  color: var(--mc-primary);
  box-shadow: 0 8px 24px rgba(0,0,0,0.12);
  display: flex;
  align-items: center;
  gap: 10px;
  z-index: 999;
  animation: toast-in 0.3s ease;
}
@keyframes toast-in {
  from { transform: translateY(20px); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
}
.toast-dismiss {
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  font-size: 14px;
}

@media (max-width: 768px) {
  .page-actions-bar { flex-direction: column; }
  .page-actions-bar .btn-secondary,
  .page-actions-bar .btn-primary { width: 100%; justify-content: center; }
  .page-editor { min-height: 46vh; }
}
</style>
