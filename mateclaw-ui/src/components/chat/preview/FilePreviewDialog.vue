<template>
  <el-dialog
    v-model="visible"
    :title="attachment?.name || ''"
    class="file-preview-dialog"
    :class="{ 'file-preview-dialog--fullscreen': fullscreen }"
    :fullscreen="fullscreen"
    width="min(960px, 94vw)"
    top="4vh"
    destroy-on-close
    append-to-body
    @closed="reset"
  >
    <template #header>
      <div class="file-preview-dialog__header">
        <span class="file-preview-dialog__title" :title="attachment?.name">{{ attachment?.name }}</span>
        <button
          class="file-preview-dialog__ghost"
          type="button"
          :title="fullscreen ? $t('chat.preview.exitFullscreen') : $t('chat.preview.fullscreen')"
          :aria-label="fullscreen ? $t('chat.preview.exitFullscreen') : $t('chat.preview.fullscreen')"
          @click="fullscreen = !fullscreen"
        >
          <el-icon><FullScreen v-if="!fullscreen" /><Aim v-else /></el-icon>
        </button>
        <button
          class="file-preview-dialog__ghost file-preview-dialog__ghost--labeled"
          type="button"
          @click="attachment && downloadFile(attachment)"
        >
          <el-icon><Download /></el-icon>
          <span>{{ $t('chat.preview.download') }}</span>
        </button>
      </div>
    </template>

    <div class="file-preview-dialog__body">
      <PreviewSpinner
        v-if="state === 'loading'"
        :label="$t('chat.preview.loading')"
      />
      <div v-else-if="state === 'unsupported'" class="file-preview-dialog__status">
        <p>{{ $t('chat.preview.unsupported') }}</p>
        <button class="file-preview-dialog__cta" type="button" @click="attachment && downloadFile(attachment)">
          {{ $t('chat.preview.download') }}
        </button>
      </div>
      <div v-else-if="state === 'error'" class="file-preview-dialog__status">
        <p>{{ $t('chat.preview.failed') }}</p>
        <button class="file-preview-dialog__cta" type="button" @click="attachment && downloadFile(attachment)">
          {{ $t('chat.preview.download') }}
        </button>
      </div>
      <component
        :is="previewComponent"
        v-else-if="state === 'ready' && previewComponent && bytes"
        :data="bytes"
        :filename="attachment?.name || ''"
      />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { Aim, Download, FullScreen } from '@element-plus/icons-vue'
import { fetchAuthenticatedBlob } from '@/api/index'
import { useAuthenticatedAttachment } from '@/composables/useAuthenticatedAttachment'
import type { ChatAttachment } from '@/types'
import { previewKindOf, type PreviewKind } from './previewKind'
import { OPEN_FILE_PREVIEW_EVENT, type PreviewTarget } from './previewBus'
import PreviewSpinner from './PreviewSpinner.vue'

// When mounted as the single app-root instance, this dialog listens on the
// window bus so attachment cards and generated-file links can open it without
// prop-drilling a ref.
const props = defineProps<{ global?: boolean }>()

// Preview renderers are lazy chunks — pdfjs/docx-preview/exceljs stay out of
// the main bundle until a matching file is actually opened.
const PdfPreview = defineAsyncComponent(() => import('./PdfPreview.vue'))
const DocxPreview = defineAsyncComponent(() => import('./DocxPreview.vue'))
const SheetPreview = defineAsyncComponent(() => import('./SheetPreview.vue'))
const TextPreview = defineAsyncComponent(() => import('./TextPreview.vue'))
const HtmlPreview = defineAsyncComponent(() => import('./HtmlPreview.vue'))

const { downloadFile } = useAuthenticatedAttachment()

const visible = ref(false)
const fullscreen = ref(false)
/** Increments per open() call; the latest wins when async fetches race. */
let openSeq = 0
const state = ref<'loading' | 'ready' | 'error' | 'unsupported'>('loading')
const attachment = ref<ChatAttachment | null>(null)
const bytes = shallowRef<ArrayBuffer | null>(null)
/** Effective render kind ('office' resolves to 'pdf' after server conversion). */
const renderKind = ref<Exclude<PreviewKind, 'office'> | null>(null)

const previewComponent = computed(() => {
  switch (renderKind.value) {
    case 'pdf': return PdfPreview
    case 'docx': return DocxPreview
    case 'sheet': return SheetPreview
    case 'text': return TextPreview
    case 'html': return HtmlPreview
    default: return null
  }
})

function reset() {
  state.value = 'loading'
  attachment.value = null
  bytes.value = null
  renderKind.value = null
  fullscreen.value = false
}

