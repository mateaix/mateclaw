<template>
  <div v-if="show" class="modal-overlay">
    <div class="modal">
      <div class="modal-header">
        <h2>{{ editingProvider ? t('settings.model.editTitle') : t('settings.model.createTitle') }}</h2>
        <button class="modal-close" @click="$emit('close')">×</button>
      </div>
      <div class="modal-body">
        <div class="form-grid">
          <div class="form-group" v-if="!editingProvider">
            <label class="form-label">{{ t('settings.model.fields.providerId') }}</label>
            <input
              v-model="form.id"
              class="form-input mono"
              :placeholder="t('settings.model.providerIdPlaceholder')"
            />
            <div class="field-hint">{{ t('settings.model.providerIdHint') }}</div>
          </div>
          <div class="form-group" v-if="!editingProvider">
            <label class="form-label">{{ t('settings.model.fields.providerName') }}</label>
            <input v-model="form.name" class="form-input" />
          </div>
          <div class="form-group">
            <label class="form-label">{{ t('settings.model.baseUrl') }}</label>
            <input
              v-model="form.baseUrl"
              class="form-input mono"
              :placeholder="baseUrlPlaceholder"
            />
            <div class="field-hint">{{ baseUrlHint }}</div>
          </div>
          <div v-if="editingProvider?.authType !== 'oauth' && form.protocol === 'openai-compatible'" class="form-group">
            <div class="search-toggle-row">
              <label class="form-label" style="margin-bottom: 0">{{ t('settings.model.fields.requireApiKey') }}</label>
              <label class="toggle-switch">
                <input type="checkbox" v-model="form.requireApiKey" />
                <span class="toggle-slider"></span>
              </label>
            </div>
            <div class="field-hint">{{ t('settings.model.requireApiKeyHint') }}</div>
          </div>
          <!-- OAuth 登录区域（auth_type === 'oauth' 时显示） -->
          <div v-if="editingProvider?.authType === 'oauth'" class="form-group full-width oauth-group">
            <label class="form-label">{{ t('settings.model.oauthTitle') }}</label>
            <div class="oauth-status-row">
              <span v-if="editingProvider?.oauthConnected" class="oauth-badge oauth-connected">
                {{ t('settings.model.oauthConnected') }}
              </span>
              <span v-else class="oauth-badge oauth-disconnected">
                {{ t('settings.model.oauthDisconnected') }}
              </span>
              <button
                v-if="!editingProvider?.oauthConnected"
                class="btn-oauth"
                type="button"
                @click="$emit('oauthLogin', editingProvider?.id)"
              >
                {{ editingProvider?.id === 'anthropic-claude-code'
                  ? t('settings.model.claudeCodeOauthDetect')
                  : t('settings.model.oauthLogin') }}
              </button>
              <button
                v-else
                class="btn-oauth btn-oauth-revoke"
                type="button"
                @click="$emit('oauthRevoke', editingProvider?.id)"
              >
                {{ t('settings.model.oauthDisconnect') }}
              </button>
            </div>
            <div class="field-hint">
              {{ editingProvider?.id === 'anthropic-claude-code'
                ? t('settings.model.claudeCodeOauthHint')
                : t('settings.model.oauthHint') }}
            </div>
          </div>
          <!-- API Key 输入区域（非 OAuth 时显示） -->
          <div v-else-if="form.protocol !== 'openai-compatible' || form.requireApiKey" class="form-group">
            <label class="form-label">{{ t('settings.model.apiKey') }}</label>
            <input
              v-model="form.apiKey"
              type="password"
              class="form-input mono"
              :placeholder="apiKeyPlaceholder"
              autocomplete="off"
            />
            <div class="field-hint">{{ t('settings.model.leaveBlankKeep') }}</div>
          </div>
          <div v-if="editingProvider?.authType !== 'oauth' && (form.protocol !== 'openai-compatible' || form.requireApiKey)" class="form-group">
            <label class="form-label">{{ t('settings.model.fields.apiKeyPrefix') }}</label>
            <input v-model="form.apiKeyPrefix" class="form-input" />
          </div>
          <div class="form-group">
            <label class="form-label">{{ t('settings.model.fields.protocol') }}</label>
            <select
              v-model="form.protocol"
              class="form-input"
              :disabled="!!editingProvider && !editingProvider.isCustom"
            >
              <option v-for="item in protocolOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
            <div class="field-hint">{{ t('settings.model.protocolHint') }}</div>
          </div>
          <!-- 内置搜索开关：仅 DashScope 和 OpenAI 协议显示 -->
          <div
            v-if="form.protocol === 'dashscope-native' || form.protocol === 'openai-compatible'"
            class="form-group full-width search-group"
          >
            <div class="search-toggle-row">
              <label class="form-label" style="margin-bottom: 0">{{ t('settings.model.fields.enableSearch') }}</label>
              <label class="toggle-switch">
                <input type="checkbox" v-model="form.enableSearch" />
                <span class="toggle-slider"></span>
              </label>
            </div>
            <div class="field-hint">{{ t('settings.model.searchHint') }}</div>
            <div v-if="form.enableSearch" class="search-strategy-row">
              <label class="form-label">{{ t('settings.model.fields.searchStrategy') }}</label>
              <select v-model="form.searchStrategy" class="form-input">
                <template v-if="form.protocol === 'dashscope-native'">
                  <option value="">{{ t('settings.model.searchStrategyDefault') }}</option>
                  <option value="agent">agent</option>
                  <option value="agent_max">agent_max</option>
                </template>
                <template v-else>
                  <option value="">{{ t('settings.model.searchStrategyDefault') }}</option>
                  <option value="low">low</option>
                  <option value="medium">medium</option>
                  <option value="high">high</option>
                </template>
              </select>
            </div>
          </div>
          <div class="form-group full-width advanced-group">
            <button type="button" class="advanced-toggle" @click="$emit('toggleAdvanced')">
              <span>{{ t('settings.model.advancedSettings') }}</span>
              <span>{{ advancedOpen ? '−' : '+' }}</span>
            </button>
            <div v-if="advancedOpen" class="advanced-panel">
              <!-- RFC-009 P3.5: failover chain priority editor.
                   0 = excluded; 1..N defines try-order after primary fails. -->
              <label class="form-label">{{ t('settings.model.fields.fallbackPriority') }}</label>
              <input
                v-model.number="form.fallbackPriority"
                type="number"
                min="0"
                max="99"
                step="1"
                class="form-input"
                style="max-width: 160px"
              />
              <div class="field-hint">{{ t('settings.model.fallbackPriorityHint') }}</div>

              <label class="form-label" style="margin-top: 14px">{{ t('settings.model.fields.generateKwargs') }}</label>
              <textarea v-model="form.generateKwargsText" rows="6" class="form-textarea mono"></textarea>
              <div class="field-hint">{{ t('settings.model.advancedHint') }}</div>
            </div>
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn-secondary" @click="$emit('close')">{{ t('common.cancel') }}</button>
        <button class="btn-primary" @click="$emit('save')">
          {{ editingProvider ? t('common.save') : t('common.create') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { ProviderInfo } from '@/types'

const { t } = useI18n()

defineProps<{
  show: boolean
  editingProvider: ProviderInfo | null
  form: {
    id: string
    name: string
    baseUrl: string
    apiKey: string
    apiKeyPrefix: string
    protocol: string
    chatModel: string
    requireApiKey: boolean
    generateKwargsText: string
    enableSearch: boolean
    searchStrategy: string
    fallbackPriority: number
  }
  advancedOpen: boolean
  protocolOptions: Array<{ value: string; label: string }>
  baseUrlPlaceholder: string
  baseUrlHint: string
  apiKeyPlaceholder: string
}>()

defineEmits<{
  close: []
  save: []
  toggleAdvanced: []
  // RFC-062: providerId tells the handler which OAuth flow to dispatch
  // (anthropic-claude-code reuses local creds, openai-chatgpt opens auth URL).
  oauthLogin: [providerId?: string]
  oauthRevoke: [providerId?: string]
}>()
</script>

<style scoped>
.modal-overlay { position: fixed; inset: 0; background: rgba(124, 63, 30, 0.45); display: flex; align-items: center; justify-content: center; padding: 20px; z-index: 40; }
.modal { width: min(760px, 100%); max-height: calc(100vh - 40px); background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 18px; overflow: hidden; display: flex; flex-direction: column; }
.modal-header, .modal-footer { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { color: var(--mc-text-primary); margin: 0; font-size: 18px; display: flex; align-items: center; }
.modal-footer { border-top: 1px solid var(--mc-border-light); border-bottom: none; justify-content: flex-end; gap: 10px; }
.modal-close { background: none; border: none; font-size: 24px; line-height: 1; cursor: pointer; color: var(--mc-text-secondary); }
.modal-close:hover { color: var(--mc-text-primary); }
.modal-body { padding: 20px; overflow-y: auto; flex: 1; min-height: 0; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { display: block; font-size: 13px; color: var(--mc-text-secondary); margin-bottom: 6px; }
.form-input, .form-textarea { width: 100%; border: 1px solid var(--mc-border); border-radius: 10px; padding: 10px 12px; font-size: 14px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.form-input:focus, .form-textarea:focus { outline: none; border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1); }
.form-textarea { resize: vertical; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
.field-hint { margin-top: 6px; font-size: 12px; line-height: 1.5; color: var(--mc-text-tertiary); }
.advanced-group { margin-top: 4px; }
.advanced-toggle { display: flex; align-items: center; justify-content: space-between; width: 100%; padding: 10px 12px; border: 1px solid var(--mc-border); border-radius: 10px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); font-size: 13px; font-weight: 600; cursor: pointer; }
.advanced-panel { margin-top: 10px; padding: 14px; border: 1px solid var(--mc-border); border-radius: 12px; background: var(--mc-bg-sunken); }
.btn-primary, .btn-secondary { border: none; border-radius: 10px; padding: 9px 14px; font-size: 14px; cursor: pointer; transition: all 0.15s; }
.btn-primary { background: var(--mc-primary); color: white; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-secondary { background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

.search-group { margin-top: 4px; }
.search-toggle-row { display: flex; align-items: center; justify-content: space-between; }
.search-strategy-row { margin-top: 10px; }
.toggle-switch { position: relative; display: inline-block; width: 40px; height: 22px; cursor: pointer; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--mc-border); border-radius: 22px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; height: 16px; width: 16px; left: 3px; bottom: 3px; background: white; border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(18px); }

.oauth-group { margin-top: 4px; }
.oauth-status-row { display: flex; align-items: center; gap: 12px; margin-top: 6px; }
.oauth-badge { display: inline-flex; align-items: center; padding: 4px 10px; border-radius: 8px; font-size: 13px; font-weight: 600; }
.oauth-connected { background: rgba(34, 197, 94, 0.12); color: #22c55e; }
.oauth-disconnected { background: rgba(156, 163, 175, 0.12); color: var(--mc-text-tertiary); }
.btn-oauth { border: none; border-radius: 10px; padding: 8px 16px; font-size: 13px; font-weight: 600; cursor: pointer; background: var(--mc-primary); color: white; transition: all 0.15s; }
.btn-oauth:hover { background: var(--mc-primary-hover); }
.btn-oauth-revoke { background: rgba(239, 68, 68, 0.1); color: #ef4444; }
.btn-oauth-revoke:hover { background: rgba(239, 68, 68, 0.2); }

@media (max-width: 900px) {
  .form-grid { grid-template-columns: 1fr; }
}
</style>
