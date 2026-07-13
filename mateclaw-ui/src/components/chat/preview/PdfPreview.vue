<template>
  <div class="pdf-preview">
    <div v-if="error" class="pdf-preview__error">{{ $t('chat.preview.failed') }}</div>
    <PreviewSpinner v-else-if="loading" :label="$t('chat.preview.loading')" />
    <div v-else ref="pagesEl" class="pdf-preview__pages">
      <canvas
        v-for="page in pageCount"
        :key="page"
        :ref="el => setCanvasRef(page, el as HTMLCanvasElement | null)"
        class="pdf-preview__page"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { PDFDocumentLoadingTask, PDFDocumentProxy } from 'pdfjs-dist'
import PreviewSpinner from './PreviewSpinner.vue'

const props = defineProps<{
  /** Raw PDF bytes (already fetched with auth). */
  data: ArrayBuffer
}>()

const loading = ref(true)
const error = ref(false)
const pageCount = ref(0)
const pagesEl = ref<HTMLElement | null>(null)

let loadingTask: PDFDocumentLoadingTask | null = null
let pdfDoc: PDFDocumentProxy | null = null
let observer: IntersectionObserver | null = null
const canvasRefs = new Map<number, HTMLCanvasElement>()
const renderedPages = new Set<number>()

function setCanvasRef(page: number, el: HTMLCanvasElement | null) {
  if (el) {
    canvasRefs.set(page, el)
    el.dataset.page = String(page)
    observer?.observe(el)
  } else {
    canvasRefs.delete(page)
  }
}

async function renderPage(pageNum: number) {
  if (!pdfDoc || renderedPages.has(pageNum)) return
  const canvas = canvasRefs.get(pageNum)
  if (!canvas) return
  renderedPages.add(pageNum)
  try {
    const page = await pdfDoc.getPage(pageNum)
    // Fit the page to the container width, capped at 2x for retina crispness.
    const containerWidth = pagesEl.value?.clientWidth || 800
    const baseViewport = page.getViewport({ scale: 1 })
    const scale = Math.min((containerWidth / baseViewport.width) * (window.devicePixelRatio || 1), 3)
    const viewport = page.getViewport({ scale })
    canvas.width = viewport.width
    canvas.height = viewport.height
    canvas.style.width = '100%'
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    await page.render({ canvas, canvasContext: ctx, viewport }).promise
  } catch (e) {
    renderedPages.delete(pageNum)
    console.warn('[PdfPreview] page render failed:', pageNum, e)
  }
}

onMounted(async () => {
  try {
    const pdfjs = await import('pdfjs-dist')
    pdfjs.GlobalWorkerOptions.workerSrc = new URL(
      'pdfjs-dist/build/pdf.worker.min.mjs',
      import.meta.url,
    ).toString()
    // pdfjs transfers the buffer to its worker — hand it a copy so the parent
    // dialog can still reuse the original bytes (e.g. for download).
    loadingTask = pdfjs.getDocument({ data: props.data.slice(0) })
    pdfDoc = await loadingTask.promise
    pageCount.value = pdfDoc.numPages
    loading.value = false

    // Lazy-render pages as they scroll into view (first pages render eagerly
    // because they start inside the viewport).
    observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          const page = Number((entry.target as HTMLElement).dataset.page)
          if (page) void renderPage(page)
        }
      }
    }, { rootMargin: '400px' })
  } catch (e) {
    console.error('[PdfPreview] failed to load document:', e)
    error.value = true
    loading.value = false
  }
})

onBeforeUnmount(() => {
  observer?.disconnect()
  void loadingTask?.destroy()
  loadingTask = null
  pdfDoc = null
})
</script>

<style scoped>
.pdf-preview {
  height: 100%;
  overflow-y: auto;
  background: var(--mc-bg-sunken, #ebe3db);
}
.pdf-preview__pages {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px 12px;
  max-width: 900px;
  margin: 0 auto;
}
/* White paper floating on the warm desk. */
.pdf-preview__page {
  display: block;
  background: #fff;
  box-shadow: var(--mc-shadow-soft, 0 10px 30px rgba(58, 32, 19, 0.08));
  border-radius: var(--mc-radius-sm, 6px);
}
.pdf-preview__error {
  padding: 48px;
  text-align: center;
  color: var(--mc-text-secondary, #665245);
}
</style>
