<template>
  <div class="mc-page-shell agent-context-shell">
    <div class="mc-page-frame agent-context-frame">
      <div class="mc-page-inner agent-context-container">
    <!-- 顶部栏 -->
    <div class="workspace-header workspace-header--compact">
      <h1 class="page-title page-title--compact">{{ t('agentContext.title') }}</h1>
      <div class="header-actions mc-surface-card">
        <AgentPickerDialog v-model="selectedAgentId" :agents="agents" />
      </div>
    </div>

    <!-- 无 Agent 提示 -->
    <div v-if="!selectedAgentId" class="empty-state">
      <div class="empty-icon">📂</div>
      <h3>{{ t('agentContext.noAgent') }}</h3>
    </div>

    <!-- 主体：文件列表 + 编辑器 -->
    <div v-else class="workspace-body">
      <!-- 左侧文件列表 -->
      <div class="file-list-panel">
        <div class="panel-card">
          <div class="panel-header">
            <h3 class="section-title">{{ t('agentContext.coreFiles') }}</h3>
            <div class="panel-actions">
              <button class="icon-btn" @click="showNewFileDialog = true" :title="t('agentContext.newFile')">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
                </svg>
              </button>
              <button
                class="icon-btn"
                :disabled="!selectedAgentId || exportInFlight"
                @click="exportMemorySnapshot"
                :title="t('agentContext.exportSnapshot')"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                  <polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
                </svg>
              </button>
              <button
                class="icon-btn"
                :disabled="!selectedAgentId || importInFlight"
                @click="onImportClick"
                :title="t('agentContext.importSnapshot')"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                  <polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>
                </svg>
              </button>
              <button class="icon-btn" @click="fetchFiles" :title="t('common.reset')">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
                </svg>
              </button>
            </div>
            <input
              ref="importFileInput"
              type="file"
              accept=".zip,application/zip"
              class="visually-hidden"
              @change="onImportFilePicked"
            />
          </div>
          <p class="info-text">{{ t('agentContext.coreFilesDesc') }}</p>
          <div class="divider"></div>

          <div class="file-scroll">
            <div v-if="sortedFiles.length === 0" class="empty-files">
              {{ t('agentContext.noFiles') }}
            </div>
            <div
              v-for="file in sortedFiles"
              :key="file.filename"
              class="file-item"
              :class="{ selected: selectedFile?.filename === file.filename }"
              @click="onFileClick(file)"
            >
              <div class="file-item-main">
                <div class="file-item-info">
                  <span class="file-icon">📄</span>
                  <span class="file-name">{{ file.filename }}</span>
                </div>
                <div class="file-item-meta">
                  <span class="file-size">{{ formatSize(file.fileSize) }}</span>
                  <label class="toggle-switch" @click.stop>
                    <input type="checkbox" :checked="file.enabled" @change="toggleFileEnabled(file)" />
                    <span class="toggle-slider"></span>
                  </label>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧编辑器 -->
      <div class="file-editor-panel">
        <div class="panel-card editor-card">
          <template v-if="selectedFile">
            <div class="editor-header">
              <div class="editor-file-info">
                <div class="editor-filename">{{ selectedFile.filename }}</div>
                <div class="editor-meta">{{ formatSize(selectedFile.fileSize) }} · {{ formatTime(selectedFile.updateTime) }}</div>
              </div>
              <div class="editor-actions">
                <button class="btn-sm" @click="resetContent" :disabled="!hasChanges">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>
                  </svg>
                  {{ t('common.reset') }}
                </button>
                <button class="btn-sm danger" @click="confirmDeleteFile">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                  </svg>
                  {{ t('common.delete') }}
                </button>
                <button class="btn-sm primary" @click="saveContent" :disabled="!hasChanges || saving">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/>
                    <polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/>
                  </svg>
                  {{ t('common.save') }}
                </button>
              </div>
            </div>

            <div class="editor-toolbar">
              <div class="preview-modes">
                <button
                  class="preview-mode-btn"
                  :class="{ active: previewMode === 'off' }"
                  @click="previewMode = 'off'"
                  :title="t('agentContext.editOnly') || 'Edit'"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                  </svg>
                </button>
                <button
                  class="preview-mode-btn"
                  :class="{ active: previewMode === 'split' }"
                  @click="previewMode = 'split'"
                  :title="t('agentContext.splitView') || 'Split'"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="3" y="3" width="18" height="18" rx="2"/><line x1="12" y1="3" x2="12" y2="21"/>
                  </svg>
                </button>
                <button
                  class="preview-mode-btn"
                  :class="{ active: previewMode === 'preview' }"
                  @click="previewMode = 'preview'"
                  :title="t('agentContext.preview') || 'Preview'"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>
                  </svg>
                </button>
              </div>
              <span v-if="hasChanges" class="change-badge">{{ t('agentContext.modified') }}</span>
            </div>

            <div class="editor-content" :class="'mode-' + previewMode">
              <textarea
                v-if="previewMode !== 'preview'"
                v-model="fileContent"
                class="editor-textarea"
                :placeholder="t('agentContext.fileContent')"
                spellcheck="false"
              ></textarea>
              <div
                v-if="previewMode !== 'off'"
                class="markdown-preview markdown-body"
                v-html="renderedMarkdown"
                @click="handlePreviewClick"
              ></div>
            </div>
          </template>

          <div v-else class="empty-editor">
            {{ t('agentContext.selectFile') }}
          </div>
        </div>
      </div>
    </div>

    <!-- Import preview dialog (glass) -->
    <Transition name="mc-glass-fade">
      <div v-if="showImportDialog" class="mc-glass-overlay" @click.self="closeImportDialog">
        <div class="mc-glass-panel import-panel" role="dialog" aria-modal="true">
          <div class="mc-glass-head">
            <div class="mc-glass-head-text">
              <h2>{{ t('agentContext.importSnapshotTitle') }}</h2>
              <p v-if="importFileName" class="mc-glass-subtitle">{{ importFileName }}</p>
            </div>
            <button class="mc-glass-close" @click="closeImportDialog" :aria-label="t('common.cancel')">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>

          <div class="mc-glass-body">
            <div v-if="importLoading" class="import-loading">
              <span class="import-spinner"></span>
              <span>{{ importLoadingMessage }}</span>
            </div>

            <template v-else-if="importPreview">
              <!-- Will create -->
              <section class="diff-section">
                <header class="diff-section-head">
                  <span class="diff-pill diff-pill--create">●</span>
                  <span class="diff-section-label">{{ t('agentContext.importPreviewWillCreate') }}</span>
                  <span class="diff-section-count">{{ importPreview.willCreate.length }}</span>
                </header>
                <ul v-if="importPreview.willCreate.length" class="diff-list">
                  <li v-for="name in importPreview.willCreate" :key="'c-' + name" class="diff-row diff-row--create">
                    <span class="diff-row-icon">＋</span>
                    <span class="diff-row-name">{{ name }}</span>
                  </li>
                </ul>
                <p v-else class="diff-empty">{{ t('agentContext.importPreviewCreateNone') }}</p>
              </section>

              <!-- Will update -->
              <section class="diff-section">
                <header class="diff-section-head">
                  <span class="diff-pill diff-pill--update">●</span>
                  <span class="diff-section-label">{{ t('agentContext.importPreviewWillUpdate') }}</span>
                  <span class="diff-section-count">{{ importPreview.willUpdate.length }}</span>
                </header>
                <ul v-if="importPreview.willUpdate.length" class="diff-list">
                  <li v-for="diff in importPreview.willUpdate" :key="'u-' + diff.filename" class="diff-row diff-row--update">
                    <span class="diff-row-icon">⟳</span>
                    <div class="diff-row-body">
                      <span class="diff-row-name">{{ diff.filename }}</span>
                      <span class="diff-row-meta">{{ t('agentContext.importPreviewUpdateMeta', { old: diff.oldSize, new: diff.newSize }) }}</span>
                    </div>
                  </li>
                </ul>
                <p v-else class="diff-empty">{{ t('agentContext.importPreviewCreateNone') }}</p>
              </section>

              <!-- Will skip -->
              <section v-if="importPreview.willSkip.length" class="diff-section">
                <header class="diff-section-head">
                  <span class="diff-pill diff-pill--skip">●</span>
                  <span class="diff-section-label">{{ t('agentContext.importPreviewWillSkip') }}</span>
                  <span class="diff-section-count">{{ importPreview.willSkip.length }}</span>
                </header>
                <ul class="diff-list">
                  <li v-for="skip in importPreview.willSkip" :key="'s-' + skip.filename" class="diff-row diff-row--skip">
                    <span class="diff-row-icon">−</span>
                    <div class="diff-row-body">
                      <span class="diff-row-name">{{ skip.filename }}</span>
                      <span class="diff-row-meta">{{ t('agentContext.importPreviewSkipReason', { reason: skip.reason }) }}</span>
                    </div>
                  </li>
                </ul>
              </section>

              <p v-if="!hasAnyChange" class="diff-empty diff-empty--all">{{ t('agentContext.importPreviewEmpty') }}</p>
              <p v-else class="import-confirm-hint">{{ t('agentContext.importConfirmHint') }}</p>
            </template>
          </div>

          <div class="mc-glass-foot">
            <button class="mc-glass-btn mc-glass-btn--ghost" @click="closeImportDialog">{{ t('common.cancel') }}</button>
            <button
              class="mc-glass-btn mc-glass-btn--primary"
              :disabled="!canApplyImport"
              @click="applyImport"
            >{{ t('agentContext.importApply') }}</button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- 新建文件弹窗 -->
    <div v-if="showNewFileDialog" class="modal-overlay">
      <div class="modal small-modal">
        <div class="modal-header">
          <h2>{{ t('agentContext.newFileTitle') }}</h2>
          <button class="modal-close" @click="showNewFileDialog = false">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">{{ t('agentContext.newFileTitle') }}</label>
            <input
              v-model="newFilename"
              class="form-input"
              :placeholder="t('agentContext.newFilePlaceholder')"
              @keyup.enter="createNewFile"
            />
            <p class="field-hint">{{ t('agentContext.newFileHint') }}</p>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="showNewFileDialog = false">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="createNewFile" :disabled="!isValidFilename">{{ t('common.create') }}</button>
        </div>
      </div>
    </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import { agentApi, agentContextApi } from '@/api/index'
