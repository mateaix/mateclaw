<template>
  <div class="raw-panel">
    <!-- Upload + Add text row -->
    <div class="upload-row">
      <div
        class="upload-zone"
        :class="{ 'is-dragging': isDragging, 'is-uploading': uploadingFiles.length > 0 }"
        @click="triggerFileInput"
        @dragover.prevent
        @dragenter.prevent="onDragEnter"
        @dragleave.prevent="onDragLeave"
        @drop.prevent="handleDrop"
      >
        <!-- Spinner while uploading -->
        <svg v-if="uploadingFiles.length > 0" class="upload-spinner" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M21 12a9 9 0 1 1-6.219-8.56"/>
        </svg>
        <!-- Arrow-up icon in drag-over state -->
        <svg v-else-if="isDragging" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <polyline points="17 8 12 3 7 8"/>
          <line x1="12" y1="3" x2="12" y2="21"/>
        </svg>
        <!-- Default upload icon -->
        <svg v-else width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
          <polyline points="17 8 12 3 7 8"/>
          <line x1="12" y1="3" x2="12" y2="15"/>
        </svg>
        <div class="upload-text">
          <span class="upload-label">
            <template v-if="uploadingFiles.length > 0">{{ t('wiki.uploading') }}</template>
            <template v-else-if="isDragging">{{ t('wiki.dropToUpload') }}</template>
            <template v-else>{{ t('wiki.dropFiles') }}</template>
          </span>
          <span class="upload-hint">.txt, .md, .pdf, .docx</span>
        </div>
      </div>
      <input ref="fileInput" type="file" style="display:none" accept=".txt,.md,.pdf,.docx,.doc" multiple @change="handleFileSelect" />
      <button class="btn-secondary add-text-btn" @click="showAddText = true">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        {{ t('wiki.addText') }}
      </button>
    </div>

    <!-- Directory scan -->
    <div class="dir-scan-row">
      <div class="dir-input-wrap">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
        </svg>
        <input
          v-model="dirPath"
          type="text"
          class="dir-input"
          :placeholder="t('wiki.dirPlaceholder')"
          @keyup.enter="handleScanDir"
        />
      </div>
      <button class="btn-secondary" @click="handleScanDir" :disabled="scanning || !dirPath.trim()">
        <svg v-if="!scanning" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        {{ scanning ? t('wiki.scanning') : t('wiki.scan') }}
      </button>
    </div>
    <div v-if="scanResult" class="scan-result">
      {{ t('wiki.scanResult', { scanned: scanResult.scanned, added: scanResult.added, skipped: scanResult.skipped }) }}
    </div>

    <!-- Raw materials list -->
    <div class="raw-list">
      <h4 class="raw-list-title">
        {{ t('wiki.rawMaterials') }} ({{ store.rawMaterials.length + uploadingFiles.length }})
      </h4>
      <div v-if="store.rawMaterials.length === 0 && uploadingFiles.length === 0" class="empty-hint">
        {{ t('wiki.noRawMaterials') }}
      </div>

      <!-- Optimistic uploading items shown at the top -->
      <div
        v-for="uf in uploadingFiles"
        :key="uf.tempId"
        class="raw-item raw-item--uploading"
      >
        <div class="raw-item-row">
          <div class="raw-item-info">
            <span class="raw-item-title">{{ uf.name }}</span>
          </div>
          <div class="raw-item-meta">
            <span v-if="uf.status === 'error'" class="status-badge failed">{{ t('wiki.status.failed') }}</span>
            <span v-else class="status-badge uploading">{{ t('wiki.status.uploading') }}</span>
            <span
              v-if="uf.status === 'error' && uf.errorMsg"
              class="error-hint" :title="uf.errorMsg"
            >{{ uf.errorMsg }}</span>
          </div>
          <div class="raw-item-actions">
            <!-- Dismiss error item -->
            <button
              v-if="uf.status === 'error'"
              class="btn-icon btn-icon-danger"
              :title="t('common.delete')"
              @click="removeUploadingFile(uf.tempId)"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
        </div>
        <!-- HTTP upload progress bar -->
        <div v-if="uf.status !== 'error'" class="raw-progress">
          <div class="raw-progress-track">
            <div
              class="raw-progress-fill"
              :class="{ indeterminate: uf.httpPct === 0 }"
              :style="uf.httpPct > 0 ? { width: uf.httpPct + '%' } : {}"
            ></div>
          </div>
          <span class="raw-progress-label">
            {{ uf.httpPct > 0 ? t('wiki.progress.uploading', { pct: uf.httpPct }) : t('wiki.progress.preparing') }}
          </span>
        </div>
      </div>

      <div
        v-for="raw in store.rawMaterials"
        :key="raw.id"
        class="raw-item"
        :class="{ 'raw-item--active': store.selectedRawId === raw.id }"
        @click="toggleRawFilter(raw.id)"
      >
        <div class="raw-item-row">
          <div class="raw-item-info">
            <span class="raw-item-title">{{ raw.title }}</span>
            <span class="raw-item-type">{{ raw.sourceType }}</span>
          </div>
          <div class="raw-item-meta">
            <span
              class="status-badge"
              :class="cancellingIds.has(raw.id) && raw.processingStatus === 'processing' ? 'cancelling' : raw.processingStatus"
            >
              {{ cancellingIds.has(raw.id) && raw.processingStatus === 'processing'
                ? t('wiki.status.cancelling')
                : t(`wiki.status.${raw.processingStatus}`) }}
            </span>
            <span v-if="raw.pageCount != null && raw.pageCount > 0" class="page-count-chip">
              <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
              {{ raw.pageCount }}
            </span>
            <span
              v-if="raw.processingStatus === 'cancelled'"
              class="error-hint" :title="raw.errorMessage || ''"
            >
              {{ t('wiki.cancelledHint') }}
            </span>
            <span
              v-else-if="raw.errorMessage && (raw.processingStatus === 'failed' || raw.processingStatus === 'partial')"
              class="error-hint" :title="raw.errorMessage"
            >
              {{ raw.errorMessage }}
            </span>
          </div>
          <div class="raw-item-actions">
            <button
              v-if="raw.processingStatus === 'processing' && !cancellingIds.has(raw.id)"
              class="btn-icon btn-icon-danger" :title="t('wiki.cancel')"
              @click="cancelRaw(raw.id)"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
                <line x1="6" y1="6" x2="18" y2="18"/>
                <line x1="6" y1="18" x2="18" y2="6"/>
              </svg>
            </button>
            <button
              v-else-if="raw.processingStatus === 'processing' && cancellingIds.has(raw.id)"
              class="btn-icon btn-icon-cancelling" :title="t('wiki.cancelling')" disabled
            >
              <svg class="spinner" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
                <path d="M21 12a9 9 0 1 1-6.22-8.56"/>
              </svg>
            </button>
            <button
              v-else-if="raw.processingStatus === 'partial'"
              class="btn-icon btn-icon-resume" :title="t('wiki.resume')"
              @click="reprocess(raw.id)"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round">
                <polygon points="6 4 20 12 6 20 6 4"/>
              </svg>
            </button>
            <button
              v-else-if="raw.processingStatus === 'failed' || raw.processingStatus === 'completed' || raw.processingStatus === 'cancelled'"
              class="btn-icon" :title="t('wiki.reprocess')"
              @click="reprocess(raw.id)"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="23 4 23 10 17 10"/>
                <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
              </svg>
            </button>
            <button
              v-if="raw.processingStatus !== 'uploading'"
              class="btn-icon" :title="t('wiki.download')"
              @click="downloadRaw(raw)"
            >
              <el-icon :size="14"><Download /></el-icon>
            </button>
            <button class="btn-icon btn-icon-danger" :title="t('common.delete')" @click="deleteRaw(raw.id)">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="3 6 5 6 21 6"/>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
              </svg>
            </button>
          </div>
        </div>
        <!-- RFC-033: Job stage bar — show when job has progressed past 'queued' or reached terminal -->
        <JobStageBar
          v-if="rawJobs[raw.id] && (rawJobs[raw.id].stage !== 'queued' || rawJobs[raw.id].status !== 'queued')"
          :stage="rawJobs[raw.id].stage"
          :status="rawJobs[raw.id].status"
          :current-model="rawJobs[raw.id].currentModelName ?? (rawJobs[raw.id].currentModelId ? `Model #${rawJobs[raw.id].currentModelId}` : undefined)"
          :is-fallback-active="rawJobs[raw.id].currentModelId != null && rawJobs[raw.id].currentModelId !== rawJobs[raw.id].primaryModelId"
          :error-code="rawJobs[raw.id].errorCode ?? undefined"
          :error-message="rawJobs[raw.id].errorMessage ?? undefined"
          :done="rawJobs[raw.id].done ?? raw.progressDone"
          :total="rawJobs[raw.id].total ?? raw.progressTotal"
          :started-at="rawJobs[raw.id].startedAt ?? undefined"
          @reprocess="reprocess(raw.id)"
          @repair="handleLocalRepair(raw.id)"
        />
        <!-- Original progress bar: shown during processing when job data is not active -->
        <div v-else-if="raw.processingStatus === 'processing'" class="raw-progress">
          <div class="raw-progress-track">
            <div
              class="raw-progress-fill"
              :class="{ indeterminate: !raw.progressTotal }"
              :style="raw.progressTotal
                ? { width: Math.min(100, Math.round((raw.progressDone / raw.progressTotal) * 100)) + '%' }
                : {}"
            ></div>
          </div>
          <span class="raw-progress-label">
            {{ raw.progressTotal
              ? `${raw.progressDone} / ${raw.progressTotal}`
              : t('wiki.progress.preparing') }}
          </span>
        </div>
      </div>
    </div>

    <!-- Process all button -->
    <button
      v-if="store.currentKB && store.rawMaterials.some(r => r.processingStatus === 'pending')"
      class="btn-primary process-btn"
      @click="processAll"
    >
      {{ t('wiki.processAll') }}
    </button>

    <!-- Add Text Modal -->
    <div v-if="showAddText" class="modal-overlay">
      <div class="modal-content">
        <h3 class="modal-title">{{ t('wiki.addText') }}</h3>
        <div class="form-group">
          <label>{{ t('wiki.materialTitle') }}</label>
          <input v-model="textTitle" type="text" class="form-input" />
        </div>
        <div class="form-group">
          <label>{{ t('wiki.materialContent') }}</label>
          <textarea v-model="textContent" class="form-input" rows="12" :placeholder="t('wiki.pasteContent')"></textarea>
        </div>
        <div class="modal-actions">
          <button class="btn-secondary" @click="showAddText = false">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="handleAddText" :disabled="!textTitle.trim() || !textContent.trim()">
            {{ t('common.add') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { Download } from '@element-plus/icons-vue'
import { useWikiStore } from '@/stores/useWikiStore'
import { wikiApi } from '@/api/index'
import JobStageBar from './JobStageBar.vue'
import type { WikiProcessingJob } from '@/composables/useWikiJobPoller'

const { t } = useI18n()
const store = useWikiStore()
const fileInput = ref<HTMLInputElement | null>(null)

// While raw materials are active, subscribe to the backend SSE progress stream.
// A slower polling fallback keeps the UI in sync if SSE reconnects or misses a
// terminal event. The database remains the source of truth.
let sse: EventSource | null = null
let fallbackTimer: number | null = null
let activeKbId: number | null = null

const hasProcessing = computed(() =>
  store.rawMaterials.some(r => r.processingStatus === 'processing' || r.processingStatus === 'pending')
)

function applyProgressEvent(payload: any) {
  if (!payload || payload.rawId == null) return
  const raw = store.rawMaterials.find(r => r.id === payload.rawId)
  if (!raw) return
  if (typeof payload.done === 'number') raw.progressDone = payload.done
  if (typeof payload.total === 'number') raw.progressTotal = payload.total
}

function openSse(kbId: number) {
  closeSse()
  activeKbId = kbId
  // Vite proxies /api to the backend, so EventSource can use a relative URL.
  const es = new EventSource(`/api/v1/wiki/knowledge-bases/${kbId}/progress`)
  sse = es

  es.addEventListener('raw.started', (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data)
      const raw = store.rawMaterials.find(r => r.id === data.rawId)
      if (raw) {
        raw.processingStatus = 'processing'
        raw.progressDone = 0
        raw.progressTotal = 0
      }
    } catch { /* ignore */ }
  })
  es.addEventListener('route.done', (ev: MessageEvent) => {
    try { applyProgressEvent(JSON.parse(ev.data)) } catch { /* ignore */ }
  })
  es.addEventListener('chunk.done', (ev: MessageEvent) => {
    try { applyProgressEvent(JSON.parse(ev.data)) } catch { /* ignore */ }
  })
  es.addEventListener('raw.completed', (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data)
      const raw = store.rawMaterials.find(r => r.id === data.rawId)
      if (raw) {
        raw.processingStatus = data.status === 'partial' ? 'partial' : 'completed'
        if (typeof data.totalPages === 'number') {
          raw.progressDone = data.totalPages
          raw.progressTotal = data.totalPages
        }
      }
      // Clear stale job entry so JobStageBar hides
      delete rawJobs[data.rawId]
      if (store.currentKB) void store.refreshCurrentKB()
    } catch { /* ignore */ }
  })
  es.addEventListener('raw.failed', (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data)
      const raw = store.rawMaterials.find(r => r.id === data.rawId)
      if (raw) raw.processingStatus = 'failed'
      // Clear stale job entry
      delete rawJobs[data.rawId]
      if (store.currentKB) void store.refreshCurrentKB()
    } catch { /* ignore */ }
  })
  es.onerror = () => {
    // Browser EventSource auto-reconnects; just log
    // console.debug('Wiki SSE error/reconnect', kbId)
  }
}

