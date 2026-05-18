<template>
  <div class="wiki-config">
    <div class="config-header">
      <h3 class="config-title">{{ t('wiki.configTitle') }}</h3>
      <p class="config-desc">{{ t('wiki.configDesc') }}</p>
    </div>

    <!-- ① Embedding model -->
    <div class="config-card">
      <div class="config-card__head">
        <div>
          <div class="config-card__title">{{ t('wiki.configPanel.embeddingModel') }}</div>
          <div class="config-card__hint">{{ t('wiki.configPanel.embeddingModelHint') }}</div>
        </div>
        <button class="btn-save" @click="saveEmbeddingBinding" :disabled="savingEmbedding">
          {{ savingEmbedding ? t('wiki.saving') : t('common.save') }}
        </button>
      </div>
      <WikiModelPicker v-model="embeddingModelId" :options="embeddingPickerOptions" :disabled="savingEmbedding" />
    </div>

    <!-- ①b Ingest mode (RFC-051 PR-1b) -->
    <div class="config-card">
      <div class="config-card__head">
        <div>
          <div class="config-card__title">{{ t('wiki.configPanel.ingestMode') }}</div>
          <div class="config-card__hint">
            {{ ingestMode === 'lazy'
              ? t('wiki.configPanel.ingestModeLazyHint')
              : t('wiki.configPanel.ingestModeEagerHint') }}
          </div>
        </div>
        <button class="btn-save" @click="saveIngestMode" :disabled="savingIngestMode">
          {{ savingIngestMode ? t('wiki.saving') : t('common.save') }}
        </button>
      </div>
      <div class="ingest-mode-row">
        <label class="ingest-mode-option" :class="{ 'ingest-mode-option--active': ingestMode === 'eager' }">
          <input type="radio" value="eager" v-model="ingestMode" :disabled="savingIngestMode" />
          <span class="ingest-mode-option__label">{{ t('wiki.configPanel.ingestModeEager') }}</span>
        </label>
        <label class="ingest-mode-option" :class="{ 'ingest-mode-option--active': ingestMode === 'lazy' }">
          <input type="radio" value="lazy" v-model="ingestMode" :disabled="savingIngestMode" />
          <span class="ingest-mode-option__label">{{ t('wiki.configPanel.ingestModeLazy') }}</span>
        </label>
      </div>
    </div>

    <!-- ② Model strategy -->
    <div class="config-card config-card--clickable" @click="modelsOpen = true">
      <div class="config-card__row">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/>
        </svg>
        <div class="config-card__row-text">
          <div class="config-card__title">{{ t('wiki.configPanel.modelStrategy') }}</div>
          <div class="config-card__hint">
            <template v-if="wikiGlobalModelId && activeStepCount > 0">Wiki 全局已设置，{{ activeStepCount }} 个步骤独立覆盖</template>
            <template v-else-if="wikiGlobalModelId">Wiki 全局模型已设置，步骤沿用</template>
            <template v-else-if="activeStepCount > 0">{{ activeStepCount }} 个步骤已绑定自定义模型</template>
            <template v-else>全部步骤使用系统全局默认模型</template>
          </div>
        </div>
        <div class="card-badge" :class="{ 'card-badge--active': activeStepCount > 0 || !!wikiGlobalModelId }">
          {{ activeStepCount }} / {{ stepKeys.length }}
        </div>
        <svg class="card-chevron" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="9 18 15 12 9 6"/>
        </svg>
      </div>
    </div>

    <!-- ③ Processing rules -->
    <div class="config-card config-card--clickable" @click="rulesOpen = true">
      <div class="config-card__row" style="align-items: flex-start">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-top:2px">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>
        </svg>
        <div class="config-card__row-text" style="flex:1;min-width:0">
          <div class="config-card__title">处理规则</div>
          <div class="config-card__hint">AI 消化原始材料时遵循的质量、格式和语言规则</div>
          <!-- Preview snippet -->
          <div v-if="configContent.trim()" class="rules-snippet">
            <span v-for="(line, i) in snippetLines" :key="i" class="rules-snippet__line" :class="{ 'h': line.startsWith('#') }">{{ line }}</span>
            <span v-if="totalLines > 4" class="rules-snippet__more">+{{ totalLines - 4 }} 行</span>
          </div>
          <div v-else class="rules-snippet rules-snippet--empty">点击配置 →</div>
        </div>
        <svg class="card-chevron" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0;margin-top:2px">
          <polyline points="9 18 15 12 9 6"/>
        </svg>
      </div>
    </div>

    <!-- ④ Search preview card -->
    <div v-if="store.currentKB" class="config-card config-card--clickable" @click="searchOpen = true">
      <div class="config-card__row">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        <div class="config-card__row-text">
          <div class="config-card__title">{{ t('wiki.configPanel.searchPreview') }}</div>
          <div class="config-card__hint">{{ t('wiki.configPanel.searchPreviewPlaceholder') }}</div>
        </div>
        <svg class="card-chevron" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="9 18 15 12 9 6"/>
        </svg>
      </div>
    </div>

    <!-- Modals -->
    <WikiConfigRules
      :open="rulesOpen"
      :model-value="configContent"
      :kb-name="store.currentKB?.name"
      :saving="savingRules"
      @close="rulesOpen = false"
      @save="saveRules"
    />

    <WikiConfigModels
      :open="modelsOpen"
      :kb-name="store.currentKB?.name"
      :saving="savingStepModels"
      :step-keys="stepKeys"
      :step-models="stepModels"
      :fallback-model-ids="fallbackModelIds"
      :providers="chatProviders"
      :config-id-to-value="configIdToValue"
      :value-to-config-id="valueToConfigId"
      :config-id-to-label="configIdToLabel"
      :wiki-global-model-id="wikiGlobalModelId"
      @close="modelsOpen = false"
      @save="saveStepModelsAndClose"
      @reset="loadStepModels"
      @add-fallback="onAddFallback"
      @remove-fallback="(idx) => fallbackModelIds.splice(idx, 1)"
      @update:wiki-global-model-id="wikiGlobalModelId = $event"
    />

    <WikiSearchPreview
      v-if="store.currentKB"
      :open="searchOpen"
      :kb-id="store.currentKB.id"
      :kb-name="store.currentKB?.name"
      @close="searchOpen = false"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useWikiStore } from '@/stores/useWikiStore'
