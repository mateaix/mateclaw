import { describe, expect, it, vi } from 'vitest'
import {
  canManageTeamRunAttention,
  refreshAttentionTaskContext,
  runAttentionTaskAction,
  type TeamAttentionActionContext,
} from '../teamRunAttentionHandlers'

const context: TeamAttentionActionContext = { teamId: '10', runId: '20', taskId: '101' }

describe('Team Run attention handlers', () => {
  it('allows only backend-issued workspace admin roles for the matching workspace', () => {
    expect(canManageTeamRunAttention('viewer', '1', '1')).toBe(false)
    expect(canManageTeamRunAttention('member', '1', '1')).toBe(false)
    expect(canManageTeamRunAttention('admin', '1', '1')).toBe(true)
    expect(canManageTeamRunAttention('owner', '1', '1')).toBe(true)
    expect(canManageTeamRunAttention('admin', '2', '1')).toBe(false)
  })

  it('refreshes the captured original run when another run becomes selected', async () => {
    const refreshRun = vi.fn().mockResolvedValue(undefined)
    await refreshAttentionTaskContext({
      context,
      currentTeamId: () => '10',
      currentTaskId: () => '101',
      reloadTask: vi.fn().mockResolvedValue(undefined),
      refreshBoard: vi.fn().mockResolvedValue(undefined),
      refreshRun,
    })
    expect(refreshRun).toHaveBeenCalledWith('20', '10')
  })

  it('does not refresh or mutate a newly selected team', async () => {
    const reloadTask = vi.fn()
    const refreshBoard = vi.fn()
    const refreshRun = vi.fn()
    await refreshAttentionTaskContext({
      context,
      currentTeamId: () => '11',
      currentTaskId: () => '101',
      reloadTask,
      refreshBoard,
      refreshRun,
    })
    expect(reloadTask).not.toHaveBeenCalled()
    expect(refreshBoard).not.toHaveBeenCalled()
    expect(refreshRun).not.toHaveBeenCalled()
  })

  it('locks each task action against double click and releases it after failure', async () => {
    const pending = new Set<string>()
    let reject!: (reason: Error) => void
    const execute = vi.fn(() => new Promise<void>((_, rejectPromise) => { reject = rejectPromise }))
    const refresh = vi.fn()
    const onError = vi.fn()
    const first = runAttentionTaskAction({ context, action: 'retry', pending, execute, refresh, onError })
    const duplicate = runAttentionTaskAction({ context, action: 'retry', pending, execute, refresh, onError })
    expect(execute).toHaveBeenCalledOnce()
    expect(pending.size).toBe(1)
    await duplicate
    reject(new Error('offline'))
    await first
    expect(refresh).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledOnce()
    expect(pending.size).toBe(0)
  })

  it('keeps the captured context and skips UI refresh when the team switches during the action', async () => {
    let currentTeamId = '10'
    let resolve!: () => void
    const execute = vi.fn(() => new Promise<void>(resolvePromise => { resolve = resolvePromise }))
    const refreshBoard = vi.fn().mockResolvedValue(undefined)
    const refreshRun = vi.fn().mockResolvedValue(undefined)
    const refresh = () => refreshAttentionTaskContext({
      context,
      currentTeamId: () => currentTeamId,
      currentTaskId: () => null,
      reloadTask: vi.fn(),
      refreshBoard,
      refreshRun,
    })

    const action = runAttentionTaskAction({
      context, action: 'approve', pending: new Set(), execute, refresh, onError: vi.fn(),
    })
    currentTeamId = '11'
    resolve()
    await action

    expect(execute).toHaveBeenCalledOnce()
    expect(refreshBoard).not.toHaveBeenCalled()
    expect(refreshRun).not.toHaveBeenCalled()
  })
})
