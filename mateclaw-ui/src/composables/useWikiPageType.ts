import { useI18n } from 'vue-i18n'
import { useWikiStore } from '@/stores/useWikiStore'

/**
 * Shared pageType presentation helpers, driven by the active KB's pageType
 * profile (loaded into the wiki store). Centralises label resolution and
 * colouring so the sidebar, graph view, node panel and toolbar all render a
 * KB's custom classification consistently instead of each hard-coding the
 * built-in ten types.
 */

// Earthy categorical palette tuned to the warm terracotta + teal app theme
// (see main.css design tokens). Hues are mutually contrasting yet share an
// earthy register so the page graph, sidebar and toolbar feel of-a-piece with
// the rest of the UI. Shared with the entity graph for one visual language.
const BUILTIN_COLORS: Record<string, string> = {
  concept: '#6B9A55',      // sage
  person: '#D97757',       // terracotta (theme primary)
  place: '#E0A030',        // goldenrod
  event: '#9B5E8E',        // plum
  technology: '#4FA39B',   // aqua
  organization: '#2F8F83', // teal (theme accent)
  product: '#5B7DB1',      // denim blue
  term: '#A07B5C',         // taupe
  process: '#C08A4E',      // ochre
  other: '#9C8576',        // warm grey
}

// Palette for custom / synthesis types not in the built-in map. A type name is
// hashed to a stable index so the same type always gets the same colour within
// and across sessions, without needing a colour field on the profile schema.
// Same earthy family so generated types stay on-theme.
const HASH_PALETTE = [
  '#C0533F', '#B06E7C', '#8B934A', '#A07B5C', '#5B7DB1', '#9B5E8E',
  '#4FA39B', '#C08A4E', '#6B9A55', '#2F8F83', '#D97757', '#E0A030',
]

function hashIndex(s: string, mod: number): number {
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) | 0
  }
  return Math.abs(h) % mod
}

export function useWikiPageType() {
  const store = useWikiStore()
  const { t, te } = useI18n()

  /**
   * Display label for a pageType, three-tier fallback:
   * profile label → i18n `wiki.pageTypes.{type}` → capitalised raw key.
   */
  function formatPageTypeLabel(type: string | null | undefined): string {
    const key = (type || '').trim().toLowerCase()
    if (!key) return ''
    const fromProfile = store.pageTypeProfile?.labels?.[key]
    if (fromProfile) return fromProfile
    const i18nKey = `wiki.pageTypes.${key}`
    if (te(i18nKey)) return t(i18nKey)
    return key.charAt(0).toUpperCase() + key.slice(1)
  }

  /** Stable colour for a pageType: built-in fixed colour, else hashed palette. */
  function typeColor(type: string | null | undefined): string {
    const key = (type || 'other').toLowerCase()
    if (BUILTIN_COLORS[key]) return BUILTIN_COLORS[key]
    return HASH_PALETTE[hashIndex(key, HASH_PALETTE.length)]
  }

  return { formatPageTypeLabel, typeColor }
}
