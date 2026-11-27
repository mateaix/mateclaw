// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const streamMock = vi.hoisted(() => {
  const handlers = new Map<string, Set<(data?: any) => void>>()
  return {
    handlers,
    connect: vi.fn().mockResolvedValue(undefined),
    disconnect: vi.fn(),
    resetDedup: vi.fn(),
    on: vi.fn((event: string, handler: (data?: any) => void) => {
      const listeners = handlers.get(event) ?? new Set()
      listeners.add(handler)
      handlers.set(event, listeners)
      return () => listeners.delete(handler)
    }),
  }
})

vi.mock('../useStream', () => ({
  useStream: () => streamMock,
}))

import { useChat } from '../useChat'

describe('tool structured completion', () => {
  beforeEach(() => {
    streamMock.handlers.clear()
    setActivePinia(createPinia())
    vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {}, removeItem: () => {} })
  })
  afterEach(() => vi.unstubAllGlobals())
  const emit = (name: string, data: any) => streamMock.handlers.get(name)?.forEach(h => h(data))
  it('pairs explicit IDs exactly, including duplicate completion, and persists structured content', async () => {
    const chat = useChat({ baseUrl: '' })
    await chat.sendMessage('tools', { conversationId: 'conv', agentId: 'agent' })
    emit('tool_call_started', { toolName: 'search', toolCallId: 'a' })
    emit('tool_call_started', { toolName: 'search', toolCallId: 'b' })
    const structuredContent = { mateclawUi: { version: 1, blocks: [] } }
    emit('tool_call_completed', { toolName: 'search', toolCallId: 'a', success: true, result: 'ok', structuredContent })
    emit('tool_call_completed', { toolName: 'search', toolCallId: 'a', success: true, result: 'ok', structuredContent })
    emit('tool_call_completed', { toolName: 'search', toolCallId: 'unknown', success: true, result: 'bad' })
    const meta = chat.messages.value.at(-1)?.metadata as any
    expect(meta.toolCalls[0].structuredContent).toEqual(structuredContent)
    expect(meta.segments[0].structuredContent).toEqual(structuredContent)
    expect(meta.toolCalls[1].status).toBe('running')
    expect(meta.segments[1].status).toBe('running')
  })

  it('renders SDK subagent lifecycle as nested delegation cards', async () => {
    const chat = useChat({ baseUrl: '' })
    await chat.sendMessage('delegate', { conversationId: 'conv', agentId: 'agent' })
    emit('subagent_start', { subagentId: 'child', childAgentName: 'DSH', depth: 1 })
    emit('subagent_start', { subagentId: 'nested', parentSubagentId: 'child', childAgentName: 'DSH', depth: 2 })
    emit('subagent_complete', { subagentId: 'nested', success: false, status: 'error' })
    emit('subagent_complete', { subagentId: 'child', success: true, status: 'ok' })
    const meta = chat.messages.value.at(-1)?.metadata as any
    const card = meta.segments.find((segment: any) => segment.id === 'child')
    expect(card.toolName).toBe('→ DSH')
    expect(card.status).toBe('completed')
    expect(card.childTimeline.children[0].subagentId).toBe('nested')
    expect(card.childTimeline.children[0].status).toBe('error')
  })
})
