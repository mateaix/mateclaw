// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import {
  buildChatRouteQuery,
  readLegacyWorkerRouteContext,
  readTeamRunRouteQuery,
  resolveConversationAgentSelection,
  resolveRouteHydrationQuery,
} from '@/utils/chatRouteHydration'

const agents = [{ id: 'agent-visible' }]
const conversations = [{ conversationId: 'conv-listed' }]

describe('resolveRouteHydrationQuery', () => {
  it('keeps a deep-linked child conversation even when it is not in the sidebar list', () => {
    const result = resolveRouteHydrationQuery({
      routeAgentId: 'agent-deleted-or-hidden',
      routeConversationId: 'team-task-finished',
      agents,
      conversations,
    })

    expect(result).toEqual({
      agentId: '',
      conversationId: 'team-task-finished',
    })
  })

  it('does not require a hidden worker conversation to be present in the sidebar response', () => {
    expect(resolveRouteHydrationQuery({
      routeAgentId: 'agent-visible',
      routeConversationId: 'worker-with-explicit-kind',
      agents,
      conversations,
    })).toEqual({ agentId: 'agent-visible', conversationId: 'worker-with-explicit-kind' })
  })

  it('keeps valid route agent and conversation ids unchanged', () => {
    const result = resolveRouteHydrationQuery({
      routeAgentId: 'agent-visible',
      routeConversationId: 'conv-listed',
      agents,
      conversations,
    })

    expect(result).toEqual({
      agentId: 'agent-visible',
      conversationId: 'conv-listed',
    })
  })
})

describe('resolveConversationAgentSelection', () => {
  it('keeps a valid route agent when opening an existing conversation with stale metadata', () => {
    const selected = resolveConversationAgentSelection({
      routeAgentId: '20798621241343139868',
      conversationAgentId: '2079862124313986',
      currentAgentId: '',
    })

    expect(selected).toBe('20798621241343139868')
  })

  it('uses the conversation agent when no route agent is provided', () => {
    const selected = resolveConversationAgentSelection({
      routeAgentId: '',
      conversationAgentId: '2079862124313986',
      currentAgentId: 'fallback',
    })

    expect(selected).toBe('2079862124313986')
  })
})

describe('team run chat route state', () => {
  it('reads only explicit string run and task ids', () => {
    expect(readTeamRunRouteQuery({
      teamRunId: '9007199254740991', taskId: '501', teamId: '20', leadConversationId: 'lead',
    })).toEqual({
      teamRunId: '9007199254740991',
      taskId: '501',
      teamId: '20',
      leadConversationId: 'lead',
    })
    expect(readTeamRunRouteQuery({ teamRunId: ['10'], taskId: null })).toEqual({})
  })

  it('preserves a team run deep link while syncing agent and conversation state', () => {
    expect(buildChatRouteQuery({
      currentQuery: {
        conversationId: 'worker-conversation', teamRunId: '9007199254740991', taskId: '501',
        teamId: '20', leadConversationId: 'lead', action: 'discard',
      },
      agentId: 'agent-visible',
      conversationId: 'worker-conversation',
    })).toEqual({
      agentId: 'agent-visible',
      conversationId: 'worker-conversation',
      teamRunId: '9007199254740991',
      taskId: '501',
      teamId: '20',
      leadConversationId: 'lead',
    })
  })

  it('drops an old run deep link when the selected conversation changes', () => {
    expect(buildChatRouteQuery({
      currentQuery: { conversationId: 'worker-conversation', teamRunId: '10', taskId: '501' },
      agentId: 'agent-visible',
      conversationId: 'another-conversation',
    })).toEqual({ agentId: 'agent-visible', conversationId: 'another-conversation' })
  })
})

describe('legacy worker route context', () => {
  const fullQuery = {
    teamRunId: '2088089561144729602',
    taskId: '2088089561182478338',
    teamId: '2080573857766100994',
    leadConversationId: 'conv_lead',
  }

  it('keeps a complete historical team-task deep link visibly governed while verification hydrates', () => {
    expect(readLegacyWorkerRouteContext('team-task-legacy', fullQuery)).toEqual({
      runId: fullQuery.teamRunId,
      taskId: fullQuery.taskId,
      teamId: fullQuery.teamId,
      leadConversationId: fullQuery.leadConversationId,
    })
  })

  it('does not project ordinary or incomplete routes as worker context', () => {
    expect(readLegacyWorkerRouteContext('ordinary', fullQuery)).toBeNull()
    expect(readLegacyWorkerRouteContext('team-task-legacy', { taskId: fullQuery.taskId })).toBeNull()
  })
})
