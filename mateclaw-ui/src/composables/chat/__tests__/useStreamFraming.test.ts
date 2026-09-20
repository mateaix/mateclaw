import { afterEach, describe, expect, it, vi } from 'vitest'
import { useStream } from '../useStream'

afterEach(() => vi.unstubAllGlobals())

describe('incremental SSE framing', () => {
  it.each(['\n', '\r\n', '\r'])('dispatches %j frames before the connection closes', async (newline) => {
    let controller!: ReadableStreamDefaultController<Uint8Array>
    const body = new ReadableStream<Uint8Array>({ start(c) { controller = c } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body)))
    const stream = useStream({ url: '/test' })
    const received: string[] = []
    stream.on('content_delta', data => received.push(data.delta))
    const connected = stream.connect()
    const encoder = new TextEncoder()
    try {
      for (const delta of ['first', 'second', 'third']) {
        const frame = `event: content_delta${newline}data: ${JSON.stringify({ delta })}${newline}${newline}`
        // Every possible CRLF pair is split across network reads.
        for (const char of frame) controller.enqueue(encoder.encode(char))
        await vi.waitFor(() => expect(received.at(-1)).toBe(delta), { timeout: 300 })
      }
      expect(received).toEqual(['first', 'second', 'third'])
    } finally {
      controller.close()
      await connected
    }
  })

  it('joins multiple data lines and ignores comment-only frames', async () => {
    const payload = ': heartbeat\r\n\r\nevent: content_delta\r\ndata: {"delta":\r\ndata: "你好"}\r\n\r\n'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(payload)))
    const stream = useStream({ url: '/test' })
    const received: unknown[] = []
    stream.onEvent(event => received.push(event.data))
    await stream.connect()
    expect(received).toEqual([{ delta: '你好' }])
  })

  it('keeps streaming when line endings change after the first event', async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>
    const body = new ReadableStream<Uint8Array>({ start(c) { controller = c } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body)))
    const stream = useStream({ url: '/test' })
    const received: string[] = []
    stream.on('content_delta', data => received.push(data.delta))
    const connected = stream.connect()
    const encoder = new TextEncoder()
    try {
      controller.enqueue(encoder.encode('data: {"delta":"first"}\n\n'))
      await vi.waitFor(() => expect(received).toEqual(['first']))
      controller.enqueue(encoder.encode('data: {"delta":"second"}\r\n\r\n'))
      await vi.waitFor(() => expect(received).toEqual(['first', 'second']))
    } finally {
      controller.close()
      await connected
    }
  })
})
