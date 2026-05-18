import { computed } from 'vue'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import type { Capability } from '@/composables/capabilities'

/**
 * Shared sidebar/nav filtering. The three layouts (Main / Settings / Security)
 * all call this with their own scope so they cannot drift in which menu items
 * a given role sees. Items with no `requiredCapability` are visible to anyone
 * authenticated; items requiring `globalAdmin: true` only show for global
 * admins regardless of workspace role.
 */
export interface NavItem {
  /** Vue Router path (absolute) — used for both `:to` and active match. */
  path: string
  /** i18n key or literal label rendered in the menu. */
  label: string
  /** Lucide / pixel icon name or symbol. Layout decides how to render. */
  icon?: string
  /** Required workspace capability; absent means "any authenticated user". */
  requiredCapability?: Capability
  /** When true, only shows for global admins (mate_user.role='admin'). */
  globalAdmin?: boolean
}

export function useNavItems(items: NavItem[]) {
  const store = useWorkspaceStore()
  return computed(() => {
    // Default deny while capabilities are still loading — render nothing
    // rather than flashing the full menu and snapping it back.
    if (!store.accessLoaded) return []
    return items.filter((item) => {
      if (item.globalAdmin) return store.isGlobalAdmin
      if (item.requiredCapability && !store.can(item.requiredCapability)) return false
      return true
    })
  })
}
