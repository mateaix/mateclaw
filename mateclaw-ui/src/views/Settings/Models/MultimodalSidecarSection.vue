<template>
  <div class="provider-group sidecar-section">
    <h3 class="group-title">
      <svg class="group-title__icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M14 3v4a1 1 0 0 0 1 1h4"/>
        <path d="M5 8V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-3"/>
        <circle cx="6" cy="13" r="3"/>
        <path d="M9 13h12"/>
      </svg>
      {{ t('settings.models.sidecar.title') }}
      <span class="group-hint">{{ t('settings.models.sidecar.hint') }}</span>
    </h3>

    <div v-if="loading" class="loading-state">{{ t('common.loading') }}</div>

    <div v-else class="sidecar-cards">
      <!-- Vision sidecar (active) -->
      <div class="sidecar-card" :class="{ 'is-configured': !!visionModelId }">
        <div class="sidecar-card__head">
          <div class="sidecar-card__icon" aria-hidden="true">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="5" width="18" height="14" rx="2"/>
              <circle cx="9" cy="11" r="2"/>
              <path d="m21 17-5.5-5.5L9 18"/>
            </svg>
          </div>
          <div class="sidecar-card__title-block">
            <div class="sidecar-card__title">{{ t('settings.models.sidecar.vision.label') }}</div>
            <div class="sidecar-card__desc">{{ t('settings.models.sidecar.vision.desc') }}</div>
          </div>
          <span
            class="sidecar-card__status"
            :class="visionModelId ? 'status--ok' : 'status--idle'"
          >{{ visionModelId ? t('common.enabled') : t('settings.models.sidecar.idle') }}</span>
        </div>
        <div class="sidecar-card__body">
          <ModelPicker
            v-model="visionModelId"
            :models="visionModels"
            :placeholder="t('settings.models.sidecar.notConfigured')"
            :empty-text="t('settings.models.sidecar.vision.empty')"
            :disabled="visionModels.length === 0"
          />
        </div>
        <div class="sidecar-card__actions">
          <span v-if="savedTip === 'vision'" class="sidecar-saved">✓ {{ t('common.saved') }}</span>
          <button
            class="card-btn save-btn"
            :disabled="saving === 'vision' || !visionDirty"
            @click="onSaveVision"
          >{{ saving === 'vision' ? t('common.saving') : t('common.save') }}</button>
        </div>
      </div>

      <!-- Video sidecar (reserved; renders dimmer to set expectations) -->
      <div class="sidecar-card sidecar-card--reserved">
        <div class="sidecar-card__head">
          <div class="sidecar-card__icon" aria-hidden="true">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="5" width="14" height="14" rx="2"/>
              <path d="m17 9 5-3v12l-5-3z"/>
            </svg>
          </div>
          <div class="sidecar-card__title-block">
            <div class="sidecar-card__title">
              {{ t('settings.models.sidecar.video.label') }}
              <span class="reserved-badge">{{ t('settings.models.sidecar.reserved') }}</span>
            </div>
            <div class="sidecar-card__desc">{{ t('settings.models.sidecar.video.desc') }}</div>
          </div>
        </div>
        <div class="sidecar-card__body">
          <ModelPicker
            v-model="videoModelId"
            :models="videoModels"
            :placeholder="t('settings.models.sidecar.notConfigured')"
            :empty-text="t('settings.models.sidecar.video.empty')"
            :disabled="videoModels.length === 0"
          />
        </div>
        <div class="sidecar-card__actions">
          <span v-if="savedTip === 'video'" class="sidecar-saved">✓ {{ t('common.saved') }}</span>
          <button
            class="card-btn save-btn"
            :disabled="saving === 'video' || !videoDirty"
            @click="onSaveVideo"
          >{{ saving === 'video' ? t('common.saving') : t('common.save') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { modelApi, settingsApi } from '@/api'
import ModelPicker from '@/components/common/ModelPicker.vue'

interface ModelOption {
  id: string | number
  name: string
  provider: string
  modelName: string
}

const { t } = useI18n()
const loading = ref(false)
// Per-card save state. 'vision' / 'video' / null. Lets each card show its own
// "Saving..." label without the buttons fighting each other.
const saving = ref<'vision' | 'video' | null>(null)
const savedTip = ref<'vision' | 'video' | null>(null)

const visionModels = ref<ModelOption[]>([])
const videoModels = ref<ModelOption[]>([])
const visionModelId = ref<string | null>(null)
const videoModelId = ref<string | null>(null)
const initialVision = ref<string | null>(null)
const initialVideo = ref<string | null>(null)

const visionDirty = computed(() => visionModelId.value !== initialVision.value)
const videoDirty = computed(() => videoModelId.value !== initialVideo.value)

async function loadAll() {
  loading.value = true
  try {
    const [visionRes, videoRes, settingsRes] = await Promise.all([
      modelApi.listByType('chat', 'vision'),
      modelApi.listByType('chat', 'video'),
      settingsApi.get(),
    ])
    visionModels.value = (visionRes.data as any[]) || []
    videoModels.value = (videoRes.data as any[]) || []
    const dto = (settingsRes.data as any) || {}
    visionModelId.value = dto.defaultVisionModelId ? String(dto.defaultVisionModelId) : null
    videoModelId.value = dto.defaultVideoModelId ? String(dto.defaultVideoModelId) : null
    initialVision.value = visionModelId.value
    initialVideo.value = videoModelId.value
  } catch (e: any) {
    console.error('[MultimodalSidecar] Load failed:', e?.message)
  } finally {
    loading.value = false
  }
}

async function persistSettings(payload: { defaultVisionModelId: number | string | null; defaultVideoModelId: number | string | null }) {
  // Use the dedicated sidecar endpoint so the bulk /settings PUT can keep
  // guarding vision/video keys with non-null checks (preventing unrelated
  // settings pages from clobbering this configuration via partial payloads).
  // This endpoint always writes both keys, so passing null here means
  // "explicit clear" which is the original UX of this card.
  await settingsApi.updateSidecar(payload)
}

async function onSaveVision() {
  saving.value = 'vision'
  savedTip.value = null
  try {
    // Send vision's pending value plus the *initial* (last-saved) video value
    // so saving vision never accidentally clears a video selection the user
    // may have edited but not yet committed in that card.
    // Send IDs as the raw string from the model picker. Number() would
    // truncate 19-digit Snowflake IDs past Number.MAX_SAFE_INTEGER; Jackson
    // on the backend coerces the string back to Long with full precision.
    await persistSettings({
      defaultVisionModelId: visionModelId.value ? String(visionModelId.value) : null,
      defaultVideoModelId: initialVideo.value ? String(initialVideo.value) : null,
    })
    initialVision.value = visionModelId.value
    savedTip.value = 'vision'
    setTimeout(() => { if (savedTip.value === 'vision') savedTip.value = null }, 2200)
  } catch (e: any) {
    console.error('[MultimodalSidecar] Save vision failed:', e?.message)
  } finally {
    saving.value = null
  }
}

async function onSaveVideo() {
  saving.value = 'video'
  savedTip.value = null
  try {
    await persistSettings({
      defaultVisionModelId: initialVision.value ? String(initialVision.value) : null,
      defaultVideoModelId: videoModelId.value ? String(videoModelId.value) : null,
    })
    initialVideo.value = videoModelId.value
    savedTip.value = 'video'
    setTimeout(() => { if (savedTip.value === 'video') savedTip.value = null }, 2200)
  } catch (e: any) {
    console.error('[MultimodalSidecar] Save video failed:', e?.message)
  } finally {
    saving.value = null
  }
}

// Wipe stale per-card "已保存" tips the moment the user touches that card again.
watch(visionModelId, () => { if (savedTip.value === 'vision') savedTip.value = null })
watch(videoModelId, () => { if (savedTip.value === 'video') savedTip.value = null })

onMounted(loadAll)
defineExpose({ refresh: loadAll })
</script>

<style scoped>
.sidecar-section {
  margin-top: 24px;
}
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
.loading-state {
  padding: 32px;
  text-align: center;
  color: var(--mc-text-tertiary);
  background: var(--mc-bg-sunken);
  border-radius: 8px;
}

.sidecar-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 12px;
}

.sidecar-card {
  padding: 16px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  transition: border-color 120ms ease;
}
.sidecar-card.is-configured {
  border-color: color-mix(in srgb, var(--mc-primary) 35%, var(--mc-border));
}
.sidecar-card--reserved {
  opacity: 0.7;
}

.sidecar-card__head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.sidecar-card__icon {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-secondary);
}
.sidecar-card.is-configured .sidecar-card__icon {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
}
.sidecar-card__title-block {
  flex: 1;
  min-width: 0;
}
.sidecar-card__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--mc-text-primary);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.sidecar-card__desc {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--mc-text-tertiary);
}
.sidecar-card__status {
  flex-shrink: 0;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  font-weight: 600;
  letter-spacing: 0.02em;
}
.status--ok {
  background: color-mix(in srgb, var(--mc-primary) 12%, transparent);
  color: var(--mc-primary);
}
.status--idle {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
}
.reserved-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.sidecar-card__body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* Picker styling is now owned by components/common/ModelPicker.vue. */

.sidecar-card__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}
.sidecar-saved {
  font-size: 12px;
  color: var(--mc-success, #22c55e);
  animation: fade-in 160ms ease;
}
@keyframes fade-in {
  from { opacity: 0; transform: translateY(-2px); }
  to { opacity: 1; transform: translateY(0); }
}
.card-btn {
  padding: 6px 16px;
  font-size: 12px;
  border-radius: 4px;
  border: 1px solid var(--mc-primary);
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  cursor: pointer;
  font-weight: 600;
  transition: opacity 120ms ease, background 120ms ease, color 120ms ease;
}
.card-btn:hover:not(:disabled) {
  background: var(--mc-primary);
  color: #fff;
}
.card-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
