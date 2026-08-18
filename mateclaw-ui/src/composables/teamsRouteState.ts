export type TeamsDetailView = 'runs' | 'board' | 'members'

export interface TeamsRouteState {
  teamId: string | null
  view: TeamsDetailView | null
  runId: string | null
  taskId: string | null
}

export interface TeamsRouteReconciliation {
  state: TeamsRouteState
  selectedRunId: string | null
  selectedTaskId: string | null
  taskAction: 'keep' | 'load' | 'close'
}

type QueryValue = string | Array<string | null> | null | undefined | number

function queryString(value: QueryValue): string | null {
  const candidate = Array.isArray(value) ? value[0] : value
  return typeof candidate === 'string' && candidate.length > 0 ? candidate : null
}

export function parseTeamsRouteQuery(query: Record<string, QueryValue>): TeamsRouteState {
  const teamId = queryString(query.teamId)
  const requestedView = queryString(query.view)
  const view = teamId
    ? (requestedView === 'board' || requestedView === 'members' ? requestedView : 'runs')
    : null
  return {
    teamId,
    view,
    runId: queryString(query.runId),
    taskId: queryString(query.taskId),
  }
}

export function buildTeamsRouteQuery(
  teamId: string,
  view: TeamsDetailView = 'runs',
  runId?: string | null,
  taskId?: string | null,
): Record<string, string> {
  const query: Record<string, string> = { teamId, view }
  if (runId) query.runId = runId
  if (taskId) query.taskId = taskId
  return query
}

export function clearTeamsRunSelection(state: TeamsRouteState): Record<string, string> {
  if (!state.teamId) return {}
  return buildTeamsRouteQuery(state.teamId, state.view ?? 'runs')
}

export function reconcileTeamsRoute(
  previous: TeamsRouteState | null,
  next: TeamsRouteState,
): TeamsRouteReconciliation {
  const runsView = next.view === 'runs'
  const selectedRunId = runsView ? next.runId : null
  const selectedTaskId = selectedRunId ? next.taskId : null
  const previousTaskId = previous?.view === 'runs' ? previous.taskId : null
  const previousRunId = previous?.view === 'runs' ? previous.runId : null
  let taskAction: TeamsRouteReconciliation['taskAction'] = 'keep'
  if (!selectedTaskId && previousTaskId) {
    taskAction = 'close'
  } else if (selectedTaskId && (selectedTaskId !== previousTaskId || selectedRunId !== previousRunId)) {
    taskAction = 'load'
  }
  return { state: next, selectedRunId, selectedTaskId, taskAction }
}