function closeSse() {
  if (sse) {
    sse.close()
    sse = null
  }
  activeKbId = null
}

watch(
  () => [hasProcessing.value, store.currentKB?.id] as const,
  ([active, kbId]) => {
    if (active && kbId != null) {
      // SSE main channel
      if (activeKbId !== kbId) openSse(kbId)
      // 60s fallback polling
      if (fallbackTimer == null) {
        fallbackTimer = window.setInterval(() => {
          if (store.currentKB) void store.refreshCurrentKB()
        }, 60000)
      }
    } else {
      closeSse()
      if (fallbackTimer != null) {
        clearInterval(fallbackTimer)
        fallbackTimer = null
      }
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  closeSse()
  if (fallbackTimer != null) {
    clearInterval(fallbackTimer)
    fallbackTimer = null
  }
})

// RFC-033: Job polling per raw material
const rawJobs = reactive<Record<number, WikiProcessingJob>>({})
let jobPoller: ReturnType<typeof setTimeout> | null = null

const TERMINAL_STATUSES = new Set(['completed', 'failed', 'partial', 'cancelled'])

// Local optimistic state: rows the user has just clicked "cancel" on.
// Backend cancellation is observed at the next abort checkpoint (which can
// take 10+ seconds while a route-phase LLM call is in flight), so without
// this set the click looks unresponsive — the button stays the same and
// the badge keeps reading "处理中". Cleared as soon as the row's status
// transitions out of "processing" via the next fetchRawMaterials.
const cancellingIds = ref(new Set<number>())

async function pollJobs() {
  if (!store.currentKB) return
  const kbId = store.currentKB.id
  const processingRaws = store.rawMaterials.filter(
    r => r.processingStatus === 'processing' || r.processingStatus === 'pending'
  )
  let anyTerminal = false
  for (const raw of processingRaws) {
    try {
      const res: any = await wikiApi.getWikiJobs(kbId, raw.id)
      const list = res.data || res || []
      if (list.length > 0) {
        const job = list[0]
        rawJobs[raw.id] = job
        if (TERMINAL_STATUSES.has(job.status)) {
          anyTerminal = true
        }
      }
    } catch { /* ignore */ }
  }
  // When any job reaches terminal, refresh wiki metadata, pages, and raw badges.
  if (anyTerminal) {
    await store.refreshCurrentKB()
  }
  // Continue polling while there are still processing/pending raws
  const stillActive = store.rawMaterials.some(
    r => r.processingStatus === 'processing' || r.processingStatus === 'pending'
  )
  if (stillActive) {
    jobPoller = setTimeout(pollJobs, 3000)
  }
}

watch(hasProcessing, (active) => {
  if (active) pollJobs()
  else if (jobPoller) { clearTimeout(jobPoller); jobPoller = null }
}, { immediate: true })

// Clear the optimistic "cancelling" flag for any row that has left the
// 'processing' state — the backend has written the terminal status, so
// the badge and action buttons can now reflect reality.
watch(() => store.rawMaterials, (rows) => {
  if (cancellingIds.value.size === 0) return
  const next = new Set(cancellingIds.value)
  for (const r of rows) {
    if (r.processingStatus !== 'processing' && next.has(r.id)) {
      next.delete(r.id)
    }
  }
  if (next.size !== cancellingIds.value.size) {
    cancellingIds.value = next
  }
}, { deep: true })

async function handleLocalRepair(rawId: number) {
  if (!store.currentKB) return
  // For local repair, we'd need a page slug. For now, reprocess the raw material.
  await reprocess(rawId)
}

const showAddText = ref(false)
const textTitle = ref('')
const textContent = ref('')
const dirPath = ref(store.currentKB?.sourceDirectory || '')
const scanning = ref(false)
const scanResult = ref<{ scanned: number; added: number; skipped: number } | null>(null)

// ─── Drag-over state ──────────────────────────────────────────────────────────
// Use a counter to handle nested dragenter/dragleave without flickering.
const isDragging = ref(false)
let dragCounter = 0

function onDragEnter() {
  dragCounter++
  isDragging.value = true
}

function onDragLeave() {
  dragCounter--
  if (dragCounter <= 0) {
    dragCounter = 0
    isDragging.value = false
  }
}

// ─── Optimistic upload items ──────────────────────────────────────────────────
interface UploadingFile {
  tempId: string
  name: string
  httpPct: number
  status: 'uploading' | 'error'
  errorMsg?: string
}

const uploadingFiles = ref<UploadingFile[]>([])
let tempIdCounter = 0

function addUploadingFile(name: string): UploadingFile {
  const item: UploadingFile = {
    tempId: `upload-${++tempIdCounter}`,
    name,
    httpPct: 0,
    status: 'uploading',
  }
  uploadingFiles.value.push(item)
  return item
}

function removeUploadingFile(tempId: string) {
  const idx = uploadingFiles.value.findIndex(f => f.tempId === tempId)
  if (idx >= 0) uploadingFiles.value.splice(idx, 1)
}

// ─── Upload helpers ───────────────────────────────────────────────────────────
async function uploadFile(kbId: number, file: File) {
  const item = addUploadingFile(file.name)
  try {
    await store.uploadRawFile(kbId, file, (pct) => {
      item.httpPct = pct
    })
    // Success: real item was added to store.rawMaterials, remove the optimistic placeholder
    removeUploadingFile(item.tempId)
  } catch (err: any) {
    item.status = 'error'
    item.errorMsg = err?.response?.data?.message || err?.message || t('wiki.uploadFailed', { name: file.name })
    mcToast.error(t('wiki.uploadFailed', { name: file.name }))
  }
}

function triggerFileInput() {
  fileInput.value?.click()
}

async function handleFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files || !store.currentKB) return
  const kbId = store.currentKB.id
  // Upload all files concurrently
  await Promise.all(Array.from(input.files).map(f => uploadFile(kbId, f)))
  input.value = ''
}

