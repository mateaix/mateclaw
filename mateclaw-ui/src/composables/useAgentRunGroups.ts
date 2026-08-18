import { computed, getCurrentInstance, onBeforeUnmount, ref, type Ref } from 'vue'
import { teamApi, teamRunApi, type LiveRunCard, type LiveSnapshot, type TeamRun, type TeamRunTask } from '@/api'
import { buildWorkerChatRoute, type TeamRunRoute } from '@/components/team-run/teamRunPresentation'

export type AgentWorkerState = 'active' | 'waiting' | 'review' | 'stuck' | 'cancelled' | 'completed' | 'failed'
export type AgentRunState = 'active' | 'waiting' | 'review' | 'stuck' | 'finalizing' | 'cancelled' | 'completed' | 'failed'

export interface AgentRunWorker {
  task: TeamRunTask
  runtime: LiveRunCard | null
  state: AgentWorkerState
}

export interface AgentRunGroup {
  run: TeamRun
  state: AgentRunState
  leadRuntime: LiveRunCard | null
  workers: AgentRunWorker[]
}

export interface AgentRunProjection {
  groups: AgentRunGroup[]
  ungrouped: LiveRunCard[]
}

export function buildAgentWorkerChatRoute(group: AgentRunGroup, worker: AgentRunWorker): TeamRunRoute | null {
  if (!worker.task.conversationId) return null
  return buildWorkerChatRoute({
    conversationId: worker.task.conversationId,
    agentId: worker.task.assigneeAgentId,
    runId: group.run.id,
    taskId: worker.task.id,
    teamId: group.run.teamId,
    leadConversationId: group.run.leadConversationId,
  })
}

function workerState(task: TeamRunTask, runtime: LiveRunCard | null): AgentWorkerState {
  if (runtime?.stuckReason) return 'stuck'
  if (task.status === 'in_review') return 'review'
  if (task.status === 'blocked' || task.status === 'pending') return 'waiting'
  if (task.status === 'cancelled' || task.status === 'stale') return 'cancelled'
  if (task.status === 'completed') return 'completed'
  if (task.status === 'failed') return 'failed'
  return runtime ? 'active' : 'waiting'
}

function runState(run: TeamRun, workers: AgentRunWorker[]): AgentRunState {
  if (workers.some(worker => worker.state === 'stuck')) return 'stuck'
  if (run.status === 'finalizing') return 'finalizing'
  if (run.status === 'cancelled') return 'cancelled'
  if (run.status === 'completed') return 'completed'
  if (run.status === 'failed') return 'failed'
  if (run.status === 'awaiting_review' || workers.some(worker => worker.state === 'review')) return 'review'
  if (workers.length > 0 && workers.every(worker => ['waiting', 'completed', 'cancelled'].includes(worker.state))) return 'waiting'
  return run.liveness?.state === 'live' || workers.some(worker => worker.state === 'active') ? 'active' : 'waiting'
}

export function projectAgentRunGroups(snapshot: LiveSnapshot | null, runs: readonly TeamRun[]): AgentRunProjection {
  const liveRuns = snapshot?.runs ?? []
  const liveByConversation = new Map(liveRuns.map(run => [run.conversationId, run]))
  const claimed = new Set<string>()
  const activeStatuses = new Set<TeamRun['status']>(['planning', 'running', 'awaiting_review', 'finalizing'])
  const groups = runs.filter(run => activeStatuses.has(run.status)).map((run) => {
    const leadRuntime = liveByConversation.get(run.leadConversationId) ?? null
    if (leadRuntime) claimed.add(leadRuntime.conversationId)
    const workers = run.tasks.map((task) => {
      const runtime = task.conversationId ? liveByConversation.get(task.conversationId) ?? null : null
      if (runtime) claimed.add(runtime.conversationId)
      return { task, runtime, state: workerState(task, runtime) }
    })
    return { run, leadRuntime, workers, state: runState(run, workers) }
  })
  return { groups, ungrouped: liveRuns.filter(run => !claimed.has(run.conversationId)) }
}

function relevantRuns(runs: TeamRun[], snapshot: LiveSnapshot | null): TeamRun[] {
  const liveIds = new Set((snapshot?.runs ?? []).map(run => run.conversationId))
  const activeStatuses = new Set(['planning', 'running', 'awaiting_review', 'finalizing'])
  return runs.filter(run => activeStatuses.has(run.status)
    || liveIds.has(run.leadConversationId)
    || run.tasks.some(task => task.conversationId != null && liveIds.has(task.conversationId)))
}