import { wikiApi, modelApi } from '@/api/index'
import type { ProviderInfo } from '@/types'
import WikiSearchPreview from './WikiSearchPreview.vue'
import WikiModelPicker, { type ModelOption } from './WikiModelPicker.vue'
import WikiConfigRules from './WikiConfigRules.vue'
import WikiConfigModels from './WikiConfigModels.vue'

const { t } = useI18n()
const store = useWikiStore()

// ── Rules state ──
const configContent = ref('')
const rulesOpen = ref(false)
const savingRules = ref(false)

const snippetLines = computed(() => configContent.value.split('\n').filter(l => l.trim()).slice(0, 4))
const totalLines = computed(() => configContent.value.split('\n').filter(l => l.trim()).length)

async function saveRules(content: string) {
  if (!store.currentKB) return
  savingRules.value = true
  try {
    await wikiApi.updateConfig(store.currentKB.id, content)
    configContent.value = content
    rulesOpen.value = false
  } catch (e) {
    console.error('[WikiConfig] Failed to save rules', e)
  } finally {
    savingRules.value = false
  }
}

// ── Embedding model ──
const embeddingModelId = ref<string>('')
const savingEmbedding = ref(false)

async function saveEmbeddingBinding() {
  if (!store.currentKB) return
  savingEmbedding.value = true
  try {
    await wikiApi.updateKB(store.currentKB.id, {
      embeddingModelId: embeddingModelId.value === '' ? null : embeddingModelId.value,
    })
    const kb: any = store.currentKB
    // Mirror the persisted value to the in-memory KB without going through
    // Number() — Snowflake model IDs would otherwise lose their last digits
    // (Number.MAX_SAFE_INTEGER = 2^53-1, IDs are 19 digits).
    kb.embeddingModelId = embeddingModelId.value === '' ? null : embeddingModelId.value
  } catch (e) {
    console.error('[WikiConfig] Failed to save embedding binding', e)
  } finally {
    savingEmbedding.value = false
  }
}

// ── Ingest mode (RFC-051 PR-1b) ──
// eager = legacy heavy pipeline (extract → chunk → route/create/merge LLM → pages).
// lazy  = extract → chunk → embed → completed (0 pages is success).
const ingestMode = ref<'eager' | 'lazy'>('eager')
const savingIngestMode = ref(false)

async function saveIngestMode() {
  if (!store.currentKB) return
  savingIngestMode.value = true
  try {
    let existingConfig: any = {}
    try {
      if (store.currentKB.configContent) existingConfig = JSON.parse(store.currentKB.configContent)
    } catch { /* config may be plain text rules */ }
    existingConfig.ingestMode = ingestMode.value
    await wikiApi.updateConfig(store.currentKB.id, JSON.stringify(existingConfig, null, 2))
  } catch (e) {
    console.error('[WikiConfig] Failed to save ingest mode', e)
  } finally {
    savingIngestMode.value = false
  }
}

