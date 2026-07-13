<template>
  <div class="text-preview">
    <div
      v-if="flavor === 'markdown'"
      class="text-preview__markdown markdown-body"
      v-html="renderedMarkdown"
    />
    <pre v-else class="text-preview__code"><code v-html="highlighted" /></pre>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import hljs from 'highlight.js'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import { extensionOf, textFlavorOf } from './previewKind'

const props = defineProps<{
  /** Raw text bytes (already fetched with auth). */
  data: ArrayBuffer
  filename: string
}>()

/** Guard: a 50MB log file must not lock the tab. */
const MAX_CHARS = 500_000

const { renderMarkdown } = useMarkdownRenderer()

const text = computed(() => {
  const raw = new TextDecoder().decode(props.data)
  return raw.length > MAX_CHARS ? raw.slice(0, MAX_CHARS) + '\n…' : raw
})

const flavor = computed(() => textFlavorOf(props.filename))

const renderedMarkdown = computed(() =>
  flavor.value === 'markdown' ? renderMarkdown(text.value) : '',
)

function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

const highlighted = computed(() => {
  if (flavor.value !== 'code') return escapeHtml(text.value)
  const lang = extensionOf(props.filename)
  try {
    if (hljs.getLanguage(lang)) {
      return hljs.highlight(text.value, { language: lang }).value
    }
  } catch { /* fall through to plain */ }
  return escapeHtml(text.value)
})
</script>

<style scoped>
.text-preview {
  height: 100%;
  overflow-y: auto;
  background: var(--mc-bg-sunken, #ebe3db);
}
.text-preview__markdown {
  padding: 24px 28px;
  max-width: 860px;
  margin: 20px auto;
  background: var(--mc-bg-elevated, #fff);
  border-radius: var(--mc-radius-md, 12px);
  box-shadow: var(--mc-shadow-soft, 0 10px 30px rgba(58, 32, 19, 0.08));
}
.text-preview__code {
  margin: 20px auto;
  max-width: 900px;
  padding: 18px 20px;
  background: var(--mc-code-bg, #faf6f1);
  border: 1px solid var(--mc-border-light, #ebe3db);
  border-radius: var(--mc-radius-md, 12px);
  font-family: var(--mc-font-mono, ui-monospace, monospace);
  font-size: var(--mc-text-sm, 13px);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--mc-text-primary, #1d1612);
}
</style>
