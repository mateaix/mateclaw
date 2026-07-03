import type { SearchProviderCatalog } from '@/types'

export interface ProviderOption {
  value: string
  label: string
}

/** Turns a catalog into <select> options, with a synthetic "auto" option prepended (value ''). */
export function buildProviderOptions(catalog: SearchProviderCatalog, autoLabel: string): ProviderOption[] {
  const options: ProviderOption[] = [{ value: '', label: autoLabel }]
  for (const entry of catalog.providers) {
    options.push({ value: entry.id, label: entry.label })
  }
  return options
}

/** Which provider card should be expanded by default: the currently-resolved one, else the first. */
export function resolveDefaultExpandedId(catalog: SearchProviderCatalog): string | null {
  if (catalog.resolvedId) return catalog.resolvedId
  return catalog.providers.length > 0 ? catalog.providers[0].id : null
}
