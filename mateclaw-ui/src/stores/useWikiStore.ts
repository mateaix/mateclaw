import { defineStore } from 'pinia'
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
  createTime: string
  // Two-stage ingestion progress: backend writes total after routing and
  // increments done as each generated page finishes.
  progressPhase: string | null
  progressTotal: number
  progressDone: number
  // Page count derived from sourceRawIds (injected by listRaw endpoint)
  pageCount?: number
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

export const useWikiStore = defineStore('wiki', () => {
  const knowledgeBases = ref<WikiKB[]>([])
  const currentKB = ref<WikiKB | null>(null)
  const rawMaterials = ref<WikiRawMaterial[]>([])
  const pages = ref<WikiPage[]>([])
  const currentPage = ref<WikiPage | null>(null)
  const loading = ref(false)

  // Raw material filter state
  const selectedRawId = ref<number | null>(null)
  const totalPageCount = ref(0)

  async function fetchKnowledgeBases() {
    loading.value = true
    try {
      const res: any = await wikiApi.listKBs()
      knowledgeBases.value = res.data || []
    } catch (e) {
      console.error('Failed to fetch knowledge bases', e)
    } finally {
      loading.value = false
    }
  }

  async function selectKB(id: number) {
    const res: any = await wikiApi.getKB(id)
    currentKB.value = res.data || res
    await Promise.all([fetchRawMaterials(id), fetchPages(id)])
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
      pages.value = []
    }
  }

  function backToLibrary() {
    currentKB.value = null
    currentPage.value = null
    rawMaterials.value = []
    pages.value = []
    selectedRawId.value = null
  }

  async function fetchRawMaterials(kbId: number) {
    const res: any = await wikiApi.listRaw(kbId)
    rawMaterials.value = res.data || []
  }

  async function fetchPages(kbId: number, rawId?: number | null) {
    const res: any = await wikiApi.listPages(kbId, rawId ?? undefined)
    pages.value = res.data || []
    if (!rawId) totalPageCount.value = pages.value.length
  }

  async function refreshCurrentKB(options: { preserveRawFilter?: boolean } = {}) {
    if (!currentKB.value) return
    const kbId = currentKB.value.id
    const rawId = options.preserveRawFilter ? selectedRawId.value : null
    const [kbRes] = await Promise.all([
      wikiApi.getKB(kbId),
      fetchRawMaterials(kbId),
      fetchPages(kbId, rawId ?? undefined),
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
    rawMaterials,
    pages,
    currentPage,
    loading,
    selectedRawId,
    totalPageCount,
    fetchKnowledgeBases,
    selectKB,
    createKB,
    deleteKB,
    backToLibrary,
    fetchRawMaterials,
    fetchPages,
    refreshCurrentKB,
    filterPagesByRaw,
    clearRawFilter,
    loadPage,
    addRawText,
    uploadRawFile,
    scanDirectory,
  }
})