async function handleDrop(event: DragEvent) {
  // Reset drag state
  dragCounter = 0
  isDragging.value = false
  if (!event.dataTransfer?.files || !store.currentKB) return
  const kbId = store.currentKB.id
  await Promise.all(Array.from(event.dataTransfer.files).map(f => uploadFile(kbId, f)))
}

async function handleAddText() {
  if (!store.currentKB) return
  await store.addRawText(store.currentKB.id, textTitle.value, textContent.value)
  showAddText.value = false
  textTitle.value = ''
  textContent.value = ''
}

async function reprocess(rawId: number) {
  if (!store.currentKB) return
  const kbId = store.currentKB.id
  await wikiApi.reprocessRaw(kbId, rawId)
  // Immediately mark local state as processing so SSE connects and progress bar shows
  const raw = store.rawMaterials.find(r => r.id === rawId)
  if (raw) {
    raw.processingStatus = 'processing'
    raw.progressDone = 0
    raw.progressTotal = 0
  }
  // Clear stale job entry
  delete rawJobs[rawId]
  await store.fetchRawMaterials(kbId)
  // Delayed re-fetch to catch final status if processing finishes before SSE connects
  setTimeout(() => { store.fetchRawMaterials(kbId) }, 5000)
  setTimeout(() => { store.fetchRawMaterials(kbId) }, 15000)
}