import { copyToClipboard } from '@/utils/clipboard'
import type { Agent, WorkspaceFile } from '@/types/index'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import { handleMermaidDownload } from '@/composables/useMermaidRenderer'
import AgentPickerDialog from '@/components/common/AgentPickerDialog.vue'

const { renderMarkdown } = useMarkdownRenderer()

const { t } = useI18n()

// Agent 选择
const agents = ref<Agent[]>([])
const selectedAgentId = ref<string | number>('')

// 文件列表
const files = ref<WorkspaceFile[]>([])
const enabledFiles = ref<string[]>([])

// 编辑器状态
const selectedFile = ref<WorkspaceFile | null>(null)
const fileContent = ref('')
const originalContent = ref('')
const saving = ref(false)
// 'off' = editor only, 'split' = side-by-side, 'preview' = preview only
const previewMode = ref<'off' | 'split' | 'preview'>('off')

// 新建文件
const showNewFileDialog = ref(false)
const newFilename = ref('')

// 记忆快照导出 / 导入
interface FileDiff {
  filename: string
  oldSize: number
  newSize: number
  oldHash: string
  newHash: string
}
interface SkipEntry {
  filename: string
  reason: string
}
interface ImportPreview {
  willCreate: string[]
  willUpdate: FileDiff[]
  willSkip: SkipEntry[]
}
const importFileInput = ref<HTMLInputElement | null>(null)
const showImportDialog = ref(false)
const importPreview = ref<ImportPreview | null>(null)
const importPendingFile = ref<File | null>(null)
const importLoading = ref(false)
const importLoadingMessage = ref('')
const exportInFlight = ref(false)
const importInFlight = ref(false)
const importFileName = computed(() => importPendingFile.value?.name ?? '')
const hasAnyChange = computed(() => {
  const p = importPreview.value
  if (!p) return false
  return p.willCreate.length > 0 || p.willUpdate.length > 0
})
const canApplyImport = computed(() => !importLoading.value && !!importPreview.value && hasAnyChange.value)

