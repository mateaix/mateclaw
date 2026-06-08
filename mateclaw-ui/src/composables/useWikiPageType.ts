import { useI18n } from 'vue-i18n'
import { useWikiStore } from '@/stores/useWikiStore'

/**
 * Shared pageType presentation helpers, driven by the active KB's pageType
 * profile (loaded into the wiki store). Centralises label resolution and
 * colouring so the sidebar, graph view, node panel and toolbar all render a
 * KB's custom classification consistently instead of each hard-coding the
 * built-in ten types.
 */

// Fixed colours for the built-in ten types so existing KBs look unchanged.
const BUILTIN_COLORS: Record<string, string> = {
  concept: '#D96E46',
  person: '#5B8DEF',
  place: '#4CAF82',
  event: '#F59E0B',
  technology: '#8B5CF6',
  organization: '#EC4899',
  product: '#14B8A6',
  term: '#6B7280',
  process: '#F97316',
  other: '#9CA3AF',
}

// Palette for custom / synthesis types not in the built-in map. A type name is
// hashed to a stable index so the same type always gets the same colour within
// and across sessions, without needing a colour field on the profile schema.
const HASH_PALETTE = [
  '#0EA5E9', '#A855F7', '#22C55E', '#EAB308', '#EF4444',
  '#06B6D4', '#D946EF', '#84CC16', '#F43F5E', '#3B82F6',
  '#10B981', '#FB923C',
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
