import { describe, expect, it } from 'vitest'
import {
  buildProviderOptions,
  builtinFallbackCatalog,
  resolveDefaultExpandedId,
  resolveSourceLabelKey,
} from '../useSearchProviderCatalog'
import type { SearchProviderCatalog } from '@/types'

const catalog: SearchProviderCatalog = {
  providers: [
    { id: 'serper', label: 'Serper (Google)', builtin: true, requiresCredential: true, available: false, pluginName: null },
    { id: 'duckduckgo', label: 'DuckDuckGo', builtin: true, requiresCredential: false, available: true, pluginName: null },
    { id: 'my-search', label: 'My Search', builtin: false, requiresCredential: true, available: true, pluginName: 'my-plugin' },
  ],
  resolvedId: 'duckduckgo',
  resolvedSource: 'keyless-fallback',
}

describe('buildProviderOptions', () => {
  it('prepends an auto option with empty-string value', () => {
    const options = buildProviderOptions(catalog, 'auto-label')
    expect(options[0]).toEqual({ value: '', label: 'auto-label' })
    expect(options).toHaveLength(4)
  })

  it('maps each catalog entry to a value/label pair preserving order', () => {
    const options = buildProviderOptions(catalog, 'auto-label')
    expect(options.slice(1)).toEqual([
      { value: 'serper', label: 'Serper (Google)' },
      { value: 'duckduckgo', label: 'DuckDuckGo' },
      { value: 'my-search', label: 'My Search' },
    ])
  })

  it('returns just the auto option when the catalog is empty', () => {
    const options = buildProviderOptions({ providers: [], resolvedId: null, resolvedSource: null }, 'auto-label')
    expect(options).toEqual([{ value: '', label: 'auto-label' }])
  })

  it('appends the saved provider id when it is missing from the catalog', () => {
    const options = buildProviderOptions(catalog, 'auto-label', 'vanished-plugin-search')
    expect(options[options.length - 1]).toEqual({ value: 'vanished-plugin-search', label: 'vanished-plugin-search' })
  })

  it('does not duplicate the saved provider id when it is already in the catalog', () => {
    const options = buildProviderOptions(catalog, 'auto-label', 'serper')
    expect(options.filter((o) => o.value === 'serper')).toHaveLength(1)
  })

  it('does not append anything for an empty or null saved value', () => {
    expect(buildProviderOptions(catalog, 'auto-label', '')).toHaveLength(4)
    expect(buildProviderOptions(catalog, 'auto-label', null)).toHaveLength(4)
  })
})

describe('builtinFallbackCatalog', () => {
  it('contains exactly the four built-in providers, all marked builtin with no resolution', () => {
    const fallback = builtinFallbackCatalog()
    expect(fallback.providers.map((p) => p.id)).toEqual(['searxng', 'duckduckgo', 'serper', 'tavily'])
    expect(fallback.providers.every((p) => p.builtin && p.pluginName === null)).toBe(true)
    expect(fallback.resolvedId).toBeNull()
    expect(fallback.resolvedSource).toBeNull()
  })
})

describe('resolveDefaultExpandedId', () => {
  it('expands the resolved provider when present', () => {
    expect(resolveDefaultExpandedId(catalog)).toBe('duckduckgo')
  })

  it('falls back to the first provider when nothing is resolved', () => {
    const noneResolved = { ...catalog, resolvedId: null, resolvedSource: null }
    expect(resolveDefaultExpandedId(noneResolved)).toBe('serper')
  })

  it('returns null when the catalog has no providers at all', () => {
    expect(resolveDefaultExpandedId({ providers: [], resolvedId: null, resolvedSource: null })).toBeNull()
  })
})

describe('resolveSourceLabelKey', () => {
  it('maps "configured" to "configured"', () => {
    expect(resolveSourceLabelKey('configured')).toBe('configured')
  })

  it('maps "auto-detect" to "autoDetect"', () => {
    expect(resolveSourceLabelKey('auto-detect')).toBe('autoDetect')
  })

  it('falls back to "keylessFallback" for "keyless-fallback"', () => {
    expect(resolveSourceLabelKey('keyless-fallback')).toBe('keylessFallback')
  })

  it('falls back to "keylessFallback" for null or unrecognized values', () => {
    expect(resolveSourceLabelKey(null)).toBe('keylessFallback')
    expect(resolveSourceLabelKey('something-new')).toBe('keylessFallback')
  })
})
