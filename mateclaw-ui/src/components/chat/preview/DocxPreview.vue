<template>
  <div class="docx-preview-wrap">
    <div v-if="error" class="docx-preview-wrap__error">{{ $t('chat.preview.failed') }}</div>
    <PreviewSpinner v-else-if="loading" :label="$t('chat.preview.loading')" />
    <div v-show="!loading && !error" ref="containerEl" class="docx-preview-wrap__body" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PreviewSpinner from './PreviewSpinner.vue'

const props = defineProps<{
  /** Raw .docx bytes (already fetched with auth). */
  data: ArrayBuffer
}>()

const loading = ref(true)
const error = ref(false)
const containerEl = ref<HTMLElement | null>(null)

onMounted(async () => {
  try {
    const { renderAsync } = await import('docx-preview')
    if (!containerEl.value) return
    await renderAsync(props.data, containerEl.value, undefined, {
      inWrapper: true,
      ignoreLastRenderedPageBreak: true,
      // Embedded fonts come from the document author; keep them but never
      // execute anything active.
      experimental: false,
    })
    loading.value = false
  } catch (e) {
    console.error('[DocxPreview] render failed:', e)
    error.value = true
    loading.value = false
  }
})
</script>

<style scoped>
.docx-preview-wrap {
  height: 100%;
  overflow-y: auto;
  background: var(--mc-bg-sunken, #ebe3db);
}
.docx-preview-wrap__body {
  padding: 20px 12px;
}
/* docx-preview renders fixed-width "pages"; keep them responsive and floating
   on the warm desk like sheets of paper. */
.docx-preview-wrap__body :deep(.docx-wrapper) {
  background: transparent;
  padding: 0;
}
.docx-preview-wrap__body :deep(.docx-wrapper > section.docx) {
  max-width: 100%;
  background: #fff;
  box-shadow: var(--mc-shadow-soft, 0 10px 30px rgba(58, 32, 19, 0.08));
  border-radius: var(--mc-radius-sm, 6px);
  margin: 0 auto 16px;
}
.docx-preview-wrap__error {
  padding: 48px;
  text-align: center;
  color: var(--mc-text-secondary, #665245);
}
</style>
