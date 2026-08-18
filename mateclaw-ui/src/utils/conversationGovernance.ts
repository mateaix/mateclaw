import type { Conversation } from '@/types'

export type ConversationKind = 'primary' | 'team_worker' | 'scheduled'

export interface VerifiedWorkerContext {
  verified: boolean
  conversationKind: 'team_worker'
  conversationId: string
  runId: string
  taskId: string
  teamId: string
  leadConversationId?: string
  agentId?: string
}

export function isSidebarConversation(conversation: Pick<Conversation, 'conversationId' | 'conversationKind'>): boolean {
  if (conversation.conversationKind === 'team_worker') return false
  return conversation.conversationKind != null || !conversation.conversationId.startsWith('team-task-')
}

export function isVerifiedWorkerContext(
  context: VerifiedWorkerContext | null,
  conversationId: string,
): context is VerifiedWorkerContext {
  return context?.verified === true
    && context.conversationKind === 'team_worker'
    && context.conversationId === conversationId
}
