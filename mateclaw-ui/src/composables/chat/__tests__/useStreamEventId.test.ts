import { afterEach, describe, expect, it, vi } from 'vitest'
import { useStream } from '@/composables/chat/useStream'

describe('useStream SSE event ids', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keeps adjacent JavaScript-safe ids distinct in Number comparisons', async () => {
    const firstId = '9007199254740990'
    const secondId = '9007199254740991'
    const payload = [
      `id: ${firstId}\nevent: content_delta\ndata: {"value":1}\n\n`,
      `id: ${secondId}\nevent: content_delta\ndata: {"value":2}\n\n`,
    ].join('')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(payload, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })))
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null) })
    const stream = useStream({ url: '/api/test-stream' })
    const receivedIds: string[] = []
    stream.onEvent(event => receivedIds.push(event.id!))

    await stream.connect({ conversationId: '1' })

    expect(receivedIds).toEqual([firstId, secondId])
    expect(stream.lastEventId.value).toBe(secondId)
  })
})
