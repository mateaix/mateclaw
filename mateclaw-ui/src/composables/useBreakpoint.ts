import { ref, onMounted, onBeforeUnmount, type Ref } from 'vue'

/**
 * Reactive CSS media-query match.
 *
 * The query is evaluated synchronously when the composable is called, so the
 * very first render already reflects the real viewport (no mount-time flash
 * from a `false` default). The `change` listener is attached on mount and
 * removed on unmount.
 *
 * @param query a CSS media query, e.g. `(max-width: 768px)`.
 * @returns a readonly-by-convention ref that stays in sync with the query.
 */
export function useMediaQuery(query: string): Ref<boolean> {
  const mql = typeof window !== 'undefined' && window.matchMedia
    ? window.matchMedia(query)
    : null
  const matches = ref(mql ? mql.matches : false)

  function onChange(e: MediaQueryListEvent) {
    matches.value = e.matches
  }

  onMounted(() => mql?.addEventListener('change', onChange))
  onBeforeUnmount(() => mql?.removeEventListener('change', onChange))

  return matches
}

/** App-wide breakpoints — keep in sync with the CSS `@media` rules. */
export const BREAKPOINTS = {
  mobile: '(max-width: 768px)',
  /** Narrow desktop — e.g. where the chat sidebar auto-collapses. */
  compact: '(max-width: 1200px)',
} as const

/** True on viewports at or below the mobile breakpoint (≤ 768px). */
export function useIsMobile(): Ref<boolean> {
  return useMediaQuery(BREAKPOINTS.mobile)
}