async function mapConcurrent<T, R>(
  items: readonly T[],
  limit: number,
  load: (item: T) => Promise<R>,
): Promise<R[]> {
  const results = new Array<R>(items.length)
  let cursor = 0
  let stopped = false
  let hasError = false
  let firstError: unknown
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (!stopped && cursor < items.length) {
      const index = cursor++
      try {
        results[index] = await load(items[index])
      } catch (cause) {
        if (!hasError) {
          hasError = true
          firstError = cause
        }
        stopped = true
      }
    }
  }))
  if (hasError) throw firstError
  return results
}

export function useAgentRunGroups(snapshot: Ref<LiveSnapshot | null>) {
  const listedRuns = ref<TeamRun[]>([])
  const ensuredRun = ref<TeamRun | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  let listSequence = 0
  let routeRevision = 0
  let closed = false
  let refreshPromise: Promise<void> | null = null

  async function loadActiveTeamRuns(teamId: string): Promise<TeamRun[]> {
    const runs: TeamRun[] = []
    const seenCursors = new Set<string>()
    let cursor: string | undefined
    do {
      const response: any = await teamRunApi.listByTeamPage(teamId, {
        activeOnly: true,
        ...(cursor ? { cursor } : {}),
        limit: 50,
      })
      const payload = response?.data
      runs.push(...(Array.isArray(payload?.items) ? payload.items : []))
      const nextCursor = typeof payload?.nextCursor === 'string' && payload.nextCursor.length > 0
        ? payload.nextCursor
        : undefined
      if (nextCursor && seenCursors.has(nextCursor)) {
        throw new Error(`Repeated team run cursor for team ${teamId}: ${nextCursor}`)
      }
      if (nextCursor) seenCursors.add(nextCursor)
      cursor = nextCursor
    } while (cursor)
    return runs
  }

  async function runRefresh(request: number) {
    try {
      const teamsResponse: any = await teamApi.list()
      const teams = teamsResponse?.data ?? []
      if (closed || request !== listSequence) return
      const teamRuns = await mapConcurrent(
        teams,
        3,
        (entry: any) => loadActiveTeamRuns(String(entry.team.id)),
      )
      if (closed || request !== listSequence) return
      const allRuns = teamRuns.flat()
      const relevant = relevantRuns(allRuns, snapshot.value)
      if (closed || request !== listSequence) return
      listedRuns.value = relevant
      error.value = null
    } catch (cause) {
      if (!closed && request === listSequence) error.value = cause instanceof Error ? cause.message : String(cause)
      throw cause
    } finally {
      if (!closed && request === listSequence) loading.value = false
    }
  }

  function refreshForSnapshot(): Promise<void> {
    if (refreshPromise) return refreshPromise
    const request = ++listSequence
    loading.value = true
    let shared: Promise<void>
    shared = runRefresh(request).finally(() => {
      if (refreshPromise === shared) refreshPromise = null
    })
    refreshPromise = shared
    return shared
  }

  async function ensureRun(runId: string | null, expectedRouteRevision: number) {
    routeRevision = Math.max(routeRevision, expectedRouteRevision)
    if (!runId) {
      ensuredRun.value = null
      return
    }
    const [teamsResponse, runResponse]: any[] = await Promise.all([teamApi.list(), teamRunApi.get(runId)])
    if (closed || expectedRouteRevision !== routeRevision) return
    const workspaceTeamIds = new Set((teamsResponse?.data ?? []).map((entry: any) => String(entry.team.id)))
    const candidate = runResponse?.data as TeamRun
    ensuredRun.value = candidate && workspaceTeamIds.has(candidate.teamId) ? candidate : null
  }

  function close() {
    closed = true
    listSequence++
    routeRevision++
    listedRuns.value = []
    ensuredRun.value = null
    loading.value = false
    error.value = null
  }

  if (getCurrentInstance()) onBeforeUnmount(close)
  const runs = computed(() => {
    if (!ensuredRun.value) return listedRuns.value
    const withoutEnsured = listedRuns.value.filter(run => run.id !== ensuredRun.value?.id)
    return [...withoutEnsured, ensuredRun.value]
  })
  const projection = computed(() => projectAgentRunGroups(snapshot.value, runs.value))
  return { runs, loading, error, projection, refreshForSnapshot, ensureRun, close }
}
