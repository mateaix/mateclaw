<template>
  <div class="hot-cache-panel">
    <header class="panel-header">
      <div class="header-text">
        <h3 class="panel-title">{{ t('wiki.hotCache.title') }}</h3>
        <p class="panel-desc">{{ t('wiki.hotCache.desc') }}</p>
      </div>
      <div class="header-actions">
        <button
          class="btn-secondary"
          :disabled="loading || !cache"
          @click="onReset"
        >
          {{ t('wiki.hotCache.reset') }}
        </button>
        <button
          class="btn-primary"
          :disabled="regenerating"
          @click="onRegenerate"
        >
          <span v-if="regenerating">{{ t('wiki.hotCache.regenerating') }}</span>
          <span v-else>{{ t('wiki.hotCache.regenerate') }}</span>
        </button>
      </div>
    </header>

    <div v-if="loading" class="state-row">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ t('common.loading') }}</span>
    </div>

    <div v-else-if="error" class="state-row state-row--error">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ error }}</span>
      <button class="btn-secondary" @click="load">{{ t('common.retry', 'Retry') }}</button>
    </div>

    <div v-else-if="!cache" class="state-row state-row--empty">
      <span>{{ t('wiki.hotCache.empty') }}</span>
    </div>

    <div v-else class="cache-body">
      <div class="meta-grid">
        <div class="meta-item">
          <span class="meta-label">{{ t('wiki.hotCache.lastUpdated') }}</span>
          <span class="meta-value">{{ formatTimestamp(cache.lastUpdated) }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">{{ t('wiki.hotCache.updateReason') }}</span>
          <span class="meta-value">{{ cache.updateReason || '—' }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">{{ t('wiki.hotCache.rebuildCount') }}</span>
          <span class="meta-value">{{ cache.rebuildCount ?? 0 }}</span>
        </div>
        <div class="meta-item" v-if="cache.lastRebuildDurationMs">
          <span class="meta-label">{{ t('wiki.hotCache.lastDuration') }}</span>
          <span class="meta-value">{{ formatDuration(cache.lastRebuildDurationMs) }}</span>
        </div>
      </div>

      <div v-if="cache.lastRebuildError" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <div>
          <div class="error-title">{{ t('wiki.hotCache.lastError') }}</div>
          <div class="error-detail">{{ cache.lastRebuildError }}</div>
        </div>
      </div>

      <pre class="cache-content">{{ cache.content || t('wiki.hotCache.contentEmpty') }}</pre>
    </div>

    <p class="footer-note">{{ t('wiki.hotCache.footer') }}</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { ElIcon } from 'element-plus'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import { hotCacheApi, type WikiHotCache } from '@/api/index'
import { useWikiStore } from '@/stores/useWikiStore'

const { t } = useI18n()
const store = useWikiStore()

const cache = ref<WikiHotCache | null>(null)
const loading = ref(false)
const regenerating = ref(false)
const error = ref('')

async function load() {
  if (!store.currentKB) return
  loading.value = true
  error.value = ''
  try {
    const resp: any = await hotCacheApi.get(store.currentKB.id)
    cache.value = resp?.data ?? null
  } catch (e: any) {
    error.value = e?.message ?? String(e)
  } finally {
    loading.value = false
  }
}

async function onRegenerate() {
  if (!store.currentKB) return
  regenerating.value = true
  try {
    await hotCacheApi.regenerate(store.currentKB.id)
    mcToast.success(t('wiki.hotCache.regenerateQueued'))
    // Background rebuild — poll once after a short delay so the operator sees
    // the row update without manual refresh. The LLM call typically returns
    // within 5-15s; if the user hits "regenerate" again, a fresh poll fires.
    setTimeout(load, 4000)
  } catch (e: any) {
    mcToast.error(e?.message ?? t('wiki.hotCache.regenerateFailed'))
  } finally {
    regenerating.value = false
  }
}

async function onReset() {
  if (!store.currentKB) return
  if (!confirm(t('wiki.hotCache.resetConfirm'))) return
  try {
    await hotCacheApi.reset(store.currentKB.id)
    mcToast.success(t('wiki.hotCache.resetDone'))
    await load()
  } catch (e: any) {
    mcToast.error(e?.message ?? t('wiki.hotCache.resetFailed'))
  }
}

function formatTimestamp(iso: string | null): string {
  if (!iso) return t('common.never', 'Never')
  return new Date(iso).toLocaleString()
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

watch(() => store.currentKB?.id, () => {
  cache.value = null
  if (store.currentKB) load()
})

onMounted(load)
</script>

<style scoped>
.hot-cache-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}
.header-text { flex: 1; min-width: 240px; }
.header-actions { display: flex; gap: 8px; }

.panel-title {
  font-size: 18px;
  font-weight: 700;
  margin: 0 0 4px;
  color: var(--mc-text-primary);
}
.panel-desc {
  font-size: 13px;
  color: var(--mc-text-secondary);
  margin: 0;
  line-height: 1.5;
}

.btn-primary, .btn-secondary {
  border-radius: 8px;
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  border: 1px solid transparent;
}
.btn-primary {
  background: var(--mc-primary);
  color: white;
}
.btn-primary:hover:not(:disabled) { background: var(--mc-primary-hover); }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-secondary {
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border-color: var(--mc-border);
}
.btn-secondary:hover:not(:disabled) { background: var(--mc-bg-sunken); }
.btn-secondary:disabled { opacity: 0.5; cursor: not-allowed; }

.state-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 24px;
  background: var(--mc-bg-muted);
  border-radius: 10px;
  color: var(--mc-text-secondary);
  font-size: 14px;
}
.state-row--error {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}
.state-row--empty {
  justify-content: center;
}

.cache-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  padding: 14px 16px;
}
.meta-item { display: flex; flex-direction: column; gap: 2px; }
.meta-label {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.meta-value {
  font-size: 13px;
  color: var(--mc-text-primary);
  font-weight: 500;
}

.error-banner {
  display: flex;
  gap: 10px;
  padding: 12px 14px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  border-radius: 8px;
  border: 1px solid var(--el-color-danger-light-7);
  font-size: 13px;
}
.error-title { font-weight: 600; margin-bottom: 2px; }
.error-detail { font-family: var(--mc-font-mono, ui-monospace, Menlo, monospace); font-size: 12px; word-break: break-word; }

.cache-content {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  padding: 16px;
  font-family: var(--mc-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 13px;
  line-height: 1.6;
  color: var(--mc-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 600px;
  overflow-y: auto;
  margin: 0;
}

.footer-note {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  line-height: 1.5;
  margin: 0;
}
</style>