const hasChanges = computed(() => fileContent.value !== originalContent.value)

const isValidFilename = computed(() => {
  const name = newFilename.value.trim()
  if (!name || !name.endsWith('.md')) return false
  if (files.value.some(f => f.filename === name)) return false
  return /^[a-zA-Z0-9_\-. ]+\.md$/.test(name)
})

const sortedFiles = computed(() => {
  const enabled = enabledFiles.value
  return [...files.value].sort((a, b) => {
    const aIdx = enabled.indexOf(a.filename)
    const bIdx = enabled.indexOf(b.filename)
    const aEnabled = aIdx !== -1
    const bEnabled = bIdx !== -1
    if (aEnabled && bEnabled) return aIdx - bIdx
    if (aEnabled) return -1
    if (bEnabled) return 1
    return a.filename.localeCompare(b.filename)
  })
})

const renderedMarkdown = computed(() => renderMarkdown(fileContent.value || ''))

const route = useRoute()

onMounted(async () => {
  await loadAgents()
  // Hydrate from query param (e.g. from Agents page "Context" tab link)
  const qAgentId = route.query.agentId
  if (qAgentId && agents.value.some(a => String(a.id) === String(qAgentId))) {
    selectedAgentId.value = qAgentId as string
  }
})

watch(selectedAgentId, () => {
  if (selectedAgentId.value) {
    selectedFile.value = null
    fileContent.value = ''
    originalContent.value = ''
    fetchFiles()
    fetchPromptFiles()
  }
})

async function loadAgents() {
  try {
    const res: any = await agentApi.list()
    agents.value = res.data || []
    // 自动选中第一个
    if (agents.value.length > 0 && !selectedAgentId.value) {
      selectedAgentId.value = agents.value[0].id
    }
  } catch {
    mcToast.error(t('agentContext.loadFailed'))
  }
}