async function deleteRaw(rawId: number) {
  if (!store.currentKB) return
  await wikiApi.deleteRaw(store.currentKB.id, rawId)
  await store.fetchRawMaterials(store.currentKB.id)
}

async function cancelRaw(rawId: number) {
  if (!store.currentKB) return
  const kbId = store.currentKB.id
  // Optimistic flag — drives the spinner button and "正在取消…" badge text
  // so the click is visibly registered even if the pipeline is currently
  // mid-LLM call and won't reach its next abort checkpoint for several seconds.
  cancellingIds.value.add(rawId)
  try {
    await wikiApi.cancelRaw(kbId, rawId)
  } catch (e) {
    // Roll back the optimistic state if the call itself failed (auth /
    // network error). Without this the button would stay stuck in the
    // cancelling state forever.
    cancellingIds.value.delete(rawId)
    throw e
  }
  // Re-fetch periodically so the row's status flips from 'processing' to
  // 'cancelled' as soon as the pipeline observes the flag and writes its
  // terminal status — at which point the watch below clears the flag.
  await store.fetchRawMaterials(kbId)
  setTimeout(() => { store.fetchRawMaterials(kbId) }, 5000)
  setTimeout(() => { store.fetchRawMaterials(kbId) }, 15000)
}

async function downloadRaw(raw: { id: number; title?: string }) {
  if (!store.currentKB) return
  try {
    // The http interceptor returns the raw body for non-R-shaped responses,
    // so this resolves directly to the Blob (no .data unwrap needed).
    const blob = (await wikiApi.downloadRaw(store.currentKB.id, raw.id)) as unknown as Blob
    let filename = raw.title && raw.title.trim().length > 0 ? raw.title : `raw-${raw.id}`
    if (!filename.includes('.')) filename += '.txt'
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    // Revoke on next tick — some browsers cancel the in-flight download if we
    // revoke synchronously before the click handler returns.
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    mcToast.error(`${t('wiki.downloadFailed')}: ${msg}`)
  }
}

