type IdLike = string | number

interface RouteHydrationAgent {
  id: IdLike
}

interface RouteHydrationConversation {
  conversationId: string
}

export function resolveRouteHydrationQuery(options: {
  routeAgentId?: string
  routeConversationId?: string
  agents: RouteHydrationAgent[]
  conversations: RouteHydrationConversation[]
}): { agentId: string; conversationId: string } {
  let agentId = options.routeAgentId || ''
  const conversationId = options.routeConversationId || ''

  if (agentId && options.agents.length > 0 && !options.agents.some(a => String(a.id) === agentId)) {
    agentId = ''
  }

  return { agentId, conversationId }
}

export function resolveConversationAgentSelection(options: {
  routeAgentId?: string
  conversationAgentId?: IdLike | null
  currentAgentId?: IdLike | null
}): string {
  if (options.routeAgentId) return options.routeAgentId
  if (options.conversationAgentId != null) return String(options.conversationAgentId)
  if (options.currentAgentId != null) return String(options.currentAgentId)
  return ''
}

export function readTeamRunRouteQuery(query: Record<string, unknown>): {
  teamRunId?: string
  taskId?: string
  teamId?: string
  leadConversationId?: string
} {
  const teamRunId = typeof query.teamRunId === 'string' && query.teamRunId ? query.teamRunId : undefined
  const taskId = typeof query.taskId === 'string' && query.taskId ? query.taskId : undefined
  const teamId = typeof query.teamId === 'string' && query.teamId ? query.teamId : undefined
  const leadConversationId = typeof query.leadConversationId === 'string' && query.leadConversationId
    ? query.leadConversationId
    : undefined
  return {
    ...(teamRunId ? { teamRunId } : {}),
    ...(taskId ? { taskId } : {}),
    ...(teamId ? { teamId } : {}),
    ...(leadConversationId ? { leadConversationId } : {}),
  }
}

export function readLegacyWorkerRouteContext(
  conversationId: string,
  query: Record<string, unknown>,
): {
  runId: string
  taskId: string
  teamId: string
  leadConversationId?: string
} | null {
  if (!conversationId.startsWith('team-task-')) return null
  const route = readTeamRunRouteQuery(query)
  if (!route.teamRunId || !route.taskId || !route.teamId) return null
  return {
    runId: route.teamRunId,
    taskId: route.taskId,
    teamId: route.teamId,
    ...(route.leadConversationId ? { leadConversationId: route.leadConversationId } : {}),
  }
}

export function buildChatRouteQuery(options: {
  currentQuery: Record<string, unknown>
  agentId?: string
  conversationId?: string
}): Record<string, string> {
  const currentConversationId = typeof options.currentQuery.conversationId === 'string'
    ? options.currentQuery.conversationId
    : undefined
  const preserveRunQuery = !options.conversationId
    || !currentConversationId
    || currentConversationId === options.conversationId
  const runQuery = preserveRunQuery ? readTeamRunRouteQuery(options.currentQuery) : {}
  return {
    ...(options.agentId ? { agentId: options.agentId } : {}),
    ...(options.conversationId ? { conversationId: options.conversationId } : {}),
    ...runQuery,
  }
}