async function fetchFiles() {
  if (!selectedAgentId.value) return
  try {
    const res: any = await agentContextApi.listFiles(selectedAgentId.value)
    files.value = res.data || []
  } catch {
    mcToast.error(t('agentContext.loadFailed'))
  }
}

async function fetchPromptFiles() {
  if (!selectedAgentId.value) return
  try {
    const res: any = await agentContextApi.getPromptFiles(selectedAgentId.value)
    enabledFiles.value = res.data || []
  } catch {
    enabledFiles.value = []
  }
}

function handlePreviewClick(e: MouseEvent) {
  if (handleMermaidDownload(e)) return
  const btn = (e.target as HTMLElement).closest('.code-block__copy') as HTMLElement | null
  if (!btn) return
  // Same as ChatConsole: the copy button now lives inside <details><summary>
  // for collapsible blocks. Without preventDefault the click toggles the
  // details open state in addition to copying.
  e.preventDefault()
  e.stopPropagation()
  const encoded = btn.getAttribute('data-code')
  if (!encoded) return
  const code = decodeURIComponent(encoded)
  copyToClipboard(code).then(() => {
    btn.classList.add('copied')
    const textEl = btn.querySelector('.code-block__copy-text')
    if (textEl) textEl.textContent = t('chat.copied') || 'Copied'
    setTimeout(() => {
      btn.classList.remove('copied')
      if (textEl) textEl.textContent = t('chat.copy') || 'Copy'
    }, 1500)
  })
}

async function onFileClick(file: WorkspaceFile) {
  selectedFile.value = file
  try {
    const res: any = await agentContextApi.getFile(selectedAgentId.value, file.filename)
    const data = res.data
    fileContent.value = data?.content || ''
    originalContent.value = fileContent.value
  } catch {
    mcToast.error(t('agentContext.loadFileFailed'))
  }
}

async function saveContent() {
  if (!selectedFile.value || !selectedAgentId.value) return
  saving.value = true
  try {
    await agentContextApi.saveFile(selectedAgentId.value, selectedFile.value.filename, fileContent.value)
    originalContent.value = fileContent.value
    mcToast.success(t('agentContext.saveSuccess'))
    await fetchFiles()
  } catch {
    mcToast.error(t('agentContext.saveFailed'))
  } finally {
    saving.value = false
  }
}

function resetContent() {
  fileContent.value = originalContent.value
}

async function toggleFileEnabled(file: WorkspaceFile) {
  const isEnabling = !enabledFiles.value.includes(file.filename)
  const newList = isEnabling
    ? [...enabledFiles.value, file.filename]
    : enabledFiles.value.filter(f => f !== file.filename)

  try {
    await agentContextApi.setPromptFiles(selectedAgentId.value, newList)
    enabledFiles.value = newList
    // 同步本地 file 状态
    const f = files.value.find(x => x.filename === file.filename)
    if (f) f.enabled = isEnabling
    mcToast.success(t('agentContext.promptUpdated'))
  } catch {
    mcToast.error(t('agentContext.promptUpdateFailed'))
  }
}

async function confirmDeleteFile() {
  if (!selectedFile.value) return
  const name = selectedFile.value.filename
  const ok = await mcConfirm({
    title: t('common.delete'),
    message: t('agentContext.deleteConfirm', { name }),
    tone: 'danger',
  })
  if (!ok) return

  try {
    await agentContextApi.deleteFile(selectedAgentId.value, name)
    mcToast.success(t('agentContext.deleteSuccess'))
    selectedFile.value = null
    fileContent.value = ''
    originalContent.value = ''
    await fetchFiles()
    await fetchPromptFiles()
  } catch {
    mcToast.error(t('agentContext.deleteFailed'))
  }
}

async function createNewFile() {
  const name = newFilename.value.trim()
  if (!isValidFilename.value) {
    mcToast.warning(t('agentContext.invalidFilename'))
    return
  }
  try {
    await agentContextApi.saveFile(selectedAgentId.value, name, '')
    showNewFileDialog.value = false
    newFilename.value = ''
    await fetchFiles()
    // 自动选中新建的文件
    const newFile = files.value.find(f => f.filename === name)
    if (newFile) {
      onFileClick(newFile)
    }
  } catch {
    mcToast.error(t('agentContext.saveFailed'))
  }
}

// ---- memory snapshot: export ----
async function exportMemorySnapshot() {
  if (!selectedAgentId.value || exportInFlight.value) return
  exportInFlight.value = true
  try {
    const blob = await agentContextApi.exportMemorySnapshot(selectedAgentId.value)
    // Build a timestamped filename so successive backups don't collide on disk.
    // Pattern: memory-agent-<id>-YYYYMMDD-HHmm.zip
    const stamp = formatStamp(new Date())
    const filename = `memory-agent-${selectedAgentId.value}-${stamp}.zip`
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    mcToast.success(t('agentContext.exportSuccess'))
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : ''
    mcToast.error(msg ? `${t('agentContext.exportFailed')}: ${msg}` : t('agentContext.exportFailed'))
  } finally {
    exportInFlight.value = false
  }
}

