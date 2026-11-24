import { computed, ref, watch, onScopeDispose } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { mcpApi } from '@/api/index'
import type { McpServer, McpServerForm, McpTestResult } from '@/views/mcp/types'
import { mcpCatalog, type McpCatalogEntry } from '@/views/mcp/catalog'

/**
 * Single source of truth for the MCP connections page. Owns the installed
 * server list, search/pagination state, and CRUD helpers — the page and
 * components stay thin presentational layers.
 */
export function useMcpServers() {
  const { t } = useI18n()
  const workspace = useWorkspaceStore()
  let workspaceGeneration = 0
  let disposed = false
  const isCurrent = (generation: number) => !disposed && generation === workspaceGeneration
  const canManage = (server: McpServer) => server.workspaceId == null
    ? workspace.isGlobalAdmin
    : server.workspaceId === (workspace.currentWorkspaceId || '1')

  const installed = ref<McpServer[]>([])
  const isLoading = ref(false)
  const isRefreshing = ref(false)
  const testingId = ref<string | null>(null)
  const testResult = ref<McpTestResult | null>(null)

  const search = ref('')
  const pageSize = ref(12)
  const installedPage = ref(1)
  const catalogPage = ref(1)

  // Reset both pages whenever the search input changes — prevents pointing
  // at a page index that no longer exists after filtering shrinks the list.
  watch(search, () => {
    installedPage.value = 1
    catalogPage.value = 1
  })

  const lowerSearch = computed(() => search.value.trim().toLowerCase())

  const filteredInstalled = computed<McpServer[]>(() => {
    const q = lowerSearch.value
    if (!q) return installed.value
    return installed.value.filter(s =>
      s.name.toLowerCase().includes(q) ||
      (s.description ?? '').toLowerCase().includes(q),
    )
  })

  // Don't recommend a catalog entry whose key collides with an existing
  // installed server name — once added it belongs in the Added section.
  const installedNames = computed(() => new Set(installed.value.map(s => s.name)))

  const filteredCatalog = computed<McpCatalogEntry[]>(() => {
    const q = lowerSearch.value
    return mcpCatalog.filter(c => {
      if (installedNames.value.has(c.key)) return false
      if (!q) return true
      return (
        c.name.toLowerCase().includes(q) ||
        c.description.toLowerCase().includes(q) ||
        c.key.toLowerCase().includes(q)
      )
    })
  })

  function paginate<T>(list: T[], page: number, size: number): T[] {
    const start = (page - 1) * size
    return list.slice(start, start + size)
  }

  // Connecting to an MCP server now runs asynchronously on the backend: the
  // create/toggle/update call returns immediately with status "connecting".
  // Poll a few times so the card updates to connected/error without the user
  // hitting "refresh" manually. Stops early once nothing is still connecting.
  // Poll long enough to outlast a slow/failing connect: the SSE/HTTP connect
  // can take up to connectTimeout + readTimeout (default 30s+30s) before it
  // resolves to error, so a short window would leave the card stuck on
  // "connecting" until a manual refresh. ~40s with a gentle 2s cadence covers
  // the common case; it also early-exits as soon as nothing is connecting.
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  function pollConnectingStatus(rounds = 20, intervalMs = 2000) {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
    const generation = workspaceGeneration
    let n = 0
    const tick = async () => {
      n += 1
      if (!isCurrent(generation)) return
      await reload()
      if (!isCurrent(generation)) return
      const stillConnecting = installed.value.some(s => s.lastStatus === 'connecting')
      if (stillConnecting && n < rounds) {
        pollTimer = setTimeout(tick, intervalMs)
      } else {
        pollTimer = null
      }
    }
    pollTimer = setTimeout(tick, intervalMs)
  }

  const pagedInstalled = computed(() =>
    paginate(filteredInstalled.value, installedPage.value, pageSize.value),
  )
  const pagedCatalog = computed(() =>
    paginate(filteredCatalog.value, catalogPage.value, pageSize.value),
  )

  let loadGeneration = 0
  async function reload() {
    const gen = ++loadGeneration
    const generation = workspaceGeneration
    isLoading.value = true
    try {
      const res: any = await mcpApi.list()
      if (gen === loadGeneration && isCurrent(generation)) {
        installed.value = (res?.data ?? []) as McpServer[]
      }
    } catch {
      if (gen === loadGeneration && isCurrent(generation)) mcToast.error(t('mcp.messages.loadFailed'))
    } finally {
      if (gen === loadGeneration && isCurrent(generation)) isLoading.value = false
    }
  }

  async function refreshAll() {
    if (!workspace.isGlobalAdmin) return
    const generation = workspaceGeneration
    isRefreshing.value = true
    try {
      await mcpApi.refresh()
      if (!isCurrent(generation)) return
      mcToast.success(t('mcp.messages.refreshSuccess'))
      await reload()
    } catch (e: any) {
      if (!isCurrent(generation)) return false
      mcToast.error(e?.message || t('mcp.messages.saveFailed'))
    } finally {
      if (isCurrent(generation)) isRefreshing.value = false
    }
  }

  async function saveServer(form: McpServerForm, editing: McpServer | null): Promise<boolean> {
    if ((editing && !canManage(editing)) || (form.global && !workspace.isGlobalAdmin)) return false
    const generation = workspaceGeneration
    const { global, ...payload } = form
    try {
      if (editing) {
        await mcpApi.update(editing.id, payload, global)
        if (!isCurrent(generation)) return false
        mcToast.success(t('mcp.messages.updateSuccess'))
      } else {
        await mcpApi.create(payload, global)
        if (!isCurrent(generation)) return false
        mcToast.success(t('mcp.messages.createSuccess'))
      }
      await reload()
      // Enabled servers connect in the background — tell the user and poll for
      // the result instead of leaving the card stuck on "connecting".
      if (!isCurrent(generation)) return false
      if (form.enabled) {
        mcToast.info(t('mcp.messages.connecting'))
        pollConnectingStatus()
      }
      return true
    } catch (e: any) {
      if (!isCurrent(generation)) return false
      mcToast.error(e?.message || t('mcp.messages.saveFailed'))
      return false
    }
  }

  async function removeServer(server: McpServer): Promise<boolean> {
    if (!canManage(server)) return false
    const generation = workspaceGeneration
    try {
      await mcpApi.delete(server.id)
      if (!isCurrent(generation)) return false
      mcToast.success(t('mcp.messages.deleteSuccess'))
      await reload()
      return isCurrent(generation)
    } catch (e: any) {
      if (!isCurrent(generation)) return false
      mcToast.error(e?.message || t('mcp.messages.saveFailed'))
      return false
    }
  }

  async function toggleServer(server: McpServer) {
    if (!canManage(server)) return
    const generation = workspaceGeneration
    const enabling = !server.enabled
    try {
      await mcpApi.toggle(server.id, enabling)
      if (!isCurrent(generation)) return
      mcToast.success(t('mcp.messages.toggleSuccess'))
      await reload()
      // Enabling connects in the background — poll until it settles.
      if (!isCurrent(generation)) return
      if (enabling) {
        mcToast.info(t('mcp.messages.connecting'))
        pollConnectingStatus()
      }
    } catch (e: any) {
      if (!isCurrent(generation)) return false
      mcToast.error(e?.message || t('mcp.messages.saveFailed'))
    }
  }

  async function testServer(server: McpServer) {
    if (!canManage(server)) return
    const generation = workspaceGeneration
    testingId.value = server.id
    testResult.value = null
    try {
      const res: any = await mcpApi.test(server.id)
      if (!isCurrent(generation)) return
      testResult.value = res.data as McpTestResult
    } catch (e: any) {
      if (!isCurrent(generation)) return false
      testResult.value = {
        success: false,
        message: e?.message || 'Unknown error',
        toolCount: 0,
        latencyMs: 0,
        discoveredTools: [],
      }
    } finally {
      if (!isCurrent(generation)) return
      testingId.value = null
      // Auto-dismiss toast after 4s so it doesn't linger on screen.
      setTimeout(() => {
        if (isCurrent(generation)) testResult.value = null
      }, 4000)
    }
  }

  watch(() => workspace.currentWorkspaceId, () => {
    workspaceGeneration++
    loadGeneration++
    if (pollTimer) clearTimeout(pollTimer)
    installed.value = []
    testingId.value = null
    testResult.value = null
    isRefreshing.value = false
    installedPage.value = 1
    catalogPage.value = 1
    void reload()
  }, { flush: 'sync' })
  onScopeDispose(() => {
    disposed = true
    if (pollTimer) clearTimeout(pollTimer)
  })

  return {
    canManage,
    // state
    installed,
    isLoading,
    isRefreshing,
    testingId,
    testResult,
    search,
    pageSize,
    installedPage,
    catalogPage,
    // derived
    filteredInstalled,
    filteredCatalog,
    pagedInstalled,
    pagedCatalog,
    // actions
    reload,
    refreshAll,
    saveServer,
    removeServer,
    toggleServer,
    testServer,
  }
}
