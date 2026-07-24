import { acceptHMRUpdate, defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { teamApi } from '@/api/index'
import type { TeamMemberVO, TeamTaskComment, TeamTaskVO, TeamVO } from '@/api/index'

/**
 * Agent-team domain state: team list, the currently opened team (members +
 * task board), and board polling. Ids stay strings for their entire lifecycle
 * (Snowflake precision convention).
 */
export const useTeamStore = defineStore('team', () => {
  const teams = ref<TeamVO[]>([])
  const loading = ref(false)

  const currentTeam = ref<TeamVO | null>(null)
  const members = ref<TeamMemberVO[]>([])
  const tasks = ref<TeamTaskVO[]>([])
  const boardLoading = ref(false)

  /** Statuses that mean the board is still moving and worth polling. */
  const ACTIVE_STATUSES = ['pending', 'in_progress', 'in_review', 'blocked']

  const hasActiveTasks = computed(() =>
    tasks.value.some((t) => ACTIVE_STATUSES.includes(t.task.status)),
  )

  async function fetchTeams() {
    loading.value = true
    try {
      const res: any = await teamApi.list()
      teams.value = res.data || []
    } catch (e) {
      console.error('Failed to fetch teams', e)
    } finally {
      loading.value = false
    }
  }

  async function openTeam(teamId: string) {
    const res: any = await teamApi.get(teamId)
    currentTeam.value = res.data?.team || null
    members.value = res.data?.members || []
    await fetchTasks(teamId)
  }

  function closeTeam() {
    currentTeam.value = null
    members.value = []
    tasks.value = []
  }

  async function fetchTasks(teamId: string) {
    boardLoading.value = true
    try {
      const res: any = await teamApi.listTasks(teamId)
      tasks.value = res.data || []
    } catch (e) {
      console.error('Failed to fetch team tasks', e)
    } finally {
      boardLoading.value = false
    }
  }

  async function createTeam(data: {
    name: string
    description?: string
    leadAgentId: string
    memberAgentIds: string[]
  }) {
    await teamApi.create(data)
    await fetchTeams()
  }

  async function deleteTeam(teamId: string) {
    await teamApi.delete(teamId)
    if (currentTeam.value?.team.id === teamId) {
      closeTeam()
    }
    await fetchTeams()
  }

  return {
    teams,
    loading,
    currentTeam,
    members,
    tasks,
    boardLoading,
    hasActiveTasks,
    fetchTeams,
    openTeam,
    closeTeam,
    fetchTasks,
    createTeam,
    deleteTeam,
  }
})

if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useTeamStore, import.meta.hot))
}

export type { TeamMemberVO, TeamTaskComment, TeamTaskVO, TeamVO }
