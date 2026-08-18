import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { LiveSnapshot, TeamRun } from '@/api'

export type AgentsView = 'roster' | 'live' | 'plans'

export interface AgentsLiveRouteState {
  view: AgentsView
  runId: string | null
  taskId: string | null
  requiredRunId: string | null
}

export interface AgentsLiveSelection {
  selectedRunId: string | null
  selectedTaskId: string | null
  selectedWorker: { taskId: string; conversationId: string | null; online: boolean } | null
  replaceQuery: LocationQueryRaw | null
}

function id(value: unknown): string | null {
  return typeof value === 'string' && value ? value : null
}

export function parseAgentsLiveRoute(query: LocationQuery | Record<string, unknown>): AgentsLiveRouteState {
  const view = query.view === 'live' || query.view === 'plans' ? query.view : 'roster'
  const runId = view === 'live' ? id(query.teamRunId) : null
  const taskId = runId ? id(query.taskId) : null
  return { view, runId, taskId, requiredRunId: runId }
}

export function reconcileAgentsLiveRoute(route: AgentsLiveRouteState, runs: readonly TeamRun[], snapshot: LiveSnapshot | null): AgentsLiveSelection {
  const empty = { selectedRunId: null, selectedTaskId: null, selectedWorker: null }
  if (route.view !== 'live' || !route.runId) return { ...empty, replaceQuery: null }
  const run = runs.find(item => item.id === route.runId)
  if (!run) return { ...empty, replaceQuery: { view: 'live' } }
  if (!route.taskId) return { selectedRunId: run.id, selectedTaskId: null, selectedWorker: null, replaceQuery: null }
  const task = run.tasks.find(item => item.id === route.taskId)
  if (!task) {
    return { selectedRunId: run.id, selectedTaskId: null, selectedWorker: null, replaceQuery: { view: 'live', teamRunId: run.id } }
  }
  const online = task.conversationId != null && (snapshot?.runs ?? []).some(item => item.conversationId === task.conversationId)
  return {
    selectedRunId: run.id,
    selectedTaskId: task.id,
    selectedWorker: { taskId: task.id, conversationId: task.conversationId, online },
    replaceQuery: null,
  }
}

interface HydratorDependencies {
  invalidatePoll: () => void
  ensureRun: (runId: string | null, routeRevision: number) => Promise<unknown>
  reconcile: (route: AgentsLiveRouteState) => AgentsLiveSelection
  replace: (query: LocationQueryRaw) => Promise<unknown>
}

export function createAgentsLiveRouteHydrator(dependencies: HydratorDependencies) {
  let revision = 0

  async function hydrate(route: AgentsLiveRouteState) {
    const expectedRevision = ++revision
    dependencies.invalidatePoll()
    await dependencies.ensureRun(route.view === 'live' ? route.requiredRunId : null, expectedRevision)
    if (expectedRevision !== revision) return false
    if (route.view !== 'live') return true
    const correction = dependencies.reconcile(route).replaceQuery
    if (correction) await dependencies.replace(correction)
    return expectedRevision === revision
  }

  return { hydrate }
}
