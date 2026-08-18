import { computed, ref, watch, type Ref } from 'vue'
import type { VerifiedWorkerContext } from '@/utils/conversationGovernance'

export type WorkerGuardState = 'pending' | 'verified' | 'nonWorker' | 'error'

export function useWorkerConversationGuard(options: {
  conversationId: Ref<string>
  workerHint: Ref<boolean>
  load: (conversationId: string) => Promise<VerifiedWorkerContext | null>
}) {
  const state = ref<WorkerGuardState>('pending')
  const context = ref<VerifiedWorkerContext | null>(null)
  let requestVersion = 0

  watch([options.conversationId, options.workerHint], async ([conversationId, workerHint]) => {
    const version = ++requestVersion
    state.value = 'pending'
    context.value = null
    if (!conversationId) {
      state.value = 'nonWorker'
      return
    }
    try {
      const result = await options.load(conversationId)
      if (version !== requestVersion) return
      if (result?.verified && result.conversationKind === 'team_worker'
          && result.conversationId === conversationId) {
        context.value = result
        state.value = 'verified'
      } else {
        state.value = workerHint ? 'error' : 'nonWorker'
      }
    } catch {
      if (version !== requestVersion) return
      state.value = 'error'
    }
  }, { immediate: true })

  return {
    state,
    context,
    readOnly: computed(() => state.value !== 'nonWorker'),
  }
}
