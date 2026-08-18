import { describe, expect, it } from 'vitest'
import type { ModelConfig } from '@/types'
import { buildViewerModelProviders } from '@/utils/viewerModelProviders'

function model(overrides: Partial<ModelConfig> = {}): ModelConfig {
  return {
    id: '1',
    name: 'GPT Test',
    provider: 'openai',
    modelName: 'gpt-test',
    enabled: true,
    isDefault: false,
    ...overrides,
  }
}

describe('buildViewerModelProviders', () => {
  it('joins safe provider options with enabled models for the chat picker', () => {
    const providers = buildViewerModelProviders(
      [{ id: 'openai', name: 'OpenAI' }],
      [model()],
    )

    expect(providers).toHaveLength(1)
    expect(providers[0]).toMatchObject({
      id: 'openai',
      name: 'OpenAI',
      available: true,
      configured: true,
      models: [{ id: 'gpt-test', name: 'GPT Test' }],
    })
  })

  it('drops disabled models and providers with no selectable model', () => {
    const providers = buildViewerModelProviders(
      [
        { id: 'openai', name: 'OpenAI' },
        { id: 'empty', name: 'Empty Provider' },
      ],
      [model({ enabled: false })],
    )

    expect(providers).toEqual([])
  })

  it('does not copy connection settings into the provider projection', () => {
    const [provider] = buildViewerModelProviders(
      [{ id: 'openai', name: 'OpenAI' }],
      [model()],
    )

    expect(provider).not.toHaveProperty('apiKey')
    expect(provider).not.toHaveProperty('baseUrl')
    expect(provider).not.toHaveProperty('generateKwargs')
  })
})
