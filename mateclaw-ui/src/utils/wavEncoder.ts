/**
 * Browser-side recorder that captures microphone audio via the Web Audio API
 * and encodes it directly to a 16-bit PCM WAV blob.
 *
 * <p>Why this exists: MediaRecorder output and codec strings vary by browser.
 * A canonical PCM WAV is the lowest common denominator that every STT
 * provider accepts without server-side transcoding, and its samples can be
 * inspected locally for silence and duration before an API call.
 *
 * <p>Trade-off: WAV files are ~10x larger than Opus. For typical
 * conversational STT (5-30s clips at 16 kHz / 16-bit / mono) that's
 * 160 KB - 1 MB — fine to send over a websocket.
 *
 * <p>Reference: openclaw {@code src/media/audio-transcode.ts} solves the same
 * problem with server-side ffmpeg. Doing it client-side avoids the ffmpeg
 * dependency on the MateClaw server and works the same way.
 */

/** Target sample rate for recordings. 16 kHz is the standard for STT models. */
const TARGET_SAMPLE_RATE = 16_000;

/** WAV / PCM constants. */
const NUM_CHANNELS = 1;
const BITS_PER_SAMPLE = 16;
const RIFF_HEADER_SIZE = 44;

/**
 * Result of a finished recording.
 */
export interface WavRecording {
    /** WAV-encoded audio bytes ready to upload. */
    blob: Blob;
    /** Convenience MIME type — always "audio/wav". */
    mimeType: 'audio/wav';
    /** Recording duration in seconds (rounded to ms). */
    durationSeconds: number;
}

/**
 * Live microphone recorder backed by Web Audio API. Call {@link start} once,
 * later call {@link stop} to receive a {@link WavRecording}. After stop the
 * underlying MediaStream tracks are released.
 *
 * <p>The recorder captures Float32 samples from a {@code ScriptProcessorNode},
 * accumulates them, then encodes a 16-bit PCM mono WAV in {@link stop}.
 * ScriptProcessorNode is deprecated in favour of AudioWorklet, but it's still
 * universally supported and zero-config — sufficient for short STT clips.
 *
 * <p>Browser support: every browser MateClaw targets ships AudioContext +
 * ScriptProcessorNode. Safari requires a user gesture to call
 * {@code getUserMedia}; the caller should already be doing this.
 */
export class WavRecorder {
    private audioContext: AudioContext | null = null;
    private mediaStream: MediaStream | null = null;
    /** Deduplicates warm-up/start getUserMedia calls so they cannot overwrite each other's stream. */
    private mediaStreamPromise: Promise<MediaStream> | null = null;
    /** Lets stop() wait for a permission/start sequence that has not finished yet. */
    private starting: Promise<void> | null = null;
    /** Warm-up recorders are disposable; a released instance must never retain a late stream. */
    private released = false;
    private source: MediaStreamAudioSourceNode | null = null;
    private processor: ScriptProcessorNode | null = null;
    /** Silent sink node — keeps the processor graph alive without echoing through speakers. */
    private silentSink: GainNode | null = null;
    private chunks: Float32Array[] = [];
    /** Sample rate the AudioContext is actually running at — may differ from TARGET_SAMPLE_RATE. */
    private inputSampleRate = TARGET_SAMPLE_RATE;
    private startTimeMs = 0;

    /**
     * Pre-acquire microphone permission and warm the audio graph without
     * starting capture. Called when the TalkMode modal opens so the
     * press-and-hold gesture later doesn't race a first-time permission
     * dialog (the dialog steals focus → mouseup fires on the dialog →
     * stopListening never runs → recording is stuck on forever).
     *
     * <p>Safe to call multiple times. Subsequent calls return immediately.
     */
    async warmUp(): Promise<void> {
        if (this.released) throw new DOMException('Recorder has been released', 'InvalidStateError');
        await this.ensureMediaStream();
    }

    /**
     * Begin capturing from the microphone. Throws if mic access is denied.
     * Already-started recorders are idempotent — calling start twice is a no-op.
     */
    async start(): Promise<void> {
        if (this.starting) return this.starting;
        if (this.audioContext) return;
        if (this.released) throw new DOMException('Recorder has been released', 'InvalidStateError');

        const pending = this.startInternal();
        this.starting = pending;
        try {
            await pending;
        } finally {
            if (this.starting === pending) this.starting = null;
        }
    }

