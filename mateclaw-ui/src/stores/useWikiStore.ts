import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref } from 'vue'
import { wikiApi } from '@/api/index'

export interface WikiKB {
  id: number
  name: string
  description: string
  agentId: number | null
  configContent: string
  sourceDirectory: string | null
  status: string
  pageCount: number
  rawCount: number
  createTime: string
  updateTime: string
}

export interface WikiRawMaterial {
  id: number
  kbId: number
  title: string
  sourceType: string
  fileSize: number
  processingStatus: string
  lastProcessedAt: string | null
  errorMessage: string | null
  // Structured failure code (AUTH_ERROR / BILLING / MODEL_NOT_FOUND / RATE_LIMIT /
  // TIMEOUT / SERVER_ERROR / CONTENT_FILTER / NO_CONTENT / EMPTY_RESULT / UNKNOWN);
  // drives the localized friendly hint. null when there is no error.
  errorCode: string | null
  // Non-blocking warning: the material processed but an async sub-step
  // (embedding / entity extraction) failed, degrading it. null when clean.
  warningCode: string | null
  warningMessage: string | null
  createTime: string
  // Two-stage ingestion progress: backend writes total after routing and
  // increments done as each generated page finishes.
  progressPhase: string | null
  progressTotal: number
  progressDone: number
  // Page count derived from sourceRawIds (injected by listRaw endpoint)
  pageCount?: number
  // Source group this raw belongs to; null = ungrouped (legacy/manual raw).
  // Snowflake ID — backend serializes Long as string (ToStringSerializer), so
  // this arrives as a string at runtime despite the numeric-looking type.
  groupId: number | string | null
  // Path/glob this raw was scanned from, when it came from a source group.
  sourcePath?: string | null
}

/** A configured scan source: one path/glob under a KB, with its own alias and schedule. */
export interface WikiSourceGroup {
  // Snowflake IDs — backend serializes Long as string; keep the union so
  // v-model/select values round-trip without precision-losing Number() coercion.
  id: number | string
  kbId: number | string
  alias: string
  path: string
  fileFilter: string | null
  cronExpr: string | null
  enabled: number
  rawCount: number
  lastScanAt: string | null
}

export interface WikiPage {
  id: number
  kbId: number
  slug: string
  title: string
  content: string | null
  summary: string
  outgoingLinks: string
  sourceRawIds: string
  version: number
  lastUpdatedBy: string
  pageType?: string | null
  // locked=1 blocks AI/tool/UI deletion. System pages are locked by default,
  // but users can lock any page.
  locked?: number | null
  // archived=1 hides the page from default list/search/related.
  archived?: number | null
  createTime: string
  updateTime: string
}

/** Shared protection check used by viewer and list to gate delete UI. */
export function isProtectedPage(page: WikiPage | null | undefined): boolean {
  if (!page) return false
  if (page.pageType === 'system') return true
  return page.locked === 1
}

/**
 * Lightweight {slug, title, archived} entry used to resolve `[[...]]` wikilinks
 * in rendered wiki content. Distinct from {@link WikiPage} — pageRefs are never
 * filtered by the user's raw-material selection and never carry content, so the
 * renderer can always trust them as the authoritative resolution index for the
 * active knowledge base.
 */
export interface WikiPageRef {
  slug: string
  title: string
  archived: boolean
}

/**
 * Parsed view of a KB's pageType profile, derived from the GET
 * page-type-profile endpoint's `config` JSON. `order` preserves the profile's
 * declared pageType ordering (the JSON object key order); `labels` maps each
 * pageType to its display label. Consumed by the sidebar grouping, graph
 * colouring and the transformation editor's target-type dropdown so all of
 * them follow the KB's own classification instead of a hard-coded list.
 */
export interface WikiPageTypeProfile {
  fallbackType: string
  order: string[]
  labels: Record<string, string>
}

/** Per-page row in a broken-links report. */
export interface WikiBrokenLinkPage {
  // Snowflake — stay as string end-to-end.
  pageId: string
  slug: string
  title: string
  brokenRefs: string[]
}

/** Aggregate response from GET /lint/broken-links. */
export interface WikiBrokenLinksReport {
  kbId: number | string
  jobId: string | null
  completedAt: string | null
  totalPages: number
  pagesWithBrokenLinks: number
  totalBrokenRefs: number
  pages: WikiBrokenLinkPage[]
}

