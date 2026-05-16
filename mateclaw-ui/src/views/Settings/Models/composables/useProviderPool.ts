import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { providerPoolApi } from '@/api'
import type { ProviderInfo } from '@/types'

interface ListDeps {
  loadProviders: () => Promise<void>
}

/**
 * RFC-074 PR-1: manual reprobe trigger. Most pool surface was inlined into
 * ProviderInfo.liveness in RFC-073, leaving this composable thin — but the
 * "in-flight" tracking and toast-on-result behavior still belong here, not
 * in the list composable.
 */
export function useProviderPool(deps: ListDeps) {
  const { t } = useI18n()

  /** Provider id currently being manually reprobed (drives the spinner on the card). */
  const reprobingId = ref<string | null>(null)

  async function reprobeProvider(provider: ProviderInfo) {
    reprobingId.value = provider.id
    try {
      const res: any = await providerPoolApi.reprobe(provider.id)
      const data = res.data || {}
      // Re-fetch the list — liveness ships inline now so this single round-trip
      // refreshes everything the UI needs (badge + status pill + dropdown filter).
      await deps.loadProviders()
      if (data.success) {
        mcToast.success(t('settings.model.poolReprobeOk'))
      } else {
        mcToast.warning(t('settings.model.poolReprobeFail', { error: data.errorMessage || '—' }))
      }
      return data
    } catch (err) {
      mcToast.error(t('settings.model.poolReprobeFail', {
        error: err instanceof Error ? err.message : String(err)
      }))
    } finally {
      reprobingId.value = null
    }
  }

  return {
    reprobingId,
    reprobeProvider,
  }
}
