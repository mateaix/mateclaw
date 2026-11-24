import { beforeEach, expect, it, vi } from 'vitest'
import { reactive, nextTick, effectScope } from 'vue'
const api = vi.hoisted(() => ({ list: vi.fn(), update: vi.fn(), create: vi.fn(), delete: vi.fn(), toggle: vi.fn(), test: vi.fn(), refresh: vi.fn(), setDisclosureTier: vi.fn() }))
const workspace = reactive({ currentWorkspaceId: 'A', isGlobalAdmin: false })
vi.mock('@/api/index', () => ({ mcpApi: api }))
vi.mock('@/stores/useWorkspaceStore', () => ({ useWorkspaceStore: () => workspace }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('@/composables/useMcToast', () => ({ mcToast: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }))
import { useMcpServers } from '../useMcpServers'
import { emptyMcpForm, type McpServer } from '@/views/mcp/types'
function deferred() { let resolve!: (value: any) => void; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
beforeEach(() => { vi.resetAllMocks(); workspace.currentWorkspaceId = 'A'; workspace.isGlobalAdmin = false; api.list.mockResolvedValue({ data: [] }) })
it('clears and reloads on workspace change and ignores the old response', async () => {
  const scope = effectScope(); const model = scope.run(useMcpServers)!; const pending = deferred(); api.list.mockReturnValueOnce(pending.promise)
  const loading = model.reload(); model.installed.value = [{ id: 'old' } as McpServer]
  workspace.currentWorkspaceId = 'B'; await nextTick(); await Promise.resolve()
  expect(model.installed.value).toEqual([]); expect(api.list).toHaveBeenCalledTimes(2)
  pending.resolve({ data: [{ id: 'stale' }] }); await loading
  expect(model.installed.value).toEqual([]); scope.stop()
})
it('prevents non administrators from mutating global servers', async () => {
  const scope = effectScope(); const model = scope.run(useMcpServers)!; const global = { id: '9223372036854775800', workspaceId: null } as McpServer
  expect(await model.saveServer(emptyMcpForm(), global)).toBe(false)
  expect(await model.removeServer(global)).toBe(false); await model.toggleServer(global)
  expect(api.update).not.toHaveBeenCalled(); expect(api.delete).not.toHaveBeenCalled(); expect(api.toggle).not.toHaveBeenCalled(); scope.stop()
})
it('does not close a newer workspace modal after an old save resolves', async () => {
  const scope = effectScope(); const model = scope.run(useMcpServers)!; const pending = deferred(); api.create.mockReturnValue(pending.promise)
  const saving = model.saveServer(emptyMcpForm(), null); workspace.currentWorkspaceId = 'B'; await nextTick()
  pending.resolve({}); expect(await saving).toBe(false); scope.stop()
})
it('passes explicit ownership separately and preserves Snowflake ids', async () => {
  workspace.isGlobalAdmin = true
  const scope = effectScope(); const model = scope.run(useMcpServers)!
  const server = { id: '9223372036854775800', workspaceId: null } as McpServer
  await model.saveServer({ ...emptyMcpForm(), global: false, enabled: false }, server)
  expect(api.update).toHaveBeenCalledWith(server.id, expect.not.objectContaining({ global: false }), false)
  scope.stop()
})
it('does not report delete completion after switching during its reload', async () => {
  const scope = effectScope(); const model = scope.run(useMcpServers)!
  const pending = deferred(); api.list.mockReturnValueOnce(pending.promise)
  const removing = model.removeServer({ id: '9223372036854775800', workspaceId: 'A' } as McpServer)
  await Promise.resolve(); workspace.currentWorkspaceId = 'B'
  pending.resolve({ data: [] }); expect(await removing).toBe(false); scope.stop()
})
it('guards global connection testing and administrator-only refresh', async () => {
  const scope = effectScope(); const model = scope.run(useMcpServers)!
  await model.testServer({ id: 'shared', workspaceId: null } as McpServer); await model.refreshAll()
  expect(api.test).not.toHaveBeenCalled(); expect(api.refresh).not.toHaveBeenCalled(); scope.stop()
})
