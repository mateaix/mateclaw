<template>
  <div v-if="show && provider" class="modal-overlay">
    <div class="modal modal-wide">
      <div class="modal-header">
        <h2>
          <span class="modal-provider-icon-shell">
            <img
              :src="getProviderIcon(provider.id)"
              :alt="provider.name"
              class="modal-provider-icon"
              @error="onIconError"
            />
          </span>
          {{ t('settings.model.manageTitle') }} · {{ provider.name }}
        </h2>
        <div class="modal-header-actions">
          <!-- Always visible when the provider supports discovery, so the entry
               point doesn't silently vanish before an API key is set; disabled
               with a hint until the provider is configured (discovery hits the
               live API and needs the key). -->
          <button
            v-if="provider.supportModelDiscovery"
            class="btn-primary discover-btn"
            :class="{ 'is-discovering': discovering }"
            :disabled="discovering || !provider.configured"
            :title="!provider.configured ? t('settings.model.discovery.configureFirst') : ''"
            @click="$emit('discover')"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
              <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
            </svg>
            {{ discovering ? t('settings.model.discovery.discovering') : t('settings.model.discovery.discover') }}
          </button>
          <button class="modal-close" @click="$emit('close')">×</button>
        </div>
      </div>
      <div class="modal-body">
        <!-- Discovery results panel -->
        <div v-if="discoverResult" class="discover-panel">
          <div class="discover-header">
            <h3>{{ t('settings.model.discovery.discoveredTitle') }}</h3>
            <span class="discover-count">
              {{ t('settings.model.discovery.discoveredCount', { total: discoverResult.totalDiscovered, count: discoverResult.newCount }) }}
            </span>
          </div>
          <div v-if="discoverResult.newCount === 0" class="discover-empty">
            {{ t('settings.model.discovery.noNewModels') }}
          </div>
          <div v-else class="discover-list">
            <label class="discover-select-all">
              <input type="checkbox" :checked="allNewSelected" @change="$emit('toggleSelectAll')" />
              {{ t('settings.model.discovery.selectAll') }}
            </label>
            <div v-for="model in discoverResult.newModels" :key="model.id" class="discover-item">
              <label class="discover-checkbox">
                <input type="checkbox" :value="model.id" :checked="selectedNewModelIds.includes(model.id)" @change="$emit('toggleModel', model.id)" />
                <span class="discover-model-name">{{ model.name }}</span>
                <span class="discover-model-id">{{ model.id }}</span>
                <span
                  v-if="model.probeOk === true"
                  class="probe-badge probe-ok"
                  :title="t('settings.model.discovery.probeVerifiedTitle')"
                >
                  ✓ {{ t('settings.model.discovery.probeVerified') }}
                </span>
              </label>
            </div>
            <div class="discover-actions">
              <button
                class="btn-primary"
                :disabled="selectedNewModelIds.length === 0 || applyingModels"
                @click="$emit('applyModels')"
              >
                {{ applyingModels ? t('settings.model.discovery.adding') : t('settings.model.discovery.addSelected') }}
                ({{ selectedNewModelIds.length }})
              </button>
            </div>
            <!-- Show models that were discovered but failed the probe, so users see
                 why they are not offered in the "add selected" list -->
            <div v-if="discoveredUnavailable.length > 0" class="discover-unavailable">
              <div class="discover-unavailable-title">
                {{ t('settings.model.discovery.probeFailedBanner', { count: discoveredUnavailable.length }) }}
              </div>
              <div v-for="model in discoveredUnavailable" :key="model.id" class="discover-unavailable-item">
                <span class="discover-model-id">{{ model.id }}</span>
                <span class="discover-unavailable-reason">{{ model.probeError || t('settings.model.discovery.probeUnreachable') }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Model list -->
        <div class="model-list">
          <div
            v-for="model in [...(provider.models || []), ...(provider.extraModels || [])]"
            :key="model.id"
            class="model-list-item"
          >
            <div>
              <div class="model-list-name">{{ model.name }}</div>
              <div class="model-list-id">{{ model.id }}</div>
              <div v-if="modelTestResults[model.id]" class="model-test-result" :class="modelTestResults[model.id].success ? 'success' : 'error'">
                <span v-if="modelTestResults[model.id].success">
                  {{ t('settings.model.discovery.modelOk') }} · {{ t('settings.model.discovery.latency', { ms: modelTestResults[model.id].latencyMs }) }}
                </span>
                <span v-else>
                  {{ modelTestResults[model.id].errorMessage }}
                </span>
              </div>
            </div>
            <div class="model-list-actions">
              <span class="provider-badge" :class="isExtraModel(model.id) ? 'custom' : 'builtin'">
                {{ isExtraModel(model.id) ? t('settings.model.custom') : t('settings.model.builtin') }}
              </span>
              <button
                class="card-btn test-btn"
                :disabled="testingModelId === model.id"
                @click="$emit('testModel', model)"
              >
                {{ testingModelId === model.id ? t('settings.model.discovery.testingModel') : t('settings.model.discovery.testModel') }}
              </button>
              <button
                class="card-btn"
                :disabled="isActiveModel(model)"
                @click="$emit('setActive', model)"
              >
                {{ isActiveModel(model) ? t('settings.model.active') : t('settings.model.setActive') }}
              </button>
              <button
                v-if="isExtraModel(model.id)"
                class="card-btn danger"
                @click="$emit('removeModel', model)"
              >
                {{ t('common.delete') }}
              </button>
            </div>
          </div>
        </div>

        <!-- Add model form -->
        <div class="model-add-box">
          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ t('settings.model.fields.modelId') }}</label>
              <input v-model="modelForm.id" class="form-input" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('settings.model.fields.modelDisplayName') }}</label>
              <input v-model="modelForm.name" class="form-input" />
            </div>
          </div>
          <div class="modal-footer compact">
            <button class="btn-primary" @click="$emit('addModel')">{{ t('settings.model.addModel') }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DiscoverResult, ProviderInfo, ProviderModelInfo, TestResult } from '@/types'