async function processAll() {
  if (!store.currentKB) return
  const kbId = store.currentKB.id
  await wikiApi.processKB(kbId)
  // Mark all pending materials as processing so SSE connects
  store.rawMaterials
    .filter(r => r.processingStatus === 'pending')
    .forEach(r => { r.processingStatus = 'processing'; r.progressDone = 0; r.progressTotal = 0 })
  await store.fetchRawMaterials(kbId)
  setTimeout(() => { store.fetchRawMaterials(kbId) }, 5000)
}

function toggleRawFilter(rawId: number) {
  if (!store.currentKB) return
  const kbId = store.currentKB.id
  if (store.selectedRawId === rawId) {
    store.clearRawFilter(kbId)
  } else {
    store.filterPagesByRaw(kbId, rawId)
  }
}

async function handleScanDir() {
  if (!store.currentKB || !dirPath.value.trim()) return
  scanning.value = true
  scanResult.value = null
  try {
    // Save directory path first
    await wikiApi.setSourceDirectory(store.currentKB.id, dirPath.value.trim())
    // Trigger scan
    const result = await store.scanDirectory(store.currentKB.id)
    scanResult.value = result
  } catch (e: any) {
    console.error('Scan failed', e)
  } finally {
    scanning.value = false
  }
}
</script>

