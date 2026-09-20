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

describe('useChat stop fallback', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    streamMock.handlers.clear()
    setActivePinia(createPinia())
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('leaves interrupting and freezes running segments when done never arrives', async () => {
    const onStreamEnd = vi.fn()
    const chat = useChat({ baseUrl: '', onStreamEnd })

    await chat.sendMessage('run a long task', {
      conversationId: 'conv-stop-fallback',
      agentId: 'agent-1',
    })

    streamMock.handlers.get('thinking_delta')?.forEach(handler => handler({ delta: 'working' }))
    expect(chat.isGenerating.value).toBe(true)

    chat.stopGeneration()
    expect(chat.streamPhase.value).toBe('interrupting')

    await vi.advanceTimersByTimeAsync(3000)

    expect(chat.streamPhase.value).toBe('stopped')
    expect(chat.isGenerating.value).toBe(false)
    expect(chat.messages.value.at(-1)?.status).toBe('stopped')
    expect((chat.messages.value.at(-1)?.metadata as any)?.segments?.[0]).toMatchObject({
      status: 'completed',
      thinkingText: 'working',
    })
    expect(streamMock.disconnect).toHaveBeenCalled()
    expect(onStreamEnd).toHaveBeenCalledWith({
      conversationId: 'conv-stop-fallback',
      reason: 'stopped',
    })
  })
})
