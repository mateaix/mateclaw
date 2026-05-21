import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref } from 'vue'
import { goalApi, type Goal, type GoalEvent } from '@/api/index'

/**
 * Per-conversation active goal cache + SSE event handlers.
 *
 * RFC 48 v3 Jobs-cut UI: the only visible affordances are the ring on the
 * assistant avatar (driven by `activeGoalByConv[cid]`) and the inline
 * "set goal" prompt that appears after the first assistant reply. There
 * is no banner, no modal dialog, no drawer in the chat view. A separate
 * /goals admin page (PR4b, optional) shows the full timeline.
 */
export const useGoalStore = defineStore('goal', () => {
  // Map of conversationId -> active goal (or null).
  const activeGoalByConv = ref<Record<string, Goal | null>>({})

  // Map of conversationId -> "evaluating right now" flag, used by the
  // avatar's breathing-halo CSS class. Set briefly by the SSE handler
  // between goal_evaluated/completed/exhausted and the next idle tick.
  const evaluatingByConv = ref<Record<string, boolean>>({})

  // Cached event timelines, keyed by goalId.
  const eventsByGoal = ref<Record<string, GoalEvent[]>>({})

  const loading = ref(false)

  async function loadActiveForConversation(conversationId: string) {
    if (!conversationId) return null
    loading.value = true
    try {
      const res: any = await goalApi.findActive(conversationId)
      const goal: Goal | null = res?.data ?? res ?? null
      activeGoalByConv.value[conversationId] = goal
      return goal
    } catch (e) {
      console.error('[goal] loadActiveForConversation failed', e)
      return null
    } finally {
      loading.value = false
    }
  }

  async function create(
    conversationId: string,
    agentId: string,
    workspaceId: string,
    title: string,
    opts: { description?: string; exitCriteria?: string; autoFollowup?: boolean } = {},
  ): Promise<Goal | null> {
    try {
      const res: any = await goalApi.create({
        conversationId,
        agentId,
        workspaceId,
        title,
        description: opts.description,
        exitCriteria: opts.exitCriteria,
        autoFollowupEnabled: opts.autoFollowup,
      })
      const goal: Goal = res?.data ?? res
      activeGoalByConv.value[conversationId] = goal
      return goal
    } catch (e) {
      console.error('[goal] create failed', e)
      return null
    }
  }

  async function abandon(goal: Goal) {
    try {
      await goalApi.abandon(goal.id)
      activeGoalByConv.value[goal.conversationId] = null
    } catch (e) {
      console.error('[goal] abandon failed', e)
    }
  }

  async function pause(goal: Goal) {
    try {
      const res: any = await goalApi.pause(goal.id)
      activeGoalByConv.value[goal.conversationId] = res?.data ?? res
    } catch (e) {
      console.error('[goal] pause failed', e)
    }
  }

  async function resume(goal: Goal) {
    try {
      const res: any = await goalApi.resume(goal.id)
      activeGoalByConv.value[goal.conversationId] = res?.data ?? res
    } catch (e) {
      console.error('[goal] resume failed', e)
    }
  }

  async function loadEvents(goalId: string) {
    try {
      const res: any = await goalApi.events(goalId)
      const events: GoalEvent[] = res?.data ?? res ?? []
      eventsByGoal.value[goalId] = events
      return events
    } catch (e) {
      console.error('[goal] loadEvents failed', e)
      return []
    }
  }

  /**
   * Handle one Goal-namespaced SSE event from the chat stream.
   *
   * Called from the chat-stream composable when an event with type
   * `goal_evaluated` / `goal_followup` / `goal_completed` / `goal_exhausted`
   * arrives. Updates the local active-goal snapshot + the evaluating-flag
   * map so the avatar ring re-paints without a refetch.
   */
  function handleSseEvent(conversationId: string, eventType: string, data: any) {
    if (!conversationId) return
    const goal = activeGoalByConv.value[conversationId]

    switch (eventType) {
      case 'goal_evaluated': {
        evaluatingByConv.value[conversationId] = false
        if (goal && data?.score != null) {
          goal.completionScore = Number(data.score)
        }
        if (goal && typeof data?.gap === 'string') {
          goal.progressSummary = data.gap
        }
        break
      }
      case 'goal_followup': {
        // The next assistant turn will land soon; nothing to do for the ring.
        break
      }
      case 'goal_completed': {
        evaluatingByConv.value[conversationId] = false
        if (goal) {
          goal.status = 'completed'
          if (data?.score != null) goal.completionScore = Number(data.score)
        }
        // Refresh active so the empty-state prompt comes back into view.
        activeGoalByConv.value[conversationId] = null
        break
      }
      case 'goal_exhausted': {
        evaluatingByConv.value[conversationId] = false
        if (goal) {
          goal.status = 'exhausted'
        }
        activeGoalByConv.value[conversationId] = null
        break
      }
      default:
        // Not a goal event — caller filters by prefix, this is a safety net.
        break
    }
  }

  function markEvaluating(conversationId: string, flag: boolean) {
    evaluatingByConv.value[conversationId] = flag
  }

  function isEvaluating(conversationId: string): boolean {
    return Boolean(evaluatingByConv.value[conversationId])
  }

  function activeGoal(conversationId: string): Goal | null {
    return activeGoalByConv.value[conversationId] ?? null
  }

  function progressFraction(conversationId: string): number | null {
    const g = activeGoal(conversationId)
    if (!g || g.completionScore == null) return null
    return Math.max(0, Math.min(1, g.completionScore))
  }

  return {
    activeGoalByConv,
    evaluatingByConv,
    eventsByGoal,
    loading,
    loadActiveForConversation,
    create,
    abandon,
    pause,
    resume,
    loadEvents,
    handleSseEvent,
    markEvaluating,
    isEvaluating,
    activeGoal,
    progressFraction,
  }
})

if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useGoalStore, import.meta.hot))
}
