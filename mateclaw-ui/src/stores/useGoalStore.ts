import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref } from 'vue'
import { goalApi, type Goal, type GoalEvent } from '@/api/index'

/**
 * Per-conversation active goal cache + SSE event handlers.
 *
 * The visible affordances are the ring on the assistant avatar
 * (driven by `activeGoalByConv[cid]`) and the inline "set goal" prompt
 * that appears after the first assistant reply. There is no banner,
 * no modal dialog, no drawer in the chat view. A separate /goals admin
 * page shows the full timeline.
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

  // Per-conversation "the user said 'No thanks' to setting a goal" — keeps
  // GoalSetInlinePrompt from re-appearing every turn. In-memory only
  // (intentionally not persisted: a fresh session re-prompts so the user
  // can change their mind on the next visit).
  const dismissedPromptByConv = ref<Record<string, boolean>>({})

  // Per-conversation snapshot of the most recent terminal event
  // (completed / exhausted). ChatConsole reads this to render the
  // GoalSystemLine in the message stream. Cleared when the user dismisses
  // it or starts a new goal on the same conversation.
  const recentTerminalByConv = ref<Record<string, {
    status: 'completed' | 'exhausted'
    title: string
    score?: number | null
    reason?: string
    at: number
  } | null>>({})

  // Per-conversation flag: "the goal evaluator just chose to inject a
  // followup prompt, and the next assistant message that opens belongs
  // to that followup turn." Consumed (cleared) by the chat composable's
  // `message_start` handler so the message gets stamped exactly once.
  const pendingFollowupByConv = ref<Record<string, boolean>>({})

  // Assistant message IDs that came from auto-followup turns, grouped by
  // conversation. MessageBubble reads this to show the small ↻ glyph on
  // the avatar — the only visible signal that a turn was auto-triggered.
  // Kept in memory only; on refetch the metadata persists server-side via
  // the message's `metadata.fromFollowup` flag (handled by ChatHistory).
  const followupMessageIdsByConv = ref<Record<string, Set<string>>>({})

  const loading = ref(false)

  async function loadActiveForConversation(conversationId: string) {
    if (!conversationId) return null
    loading.value = true
    try {
      const res: any = await goalApi.findActive(conversationId)
        // mateclaw axios interceptor returns the R envelope itself:
      // { code, msg, data: Goal | null }. The goal lives at .data; when
      // the conversation has no active goal, .data is null (NOT undefined,
      // so `?? res` would clobber it with the envelope — that was the bug).
      const goal: Goal | null = res?.data ?? null
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
      const goal: Goal = res?.data
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
      activeGoalByConv.value[goal.conversationId] = res?.data
    } catch (e) {
      console.error('[goal] pause failed', e)
    }
  }

  async function resume(goal: Goal) {
    try {
      const res: any = await goalApi.resume(goal.id)
      activeGoalByConv.value[goal.conversationId] = res?.data
    } catch (e) {
      console.error('[goal] resume failed', e)
    }
  }

  async function loadEvents(goalId: string) {
    try {
      const res: any = await goalApi.events(goalId)
      const events: GoalEvent[] = res?.data ?? []
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
        // The next assistant turn will land soon. Flag the conversation
        // so the chat composable can stamp the upcoming message as a
        // followup turn when its `message_start` arrives. The ring keeps
        // its evaluating state until message_complete fires for that
        // followup turn — so the user sees breathe → still → breathe.
        pendingFollowupByConv.value[conversationId] = true
        break
      }
      case 'goal_completed': {
        evaluatingByConv.value[conversationId] = false
        // Capture the terminal snapshot BEFORE we null out the cache so
        // the GoalSystemLine has a title + score to render.
        recentTerminalByConv.value[conversationId] = {
          status: 'completed',
          title: goal?.title || '目标',
          score: data?.score != null ? Number(data.score) : (goal?.completionScore ?? null),
          at: Date.now(),
        }
        // Clear active so the inline "set goal" prompt can come back.
        activeGoalByConv.value[conversationId] = null
        // Re-enable the inline prompt on completion (the user just closed
        // a goal; a follow-up task is plausible).
        dismissedPromptByConv.value[conversationId] = false
        break
      }
      case 'goal_exhausted': {
        evaluatingByConv.value[conversationId] = false
        recentTerminalByConv.value[conversationId] = {
          status: 'exhausted',
          title: goal?.title || '目标',
          reason: data?.reason,
          at: Date.now(),
        }
        activeGoalByConv.value[conversationId] = null
        dismissedPromptByConv.value[conversationId] = false
        break
      }
      case 'goal_created':
      case 'goal_updated': {
        // Tool-side mutation from GoalManagementTool. Payload carries the
        // full goal snapshot so we can hydrate the cache without a GET.
        // Falls back to a fetch when the payload shape is unexpected so
        // future server-side changes don't silently degrade the UX.
        const fresh = data?.goal as Goal | undefined
        if (fresh && typeof fresh.id === 'string') {
          activeGoalByConv.value[conversationId] = fresh
        } else {
          // Best-effort refetch — runs async; don't await inside the
          // synchronous SSE handler.
          void loadActiveForConversation(conversationId)
        }
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

  // ==================== Inline prompt + system line helpers ====================

  function isPromptDismissed(conversationId: string): boolean {
    return Boolean(dismissedPromptByConv.value[conversationId])
  }

  function dismissPrompt(conversationId: string) {
    dismissedPromptByConv.value[conversationId] = true
  }

  function clearDismissedPrompt(conversationId: string) {
    dismissedPromptByConv.value[conversationId] = false
  }

  function recentTerminal(conversationId: string) {
    return recentTerminalByConv.value[conversationId] ?? null
  }

  function clearRecentTerminal(conversationId: string) {
    recentTerminalByConv.value[conversationId] = null
  }

  // ==================== Followup attribution helpers ====================

  /**
   * Consume the pending-followup flag for this conversation if it's
   * set, returning true when the caller should stamp the just-opened
   * assistant message as a followup turn. Idempotent — calling twice
   * returns false the second time.
   */
  function consumePendingFollowup(conversationId: string): boolean {
    if (!conversationId) return false
    const pending = pendingFollowupByConv.value[conversationId]
    if (pending) {
      pendingFollowupByConv.value[conversationId] = false
      return true
    }
    return false
  }

  function markFollowupMessage(conversationId: string, messageId: string) {
    if (!conversationId || !messageId) return
    let set = followupMessageIdsByConv.value[conversationId]
    if (!set) {
      set = new Set<string>()
      followupMessageIdsByConv.value[conversationId] = set
    }
    set.add(messageId)
  }

  function isFollowupMessage(conversationId: string, messageId: string): boolean {
    if (!conversationId || !messageId) return false
    return followupMessageIdsByConv.value[conversationId]?.has(messageId) ?? false
  }

  return {
    activeGoalByConv,
    evaluatingByConv,
    eventsByGoal,
    dismissedPromptByConv,
    recentTerminalByConv,
    pendingFollowupByConv,
    followupMessageIdsByConv,
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
    isPromptDismissed,
    dismissPrompt,
    clearDismissedPrompt,
    recentTerminal,
    clearRecentTerminal,
    consumePendingFollowup,
    markFollowupMessage,
    isFollowupMessage,
  }
})

if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useGoalStore, import.meta.hot))
}
