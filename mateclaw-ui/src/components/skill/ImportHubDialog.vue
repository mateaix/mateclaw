<template>
  <div v-if="visible" class="modal-overlay" @click.self="handleClose">
    <div class="import-modal">
      <div class="modal-header">
        <h2>{{ t('skills.import.title') }}</h2>
        <button class="modal-close" @click="handleClose" :disabled="installing">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <!-- Tab 切换 -->
        <div class="import-tabs">
          <button class="import-tab" :class="{ active: activeTab === 'url' }" @click="activeTab = 'url'">
            {{ t('skills.import.urlTab') }}
          </button>
          <button class="import-tab" :class="{ active: activeTab === 'search' }" @click="activeTab = 'search'">
            {{ t('skills.import.searchTab') }}
          </button>
          <button class="import-tab" :class="{ active: activeTab === 'zip' }" @click="activeTab = 'zip'">
            {{ t('skills.import.zipTab') }}
          </button>
        </div>

        <!-- URL 安装 -->
        <div v-if="activeTab === 'url'" class="tab-content">
          <div class="form-group">
            <input v-model="urlInput" class="form-input" :placeholder="t('skills.import.urlPlaceholder')"
              :disabled="installing" @keyup.enter="startUrlInstall" />
            <p class="form-hint-text">{{ t('skills.import.urlHint') }}</p>
          </div>
          <div class="url-examples">
            <span class="example-label">{{ t('skills.import.examples') }}:</span>
            <code class="example-url">https://github.com/user/skill-repo</code>
            <code class="example-url">https://clawhub.ai/skills/my-skill</code>
          </div>
          <div class="form-options">
            <label class="checkbox-label">
              <input type="checkbox" v-model="enableAfterInstall" />
              {{ t('skills.import.enableAfterInstall') }}
            </label>
            <label class="checkbox-label">
              <input type="checkbox" v-model="overwriteExisting" />
              {{ t('skills.import.overwrite') }}
            </label>
          </div>
          <button class="btn-primary install-btn" @click="startUrlInstall"
            :disabled="!urlInput.trim() || installing">
            <svg v-if="!installing" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
            </svg>
            {{ installing ? t('skills.import.installing') : t('skills.import.install') }}
          </button>
        </div>

        <!-- 搜索市场 -->
        <div v-if="activeTab === 'search'" class="tab-content">
          <div class="form-group">
            <input v-model="searchQuery" class="form-input" :placeholder="t('skills.import.searchPlaceholder')"
              @keyup.enter="doSearch" :disabled="searching" />
          </div>
          <button class="btn-secondary search-btn" @click="doSearch" :disabled="!searchQuery.trim() || searching">
            {{ searching ? t('skills.import.searching') : t('skills.import.search') }}
          </button>

          <div v-if="searchResults.length > 0" class="search-results">
            <div v-for="item in searchResults" :key="item.slug" class="search-item">
              <div class="search-item-info">
                <span class="search-item-icon">{{ item.icon || '📦' }}</span>
                <div>
                  <div class="search-item-name">{{ item.name }}</div>
                  <div class="search-item-desc">{{ item.description }}</div>
                  <div class="search-item-meta">
                    <span v-if="item.author">{{ item.author }}</span>
                    <span v-if="item.version">v{{ item.version }}</span>
                  </div>
                </div>
              </div>
              <button class="btn-primary btn-sm" @click="installFromHub(item)" :disabled="installing">
                {{ t('skills.import.install') }}
              </button>
            </div>
          </div>
          <div v-else-if="searchDone && searchResults.length === 0" class="search-empty">
            {{ t('skills.import.noResults') }}
          </div>
        </div>

        <!-- ZIP 上传 -->
        <div v-if="activeTab === 'zip'" class="tab-content">
          <div class="upload-zone"
               :class="{ 'drag-over': isDragging, 'has-file': zipFile }"
               @dragenter.prevent="onDragEnter"
               @dragover.prevent
               @dragleave.prevent="onDragLeave"
               @drop.prevent="onDrop"
               @click="triggerFileInput">
            <input ref="zipInputRef" type="file" accept=".zip" class="hidden-input" @change="handleFileSelect" />
            <svg v-if="!zipFile" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style="opacity: 0.4; margin-bottom: 8px;">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>
            </svg>
            <p v-if="!zipFile" class="upload-hint">{{ t('skills.import.dropHint') }}</p>
            <p v-if="!zipFile" class="upload-sub">{{ t('skills.import.zipRequirement') }}</p>
            <div v-if="zipFile" class="file-preview">
              <span class="file-icon">📦</span>
              <span class="file-name">{{ zipFile.name }}</span>
              <span class="file-size">{{ formatSize(zipFile.size) }}</span>
              <button class="file-remove" @click.stop="zipFile = null">&times;</button>
            </div>
          </div>
          <div class="form-options">
            <label class="checkbox-label">
              <input type="checkbox" v-model="enableAfterInstall" />
              {{ t('skills.import.enableAfterInstall') }}
            </label>
            <label class="checkbox-label">
              <input type="checkbox" v-model="overwriteExisting" />
              {{ t('skills.import.overwrite') }}
            </label>
          </div>
          <button class="btn-primary install-btn" @click="uploadZip" :disabled="!zipFile || installing">
            {{ installing ? t('skills.import.uploading') : t('skills.import.install') }}
          </button>
        </div>

        <!-- 安装进度 -->
        <div v-if="currentTask" class="install-progress">
          <div class="progress-header">
            <span class="progress-status" :class="'status-' + currentTask.status.toLowerCase()">
              {{ getStatusLabel(currentTask.status) }}
            </span>
            <button v-if="currentTask.status === 'INSTALLING'" class="btn-cancel" @click="cancelInstall">
              {{ t('skills.import.cancel') }}
            </button>
          </div>
          <div class="progress-url">{{ currentTask.bundleUrl }}</div>
          <div v-if="currentTask.error" class="progress-error">{{ currentTask.error }}</div>
          <div v-if="currentTask.result" class="progress-result">
            {{ t('skills.import.installed') }}: <strong>{{ currentTask.result.name }}</strong>
            ({{ currentTask.result.sourceType }})
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { useFileDrop } from '@/composables/useFileDrop'
import { skillInstallApi } from '@/api/index'
import type { InstallTask, HubSkillInfo } from '@/types/index'

