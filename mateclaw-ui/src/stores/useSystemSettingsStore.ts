import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref } from 'vue'
import { settingsApi } from '@/api'

/**
 * Holds the runtime-consumable slice of system settings so the chat flow can
 * actually honor toggles like "stream response" and "debug mode". Previously
 * `streamEnabled` / `debugMode` were written to the backend by the settings
 * page but never read anywhere — the switches were dead. This store is the
 * single source the rest of the app reads from.
 *
 * A localStorage mirror makes the values available on the very first render
 * (before the /settings GET resolves) so there is no flicker of the wrong
 * behavior on a hard reload.
 */
const STORAGE_KEY = 'mateclaw-system-settings'

interface CachedSettings {
  streamEnabled: boolean
  debugMode: boolean
}

function readCache(): CachedSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      return {
        streamEnabled: parsed.streamEnabled !== false, // default true
        debugMode: parsed.debugMode === true,          // default false
      }
    }
  } catch { /* ignore */ }
  return { streamEnabled: true, debugMode: false }
}

export const useSystemSettingsStore = defineStore('systemSettings', () => {
  const cached = readCache()
  // Whether the chat UI renders tokens incrementally (true) or buffers the
  // turn and reveals it once on completion (false).
  const streamEnabled = ref<boolean>(cached.streamEnabled)
  // Whether thinking blocks and tool-call internals are shown. Off = only the
  // final answer plus collapsed summaries (keeps the transcript clean).
  const debugMode = ref<boolean>(cached.debugMode)

  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        streamEnabled: streamEnabled.value,
        debugMode: debugMode.value,
      }))
    } catch { /* ignore */ }
  }

  /** Apply a settings object (from /settings GET or the settings page save). */
  function apply(settings: Partial<CachedSettings> | null | undefined) {
    if (!settings) return
    if (typeof settings.streamEnabled === 'boolean') streamEnabled.value = settings.streamEnabled
    if (typeof settings.debugMode === 'boolean') debugMode.value = settings.debugMode
    persist()
  }

  /** Fetch from the backend once at app start. */
  async function load() {
    try {
      const res: any = await settingsApi.get()
      apply(res?.data)
    } catch { /* keep cached defaults */ }
  }

  return { streamEnabled, debugMode, apply, load }
})

if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useSystemSettingsStore, import.meta.hot))
}
