<script setup lang="ts">
import { computed } from 'vue'

/**
 * Lightweight, dependency-free JSON viewer with syntax highlighting.
 * Parses the raw string as JSON and pretty-prints it with token colors;
 * when the input is not valid JSON (e.g. plain terminal output), it falls
 * back to rendering the raw text verbatim without highlighting.
 */
const props = defineProps<{
  raw?: string
}>()

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/** Wrap JSON tokens (keys, strings, numbers, booleans, null) in colored spans. */
function highlight(jsonStr: string): string {
  return escapeHtml(jsonStr).replace(
    /("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false)\b|\bnull\b|-?\d+(?:\.\d*)?(?:[eE][+-]?\d+)?)/g,
    (match) => {
      let cls = 'jv-number'
      if (/^"/.test(match)) {
        cls = /:$/.test(match) ? 'jv-key' : 'jv-string'
      } else if (/true|false/.test(match)) {
        cls = 'jv-boolean'
      } else if (/null/.test(match)) {
        cls = 'jv-null'
      }
      return `<span class="${cls}">${match}</span>`
    },
  )
}

const parsed = computed(() => {
  const s = (props.raw || '').trim()
  if (!s) return { empty: true, isJson: false, html: '' }
  try {
    const pretty = JSON.stringify(JSON.parse(s), null, 2)
    return { empty: false, isJson: true, html: highlight(pretty) }
  } catch {
    return { empty: false, isJson: false, html: escapeHtml(props.raw || '') }
  }
})
</script>

<template>
  <pre class="json-view" :class="{ 'is-plain': !parsed.isJson }"><code v-html="parsed.html" /></pre>
</template>

<style scoped>
.json-view {
  margin: 0;
  padding: 12px 14px;
  /* Translucent surface so the dialog's frosted-glass blur shows through */
  background: rgba(255, 255, 255, 0.42);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  font-family: var(--mc-font-mono, 'SF Mono', 'Menlo', 'Consolas', monospace);
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--mc-code-text, #1c1410);
  max-height: 420px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  tab-size: 2;
}
.json-view code {
  font-family: inherit;
}
</style>

<!-- Token palette lives in a non-scoped block: the colored spans are produced
     via v-html, so they carry no scoped data-attribute. -->
<style>
.json-view .jv-key { color: #e45649; }
.json-view .jv-string { color: #50a14f; }
.json-view .jv-number { color: #c18401; }
.json-view .jv-boolean { color: #a626a4; }
.json-view .jv-null { color: #a626a4; }

html.dark .json-view {
  background: rgba(255, 255, 255, 0.05);
}
html.dark .json-view .jv-key { color: #e06c75; }
html.dark .json-view .jv-string { color: #98c379; }
html.dark .json-view .jv-number { color: #d19a66; }
html.dark .json-view .jv-boolean { color: #c678dd; }
html.dark .json-view .jv-null { color: #c678dd; }
</style>
