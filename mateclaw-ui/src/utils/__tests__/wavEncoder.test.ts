import { describe, expect, it, vi } from 'vitest'
import { WavRecorder } from '@/utils/wavEncoder'

class FakeNode {
  connect() { return this }
  disconnect() {}
}

class FakeProcessor extends FakeNode {
  onaudioprocess: ((event: AudioProcessingEvent) => void) | null = null
}

class FakeAudioContext {
  sampleRate = 48_000
  state = 'running'
  destination = new FakeNode()
  createMediaStreamSource() { return new FakeNode() }
  createScriptProcessor() { return new FakeProcessor() }
  createGain() { return Object.assign(new FakeNode(), { gain: { value: 1 } }) }
  async resume() {}
  async close() {}
}

describe('WavRecorder microphone lifecycle', () => {
  it('shares one pending getUserMedia call between warmUp and start', async () => {
    vi.stubGlobal('AudioContext', FakeAudioContext)

    let resolveStream!: (stream: MediaStream) => void
    const getUserMedia = vi.fn(() => new Promise<MediaStream>((resolve) => {
      resolveStream = resolve
    }))
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia },
    })

    const stopTrack = vi.fn()
    const stream = {
      getAudioTracks: () => [{ readyState: 'live' }],
      getTracks: () => [{ stop: stopTrack }],
    } as unknown as MediaStream

    const recorder = new WavRecorder()
    const warmUp = recorder.warmUp()
    const start = recorder.start()
    const duplicateStart = recorder.start()

    expect(getUserMedia).toHaveBeenCalledTimes(1)
    resolveStream(stream)
    await Promise.all([warmUp, start, duplicateStart])
    await recorder.stop()

    expect(getUserMedia).toHaveBeenCalledTimes(1)
    expect(stopTrack).toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})
