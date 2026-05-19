import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { workspaceTeamApi } from '@/api/index'
import type { Capability, WorkspaceRole } from '@/composables/capabilities'
import { ROLE_LEVEL } from '@/composables/capabilities'

export interface Workspace {
  // Backend-issued Snowflake id — kept as a string for its whole lifecycle so a
  // Number() round-trip never truncates the 19-digit value (see CLAUDE.md).
  id: string
  name: string
  slug: string
  description?: string
  basePath?: string
  ownerId?: string
  settingsJson?: string
  createTime?: string
  updateTime?: string
  /** Real membership role; null for global admins viewing a workspace they have not joined. */
  memberRole?: WorkspaceRole | null
  roleLevel?: number
  isGlobalAdmin?: boolean
  /** Equals memberRole for normal users; 'owner' for global admins. */
  effectiveRole?: WorkspaceRole | null
}

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaces = ref<Workspace[]>([])
  // Stored as a string: the workspace id is a 19-digit Snowflake, so a Number()
  // coercion here would corrupt every non-default workspace id and the reloaded
  // value would match no workspace, silently snapping back to Default.
  const currentWorkspaceId = ref<string | null>(
    localStorage.getItem('mc-workspace-id') || null
  )
  const loading = ref(false)

  // RBAC: capabilities for the current workspace, sourced from the backend.
  // accessLoaded gates router guards so we never render a protected route on a
  // half-initialized store.
  const currentCapabilities = ref<Set<Capability>>(new Set())
  const accessLoaded = ref(false)
  let accessInFlight: Promise<void> | null = null

  const currentWorkspace = computed(() =>
    workspaces.value.find((ws) => ws.id === currentWorkspaceId.value) || workspaces.value[0] || null
  )

  const currentRole = computed<WorkspaceRole | null>(() => {
    const ws = currentWorkspace.value
    return (ws?.effectiveRole as WorkspaceRole) || null
  })

  const isGlobalAdmin = computed(() => Boolean(currentWorkspace.value?.isGlobalAdmin))

  function can(cap: Capability): boolean {
    return accessLoaded.value && currentCapabilities.value.has(cap)
  }

  function isAtLeast(role: WorkspaceRole): boolean {
    if (!currentRole.value) return false
    return ROLE_LEVEL[currentRole.value] >= ROLE_LEVEL[role]
  }

  async function refreshAccess() {
    const id = currentWorkspaceId.value
    if (id == null) {
      currentCapabilities.value = new Set()
      accessLoaded.value = true
      return
    }
    if (accessInFlight) return accessInFlight
    accessInFlight = (async () => {
      try {
        const res: any = await workspaceTeamApi.getAccess(id)
        const caps: string[] = res?.data?.capabilities || []
        currentCapabilities.value = new Set(caps as Capability[])
      } catch (e) {
        console.warn('Failed to fetch workspace access:', e)
        currentCapabilities.value = new Set()
      } finally {
        accessLoaded.value = true
        accessInFlight = null
      }
    })()
    return accessInFlight
  }

  async function fetchWorkspaces() {
    loading.value = true
    try {
      const res: any = await workspaceTeamApi.list()
      workspaces.value = res.data || []
      if (
        !currentWorkspaceId.value ||
        !workspaces.value.find((ws) => ws.id === currentWorkspaceId.value)
      ) {
        if (workspaces.value.length > 0) {
          await switchWorkspace(workspaces.value[0].id)
          return
        }
      }
      await refreshAccess()
    } catch (e) {
      console.warn('Failed to fetch workspaces:', e)
    } finally {
      loading.value = false
    }
  }

  async function switchWorkspace(id: string) {
    currentWorkspaceId.value = id
    localStorage.setItem('mc-workspace-id', id)
    accessLoaded.value = false
    currentCapabilities.value = new Set()
    await refreshAccess()
  }

  return {
    workspaces,
    currentWorkspaceId,
    currentWorkspace,
    currentRole,
    isGlobalAdmin,
    currentCapabilities,
    accessLoaded,
    loading,
    can,
    isAtLeast,
    fetchWorkspaces,
    switchWorkspace,
    refreshAccess,
  }
})

// Enable HMR for this store: editing it during `pnpm dev` patches the live
// store instead of requiring a full page reload. Stripped from prod builds.
if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useWorkspaceStore, import.meta.hot))
}
