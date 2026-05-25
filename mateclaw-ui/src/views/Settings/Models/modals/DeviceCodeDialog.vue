<template>
  <div v-if="visible" class="modal-overlay" @click.self="$emit('close')">
    <div class="modal device-modal">
      <div class="modal-header">
        <h2>{{ t('settings.model.oauthDeviceTitle') }}</h2>
        <button class="modal-close" @click="$emit('close')">×</button>
      </div>
      <div class="modal-body device-body">
        <p class="device-step">{{ t('settings.model.oauthDeviceStep1') }}</p>
        <a
          class="device-link"
          :href="verificationUrlComplete || verificationUrl"
          target="_blank"
          rel="noopener noreferrer"
        >
          {{ verificationUrl }}
        </a>

        <p class="device-step">{{ t('settings.model.oauthDeviceStep2') }}</p>
        <div class="device-code-row">
          <code class="device-code">{{ userCode }}</code>
          <button class="btn-copy" type="button" @click="copyCode">
            {{ copied ? t('settings.model.copied') : t('settings.model.copy') }}
          </button>
        </div>

        <p class="device-step device-countdown">
          {{ t('settings.model.oauthDeviceStep3', { seconds: remainingSeconds }) }}
        </p>
      </div>
      <div class="modal-footer">
        <button class="btn-secondary" @click="$emit('close')">
          {{ t('common.cancel') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { copyToClipboard } from '@/utils/clipboard'

const props = defineProps<{
  visible: boolean
  userCode: string
  verificationUrl: string
  verificationUrlComplete: string | null
  /** Epoch ms when the device authorization expires. */
  expiresAt: number
}>()

defineEmits<{ (e: 'close'): void }>()

const { t } = useI18n()
const copied = ref(false)
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null

const remainingSeconds = computed(() => {
  const left = Math.max(0, Math.floor((props.expiresAt - now.value) / 1000))
  return left
})

watch(
  () => props.visible,
  (v) => {
    if (v) {
      now.value = Date.now()
      timer = setInterval(() => { now.value = Date.now() }, 1000)
    } else if (timer) {
      clearInterval(timer)
      timer = null
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

async function copyCode() {
  try {
    await copyToClipboard(props.userCode)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    mcToast.warning(t('settings.model.copyFailed'))
  }
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(124, 63, 30, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  z-index: 50;
}
.modal {
  background: var(--mc-bg-elevated);
  border-radius: 16px;
  width: 100%;
  max-width: 480px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.25);
}
.modal-header,
.modal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px;
  border-bottom: 1px solid var(--mc-border-light);
}
.modal-header h2 {
  margin: 0;
  font-size: 18px;
  color: var(--mc-text-primary);
}
.modal-footer {
  border-top: 1px solid var(--mc-border-light);
  border-bottom: none;
  justify-content: flex-end;
}
.modal-close {
  background: transparent;
  border: none;
  font-size: 22px;
  color: var(--mc-text-secondary);
  cursor: pointer;
  line-height: 1;
}
.device-body {
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.device-step {
  margin: 0;
  font-size: 13px;
  color: var(--mc-text-secondary);
}
.device-link {
  display: inline-block;
  font-family: var(--mc-mono, monospace);
  font-size: 14px;
  color: var(--mc-primary);
  word-break: break-all;
}
.device-code-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.device-code {
  font-family: var(--mc-mono, ui-monospace, SFMono-Regular, monospace);
  font-size: 28px;
  letter-spacing: 6px;
  font-weight: 700;
  padding: 14px 20px;
  background: var(--mc-bg-sunken);
  border-radius: 10px;
  color: var(--mc-text-primary);
  user-select: all;
  flex: 1;
  text-align: center;
}
.btn-copy {
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border-radius: 8px;
  padding: 8px 14px;
  font-size: 13px;
  cursor: pointer;
}
.btn-copy:hover {
  background: var(--mc-bg-sunken);
}
.device-countdown {
  margin-top: 4px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
}
.btn-secondary {
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border-radius: 10px;
  padding: 9px 14px;
  font-size: 14px;
  cursor: pointer;
}
.btn-secondary:hover {
  background: var(--mc-bg-sunken);
}
</style>
