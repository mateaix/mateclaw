import { ref, watch, onBeforeUnmount, type Ref } from 'vue'
import {
  teamApi,
  type AgentTeam,
  type TeamMemberVO,
  type TeamTaskVO,
  type TeamVO,
} from '@/api'
import { subscribeTeamEvents } from '@/composables/useTeamEvents'
export interface ChatTeamMember extends TeamMemberVO {
  activity: 'busy' | 'idle' | 'unknown'
}
export function useChatTeam(
  agentId: Ref<string | number>,
  workspaceId: Ref<string | number>,
  worker: Ref<boolean>,
) {
  const team = ref<AgentTeam | null>(null),
    members = ref<ChatTeamMember[]>([]),
    coordinatorMode = ref(false)
  let generation = 0,
    stop: (() => void) | undefined,
    timer: ReturnType<typeof setInterval> | undefined
  let refreshCurrent = () => {}
  function cleanup() {
    stop?.()
    stop = undefined
    clearInterval(timer)
    window.removeEventListener('focus', refreshCurrent)
    document.removeEventListener('visibilitychange', refreshCurrent)
  }
  watch(
    [agentId, workspaceId, worker],
    ([agent, workspace, isWorker]) => {
      cleanup()
      const token = ++generation
      team.value = null
      members.value = []
      coordinatorMode.value = false
      if (!agent || !workspace || isWorker) return
      let running = false,
        pending = false,
        subscribedId = ''
      const valid = () => token === generation
      const load = async () => {
        if (!valid()) return
        if (running) {
          pending = true
          return
        }
        running = true
        try {
          const list = await teamApi.list()
          if (!valid()) return
          const found = (list.data as TeamVO[]).find(
            (v) => v.team.leadAgentId === String(agent),
          )
          if (!found) {
            team.value = null
            members.value = []
            stop?.()
            stop = undefined
            subscribedId = ''
            return
          }
          const detail = await teamApi.get(found.team.id)
          if (!valid()) return
          const data = detail.data as { team: TeamVO; members: TeamMemberVO[] }
          if (data.team.team.leadAgentId !== String(agent)) {
            team.value = null
            members.value = []
            return
          }
          let tasks: TeamTaskVO[] | null = null
          try {
            tasks = (
              await teamApi.listTasks(found.team.id, [
                'in_progress',
                'awaiting_approval',
              ])
            ).data
          } catch {}
          if (!valid()) return
          team.value = data.team.team
          try {
            coordinatorMode.value =
              JSON.parse(team.value.settings || '{}')?.coordinatorMode === true
          } catch {
            coordinatorMode.value = false
          }
          members.value = data.members
            .filter((m) => m.agentId !== String(agent) && m.role !== 'lead')
            .map((m) => ({
              ...m,
              activity:
                tasks === null
                  ? 'unknown'
                  : tasks.some(
                        ({ task }) =>
                          ['in_progress', 'awaiting_approval'].includes(
                            task.status,
                          ) &&
                          (task.assigneeAgentId === m.agentId ||
                            task.ownerAgentId === m.agentId),
                      )
                    ? 'busy'
                    : 'idle',
            }))
          if (subscribedId !== found.team.id) {
            stop?.()
            subscribedId = found.team.id
            stop = subscribeTeamEvents(subscribedId, () => void load())
          }
        } catch {
          if (valid()) {
            team.value = null
            members.value = []
            coordinatorMode.value = false
          }
        } finally {
          running = false
          if (pending && valid()) {
            pending = false
            void load()
          }
        }
      }
      refreshCurrent = () => {
        if (document.visibilityState !== 'hidden') void load()
      }
      window.addEventListener('focus', refreshCurrent)
      document.addEventListener('visibilitychange', refreshCurrent)
      timer = setInterval(refreshCurrent, 15000)
      void load()
    },
    { immediate: true, flush: 'sync' },
  )
  onBeforeUnmount(() => {
    generation++
    cleanup()
  })
  return { team, members, coordinatorMode }
}
