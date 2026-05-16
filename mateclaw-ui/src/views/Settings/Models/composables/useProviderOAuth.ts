import { ref, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { claudeCodeOAuthApi, oauthApi } from '@/api'
import type { ProviderInfo } from '@/types'

interface FormDeps {
  /** Editing-modal context — rebound after a load so the modal sees fresh OAuth state. */
  editingProvider: Ref<ProviderInfo | null>
}

interface ListDeps {
  loadProviders: () => Promise<void>
  /** Read-only access to the current providers list — used to re-resolve editingProvider after refresh. */
  providers: Ref<ProviderInfo[]>
}

export interface DeviceCodeDialogState {
  visible: boolean
  userCode: string
  verificationUrl: string
  verificationUrlComplete: string | null
  expiresAt: number
}

/**
 * RFC-074 PR-1: OAuth flows. Two distinct shapes:
 *   - openai-chatgpt: pop a real authorize window, poll status, refresh on success.
 *   - anthropic-claude-code (RFC-062): credentials live on disk under the user's
 *     Claude Code install — the "Connect" button just re-reads from disk.
 */
export function useProviderOAuth(deps: FormDeps & ListDeps) {
  const { t } = useI18n()

  const deviceCodeDialog = ref<DeviceCodeDialogState>({
    visible: false,
    userCode: '',
    verificationUrl: '',
    verificationUrlComplete: null,
    expiresAt: 0,
  })

  let devicePollTimer: ReturnType<typeof setTimeout> | null = null
  let activeDeviceAuthId: string | null = null

  /** After a load that may have changed OAuth state, keep the editing modal in sync. */
  async function reloadProvidersAndSync() {
    await deps.loadProviders()
    if (deps.editingProvider.value) {
      const updated = deps.providers.value.find(p => p.id === deps.editingProvider.value!.id)
      if (updated) deps.editingProvider.value = updated
    }
  }

  function stopDevicePolling() {
    if (devicePollTimer != null) {
      clearTimeout(devicePollTimer)
      devicePollTimer = null
    }
  }

  function closeDeviceCodeDialog() {
    deviceCodeDialog.value.visible = false
    stopDevicePolling()
    if (activeDeviceAuthId) {
      const id = activeDeviceAuthId
      activeDeviceAuthId = null
      oauthApi.deviceCancel(id).catch(() => { /* best-effort */ })
    }
  }

  async function runDeviceCodeFlow() {
    let start: any
    try {
      start = await oauthApi.deviceStart()
    } catch (e: any) {
      mcToast.error(e.msg || 'Device code request failed')
      return
    }
    const data = start.data
    if (!data?.deviceAuthId || !data?.userCode) {
      mcToast.error('Device code response was incomplete')
      return
    }

    activeDeviceAuthId = data.deviceAuthId
    deviceCodeDialog.value = {
      visible: true,
      userCode: data.userCode,
      verificationUrl: data.verificationUrl,
      verificationUrlComplete: data.verificationUrlComplete ?? null,
      expiresAt: Date.now() + (data.expiresInSeconds ?? 600) * 1000,
    }

    const intervalMs = Math.max((data.intervalSeconds ?? 5) * 1000, 3000)

    const tick = async () => {
      if (!activeDeviceAuthId || !deviceCodeDialog.value.visible) {
        stopDevicePolling()
        return
      }
      if (Date.now() > deviceCodeDialog.value.expiresAt) {
        mcToast.warning(t('settings.model.oauthDeviceExpired'))
        closeDeviceCodeDialog()
        return
      }
      try {
        const res: any = await oauthApi.devicePoll(activeDeviceAuthId)
        const status = res.data?.status
        if (status === 'COMPLETED') {
          activeDeviceAuthId = null
          stopDevicePolling()
          deviceCodeDialog.value.visible = false
          mcToast.success(t('settings.model.oauthLoginSuccess'))
          await reloadProvidersAndSync()
          return
        }
        if (status === 'EXPIRED') {
          mcToast.warning(t('settings.model.oauthDeviceExpired'))
          closeDeviceCodeDialog()
          return
        }
      } catch { /* transient — keep polling */ }
      devicePollTimer = setTimeout(tick, intervalMs)
    }
    devicePollTimer = setTimeout(tick, intervalMs)
  }

  async function handleOAuthLogin(providerId?: string) {
    if (providerId === 'anthropic-claude-code') {
      try {
        const res: any = await claudeCodeOAuthApi.reload()
        if (res.data?.connected && !res.data?.expired) {
          mcToast.success(t('settings.model.oauthLoginSuccess'))
        } else {
          mcToast.warning(t('settings.model.claudeCodeOauthInstructions'))
        }
        await reloadProvidersAndSync()
      } catch (e: any) {
        mcToast.error(e.msg || 'Claude Code OAuth detection failed')
      }
      return
    }
    try {
      const res: any = await oauthApi.authorize()
      const { authorizeUrl, mode } = res.data || {}

      if (mode === 'DEVICE_CODE') {
        await runDeviceCodeFlow()
        return
      }

      // LOCAL / MANUAL_PASTE both produce an authorize URL the user opens; success
      // is detected by polling /status (LOCAL completes via the loopback server,
      // MANUAL_PASTE relies on the user pasting the URL back via callbackPaste).
      const authWindow = window.open(authorizeUrl, '_blank', 'width=600,height=700')
      const pollInterval = setInterval(async () => {
        try {
          const statusRes: any = await oauthApi.status()
          if (statusRes.data?.connected) {
            clearInterval(pollInterval)
            if (authWindow && !authWindow.closed) authWindow.close()
            mcToast.success(t('settings.model.oauthLoginSuccess'))
            await reloadProvidersAndSync()
          }
        } catch { /* ignore polling errors */ }
      }, 2000)
      setTimeout(() => clearInterval(pollInterval), 30000)
    } catch (e: any) {
      mcToast.error(e.msg || 'OAuth login failed')
    }
  }

  async function handleOAuthRevoke(providerId?: string) {
    // Claude Code OAuth credentials live on disk and are owned by the Claude
    // Code app, not MateClaw — direct the user to log out there instead of
    // clobbering their machine-level login.
    if (providerId === 'anthropic-claude-code') {
      mcToast.info(t('settings.model.claudeCodeOauthRevokeHint'))
      return
    }
    try {
      await oauthApi.revoke()
      mcToast.success(t('settings.model.oauthRevokeSuccess'))
      await reloadProvidersAndSync()
    } catch (e: any) {
      mcToast.error(e.msg || 'OAuth revoke failed')
    }
  }

  return {
    handleOAuthLogin,
    handleOAuthRevoke,
    deviceCodeDialog,
    closeDeviceCodeDialog,
  }
}
