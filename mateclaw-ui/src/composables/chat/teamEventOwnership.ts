export type TeamEventOwner = 'run' | 'task' | 'unowned'

export interface StructuredTeamEvent {
  id?: string
  event: string
  data: Record<string, unknown>
}

export interface TeamEventOwnershipContext {
  runIds?: ReadonlySet<string>
  taskKeys?: ReadonlySet<string>
  conversationIds?: ReadonlySet<string>
}

const ACTION_EVENT_SUFFIXES = new Set([
  'failed',
  'blocked',
  'in_review',
  'review_requested',
  'approval_required',
  'rejected',
  'stale',
])

function stringId(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
}

function actionId(event: StructuredTeamEvent): string | null {
  return stringId(event.data.actionId) ?? stringId(event.data.parentActionId)
}

function conversationId(event: StructuredTeamEvent): string | null {
  return stringId(event.data.conversationId)
    ?? stringId(event.data.leadConversationId)
    ?? stringId(event.data.workerConversationId)
}

function normalizedEventType(value: string): string {
  return value.trim().toLowerCase().replace(/[\s-]+/g, '_')
}

export function classifyTeamEventOwnership(
  event: StructuredTeamEvent,
  context: TeamEventOwnershipContext = {},
): TeamEventOwner {
  const runId = stringId(event.data.runId)
  const conversation = conversationId(event)
  const hasStableEvidence = Boolean(actionId(event)
    || stringId(event.data.eventId)
    || conversation)
  if (runId && context.runIds && !context.runIds.has(runId)) return 'unowned'
  if (conversation && context.conversationIds && !context.conversationIds.has(conversation)) {
    return 'unowned'
  }
  if (event.event === 'team_run' || event.event.startsWith('team_run_')
    || event.event === 'team_announce' || event.event.startsWith('team_announce_')) {
    return runId || hasStableEvidence ? 'run' : 'unowned'
  }
  if (event.event.startsWith('team_task_')) {
    const taskId = stringId(event.data.taskId)
    if (taskId && runId && context.taskKeys && !context.taskKeys.has(`${runId}:${taskId}`)) return 'unowned'
    return (taskId && runId) || hasStableEvidence ? 'task' : 'unowned'
  }
  return 'unowned'
}

export function canonicalTeamEventKey(event: StructuredTeamEvent): string | null {
  const conversation = conversationId(event)
  const runId = stringId(event.data.runId)
  const taskId = stringId(event.data.taskId)
  const runScope = runId ? taskId ? `run=${runId}|task=${taskId}` : `run=${runId}` : null
  const scope = runScope ?? (conversation ? `conversation=${conversation}` : null)
  const action = actionId(event)
  if (action) {
    const eventType = normalizedEventType(event.event)
    if (scope) return `action:${scope}:${eventType}:${action}`
    const streamId = stringId(event.id)
    return streamId ? `action:stream=${streamId}:${eventType}:${action}` : null
  }
  const payloadEventId = stringId(event.data.eventId)
  if (payloadEventId) return scope ? `event:${scope}:${payloadEventId}` : `event:${payloadEventId}`

  const streamId = stringId(event.id)
  if (!streamId) return null
  if (scope) return `stream:${scope}:${streamId}`
  return streamId ? `stream:${streamId}` : null
}

export function discoveredTeamTaskKey(
  event: StructuredTeamEvent,
  knownRunIds: ReadonlySet<string>,
): string | null {
  if (!event.event.startsWith('team_task_')) return null
  const suffix = event.event.slice('team_task_'.length)
  if (ACTION_EVENT_SUFFIXES.has(suffix)) return null
  const runId = stringId(event.data.runId)
  const taskId = stringId(event.data.taskId)
  return runId && taskId && knownRunIds.has(runId) ? `${runId}:${taskId}` : null
}

export function shouldShowInGlobalTeamFeed(
  event: StructuredTeamEvent,
  context: TeamEventOwnershipContext = {},
): boolean {
  if (classifyTeamEventOwnership(event, context) !== 'task') return true
  const suffix = event.event.slice('team_task_'.length)
  return ACTION_EVENT_SUFFIXES.has(suffix)
}