const props = defineProps<{ visible: boolean }>()
// RFC-090 §4.4 — when install completes, hand the parent the skill
// name so it can auto-open the pre-flight dialog if requirements
// aren't met. Backwards-compat: payload is optional, old listeners
// that ignore it keep working.
const emit = defineEmits<{
  (e: 'update:visible', val: boolean): void
  (e: 'installed', payload?: { name?: string }): void
}>()

const { t } = useI18n()

const activeTab = ref<'url' | 'search' | 'zip'>('url')
const urlInput = ref('')
const searchQuery = ref('')
const searchResults = ref<HubSkillInfo[]>([])
const searchDone = ref(false)
const searching = ref(false)
const installing = ref(false)
const enableAfterInstall = ref(true)
const overwriteExisting = ref(false)
const currentTask = ref<InstallTask | null>(null)
const zipFile = ref<File | null>(null)
const zipInputRef = ref<HTMLInputElement | null>(null)
const { isDragging, onDragEnter, onDragLeave, onDrop } = useFileDrop(handleDroppedZip)
let pollTimer: ReturnType<typeof setInterval> | null = null

watch(() => props.visible, (val) => {
  if (!val) {
    stopPolling()
    currentTask.value = null
  }
})

function handleClose() {
  if (installing.value) return
  emit('update:visible', false)
}

async function startUrlInstall() {
  const url = urlInput.value.trim()
  if (!url) return
  await doInstall(url)
}

async function installFromHub(item: HubSkillInfo) {
  const url = item.bundleUrl || `https://clawhub.ai/skills/${item.slug}`
  await doInstall(url)
}

async function doInstall(bundleUrl: string) {
  installing.value = true
  currentTask.value = null
  try {
    const res: any = await skillInstallApi.startInstall({
      bundleUrl,
      enable: enableAfterInstall.value,
      overwrite: overwriteExisting.value,
    })
    currentTask.value = res.data
    startPolling(res.data.taskId)
  } catch (e: any) {
    mcToast.error(e?.message || t('skills.import.failed'))
    installing.value = false
  }
}

