<template>
  <div class="section-card">
    <div class="section-header">
      <h4 class="section-title">{{ section.heading }}</h4>
      <div class="section-actions">
        <button v-if="!editing && canMoveUp" class="section-btn section-btn--move" :title="t('memory.hil.moveUp')" @click="emit('moveUp')">↑</button>
        <button v-if="!editing && canMoveDown" class="section-btn section-btn--move" :title="t('memory.hil.moveDown')" @click="emit('moveDown')">↓</button>
        <button v-if="!editing" class="section-btn" :title="t('memory.hil.edit')" @click="startEdit">
          <MemoryIcon name="edit" :size="12" />
        </button>
      </div>
    </div>

    <!-- View mode -->
    <div v-if="!editing" class="section-body" v-html="rendered" />

    <!-- Edit mode -->
    <div v-else class="section-edit">
      <textarea v-model="draft" class="section-textarea" rows="6" />
      <div class="edit-bar">
        <button class="edit-btn cancel" @click="editing = false">{{ t('memory.hil.cancel') }}</button>
        <button class="edit-btn save" :disabled="saving" @click="onSave">
          {{ saving ? '…' : t('memory.hil.save') }}
        </button>
      </div>
    </div>

    <!-- User-edited badge -->
    <div v-if="section.userEdited" class="edited-badge">
      {{ t('memory.memoryBrowser.userEdited') }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import MemoryIcon from './MemoryIcon.vue'
import type { MemorySectionData } from './types'

const props = defineProps<{
  section: MemorySectionData
  /** Persists the edited body — resolves on success, rejects on failure. */
  saveHandler: (body: string) => Promise<void>
  /** Show a ↑ button; emits `moveUp`. Off by default (MemoryBrowser doesn't reorder). */
  canMoveUp?: boolean
  /** Show a ↓ button; emits `moveDown`. */
  canMoveDown?: boolean
}>()

const emit = defineEmits<{ moveUp: []; moveDown: [] }>()

const { t } = useI18n()
const editing = ref(false)
const draft = ref('')
const saving = ref(false)

const rendered = computed(() => renderMarkdown(props.section.body))

function startEdit() {
  // section.body is already marker-free (stripped when the file is parsed).
  draft.value = props.section.body
  editing.value = true
}

async function onSave() {
  saving.value = true
  try {
    await props.saveHandler(draft.value)
    editing.value = false
  } catch (e: any) {
    mcToast.error(e?.message || 'Save failed')
  } finally {
    saving.value = false
  }
}

/**
 * Minimal Markdown — bold, inline code, italic, lists, blockquotes. HTML is
 * escaped first so a section body can never inject markup through v-html.
 */
function renderMarkdown(body: string): string {
  const esc = (s: string) =>
    s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const inline = (s: string) =>
    esc(s)
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/`([^`]+?)`/g, '<code>$1</code>')
      // Italic — require a boundary before the marker so snake_case is left alone.
      .replace(/(^|[\s(])([*_])(?=\S)([^*_]+?)\2(?=[\s).,;:!?，。；：！？]|$)/g, '$1<em>$3</em>')
  return body
    .split('\n')
    .map(line => {
      if (line.trim() === '') return ''
      if (line.startsWith('- ')) return `<li>${inline(line.slice(2))}</li>`
      if (line.startsWith('> ')) return `<blockquote>${inline(line.slice(2))}</blockquote>`
      return `<p>${inline(line)}</p>`
    })
    .filter(Boolean)
    .join('')
}
</script>

<style scoped>
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
.section-btn--move { font-size: 14px; line-height: 1; }

.section-body {
  margin-top: 8px; font-size: 13px; color: var(--mc-text-secondary); line-height: 1.6;
}
.section-body :deep(li) { margin-left: 16px; margin-bottom: 2px; }
.section-body :deep(code) {
  padding: 1px 4px; border-radius: 3px; background: var(--mc-bg-elevated);
  font-size: 12px; font-family: 'SF Mono', Menlo, monospace;
}
.section-body :deep(strong) { color: var(--mc-text-primary); }
.section-body :deep(em) { font-style: italic; }
.section-body :deep(p) { margin: 2px 0; }
.section-body :deep(blockquote) {
  margin: 4px 0; padding: 1px 0 1px 10px;
  border-left: 2px solid var(--mc-border); color: var(--mc-text-tertiary);
}

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
</style>
