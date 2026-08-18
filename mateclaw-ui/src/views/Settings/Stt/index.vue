<template>
  <div class="settings-section">
    <div class="section-header">
      <h2 class="section-title">{{ t('settings.sttTitle') }}</h2>
      <p class="section-desc">{{ t('settings.sttDesc') }}</p>
    </div>

    <div class="settings-card">
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.sttEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.sttEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.sttEnabled" type="checkbox" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>

      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.sttProvider') }}</div>
          <div class="setting-hint">{{ t('settings.hints.sttProvider') }}</div>
        </div>
        <div class="setting-control">
          <select v-model="settings.sttProvider" class="form-input" :disabled="!settings.sttEnabled">
            <option value="auto">{{ t('settings.sttProviderOptions.auto') }}</option>
            <option value="openai">OpenAI Whisper</option>
            <option value="dashscope">DashScope (Qwen3-ASR Flash)</option>
          </select>
        </div>
      </div>

      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.sttFallbackEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.sttFallbackEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.sttFallbackEnabled" type="checkbox" :disabled="!settings.sttEnabled" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>
    </div>

    <template v-if="settings.sttEnabled">
      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">OpenAI / OpenAI-compatible (Whisper)</span>
          <span class="provider-tag">{{ t('settings.sttProviderTags.reuseLlmKey') }}</span>
        </div>
        <!-- Issue #76: let users point this provider at any OpenAI-compat
             endpoint (FunASR / SiliconFlow / Groq / Together / Volcano /
             Qiniu / self-hosted ...) by selecting the credential row + model. -->
        <div class="settings-card">
          <div class="setting-item">
            <div class="setting-info">
              <div class="setting-label">{{ t('settings.fields.sttOpenAiCompatProviderId') }}</div>
              <div class="setting-hint">{{ t('settings.hints.sttOpenAiCompatProviderId') }}</div>
            </div>
            <div class="setting-control">
              <select
                v-model="settings.sttOpenAiCompatProviderId"
                class="form-input"
                :disabled="!settings.sttEnabled"
              >
                <option
                  v-for="p in openAiCompatProviders"
                  :key="p.id"
                  :value="p.id"
                >{{ p.name }}{{ p.id === 'openai' ? '' : ` (${p.id})` }}</option>
              </select>
            </div>
          </div>
          <div class="setting-item">
            <div class="setting-info">
              <div class="setting-label">{{ t('settings.fields.sttOpenAiCompatModel') }}</div>
              <div class="setting-hint">{{ t('settings.hints.sttOpenAiCompatModel') }}</div>
            </div>
            <div class="setting-control">
              <input
                v-model="settings.sttOpenAiCompatModel"
                class="form-input"
                :disabled="!settings.sttEnabled"
                placeholder="whisper-1"
              />
            </div>
          </div>
          <div class="setting-item">
            <div class="setting-info">
              <div class="setting-hint">{{ t('settings.hints.sttOpenAiCompatNote') }}</div>
            </div>
          </div>
        </div>
      </div>
      <div class="provider-section">
        <div class="provider-header">
          <span class="provider-name">DashScope (Qwen3-ASR Flash)</span>
          <span class="provider-tag">{{ t('settings.sttProviderTags.reuseLlmKey') }}</span>
        </div>
        <div class="settings-card"><div class="setting-item"><div class="setting-info"><div class="setting-hint">{{ t('settings.hints.dashscopeSttInfo') }}</div></div></div></div>
      </div>
    </template>

    <div class="save-bar">
      <button class="btn-secondary" @click="loadSettings">{{ t('common.reset') }}</button>
      <button class="btn-primary" @click="onSaveSettings">{{ t('settings.actions.saveSystem') }}</button>
    </div>
    <div v-if="savedTip" class="save-tip">{{ savedTip }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { modelApi, settingsApi } from '@/api'

const { t } = useI18n()
const savedTip = ref('')
const settings = reactive({
  sttEnabled: false,
  sttProvider: 'auto',
  sttFallbackEnabled: true,
  // Issue #76: route OpenAI Whisper provider to any OpenAI-compat credential row.
  sttOpenAiCompatProviderId: 'openai',
  sttOpenAiCompatModel: 'whisper-1',
})

interface ProviderRow {
  id: string
  name: string
  chatModel?: string
  protocol?: string
}

const providers = ref<ProviderRow[]>([])

/**
 * Issue #76: surface every OpenAI-compatible credential row as a STT
 * endpoint candidate. This includes the bundled OpenAI provider, Kimi /
 * DeepSeek / Together / SiliconFlow / Groq / Volcano / Qiniu (all share
 * `OpenAIChatModel`), and any user-added custom provider that picks the
 * OpenAI-compatible protocol. The user's self-hosted FunASR fits the
 * latter — they add a custom provider with baseUrl http://internal/v1
 * and select it here.
 */
const openAiCompatProviders = computed<ProviderRow[]>(() => {
  const list = providers.value.filter(p =>
    (p.chatModel === 'OpenAIChatModel') || (p.protocol === 'openai-compatible')
  )
  if (list.some(p => p.id === settings.sttOpenAiCompatProviderId)) return list
  // Always render the currently-saved id even if its row was disabled / removed,
  // so the user can see what's persisted instead of silent fallback to "openai".
  return [
    ...list,
    { id: settings.sttOpenAiCompatProviderId, name: settings.sttOpenAiCompatProviderId },
  ]
})

onMounted(async () => {
  await Promise.all([loadProviders(), loadSettings()])
})

async function loadProviders() {
  try {
    const res: any = await modelApi.listProviders()
    providers.value = (res?.data || []).map((p: any) => ({
      id: p.id,
      name: p.name,
      chatModel: p.chatModel,
      protocol: p.protocol,
    }))
  } catch {
    providers.value = []
  }
}

async function loadSettings() {
  const res: any = await settingsApi.get()
  const d = res.data || {}
  settings.sttEnabled = d.sttEnabled ?? false
  settings.sttProvider = d.sttProvider ?? 'auto'
  settings.sttFallbackEnabled = d.sttFallbackEnabled ?? true
  settings.sttOpenAiCompatProviderId = d.sttOpenAiCompatProviderId ?? 'openai'
  settings.sttOpenAiCompatModel = d.sttOpenAiCompatModel ?? 'whisper-1'
}

async function onSaveSettings() {
  await settingsApi.update({
    sttEnabled: settings.sttEnabled,
    sttProvider: settings.sttProvider,
    sttFallbackEnabled: settings.sttFallbackEnabled,
    sttOpenAiCompatProviderId: settings.sttOpenAiCompatProviderId,
    sttOpenAiCompatModel: settings.sttOpenAiCompatModel,
  })
  await loadSettings()
  savedTip.value = t('settings.messages.saveSuccess')
  setTimeout(() => { savedTip.value = '' }, 2500)
}
</script>

<style scoped>
.settings-section { width: 100%; }
.section-header { display: flex; flex-direction: column; gap: 6px; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }
.settings-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; padding: 18px; box-shadow: 0 8px 24px rgba(124,63,30,0.04); width: 100%; }
.setting-item { display: flex; justify-content: space-between; gap: 20px; padding: 16px 0; border-bottom: 1px solid var(--mc-border-light); }
.setting-item:last-child { border-bottom: none; }
.setting-info { flex: 1; }
.setting-label { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 4px; }
.setting-hint { font-size: 13px; color: var(--mc-text-secondary); }
.setting-control { width: 220px; display: flex; align-items: center; justify-content: flex-end; }
.form-input { width: 100%; border: 1px solid var(--mc-border); border-radius: 10px; padding: 10px 12px; font-size: 14px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.form-input:disabled { opacity: 0.5; cursor: not-allowed; }
.toggle-switch { position: relative; display: inline-flex; width: 44px; height: 24px; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; cursor: pointer; background: var(--mc-border); border-radius: 999px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 18px; height: 18px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(20px); }
.toggle-switch input:disabled + .toggle-slider { opacity: 0.5; cursor: not-allowed; }
.provider-section { margin-top: 24px; }
.provider-header { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.provider-name { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); }
.provider-tag { font-size: 12px; padding: 2px 8px; border-radius: 6px; background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.save-bar { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
.btn-primary, .btn-secondary { border: none; border-radius: 10px; padding: 9px 14px; font-size: 14px; cursor: pointer; transition: all 0.15s; }
.btn-primary { background: var(--mc-primary); color: white; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-secondary { background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); }
.save-tip { position: fixed; right: 24px; bottom: 24px; background: var(--mc-text-primary); color: var(--mc-text-inverse); padding: 10px 14px; border-radius: 10px; box-shadow: 0 10px 30px rgba(124,63,30,0.22); }
</style>