// ---- memory snapshot: import ----
function onImportClick() {
  if (!selectedAgentId.value || importInFlight.value) return
  // Reset the input so picking the same file twice still fires `change`.
  if (importFileInput.value) importFileInput.value.value = ''
  importFileInput.value?.click()
}

async function onImportFilePicked(event: Event) {
  const target = event.target as HTMLInputElement | null
  const file = target?.files?.[0]
  if (!file) return
  importPendingFile.value = file
  importPreview.value = null
  showImportDialog.value = true
  importLoading.value = true
  importLoadingMessage.value = t('agentContext.importPreviewing')
  try {
    const res: any = await agentContextApi.previewImportMemorySnapshot(selectedAgentId.value, file)
    importPreview.value = (res?.data ?? null) as ImportPreview | null
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : ''
    mcToast.error(msg ? `${t('agentContext.importPreviewFailed')}: ${msg}` : t('agentContext.importPreviewFailed'))
    closeImportDialog()
  } finally {
    importLoading.value = false
  }
}

function closeImportDialog() {
  showImportDialog.value = false
  importPreview.value = null
  importPendingFile.value = null
}

async function applyImport() {
  if (!canApplyImport.value || !importPendingFile.value || !selectedAgentId.value) return
  importLoading.value = true
  importLoadingMessage.value = t('agentContext.importApplying')
  importInFlight.value = true
  try {
    const res: any = await agentContextApi.applyImportMemorySnapshot(selectedAgentId.value, importPendingFile.value)
    const applied = Number(res?.data?.applied ?? 0)
    const skipped = Number(res?.data?.skipped ?? 0)
    mcToast.success(t('agentContext.importApplied', { applied, skipped }))
    closeImportDialog()
    // Re-fetch so the file list and any edited file reflect overwrites.
    await fetchFiles()
    await fetchPromptFiles()
    if (selectedFile.value) {
      const stillThere = files.value.find(f => f.filename === selectedFile.value?.filename)
      if (stillThere) {
        await onFileClick(stillThere)
      } else {
        selectedFile.value = null
        fileContent.value = ''
        originalContent.value = ''
      }
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : ''
    mcToast.error(msg ? `${t('agentContext.importFailed')}: ${msg}` : t('agentContext.importFailed'))
  } finally {
    importLoading.value = false
    importInFlight.value = false
  }
}

function formatStamp(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}`
}

function formatSize(bytes: number): string {
  if (!bytes || bytes === 0) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function formatTime(time?: string): string {
  if (!time) return ''
  try {
    return new Date(time).toLocaleString()
  } catch {
    return time
  }
}
</script>

<style scoped>
.agent-context-shell {
  background: transparent;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.agent-context-frame {
  height: min(calc(100vh - 28px), 100%);
  min-height: 0;
  overflow: hidden;
}

.agent-context-container {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.workspace-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding-bottom: 14px; flex-shrink: 0; border-bottom: 1px solid var(--mc-border-light); }
.workspace-header--compact { align-items: center; padding-bottom: 10px; }
.workspace-header-copy { min-width: 0; }
.page-title { font-size: clamp(28px, 3vw, 40px); font-weight: 800; color: var(--mc-text-primary); letter-spacing: -0.04em; margin: 0 0 8px; }
.page-title--compact { font-size: clamp(24px, 2.5vw, 32px); font-weight: 800; letter-spacing: -0.03em; margin: 0; }
.page-desc { font-size: 15px; color: var(--mc-text-secondary); margin: 0; line-height: 1.7; max-width: 720px; }
.header-actions { display: flex; gap: 8px; align-items: center; padding: 12px; border-radius: 18px; flex-shrink: 0; }

/* 空状态 */
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; flex: 1; text-align: center; }
.empty-icon { font-size: 48px; margin-bottom: 16px; }
.empty-state h3 { font-size: 16px; color: var(--mc-text-secondary); margin: 0; }

/* 主体 */
.workspace-body { display: flex; flex: 1; overflow: hidden; padding-top: 18px; gap: 16px; min-height: 0; }

/* 左侧面板 */
.file-list-panel { width: 300px; min-width: 260px; display: flex; flex-direction: column; }
.panel-card { background: linear-gradient(180deg, var(--mc-bg-elevated), var(--mc-bg-muted)); border: 1px solid var(--mc-border); border-radius: 22px; display: flex; flex-direction: column; flex: 1; overflow: hidden; box-shadow: var(--mc-shadow-soft); }
.panel-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px 0; }
.section-title { font-size: 14px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.panel-actions { display: flex; gap: 4px; }
.icon-btn { width: 30px; height: 30px; border: 1px solid var(--mc-border); background: var(--mc-bg-elevated); border-radius: 8px; cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--mc-text-secondary); transition: all 0.15s; }
.icon-btn:hover:not(:disabled) { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.icon-btn:disabled { opacity: 0.45; cursor: not-allowed; }
.info-text { font-size: 12px; color: var(--mc-text-tertiary); padding: 6px 16px 0; margin: 0; line-height: 1.4; }
.divider { height: 1px; background: var(--mc-border-light); margin: 10px 16px; }

.file-scroll { flex: 1; overflow-y: auto; padding: 0 8px 8px; }
.file-scroll::-webkit-scrollbar { width: 4px; }
.file-scroll::-webkit-scrollbar-thumb { background: var(--mc-border); border-radius: 2px; }

.empty-files { padding: 24px 8px; text-align: center; color: var(--mc-text-tertiary); font-size: 13px; }

.file-item { padding: 8px 10px; border-radius: 8px; cursor: pointer; transition: all 0.15s; margin-bottom: 2px; }
.file-item:hover { background: var(--mc-bg-sunken); }
.file-item.selected { background: var(--mc-primary-bg); border: 1px solid var(--mc-primary-light); }
.file-item-main { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.file-item-info { display: flex; align-items: center; gap: 6px; min-width: 0; flex: 1; }
.file-icon { font-size: 14px; flex-shrink: 0; }
.file-name { font-size: 13px; color: var(--mc-text-primary); font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.file-item-meta { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.file-size { font-size: 11px; color: var(--mc-text-tertiary); }

/* Toggle */
.toggle-switch { position: relative; display: inline-block; width: 32px; height: 18px; cursor: pointer; flex-shrink: 0; }
.toggle-switch.small { width: 28px; height: 16px; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--mc-border); border-radius: 18px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 12px; height: 12px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch.small .toggle-slider::before { width: 10px; height: 10px; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(14px); }
.toggle-switch.small input:checked + .toggle-slider::before { transform: translateX(12px); }

/* 右侧编辑器 */
.file-editor-panel { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.editor-card { display: flex; flex-direction: column; flex: 1; overflow: hidden; }

.editor-header { display: flex; align-items: flex-start; justify-content: space-between; padding: 14px 16px; gap: 12px; flex-shrink: 0; }
.editor-file-info { min-width: 0; }
.editor-filename { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); }
.editor-meta { font-size: 12px; color: var(--mc-text-tertiary); margin-top: 2px; }
.editor-actions { display: flex; gap: 6px; flex-shrink: 0; }
.btn-sm { display: flex; align-items: center; gap: 4px; padding: 5px 10px; border: 1px solid var(--mc-border); background: var(--mc-bg-elevated); border-radius: 6px; font-size: 12px; color: var(--mc-text-primary); cursor: pointer; transition: all 0.15s; }
.btn-sm:hover { background: var(--mc-bg-sunken); }
.btn-sm:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-sm.primary { background: var(--mc-primary); color: white; border-color: var(--mc-primary); }
.btn-sm.primary:hover:not(:disabled) { background: var(--mc-primary-hover); }
.btn-sm.danger:hover:not(:disabled) { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }

.editor-toolbar { display: flex; align-items: center; gap: 8px; padding: 0 16px 8px; flex-shrink: 0; }
.preview-modes { display: flex; gap: 2px; background: var(--mc-bg-sunken); border-radius: 6px; padding: 2px; }
.preview-mode-btn { width: 30px; height: 26px; border: none; background: transparent; border-radius: 4px; cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--mc-text-tertiary); transition: all 0.15s; }
.preview-mode-btn:hover { color: var(--mc-text-primary); }
.preview-mode-btn.active { background: var(--mc-bg-elevated); color: var(--mc-primary); box-shadow: 0 1px 2px rgba(0,0,0,0.06); }
.change-badge { font-size: 11px; background: rgba(245, 158, 11, 0.15); color: #f59e0b; padding: 2px 8px; border-radius: 10px; margin-left: auto; }

.editor-content { flex: 1; overflow: hidden; display: flex; flex-direction: row; gap: 0; padding: 0 16px 16px; }
.editor-content.mode-off .editor-textarea { flex: 1; }
.editor-content.mode-preview .markdown-preview { flex: 1; }
.editor-content.mode-split { gap: 8px; }
.editor-content.mode-split .editor-textarea { flex: 1; min-width: 0; }
.editor-content.mode-split .markdown-preview { flex: 1; min-width: 0; }

.editor-textarea { flex: 1; width: 100%; resize: none; border: 1px solid var(--mc-border); border-radius: 14px; padding: 14px; font-family: 'SF Mono', Monaco, Consolas, 'Courier New', monospace; font-size: 13px; line-height: 1.6; color: var(--mc-text-primary); background: var(--mc-bg-sunken); outline: none; tab-size: 2; }
.editor-textarea:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.1); }
.markdown-preview { flex: 1; overflow-y: auto; padding: 16px; border: 1px solid var(--mc-border); border-radius: 14px; background: var(--mc-bg-sunken); font-size: 14px; line-height: 1.7; color: var(--mc-text-primary); }
.markdown-preview::-webkit-scrollbar { width: 4px; }
.markdown-preview::-webkit-scrollbar-thumb { background: var(--mc-border); border-radius: 2px; }

.empty-editor { display: flex; align-items: center; justify-content: center; flex: 1; color: var(--mc-text-tertiary); font-size: 14px; }

/* ===== Memory snapshot — glass import dialog ===== */
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }

.mc-glass-overlay {
  position: fixed; inset: 0;
  background: rgba(20, 14, 10, 0.32);
  backdrop-filter: blur(8px) saturate(140%);
  -webkit-backdrop-filter: blur(8px) saturate(140%);
  z-index: 2000;
  display: flex; align-items: center; justify-content: center; padding: 20px;
}
:global(html.dark) .mc-glass-overlay { background: rgba(0, 0, 0, 0.55); }

.mc-glass-panel {
  width: 100%; max-width: 560px;
  max-height: 80vh;
  display: flex; flex-direction: column;
  background: rgba(255, 250, 245, 0.88);
  backdrop-filter: blur(48px) saturate(180%);
  -webkit-backdrop-filter: blur(48px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: 18px;
  box-shadow: 0 24px 60px rgba(25, 14, 8, 0.22), 0 2px 6px rgba(25, 14, 8, 0.08);
  animation: mc-glass-pop 0.28s cubic-bezier(0.32, 0.72, 0, 1.2);
}
:global(html.dark) .mc-glass-panel {
  background: rgba(32, 26, 22, 0.88);
  border-color: rgba(255, 255, 255, 0.10);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.6), 0 2px 6px rgba(0, 0, 0, 0.4);
}
@keyframes mc-glass-pop {
  from { opacity: 0; transform: scale(0.94) translateY(6px); }
  to   { opacity: 1; transform: scale(1) translateY(0); }
}

.mc-glass-fade-enter-active, .mc-glass-fade-leave-active { transition: opacity 0.18s ease; }
.mc-glass-fade-enter-from, .mc-glass-fade-leave-to { opacity: 0; }

.mc-glass-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 12px;
  padding: 20px 24px 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.4);
}
:global(html.dark) .mc-glass-head { border-bottom-color: rgba(255, 255, 255, 0.06); }
.mc-glass-head-text h2 { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); margin: 0; line-height: 1.4; }
.mc-glass-subtitle { font-size: 12px; color: var(--mc-text-tertiary); margin: 4px 0 0; word-break: break-all; }
.mc-glass-close {
  width: 32px; height: 32px; border: none; background: rgba(255, 255, 255, 0.35);
  cursor: pointer; color: var(--mc-text-tertiary);
  display: flex; align-items: center; justify-content: center;
  border-radius: 10px; flex-shrink: 0;
  transition: background 0.18s ease, color 0.18s ease;
}
.mc-glass-close:hover { background: rgba(255, 255, 255, 0.6); color: var(--mc-text-primary); }
:global(html.dark) .mc-glass-close { background: rgba(255, 255, 255, 0.05); }
:global(html.dark) .mc-glass-close:hover { background: rgba(255, 255, 255, 0.12); color: var(--mc-text-primary); }

.mc-glass-body {
  padding: 16px 24px;
  overflow-y: auto;
  flex: 1;
  min-height: 0;
}

.mc-glass-foot {
  display: flex; justify-content: flex-end; gap: 10px;
  padding: 14px 24px 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.4);
}
:global(html.dark) .mc-glass-foot { border-top-color: rgba(255, 255, 255, 0.06); }

.mc-glass-btn {
  padding: 8px 16px; font-size: 14px; font-weight: 500;
  border-radius: 10px; cursor: pointer;
  transition: background 0.18s ease, transform 0.12s ease;
}
.mc-glass-btn:disabled { cursor: not-allowed; opacity: 0.55; }
.mc-glass-btn--ghost {
  background: rgba(255, 255, 255, 0.4);
  border: 1px solid rgba(255, 255, 255, 0.6);
  color: var(--mc-text-primary);
}
.mc-glass-btn--ghost:hover:not(:disabled) { background: rgba(255, 255, 255, 0.65); }
:global(html.dark) .mc-glass-btn--ghost {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.10);
}
:global(html.dark) .mc-glass-btn--ghost:hover:not(:disabled) { background: rgba(255, 255, 255, 0.12); }
.mc-glass-btn--primary {
  background: var(--mc-primary); color: white;
  border: 1px solid var(--mc-primary);
  box-shadow: 0 4px 14px rgba(217, 119, 87, 0.32);
}
.mc-glass-btn--primary:hover:not(:disabled) { background: var(--mc-primary-hover); }

/* Loading state */
.import-loading {
  display: flex; align-items: center; gap: 12px;
  padding: 20px 4px; color: var(--mc-text-secondary); font-size: 14px;
}
.import-spinner {
  width: 18px; height: 18px;
  border: 2px solid rgba(217, 119, 87, 0.25);
  border-top-color: var(--mc-primary);
  border-radius: 50%;
  animation: mc-import-spin 0.7s linear infinite;
}
@keyframes mc-import-spin { to { transform: rotate(360deg); } }

/* Diff sections */
.diff-section {
  margin-bottom: 14px;
  padding: 12px 14px;
  background: rgba(255, 255, 255, 0.45);
  border: 1px solid rgba(255, 255, 255, 0.55);
  border-radius: 12px;
}
:global(html.dark) .diff-section {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(255, 255, 255, 0.08);
}
.diff-section-head {
  display: flex; align-items: center; gap: 8px;
  font-size: 12px; font-weight: 600; color: var(--mc-text-secondary);
  text-transform: uppercase; letter-spacing: 0.04em;
  margin-bottom: 8px;
}
.diff-section-count {
  margin-left: auto;
  font-size: 11px; font-weight: 600; color: var(--mc-text-tertiary);
  background: rgba(0, 0, 0, 0.06); padding: 2px 7px; border-radius: 9px;
  letter-spacing: 0;
}
:global(html.dark) .diff-section-count { background: rgba(255, 255, 255, 0.08); }
.diff-pill { font-size: 18px; line-height: 1; }
.diff-pill--create { color: #16a34a; }
.diff-pill--update { color: #f59e0b; }
.diff-pill--skip   { color: #94a3b8; }

.diff-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.diff-row {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--mc-text-primary);
}
.diff-row-icon {
  flex-shrink: 0;
  font-family: 'SF Mono', Monaco, Consolas, monospace;
  font-size: 13px; font-weight: 700; line-height: 20px;
  width: 18px; text-align: center;
}
.diff-row-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; }
.diff-row-name { font-weight: 500; word-break: break-all; }
.diff-row-meta { font-size: 11px; color: var(--mc-text-tertiary); }

.diff-row--create { background: rgba(22, 163, 74, 0.08); }
.diff-row--create .diff-row-icon { color: #16a34a; }
.diff-row--update { background: rgba(245, 158, 11, 0.10); }
.diff-row--update .diff-row-icon { color: #f59e0b; }
.diff-row--skip   { background: rgba(148, 163, 184, 0.10); }
.diff-row--skip .diff-row-icon { color: #94a3b8; }

.diff-empty { font-size: 12px; color: var(--mc-text-tertiary); margin: 0; padding: 4px 2px; }
.diff-empty--all { padding: 24px 0; text-align: center; font-size: 13px; }

.import-confirm-hint {
  margin: 8px 2px 0;
  padding: 8px 12px;
  font-size: 12px; color: var(--mc-text-secondary);
  background: rgba(217, 119, 87, 0.10);
  border-radius: 8px;
  border-left: 3px solid var(--mc-primary);
}
:global(html.dark) .import-confirm-hint { background: rgba(217, 119, 87, 0.16); }

@media (max-width: 600px) {
  .mc-glass-panel { max-width: 100%; max-height: 90vh; }
  .mc-glass-head, .mc-glass-body, .mc-glass-foot { padding-left: 18px; padding-right: 18px; }
}

/* 弹窗 */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 420px; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal.small-modal { max-width: 420px; }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.modal-body { padding: 20px 24px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px; border-top: 1px solid var(--mc-border-light); }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-label { font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); }
.form-input { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; background: var(--mc-bg-sunken); }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.field-hint { font-size: 12px; color: var(--mc-text-tertiary); margin: 0; }
.btn-primary { padding: 8px 16px; background: var(--mc-primary); color: white; border: none; border-radius: 8px; font-size: 14px; font-weight: 500; cursor: pointer; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

@media (max-width: 900px) {
  .agent-context-frame {
    height: auto;
    min-height: calc(100vh - 28px);
    overflow: visible;
  }

  .workspace-header,
  .workspace-body {
    flex-direction: column;
  }

  .header-actions,
  .file-list-panel {
    width: 100%;
    min-width: 0;
  }

  .header-actions :deep(.mc-agent-picker),
  .header-actions :deep(.mc-agent-picker__trigger) {
    width: 100%;
    min-width: 0;
  }
}
</style>
