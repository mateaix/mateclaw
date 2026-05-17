<template>
  <div class="provider-group embedding-section">
    <h3 class="group-title">
      <svg class="group-title__icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="12" cy="12" r="10"/>
        <circle cx="12" cy="12" r="4"/>
        <line x1="4.93" y1="4.93" x2="9.17" y2="9.17"/>
        <line x1="14.83" y1="14.83" x2="19.07" y2="19.07"/>
        <line x1="14.83" y1="9.17" x2="19.07" y2="4.93"/>
        <line x1="4.93" y1="19.07" x2="9.17" y2="14.83"/>
      </svg>
      {{ t('settings.model.embedding.title') }}
      <span class="group-hint">{{ t('settings.model.embedding.hint') }}</span>
    </h3>

    <div v-if="loading" class="loading-state">{{ t('settings.model.embedding.loading') }}</div>

    <template v-else>
      <div v-if="models.length === 0" class="empty-state">
        {{ t('settings.model.embedding.empty') }}
      </div>

      <div v-else class="embedding-grid">
        <div v-for="model in models" :key="model.id" class="embedding-card">
          <div class="embedding-card-header">
            <div class="embedding-name">
              {{ model.name }}
              <span v-if="String(model.id) === defaultModelId" class="default-badge">
                {{ t('settings.model.embedding.defaultBadge') }}
              </span>
              <span v-if="model.builtin" class="builtin-badge">
                {{ t('settings.model.embedding.builtinBadge') }}
              </span>
            </div>
            <span class="provider-badge">{{ model.provider }}</span>
          </div>
          <div class="embedding-model-id">{{ model.modelName }}</div>
          <div v-if="model.description" class="embedding-desc">{{ model.description }}</div>

          <!-- Connectivity test result -->
          <div
            v-if="testResults[String(model.id)]"
            class="test-result"
            :class="testResults[String(model.id)].success ? 'success' : 'error'"
          >
            <span v-if="testResults[String(model.id)].success">
              ✓ {{ t('settings.model.embedding.testPassed', { dim: testResults[String(model.id)].dimensions }) }}
            </span>
            <span v-else>✗ {{ testResults[String(model.id)].message }}</span>
          </div>

          <div class="embedding-actions">
            <button
              class="card-btn test-btn"
              :disabled="testingId === String(model.id)"
              @click="onTest(model)"
            >
              {{ testingId === String(model.id) ? t('settings.model.embedding.testing') : t('settings.model.embedding.test') }}
            </button>
            <button
              v-if="String(model.id) !== defaultModelId"
              class="card-btn"
              @click="onSetDefault(model)"
            >
              {{ t('settings.model.embedding.setDefault') }}
            </button>
            <button
              v-if="!model.builtin"
              class="card-btn danger"
              @click="onDelete(model)"
            >
              {{ t('settings.model.embedding.delete') }}
            </button>
          </div>
        </div>
      </div>

      <!-- Add embedding model -->
      <div class="embedding-add-box">
        <div class="add-box-title">{{ t('settings.model.embedding.addTitle') }}</div>
        <div class="add-box-hint">{{ t('settings.model.embedding.addHint') }}</div>

        <div v-if="providers.length === 0" class="no-providers">
          {{ t('settings.model.embedding.noProviders') }}
        </div>

        <template v-else>
          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ t('settings.model.embedding.provider') }}</label>
              <select v-model="form.providerId" class="form-input">
                <option value="" disabled>{{ t('settings.model.embedding.selectProvider') }}</option>
                <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('settings.model.embedding.modelName') }}</label>
              <input
                v-model.trim="form.modelName"
                class="form-input"
                :placeholder="t('settings.model.embedding.modelNamePlaceholder')"
              />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('settings.model.embedding.displayName') }}</label>
              <input
                v-model.trim="form.displayName"
                class="form-input"
                :placeholder="t('settings.model.embedding.displayNamePlaceholder')"
              />
            </div>
          </div>
          <div class="add-actions">
            <button
              class="btn-primary"
              :disabled="adding || !form.providerId || !form.modelName"
              @click="onAdd"
            >
              {{ adding ? t('settings.model.embedding.adding') : t('settings.model.embedding.add') }}
            </button>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { modelApi } from '@/api'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'

interface EmbeddingModel {
  id: string | number
  name: string
  provider: string
  modelName: string
  description?: string
  enabled?: boolean
  isDefault?: boolean
  builtin?: boolean
}

interface ProviderOption {
  id: string
  name: string
}

const { t } = useI18n()

const models = ref<EmbeddingModel[]>([])
const providers = ref<ProviderOption[]>([])
const loading = ref(false)
const defaultModelId = ref<string>('')
const testingId = ref<string>('')
const adding = ref(false)
const testResults = ref<Record<string, { success: boolean; dimensions?: number; message?: string }>>({})

const form = reactive({
  providerId: '',
  modelName: '',
  displayName: '',
})

async function loadAll() {
  loading.value = true
  try {
    const [listRes, defaultRes, providerRes] = await Promise.all([
      modelApi.listByType('embedding'),
      modelApi.getDefaultEmbedding(),
      modelApi.listProviders(),
    ])
    models.value = (listRes.data as EmbeddingModel[]) || []
    defaultModelId.value = String((defaultRes.data as any)?.defaultModelId || '')
    providers.value = ((providerRes.data as any[]) || []).map(p => ({ id: p.id, name: p.name }))
  } catch (e: any) {
    console.error('[EmbeddingModels] Load failed:', e?.message)
  } finally {
    loading.value = false
  }
}

