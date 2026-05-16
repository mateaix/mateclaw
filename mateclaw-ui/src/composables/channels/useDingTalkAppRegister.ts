import { ref, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { channelApi } from '@/api'

export type DingTalkRegisterStatus = '' | 'waiting' | 'confirmed' | 'expired' | 'denied'

export interface DingTalkRegisterResult {
  clientId: string
  clientSecret: string
}

/**
 * 钉钉"一键应用注册"前端状态机：
 *  1. POST /dingtalk/register/begin → sessionId（后端起 worker，开始 5s 轮询 /poll）
 *  2. 立即触发一次 status 轮询，之后每 2s 轮询 → 拿到 qrcode_img 渲染 / confirmed 时回调
 *  3. 终态（confirmed / expired / denied）后停止轮询
 *
 * `loading` 状态从用户点击开始一直保持 true，直到 QR 真正可见才置 false ——
 * 期间的 UI 会显示 spinner 占位块，避免出现"按钮已恢复但 QR 还没来"的死页面错觉。
 */
export function useDingTalkAppRegister(onConfirmed: (r: DingTalkRegisterResult) => void) {
  const { t } = useI18n()
  const qrcodeUrl = ref('')
  const loading = ref(false)
  const status = ref<DingTalkRegisterStatus>('')

  let pollTimer: ReturnType<typeof setInterval> | null = null
  let confirmedFired = false

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function reset() {
    stopPolling()
    qrcodeUrl.value = ''
    status.value = ''
    confirmedFired = false
  }

  async function start() {
    reset()
    loading.value = true

    let sessionId = ''
    try {
      const res: any = await channelApi.dingtalkRegisterBegin()
      sessionId = res?.data?.session_id || res?.session_id || ''
      if (!sessionId) {
        loading.value = false
        mcToast.error(t('channels.dingtalkRegister.startFailed'))
        return
      }
      status.value = 'waiting'
    } catch {
      loading.value = false
      mcToast.error(t('channels.dingtalkRegister.startFailed'))
      return
    }

    // Single poll body. Used both for the immediate first call (no 2s wait) and
    // the subsequent setInterval — DingTalk backend polls every 5s, so the
    // first qrcode_img usually appears in our 2nd or 3rd poll.
    const pollOnce = async () => {
      try {
        const res: any = await channelApi.dingtalkRegisterStatus(sessionId)
        const data = res?.data || res || {}
        const s = (data.status as DingTalkRegisterStatus) || 'waiting'

        const img = data.qrcode_img || data.qrcode_url
        if (img && qrcodeUrl.value !== img) {
          qrcodeUrl.value = img
          loading.value = false   // QR is now visible, button can recover
        }
        status.value = s

        if (s === 'confirmed') {
          if (confirmedFired) return
          confirmedFired = true
          stopPolling()
          loading.value = false
          const clientId = data.client_id || ''
          const clientSecret = data.client_secret || ''
          if (clientId && clientSecret) {
            onConfirmed({ clientId, clientSecret })
            mcToast.success(t('channels.dingtalkRegister.confirmed'))
          }
          return
        }

        if (s === 'expired') {
          stopPolling()
          loading.value = false
          mcToast.warning(t('channels.dingtalkRegister.expired'))
        } else if (s === 'denied') {
          stopPolling()
          loading.value = false
          mcToast.warning(t('channels.dingtalkRegister.denied'))
        }
      } catch {
        // Silent — transient network errors should not abort the loop.
      }
    }

    // Immediate first poll, then 2s interval. begin() should already have stored
    // the QR URL, so the very first poll typically already returns it.
    await pollOnce()
    pollTimer = setInterval(pollOnce, 2000)
  }

  onBeforeUnmount(stopPolling)

  return { qrcodeUrl, loading, status, start, reset, stopPolling }
}
