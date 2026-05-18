<template>
  <Transition name="talk-fade">
    <div v-if="visible" class="talk-overlay">
      <!-- Header -->
      <div class="talk-header">
        <span class="talk-title">{{ t('talk.title') }}</span>
        <button class="talk-close" @click="$emit('close')">
          <el-icon><CloseBold /></el-icon>
        </button>
      </div>

      <!-- Visualizer -->
      <div class="talk-visualizer">
        <div class="talk-pulse" :class="stateClass">
          <div class="talk-pulse-ring"></div>
          <div class="talk-pulse-ring talk-pulse-ring--2"></div>
          <div class="talk-pulse-core">
            <el-icon v-if="state === 'idle'"><Microphone /></el-icon>
            <el-icon v-else-if="state === 'listening'" class="pulse-anim"><Microphone /></el-icon>
            <el-icon v-else-if="state === 'processing'" class="spin"><Loading /></el-icon>
            <el-icon v-else><Service /></el-icon>
          </div>
        </div>
        <div class="talk-state-label">{{ stateLabel }}</div>
      </div>

      <!-- Transcript -->
      <div class="talk-transcript">
        <div v-for="(msg, i) in transcript" :key="i" class="talk-msg" :class="'talk-msg--' + msg.role">
          <span class="talk-msg-role">{{ msg.role === 'user' ? t('talk.you') : t('talk.ai') }}</span>
          <span class="talk-msg-text">{{ msg.text }}</span>
        </div>
      </div>

      <!-- Push-to-Talk button -->
      <div class="talk-controls">
        <button
          class="talk-ptt"
          :class="{ active: state === 'listening' }"
          :disabled="!canRecord"
          @mousedown="startListening"
          @mouseup="stopListening"
          @touchstart.prevent="startListening"
          @touchend.prevent="stopListening"
        >
          {{ pttLabel }}
        </button>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { CloseBold, Loading, Microphone, Service } from '@element-plus/icons-vue'
import { WavRecorder } from '@/utils/wavEncoder'

const { t } = useI18n()

const props = defineProps<{
  visible: boolean
  agentId: string | number | null
  conversationId?: string
}>()

const emit = defineEmits<{
  close: []
}>()

/**
 * Connection-aware state machine. Pre-fix the modal opened in 'idle' which
 * lit the PTT button green even while the WebSocket was still in
 * CONNECTING (readyState=0). Users hit the button immediately, recorded a
 * clip, and stopListening saw ws.readyState !== OPEN — the audio went into
 * the void. Now PTT is disabled until the backend sends {type:"ready"}.
 */
type TalkState = 'connecting' | 'idle' | 'listening' | 'processing' | 'speaking' | 'failed'

const state = ref<TalkState>('connecting')
const transcript = ref<Array<{ role: 'user' | 'assistant'; text: string }>>([])

let ws: WebSocket | null = null
let recorder: WavRecorder | null = null
/**
 * Persistent warmed-up recorder kept alive for the modal's lifetime so the
 * press-and-hold gesture doesn't race a first-time mic permission dialog.
 * The dialog steals focus → mouseup fires on the dialog instead of the PTT
 * button → stopListening never runs → recording is stuck on. Warming up
 * front-loads the permission prompt and reuses the MediaStream.
 */
let warmRecorder: WavRecorder | null = null
let audioContext: AudioContext | null = null

/**
 * Button-enabled predicate. Allows:
 *   - 'idle' — fresh / between recordings (the normal case)
 *   - 'listening' — already recording, the release event must reach us
 *   - 'failed' — clicking restarts the WS connection (retry path)
 */
const canRecord = computed(() =>
    state.value === 'idle' || state.value === 'listening' || state.value === 'failed')
const pttLabel = computed(() => {
  if (state.value === 'connecting') return t('talk.connecting')
  if (state.value === 'failed') return t('talk.retry')
  return state.value === 'listening' ? t('talk.releaseToSend') : t('talk.holdToTalk')
})

const stateClass = computed(() => 'talk-state--' + state.value)
const stateLabel = computed(() => {
  switch (state.value) {
    case 'connecting': return t('talk.connecting')
    case 'idle': return t('talk.ready')
    case 'listening': return t('talk.listening')
    case 'processing': return t('talk.processing')
    case 'speaking': return t('talk.speaking')
    case 'failed': return t('talk.connectionError')
    default: return ''
  }
})

onMounted(() => {
  if (props.agentId) {
    connectWebSocket()
    // Pre-acquire mic permission so the press-and-hold path skips the
    // permission dialog. Best-effort — failure here just means the user
    // gets the dialog on first PTT press (i.e. previous behaviour).
    warmRecorder = new WavRecorder()
    warmRecorder.warmUp().catch(err => {
      console.debug('[TalkMode] mic warm-up failed (will prompt on first PTT)', err)
    })
  }
})

