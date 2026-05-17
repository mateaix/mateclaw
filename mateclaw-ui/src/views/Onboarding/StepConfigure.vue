<template>
  <div class="step-configure">
    <!-- Local path: Ollama model discovery -->
    <template v-if="path === 'local'">
      <h3 class="section-title">{{ t('onboarding.selectModel') }}</h3>

      <div v-if="loading" class="loading-text">{{ t('common.loading') }}</div>
      <div v-else-if="models.length === 0" class="empty-text">
        {{ t('onboarding.localDesc') }}
      </div>
      <div v-else class="model-list">
        <label
          v-for="model in models"
          :key="model"
          class="model-radio"
          :class="{ selected: selectedModel === model }"
        >
          <input
            type="radio"
            :value="model"
            v-model="selectedModel"
            class="radio-input"
          />
          <span class="model-name">{{ model }}</span>
        </label>
      </div>

      <button
        class="btn-primary"
        :disabled="!selectedModel || applying"
        @click="applyLocalModel"
      >{{ applying ? t('common.loading') : t('onboarding.setDefault') }}</button>
      <span v-if="applyError" class="test-failed">{{ applyError }}</span>
    </template>

    <!-- Cloud path: provider cards -->
    <template v-else>
      <h3 class="section-title">{{ t('onboarding.cloudTitle') }}</h3>

      <div class="provider-cards">
        <div
          v-for="p in providers"
          :key="p.id"
          class="provider-card"
          :class="{ selected: selectedProvider === p.id }"
          @click="selectedProvider = p.id"
        >
          <span class="provider-name">{{ p.name }}</span>
          <span class="provider-hint">{{ p.keyHint }}</span>
        </div>
      </div>

      <div v-if="selectedProvider" class="api-key-section">
        <label class="input-label">{{ t('onboarding.enterApiKey') }}</label>
        <input
          v-model="apiKey"
          type="password"
          class="text-input"
          :placeholder="providers.find(p => p.id === selectedProvider)?.keyHint"
        />
        <div class="action-row">
          <button
            class="btn-secondary"
            :disabled="!apiKey || testing"
            @click="testConnection"
          >{{ testing ? t('common.loading') : t('onboarding.testConnection') }}</button>
          <span v-if="testResult === 'success'" class="test-success">{{ t('onboarding.testSuccess') }}</span>
          <span v-if="testResult === 'failed'" class="test-failed">{{ t('onboarding.testFailed') }}</span>
        </div>
        <button
          class="btn-primary"
          :disabled="testResult !== 'success' || saving"
          @click="saveCloudConfig"
        >{{ saving ? t('common.loading') : t('onboarding.saveAndContinue') }}</button>
        <p v-if="testResult !== 'success'" class="save-hint">{{ t('onboarding.testBeforeSave') }}</p>
        <span v-if="saveError" class="test-failed">{{ saveError }}</span>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { modelApi } from '@/api/index'

const props = defineProps<{ path: 'local' | 'cloud' }>()
const emit = defineEmits<{ (e: 'done'): void }>()
const { t } = useI18n()

// Local path state
const loading = ref(false)
const models = ref<string[]>([])
const selectedModel = ref('')
const applying = ref(false)
const applyError = ref('')

// Cloud path state
const providers = [
  { id: 'openai', name: 'OpenAI', keyHint: 'sk-...' },
  { id: 'dashscope', name: 'DashScope', keyHint: 'sk-...' },
  { id: 'deepseek', name: 'DeepSeek', keyHint: 'sk-...' },
]
const selectedProvider = ref('')
const apiKey = ref('')
const testing = ref(false)
const testResult = ref<'' | 'success' | 'failed'>('')
const saving = ref(false)
const saveError = ref('')

// A key or provider edit invalidates the previous connection test, so the
// user must re-test before the provider can be enabled.
watch([apiKey, selectedProvider], () => {
  testResult.value = ''
  saveError.value = ''
})

onMounted(async () => {
  if (props.path === 'local') {
    loading.value = true
    try {
      const res: any = await modelApi.discoverModels('ollama')
      // DiscoverResult: { discoveredModels: [...], newModels: [...] }
      const discovered = res?.data?.discoveredModels || res?.data?.newModels || res?.discoveredModels || res?.newModels || []
      if (Array.isArray(discovered)) {
        models.value = discovered.map((m: any) => typeof m === 'string' ? m : m.modelId || m.modelName || m.id || m.name)
      }
    } catch {
      // Ollama not available
    } finally {
      loading.value = false
    }
  }
})

