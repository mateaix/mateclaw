import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
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

  const installed = ref<McpServer[]>([])
  const isLoading = ref(false)
  const isRefreshing = ref(false)
  const testingId = ref<number | null>(null)
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

  const pagedInstalled = computed(() =>
    paginate(filteredInstalled.value, installedPage.value, pageSize.value),
  )
  const pagedCatalog = computed(() =>
    paginate(filteredCatalog.value, catalogPage.value, pageSize.value),
  )

  let loadGeneration = 0
  async function reload() {
    const gen = ++loadGeneration
    isLoading.value = true
    try {
      const res: any = await mcpApi.list()
      if (gen === loadGeneration) {
        installed.value = (res?.data ?? []) as McpServer[]
      }
    } catch {
      if (gen === loadGeneration) mcToast.error(t('mcp.messages.loadFailed'))
    } finally {
      if (gen === loadGeneration) isLoading.value = false
    }
  }

  async function refreshAll() {
    isRefreshing.value = true
    try {
      await mcpApi.refresh()
      mcToast.success(t('mcp.messages.refreshSuccess'))
      await reload()
    } catch (e: any) {
      mcToast.error(e?.message || t('mcp.messages.saveFailed'))
    } finally {
      isRefreshing.value = false
    }
  }

  async function saveServer(form: McpServerForm, editing: McpServer | null): Promise<boolean> {
    try {
      if (editing) {
        await mcpApi.update(editing.id, form)
        mcToast.success(t('mcp.messages.updateSuccess'))
      } else {
        await mcpApi.create(form)
        mcToast.success(t('mcp.messages.createSuccess'))
      }
      await reload()
      return true
    } catch (e: any) {
      mcToast.error(e?.message || t('mcp.messages.saveFailed'))
      return false
    }
  }

  async function removeServer(server: McpServer): Promise<boolean> {
    try {
      await mcpApi.delete(server.id)
      mcToast.success(t('mcp.messages.deleteSuccess'))
      await reload()
      return true
    } catch (e: any) {
      mcToast.error(e?.message || t('mcp.messages.saveFailed'))
      return false
    }
  }

  async function toggleServer(server: McpServer) {
    try {
      await mcpApi.toggle(server.id, !server.enabled)
      mcToast.success(t('mcp.messages.toggleSuccess'))
      await reload()
    } catch (e: any) {
      mcToast.error(e?.message || t('mcp.messages.saveFailed'))
    }
  }

  async function testServer(server: McpServer) {
    testingId.value = server.id
    testResult.value = null
    try {
      const res: any = await mcpApi.test(server.id)
      testResult.value = res.data as McpTestResult
    } catch (e: any) {
      testResult.value = {
        success: false,
        message: e?.message || 'Unknown error',
        toolCount: 0,
        latencyMs: 0,
        discoveredTools: [],
      }
    } finally {
      testingId.value = null
      // Auto-dismiss toast after 4s so it doesn't linger on screen.
      setTimeout(() => {
        testResult.value = null
      }, 4000)
    }
  }

  return {
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
