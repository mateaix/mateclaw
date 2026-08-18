import type { WorkspaceRole } from '@/composables/capabilities'

export type TeamAttentionAction = 'approve' | 'retry'

export interface TeamAttentionActionContext {
  teamId: string
  runId: string
  taskId: string
}

export function canManageTeamRunAttention(
  role: WorkspaceRole | null,
  currentWorkspaceId: string | null,
  runWorkspaceId: string | null,
) {
  return (role === 'admin' || role === 'owner')
    && currentWorkspaceId !== null
    && currentWorkspaceId === runWorkspaceId
}

export function attentionActionKey(context: TeamAttentionActionContext, action: TeamAttentionAction) {
  return `${context.teamId}:${context.runId}:${context.taskId}:${action}`
}

export async function runAttentionTaskAction(options: {
  context: TeamAttentionActionContext
  action: TeamAttentionAction
  pending: Set<string>
  execute: () => Promise<unknown>
  refresh: () => Promise<unknown>
  onError: (cause: unknown) => void
}) {
  const key = attentionActionKey(options.context, options.action)
  if (options.pending.has(key)) return false
  options.pending.add(key)
  try {
    await options.execute()
    await options.refresh()
    return true
  } catch (cause) {
    options.onError(cause)
    return false
  } finally {
    options.pending.delete(key)
  }
}

export async function refreshAttentionTaskContext(options: {
  context: TeamAttentionActionContext
  currentTeamId: () => string | null
  currentTaskId: () => string | null
  reloadTask: () => Promise<unknown>
  refreshBoard: (teamId: string) => Promise<unknown>
  refreshRun: (runId: string, teamId: string) => Promise<unknown>
}) {
  if (options.currentTeamId() !== options.context.teamId) return
  const operations: Promise<unknown>[] = [
    options.refreshBoard(options.context.teamId),
    options.refreshRun(options.context.runId, options.context.teamId),
  ]
  if (options.currentTaskId() === options.context.taskId) operations.push(options.reloadTask())
  await Promise.all(operations)
}
