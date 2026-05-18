import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import { modelApi } from '@/api'
import type { ProviderInfo } from '@/types'
import { safeParseJson } from '@/utils/safeJson'
import { chatModelToProtocol, protocolToChatModel } from '@/utils/modelProtocol'

// Provider IDs are used as path segments in DELETE / config endpoints.
// Slashes / spaces / # / ? would make `{providerId}` PathVariable miss
// the controller and fall through to the static-resource handler
// (see issue #39: "No static resource api/v1/models/custom-providers/...").
// Keep this in sync with the backend if a server-side guard is added.
const PROVIDER_ID_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/

interface ListDeps {
  loadProviders: () => Promise<void>
  loadActiveModel: () => Promise<void>
}

/**
 * RFC-074 PR-1: provider create / edit / save / delete + the form reactive
 * model and its derived placeholders. Refresh after mutation goes back to
 * useProviderList via injected callbacks so this composable stays UI-only.
 */
export function useProviderForm(deps: ListDeps) {
  const { t } = useI18n()

  const editingProvider = ref<ProviderInfo | null>(null)
  const showProviderModal = ref(false)
  const advancedOpen = ref(false)

  const providerForm = reactive({
    id: '',
    name: '',
    baseUrl: '',
    apiKey: '',
    apiKeyPrefix: 'sk-',
    protocol: 'openai-compatible',
    chatModel: 'OpenAIChatModel',
    requireApiKey: true,
    generateKwargsText: '{}',
    enableSearch: false,
    searchStrategy: '',
    // RFC-009 P3.5: position in the multi-model failover chain.
    // 0 = excluded; positive int = ascending try-order.
    fallbackPriority: 0,
  })

  const protocolOptions = computed(() => ([
    { value: 'openai-compatible', label: t('settings.model.protocolOpenAI') },
    { value: 'anthropic-messages', label: t('settings.model.protocolAnthropic') },
    { value: 'gemini-native', label: t('settings.model.protocolGemini') },
    { value: 'dashscope-native', label: t('settings.model.protocolDashScope') },
  ]))

  const currentProviderForForm = computed(() => editingProvider.value ?? {
    id: providerForm.id,
    name: providerForm.name,
  })

  const providerBaseUrlPlaceholder = computed(() => {
    const id = currentProviderForForm.value?.id
    if (id === 'openai') return 'https://api.openai.com/v1'
    if (id === 'azure-openai') return 'https://<resource>.openai.azure.com/openai/v1'
    if (id === 'anthropic') return 'https://api.anthropic.com'
    if (id === 'ollama') return 'http://localhost:11434'
    if (id === 'lmstudio') return 'http://localhost:1234/v1'
    if (id === 'gemini') return 'https://generativelanguage.googleapis.com'
    if (id === 'openrouter') return 'https://openrouter.ai/api/v1'
    if (id === 'zhipu-cn') return 'https://open.bigmodel.cn/api/paas/v4'
    if (id === 'zhipu-intl') return 'https://open.z.ai/api/paas/v4'
    if (id === 'zhipu-cn-codingplan') return 'https://open.bigmodel.cn/api/coding/paas/v4'
    if (id === 'zhipu-intl-codingplan') return 'https://api.z.ai/api/coding/paas/v4'
    if (id === 'volcengine') return 'https://ark.cn-beijing.volces.com/api/v3'
    if (id === 'xiaomi-mimo') return 'https://api.xiaomimimo.com/v1'
    return 'https://example.com/v1'
  })

  const providerBaseUrlHint = computed(() => {
    const id = currentProviderForForm.value?.id
    if (id === 'openai') return t('settings.model.hints.openai')
    if (id === 'azure-openai') return t('settings.model.hints.azureOpenai')
    if (id === 'anthropic') return t('settings.model.hints.anthropic')
    if (id === 'ollama') return t('settings.model.hints.ollama')
    if (id === 'lmstudio') return t('settings.model.hints.lmstudio')
    if (id === 'gemini') return t('settings.model.hints.gemini')
    if (id === 'openrouter') return t('settings.model.hints.openrouter')
    if (id === 'zhipu-cn') return t('settings.model.hints.zhipu')
    if (id === 'zhipu-intl') return t('settings.model.hints.zhipuIntl')
    if (id === 'volcengine') return t('settings.model.hints.volcengine')
    if (id === 'xiaomi-mimo') return t('settings.model.hints.xiaomiMimo')
    return t('settings.model.hints.openaiCompatible')
  })

  const providerApiKeyPlaceholder = computed(() => {
    return providerForm.apiKeyPrefix
      ? `${t('settings.model.apiKeyInput')} (${providerForm.apiKeyPrefix}...)`
      : t('settings.model.apiKeyInput')
  })

  function openCreateProviderModal() {
    editingProvider.value = null
    advancedOpen.value = false
    Object.assign(providerForm, {
      id: '',
      name: '',
      baseUrl: '',
      apiKey: '',
      apiKeyPrefix: 'sk-',
      protocol: 'openai-compatible',
      chatModel: 'OpenAIChatModel',
      requireApiKey: true,
      generateKwargsText: '{}',
      enableSearch: false,
      searchStrategy: '',
      fallbackPriority: 0,
    })
    showProviderModal.value = true
  }

  function openProviderConfigModal(provider: ProviderInfo) {
    editingProvider.value = provider
    advancedOpen.value = true
    const kwargs = provider.generateKwargs || {}
    const protocol = provider.protocol || chatModelToProtocol(provider.chatModel)
    // DashScope opens search by default — only off when kwargs explicitly set false.
    const isDashScope = protocol === 'dashscope-native'
    const searchDefault = isDashScope ? kwargs.enableSearch !== false : !!kwargs.enableSearch
    Object.assign(providerForm, {
      id: provider.id,
      name: provider.name,
      baseUrl: provider.baseUrl || '',
      apiKey: '',
      apiKeyPrefix: provider.apiKeyPrefix || 'sk-',
      protocol,
      chatModel: provider.chatModel || 'OpenAIChatModel',
      requireApiKey: protocol === 'openai-compatible' ? provider.requireApiKey !== false : true,
      generateKwargsText: JSON.stringify(kwargs, null, 2),
      enableSearch: searchDefault,
      searchStrategy: (kwargs.searchStrategy as string) || '',
      fallbackPriority: provider.fallbackPriority ?? 0,
    })
    showProviderModal.value = true
  }

  function closeProviderModal() {
    showProviderModal.value = false
    editingProvider.value = null
    advancedOpen.value = false
  }

  async function saveProvider(): Promise<boolean> {
    // RFC-074 / issue #39: provider id becomes a URL path segment, so a slash
    // or other unsafe char makes the row impossible to delete later. Validate
    // before hitting the API on the create path; editing is exempt because the
    // id field is hidden and the existing value is reused untouched.
    if (!editingProvider.value) {
      const id = providerForm.id.trim()
      if (!id || !PROVIDER_ID_PATTERN.test(id)) {
        mcToast.error(t('settings.model.providerIdInvalid'))
        return false
      }
      providerForm.id = id
    }
    const kwargs = safeParseJson(providerForm.generateKwargsText)
    if (providerForm.enableSearch) {
      kwargs.enableSearch = true
      if (providerForm.searchStrategy) {
        kwargs.searchStrategy = providerForm.searchStrategy
      } else {
        delete kwargs.searchStrategy
      }
    } else {
      delete kwargs.enableSearch
      delete kwargs.searchStrategy
    }
    // RFC-009 P3.5: clamp to non-negative, coerce string input back to integer.
    const fallbackPriority = Math.max(0, Math.floor(Number(providerForm.fallbackPriority) || 0))
    const requireApiKey = providerForm.protocol === 'openai-compatible'
      ? providerForm.requireApiKey
      : true
    if (editingProvider.value) {
      await modelApi.updateProviderConfig(editingProvider.value.id, {
        apiKey: providerForm.apiKey,
        baseUrl: providerForm.baseUrl,
        protocol: providerForm.protocol,
        chatModel: protocolToChatModel(providerForm.protocol),
        requireApiKey,
        generateKwargs: kwargs,
        fallbackPriority,
      })
    } else {
      await modelApi.createCustomProvider({
        id: providerForm.id,
        name: providerForm.name,
        defaultBaseUrl: providerForm.baseUrl,
        apiKeyPrefix: providerForm.apiKeyPrefix,
        protocol: providerForm.protocol,
        chatModel: protocolToChatModel(providerForm.protocol),
        requireApiKey,
        models: [],
      })
      if (providerForm.apiKey || providerForm.generateKwargsText || fallbackPriority > 0) {
        await modelApi.updateProviderConfig(providerForm.id, {
          apiKey: providerForm.apiKey,
          baseUrl: providerForm.baseUrl,
          protocol: providerForm.protocol,
          chatModel: protocolToChatModel(providerForm.protocol),
          requireApiKey,
          generateKwargs: kwargs,
          fallbackPriority,
        })
      }
    }
    closeProviderModal()
    await Promise.all([deps.loadProviders(), deps.loadActiveModel()])
    return true
  }

  /**
   * Inline API-key save from the provider card — bypasses the modal so the
   * 90% case (paste a key, hit save) doesn't require opening a settings dialog.
   *
   * Backend updateProviderConfig is a PUT that overwrites baseUrl / chatModel /
   * generateKwargs unconditionally, so we must echo the existing values to
   * avoid clobbering them when we only want to change the key.
   */
  async function saveProviderApiKey(provider: ProviderInfo, apiKey: string) {
    const trimmed = apiKey.trim()
    if (!trimmed) return
    await modelApi.updateProviderConfig(provider.id, {
      apiKey: trimmed,
      baseUrl: provider.baseUrl ?? '',
      protocol: provider.protocol || chatModelToProtocol(provider.chatModel),
      chatModel: provider.chatModel,
      generateKwargs: provider.generateKwargs ?? {},
      // Omit fallbackPriority — backend treats null as "leave untouched".
    })
    await Promise.all([deps.loadProviders(), deps.loadActiveModel()])
  }

  async function deleteProvider(provider: ProviderInfo) {
    const ok = await mcConfirm({
      title: t('common.confirm'),
      message: t('settings.model.deleteConfirm', { name: provider.name }),
      confirmText: t('common.delete'),
      tone: 'danger',
    })
    if (!ok) return false
    await modelApi.deleteCustomProvider(provider.id)
    await deps.loadProviders()
    return true
  }

  return {
    editingProvider,
    showProviderModal,
    advancedOpen,
    providerForm,
    protocolOptions,
    providerBaseUrlPlaceholder,
    providerBaseUrlHint,
    providerApiKeyPlaceholder,
    openCreateProviderModal,
    openProviderConfigModal,
    closeProviderModal,
    saveProvider,
    saveProviderApiKey,
    deleteProvider,
  }
}
