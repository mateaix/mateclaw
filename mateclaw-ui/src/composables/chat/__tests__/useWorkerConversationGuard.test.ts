import { describe, expect, it } from 'vitest'
import { nextTick, ref } from 'vue'
import { useWorkerConversationGuard } from '../useWorkerConversationGuard'
import type { VerifiedWorkerContext } from '@/utils/conversationGovernance'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

const worker = (conversationId: string): VerifiedWorkerContext => ({
  verified: true,
  conversationKind: 'team_worker',
  conversationId,
  runId: '77', taskId: '501', teamId: '20', leadConversationId: 'lead', agentId: '41',
})

describe('useWorkerConversationGuard', () => {
  it('fails closed while a worker-looking route is pending and after 403/500', async () => {
    const conversationId = ref('worker')
    const workerHint = ref(true)
    const pending = deferred<VerifiedWorkerContext | null>()
    const guard = useWorkerConversationGuard({
      conversationId,
      workerHint,
      load: id => id === 'worker'
        ? pending.promise
        : Promise.reject(Object.assign(new Error('server error'), { status: 500 })),
    })

    expect(guard.state.value).toBe('pending')
    expect(guard.readOnly.value).toBe(true)
    pending.reject(Object.assign(new Error('forbidden'), { status: 403 }))
    await nextTick(); await Promise.resolve()
    expect(guard.state.value).toBe('error')
    expect(guard.readOnly.value).toBe(true)

    conversationId.value = 'worker-500'
    await nextTick(); await Promise.resolve()
    expect(guard.state.value).toBe('error')
    expect(guard.readOnly.value).toBe(true)
  })

  it('ignores an old verified response after switching quickly to a non-worker', async () => {
    const conversationId = ref('old-worker')
    const workerHint = ref(true)
    const oldRequest = deferred<VerifiedWorkerContext | null>()
    const newRequest = deferred<VerifiedWorkerContext | null>()
    const guard = useWorkerConversationGuard({
      conversationId,
      workerHint,
      load: id => id === 'old-worker' ? oldRequest.promise : newRequest.promise,
    })

    conversationId.value = 'ordinary'
    workerHint.value = false
    await nextTick()
    newRequest.resolve(null)
    await nextTick(); await Promise.resolve()
    expect(guard.state.value).toBe('nonWorker')
    expect(guard.readOnly.value).toBe(false)

    oldRequest.resolve(worker('old-worker'))
    await nextTick(); await Promise.resolve()
    expect(guard.state.value).toBe('nonWorker')
    expect(guard.context.value).toBeNull()
  })

  it('keeps a confirmed worker read-only and only explicit nonWorker writable', async () => {
    const conversationId = ref('worker')
    const guard = useWorkerConversationGuard({
      conversationId,
      workerHint: ref(false),
      load: async id => worker(id),
    })
    await nextTick(); await Promise.resolve()
    expect(guard.state.value).toBe('verified')
    expect(guard.readOnly.value).toBe(true)
  })

  it('fails closed when a worker-looking route has no verified context', async () => {
    const guard = useWorkerConversationGuard({
      conversationId: ref('team-task-legacy'),
      workerHint: ref(true),
      load: async () => null,
    })

    await nextTick(); await Promise.resolve()
    expect(guard.state.value).toBe('error')
    expect(guard.readOnly.value).toBe(true)
  })
})