    private async startInternal(): Promise<void> {

        // sampleRate hint: browsers honour it on Chrome/Edge but Safari may
        // ignore it and run at the device default. We resample manually in
        // stop() to be safe.
        const ctx = new AudioContext();
        this.audioContext = ctx;
        this.inputSampleRate = ctx.sampleRate;
        try {
            // Reuse the warmed-up stream when present so we skip the permission
            // dialog on the press-and-hold path.
            const mediaStream = await this.ensureMediaStream();
            // Modern Chromium AudioContexts created from a user gesture may still
            // arrive suspended if mic permission was prompted asynchronously.
            // Force-resume so onaudioprocess actually fires.
            if (ctx.state === 'suspended') {
                await ctx.resume();
            }
            this.source = ctx.createMediaStreamSource(mediaStream);
            this.processor = ctx.createScriptProcessor(4096, 1, 1);
            this.processor.onaudioprocess = (e) => {
                const channel = e.inputBuffer.getChannelData(0);
                // Defensive copy — the underlying buffer is reused on the next callback.
                this.chunks.push(new Float32Array(channel));
            };
            // Wire source → processor → silent gain → destination. The gain=0
            // node muzzles the echo through the speakers but keeps the chain
            // attached to destination, which Chrome requires to fire
            // onaudioprocess. Connecting processor directly to destination
            // would echo the mic input back through speakers (feedback) AND
            // some browsers stop calling onaudioprocess if they decide the
            // chain "produces no audible output" — the explicit GainNode
            // makes that decision unambiguous.
            const silentSink = ctx.createGain();
            silentSink.gain.value = 0;
            this.silentSink = silentSink;
            this.source.connect(this.processor);
            this.processor.connect(silentSink);
            silentSink.connect(ctx.destination);
            this.startTimeMs = Date.now();
            // Diagnostic — paste from devtools console when the recording silently
            // produces 0 bytes. Includes sample rate so we can confirm Safari
            // is at 44.1kHz vs Chrome's 48kHz.
            console.debug('[WavRecorder] started',
                    'sampleRate=', ctx.sampleRate,
                    'state=', ctx.state);
        } catch (error) {
            this.processor?.disconnect();
            this.source?.disconnect();
            this.silentSink?.disconnect();
            this.mediaStream?.getTracks().forEach((track) => track.stop());
            await ctx.close().catch(() => {});
            this.audioContext = null;
            this.mediaStream = null;
            this.source = null;
            this.processor = null;
            this.silentSink = null;
            throw error;
        }
    }

    /**
     * Stop capturing, encode the buffered audio to WAV, and release resources.
     * Returns null when nothing was captured (e.g. start failed silently).
     */
    async stop(): Promise<WavRecording | null> {
        const pendingStart = this.starting;
        if (pendingStart) {
            try {
                await pendingStart;
            } catch {
                return null;
            }
        }
        if (!this.audioContext) return null;

        const durationSeconds = Math.round((Date.now() - this.startTimeMs)) / 1000;
        const sampleRate = this.inputSampleRate;
        const collected = this.chunks;

        this.processor?.disconnect();
        this.source?.disconnect();
        this.silentSink?.disconnect();
        this.mediaStream?.getTracks().forEach((t) => t.stop());
        await this.audioContext.close();
        this.audioContext = null;
        this.mediaStream = null;
        this.mediaStreamPromise = null;
        this.released = true;
        this.source = null;
        this.processor = null;
        this.silentSink = null;
        this.chunks = [];

        // Diagnostic — chunk count + total samples. If chunks=0, the
        // ScriptProcessor never fired (suspended context, browser
        // optimised the graph away, etc). With this log the user can paste
        // the devtools output and we can tell from a single line.
        const totalSamples = collected.reduce((n, c) => n + c.length, 0);
        console.debug('[WavRecorder] stopped',
                'durationSec=', durationSeconds,
                'chunks=', collected.length,
                'totalSamples=', totalSamples);

        if (collected.length === 0) {
            return null;
        }
        const merged = mergeFloat32(collected);
        // Resample to 16 kHz if we captured at a higher rate (Safari often runs
        // the AudioContext at 44.1 kHz). 16 kHz is what speech recognizers
        // expect, and shrinks the WAV by ~3x at no quality cost for speech.
        const resampled = sampleRate === TARGET_SAMPLE_RATE
            ? merged
            : downsample(merged, sampleRate, TARGET_SAMPLE_RATE);
        const blob = encodeWav(resampled, TARGET_SAMPLE_RATE);
        return { blob, mimeType: 'audio/wav', durationSeconds };
    }

    /** True when the recorder is actively capturing. */
    isActive(): boolean {
        return this.audioContext !== null;
    }

