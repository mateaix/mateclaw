import { ref, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { channelApi } from '@/api'

export type FeishuRegisterStatus = '' | 'pending' | 'waiting' | 'confirmed' | 'expired' | 'denied' | 'error'

export interface FeishuRegisterResult {
  appId: string
  appSecret: string
}

/**
 * 飞书"一键应用注册"前端状态机：
 *  1. POST /feishu/register/begin → sessionId（后端起 worker，开始向飞书拉 QR）
 *  2. 立即触发一次 status 轮询，之后每 2s 轮询 → 拿到 qrcode_img 渲染二维码 / confirmed 时回调
 *  3. 终态（confirmed / expired / denied / error）后停止轮询
 *
 * `loading` 状态从用户点击开始一直保持 true，直到 QR 真正可见才置 false ——
 * 期间的 UI 会显示 spinner 占位块，避免出现"按钮已恢复但 QR 还没来"的死页面错觉。
 */
export function useFeishuAppRegister(onConfirmed: (r: FeishuRegisterResult) => void) {
  const { t } = useI18n()
  const qrcodeUrl = ref('')
  const loading = ref(false)
  const status = ref<FeishuRegisterStatus>('')

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

  async function start(domain: string) {
    reset()
    loading.value = true

    let sessionId = ''
    try {
      const res: any = await channelApi.feishuRegisterBegin(domain)
      sessionId = res?.data?.session_id || res?.session_id || ''
      if (!sessionId) {
        loading.value = false
        mcToast.error(t('channels.feishuRegister.startFailed'))
        return
      }
      status.value = 'pending'
    } catch {
      loading.value = false
      mcToast.error(t('channels.feishuRegister.startFailed'))
      return
    }

    // Single poll body. Used both for the immediate first call (no 2s wait) and
    // the subsequent setInterval — without the immediate call the user stares at
    // a button-disabled-but-no-QR window for up to two seconds.
    const pollOnce = async () => {
      try {
        const res: any = await channelApi.feishuRegisterStatus(sessionId)
        const data = res?.data || res || {}
        const s = (data.status as FeishuRegisterStatus) || 'pending'

        // Prefer the backend-rendered base64 PNG. The raw qrcode_url is the
        // verification URL that needs encoding into a QR image; browsers can't
        // render plain text as an image. Fall back to URL only as a defensive
        // last resort.
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
          const appId = data.client_id || ''
          const appSecret = data.client_secret || ''
          if (appId && appSecret) {
            onConfirmed({ appId, appSecret })
            mcToast.success(t('channels.feishuRegister.confirmed'))
          }
          return
        }

        if (s === 'expired') {
          stopPolling()
          loading.value = false
          mcToast.warning(t('channels.feishuRegister.expired'))
        } else if (s === 'denied') {
          stopPolling()
          loading.value = false
          mcToast.warning(t('channels.feishuRegister.denied'))
        } else if (s === 'error') {
          stopPolling()
          loading.value = false
          mcToast.error(t('channels.feishuRegister.error'))
        }
      } catch {
        // Silent — transient network errors should not abort the loop.
      }
    }

    // Immediate first poll, then 2s interval. The first poll usually returns
    // pending (worker hasn't received onQRCode yet) — the second one ~2s
    // later typically has the QR ready.
    await pollOnce()
    pollTimer = setInterval(pollOnce, 2000)
  }

  onBeforeUnmount(stopPolling)

  return { qrcodeUrl, loading, status, start, reset, stopPolling }
}
