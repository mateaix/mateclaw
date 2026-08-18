import { describe, expect, it } from 'vitest'
import { isSidebarConversation, isVerifiedWorkerContext } from '@/utils/conversationGovernance'

describe('conversation governance', () => {
  it('excludes explicit and legacy workers but keeps ordinary lookalikes', () => {
    expect(isSidebarConversation({ conversationId: 'worker', conversationKind: 'team_worker' })).toBe(false)
    expect(isSidebarConversation({ conversationId: 'team-task-legacy' })).toBe(false)
    expect(isSidebarConversation({ conversationId: 'ordinary-team-task-note' })).toBe(true)
  })

  it('accepts read-only mode only from a verified server context matching the conversation', () => {
    const verified = {
      verified: true as const,
      conversationKind: 'team_worker' as const,
      conversationId: 'worker',
      runId: '77', taskId: '501', teamId: '20', leadConversationId: 'lead', agentId: '41',
    }
    expect(isVerifiedWorkerContext(verified, 'worker')).toBe(true)
    expect(isVerifiedWorkerContext(verified, 'ordinary')).toBe(false)
    expect(isVerifiedWorkerContext({ ...verified, verified: false }, 'worker')).toBe(false)
    expect(isVerifiedWorkerContext(null, 'worker')).toBe(false)
  })
})
