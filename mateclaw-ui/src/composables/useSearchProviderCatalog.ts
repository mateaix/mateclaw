import type { SearchProviderCatalog } from '@/types'

export interface ProviderOption {
  value: string
  label: string
}

/**
 * Turns a catalog into <select> options, with a synthetic "auto" option prepended (value '').
 *
 * When `currentId` (the currently-saved provider id) is set but missing from the
 * catalog — catalog fetch failed, or the owning plugin was disabled — an option for
 * it is appended so the <select> still shows the real stored value. Without it the
 * dropdown would render blank and a subsequent save would silently rewrite the
 * setting to '' (auto) even though the admin never chose to change it.
 */
export function buildProviderOptions(
  catalog: SearchProviderCatalog,
  autoLabel: string,
  currentId?: string | null,
): ProviderOption[] {
  const options: ProviderOption[] = [{ value: '', label: autoLabel }]
  for (const entry of catalog.providers) {
    options.push({ value: entry.id, label: entry.label })
  }
  if (currentId && !options.some((opt) => opt.value === currentId)) {
    options.push({ value: currentId, label: currentId })
  }
  return options
}

/** Which provider card should be expanded by default: the currently-resolved one, else the first. */
export function resolveDefaultExpandedId(catalog: SearchProviderCatalog): string | null {
  if (catalog.resolvedId) return catalog.resolvedId
  return catalog.providers.length > 0 ? catalog.providers[0].id : null
}

/**
 * Maps the backend's resolvedSource value to the i18n key suffix used under
 * settings.searchResolvedSource.*. Falls back to 'keylessFallback' for any
 * unrecognized or null value — but that fallback is now a named, visible
 * decision here rather than an implicit template ternary.
 */
export function resolveSourceLabelKey(source: string | null): string {
  if (source === 'configured') return 'configured'
  if (source === 'auto-detect') return 'autoDetect'
  return 'keylessFallback'
}

/**
 * Static stand-in for the four built-in providers, used when the catalog endpoint
 * fails: the built-in config forms (which bind to plain SystemSettings fields and
 * never depended on the catalog) stay reachable instead of the whole search section
 * silently vanishing. `available` is unknown in this mode — callers should hide
 * status badges rather than show a guessed state.
 *
 * ⚠ DRIFT COUPLING — these entries are a hand-maintained mirror of the backend's
 * built-in providers and MUST be kept in sync when the backend changes them:
 *   - Source of truth: mateclaw-server/.../tool/search/*SearchProvider.java
 *     (id() + autoDetectOrder()).
 *   - id/label/requiresCredential must match each provider exactly.
 *   - Order must match ascending autoDetectOrder (searxng=50, duckduckgo=100,
 *     serper=300, tavily=400 today), because resolveDefaultExpandedId() falls
 *     back to providers[0] and the UI's default-expanded card should be the
 *     highest-priority keyless one.
 *   - If a built-in is added/removed/renamed, update BOTH this list AND the
 *     <template v-if="entry.id === '...'"> form blocks in Settings/System/index.vue.
 * These ids are NOT a stable public contract — they are internal keys that have
 * just never changed. There is a matching unit test (builtinFallbackCatalog)
 * that pins the current set, so an accidental local edit will fail tests; it
 * cannot catch a backend-only change, hence this comment.
 */
export function builtinFallbackCatalog(): SearchProviderCatalog {
  return {
    providers: [
      { id: 'searxng', label: 'SearXNG', builtin: true, requiresCredential: false, available: false, pluginName: null },
      { id: 'duckduckgo', label: 'DuckDuckGo', builtin: true, requiresCredential: false, available: false, pluginName: null },
      { id: 'serper', label: 'Serper (Google)', builtin: true, requiresCredential: true, available: false, pluginName: null },
      { id: 'tavily', label: 'Tavily', builtin: true, requiresCredential: true, available: false, pluginName: null },
    ],
    resolvedId: null,
    resolvedSource: null,
  }
}
