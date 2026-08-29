import { describe, expect, it } from 'vitest'
import { buildChatStreamRequestBody } from '../useChat'

describe('buildChatStreamRequestBody', () => {
  it('serializes agentId as a string before JSON.stringify touches the request body', () => {
    const body = buildChatStreamRequestBody('', {
      conversationId: 'wecom:2079870010935783426:DeBaDe',
      agentId: '2079862124134313986',
      contentParts: [],
    })

    expect(body.agentId).toBe('2079862124134313986')
    expect(JSON.stringify(body)).toContain('"agentId":"2079862124134313986"')
  })

  it('preserves the exact approval id when several approvals coexist', () => {
    const body = buildChatStreamRequestBody('/deny', {
      conversationId: 'conv-multi-approval',
      agentId: '1000000001',
      contentParts: [],
      pendingApprovalId: 'pending-newest',
    })

    expect(body.pendingApprovalId).toBe('pending-newest')
  })
})