onBeforeUnmount(() => {
  disconnectWebSocket()
  warmRecorder?.releaseWarmUp()
  warmRecorder = null
})

function connectWebSocket() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const token = localStorage.getItem('token')
  const wsUrl = `${protocol}//${location.host}/api/v1/talk/ws${token ? '?token=' + token : ''}`

  state.value = 'connecting'
  ws = new WebSocket(wsUrl)

  ws.onopen = () => {
    // Send init message. Stay in 'connecting' until the backend's
    // {type:"ready"} ack lands — the WS being open isn't the same as the
    // backend session being initialised.
    ws?.send(JSON.stringify({
      type: 'init',
      agentId: props.agentId,
      conversationId: props.conversationId || 'talk-' + Date.now(),
      username: localStorage.getItem('username') || 'anonymous',
    }))
  }

  ws.onmessage = (event) => {
    if (event.data instanceof Blob) {
      // Binary = TTS audio
      playAudio(event.data)
      return
    }

    try {
      const data = JSON.parse(event.data)
      switch (data.type) {
        case 'ready':
          // Only NOW does the PTT button unlock — see canRecord computed.
          state.value = 'idle'
          break
        case 'state':
          state.value = data.state as TalkState
          break
        case 'transcript':
          transcript.value.push({ role: 'user', text: data.text })
          break
        case 'reply':
          transcript.value.push({ role: 'assistant', text: data.text })
          break
        case 'tts_url':
          playAudioUrl(data.url)
          break
        case 'error':
          mcToast.error(data.message || t('talk.connectionError'))
          state.value = 'idle'
          break
      }
    } catch {
      // ignore non-JSON frames
    }
  }

  ws.onerror = (e) => {
    console.warn('[TalkMode] WS error', e)
    if (state.value === 'connecting') {
      state.value = 'failed'
      mcToast.error(t('talk.connectionError'))
    }
  }

  ws.onclose = (e) => {
    console.debug('[TalkMode] WS closed', 'code=', e.code, 'reason=', e.reason)
    // Distinguish close-before-init (the user got a failed handshake) from
    // close-after-success (the modal was just closed). A premature close
    // leaves the user staring at "Ready" with no working button — flag
    // it so the retry path actually runs.
    if (state.value === 'connecting') {
      state.value = 'failed'
    } else if (state.value !== 'failed') {
      state.value = 'idle'
    }
  }
}

/** Manual reconnect — wired to the PTT button when state==='failed'. */
function retryConnection() {
  ws?.close()
  ws = null
  connectWebSocket()
}

function disconnectWebSocket() {
  // Force-stop any in-flight recording so released the mic on close.
  if (recorder?.isActive()) {
    recorder.stop().catch(() => {})
    recorder = null
  }
  ws?.close()
  ws = null
  audioContext?.close()
  audioContext = null
}

async function startListening() {
  // Failed-state click is a retry, not a recording start. The button label
  // already says "Retry" via pttLabel, so this matches the user's intent.
  if (state.value === 'failed') {
    retryConnection()
    return
  }
  if (state.value !== 'idle') return

  try {
    // Web Audio API + manual WAV encode (utils/wavEncoder.ts) — replaces
    // MediaRecorder/WebM. DashScope Paraformer rejects webm; WAV is the
    // lowest common denominator every STT provider accepts.
    //
    // Reuse the warmed-up recorder so we skip the permission dialog if
    // it was successfully acquired in onMounted. If warm-up failed (or
    // is still pending), fall back to a fresh recorder.
    recorder = warmRecorder ?? new WavRecorder()
    warmRecorder = null  // ownership transferred for the duration of recording
    await recorder.start()
    state.value = 'listening'
    console.debug('[TalkMode] listening started')
  } catch (err) {
    console.warn('[TalkMode] startListening failed', err)
    mcToast.error(t('talk.micError'))
    state.value = 'idle'
    recorder = null
  }
}

async function stopListening() {
  // Belt-and-braces: also stop when recorder exists but state hasn't caught
  // up (race with the slow path of startListening). Without this, a fast
  // press-then-release leaves the recorder running invisibly.
  if (!recorder) {
    if (state.value === 'listening') state.value = 'idle'
    return
  }
  const result = await recorder.stop()
  recorder = null
  // Re-warm for the next press so subsequent PTTs also skip permission.
  warmRecorder = new WavRecorder()
  warmRecorder.warmUp().catch(() => {})

  if (!result) {
    // No audio captured — the most common cause is ScriptProcessor never
    // firing (suspended AudioContext) or the recording was so short no
    // sample buffer landed. Surface a clear hint instead of a silent idle.
    console.warn('[TalkMode] stop returned no audio (recording too short or context suspended)')
    mcToast.warning(t('talk.tooShort') || '录音过短，请按住按钮多说几秒')
    state.value = 'idle'
    return
  }
  const arrayBuffer = await result.blob.arrayBuffer()
  console.debug('[TalkMode] stop produced wav',
    'bytes=', arrayBuffer.byteLength,
    'duration=', result.durationSeconds, 's',
    'wsReadyState=', ws?.readyState)
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(arrayBuffer)
    state.value = 'processing'
  } else {
    // Distinct from the empty-recording case: capture worked, but the WS
    // dropped before we got here. Tell the user instead of silently going
    // idle — they'd otherwise blame the mic.
    console.warn('[TalkMode] WS not open at stop time, readyState=', ws?.readyState)
    mcToast.error(t('talk.connectionError'))
    state.value = 'idle'
  }
}

