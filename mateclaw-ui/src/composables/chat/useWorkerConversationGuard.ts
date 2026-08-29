import { computed, ref, watch, type Ref } from 'vue'
import type { VerifiedWorkerContext } from '@/utils/conversationGovernance'

export type WorkerGuardState = 'pending' | 'verified' | 'nonWorker' | 'error'

export function useWorkerConversationGuard(options: {
  conversationId: Ref<string>
  workerHint: Ref<boolean>
  load: (conversationId: string) => Promise<VerifiedWorkerContext | null>
  /** Initial delay for retrying an ordinary conversation while the backend restarts. */
  retryDelayMs?: number
}) {
  const state = ref<WorkerGuardState>('pending')
  const context = ref<VerifiedWorkerContext | null>(null)
  let requestVersion = 0

  watch([options.conversationId, options.workerHint], ([conversationId, workerHint], _previous, onCleanup) => {
    const version = ++requestVersion
    let retryTimer: ReturnType<typeof setTimeout> | null = null
    let stopped = false
    onCleanup(() => {
      stopped = true
      if (retryTimer) clearTimeout(retryTimer)
    })
    state.value = 'pending'
    context.value = null
    if (!conversationId) {
      state.value = 'nonWorker'
      return
    }

    const verify = async (retryAttempt: number) => {
      try {
        const result = await options.load(conversationId)
        if (stopped || version !== requestVersion) return
        if (result?.verified && result.conversationKind === 'team_worker'
            && result.conversationId === conversationId) {
          context.value = result
          state.value = 'verified'
        } else {
          state.value = workerHint ? 'error' : 'nonWorker'
        }
      } catch {
        if (stopped || version !== requestVersion) return
        state.value = 'error'
        // A normal conversation loaded while the backend is restarting must
        // stay fail-closed, but it must not remain read-only forever. Worker
        // routes already have an explicit hint and need no availability retry.
        if (!workerHint) {
          const initialDelay = Math.max(1, options.retryDelayMs ?? 1000)
          const delay = Math.min(initialDelay * (2 ** retryAttempt), 10_000)
          retryTimer = setTimeout(() => {
            retryTimer = null
            void verify(retryAttempt + 1)
          }, delay)
        }
      }
    }

    void verify(0)
  }, { immediate: true })

  return {
    state,
    context,
    readOnly: computed(() => state.value !== 'nonWorker'),
  }
}
