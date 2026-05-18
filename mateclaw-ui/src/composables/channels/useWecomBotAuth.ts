import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'

const SDK_URL = 'https://wwcdn.weixin.qq.com/node/wework/js/wecom-aibot-sdk@0.1.0.min.js'
const SOURCE = 'mateclaw'

// Module-level guard: the SDK script tag is appended to <body> exactly once
// across all component instances and re-renders.
let sdkLoadPromise: Promise<void> | null = null

function loadSDK(): Promise<void> {
  if ((window as any).WecomAIBotSDK) return Promise.resolve()
  if (sdkLoadPromise) return sdkLoadPromise
  sdkLoadPromise = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SDK_URL
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => {
      sdkLoadPromise = null // allow retry on next call
      reject(new Error('WeCom SDK load failed'))
    }
    document.body.appendChild(script)
  })
  return sdkLoadPromise
}

export interface WecomBotAuthResult {
  botid: string
  secret: string
}

/**
 * 企业微信扫码授权：动态加载官方 JS SDK，弹出授权窗口，回调 botid/secret。
 *
 * 拆出来的好处：
 *  - SDK 脚本只在用户真点"授权"按钮时才注入，不污染每个页面的 <head>
 *  - 多次点击不会重复 append <script>（sdkLoadPromise 去重）
 *  - 弹窗组件 template 里只剩按钮 + loading 状态
 */
export function useWecomBotAuth(onSuccess: (r: WecomBotAuthResult) => void) {
  const { t } = useI18n()
  const loading = ref(false)

  async function start() {
    loading.value = true
    try {
      await loadSDK()
    } catch {
      mcToast.error(t('channels.wecom.sdkFailed'))
      loading.value = false
      return
    }

    const sdk = (window as any).WecomAIBotSDK
    if (!sdk) {
      mcToast.error(t('channels.wecom.sdkFailed'))
      loading.value = false
      return
    }
    loading.value = false

    const result = sdk.openBotInfoAuthWindow({ source: SOURCE })
    if (!result || typeof result.then !== 'function') return

    result.then(
      (bot: WecomBotAuthResult) => {
        if (bot?.botid) {
          onSuccess(bot)
          mcToast.success(t('channels.wecom.authSuccess'))
        }
      },
      (error: { code: string; message: string }) => {
        if (error?.code === 'WINDOW_BLOCKED') {
          mcToast.error(t('channels.wecom.windowBlocked'))
        } else if (error?.code === 'CANCELLED') {
          mcToast.info(t('channels.wecom.authCancelled'))
        } else {
          mcToast.error(t('channels.wecom.authFailed') + '：' + (error?.message || error?.code || ''))
        }
      },
    )
  }

  return { loading, start }
}
