<template>
  <el-image-viewer
    v-if="open"
    :url-list="urls"
    :initial-index="activeIndex"
    :hide-on-click-modal="true"
    :teleported="true"
    @close="close"
  />
</template>

<script setup lang="ts">
/**
 * Lightbox overlay for inline wiki page images.
 *
 * <p>The component is mounted once per wiki page view and exposes a
 * single imperative entry point — {@link attach} — that the parent
 * calls after a markdown render lands. attach() walks every <img>
 * inside the supplied container, attaches a click handler that opens
 * the lightbox at the image's index, applies the cursor cue, and
 * marks the element as bound so subsequent attach() calls are
 * idempotent.
 *
 * <p>Element Plus ships {@code ElImageViewer} as a teleported
 * fullscreen overlay; the heavyweight scroll/zoom UI comes from
 * upstream so this component stays a thin coordinator.
 */
import { ref } from 'vue'

const open = ref(false)
const urls = ref<string[]>([])
const activeIndex = ref(0)

const BOUND_FLAG = 'wikiLightboxBound'

function close() {
  open.value = false
}

/**
 * Walks all <img> elements in {@code container} and binds click → open
 * lightbox. Safe to call repeatedly: already-bound elements are skipped.
 *
 * @param container any HTMLElement that holds rendered markdown
 */
function attach(container: HTMLElement | null) {
  if (!container) return
  const images = Array.from(container.querySelectorAll('img'))
  if (images.length === 0) return

  // Snapshot URLs so the order is stable for the duration of this view.
  const newUrls = images
    .map(img => img.getAttribute('src'))
    .filter((u): u is string => !!u && u.length > 0)
  urls.value = newUrls

  images.forEach((img, idx) => {
    if (img.dataset[BOUND_FLAG] === 'true') return
    img.dataset[BOUND_FLAG] = 'true'
    img.style.cursor = 'zoom-in'
    img.addEventListener('click', (event) => {
      event.preventDefault()
      activeIndex.value = idx
      open.value = true
    })
  })
}

defineExpose({ attach })
</script>