/** Open the dialog for an attachment or a bare {name,url} generated-file target. */
async function open(target: PreviewTarget) {
  // Normalize into a ChatAttachment shape so download/render paths are uniform.
  const att: ChatAttachment = {
    name: target.name,
    url: target.url,
    storedName: target.storedName ?? target.name,
    contentType: target.contentType ?? '',
    size: target.size ?? 0,
    path: target.path ?? target.url,
  }
  // Monotonic token guards against a stale fetch resolving after the dialog
  // was closed or reopened for a different file. (Object-identity comparison
  // is unreliable: assigning to a deep `ref` reactive-wraps the value, so
  // `attachment.value === att` would never hold for a freshly built object.)
  const reqId = ++openSeq
  attachment.value = att
  state.value = 'loading'
  bytes.value = null
  visible.value = true

  const kind = previewKindOf(att)
  if (!kind) {
    state.value = 'unsupported'
    return
  }

  try {
    if (kind === 'office') {
      // Server-side office→PDF conversion; 501 means no converter installed.
      const blob = await fetchAuthenticatedBlob(att.url.replace(/\/?$/, '') + '/preview')
      bytes.value = await blob.arrayBuffer()
      renderKind.value = 'pdf'
    } else {
      const blob = await fetchAuthenticatedBlob(att.url)
      bytes.value = await blob.arrayBuffer()
      renderKind.value = kind
    }
    // Ignore if the dialog was closed or reopened for another file meanwhile.
    if (visible.value && reqId === openSeq) {
      state.value = 'ready'
    }
  } catch (e) {
    console.warn('[FilePreviewDialog] preview load failed:', att.name, e)
    if (visible.value && reqId === openSeq) {
      state.value = kind === 'office' ? 'unsupported' : 'error'
    }
  }
}

// Global instance: open on window-bus events from attachment cards and the
// generated-file link interceptor.
function onPreviewEvent(e: Event) {
  const detail = (e as CustomEvent<PreviewTarget>).detail
  if (detail?.url && detail?.name) void open(detail)
}
onMounted(() => {
  if (props.global) window.addEventListener(OPEN_FILE_PREVIEW_EVENT, onPreviewEvent)
})
onBeforeUnmount(() => {
  if (props.global) window.removeEventListener(OPEN_FILE_PREVIEW_EVENT, onPreviewEvent)
})

defineExpose({ open })
</script>

<style scoped>
.file-preview-dialog__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-right: 32px;
  min-width: 0;
}
.file-preview-dialog__title {
  flex: 1;
  font-weight: 600;
  font-size: var(--mc-text-base, 15px);
  color: var(--mc-text-primary, #1d1612);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* Ghost buttons (fullscreen toggle, download) — quiet until hovered, then
   lift in terracotta. Icon-only by default; the labeled variant adds text. */
.file-preview-dialog__ghost {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px;
  border: none;
  border-radius: var(--mc-radius-md, 12px);
  background: transparent;
  color: var(--mc-text-secondary, #665245);
  font: inherit;
  font-size: var(--mc-text-sm, 13px);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.file-preview-dialog__ghost--labeled {
  padding: 6px 12px;
}
.file-preview-dialog__ghost:hover {
  background: var(--mc-primary-bg, #f6e2d7);
  color: var(--mc-primary, #d96d46);
}
.file-preview-dialog__body {
  height: 74vh;
  overflow: hidden;
  background: var(--mc-bg-sunken, #ebe3db);
}
/* Fullscreen: let the desk fill the whole viewport below the header. */
.file-preview-dialog--fullscreen .file-preview-dialog__body {
  height: calc(100vh - 56px);
}
.file-preview-dialog__status {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: var(--mc-text-secondary, #665245);
  font-size: var(--mc-text-sm, 13px);
}
.file-preview-dialog__cta {
  padding: 8px 20px;
  border: none;
  border-radius: var(--mc-radius-md, 12px);
  background: var(--mc-primary, #d96d46);
  color: var(--mc-text-inverse, #fff);
  font: inherit;
  font-size: var(--mc-text-sm, 13px);
  cursor: pointer;
  transition: background 0.15s ease;
}
.file-preview-dialog__cta:hover {
  background: var(--mc-primary-hover, #bb4f27);
}
</style>

<style>
/* Glass shell: the dialog IS the frosted glass, matching .mc-page-frame,
   instead of a plain white box dropped onto the warm theme. */
.file-preview-dialog.el-dialog {
  background: var(--mc-surface-overlay, rgba(255, 255, 255, 0.72));
  backdrop-filter: blur(12px) saturate(1.1);
  border: 1px solid var(--mc-border, #d9cec2);
  border-radius: var(--mc-radius-xl, 20px);
  box-shadow: var(--mc-shadow-strong, 0 24px 70px rgba(58, 32, 19, 0.16));
  overflow: hidden;
  padding: 0;
}
/* Fullscreen fills the viewport edge-to-edge: no radius, no border, no lift. */
.file-preview-dialog.el-dialog.is-fullscreen {
  border: none;
  border-radius: 0;
  box-shadow: none;
}
.file-preview-dialog .el-dialog__header {
  margin: 0;
  padding: 16px 20px 8px;
}
.file-preview-dialog .el-dialog__headerbtn {
  top: 14px;
  right: 12px;
}
.file-preview-dialog .el-dialog__headerbtn .el-dialog__close {
  color: var(--mc-text-tertiary, #9b7d6c);
}
.file-preview-dialog .el-dialog__headerbtn:hover .el-dialog__close {
  color: var(--mc-primary, #d96d46);
}
/* Kill the inner box: body renders the content directly on the warm desk. */
.file-preview-dialog .el-dialog__body {
  padding: 0;
}
/* Warm-black overlay instead of neutral black. */
.file-preview-dialog + .el-overlay,
.el-overlay:has(.file-preview-dialog) {
  background: rgba(29, 22, 18, 0.32);
}
</style>