function startPolling(taskId: string) {
  stopPolling()
  pollTimer = setInterval(async () => {
    try {
      const res: any = await skillInstallApi.getStatus(taskId)
      currentTask.value = res.data
      const status = res.data?.status
      if (status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED') {
        stopPolling()
        installing.value = false
        if (status === 'COMPLETED') {
          mcToast.success(t('skills.import.installed'))
          // The install task's result envelope carries `{name, enabled, sourceUrl, ...}`.
          // Hand the slug along so the parent can pop preflight for the
          // freshly-installed skill if its requirements aren't met.
          emit('installed', { name: res.data?.result?.name })
        }
      }
    } catch {
      stopPolling()
      installing.value = false
    }
  }, 1500)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function cancelInstall() {
  if (!currentTask.value) return
  try {
    await skillInstallApi.cancelInstall(currentTask.value.taskId)
    mcToast.info(t('skills.import.cancelled'))
  } catch {
    // ignore
  }
}

function triggerFileInput() {
  zipInputRef.value?.click()
}

function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (file && file.name.endsWith('.zip')) {
    zipFile.value = file
  } else if (file) {
    mcToast.warning(t('skills.import.invalidZip'))
  }
  input.value = ''
}

function handleDroppedZip(e: DragEvent) {
  const file = e.dataTransfer?.files?.[0]
  if (file && file.name.endsWith('.zip')) {
    zipFile.value = file
  } else {
    mcToast.warning(t('skills.import.invalidZip'))
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

async function uploadZip() {
  if (!zipFile.value) return
  if (zipFile.value.size > 50 * 1024 * 1024) {
    mcToast.error(t('skills.import.tooLarge'))
    return
  }
  installing.value = true
  try {
    const res: any = await skillInstallApi.uploadZip(zipFile.value, {
      enable: enableAfterInstall.value,
      overwrite: overwriteExisting.value,
    })
    mcToast.success(t('skills.import.uploadSuccess'))
    zipFile.value = null
    emit('installed', { name: res?.data?.name })
  } catch (e: any) {
    mcToast.error(e?.response?.data?.msg || e?.message || t('skills.import.uploadFailed'))
  } finally {
    installing.value = false
  }
}

async function doSearch() {
  const q = searchQuery.value.trim()
  if (!q) return
  searching.value = true
  searchDone.value = false
  try {
    const res: any = await skillInstallApi.searchHub(q)
    searchResults.value = res.data || []
    searchDone.value = true
  } catch (e: any) {
    mcToast.error(e?.message || t('skills.import.searchFailed'))
    searchResults.value = []
    searchDone.value = true
  } finally {
    searching.value = false
  }
}

function getStatusLabel(status: string): string {
  const map: Record<string, string> = {
    PENDING: t('skills.import.statusPending'),
    INSTALLING: t('skills.import.installing'),
    COMPLETED: t('skills.import.installed'),
    FAILED: t('skills.import.failed'),
    CANCELLED: t('skills.import.cancelled'),
  }
  return map[status] || status
}
</script>

<style scoped>
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.import-modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 520px; max-height: 85vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.modal-close:disabled { opacity: 0.5; cursor: not-allowed; }
.modal-body { flex: 1; overflow-y: auto; padding: 20px 24px; }

.import-tabs { display: flex; gap: 4px; margin-bottom: 16px; background: var(--mc-bg-sunken); border-radius: 8px; padding: 3px; }
.import-tab { flex: 1; padding: 8px 12px; border: none; background: none; border-radius: 6px; font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; }
.import-tab.active { background: var(--mc-bg-elevated); color: var(--mc-text-primary); box-shadow: 0 1px 3px rgba(0,0,0,0.08); }

.tab-content { display: flex; flex-direction: column; gap: 12px; }
.form-group { display: flex; flex-direction: column; gap: 4px; }
.form-input { padding: 10px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; background: var(--mc-bg-sunken); transition: border-color 0.15s; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.form-input:disabled { opacity: 0.6; cursor: not-allowed; }
.form-hint-text { font-size: 12px; color: var(--mc-text-tertiary); margin: 0; }

.url-examples { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.example-label { font-size: 12px; color: var(--mc-text-tertiary); }
.example-url { font-size: 11px; padding: 2px 6px; background: var(--mc-bg-sunken); border-radius: 4px; color: var(--mc-text-secondary); font-family: 'JetBrains Mono', monospace; }

.form-options { display: flex; gap: 16px; }
.checkbox-label { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--mc-text-secondary); cursor: pointer; }
.checkbox-label input { accent-color: var(--mc-primary); }

.install-btn { width: 100%; justify-content: center; padding: 10px; }
.search-btn { width: 100%; justify-content: center; padding: 8px; }
.btn-primary { display: flex; align-items: center; gap: 6px; padding: 8px 16px; background: var(--mc-primary); color: white; border: none; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; transition: background 0.15s; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { display: flex; align-items: center; gap: 6px; padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-secondary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-sm { padding: 5px 12px; font-size: 12px; border-radius: 6px; flex-shrink: 0; }

.search-results { display: flex; flex-direction: column; gap: 8px; margin-top: 4px; max-height: 300px; overflow-y: auto; }
.search-item { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border: 1px solid var(--mc-border); border-radius: 8px; background: var(--mc-bg); }
.search-item:hover { border-color: var(--mc-primary-light); }
.search-item-info { display: flex; gap: 10px; align-items: flex-start; overflow: hidden; }
.search-item-icon { font-size: 20px; flex-shrink: 0; }
.search-item-name { font-size: 14px; font-weight: 600; color: var(--mc-text-primary); }
.search-item-desc { font-size: 12px; color: var(--mc-text-secondary); line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.search-item-meta { display: flex; gap: 8px; margin-top: 2px; font-size: 11px; color: var(--mc-text-tertiary); }
.search-empty { text-align: center; padding: 20px; color: var(--mc-text-tertiary); font-size: 14px; }

.install-progress { margin-top: 16px; padding: 12px; border: 1px solid var(--mc-border); border-radius: 8px; background: var(--mc-bg); }
.progress-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.progress-status { font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 999px; }
.status-pending { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.status-installing { background: var(--mc-primary-bg); color: var(--mc-primary); }
.status-completed { background: #e6f9e6; color: #2d8c2d; }
.status-failed { background: var(--mc-danger-bg); color: var(--mc-danger); }
.status-cancelled { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.btn-cancel { padding: 4px 10px; border: 1px solid var(--mc-border); background: none; border-radius: 6px; font-size: 12px; color: var(--mc-text-secondary); cursor: pointer; }
.btn-cancel:hover { background: var(--mc-bg-sunken); }
.progress-url { font-size: 11px; color: var(--mc-text-tertiary); font-family: monospace; word-break: break-all; margin-bottom: 4px; }
.progress-error { font-size: 12px; color: var(--mc-danger); margin-top: 4px; }
.progress-result { font-size: 13px; color: var(--mc-text-primary); margin-top: 4px; }

/* ZIP upload zone */
.upload-zone { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 140px; border: 2px dashed var(--mc-border); border-radius: 12px; padding: 24px; cursor: pointer; transition: all 0.2s; text-align: center; }
.upload-zone:hover { border-color: var(--mc-primary); background: var(--mc-bg-muted); }
.upload-zone.drag-over { border-color: var(--mc-primary); background: var(--mc-primary-light, rgba(217,119,87,0.06)); }
.upload-zone.has-file { border-style: solid; border-color: var(--mc-primary); }
.upload-hint { font-size: 14px; color: var(--mc-text-secondary); margin: 0; }
.upload-sub { font-size: 12px; color: var(--mc-text-tertiary); margin: 4px 0 0; }
.hidden-input { position: absolute; width: 0; height: 0; opacity: 0; pointer-events: none; }
.file-preview { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.file-icon { font-size: 20px; }
.file-name { font-weight: 500; color: var(--mc-text-primary); }
.file-size { font-size: 12px; color: var(--mc-text-tertiary); }
.file-remove { border: none; background: none; color: var(--mc-text-tertiary); font-size: 18px; cursor: pointer; padding: 0 4px; }
.file-remove:hover { color: var(--mc-danger); }
</style>