<style scoped>
.raw-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* Buttons */
.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; background: var(--mc-primary); color: white; border: none; border-radius: 10px; font-size: 14px; font-weight: 500; cursor: pointer; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; cursor: pointer; white-space: nowrap; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

/* Directory scan */
.dir-scan-row { display: flex; gap: 10px; align-items: center; }
.dir-input-wrap { flex: 1; display: flex; align-items: center; gap: 8px; padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 12px; background: var(--mc-bg-elevated); color: var(--mc-text-tertiary); }
.dir-input-wrap:focus-within { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.dir-input { flex: 1; border: none; background: transparent; font-size: 13px; color: var(--mc-text-primary); outline: none; }
.dir-input::placeholder { color: var(--mc-text-tertiary); }
.scan-result { font-size: 12px; color: var(--mc-text-secondary); padding: 8px 10px; background: rgba(90,138,90,0.1); border-radius: 10px; }

/* Upload row: zone + add text side by side */
.upload-row { display: flex; gap: 12px; align-items: stretch; }
.upload-zone {
  flex: 1;
  border: 1px dashed var(--mc-border);
  border-radius: 16px;
  padding: 18px 20px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--mc-text-tertiary);
}
.upload-zone:hover { border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.upload-zone.is-dragging {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
  box-shadow: 0 0 0 3px rgba(217,119,87,0.15);
  color: var(--mc-primary);
}
.upload-zone.is-uploading {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
  cursor: default;
  pointer-events: none;
}
.upload-zone svg { flex-shrink: 0; }
.upload-text { display: flex; flex-direction: column; gap: 2px; }
.upload-label { font-size: 14px; color: var(--mc-text-secondary); }
.upload-hint { font-size: 12px; color: var(--mc-text-tertiary); }
.add-text-btn { flex-shrink: 0; }

/* Spinner animation for uploading state */
.upload-spinner {
  flex-shrink: 0;
  animation: spin 1s linear infinite;
  color: var(--mc-primary);
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to   { transform: rotate(360deg); }
}

/* Raw list */
.raw-list { display: flex; flex-direction: column; gap: 8px; padding-top: 4px; }
.raw-list-title { font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--mc-text-tertiary); margin-bottom: 4px; }
.empty-hint { text-align: center; padding: 24px 0; font-size: 14px; color: var(--mc-text-tertiary); }

