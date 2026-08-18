import type { ModelConfig, ProviderInfo, ProviderModelInfo } from '@/types'

export interface ProviderOption {
  id: string
  name: string
}

/**
 * Build the credential-free provider shape consumed by the chat model picker.
 *
 * Viewer-level users cannot read GET /models because that response includes
 * connection settings. They can, however, read the provider option projection
 * and the enabled model list. Joining those responses gives the picker all it
 * needs without exposing credentials or inventing liveness diagnostics.
 */
export function buildViewerModelProviders(
  options: ProviderOption[],
  enabledModels: ModelConfig[],
): ProviderInfo[] {
  const modelsByProvider = new Map<string, ProviderModelInfo[]>()

  for (const model of enabledModels) {
    if (!model.enabled || !model.provider || !model.modelName) continue
    const models = modelsByProvider.get(model.provider) || []
    models.push({ id: model.modelName, name: model.name || model.modelName })
    modelsByProvider.set(model.provider, models)
  }

  return options.flatMap((option) => {
    const models = modelsByProvider.get(option.id) || []
    if (models.length === 0) return []
    return [{
      id: option.id,
      name: option.name || option.id,
      models,
      extraModels: [],
      isCustom: false,
      isLocal: false,
      supportModelDiscovery: false,
      supportConnectionCheck: false,
      freezeUrl: true,
      requireApiKey: false,
      configured: true,
      available: true,
      enabled: true,
    } satisfies ProviderInfo]
  })
}
