import { createApp, ref, nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { useChatTeam } from '../useChatTeam'
vi.mock('@/api', () => ({
  teamApi: { list: vi.fn(), get: vi.fn(), listTasks: vi.fn() },
}))
vi.mock('@/composables/useTeamEvents', () => ({
  subscribeTeamEvents: vi.fn(() => vi.fn()),
}))
import { teamApi } from '@/api'
let app: ReturnType<typeof createApp>
afterEach(() => app?.unmount())
it('scopes lead members, separate enabled and busy, clears on workspace changes', async () => {
  vi.mocked(teamApi.list).mockResolvedValue({
    data: [{ team: { id: '9', leadAgentId: '1' } }],
  } as any)
  vi.mocked(teamApi.get).mockResolvedValue({
    data: {
      team: {
        team: {
          id: '9',
          leadAgentId: '1',
          name: 'Team',
          settings: '{"coordinatorEnabled":true}',
        },
      },
      members: [
        { agentId: '1', role: 'lead' },
        {
          agentId: '9007199254740993',
          name: 'Jane Doe',
          role: 'member',
          enabled: false,
        },
      ],
    },
  } as any)
  vi.mocked(teamApi.listTasks).mockResolvedValue({
    data: [
      { task: { status: 'in_progress', assigneeAgentId: '9007199254740993' } },
    ],
  } as any)
  const workspace = ref('a'),
    agent = ref('1')
  let state: any
  app = createApp({
    setup() {
      state = useChatTeam(agent, workspace, ref(false))
      return () => null
    },
  })
  app.mount(document.createElement('div'))
  await vi.waitFor(() => expect(state.members.value).toHaveLength(1))
  expect(state.members.value[0]).toMatchObject({
    enabled: false,
    activity: 'busy',
    agentId: '9007199254740993',
  })
  workspace.value = 'b'
  await nextTick()
  expect(state.members.value).toEqual([])
})
it('ignores delayed prior workspaces and suppresses worker records', async () => {
  let resolveOld!: (v: any) => void
  vi.mocked(teamApi.list)
    .mockImplementationOnce(
      () => new Promise((resolve) => (resolveOld = resolve)),
    )
    .mockResolvedValue({ data: [] } as any)
  const agent = ref('1'),
    workspace = ref('old'),
    worker = ref(false)
  let state: any
  app = createApp({
    setup() {
      state = useChatTeam(agent, workspace, worker)
      return () => null
    },
  })
  app.mount(document.createElement('div'))
  workspace.value = 'new'
  await nextTick()
  resolveOld({ data: [{ team: { id: 'old', leadAgentId: '1' } }] })
  await nextTick()
  await nextTick()
  expect(state.team.value).toBeNull()
  expect(state.members.value).toEqual([])
  worker.value = true
  expect(state.members.value).toEqual([])
})
it('reports task lookup failures as unknown and discovers membership changes on focus', async () => {
  vi.mocked(teamApi.list).mockResolvedValue({
    data: [{ team: { id: '9', leadAgentId: '1' } }],
  } as any)
  const details = (name: string) => ({
    data: {
      team: {
        team: { id: '9', leadAgentId: '1', name: 'Team', settings: 'bad' },
      },
      members: [{ agentId: '2', name, role: 'member', enabled: null }],
    },
  })
  vi.mocked(teamApi.get).mockResolvedValue(details('First') as any)
  vi.mocked(teamApi.listTasks).mockRejectedValue(new Error('offline'))
  let state: any
  app = createApp({
    setup() {
      state = useChatTeam(ref('1'), ref('a'), ref(false))
      return () => null
    },
  })
  app.mount(document.createElement('div'))
  await vi.waitFor(() => expect(state.members.value[0]?.name).toBe('First'))
  expect(state.members.value[0]).toMatchObject({
    enabled: null,
    activity: 'unknown',
  })
  expect(state.coordinatorMode.value).toBe(false)
  vi.mocked(teamApi.get).mockResolvedValue(details('New member') as any)
  window.dispatchEvent(new Event('focus'))
  await vi.waitFor(() =>
    expect(state.members.value[0]?.name).toBe('New member'),
  )
  vi.mocked(teamApi.list).mockRejectedValue(new Error('offline'))
  window.dispatchEvent(new Event('focus'))
  await vi.waitFor(() => expect(state.members.value).toEqual([]))
})