.raw-item { display: flex; flex-direction: column; gap: 8px; padding: 12px 14px; background: linear-gradient(180deg, var(--mc-bg-elevated), var(--mc-bg-muted)); border: 1px solid var(--mc-border-light); border-radius: 14px; font-size: 13px; transition: border-color 0.15s, transform 0.15s; cursor: pointer; }
.raw-item:hover { border-color: var(--mc-border); transform: translateY(-1px); }
.raw-item--active { border-color: var(--mc-primary) !important; background: var(--mc-primary-bg) !important; transform: translateY(-1px); }
.raw-item--uploading { cursor: default; opacity: 0.85; }
.raw-item--uploading:hover { transform: none; border-color: var(--mc-border-light); }
.raw-item-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }

.raw-item-info { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; }
.raw-item-title { font-weight: 500; color: var(--mc-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.raw-item-type { font-size: 10px; padding: 2px 6px; background: var(--mc-bg-sunken); border-radius: 4px; text-transform: uppercase; color: var(--mc-text-tertiary); letter-spacing: 0.02em; }
.raw-item-meta { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.raw-item-actions { display: flex; gap: 4px; flex-shrink: 0; }
.error-hint { font-size: 11px; color: var(--mc-danger); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.page-count-chip { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; font-weight: 500; color: var(--mc-text-secondary); background: var(--mc-bg-sunken); border-radius: 9999px; padding: 2px 7px; }

/* Two-phase digest progress bar (RFC-012 M2 v2 UI) */
.raw-progress { display: flex; align-items: center; gap: 10px; padding-top: 2px; }
.raw-progress-track { flex: 1; height: 4px; background: var(--mc-bg-sunken); border-radius: 9999px; overflow: hidden; position: relative; }
.raw-progress-fill { height: 100%; background: var(--mc-primary); border-radius: 9999px; transition: width 0.3s ease; }
.raw-progress-fill.indeterminate {
  width: 30%;
  position: absolute;
  left: 0;
  animation: raw-progress-slide 1.6s ease-in-out infinite;
}
@keyframes raw-progress-slide {
  0%   { transform: translateX(-100%); }
  50%  { transform: translateX(170%); }
  100% { transform: translateX(330%); }
}
.raw-progress-label { font-size: 11px; color: var(--mc-text-tertiary); font-variant-numeric: tabular-nums; flex-shrink: 0; min-width: 56px; text-align: right; }

/* Icon button */
.btn-icon { width: 30px; height: 30px; border: 1px solid var(--mc-border-light); background: var(--mc-bg-elevated); cursor: pointer; border-radius: 8px; color: var(--mc-text-secondary); transition: all 0.15s; display: flex; align-items: center; justify-content: center; }
.btn-icon:hover { background: var(--mc-bg-sunken); color: var(--mc-primary); border-color: var(--mc-border); }
.btn-icon-danger:hover { background: var(--mc-danger-bg); color: var(--mc-danger); border-color: var(--mc-danger); }
.btn-icon-resume { color: var(--mc-primary); border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.btn-icon-resume:hover { background: var(--mc-primary); color: #fff; border-color: var(--mc-primary); }

/* Status badges */
.status-badge { font-size: 10px; padding: 2px 8px; border-radius: 9999px; text-transform: uppercase; font-weight: 500; letter-spacing: 0.02em; }
.status-badge.pending { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.status-badge.uploading { background: rgba(59, 130, 246, 0.12); color: #3b82f6; }
.status-badge.processing { background: var(--mc-primary-bg); color: var(--mc-primary); }
.status-badge.completed { background: rgba(90, 138, 90, 0.15); color: var(--mc-success); }
.status-badge.partial { background: rgba(217, 119, 87, 0.15); color: var(--mc-primary); }
.status-badge.failed { background: var(--mc-danger-bg); color: var(--mc-danger); }
.status-badge.cancelled { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.status-badge.cancelling { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }

.btn-icon.btn-icon-cancelling { cursor: default; opacity: 0.7; }
.btn-icon.btn-icon-cancelling .spinner { animation: rmp-spin 0.9s linear infinite; }
@keyframes rmp-spin { to { transform: rotate(360deg); } }

/* Process button */
.process-btn { width: 100%; justify-content: center; margin-top: 16px; }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal-content { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 640px; padding: 24px; max-height: 80vh; overflow-y: auto; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-title { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0 0 16px; }

/* Form */
.form-group { margin-bottom: 16px; }
.form-group label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 6px; color: var(--mc-text-secondary); }
.form-input { width: 100%; padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); outline: none; font-family: inherit; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }

.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 16px; }

@media (max-width: 768px) {
  .upload-row,
  .dir-scan-row {
    flex-direction: column;
  }

  .add-text-btn,
  .dir-scan-row > .btn-secondary,
  .process-btn {
    width: 100%;
    justify-content: center;
  }

  .raw-item {
    align-items: flex-start;
    flex-direction: column;
  }

  .raw-item-meta,
  .raw-item-actions {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