// ── Model strategy ──
const stepKeys = ['route', 'create_page', 'merge_page', 'enrich', 'summary']
const stepModels = reactive<Record<string, string>>({})
const fallbackModelIds = ref<string[]>([])
const wikiGlobalModelId = ref<string>('')
const modelsOpen = ref(false)
const savingStepModels = ref(false)
const searchOpen = ref(false)

const activeStepCount = computed(() => stepKeys.filter(k => !!stepModels[k]).length)

function loadStepModels() {
  stepKeys.forEach(k => (stepModels[k] = ''))
  fallbackModelIds.value = []
  wikiGlobalModelId.value = ''
  ingestMode.value = 'eager'
  if (!store.currentKB) return
  try {
    const cfg = store.currentKB.configContent ? JSON.parse(store.currentKB.configContent) : null
    if (cfg?.stepModels) {
      for (const key of stepKeys) {
        const fullKey = `heavy_ingest.${key}`
        if (cfg.stepModels[fullKey]) stepModels[key] = String(cfg.stepModels[fullKey])
      }
    }
    if (cfg?.fallbackModelIds) fallbackModelIds.value = cfg.fallbackModelIds.map(String)
    if (cfg?.wikiDefaultModelId) wikiGlobalModelId.value = String(cfg.wikiDefaultModelId)
    if (cfg?.ingestMode === 'lazy') ingestMode.value = 'lazy'
  } catch { /* not JSON */ }
}

async function saveStepModelsAndClose() {
  if (!store.currentKB) return
  savingStepModels.value = true
  try {
    // Keep model IDs as strings end-to-end. Snowflake IDs exceed
    // Number.MAX_SAFE_INTEGER, so Number() / .map(Number) would silently
    // corrupt the last few digits before serializing to configContent JSON.
    // Backend parses configContent leniently — string-typed IDs are fine.
    const stepMap: Record<string, string> = {}
    for (const key of stepKeys) {
      if (stepModels[key]) stepMap[`heavy_ingest.${key}`] = stepModels[key]
    }
    let existingConfig: any = {}
    try {
      if (store.currentKB.configContent) existingConfig = JSON.parse(store.currentKB.configContent)
    } catch { /* not JSON */ }
    existingConfig.stepModels = Object.keys(stepMap).length > 0 ? stepMap : undefined
    existingConfig.fallbackModelIds = fallbackModelIds.value.length > 0
      ? [...fallbackModelIds.value] : undefined
    existingConfig.wikiDefaultModelId = wikiGlobalModelId.value
      ? wikiGlobalModelId.value : undefined
    await wikiApi.updateConfig(store.currentKB.id, JSON.stringify(existingConfig, null, 2))
    modelsOpen.value = false
  } catch (e) {
    console.error('[WikiConfig] Failed to save step models', e)
  } finally {
    savingStepModels.value = false
  }
}

function onAddFallback(id: string) {
  if (id && !fallbackModelIds.value.includes(id)) fallbackModelIds.value.push(id)
}

// ── Raw model data ──
interface RawModel { id: number | string; name: string; modelName?: string; provider?: string; enabled?: boolean }
const chatRawModels = ref<RawModel[]>([])
const embeddingRawModels = ref<RawModel[]>([])
const providerNames = ref<Record<string, string>>({})
// Tracks whether a provider has a usable API key (available = true from ProviderInfoDTO)
const providerAvailable = ref<Record<string, boolean>>({})
// Full provider list for ModelSelector (only available providers)
const chatProviders = ref<ProviderInfo[]>([])

async function loadProviderNames() {
  try {
    const res: any = await modelApi.listProviders()
    const list: any[] = res.data || []
    chatProviders.value = list as ProviderInfo[]
    const nameMap: Record<string, string> = {}
    const availMap: Record<string, boolean> = {}
    for (const p of list) {
      nameMap[p.id] = p.name || p.id
      // Local providers (Ollama etc.) don't need an API key — treat as available
      availMap[p.id] = !!(p.available || p.isLocal)
    }
    providerNames.value = nameMap
    providerAvailable.value = availMap
  } catch { /* ignore */ }
}

// ── Format bridge: numeric config ID ↔ ModelSelector's "providerId::modelId" ──
// config ID → "providerId::modelName" (the value ModelSelector emits)
const configIdToValue = computed(() => {
  const map = new Map<string, string>()
  for (const m of chatRawModels.value) {
    const modelName = (m as any).modelName
    if (m.provider && modelName) map.set(String(m.id), `${m.provider}::${modelName}`)
  }
  return map
})
// "providerId::modelName" → config ID
const valueToConfigId = computed(() => {
  const map = new Map<string, string>()
  for (const m of chatRawModels.value) {
    const modelName = (m as any).modelName
    if (m.provider && modelName) map.set(`${m.provider}::${modelName}`, String(m.id))
  }
  return map
})
// Display label for a stored config ID
function configIdToLabel(id: string): string {
  if (!id) return ''
  return chatRawModels.value.find(m => String(m.id) === id)?.name || id
}

