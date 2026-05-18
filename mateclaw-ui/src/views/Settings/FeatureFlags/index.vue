<template>
  <div class="settings-section feature-flags-section">
    <div class="section-header">
      <h2 class="section-title">{{ t('settings.featureFlags.title') }}</h2>
      <p class="section-desc">{{ t('settings.featureFlags.description') }}</p>
    </div>

    <div v-if="loading" class="settings-card state-card">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ t('common.loading') }}</span>
    </div>

    <div v-else-if="error" class="settings-card state-card state-card--error">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ error }}</span>
      <button class="btn-secondary" @click="load">{{ t('common.retry', 'Retry') }}</button>
    </div>

    <div v-else-if="flags.length > 0" class="settings-card">
      <div
        v-for="flag in flags"
        :key="flag.flagKey"
        class="setting-item"
        :class="{ 'setting-item--unwired': !isWired(flag) }"
      >
        <div class="setting-info">
          <div class="flag-header">
            <span class="setting-label flag-key">{{ flag.flagKey }}</span>
            <span v-if="!isWired(flag)" class="flag-badge">
              {{ t('settings.featureFlags.notWired') }}
            </span>
          </div>
          <div v-if="describe(flag)" class="setting-hint">{{ describe(flag) }}</div>
          <div v-if="hasScope(flag)" class="flag-scope">
            <span v-if="flag.whitelistKbIds">
              {{ t('settings.featureFlags.scope.kb') }}: {{ flag.whitelistKbIds }}
            </span>
            <span v-if="flag.whitelistUserIds">
              {{ t('settings.featureFlags.scope.user') }}: {{ flag.whitelistUserIds }}
            </span>
            <span v-if="(flag.rolloutPercent ?? 0) > 0 && (flag.rolloutPercent ?? 0) < 100">
              {{ t('settings.featureFlags.scope.rollout', { pct: flag.rolloutPercent }) }}
            </span>
          </div>
        </div>
        <div class="setting-control">
          <label
            class="toggle-switch"
            :title="!isWired(flag) ? t('settings.featureFlags.notWiredTooltip') : ''"
          >
            <input
              type="checkbox"
              :checked="flag.enabled"
              :disabled="pending[flag.flagKey] === true || !isWired(flag)"
              @change="onToggle(flag, ($event.target as HTMLInputElement).checked)"
            />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>
    </div>

    <div v-else class="settings-card state-card">
      <span>{{ t('settings.featureFlags.empty') }}</span>
    </div>

    <p class="section-footer-note">{{ t('settings.featureFlags.footer') }}</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { ElIcon } from 'element-plus'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import { featureFlagApi, type FeatureFlag } from '@/api/index'

const { t, getLocaleMessage, locale } = useI18n()

// Flag keys that have at least one featureFlagService.isEnabled(...) consumer
// in the backend. Seeded flags without a consumer toggle to no effect, which
// confuses operators — we grey them out + tooltip "not yet implemented".
//
// Update this set whenever a new flag gets wired. Verify with:
//   grep -rn 'featureFlagService\.isEnabled' mateclaw-server/src/main/java
const WIRED_FLAGS = new Set<string>([
  'wiki.ocr.enabled',
  'wiki.hot_cache.enabled',
])

function isWired(flag: FeatureFlag): boolean {
  return WIRED_FLAGS.has(flag.flagKey)
}

// Walk the message tree manually — vue-i18n's t()/tm() interpret dots as a
// nested path, and our flagKey ("wiki.ocr.enabled") contains dots that should
// be treated as part of a single property name. Backend description is the
// fallback when a flag has no localized copy.
function describe(flag: FeatureFlag): string {
  const messages = getLocaleMessage(locale.value) as any
  const localized = messages?.settings?.featureFlags?.descriptions?.[flag.flagKey]
  if (typeof localized === 'string' && localized.length > 0) {
    return localized
  }
  return flag.description ?? ''
}

const loading = ref(true)
const error = ref<string>('')
const flags = ref<FeatureFlag[]>([])
const pending = reactive<Record<string, boolean>>({})

function hasScope(flag: FeatureFlag): boolean {
  return !!flag.whitelistKbIds
      || !!flag.whitelistUserIds
      || ((flag.rolloutPercent ?? 0) > 0 && (flag.rolloutPercent ?? 0) < 100)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const resp: any = await featureFlagApi.list()
    flags.value = (resp?.data ?? []).slice().sort((a: FeatureFlag, b: FeatureFlag) =>
        a.flagKey.localeCompare(b.flagKey))
  } catch (e: any) {
    error.value = e?.message ?? String(e)
  } finally {
    loading.value = false
  }
}

async function onToggle(flag: FeatureFlag, next: boolean) {
  pending[flag.flagKey] = true
  try {
    await featureFlagApi.update(flag.flagKey, { enabled: next })
    flag.enabled = next
    mcToast.success(t(next ? 'settings.featureFlags.enabled' : 'settings.featureFlags.disabled',
        { key: flag.flagKey }))
  } catch (e: any) {
    mcToast.error(e?.message ?? t('settings.featureFlags.toggleFailed'))
    await load()
  } finally {
    pending[flag.flagKey] = false
  }
}

onMounted(load)
</script>

<style scoped>
.settings-section { width: 100%; }
.section-header { display: flex; flex-direction: column; gap: 6px; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }

.settings-card { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; padding: 18px; box-shadow: 0 8px 24px rgba(124, 63, 30, 0.04); width: 100%; }
.setting-item { display: flex; justify-content: space-between; gap: 20px; padding: 16px 0; border-bottom: 1px solid var(--mc-border-light); }
.setting-item:last-child { border-bottom: none; }
.setting-info { flex: 1; min-width: 0; }
.setting-label { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); margin-bottom: 4px; }
.setting-hint { font-size: 13px; color: var(--mc-text-secondary); line-height: 1.5; }
.setting-control { width: 80px; display: flex; align-items: center; justify-content: flex-end; }

.toggle-switch { position: relative; display: inline-flex; width: 44px; height: 24px; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; cursor: pointer; background: var(--mc-border); border-radius: 999px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 18px; height: 18px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(20px); }
.toggle-switch input:disabled + .toggle-slider { opacity: 0.5; cursor: not-allowed; }

.btn-secondary { border: 1px solid var(--mc-border); border-radius: 10px; padding: 6px 12px; font-size: 13px; cursor: pointer; background: var(--mc-bg-elevated); color: var(--mc-text-primary); transition: all 0.15s; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

.flag-key {
  font-family: var(--mc-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 13px;
}

.flag-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}

.flag-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.setting-item--unwired .setting-label,
.setting-item--unwired .setting-hint {
  opacity: 0.55;
}
.setting-item--unwired .toggle-switch {
  cursor: not-allowed;
}

.flag-scope {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  margin-top: 6px;
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.state-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px;
  color: var(--mc-text-secondary);
}

.state-card--error {
  color: var(--el-color-danger);
}

.section-footer-note {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  line-height: 1.5;
  margin-top: 16px;
}

@media (max-width: 900px) {
  .setting-item { flex-direction: column; }
  .setting-control { width: 100%; justify-content: flex-start; }
}
</style>