async function onTest(model: EmbeddingModel) {
  testingId.value = String(model.id)
  try {
    const res = await modelApi.testEmbedding(model.id)
    const data = res.data as any
    testResults.value[String(model.id)] = {
      success: !!data?.success,
      dimensions: data?.dimensions,
      message: data?.message,
    }
  } catch (e: any) {
    testResults.value[String(model.id)] = {
      success: false,
      message: e?.message || t('settings.model.embedding.addFailed'),
    }
  } finally {
    testingId.value = ''
  }
}

async function onSetDefault(model: EmbeddingModel) {
  try {
    await modelApi.setDefaultEmbedding(model.id)
    defaultModelId.value = String(model.id)
  } catch (e: any) {
    mcToast.error(e?.message || t('settings.model.embedding.addFailed'))
  }
}

async function onAdd() {
  const modelName = form.modelName.trim()
  if (!form.providerId || !modelName) {
    mcToast.error(t('settings.model.embedding.nameRequired'))
    return
  }
  adding.value = true
  try {
    await modelApi.create({
      name: form.displayName.trim() || modelName,
      provider: form.providerId,
      modelName,
      modelType: 'embedding',
      description: '',
      builtin: false,
      isDefault: false,
      enabled: true,
    })
    form.modelName = ''
    form.displayName = ''
    mcToast.success(t('settings.model.embedding.added'))
    await loadAll()
  } catch (e: any) {
    mcToast.error(e?.message || t('settings.model.embedding.addFailed'))
  } finally {
    adding.value = false
  }
}

async function onDelete(model: EmbeddingModel) {
  const ok = await mcConfirm({
    title: t('common.confirm'),
    message: t('settings.model.embedding.deleteConfirm', { name: model.name }),
    confirmText: t('settings.model.embedding.delete'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await modelApi.delete(model.id)
    // The deleted model may still be referenced as the system default — clear
    // it so wiki semantic search falls back instead of resolving a dead id.
    if (String(model.id) === defaultModelId.value) {
      await modelApi.setDefaultEmbedding('')
    }
    mcToast.success(t('settings.model.embedding.deleted'))
    await loadAll()
  } catch (e: any) {
    mcToast.error(e?.message || t('settings.model.embedding.deleteFailed'))
  }
}

onMounted(loadAll)
defineExpose({ refresh: loadAll })
</script>

<style scoped>
.embedding-section {
  margin-top: 24px;
}
/* Mirror the flex layout used by .group-title in index.vue — scoped styles
 * don't cross component boundaries, so without this the icon stacks above
 * the title instead of sitting inline. */
.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 14px;
  font-size: 16px;
  font-weight: 600;
  color: var(--mc-text-primary);
}
.group-title__icon {
  flex-shrink: 0;
  color: var(--mc-text-secondary);
}
.group-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--mc-text-tertiary);
  margin-left: 4px;
}
.loading-state, .empty-state {
  padding: 32px;
  text-align: center;
  color: var(--mc-text-tertiary);
  background: var(--mc-bg-sunken);
  border-radius: 8px;
}

.embedding-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}
.embedding-card {
  padding: 16px;
  background: var(--mc-bg-surface);
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.embedding-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.embedding-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--mc-text-primary);
  display: flex;
  align-items: center;
  gap: 6px;
}
.default-badge {
  font-size: 10px;
  padding: 2px 6px;
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  border-radius: 4px;
  font-weight: 600;
}
.builtin-badge {
  font-size: 10px;
  padding: 2px 6px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
  border-radius: 4px;
  font-weight: 600;
}
.provider-badge {
  font-size: 11px;
  padding: 2px 8px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
  border-radius: 999px;
}
.embedding-model-id {
  font-size: 12px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  color: var(--mc-text-tertiary);
}
.embedding-desc {
  font-size: 12px;
  color: var(--mc-text-secondary);
  line-height: 1.5;
}
.test-result {
  font-size: 12px;
  padding: 6px 8px;
  border-radius: 4px;
}
.test-result.success {
  background: rgba(34, 197, 94, 0.1);
  color: rgb(21, 128, 61);
}
.test-result.error {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}
.embedding-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
.card-btn {
  flex: 1;
  padding: 6px 12px;
  font-size: 12px;
  border-radius: 4px;
  border: 1px solid var(--mc-border);
  background: transparent;
  color: var(--mc-text-primary);
  cursor: pointer;
}
.card-btn:hover:not(:disabled) { background: var(--mc-bg-sunken); }
.card-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.test-btn {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  border-color: var(--mc-primary);
}
.card-btn.danger {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
  border-color: var(--mc-danger);
}

.embedding-add-box {
  margin-top: 16px;
  padding: 16px;
  border: 1px dashed var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-sunken);
}
.add-box-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--mc-text-primary);
}
.add-box-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--mc-text-tertiary);
}
.no-providers {
  margin-top: 12px;
  font-size: 13px;
  color: var(--mc-text-secondary);
}
.form-grid {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}
.form-label {
  display: block;
  font-size: 13px;
  color: var(--mc-text-secondary);
  margin-bottom: 6px;
}
.form-input {
  width: 100%;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  padding: 9px 12px;
  font-size: 14px;
  background: var(--mc-bg-surface);
  color: var(--mc-text-primary);
}
.form-input:focus {
  outline: none;
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1);
}
.add-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.btn-primary {
  border: none;
  border-radius: 8px;
  padding: 9px 16px;
  font-size: 14px;
  cursor: pointer;
  background: var(--mc-primary);
  color: white;
}
.btn-primary:hover:not(:disabled) { background: var(--mc-primary-hover); }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