const { t } = useI18n()

const props = defineProps<{
  show: boolean
  provider: ProviderInfo | null
  modelForm: { id: string; name: string }
  discovering: boolean
  discoverResult: DiscoverResult | null
  selectedNewModelIds: string[]
  applyingModels: boolean
  allNewSelected: boolean
  testingModelId: string | null
  modelTestResults: Record<string, TestResult>
  isExtraModel: (modelId: string) => boolean
  isActiveModel: (model: ProviderModelInfo) => boolean
  getProviderIcon: (providerId: string) => string
  onIconError: (e: Event) => void
}>()

// Models from discovery that failed the probe — shown in a warning block
// so users understand why they are not in the "add selected" list
const discoveredUnavailable = computed(() => {
  const all = props.discoverResult?.discoveredModels || []
  return all.filter(m => m && m.probeOk === false)
})

defineEmits<{
  close: []
  discover: []
  toggleSelectAll: []
  toggleModel: [modelId: string]
  applyModels: []
  testModel: [model: ProviderModelInfo]
  setActive: [model: ProviderModelInfo]
  removeModel: [model: ProviderModelInfo]
  addModel: []
}>()
</script>

<style scoped>
.modal-overlay { position: fixed; inset: 0; background: rgba(124, 63, 30, 0.45); display: flex; align-items: center; justify-content: center; padding: 20px; z-index: 40; }
.modal { width: min(760px, 100%); max-height: calc(100vh - 40px); background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 18px; overflow: hidden; display: flex; flex-direction: column; }
.modal-wide { width: min(860px, 100%); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { color: var(--mc-text-primary); margin: 0; font-size: 18px; display: flex; align-items: center; }
.modal-header-actions { display: flex; align-items: center; gap: 10px; }
.modal-provider-icon-shell {
  width: 38px;
  height: 38px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-right: 8px;
  padding: 7px;
  border-radius: 12px;
  border: 1px solid rgba(123, 88, 67, 0.18);
  background: linear-gradient(180deg, #ffffff, #f5ede6);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 6px 16px rgba(25, 14, 8, 0.14);
  flex-shrink: 0;
}

.modal-provider-icon {
  width: 100%;
  height: 100%;
  object-fit: contain;
  vertical-align: middle;
  filter: drop-shadow(0 1px 1px rgba(44, 24, 10, 0.12));
}

:global(html.dark) .modal-provider-icon-shell {
  border-color: rgba(255, 248, 241, 0.28);
  background: linear-gradient(180deg, #fffdfb, #f3e8dc);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.96),
    0 8px 22px rgba(0, 0, 0, 0.26);
}

:global(html.dark) .modal-provider-icon {
  filter: drop-shadow(0 1px 1px rgba(44, 24, 10, 0.18));
}
.modal-close { background: none; border: none; font-size: 24px; line-height: 1; cursor: pointer; color: var(--mc-text-secondary); }
.modal-close:hover { color: var(--mc-text-primary); }
.modal-body { padding: 20px; overflow-y: auto; flex: 1; min-height: 0; }
.modal-footer.compact { padding: 12px 0 0; border: none; display: flex; justify-content: flex-end; }

.btn-primary, .card-btn { border: none; border-radius: 10px; padding: 9px 14px; font-size: 14px; cursor: pointer; transition: all 0.15s; }
.btn-primary { background: var(--mc-primary); color: white; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.card-btn { background: var(--mc-primary-bg); color: var(--mc-primary); }
.card-btn:hover { background: rgba(217, 119, 87, 0.18); }
.card-btn.danger { background: var(--mc-danger-bg); color: var(--mc-danger); }
.test-btn { font-size: 12px; padding: 4px 10px; }

.discover-btn { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; padding: 6px 12px; }
.discover-btn svg { flex-shrink: 0; }
/* Spin only while actually discovering — not when disabled for a missing key. */
.discover-btn.is-discovering svg { animation: spin 1s linear infinite; }
/* Disabled-for-missing-key state reads as inactive (the discovering state keeps full opacity + spinner). */
.discover-btn:disabled:not(.is-discovering) { opacity: 0.5; cursor: not-allowed; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

.discover-panel { margin-bottom: 16px; padding: 14px; border: 1px solid var(--mc-primary-bg); border-radius: 12px; background: var(--mc-primary-bg); }
.discover-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.discover-header h3 { margin: 0; font-size: 14px; color: var(--mc-text-primary); }
.discover-count { font-size: 12px; color: var(--mc-text-secondary); }
.discover-empty { font-size: 13px; color: var(--mc-text-tertiary); text-align: center; padding: 12px; }
.discover-list { display: grid; gap: 6px; }
.discover-select-all { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; color: var(--mc-text-secondary); padding: 4px 0; cursor: pointer; }
.discover-item { display: flex; align-items: center; }
.discover-checkbox { display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 13px; width: 100%; padding: 6px 8px; border-radius: 8px; transition: background 0.15s; }
.discover-checkbox:hover { background: var(--mc-bg-sunken); }
.discover-model-name { font-weight: 500; color: var(--mc-text-primary); }
.discover-model-id { color: var(--mc-text-tertiary); margin-left: auto; font-size: 12px; }
.probe-badge { font-size: 10px; padding: 1px 6px; border-radius: 4px; font-weight: 600; margin-left: 6px; }
.probe-ok { background: rgba(34, 197, 94, 0.12); color: rgb(21, 128, 61); }
.discover-unavailable { margin-top: 12px; padding: 10px 12px; border-radius: 8px; background: rgba(234, 179, 8, 0.08); border: 1px solid rgba(234, 179, 8, 0.3); }
.discover-unavailable-title { font-size: 12px; font-weight: 600; color: rgb(161, 98, 7); margin-bottom: 6px; }
.discover-unavailable-item { display: flex; justify-content: space-between; align-items: center; padding: 3px 0; font-size: 12px; }
.discover-unavailable-reason { color: var(--mc-text-tertiary); margin-left: 10px; max-width: 60%; text-align: right; text-overflow: ellipsis; overflow: hidden; white-space: nowrap; }
.discover-actions { display: flex; justify-content: flex-end; margin-top: 8px; }

.model-list { display: grid; gap: 12px; }
.model-list-item { display: flex; justify-content: space-between; gap: 12px; align-items: center; padding: 12px 14px; border: 1px solid var(--mc-border); border-radius: 12px; }
.model-list-name { font-weight: 600; color: var(--mc-text-primary); }
.model-list-id { font-size: 12px; color: var(--mc-text-secondary); }
.model-list-actions { display: flex; align-items: center; gap: 8px; }
.model-add-box { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--mc-border-light); }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.form-label { display: block; font-size: 13px; color: var(--mc-text-secondary); margin-bottom: 6px; }
.form-input { width: 100%; border: 1px solid var(--mc-border); border-radius: 10px; padding: 10px 12px; font-size: 14px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.form-input:focus { outline: none; border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1); }

.provider-badge { display: inline-flex; align-items: center; border-radius: 999px; padding: 3px 9px; font-size: 12px; font-weight: 600; }
.provider-badge.builtin { background: var(--mc-primary-bg); color: var(--mc-primary); }
.provider-badge.custom { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }

.model-test-result { margin-top: 4px; font-size: 11px; }
.model-test-result.success { color: var(--mc-primary); }
.model-test-result.error { color: var(--mc-danger); }
</style>
