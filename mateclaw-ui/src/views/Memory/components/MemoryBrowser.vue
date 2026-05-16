<template>
  <div class="memory-browser">
    <!-- File selector -->
    <div class="file-nav">
      <button v-for="f in files" :key="f.filename"
        class="file-nav-btn" :class="{ active: currentFile === f.filename }"
        :title="f.filename"
        @click="loadFile(f.filename)">
        <span class="file-nav-icon">{{ fileIcon(f.filename) }}</span>
        <span class="file-nav-name">{{ fileLabel(f.filename) }}</span>
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="browser-loading">
      <div class="skeleton-card" v-for="i in 3" :key="i"><div class="skeleton-line" /><div class="skeleton-line short" /></div>
    </div>

    <!-- Sections -->
    <div v-else-if="sections.length > 0" class="section-list">
      <div v-for="(sec, idx) in sections" :key="idx" class="section-card">
        <div class="section-header">
          <h4 class="section-title">{{ sec.heading }}</h4>
          <div class="section-actions">
            <button v-if="editingIdx !== idx" class="section-btn" @click="startEdit(idx)">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
              </svg>
            </button>
          </div>
        </div>

        <!-- View mode -->
        <div v-if="editingIdx !== idx" class="section-body" v-html="renderMd(sec.body)" />

        <!-- Edit mode -->
        <div v-else class="section-edit">
          <textarea v-model="editText" class="section-textarea" rows="6" />
          <div class="edit-bar">
            <button class="edit-btn cancel" @click="editingIdx = -1">{{ t('memory.hil.cancel') }}</button>
            <button class="edit-btn save" :disabled="saving" @click="saveSection(idx)">
              {{ saving ? '...' : t('memory.hil.save') }}
            </button>
          </div>
        </div>

        <!-- User-edited badge -->
        <div v-if="sec.userEdited" class="edited-badge">
          {{ t('memory.memoryBrowser.userEdited') }}
        </div>
      </div>
    </div>

    <!-- Empty -->
    <div v-else class="browser-empty">
      <div class="empty-icon">📝</div>
      <p>{{ t('memory.memoryBrowser.empty') }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { http, agentContextApi } from '@/api'

const props = defineProps<{ agentId: number }>()
const { t } = useI18n()

interface FileInfo { filename: string; fileSize: number; enabled: boolean }
interface Section { heading: string; body: string; userEdited: boolean; raw: string }

const files = ref<FileInfo[]>([])
const currentFile = ref('')
const sections = ref<Section[]>([])
const loading = ref(false)
const editingIdx = ref(-1)
const editText = ref('')
const saving = ref(false)

watch(() => props.agentId, () => { loadFileList() }, { immediate: true })

async function loadFileList() {
  try {
    const res: any = await agentContextApi.listFiles(props.agentId)
    const allFiles: FileInfo[] = res.data || []
    // Memory-relevant files only: MEMORY.md, PROFILE.md, SOUL.md, structured/*.md
    const filtered = allFiles.filter((f: FileInfo) =>
      ['MEMORY.md', 'PROFILE.md', 'SOUL.md'].includes(f.filename) ||
      f.filename.startsWith('structured/')
    )
    // Order by importance: brain → persona → extracted facts (alpha within group)
    files.value = filtered.sort((a, b) => fileRank(a.filename) - fileRank(b.filename) || a.filename.localeCompare(b.filename))
    if (files.value.some(f => f.filename === 'MEMORY.md')) {
      loadFile('MEMORY.md')
    } else if (files.value.length > 0) {
      loadFile(files.value[0].filename)
    }
  } catch { files.value = [] }
}

async function loadFile(filename: string) {
  currentFile.value = filename
  loading.value = true
  editingIdx.value = -1
  try {
    const res: any = await agentContextApi.getFile(props.agentId, filename)
    const content: string = res.data?.content || ''
    sections.value = parseSections(content)
  } catch { sections.value = [] }
  finally { loading.value = false }
}

function parseSections(content: string): Section[] {
  if (!content.trim()) return []
  const parts = content.split(/(?=^## )/m)
  const result: Section[] = []
  for (const part of parts) {
    const match = part.match(/^## (.+)\n([\s\S]*)/)
    if (match) {
      const heading = match[1].trim()
      const body = match[2].trim()
      const userEdited = body.includes('<!-- user-edited')
      result.push({ heading, body, userEdited, raw: part })
    } else if (part.trim() && result.length === 0) {
      // Content before first ## heading
      result.push({ heading: t('memory.memoryBrowser.header'), body: part.trim(), userEdited: false, raw: part })
    }
  }
  return result
}

function startEdit(idx: number) {
  editingIdx.value = idx
  editText.value = sections.value[idx].body
}

async function saveSection(idx: number) {
  saving.value = true
  try {
    // Use HiL edit endpoint to write back with user-edited metadata
    const heading = sections.value[idx].heading
    await http.post(
      `/memory/${props.agentId}/dream/reports/0/entries/${encodeURIComponent(heading)}/edit`,
      { content: editText.value }
    )
    mcToast.success(t('memory.hil.saved'))
    editingIdx.value = -1
    // Reload file to see changes
    await loadFile(currentFile.value)
  } catch (e: any) {
    mcToast.error(e.message || 'Save failed')
  } finally { saving.value = false }
}

function renderMd(body: string): string {
  // Simple markdown rendering (lists, bold, line breaks)
  return body
    .split('\n')
    .map(line => {
      let html = line
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/`(.+?)`/g, '<code>$1</code>')
      if (html.startsWith('- ')) return `<li>${html.slice(2)}</li>`
      if (html.trim() === '') return ''
      return `<p>${html}</p>`
    })
    .filter(Boolean)
    .join('')
}

function fileIcon(filename: string): string {
  if (filename === 'MEMORY.md') return '🧠'
  if (filename === 'PROFILE.md') return '👤'
  if (filename === 'SOUL.md') return '💫'
  if (filename === 'structured/user.md') return '📋'
  if (filename === 'structured/reference.md') return '🔗'
  if (filename.startsWith('structured/')) return '📋'
  return '📄'
}

// Rank for display order: 0 = primary brain, 1 = persona, 2 = extracted facts.
function fileRank(filename: string): number {
  if (filename === 'MEMORY.md') return 0
  if (filename === 'PROFILE.md' || filename === 'SOUL.md') return 1
  return 2
}

// Friendly label — hides directory paths and the .md extension from the user.
function fileLabel(filename: string): string {
  const key = ({
    'MEMORY.md': 'memory',
    'PROFILE.md': 'profile',
    'SOUL.md': 'soul',
    'structured/user.md': 'userFacts',
    'structured/reference.md': 'references',
  } as Record<string, string>)[filename]
  if (key) {
    const i18nKey = `memory.memoryBrowser.files.${key}`
    const translated = t(i18nKey)
    if (translated !== i18nKey) return translated
  }
  // Fallback: strip path prefix and .md extension.
  const base = filename.includes('/') ? filename.slice(filename.lastIndexOf('/') + 1) : filename
  return base.replace(/\.md$/i, '')
}
</script>

<style scoped>
/* File nav — segmented pills, no file-system noise */
.file-nav {
  display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 18px;
}
.file-nav-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 6px 12px; border: 1px solid transparent; border-radius: 999px;
  background: var(--mc-bg-sunken); font-size: 12.5px; font-weight: 500;
  color: var(--mc-text-secondary); cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.file-nav-btn:hover { color: var(--mc-text-primary); background: var(--mc-bg-elevated); }
.file-nav-btn.active {
  background: var(--mc-primary-bg);
  border-color: var(--mc-primary);
  color: var(--mc-text-primary);
}
.file-nav-icon { font-size: 14px; line-height: 1; }
.file-nav-name { letter-spacing: 0.1px; }

/* Section cards */
.section-list { display: flex; flex-direction: column; gap: 10px; }
.section-card {
  padding: 14px 16px; border-radius: 12px; background: var(--mc-bg-sunken);
  border: 1px solid transparent; position: relative;
}
.section-card:hover { border-color: var(--mc-border-light); }
.section-header { display: flex; align-items: center; justify-content: space-between; }
.section-title { margin: 0; font-size: 13px; font-weight: 600; color: var(--mc-text-primary); }
.section-actions { opacity: 0; transition: opacity 0.15s; }
.section-card:hover .section-actions { opacity: 1; }
.section-btn {
  width: 26px; height: 26px; display: flex; align-items: center; justify-content: center;
  border: none; border-radius: 6px; background: transparent; color: var(--mc-text-tertiary);
  cursor: pointer; transition: all 0.12s;
}
.section-btn:hover { background: var(--mc-bg-elevated); color: var(--mc-primary); }

.section-body {
  margin-top: 8px; font-size: 13px; color: var(--mc-text-secondary); line-height: 1.6;
}
.section-body :deep(li) { margin-left: 16px; margin-bottom: 2px; }
.section-body :deep(code) {
  padding: 1px 4px; border-radius: 3px; background: var(--mc-bg-elevated);
  font-size: 12px; font-family: 'SF Mono', Menlo, monospace;
}
.section-body :deep(strong) { color: var(--mc-text-primary); }
.section-body :deep(p) { margin: 2px 0; }

/* Edit mode */
.section-edit { margin-top: 8px; }
.section-textarea {
  width: 100%; padding: 10px 12px; border: 1px solid var(--mc-border); border-radius: 8px;
  background: var(--mc-bg-elevated); font-size: 13px; color: var(--mc-text-primary);
  font-family: 'SF Mono', Menlo, monospace; resize: vertical; outline: none;
  line-height: 1.5;
}
.section-textarea:focus { border-color: var(--mc-primary); }
.edit-bar { display: flex; justify-content: flex-end; gap: 6px; margin-top: 8px; }
.edit-btn {
  padding: 5px 14px; border-radius: 8px; font-size: 12px; font-weight: 500;
  cursor: pointer; transition: all 0.12s; border: 1px solid var(--mc-border);
}
.edit-btn.cancel { background: transparent; color: var(--mc-text-secondary); }
.edit-btn.cancel:hover { border-color: var(--mc-text-tertiary); }
.edit-btn.save { background: var(--mc-primary); color: #fff; border-color: var(--mc-primary); }
.edit-btn.save:disabled { opacity: 0.4; cursor: not-allowed; }

/* User-edited badge */
.edited-badge {
  position: absolute; top: 8px; right: 10px;
  font-size: 10px; font-weight: 500; color: var(--mc-primary);
  background: var(--mc-primary-bg); padding: 2px 6px; border-radius: 4px;
}

/* States */
.browser-loading { padding: 8px 0; }
.skeleton-card { padding: 14px; margin-bottom: 8px; }
.skeleton-line { height: 10px; border-radius: 4px; background: var(--mc-border-light); margin-bottom: 8px; }
.skeleton-line.short { width: 60%; }
.browser-empty { display: flex; flex-direction: column; align-items: center; padding: 40px 0; color: var(--mc-text-tertiary); }
.empty-icon { font-size: 28px; margin-bottom: 8px; }
</style>