    /**
     * Release the warmed-up MediaStream without starting a recording.
     * Pair with {@link warmUp} on modal close.
     */
    releaseWarmUp(): void {
        this.released = true;
        if (this.audioContext) return;  // active recording owns the stream
        this.mediaStream?.getTracks().forEach((t) => t.stop());
        this.mediaStream = null;
        // getUserMedia may resolve after the component was unmounted. Stop
        // that late stream immediately instead of leaving the mic indicator on.
        this.mediaStreamPromise?.then((stream) => {
            stream.getTracks().forEach((track) => track.stop());
        }).catch(() => {});
        this.mediaStreamPromise = null;
    }

    /** Acquire one stable mono speech stream shared by warm-up and recording start. */
    private async ensureMediaStream(): Promise<MediaStream> {
        if (this.mediaStream?.getAudioTracks().some((track) => track.readyState === 'live')) {
            return this.mediaStream;
        }
        if (!this.mediaStreamPromise) {
            this.mediaStreamPromise = navigator.mediaDevices.getUserMedia({
                audio: {
                    channelCount: 1,
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true,
                },
            }).then((stream) => {
                if (this.released) {
                    stream.getTracks().forEach((track) => track.stop());
                    throw new DOMException('Recorder was released while requesting microphone access', 'AbortError');
                }
                this.mediaStream = stream;
                return stream;
            }).finally(() => {
                this.mediaStreamPromise = null;
            });
        }
        return this.mediaStreamPromise;
    }
}

/* ------------------------------------------------------------------ */
/* Internals                                                           */
/* ------------------------------------------------------------------ */

function mergeFloat32(chunks: Float32Array[]): Float32Array {
    let total = 0;
    for (const c of chunks) total += c.length;
    const out = new Float32Array(total);
    let offset = 0;
    for (const c of chunks) {
        out.set(c, offset);
        offset += c.length;
    }
    return out;
}

/**
 * Decimating linear-interpolation resampler. Adequate for 16 kHz speech —
 * the accuracy gap vs polyphase resampling is immaterial to speech
 * recognizers at the input sample rates we see in practice (44.1k → 16k).
 */
function downsample(samples: Float32Array, fromRate: number, toRate: number): Float32Array {
    if (fromRate === toRate) return samples;
    const ratio = fromRate / toRate;
    const newLength = Math.floor(samples.length / ratio);
    const out = new Float32Array(newLength);
    for (let i = 0; i < newLength; i++) {
        const srcIndex = i * ratio;
        const lo = Math.floor(srcIndex);
        const hi = Math.min(lo + 1, samples.length - 1);
        const frac = srcIndex - lo;
        out[i] = samples[lo] * (1 - frac) + samples[hi] * frac;
    }
    return out;
}

/**
 * Build a WAV (RIFF / WAVE) blob from Float32 PCM samples in the [-1.0, 1.0]
 * range. Output is 16-bit signed PCM, mono.
 */
function encodeWav(samples: Float32Array, sampleRate: number): Blob {
    const dataSize = samples.length * (BITS_PER_SAMPLE / 8);
    const buffer = new ArrayBuffer(RIFF_HEADER_SIZE + dataSize);
    const view = new DataView(buffer);

    // RIFF header — fixed shape; getting any of these wrong makes the WAV
    // unreadable by every consumer (browsers, ffmpeg, STT servers).
    writeAscii(view, 0, 'RIFF');
    view.setUint32(4, 36 + dataSize, true);  // file size minus the 8-byte RIFF preamble
    writeAscii(view, 8, 'WAVE');

    // fmt chunk
    writeAscii(view, 12, 'fmt ');
    view.setUint32(16, 16, true);                                    // PCM fmt chunk size
    view.setUint16(20, 1, true);                                     // audio format = 1 (PCM)
    view.setUint16(22, NUM_CHANNELS, true);
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * NUM_CHANNELS * (BITS_PER_SAMPLE / 8), true);  // byte rate
    view.setUint16(32, NUM_CHANNELS * (BITS_PER_SAMPLE / 8), true);  // block align
    view.setUint16(34, BITS_PER_SAMPLE, true);

    // data chunk
    writeAscii(view, 36, 'data');
    view.setUint32(40, dataSize, true);

    // PCM samples — clamp to [-1, 1] before scaling to int16 to avoid wrap-around
    // distortion on hot signals.
    let offset = RIFF_HEADER_SIZE;
    for (let i = 0; i < samples.length; i++, offset += 2) {
        const clamped = Math.max(-1, Math.min(1, samples[i]));
        const intVal = clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff;
        view.setInt16(offset, intVal, true);
    }
    return new Blob([buffer], { type: 'audio/wav' });
}

function writeAscii(view: DataView, offset: number, text: string): void {
    for (let i = 0; i < text.length; i++) {
        view.setUint8(offset + i, text.charCodeAt(i));
    }
}
