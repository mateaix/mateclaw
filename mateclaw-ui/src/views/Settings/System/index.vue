<template>
  <div class="settings-section system-section">
    <div class="section-header">
      <h2 class="section-title">{{ t('settings.systemTitle') }}</h2>
      <p class="section-desc">{{ t('settings.systemDesc') }}</p>
    </div>

    <div class="settings-card">
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.language') }}</div>
          <div class="setting-hint">{{ t('settings.hints.language') }}</div>
        </div>
        <div class="setting-control">
          <select v-model="settings.language" class="form-input">
            <option value="zh-CN">{{ t('settings.languageOptions.zhCN') }}</option>
            <option value="en-US">{{ t('settings.languageOptions.enUS') }}</option>
          </select>
        </div>
      </div>

      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.streamEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.streamEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.streamEnabled" type="checkbox" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>

      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.debugMode') }}</div>
          <div class="setting-hint">{{ t('settings.hints.debugMode') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.debugMode" type="checkbox" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>

      <div class="setting-item setting-item-vertical">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.workspaceStorageRoot') }}</div>
          <div class="setting-hint">{{ t('settings.hints.workspaceStorageRoot') }}</div>
        </div>
        <div class="setting-control-full">
          <input
            v-model.trim="settings.workspaceStorageRoot"
            type="text"
            class="form-input"
            :placeholder="t('settings.hints.workspaceStorageRootPlaceholder')"
          />
        </div>
      </div>
    </div>

    <!-- 搜索服务配置 -->
    <div class="section-header" style="margin-top: 32px;">
      <h2 class="section-title">{{ t('settings.searchTitle') }}</h2>
      <p class="section-desc">{{ t('settings.searchDesc') }}</p>
    </div>

    <div class="settings-card">
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.searchEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.searchEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.searchEnabled" type="checkbox" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>

      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.searchProvider') }}</div>
          <div class="setting-hint">{{ t('settings.hints.searchProvider') }}</div>
        </div>
        <div class="setting-control">
          <select v-model="settings.searchProvider" class="form-input" :disabled="!settings.searchEnabled">
            <option v-for="opt in providerOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
        </div>
      </div>

      <div v-if="catalogError" class="setting-item">
        <div class="setting-info">
          <div class="setting-hint catalog-error-hint">⚠ {{ t('settings.searchCatalogError') }}</div>
        </div>
      </div>

      <div v-if="providerCatalog.resolvedId" class="setting-item">
        <div class="setting-info">
          <div class="setting-hint">
            ✓ {{ t('settings.searchResolvedLabel') }}:
            {{ providerCatalog.providers.find(p => p.id === providerCatalog.resolvedId)?.label }}
            （{{ t('settings.searchResolvedSource.' + (resolveSourceLabelKey(providerCatalog.resolvedSource))) }}）
          </div>
        </div>
      </div>

      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.searchFallbackEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.searchFallbackEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.searchFallbackEnabled" type="checkbox" :disabled="!settings.searchEnabled" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>

      <div v-for="entry in providerCatalog.providers" :key="entry.id" class="provider-card">
        <button type="button" class="provider-card-header" @click="toggleExpanded(entry.id)">
          <span class="provider-card-name">{{ entry.label }}</span>
          <!-- In catalog-fallback mode availability is unknown — hide badges rather than guess -->
          <span v-if="!catalogError" class="provider-card-badges">
            <span v-if="entry.id === providerCatalog.resolvedId" class="badge badge-active">{{ t('settings.searchStatusActive') }}</span>
            <span v-else-if="!entry.requiresCredential" class="badge">{{ t('settings.searchStatusNoCredential') }}</span>
            <span v-else-if="entry.available" class="badge badge-ok">{{ t('settings.searchStatusConfigured') }}</span>
            <span v-else class="badge badge-warn">{{ t('settings.searchStatusNotConfigured') }}</span>
          </span>
          <span class="provider-card-chevron">{{ isExpanded(entry.id) ? '▾' : '▸' }}</span>
        </button>

        <div v-if="isExpanded(entry.id)" class="provider-card-body">
          <!-- Plugin-provided entry: no form here, point to the plugin page instead -->
          <p v-if="!entry.builtin" class="setting-hint">
            {{ t('settings.searchPluginManaged', { plugin: entry.pluginName }) }}
            <router-link to="/plugins">{{ t('settings.searchGoToPlugins') }}</router-link>
          </p>

          <!-- Built-in providers: same fields/logic as before, keyed by id -->
          <template v-if="entry.id === 'serper'">
            <div class="setting-item setting-item-vertical">
              <div class="setting-info">
                <div class="setting-label">{{ t('settings.fields.serperApiKey') }}</div>
                <div class="setting-hint">{{ t('settings.hints.serperApiKey') }}</div>
              </div>
              <div class="setting-control-full">
                <input
                  v-model="serperApiKeyInput"
                  type="password"
                  class="form-input"
                  :placeholder="settings.serperApiKeyMasked || t('settings.model.apiKeyInput')"
                  :disabled="!settings.searchEnabled"
                  autocomplete="off"
                />
              </div>
            </div>
            <div class="setting-item setting-item-vertical">
              <div class="setting-info">
                <div class="setting-label">{{ t('settings.fields.serperBaseUrl') }}</div>
                <div class="setting-hint">{{ t('settings.hints.serperBaseUrl') }}</div>
              </div>
              <div class="setting-control-full">
                <input
                  v-model="settings.serperBaseUrl"
                  type="text"
                  class="form-input"
                  placeholder="https://google.serper.dev/search"
                  :disabled="!settings.searchEnabled"
                />
              </div>
            </div>
          </template>
          <template v-else-if="entry.id === 'tavily'">
            <div class="setting-item setting-item-vertical">
              <div class="setting-info">
                <div class="setting-label">{{ t('settings.fields.tavilyApiKey') }}</div>
                <div class="setting-hint">{{ t('settings.hints.tavilyApiKey') }}</div>
              </div>
              <div class="setting-control-full">
                <input
                  v-model="tavilyApiKeyInput"
                  type="password"
                  class="form-input"
                  :placeholder="settings.tavilyApiKeyMasked || t('settings.model.apiKeyInput')"
                  :disabled="!settings.searchEnabled"
                  autocomplete="off"
                />
              </div>
            </div>
            <div class="setting-item setting-item-vertical">
              <div class="setting-info">
                <div class="setting-label">{{ t('settings.fields.tavilyBaseUrl') }}</div>
                <div class="setting-hint">{{ t('settings.hints.tavilyBaseUrl') }}</div>
              </div>
              <div class="setting-control-full">
                <input
                  v-model="settings.tavilyBaseUrl"
                  type="text"
                  class="form-input"
                  placeholder="https://api.tavily.com/search"
                  :disabled="!settings.searchEnabled"
                />
              </div>
            </div>
          </template>
          <template v-else-if="entry.id === 'duckduckgo'">
            <div class="setting-item">
              <div class="setting-info">
                <div class="setting-label">{{ t('settings.fields.duckduckgoEnabled') }}</div>
                <div class="setting-hint">{{ t('settings.hints.duckduckgoEnabled') }}</div>
              </div>
              <div class="setting-control">
                <label class="toggle-switch">
                  <input v-model="settings.duckduckgoEnabled" type="checkbox" :disabled="!settings.searchEnabled" />
                  <span class="toggle-slider"></span>
                </label>
              </div>
            </div>
          </template>
          <template v-else-if="entry.id === 'searxng'">
            <div class="setting-item setting-item-vertical">
              <div class="setting-info">
                <div class="setting-label">{{ t('settings.fields.searxngBaseUrl') }}</div>
                <div class="setting-hint">{{ t('settings.hints.searxngBaseUrl') }}</div>
              </div>
              <div class="setting-control-full">
                <input
                  v-model="settings.searxngBaseUrl"
                  type="text"
                  class="form-input"
                  placeholder="http://searxng:8080"
                  :disabled="!settings.searchEnabled"
                />
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>

    <div class="settings-section system-section">
      <h2 class="section-title">{{ t('settings.weixinoaTitle') }}</h2>
      <p class="section-desc">{{ t('settings.weixinoaDesc') }}</p>
      <div class="settings-card">
        <div class="setting-item setting-item-vertical">
          <div class="setting-info">
            <div class="setting-label">{{ t('settings.fields.weixinoaAppId') }}</div>
            <div class="setting-hint">{{ t('settings.hints.weixinoaAppId') }}</div>
          </div>
          <div class="setting-control setting-control-full">
            <input
              v-model="settings.weixinoaAppId"
              type="text"
              class="form-input"
              placeholder="wx1234567890abcdef"
            />
          </div>
        </div>
        <div class="setting-item setting-item-vertical">
          <div class="setting-info">
            <div class="setting-label">{{ t('settings.fields.weixinoaAppSecret') }}</div>
            <div class="setting-hint">{{ t('settings.hints.weixinoaAppSecret') }}</div>
          </div>
          <div class="setting-control setting-control-full">
            <input
              v-model="weixinoaAppSecretInput"
              type="password"
              class="form-input"
              autocomplete="new-password"
              :placeholder="settings.weixinoaAppSecretMasked || t('settings.model.apiKeyInput')"
            />
          </div>
        </div>
      </div>
    </div>

    <div class="save-bar">
      <button class="btn-secondary" @click="loadSettings">{{ t('common.reset') }}</button>
      <button class="btn-primary" @click="onSaveSettings">{{ t('settings.actions.saveSystem') }}</button>
    </div>

    <div v-if="savedTip" class="save-tip">{{ savedTip }}</div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { settingsApi } from '@/api'
import { applyLocale } from '@/i18n'
import { mcToast } from '@/composables/useMcToast'
import { useSystemSettingsStore } from '@/stores/useSystemSettingsStore'
import { buildProviderOptions, builtinFallbackCatalog, resolveDefaultExpandedId, resolveSourceLabelKey } from '@/composables/useSearchProviderCatalog'
import type { SystemSettings, SearchProviderCatalog } from '@/types'

const { t } = useI18n()
const systemSettingsStore = useSystemSettingsStore()
const savedTip = ref('')

// API Key 独立管理，不回显明文
const serperApiKeyInput = ref('')
const tavilyApiKeyInput = ref('')
const weixinoaAppSecretInput = ref('')

const providerCatalog = ref<SearchProviderCatalog>({ providers: [], resolvedId: null, resolvedSource: null })
const expandedProviderId = ref<string | null>(null)
const catalogError = ref(false)

// Pass the saved provider id so it stays selectable even when the catalog
// doesn't contain it (fetch failure / owning plugin disabled) — otherwise the
// select renders blank and a save would silently rewrite the value to ''.
const providerOptions = computed(() =>
  buildProviderOptions(providerCatalog.value, t('settings.fields.searchProviderAuto'), settings.searchProvider))

function isExpanded(id: string) {
  return expandedProviderId.value === id
}
function toggleExpanded(id: string) {
  expandedProviderId.value = isExpanded(id) ? null : id
}

async function loadProviderCatalog() {
  try {
    const res: any = await settingsApi.getSearchProviders()
    providerCatalog.value = res.data || { providers: [], resolvedId: null, resolvedSource: null }
    catalogError.value = false
    expandedProviderId.value = resolveDefaultExpandedId(providerCatalog.value)
  } catch {
    // Catalog outage must not hide the search config: fall back to the four
    // built-in providers (their forms bind to plain settings fields and never
    // needed the catalog) and surface a visible warning instead.
    catalogError.value = true
    providerCatalog.value = builtinFallbackCatalog()
    expandedProviderId.value = null
  }
}

const settings = reactive<SystemSettings>({
  language: 'zh-CN',
  streamEnabled: true,
  debugMode: false,
  workspaceStorageRoot: '',
  searchEnabled: true,
  searchProvider: 'serper',
  searchFallbackEnabled: false,
  serperBaseUrl: 'https://google.serper.dev/search',
  tavilyBaseUrl: 'https://api.tavily.com/search',
  duckduckgoEnabled: true,
  searxngBaseUrl: '',
  weixinoaAppId: '',
})

onMounted(async () => {
  await Promise.all([loadSettings(), loadProviderCatalog()])
})

async function loadSettings() {
  const res: any = await settingsApi.get()
  Object.assign(settings, res.data || {})
  // Keep the runtime store in sync so chat honors the latest toggles.
  systemSettingsStore.apply(settings)
  // 清空 API Key 输入框（不回显明文）
  serperApiKeyInput.value = ''
  tavilyApiKeyInput.value = ''
  weixinoaAppSecretInput.value = ''
}

async function onSaveSettings() {
  const payload: any = { ...settings }
  // 仅在用户输入了新值时才提交 API Key
  if (serperApiKeyInput.value) {
    payload.serperApiKey = serperApiKeyInput.value
  }
  if (tavilyApiKeyInput.value) {
    payload.tavilyApiKey = tavilyApiKeyInput.value
  }
  if (weixinoaAppSecretInput.value) {
    payload.weixinoaAppSecret = weixinoaAppSecretInput.value
  }
  try {
    await settingsApi.update(payload)
  } catch (e: any) {
    // Surface backend validation failures (e.g. invalid storage path).
    mcToast.error(e?.response?.data?.msg || e?.message || t('settings.messages.saveFailed'))
    return
  }
  await applyLocale(settings.language)
  // 重新加载以获取最新脱敏值
  await loadSettings()
  showSavedTip(t('settings.messages.saveSuccess'))
}

function showSavedTip(message: string) {
  savedTip.value = message
  window.setTimeout(() => { savedTip.value = '' }, 2500)
}
</script>

<style scoped>
.settings-section { width: 100%; }
.settings-section.system-section { max-width: none; }
.section-header { display: flex; flex-direction: column; gap: 6px; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }

.settings-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; padding: 18px; box-shadow: 0 8px 24px rgba(124, 63, 30, 0.04); width: 100%; }
.setting-item { display: flex; justify-content: space-between; gap: 20px; padding: 16px 0; border-bottom: 1px solid var(--mc-border-light); }
.setting-item:last-child { border-bottom: none; }
.setting-item-vertical { flex-direction: column; gap: 10px; }
.setting-info { flex: 1; }
.setting-label { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 4px; }
.setting-hint { font-size: 13px; color: var(--mc-text-secondary); }
.setting-control { width: 220px; display: flex; align-items: center; justify-content: flex-end; }
.setting-control-full { width: 100%; }
.form-input { width: 100%; border: 1px solid var(--mc-border); border-radius: 10px; padding: 10px 12px; font-size: 14px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.form-input:focus { outline: none; border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1); }
.form-input:disabled { opacity: 0.5; cursor: not-allowed; }

.toggle-switch { position: relative; display: inline-flex; width: 44px; height: 24px; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; cursor: pointer; background: var(--mc-border); border-radius: 999px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 18px; height: 18px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(20px); }
.toggle-switch input:disabled + .toggle-slider { opacity: 0.5; cursor: not-allowed; }

.save-bar { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }
.btn-primary, .btn-secondary { border: none; border-radius: 10px; padding: 9px 14px; font-size: 14px; cursor: pointer; transition: all 0.15s; }
.btn-primary { background: var(--mc-primary); color: white; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-secondary { background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

.save-tip { position: fixed; right: 24px; bottom: 24px; background: var(--mc-text-primary); color: var(--mc-text-inverse); padding: 10px 14px; border-radius: 10px; box-shadow: 0 10px 30px rgba(124, 63, 30, 0.22); }

.provider-card { border: 1px solid var(--mc-border); border-radius: 12px; margin-bottom: 12px; overflow: hidden; }
.provider-card-header { display: flex; align-items: center; gap: 10px; width: 100%; padding: 12px 16px; border: none; background: var(--mc-bg-elevated); color: inherit; font: inherit; text-align: left; cursor: pointer; }
.provider-card-name { font-weight: 600; flex: 1; }
.provider-card-badges { display: flex; gap: 6px; }
.badge { font-size: 12px; padding: 2px 8px; border-radius: 999px; background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.badge-active { background: var(--mc-primary); color: white; }
.badge-ok { background: rgba(34, 197, 94, 0.15); color: rgb(22, 163, 74); }
.badge-warn { background: rgba(234, 179, 8, 0.15); color: rgb(161, 98, 7); }
.provider-card-chevron { color: var(--mc-text-secondary); }
.provider-card-body { padding: 4px 16px 16px; }
.catalog-error-hint { color: rgb(161, 98, 7); }

@media (max-width: 900px) {
  .setting-item { flex-direction: column; }
  .setting-control { width: 100%; justify-content: flex-start; }
}
</style>
