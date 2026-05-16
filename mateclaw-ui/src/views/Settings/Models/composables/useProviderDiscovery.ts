import { computed, reactive, ref, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { modelApi } from '@/api'
import type { DiscoverResult, ProviderInfo, ProviderModelInfo, TestResult } from '@/types'

interface ListDeps {
  currentProvider: Ref<ProviderInfo | null>
  refreshCurrentProvider: (providerId: string) => Promise<void>
}

/**
 * RFC-074 PR-1: manage-models modal + per-provider model discovery and
 * connection / model testing. All testing state (connectionResults,
 * modelTestResults) lives here so the cards can render results without the
 * list composable knowing about probes.
 */
export function useProviderDiscovery(deps: ListDeps) {
  const { t } = useI18n()

  const showManageModelsModal = ref(false)
  const providerModelForm = reactive({
    id: '',
    name: '',
  })

  const discovering = ref(false)
  const discoverResult = ref<DiscoverResult | null>(null)
  const selectedNewModelIds = ref<string[]>([])
  const applyingModels = ref(false)

  const connectionTestingId = ref<string | null>(null)
  const connectionResults = ref<Record<string, TestResult>>({})
  const testingModelId = ref<string | null>(null)
  const modelTestResults = ref<Record<string, TestResult>>({})

  const allNewSelected = computed(() => {
    if (!discoverResult.value || discoverResult.value.newCount === 0) return false
    return selectedNewModelIds.value.length === discoverResult.value.newModels.length
  })

  function openManageModelsModal(provider: ProviderInfo) {
    deps.currentProvider.value = provider
    providerModelForm.id = ''
    providerModelForm.name = ''
    showManageModelsModal.value = true
  }

  function closeManageModelsModal() {
    showManageModelsModal.value = false
    deps.currentProvider.value = null
    discoverResult.value = null
    selectedNewModelIds.value = []
    modelTestResults.value = {}
    testingModelId.value = null
  }

  function isExtraModel(modelId: string) {
    return !!deps.currentProvider.value?.extraModels?.some(model => model.id === modelId)
  }

  async function addProviderModel() {
    if (!deps.currentProvider.value || !providerModelForm.id) return
    await modelApi.addProviderModel(deps.currentProvider.value.id, {
      id: providerModelForm.id,
      name: providerModelForm.name || providerModelForm.id,
    })
    await deps.refreshCurrentProvider(deps.currentProvider.value.id)
    providerModelForm.id = ''
    providerModelForm.name = ''
  }

  async function removeProviderModel(model: ProviderModelInfo) {
    if (!deps.currentProvider.value) return
    if (!confirm(t('settings.model.removeConfirm', { name: model.name }))) return
    await modelApi.removeProviderModel(deps.currentProvider.value.id, model.id)
    await deps.refreshCurrentProvider(deps.currentProvider.value.id)
  }

  function toggleSelectAll() {
    if (!discoverResult.value) return
    if (allNewSelected.value) {
      selectedNewModelIds.value = []
    } else {
      selectedNewModelIds.value = discoverResult.value.newModels.map(m => m.id)
    }
  }

  async function handleDiscoverModels() {
    if (!deps.currentProvider.value) return
    discovering.value = true
    discoverResult.value = null
    selectedNewModelIds.value = []
    try {
      const res: any = await modelApi.discoverModels(deps.currentProvider.value.id)
      discoverResult.value = res.data
      if (res.data?.newCount > 0) {
        selectedNewModelIds.value = res.data.newModels.map((m: ProviderModelInfo) => m.id)
      }
    } catch (error) {
      mcToast.error(error instanceof Error ? error.message : String(error))
    } finally {
      discovering.value = false
    }
  }

  async function handleApplyModels() {
    if (!deps.currentProvider.value || selectedNewModelIds.value.length === 0) return 0
    applyingModels.value = true
    try {
      const res: any = await modelApi.applyDiscoveredModels(
        deps.currentProvider.value.id, selectedNewModelIds.value)
      const added = res.data?.added ?? selectedNewModelIds.value.length
      discoverResult.value = null
      selectedNewModelIds.value = []
      await deps.refreshCurrentProvider(deps.currentProvider.value.id)
      return added
    } catch (error) {
      mcToast.error(error instanceof Error ? error.message : String(error))
      return 0
    } finally {
      applyingModels.value = false
    }
  }

  async function handleTestConnection(provider: ProviderInfo) {
    connectionTestingId.value = provider.id
    delete connectionResults.value[provider.id]
    try {
      const res: any = await modelApi.testConnection(provider.id)
      connectionResults.value[provider.id] = res.data
    } catch (error) {
      connectionResults.value[provider.id] = {
        success: false,
        latencyMs: 0,
        errorMessage: error instanceof Error ? error.message : String(error),
      }
    } finally {
      connectionTestingId.value = null
    }
  }

  async function handleTestModel(model: ProviderModelInfo) {
    if (!deps.currentProvider.value) return
    testingModelId.value = model.id
    delete modelTestResults.value[model.id]
    try {
      const res: any = await modelApi.testModel(deps.currentProvider.value.id, model.id)
      modelTestResults.value[model.id] = res.data
    } catch (error) {
      modelTestResults.value[model.id] = {
        success: false,
        latencyMs: 0,
        errorMessage: error instanceof Error ? error.message : String(error),
      }
    } finally {
      testingModelId.value = null
    }
  }

  return {
    showManageModelsModal,
    providerModelForm,
    discovering,
    discoverResult,
    selectedNewModelIds,
    applyingModels,
    connectionTestingId,
    connectionResults,
    testingModelId,
    modelTestResults,
    allNewSelected,
    openManageModelsModal,
    closeManageModelsModal,
    isExtraModel,
    addProviderModel,
    removeProviderModel,
    toggleSelectAll,
    handleDiscoverModels,
    handleApplyModels,
    handleTestConnection,
    handleTestModel,
  }
}
