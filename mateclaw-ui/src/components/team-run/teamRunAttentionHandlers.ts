import type { WorkspaceRole } from '@/composables/capabilities'

export type TeamAttentionAction = 'approve' | 'retry' | 'approve-tool' | 'deny-tool' | 'feedback'

export interface TeamAttentionActionContext {
  teamId: string
  runId: string
  taskId: string
}

export function captureTeamAttentionContext(
  run: { id: string | number; teamId: string | number; tasks: Array<{ id: string | number }> } | null,
  currentTeamId: string | number | null,
  taskId: string | number,
): TeamAttentionActionContext | null {
  if (!run || currentTeamId === null) return null
  const teamId = String(currentTeamId)
  const normalizedTaskId = String(taskId)
  if (String(run.teamId) !== teamId
    || !run.tasks.some(task => String(task.id) === normalizedTaskId)) return null
  return { teamId, runId: String(run.id), taskId: normalizedTaskId }
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
