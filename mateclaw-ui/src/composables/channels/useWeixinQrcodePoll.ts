import { ref, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { channelApi } from '@/api'

export type WeixinPollStatus = '' | 'polling' | 'scanned' | 'confirmed' | 'expired'

export interface WeixinQrcodeResult {
  botToken: string
  baseUrl?: string
}

/**
 * 微信 iLink Bot 扫码登录的状态机：
 * 拉一张二维码 → 轮询扫码状态（每 2s）→ confirmed 时回调 botToken/baseUrl。
 *
 * 把这段单独抽成 composable 的目的：
 *  - 弹窗组件不需要再背着轮询定时器、state 转移、错误提示这一坨副作用
 *  - 组件卸载时自动 stopPolling，不漏定时器
 *  - 可以被多处复用（比如未来在向导里也用）
 */
export function useWeixinQrcodePoll(onConfirmed: (r: WeixinQrcodeResult) => void) {
  const { t } = useI18n()
  const qrcodeImg = ref('')
  const loading = ref(false)
  const pollStatus = ref<WeixinPollStatus>('')

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
    qrcodeImg.value = ''
    pollStatus.value = ''
    confirmedFired = false
  }

  async function start() {
    reset()
    loading.value = true

    try {
      const data: any = await channelApi.weixinQrcode()
      const imgContent = data?.qrcode_img_content || data?.qrcode_img || ''
      const qrcodeId = data?.qrcode || ''

      if (!imgContent && !qrcodeId) {
        mcToast.error(t('channels.weixin.qrcodeFailed'))
        return
      }

      if (imgContent) {
        qrcodeImg.value = imgContent.startsWith('http')
          ? imgContent
          : `data:image/png;base64,${imgContent}`
      }
      pollStatus.value = 'polling'

      if (!qrcodeId) return

      pollTimer = setInterval(async () => {
        try {
          const s: any = await channelApi.weixinQrcodeStatus(qrcodeId)
          const status = s?.status || ''

          if (status === 'scanned') {
            pollStatus.value = 'scanned'
          }

          if (status === 'confirmed' && s?.bot_token) {
            if (confirmedFired) return
            confirmedFired = true
            stopPolling()
            qrcodeImg.value = ''
            pollStatus.value = 'confirmed'
            onConfirmed({ botToken: s.bot_token, baseUrl: s.base_url })
            mcToast.success(t('channels.weixin.loginSuccess'))
          }

          if (status === 'expired') {
            stopPolling()
            qrcodeImg.value = ''
            pollStatus.value = 'expired'
            mcToast.warning(t('channels.weixin.qrcodeExpired'))
          }
        } catch {
          // Silent — transient network errors should not abort the loop.
        }
      }, 2000)
    } catch {
      mcToast.error(t('channels.weixin.qrcodeFailed'))
    } finally {
      loading.value = false
    }
  }

  onBeforeUnmount(stopPolling)

  return { qrcodeImg, loading, pollStatus, start, reset, stopPolling }
}