async function applyLocalModel() {
  if (!selectedModel.value) return
  applying.value = true
  applyError.value = ''
  try {
    await modelApi.applyDiscoveredModels('ollama', [selectedModel.value])
    // Local providers ship disabled — enable Ollama so it appears in the
    // model dropdown and the chat runtime can route to it.
    await modelApi.enableProvider('ollama')
    await modelApi.setActive({ providerId: 'ollama', model: selectedModel.value })
    emit('done')
  } catch (err) {
    applyError.value = err instanceof Error ? err.message : t('onboarding.saveFailed')
  } finally {
    applying.value = false
  }
}

async function testConnection() {
  if (!selectedProvider.value || !apiKey.value) return
  testing.value = true
  testResult.value = ''
  saveError.value = ''
  try {
    // Save key first so test can use it
    await modelApi.updateProviderConfig(selectedProvider.value, { apiKey: apiKey.value })
    await modelApi.testConnection(selectedProvider.value)
    testResult.value = 'success'
  } catch {
    testResult.value = 'failed'
  } finally {
    testing.value = false
  }
}

async function saveCloudConfig() {
  if (!selectedProvider.value || !apiKey.value) return
  saving.value = true
  saveError.value = ''
  try {
    const res: any = await modelApi.updateProviderConfig(selectedProvider.value, {
      apiKey: apiKey.value,
    })
    // Cloud providers ship disabled — enable this one so it surfaces in the
    // model dropdown and the chat runtime can route to it.
    await modelApi.enableProvider(selectedProvider.value)
    // Promote one of its models to the active default so the verify step,
    // and the rest of the app, has a usable model immediately.
    const firstModel = (res?.data?.models || [])[0]
    if (firstModel?.id) {
      await modelApi.setActive({ providerId: selectedProvider.value, model: firstModel.id })
    }
    emit('done')
  } catch (err) {
    saveError.value = err instanceof Error ? err.message : t('onboarding.saveFailed')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.step-configure {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--mc-text-primary);
  margin: 0;
}

.loading-text,
.empty-text {
  font-size: 13px;
  color: var(--mc-text-tertiary);
  text-align: center;
  padding: 24px 0;
}

.model-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 200px;
  overflow-y: auto;
}

.model-radio {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
  background: var(--mc-bg);
}

.model-radio:hover {
  border-color: var(--mc-primary);
}

.model-radio.selected {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.radio-input {
  accent-color: var(--mc-primary);
}

.model-name {
  font-size: 14px;
  color: var(--mc-text-primary);
}

.provider-cards {
  display: flex;
  gap: 10px;
}

.provider-card {
  flex: 1;
  padding: 16px 12px;
  border: 2px solid var(--mc-border);
  border-radius: 10px;
  background: var(--mc-bg);
  cursor: pointer;
  text-align: center;
  transition: all 0.2s ease;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.provider-card:hover {
  border-color: var(--mc-primary);
}

.provider-card.selected {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.provider-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--mc-text-primary);
}

.provider-hint {
  font-size: 12px;
  color: var(--mc-text-tertiary);
}

.api-key-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.input-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-secondary);
}

.text-input {
  padding: 10px 14px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  font-size: 14px;
  outline: none;
  transition: border-color 0.15s ease;
}

.text-input:focus {
  border-color: var(--mc-primary);
}

.action-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.test-success {
  font-size: 13px;
  color: var(--mc-success);
  font-weight: 500;
}

.test-failed {
  font-size: 13px;
  color: var(--mc-danger);
  font-weight: 500;
}

.save-hint {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin: 0;
}

.btn-primary {
  padding: 10px 20px;
  background: var(--mc-primary);
  color: var(--mc-text-inverse);
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s ease;
  align-self: flex-start;
}

.btn-primary:hover:not(:disabled) {
  background: var(--mc-primary-hover);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-secondary {
  padding: 8px 16px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-secondary:hover:not(:disabled) {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
}

.btn-secondary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