async function playAudio(blob: Blob) {
  try {
    if (!audioContext) {
      audioContext = new AudioContext()
    }
    const arrayBuffer = await blob.arrayBuffer()
    const audioBuffer = await audioContext.decodeAudioData(arrayBuffer)
    const source = audioContext.createBufferSource()
    source.buffer = audioBuffer
    source.connect(audioContext.destination)
    source.onended = () => {
      state.value = 'idle'
    }
    source.start(0)
    state.value = 'speaking'
  } catch {
    mcToast.warning(t('talk.playbackError'))
    state.value = 'idle'
  }
}

function playAudioUrl(url: string) {
  const audio = new Audio(url)
  audio.onended = () => { state.value = 'idle' }
  audio.onerror = () => { state.value = 'idle' }
  audio.play().catch(() => { state.value = 'idle' })
}
</script>

<style scoped>
.talk-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: var(--el-bg-color, #fff);
  display: flex;
  flex-direction: column;
  align-items: center;
}

.talk-header {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
}

.talk-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}

.talk-close {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--el-text-color-regular, #606266);
  padding: 4px;
  border-radius: 6px;
}

.talk-close:hover {
  background: var(--el-fill-color-light, #f5f7fa);
}

.talk-visualizer {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24px;
}

.talk-pulse {
  position: relative;
  width: 120px;
  height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.talk-pulse-ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 2px solid var(--el-color-primary, #409eff);
  opacity: 0.2;
}

.talk-state--listening .talk-pulse-ring {
  animation: pulse-ring 1.5s ease-out infinite;
  opacity: 0.4;
}

.talk-state--listening .talk-pulse-ring--2 {
  animation-delay: 0.5s;
}

.talk-state--speaking .talk-pulse-ring {
  animation: pulse-ring 2s ease-out infinite;
  border-color: var(--el-color-success, #67c23a);
  opacity: 0.3;
}

.talk-pulse-core {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: var(--el-fill-color-light, #f5f7fa);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-color-primary, #409eff);
  transition: all 0.3s;
}

.talk-state--listening .talk-pulse-core {
  background: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
}

.talk-state--processing .talk-pulse-core {
  background: var(--el-color-warning-light-9, #fdf6ec);
  color: var(--el-color-warning, #e6a23c);
}

.talk-state--speaking .talk-pulse-core {
  background: var(--el-color-success-light-9, #f0f9eb);
  color: var(--el-color-success, #67c23a);
}

.talk-state-label {
  font-size: 14px;
  color: var(--el-text-color-secondary, #909399);
  font-weight: 500;
}

.talk-transcript {
  width: 100%;
  max-width: 600px;
  max-height: 200px;
  overflow-y: auto;
  padding: 0 24px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.talk-msg {
  display: flex;
  gap: 8px;
  font-size: 14px;
  line-height: 1.5;
}

.talk-msg-role {
  font-weight: 600;
  min-width: 32px;
  color: var(--el-text-color-secondary, #909399);
}

.talk-msg--user .talk-msg-role { color: var(--el-color-primary, #409eff); }
.talk-msg--assistant .talk-msg-role { color: var(--el-color-success, #67c23a); }

.talk-msg-text {
  color: var(--el-text-color-primary, #303133);
}

.talk-controls {
  padding: 32px;
}

.talk-ptt {
  width: 200px;
  height: 56px;
  border-radius: 28px;
  border: 2px solid var(--el-color-primary, #409eff);
  background: transparent;
  color: var(--el-color-primary, #409eff);
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
  -webkit-user-select: none;
}

.talk-ptt:hover:not(:disabled) {
  background: var(--el-color-primary-light-9, #ecf5ff);
}

.talk-ptt.active {
  background: var(--el-color-primary, #409eff);
  color: white;
  transform: scale(1.05);
}

.talk-ptt:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

@keyframes pulse-ring {
  0% { transform: scale(1); opacity: 0.4; }
  100% { transform: scale(1.6); opacity: 0; }
}

.pulse-anim {
  animation: pulse-icon 1s ease-in-out infinite;
}

@keyframes pulse-icon {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.1); }
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.talk-fade-enter-active,
.talk-fade-leave-active {
  transition: opacity 0.3s;
}

.talk-fade-enter-from,
.talk-fade-leave-to {
  opacity: 0;
}
</style>
