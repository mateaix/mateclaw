import type { TeamRun } from '@/api'
import type { Message } from '@/types'
import { classifyTeamEventOwnership } from './teamEventOwnership'

const TEAM_RUN_TYPES = new Set([
  'team_run',
  'team_run_start',
  'team_run_started',
  'team_run_sealed',
  'team_run_protocol',
])

export interface ParsedTeamMessageMetadata {
  type?: string
  runId?: string
  taskId?: string
  originMessageId?: string
  teamId?: string
  leadConversationId?: string
  actionId?: string
  conversationId?: string
  eventId?: string
  isTeamRunProtocol: boolean
  isTeamAnnounce: boolean
  isLegacyTeamAnnounce: boolean
}

export interface WorkerRunContext {
  runId: string
  taskId: string
  teamId?: string
  leadConversationId?: string
  source: 'route' | 'metadata' | 'projection'
}

export function isConversationReadOnly(context: WorkerRunContext | null): boolean {
  return context !== null
}

function parseObject(value: unknown): Record<string, unknown> {
  let current = value
  for (let depth = 0; depth < 2 && typeof current === 'string'; depth += 1) {
    try {
      current = JSON.parse(current)
    } catch {
      return {}
    }
  }
  return current !== null && typeof current === 'object' && !Array.isArray(current)
    ? current as Record<string, unknown>
    : {}
}

function stringId(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

export function parseTeamMessageMetadata(message: Message): ParsedTeamMessageMetadata {
  const metadata = parseObject(message.metadata)
  const type = typeof metadata.type === 'string' ? metadata.type : undefined
  const runId = stringId(metadata.runId)
  const explicitAnnounce = type === 'team_announce' || type === 'team_announce_reply'
  const isLegacyTeamAnnounce = !runId
    && message.role === 'user'
    && typeof message.content === 'string'
    && message.content.startsWith('[System Message] ')

  return {
    type,
    runId,
    taskId: stringId(metadata.taskId),
    originMessageId: stringId(metadata.originMessageId),
    teamId: stringId(metadata.teamId),
    leadConversationId: stringId(metadata.leadConversationId),
    actionId: stringId(metadata.actionId) ?? stringId(metadata.parentActionId),
    conversationId: stringId(metadata.conversationId) ?? stringId(message.conversationId),
    eventId: stringId(metadata.eventId),
    isTeamRunProtocol: Boolean(type && (TEAM_RUN_TYPES.has(type) || type.startsWith('team_run_'))),
    isTeamAnnounce: explicitAnnounce || isLegacyTeamAnnounce,
    isLegacyTeamAnnounce,
  }
}

export function isTeamRunBookkeeping(message: Message, runId: string): boolean {
  const metadata = parseTeamMessageMetadata(message)
  if (metadata.runId !== runId) return false
  if (!metadata.type) return false
  return classifyTeamEventOwnership({
    id: metadata.eventId,
    event: metadata.type,
    data: {
      runId: metadata.runId,
      taskId: metadata.taskId,
      actionId: metadata.actionId,
      conversationId: metadata.conversationId,
      eventId: metadata.eventId,
    },
  }) !== 'unowned'
}

export function resolveWorkerRunContext(input: {
  messages: Message[]
  runs: TeamRun[]
  conversationId: string
  routeRunId?: string
  routeTaskId?: string
}): WorkerRunContext | null {
  const { messages, runs, conversationId, routeRunId, routeTaskId } = input
  const projectedContext = (
    runId: string,
    taskId: string,
    source: WorkerRunContext['source'],
  ): WorkerRunContext | null => {
    const run = runs.find(candidate => candidate.id === runId)
    const task = run?.tasks.find(candidate =>
      candidate.id === taskId && candidate.conversationId === conversationId)
    if (!run || !task) return null
    return {
      runId: run.id,
      taskId: task.id,
      teamId: run.teamId,
      leadConversationId: run.leadConversationId,
      source,
    }
  }

  if (routeRunId && routeTaskId) {
    return projectedContext(routeRunId, routeTaskId, 'route')
  }

  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const metadata = parseTeamMessageMetadata(messages[index])
    if (metadata.runId && metadata.taskId
        && !metadata.isTeamAnnounce && !metadata.isTeamRunProtocol) {
      const context = projectedContext(metadata.runId, metadata.taskId, 'metadata')
      if (context) return context
    }
  }

  for (const run of runs) {
    const task = run.tasks.find(task => task.conversationId === conversationId)
    if (task) {
      return projectedContext(run.id, task.id, 'projection')
    }
  }
  return null
}
