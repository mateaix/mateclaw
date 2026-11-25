import { readFileSync } from 'node:fs'
import { createApp, computed, ref, nextTick } from 'vue'
import { expect, it, vi } from 'vitest'
import { useWorkerConversationGuard } from '../useWorkerConversationGuard'
import { useChatTeam } from '../useChatTeam'
import type { VerifiedWorkerContext } from '@/utils/conversationGovernance'
vi.mock('@/api', () => ({
  teamApi: {
    list: vi.fn(async () => ({
      data: [{ team: { id: '9', leadAgentId: '1' } }],
    })),
    get: vi.fn(async () => ({
      data: {
        team: {
          team: { id: '9', name: 'Team', leadAgentId: '1', settings: null },
        },
        members: [
          { agentId: '2', name: 'Member', enabled: true, role: 'member' },
        ],
      },
    })),
    listTasks: vi.fn(async () => ({ data: [] })),
  },
}))
vi.mock('@/composables/useTeamEvents', () => ({
  subscribeTeamEvents: () => () => {},
}))
it('wires the ChatConsole roster to the fail-closed conversation guard', () => {
  const source = readFileSync('src/views/ChatConsole.vue', 'utf8')
  expect(source).toContain(
    'computed(() => workerConversationReadOnly.value || !!workerRunContext.value)',
  )
})
it('clears the lead roster through pending, failed and verified worker conversations', async () => {
  const conversation = ref('normal'),
    hint = ref(false),
    agent = ref('1')
  let resolve!: (value: VerifiedWorkerContext | null) => void,
    reject!: (error: Error) => void
  let guard!: ReturnType<typeof useWorkerConversationGuard>,
    roster!: ReturnType<typeof useChatTeam>
  const app = createApp({
    setup() {
      guard = useWorkerConversationGuard({
        conversationId: conversation,
        workerHint: hint,
        load: (id) =>
          id === 'normal'
            ? Promise.resolve(null)
            : new Promise((res, rej) => {
                resolve = res
                reject = rej
              }),
      })
      roster = useChatTeam(
        agent,
        ref('workspace'),
        computed(() => guard.readOnly.value || !!guard.context.value),
      )
      return () => null
    },
  })
  app.mount(document.createElement('div'))
  try {
    await vi.waitFor(() => expect(roster.members.value).toHaveLength(1))
    conversation.value = 'failing'
    hint.value = true
    await nextTick()
    expect(guard.state.value).toBe('pending')
    expect(roster.members.value).toEqual([])
    reject(new Error('forbidden'))
    await nextTick()
    await nextTick()
    expect(guard.state.value).toBe('error')
    expect(roster.team.value).toBeNull()
    conversation.value = 'ordinary'
    hint.value = false
    await nextTick()
    resolve(null)
    await vi.waitFor(() => expect(roster.members.value).toHaveLength(1))
    conversation.value = 'worker'
    hint.value = true
    await nextTick()
    expect(roster.members.value).toEqual([])
    resolve({
      verified: true,
      conversationKind: 'team_worker',
      conversationId: 'worker',
      runId: '7',
      taskId: '8',
      teamId: '9',
      leadConversationId: 'lead',
      agentId: '2',
    })
    await nextTick()
    await nextTick()
    expect(guard.state.value).toBe('verified')
    expect(roster.members.value).toEqual([])
    expect(agent.value).toBe('1')
  } finally {
    app.unmount()
  }
})
