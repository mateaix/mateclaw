import { afterEach, describe, expect, it, vi } from 'vitest'
import { http, teamApi, teamRunApi } from '@/api/index'
import type { TeamRun } from '@/api/index'

describe('teamRunApi', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('uses the run lifecycle endpoints without numeric id coercion', () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({} as never)
    const post = vi.spyOn(http, 'post').mockResolvedValue({} as never)
    const runId = '9007199254740993'
    const teamId = '9007199254740995'
    const conversationId = 'lead/conversation'

    teamRunApi.get(runId)
    teamRunApi.listByTeam(teamId)
    teamRunApi.listByConversation(conversationId)
    teamRunApi.cancel(runId, 'stop')
    teamApi.createTask(teamId, {
      runId,
      subject: 'Task',
      assigneeAgentId: '2',
    })

    expect(get).toHaveBeenNthCalledWith(1, `/team-runs/${runId}`, { timeout: 15_000 })
    expect(get).toHaveBeenNthCalledWith(2, `/teams/${teamId}/runs`)
    expect(get).toHaveBeenNthCalledWith(
      3,
      `/conversations/${encodeURIComponent(conversationId)}/team-runs`,
    )
    expect(post).toHaveBeenCalledWith(`/team-runs/${runId}/cancel`, { reason: 'stop' })
    expect(post).toHaveBeenCalledWith(`/teams/${teamId}/tasks`, {
      runId,
      subject: 'Task',
      assigneeAgentId: '2',
    })
  })

  it('uses the real paged run URLs and passes cursor and limit as request params', () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue({} as never)
    const teamId = '9007199254740995'
    const conversationId = 'lead/conversation#one'

    teamRunApi.listByTeamPage(teamId, { activeOnly: true, cursor: 'team-cursor', limit: 17 })
    teamRunApi.listByConversationPage(conversationId, { cursor: 'conversation-cursor', limit: 19 })

    expect(get).toHaveBeenNthCalledWith(1, `/teams/${teamId}/runs/page`, {
      params: { activeOnly: true, cursor: 'team-cursor', limit: 17 },
      timeout: 15_000,
    })
    expect(get).toHaveBeenNthCalledWith(
      2,
      `/conversations/${encodeURIComponent(conversationId)}/team-runs/page`,
      { params: { cursor: 'conversation-cursor', limit: 19 }, timeout: 15_000 },
    )
  })

  it('models every run projection id as a string', () => {
    const run = {
      id: '9007199254740993',
      teamId: '9007199254740995',
      workspaceId: '30',
      leadAgentId: '1',
      leadConversationId: 'lead-conversation',
      originMessageId: '9007199254740997',
      title: 'Run',
      objective: 'Objective',
      status: 'running',
      finalSummary: null,
      stopReason: null,
      metadata: null,
      startedAt: null,
      completedAt: null,
      createTime: null,
      updateTime: null,
      progress: { total: 1, done: 0, failed: 0, inReview: 0, percent: 0 },
      tasks: [{
        id: '9007199254740999',
        teamId: '9007199254740995',
        runId: '9007199254740993',
        taskNumber: 1,
        subject: 'Task',
        description: null,
        status: 'pending',
        priority: 0,
        taskType: 'general',
        assigneeAgentId: '2',
        ownerAgentId: null,
        blockedBy: '["9007199254740997"]',
        requireApproval: false,
        progressPercent: null,
        progressStep: null,
        result: null,
        reason: null,
        conversationId: null,
        metadata: '{"planId":"9007199254740993"}',
        createTime: null,
        updateTime: null,
      }],
    } satisfies TeamRun

    expect(typeof run.id).toBe('string')
    expect(typeof run.teamId).toBe('string')
    expect(typeof run.tasks[0].id).toBe('string')
    expect(typeof run.tasks[0].runId).toBe('string')
    expect(run.tasks[0].blockedBy).toBe('["9007199254740997"]')
    expect(run.tasks[0].metadata).toBe('{"planId":"9007199254740993"}')
  })
})