async function loadChatModels() {
  try {
    const res: any = await modelApi.listByType('chat')
    chatRawModels.value = ((res.data as RawModel[]) || []).filter(m => m.enabled !== false)
  } catch (e) { console.error('[WikiConfig] Failed to load chat models', e) }
}

async function loadEmbeddingModels() {
  try {
    const res: any = await modelApi.listByType('embedding')
    embeddingRawModels.value = ((res.data as RawModel[]) || []).filter(m => m.enabled !== false)
  } catch (e) { console.error('[WikiConfig] Failed to load embedding models', e) }
}

function buildPickerOptions(models: RawModel[]): ModelOption[] {
  return models.map(m => ({
    id: String(m.id),
    name: m.name,
    modelId: (m as any).modelName,
    providerId: m.provider,
    providerName: m.provider ? (providerNames.value[m.provider] || m.provider) : undefined,
    // If the provider ID is known and its availability is explicitly false, mark unavailable
    available: m.provider ? (providerAvailable.value[m.provider] !== false) : true,
  }))
}

const embeddingPickerOptions = computed<ModelOption[]>(() => buildPickerOptions(embeddingRawModels.value))

// ── Lifecycle ──
watch(() => store.currentKB, async () => {
  if (!store.currentKB) return
  try {
    const res: any = await wikiApi.getConfig(store.currentKB.id)
    configContent.value = res.data?.content || ''
  } catch { /* ignore */ }
  embeddingModelId.value = (store.currentKB as any)?.embeddingModelId
    ? String((store.currentKB as any).embeddingModelId) : ''
  loadStepModels()
}, { immediate: true })

loadProviderNames().then(() => {
  loadChatModels()
  loadEmbeddingModels()
})
</script>

<style scoped>
.wiki-config {
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
  padding-bottom: 24px;
}

.config-header { padding-bottom: 10px; border-bottom: 1px solid var(--mc-border-light); }
.config-title { font-size: 18px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 4px; letter-spacing: -0.02em; }
.config-desc { font-size: 13px; color: var(--mc-text-tertiary); margin: 0; line-height: 1.6; }

/* Cards */
.config-card {
  padding: 12px 14px;
  background: var(--mc-bg-sunken);
  border-radius: 12px;
  border: 1px solid var(--mc-border-light);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.config-card--clickable { cursor: pointer; transition: border-color 0.15s, background 0.15s; }
.config-card--clickable:hover { border-color: var(--mc-primary); background: var(--mc-bg-muted); }
.config-card__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.config-card__row { display: flex; align-items: center; gap: 10px; }
.config-card__row-text { flex: 1; min-width: 0; }
.config-card__title { font-size: 13px; font-weight: 600; color: var(--mc-text-primary); }
.config-card__hint { font-size: 11px; color: var(--mc-text-tertiary); margin-top: 2px; line-height: 1.4; }

.card-badge {
  font-size: 10px;
  padding: 1px 7px;
  border-radius: 99px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-tertiary);
  font-weight: 600;
  flex-shrink: 0;
}
.card-badge--active { border-color: var(--mc-primary); color: var(--mc-primary); background: var(--mc-primary-bg); }
.card-chevron { color: var(--mc-text-tertiary); flex-shrink: 0; }
.config-card--clickable:hover .card-chevron { color: var(--mc-primary); }

/* Rules snippet preview */
.rules-snippet {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
  margin-top: 6px;
}
.rules-snippet__line {
  font-size: 11px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  color: var(--mc-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 240px;
}
.rules-snippet__line.h { color: var(--mc-text-secondary); font-weight: 600; }
.rules-snippet__more { font-size: 11px; color: var(--mc-text-tertiary); font-style: italic; }
.rules-snippet--empty { font-size: 11px; color: var(--mc-primary); font-style: italic; }

/* Save button */
.btn-save {
  display: inline-flex;
  align-items: center;
  padding: 6px 14px;
  border: none;
  border-radius: 8px;
  background: var(--mc-primary);
  color: white;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
  flex-shrink: 0;
}
.btn-save:hover { opacity: 0.88; }
.btn-save:disabled { background: var(--mc-border); cursor: not-allowed; }

/* Ingest mode radio group */
.ingest-mode-row { display: flex; gap: 8px; flex-wrap: wrap; }
.ingest-mode-option {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg-elevated);
  font-size: 12px;
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}
.ingest-mode-option input { margin: 0; cursor: pointer; }
.ingest-mode-option--active {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
  background: var(--mc-primary-bg);
  font-weight: 600;
}
.ingest-mode-option__label { line-height: 1; }
</style>
