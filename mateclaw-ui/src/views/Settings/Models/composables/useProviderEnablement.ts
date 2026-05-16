import { ref, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { modelApi } from '@/api'
import type { EnableResult, ProviderInfo } from '@/types'

interface ListDeps {
  /** Re-fetch the visible (enabled) providers list after a toggle. */
  loadProviders: () => Promise<void>
  /** Re-fetch active model so the dropdown reflects auto-promoted defaults. */
  loadActiveModel: () => Promise<void>
}

/**
 * RFC-074 PR-2: catalog browse + enable / disable lifecycle for the
 * "Add Provider" drawer.
 *
 * State is intentionally split from useProviderList: the catalog endpoint
 * returns ALL providers (enabled + disabled) and is only fetched on demand
 * when the drawer opens, while loadProviders() returns the user-facing
 * enabled subset.
 */
export function useProviderEnablement(deps: ListDeps) {
  const { t } = useI18n()

  /** Full catalog (enabled + disabled). Loaded lazily when drawer opens. */
  const catalog = ref<ProviderInfo[]>([])
  /** Drawer visibility — both index.vue and useProviderEnablement read/write. */
  const drawerOpen = ref(false)
  /** Provider id currently being toggled (for spinner state on the row). */
  const togglingId = ref<string | null>(null)

  async function loadCatalog() {
    const res: any = await modelApi.catalog()
    catalog.value = res.data || []
  }

  function openDrawer() {
    drawerOpen.value = true
    // fire-and-forget refresh so revisits get fresh data without blocking the open animation
    loadCatalog().catch(err => {
      console.warn('[ProviderEnablement] catalog load failed', err)
    })
  }

  function closeDrawer() {
    drawerOpen.value = false
  }

  async function enableProvider(providerId: string): Promise<EnableResult | null> {
    togglingId.value = providerId
    try {
      const res: any = await modelApi.enableProvider(providerId)
      // Refresh both catalog (now this row shows enabled) and main list (now this row appears).
      await Promise.all([loadCatalog(), deps.loadProviders()])
      return res.data as EnableResult
    } catch (err) {
      mcToast.error(err instanceof Error ? err.message : String(err))
      return null
    } finally {
      togglingId.value = null
    }
  }

  async function disableProvider(providerId: string): Promise<EnableResult | null> {
    togglingId.value = providerId
    try {
      const res: any = await modelApi.disableProvider(providerId)
      const result: EnableResult = res.data
      // Re-fetch list (row disappears) + active model (default may have switched).
      await Promise.all([loadCatalog(), deps.loadProviders(), deps.loadActiveModel()])
      // RFC-074 §2: silent switch + toast. Show the toast here so every caller
      // (drawer disable, card more-menu, etc.) gets consistent UX.
      if (result?.defaultSwitched) {
        mcToast.success(t('settings.model.defaultSwitchedToast', {
          provider: result.newDefaultProviderId,
          model: result.newDefaultModel,
        }))
      }
      return result
    } catch (err) {
      mcToast.error(err instanceof Error ? err.message : String(err))
      return null
    } finally {
      togglingId.value = null
    }
  }

  return {
    catalog,
    drawerOpen,
    togglingId,
    loadCatalog,
    openDrawer,
    closeDrawer,
    enableProvider,
    disableProvider,
  }
}
