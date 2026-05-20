import { ref, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { channelApi } from '@/api'

export type QqRegisterStatus = '' | 'waiting' | 'confirmed' | 'expired' | 'denied'

export interface QqRegisterResult {
  appId: string
  clientSecret: string
  userOpenid?: string
}

/**
 * QQ Bot "scan-to-bind" frontend state machine:
 *  1. POST /channels/qrcode/qq/begin → sessionId (backend creates the bind task)
 *  2. Immediate first status poll, then every 2s
 *  3. Render qrcode_img once it arrives; auto-fill app_id + client_secret on confirmed
 *  4. Terminal states (confirmed / expired / denied) stop polling
 *
 * `loading` stays true from click until the QR image is visible, so the UI can show
 * a spinner placeholder during the brief window between begin() and the first poll
 * that returns an image.
 */
export function useQqAppRegister(onConfirmed: (r: QqRegisterResult) => void) {
  const { t } = useI18n()
  const qrcodeUrl = ref('')
  const loading = ref(false)
  const status = ref<QqRegisterStatus>('')

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
      const res: any = await channelApi.qqRegisterBegin()
      sessionId = res?.data?.session_id || res?.session_id || ''
      if (!sessionId) {
        loading.value = false
        mcToast.error(t('channels.qqRegister.startFailed'))
        return
      }
      status.value = 'waiting'
    } catch {
      loading.value = false
      mcToast.error(t('channels.qqRegister.startFailed'))
      return
    }

    const pollOnce = async () => {
      try {
        const res: any = await channelApi.qqRegisterStatus(sessionId)
        const data = res?.data || res || {}
        const s = (data.status as QqRegisterStatus) || 'waiting'

        const img = data.qrcode_img || data.qrcode_url
        if (img && qrcodeUrl.value !== img) {
          qrcodeUrl.value = img
          loading.value = false
        }
        status.value = s

        if (s === 'confirmed') {
          if (confirmedFired) return
          confirmedFired = true
          stopPolling()
          loading.value = false
          const appId = data.app_id || ''
          const clientSecret = data.client_secret || ''
          if (appId && clientSecret) {
            onConfirmed({ appId, clientSecret, userOpenid: data.user_openid })
            mcToast.success(t('channels.qqRegister.confirmed'))
          }
          return
        }

        if (s === 'expired') {
          stopPolling()
          loading.value = false
          mcToast.warning(t('channels.qqRegister.expired'))
        } else if (s === 'denied') {
          stopPolling()
          loading.value = false
          mcToast.warning(t('channels.qqRegister.denied'))
        }
      } catch {
        // Silent — transient network errors should not abort the loop.
      }
    }

    await pollOnce()
    pollTimer = setInterval(pollOnce, 2000)
  }

  onBeforeUnmount(stopPolling)

  return { qrcodeUrl, loading, status, start, reset, stopPolling }
}
