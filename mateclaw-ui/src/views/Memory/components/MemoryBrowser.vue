<template>
  <div class="memory-browser">
    <!-- File selector -->
    <div class="file-nav">
      <button v-for="f in files" :key="f.filename"
        class="file-nav-btn" :class="{ active: currentFile === f.filename }"
        :title="f.filename"
        @click="loadFile(f.filename)">
        <MemoryIcon :name="fileIconName(f.filename)" :size="14" />
        <span class="file-nav-name">{{ fileLabel(f.filename) }}</span>
      </button>
    </div>

    <!-- Loading -->
    <MemorySkeleton v-if="loading" />

    <!-- Sections -->
    <div v-else-if="sections.length > 0" class="section-list">
      <MemorySection
        v-for="(sec, idx) in sections"
        :key="`${props.agentId}-${currentFile}-${idx}`"
        :section="sec"
        :save-handler="(body) => saveSection(sec, body)"
      />
    </div>

    <!-- Empty -->
    <MemoryEmptyState v-else icon="file-text" :text="t('memory.memoryBrowser.empty')" />
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { http, agentContextApi } from '@/api'
import MemoryIcon from './MemoryIcon.vue'
import MemorySkeleton from './MemorySkeleton.vue'
import MemoryEmptyState from './MemoryEmptyState.vue'
import MemorySection from './MemorySection.vue'
import type { MemoryIconName } from './icons'
import type { MemorySectionData } from './types'

const props = defineProps<{ agentId: string | number }>()
const { t } = useI18n()

interface FileInfo { filename: string; fileSize: number; enabled: boolean }

const files = ref<FileInfo[]>([])
const currentFile = ref('')
const sections = ref<MemorySectionData[]>([])
const loading = ref(false)

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
  try {
    const res: any = await agentContextApi.getFile(props.agentId, filename)
    const content: string = res.data?.content || ''
    sections.value = parseSections(content)
  } catch { sections.value = [] }
  finally { loading.value = false }
}

function parseSections(content: string): MemorySectionData[] {
  if (!content.trim()) return []
  const parts = content.split(/(?=^## )/m)
  const result: MemorySectionData[] = []
  for (const part of parts) {
    const match = part.match(/^## (.+)\n([\s\S]*)/)
    if (match) {
      const heading = match[1].trim()
      const rawBody = match[2].trim()
      const userEdited = rawBody.includes('<!-- user-edited')
      // Strip the hidden marker from the display body — it is metadata, and
      // since renderMarkdown escapes HTML it would otherwise show as raw text.
      result.push({ heading, body: stripMarker(rawBody), userEdited })
    } else if (part.trim() && result.length === 0) {
      // Content before the first ## heading — a synthetic "preamble" section.
      result.push({ heading: t('memory.memoryBrowser.header'), body: part.trim(), userEdited: false, synthetic: true })
    }
  }
  return result
}

// Strip the hidden user-edited marker so it never shows up as raw text in the
// editor (and never accumulates when a section is edited repeatedly).
function stripMarker(body: string): string {
  return body.replace(/^[ \t]*<!-- user-edited:.*-->[ \t]*$/gm, '').trim()
}

/**
 * Persist an edited section. Throws on failure so MemorySection can keep the
 * editor open and surface the error.
 */
async function saveSection(sec: MemorySectionData, body: string) {
  if (sec.synthetic) {
    // The preamble (content before the first ## heading) has no section key
    // the HiL endpoint can address — rewrite it through the workspace file
    // API, preserving every real `## ` section that follows.
    const res: any = await agentContextApi.getFile(props.agentId, currentFile.value)
    const content: string = res.data?.content || ''
    const headingIdx = content.search(/^## /m)
    const rest = headingIdx >= 0 ? content.slice(headingIdx) : ''
    const preamble = body.trim()
    const merged = preamble && rest ? `${preamble}\n\n${rest}` : preamble + rest
    await agentContextApi.saveFile(props.agentId, currentFile.value, merged)
  } else {
    // Use HiL edit endpoint to write back with user-edited metadata.
    // `filename` tells the backend which memory file to edit — without it the
    // backend defaults to MEMORY.md and PROFILE.md / SOUL.md edits fail.
    await http.post(
      `/memory/${props.agentId}/dream/reports/0/entries/${encodeURIComponent(sec.heading)}/edit`,
      { content: body, filename: currentFile.value }
    )
  }
  mcToast.success(t('memory.hil.saved'))
  // Reload file to see changes
  await loadFile(currentFile.value)
}

// Registry icon key for each memory file type.
function fileIconName(filename: string): MemoryIconName {
  if (filename === 'MEMORY.md') return 'memory'
  if (filename === 'PROFILE.md') return 'profile'
  if (filename === 'SOUL.md') return 'soul'
  if (filename === 'structured/reference.md') return 'link'
  if (filename.startsWith('structured/')) return 'list'
  return 'file'
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
.file-nav-name { letter-spacing: 0.1px; }

/* Section list */
.section-list { display: flex; flex-direction: column; gap: 10px; }
</style>
