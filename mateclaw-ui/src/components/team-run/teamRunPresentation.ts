import type { TeamRun, TeamRunStatus, TeamRunTask } from '@/api'
import { isSafeFileUrl } from '@/utils/generatedFileLinks'

export type TeamRunTone = 'neutral' | 'green' | 'amber' | 'red'

export interface TeamRunStatusPresentation {
  labelKey: `teamRuns.status.${TeamRunStatus}`
  tone: TeamRunTone
}
export interface DurationUnits {
  day: string
  hour: string
  minute: string
  second: string
}

export interface TeamRunDeliverable {
  name: string
  url: string
  time?: string
  taskId?: string
}

export interface TeamRunRoute {
  path: '/chat' | '/agents' | '/teams'
  query: Record<string, string>
}

const statusTones: Record<TeamRunStatus, TeamRunTone> = {
  planning: 'neutral',
  running: 'green',
  awaiting_review: 'amber',
  finalizing: 'green',
  completed: 'green',
  partial: 'amber',
  failed: 'red',
  cancelled: 'neutral',
}

const defaultDurationUnits: DurationUnits = {
  day: 'd',
  hour: 'h',
  minute: 'm',
  second: 's',
}

export function getRunStatusPresentation(status: TeamRunStatus): TeamRunStatusPresentation {
  return { labelKey: `teamRuns.status.${status}`, tone: statusTones[status] }
}

export function formatRunDuration(
  startedAt: string | null,
  completedAt: string | null,
  now = new Date(),
  units: DurationUnits = defaultDurationUnits,
): string {
  if (!startedAt) return ''
  const start = Date.parse(startedAt)
  const end = completedAt ? Date.parse(completedAt) : now.getTime()
  if (!Number.isFinite(start) || !Number.isFinite(end)) return ''

  let remaining = Math.max(0, Math.floor((end - start) / 1_000))
  const values = [
    [Math.floor(remaining / 86_400), units.day],
    [Math.floor((remaining %= 86_400) / 3_600), units.hour],
    [Math.floor((remaining %= 3_600) / 60), units.minute],
    [remaining % 60, units.second],
  ] as const
  const visible = values.filter(([value]) => value > 0).slice(0, 2)
  if (visible.length === 0) return `0${units.second}`
  return visible.map(([value, unit]) => `${value}${unit}`).join(' ')
}

function parseMetadata(raw: unknown): Record<string, unknown> {
  let value = raw
  for (let depth = 0; depth < 2 && typeof value === 'string'; depth += 1) {
    if (!value.trim()) return {}
    try {
      value = JSON.parse(value)
    } catch {
      return {}
    }
  }
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

function deliverablesFrom(raw: unknown, taskId?: string): TeamRunDeliverable[] {
  const entries = parseMetadata(raw).deliverables
  if (!Array.isArray(entries)) return []
  return entries.flatMap((entry) => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return []
    const item = entry as Record<string, unknown>
    const name = typeof item.name === 'string' ? item.name.trim() : ''
    const url = typeof item.url === 'string' ? item.url.trim() : ''
    if (!name || !isSafeFileUrl(url)) return []
    return [{
      name,
      url,
      time: typeof item.time === 'string' && item.time.trim() ? item.time : undefined,
      taskId,
    }]
  })
}

export function extractRunDeliverables(run: TeamRun): TeamRunDeliverable[] {
  const all = [
    ...deliverablesFrom(run.metadata),
    ...run.tasks.flatMap(task => deliverablesFrom(task.metadata, task.id)),
  ]
  const seen = new Set<string>()
  return all.filter((item) => {
    const key = `${item.url}\u0000${item.name}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

export function taskDependencyIds(task: TeamRunTask): string[] {
  const metadata = parseMetadata(task.metadata)
  const raw = task.blockedBy ?? metadata.blockedBy
  let value: unknown = raw
  if (typeof value === 'string') {
    try {
      value = JSON.parse(value)
    } catch {
      return []
    }
  }
  if (!Array.isArray(value)) return []
  return value.filter((id): id is string => typeof id === 'string' && id.trim().length > 0)
}

export function orderTasksByDependencies<T extends TeamRunTask>(tasks: readonly T[]): T[] {
  const byId = new Map(tasks.map(task => [task.id, task]))
  const index = new Map(tasks.map((task, position) => [task.id, position]))
  const indegree = new Map(tasks.map(task => [task.id, 0]))
  const dependents = new Map(tasks.map(task => [task.id, [] as string[]]))

  for (const task of tasks) {
    const dependencies = [...new Set(taskDependencyIds(task))].filter(id => id !== task.id && byId.has(id))
    indegree.set(task.id, dependencies.length)
    dependencies.forEach(id => dependents.get(id)!.push(task.id))
  }

  const ready = tasks.filter(task => indegree.get(task.id) === 0)
  const ordered: T[] = []
  while (ready.length > 0) {
    ready.sort((a, b) => index.get(a.id)! - index.get(b.id)!)
    const task = ready.shift()!
    ordered.push(task)
    for (const dependentId of dependents.get(task.id)!) {
      const next = indegree.get(dependentId)! - 1
      indegree.set(dependentId, next)
      if (next === 0) ready.push(byId.get(dependentId)!)
    }
  }

  if (ordered.length < tasks.length) {
    const emitted = new Set(ordered.map(task => task.id))
    ordered.push(...tasks.filter(task => !emitted.has(task.id)))
  }
  return ordered
}

function stringId(value: string, name: string): string {
  if (typeof value !== 'string' || !value.trim()) {
    throw new TypeError(`${name} must be a non-empty string`)
  }
  return value
}

export function buildChatRunRoute(runId: string, conversationId: string): TeamRunRoute {
  return {
    path: '/chat',
    query: {
      conversationId: stringId(conversationId, 'conversationId'),
      teamRunId: stringId(runId, 'runId'),
    },
  }
}

export function buildWorkerChatRoute(context: {
  conversationId: string
  agentId?: string | null
  runId?: string | null
  taskId: string
  teamId: string
  leadConversationId?: string | null
}): TeamRunRoute {
  const query: Record<string, string> = {
    conversationId: stringId(context.conversationId, 'conversationId'),
    taskId: stringId(context.taskId, 'taskId'),
    teamId: stringId(context.teamId, 'teamId'),
  }
  if (context.agentId) query.agentId = stringId(context.agentId, 'agentId')
  if (context.runId) query.teamRunId = stringId(context.runId, 'runId')
  if (context.leadConversationId) {
    query.leadConversationId = stringId(context.leadConversationId, 'leadConversationId')
  }
  return { path: '/chat', query }
}

export function buildAgentRunRoute(runId: string, taskId?: string): TeamRunRoute {
  const query: Record<string, string> = { view: 'live', teamRunId: stringId(runId, 'runId') }
  if (taskId !== undefined) query.taskId = stringId(taskId, 'taskId')
  return { path: '/agents', query }
}

export function buildTeamRunRoute(teamId: string, runId: string, taskId?: string): TeamRunRoute {
  const query: Record<string, string> = {
    teamId: stringId(teamId, 'teamId'),
    view: 'runs',
    runId: stringId(runId, 'runId'),
  }
  if (taskId !== undefined) query.taskId = stringId(taskId, 'taskId')
  return { path: '/teams', query }
}
