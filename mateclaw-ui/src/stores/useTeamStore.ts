import { acceptHMRUpdate, defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { teamApi } from '@/api/index'
import type { TeamMemberVO, TeamTaskComment, TeamTaskVO, TeamVO } from '@/api/index'

/**
 * Agent-team domain state: team list, the currently opened team (members +
 * task board), and board polling. Ids stay strings for their entire lifecycle
 * (Snowflake precision convention).
 *
 * Board loading strategy: active statuses are always fetched in full (they
 * are bounded by the team's working set), while the ever-growing terminal
 * columns (completed / closed) are windowed newest-first and extended with
 * load-more. Column headers show true totals from the stats endpoint.
 */
export const useTeamStore = defineStore('team', () => {
  const teams = ref<TeamVO[]>([])
  const loading = ref(false)

  const currentTeam = ref<TeamVO | null>(null)
  const members = ref<TeamMemberVO[]>([])
  const boardLoading = ref(false)
  /** Null = aggregate history; otherwise the board is scoped to one run. */
  const taskRunId = ref<string | null>(null)

  /** Statuses that mean the board is still moving and worth polling. */
  const ACTIVE_STATUSES = ['pending', 'in_progress', 'in_review', 'blocked']
  const COMPLETED_STATUSES = ['completed']
  const CLOSED_STATUSES = ['failed', 'cancelled', 'stale']
  /** Terminal-column page size. */
  const TERMINAL_PAGE = 20

  const activeTasks = ref<TeamTaskVO[]>([])
  const completedTasks = ref<TeamTaskVO[]>([])
  const closedTasks = ref<TeamTaskVO[]>([])
  /** True per-status totals from the stats endpoint. */
  const taskStats = ref<Record<string, number>>({})
  let teamGeneration = 0
  let boardRequestSequence = 0

  /** Merged view consumed by the board's status-filtered columns. */
  const tasks = computed<TeamTaskVO[]>(() => [
    ...activeTasks.value,
    ...completedTasks.value,
    ...closedTasks.value,
  ])

  const hasActiveTasks = computed(() => activeTasks.value.length > 0)

  const completedTotal = computed(() => sumStats(COMPLETED_STATUSES))
  const closedTotal = computed(() => sumStats(CLOSED_STATUSES))
  const completedHasMore = computed(() => completedTasks.value.length < completedTotal.value)
  const closedHasMore = computed(() => closedTasks.value.length < closedTotal.value)

  function sumStats(statuses: string[]): number {
    return statuses.reduce((sum, s) => sum + (Number(taskStats.value[s]) || 0), 0)
  }

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
    const generation = ++teamGeneration
    const res: any = await teamApi.get(teamId)
    if (generation !== teamGeneration) return
    currentTeam.value = res.data?.team || null
    members.value = res.data?.members || []
    activeTasks.value = []
    completedTasks.value = []
    closedTasks.value = []
    taskStats.value = {}
    taskRunId.value = null
    await fetchTasks(teamId, generation)
  }

  function closeTeam() {
    teamGeneration++
    boardRequestSequence++
    currentTeam.value = null
    members.value = []
    activeTasks.value = []
    completedTasks.value = []
    closedTasks.value = []
    taskStats.value = {}
    taskRunId.value = null
    boardLoading.value = false
  }

  /**
   * Refresh the board. Terminal windows keep (at least) their currently
   * loaded size, so a poll/event refresh never collapses a column the user
   * has extended with load-more.
   */
  async function fetchTasks(teamId: string, expectedGeneration = teamGeneration,
                            retryOnTimeout = true) {
    const requestSequence = ++boardRequestSequence
    boardLoading.value = true
    try {
      const completedLimit = Math.max(TERMINAL_PAGE, completedTasks.value.length)
      const closedLimit = Math.max(TERMINAL_PAGE, closedTasks.value.length)
      const [active, completed, closed, stats] = (await Promise.all([
        teamApi.listTasks(teamId, ACTIVE_STATUSES, { runId: taskRunId.value ?? undefined }),
        teamApi.listTasks(teamId, COMPLETED_STATUSES, { limit: completedLimit, offset: 0, runId: taskRunId.value ?? undefined }),
        teamApi.listTasks(teamId, CLOSED_STATUSES, { limit: closedLimit, offset: 0, runId: taskRunId.value ?? undefined }),
        teamApi.taskStats(teamId, taskRunId.value ?? undefined),
      ])) as any[]
      if (expectedGeneration !== teamGeneration
        || requestSequence !== boardRequestSequence
        || String(currentTeam.value?.team.id ?? '') !== teamId) return
      activeTasks.value = active.data || []
      completedTasks.value = completed.data || []
      closedTasks.value = closed.data || []
      taskStats.value = stats.data || {}
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      const timedOut = (e as { code?: string } | null)?.code === 'ECONNABORTED'
        || /timeout/i.test(message)
      if (retryOnTimeout
        && timedOut
        && expectedGeneration === teamGeneration
        && requestSequence === boardRequestSequence
        && String(currentTeam.value?.team.id ?? '') === teamId) {
        await fetchTasks(teamId, expectedGeneration, false)
        return
      }
      if (expectedGeneration === teamGeneration && requestSequence === boardRequestSequence) {
        console.error('Failed to fetch team tasks', e)
      }
    } finally {
      if (expectedGeneration === teamGeneration && requestSequence === boardRequestSequence) {
        boardLoading.value = false
      }
    }
  }

  async function loadMoreCompleted(teamId: string) {
    const generation = teamGeneration
    const res: any = await teamApi.listTasks(teamId, COMPLETED_STATUSES, {
      limit: TERMINAL_PAGE,
      offset: completedTasks.value.length,
      runId: taskRunId.value ?? undefined,
    })
    if (generation !== teamGeneration || String(currentTeam.value?.team.id ?? '') !== teamId) return
    completedTasks.value = [...completedTasks.value, ...(res.data || [])]
  }

  async function loadMoreClosed(teamId: string) {
    const generation = teamGeneration
    const res: any = await teamApi.listTasks(teamId, CLOSED_STATUSES, {
      limit: TERMINAL_PAGE,
      offset: closedTasks.value.length,
      runId: taskRunId.value ?? undefined,
    })
    if (generation !== teamGeneration || String(currentTeam.value?.team.id ?? '') !== teamId) return
    closedTasks.value = [...closedTasks.value, ...(res.data || [])]
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

  async function setTaskRunId(teamId: string, runId: string | null) {
    if (taskRunId.value === runId) {
      await fetchTasks(teamId)
      return
    }
    taskRunId.value = runId
    activeTasks.value = []
    completedTasks.value = []
    closedTasks.value = []
    taskStats.value = {}
    await fetchTasks(teamId)
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
    taskStats,
    boardLoading,
    taskRunId,
    hasActiveTasks,
    completedTotal,
    closedTotal,
    completedHasMore,
    closedHasMore,
    fetchTeams,
    openTeam,
    closeTeam,
    fetchTasks,
    loadMoreCompleted,
    loadMoreClosed,
    setTaskRunId,
    createTeam,
    deleteTeam,
  }
})

if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useTeamStore, import.meta.hot))
}

export type { TeamMemberVO, TeamTaskComment, TeamTaskVO, TeamVO }