/** Job envelope returned by POST /lint/broken-links. */
export interface WikiLintJob {
  jobId: string
  kbId: number | string
  status: 'queued' | 'running' | 'completed' | 'failed'
  startedAt: string
  completedAt: string | null
  totalPages: number
  pagesWithBrokenLinks: number
  totalBrokenRefs: number
  errorMessage?: string
}

export const useWikiStore = defineStore('wiki', () => {
  const knowledgeBases = ref<WikiKB[]>([])
  const currentKB = ref<WikiKB | null>(null)
  // Which face of an opened KB to show: 'browse' = reading surfaces (pages +
  // graph), 'manage' = build/config surfaces (raw materials, config,
  // transformations, advanced, hot cache). Both views share the data loaded by
  // selectKB, so switching between them is a flag flip with no refetch.
  const workspaceMode = ref<'browse' | 'manage'>('browse')
  const rawMaterials = ref<WikiRawMaterial[]>([])
  const sourceGroups = ref<WikiSourceGroup[]>([])
  const pages = ref<WikiPage[]>([])
  const currentPage = ref<WikiPage | null>(null)
  const loading = ref(false)

  // Raw material filter state
  const selectedRawId = ref<number | null>(null)
  const totalPageCount = ref(0)

  // Active KB's pageType profile (parsed). Loaded alongside the KB so sidebar
  // grouping, graph colouring and the transformation editor can render the
  // KB's own classification. Null until a KB is selected / its profile loads.
  const pageTypeProfile = ref<WikiPageTypeProfile | null>(null)

  // Wikilink resolution index — kept separate from `pages` because (a) it must
  // survive the raw-material filter, and (b) the viewer's postprocess needs an
  // O(1) slug/title lookup over the full KB. `archivedPageRefs` is only loaded
  // on demand: most pages don't reference archived targets, and asking for them
  // by default would let archived slugs leak into the active resolution map.
  const pageRefs = ref<WikiPageRef[]>([])
  const archivedPageRefs = ref<WikiPageRef[]>([])

  // Broken-link lint state. `brokenLinksReport` holds the latest aggregate
  // server response; `brokenLinksJob` tracks the in-flight scan job (null
  // when nothing is running or after the last scan settled). Both are
  // scoped to currentKB — clear in selectKB / backToLibrary so KB switching
  // doesn't bleed stale data across knowledge bases.
  const brokenLinksReport = ref<WikiBrokenLinksReport | null>(null)
  const brokenLinksJob = ref<WikiLintJob | null>(null)
  const brokenLinksLoading = ref(false)
  // Track the timer id so a second startBrokenLinksScan call cancels the
  // stale poller — avoids double-fires after rapid clicks.
  let brokenLinksPollTimer: ReturnType<typeof setInterval> | null = null

  async function fetchKnowledgeBases() {
    loading.value = true
    try {
      const res: any = await wikiApi.listKBs()
      const next: WikiKB[] = res.data || []
      // 工作区切换时清理上一个工作区的 KB 上下文，避免显示其内容。
      // backToLibrary 已清理 pages/pageRefs/rawMaterials 等关联状态，这里复用。
      if (currentKB.value && !next.some(kb => kb.id === currentKB.value!.id)) {
        backToLibrary()
      }
      knowledgeBases.value = next
    } catch (e) {
      console.error('Failed to fetch knowledge bases', e)
    } finally {
      loading.value = false
    }
  }

  async function selectKB(id: number, mode: 'browse' | 'manage' = 'browse') {
    workspaceMode.value = mode
    const res: any = await wikiApi.getKB(id)
    currentKB.value = res.data || res
    // pageRefs refresh in parallel with materials + pages — the viewer needs
    // the resolution index ready before it tries to postprocess wikilinks.
    archivedPageRefs.value = []
    // Clear stale broken-links state from the previous KB; the report fetch
    // below repopulates it (or leaves it null if no scan has run on this KB).
    brokenLinksReport.value = null
    brokenLinksJob.value = null
    if (brokenLinksPollTimer) { clearInterval(brokenLinksPollTimer); brokenLinksPollTimer = null }
    await Promise.all([
      fetchRawMaterials(id),
      fetchSourceGroups(id),
      fetchPages(id),
      fetchPageRefs(id),
      loadBrokenLinksReport(id),
      loadPageTypeProfile(id),
    ])
  }

  /**
   * Load + parse the KB's pageType profile into {@link pageTypeProfile}. The
   * endpoint returns `config` as a JSON string (built-in default when the KB
   * has no custom profile); we extract the declared type order, labels and
   * fallbackType. Failures degrade to a null profile — consumers then fall
   * back to i18n labels / hash colouring rather than breaking the view.
   */
  async function loadPageTypeProfile(kbId: number) {
    try {
      const res: any = await wikiApi.getPageTypeProfile(kbId)
      const payload = res.data ?? res
      const cfg = JSON.parse(payload.config || '{}')
      const types = cfg.pageTypes && typeof cfg.pageTypes === 'object' ? cfg.pageTypes : {}
      const order = Object.keys(types)
      const labels: Record<string, string> = {}
      for (const key of order) {
        const def = types[key]
        labels[key] = (def && typeof def.label === 'string' && def.label) || key
      }
      pageTypeProfile.value = {
        fallbackType: typeof cfg.fallbackType === 'string' ? cfg.fallbackType : 'concept',
        order,
        labels,
      }
    } catch (e) {
      console.error('[Wiki] Failed to load pageType profile', e)
      pageTypeProfile.value = null
    }
  }

  async function createKB(data: { name: string; description?: string; agentId?: number }) {
    const res: any = await wikiApi.createKB(data)
    const kb = res.data || res
    knowledgeBases.value.unshift(kb)
    return kb
  }

  async function deleteKB(id: number) {
    await wikiApi.deleteKB(id)
    knowledgeBases.value = knowledgeBases.value.filter((kb) => kb.id !== id)
    if (currentKB.value?.id === id) {
      currentKB.value = null
      rawMaterials.value = []
      sourceGroups.value = []
      pages.value = []
    }
  }

  // Switch between reading/management faces of an already-open KB without
  // refetching — both share the data selectKB already loaded.
  function setWorkspaceMode(mode: 'browse' | 'manage') {
    workspaceMode.value = mode
  }

  function backToLibrary() {
    currentKB.value = null
    currentPage.value = null
    workspaceMode.value = 'browse'
    rawMaterials.value = []
    sourceGroups.value = []
    pages.value = []
    pageRefs.value = []
    archivedPageRefs.value = []
    brokenLinksReport.value = null
    brokenLinksJob.value = null
    if (brokenLinksPollTimer) { clearInterval(brokenLinksPollTimer); brokenLinksPollTimer = null }
    brokenLinksLoading.value = false
    selectedRawId.value = null
    pageTypeProfile.value = null
  }

  async function fetchRawMaterials(kbId: number) {
    const res: any = await wikiApi.listRaw(kbId)
    rawMaterials.value = res.data || []
  }

  async function fetchSourceGroups(kbId: number) {
    const res: any = await wikiApi.listSourceGroups(kbId)
    sourceGroups.value = res.data || []
  }

  async function createSourceGroup(kbId: number, data: {
    alias: string
    path: string
    fileFilter?: string | null
    cronExpr?: string | null
    enabled?: boolean | null
  }) {
    const res: any = await wikiApi.createSourceGroup(kbId, data)
    await fetchSourceGroups(kbId)
    return res.data || res
  }

  async function updateSourceGroup(kbId: number, groupId: number | string, data: {
    alias?: string | null
    path?: string | null
    fileFilter?: string | null
    cronExpr?: string | null
    enabled?: boolean | null
  }) {
    const res: any = await wikiApi.updateSourceGroup(kbId, groupId, data)
    await fetchSourceGroups(kbId)
    return res.data || res
  }

  /** reassignTo: target groupId to move member raws into, or null/undefined to leave them ungrouped. */
  async function deleteSourceGroup(kbId: number, groupId: number | string, reassignTo?: number | string | null) {
    await wikiApi.deleteSourceGroup(kbId, groupId, reassignTo)
    await Promise.all([fetchSourceGroups(kbId), fetchRawMaterials(kbId)])
  }

  async function scanSourceGroup(kbId: number, groupId: number | string, mode: 'incremental' | 'full' = 'incremental') {
    const res: any = await wikiApi.scanSourceGroup(kbId, groupId, mode)
    await Promise.all([fetchSourceGroups(kbId), fetchRawMaterials(kbId)])
    return res.data || res
  }

  async function updateRawGroup(kbId: number, rawId: number, groupId: number | string | null) {
    await wikiApi.updateRawGroup(kbId, rawId, groupId)
    await Promise.all([fetchRawMaterials(kbId), fetchSourceGroups(kbId)])
  }

  async function batchUpdateRawGroup(kbId: number, rawIds: number[], groupId: number | string | null) {
    await wikiApi.batchUpdateRawGroup(kbId, rawIds, groupId)
    await Promise.all([fetchRawMaterials(kbId), fetchSourceGroups(kbId)])
  }

  async function fetchPages(kbId: number, rawId?: number | null) {
    const res: any = await wikiApi.listPages(kbId, rawId ?? undefined)
    pages.value = res.data || []
    if (!rawId) totalPageCount.value = pages.value.length
  }

  /**
   * Fetch the active (non-archived) wikilink resolution index. Called on KB
   * switch and from {@link refreshCurrentKB} so the viewer always has a fresh
   * map. `archivedPageRefs` is left alone — call {@link fetchArchivedPageRefs}
   * lazily if the viewer detects a link pointing at a possibly archived target.
   */
  async function fetchPageRefs(kbId: number) {
    const res: any = await wikiApi.listPageRefs(kbId, false)
    pageRefs.value = (res.data?.items ?? res.items ?? []) as WikiPageRef[]
  }

  /**
   * Lazily fetch archived refs so the viewer can label existing links to
   * archived targets without polluting the default resolution map (which would
   * otherwise let LLM-generated content keep pointing at retired pages).
   */
  async function fetchArchivedPageRefs(kbId: number) {
    if (archivedPageRefs.value.length > 0) return
    const res: any = await wikiApi.listPageRefs(kbId, true)
    const all = (res.data?.items ?? res.items ?? []) as WikiPageRef[]
    archivedPageRefs.value = all.filter((p) => p.archived)
  }

  /**
   * Load the latest broken-links report for the active KB. Treats HTTP 404
   * ("no scan yet") as an expected empty state rather than an error — the
   * caller decides whether to surface "click scan" UX.
   */
  async function loadBrokenLinksReport(kbId: number) {
    try {
      const res: any = await wikiApi.getBrokenLinksReport(kbId)
      brokenLinksReport.value = (res.data ?? res) as WikiBrokenLinksReport
    } catch (e: any) {
      if (e?.response?.status === 404 || e?.code === 404) {
        brokenLinksReport.value = null
      } else {
        console.error('[Wiki] Failed to load broken-links report', e)
      }
    }
  }

  /**
   * Start (or rejoin) a broken-links scan job and poll the aggregate
   * endpoint until completedAt advances past the job's startedAt. Updates
   * `brokenLinksJob` for in-flight UX and `brokenLinksReport` once the
   * server confirms completion. Returns when polling resolves or aborts.
   */
  async function startBrokenLinksScan(kbId: number) {
    if (brokenLinksPollTimer) {
      clearInterval(brokenLinksPollTimer)
      brokenLinksPollTimer = null
    }
    brokenLinksLoading.value = true
    try {
      const res: any = await wikiApi.startBrokenLinksScan(kbId)
      const job = (res.data ?? res) as WikiLintJob
      brokenLinksJob.value = job
      // If POST returned an already-completed job (e.g. instant scan on tiny
      // KB), refresh the aggregate immediately and skip polling.
      if (job.status === 'completed' || job.status === 'failed') {
        await loadBrokenLinksReport(kbId)
        brokenLinksLoading.value = false
        return job
      }
      // Otherwise poll the aggregate every 2s. Authoritative "is this done"
      // signal is completedAt > startedAt — the job state in memory is
      // refreshed alongside for failure surfacing.
      const startedAt = job.startedAt
      brokenLinksPollTimer = setInterval(async () => {
        try {
          await loadBrokenLinksReport(kbId)
          const report = brokenLinksReport.value
          if (report?.completedAt && (!startedAt || report.completedAt >= startedAt)) {
            if (brokenLinksPollTimer) clearInterval(brokenLinksPollTimer)
            brokenLinksPollTimer = null
            brokenLinksJob.value = null
            brokenLinksLoading.value = false
          }
        } catch (e) {
          console.error('[Wiki] Polling broken-links scan failed', e)
        }
      }, 2000)
      // Hard timeout — give up tracking after 5 minutes; user can re-trigger.
      setTimeout(() => {
        if (brokenLinksPollTimer) {
          clearInterval(brokenLinksPollTimer)
          brokenLinksPollTimer = null
          brokenLinksLoading.value = false
        }
      }, 5 * 60 * 1000)
      return job
    } catch (e) {
      brokenLinksLoading.value = false
      throw e
    }
  }

  // Background refreshes (job completion, SSE events, fallback polling) must
  // not drop the user's active raw-material filter. Re-fetch the page list
  // scoped to selectedRawId whenever a filter is applied — otherwise the list
  // silently reverts to every material's pages while the filter banner still
  // claims a filter is active.
  async function refreshCurrentKB() {
    if (!currentKB.value) return
    const kbId = currentKB.value.id
    const [kbRes] = await Promise.all([
      wikiApi.getKB(kbId),
      fetchRawMaterials(kbId),
      fetchSourceGroups(kbId),
      fetchPages(kbId, selectedRawId.value ?? undefined),
      // Keep refs in lockstep with the rest of the KB state so a freshly
      // created page is immediately resolvable by the viewer.
      fetchPageRefs(kbId),
      loadPageTypeProfile(kbId),
    ])
    const nextKB = (kbRes as any).data || kbRes
    currentKB.value = nextKB
    const idx = knowledgeBases.value.findIndex(kb => kb.id === kbId)
    if (idx >= 0) {
      knowledgeBases.value[idx] = nextKB
    }
  }

  async function filterPagesByRaw(kbId: number, rawId: number) {
    selectedRawId.value = rawId
    await fetchPages(kbId, rawId)
  }

  async function clearRawFilter(kbId: number) {
    selectedRawId.value = null
    await fetchPages(kbId)
  }

  async function loadPage(kbId: number, slug: string) {
    const res: any = await wikiApi.getPage(kbId, slug)
    currentPage.value = res.data || res
  }

  async function addRawText(kbId: number, title: string, content: string) {
    const res: any = await wikiApi.addRawText(kbId, { title, content })
    const raw = res.data || res
    const existingIdx = rawMaterials.value.findIndex(r => r.id === raw.id)
    if (existingIdx >= 0) {
      rawMaterials.value[existingIdx] = raw
    } else {
      rawMaterials.value.unshift(raw)
    }
    return raw
  }

  async function uploadRawFile(kbId: number, file: File, onProgress?: (pct: number) => void) {
    const formData = new FormData()
    formData.append('file', file)
    const res: any = await wikiApi.uploadRaw(kbId, formData, onProgress)
    const raw = res.data || res
    // Dedup: if backend returned an existing record, replace it in the list instead of adding a duplicate
    const existingIdx = rawMaterials.value.findIndex(r => r.id === raw.id)
    if (existingIdx >= 0) {
      rawMaterials.value[existingIdx] = raw
    } else {
      rawMaterials.value.unshift(raw)
    }
    return raw
  }

  async function scanDirectory(kbId: number) {
    const res: any = await wikiApi.scanDirectory(kbId)
    const result = res.data || res
    // Refresh materials after the scan imports new rows.
    await fetchRawMaterials(kbId)
    return result
  }

  return {
    knowledgeBases,
    currentKB,
    workspaceMode,
    rawMaterials,
    sourceGroups,
    pages,
    currentPage,
    loading,
    selectedRawId,
    totalPageCount,
    pageTypeProfile,
    pageRefs,
    archivedPageRefs,
    brokenLinksReport,
    brokenLinksJob,
    brokenLinksLoading,
    fetchKnowledgeBases,
    selectKB,
    createKB,
    deleteKB,
    setWorkspaceMode,
    backToLibrary,
    fetchRawMaterials,
    fetchSourceGroups,
    createSourceGroup,
    updateSourceGroup,
    deleteSourceGroup,
    scanSourceGroup,
    updateRawGroup,
    batchUpdateRawGroup,
    fetchPages,
    fetchPageRefs,
    fetchArchivedPageRefs,
    loadBrokenLinksReport,
    startBrokenLinksScan,
    loadPageTypeProfile,
    refreshCurrentKB,
    filterPagesByRaw,
    clearRawFilter,
    loadPage,
    addRawText,
    uploadRawFile,
    scanDirectory,
  }
})

// Enable HMR for this store: editing it during `pnpm dev` patches the live
// store instead of requiring a full page reload. Stripped from prod builds.
if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useWikiStore, import.meta.hot))
}
