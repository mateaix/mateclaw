import { onBeforeUnmount, onMounted } from 'vue'
import { fetchAuthenticatedBlob } from '@/api/index'

const GENERATED_IMAGE_RE = /^\/api\/v1\/files\/generated\//

export function useGlobalGeneratedImageBlob() {
  const objectUrls = new Set<string>()
  let observer: MutationObserver | null = null

  function relativeFilePath(src: string): string | null {
    try {
      const url = new URL(src, window.location.href)
      if (!GENERATED_IMAGE_RE.test(url.pathname)) return null
      return url.pathname + url.search
    } catch {
      return null
    }
  }

  async function loadImage(img: HTMLImageElement) {
    if (img.dataset.generatedImageLoaded === '1' || img.dataset.generatedImageLoading === '1') return
    const original = img.dataset.generatedSrc || relativeFilePath(img.getAttribute('src') || '')
    if (!original) return
    img.dataset.generatedSrc = original
    img.dataset.generatedImageLoading = '1'
    try {
      const blob = await fetchAuthenticatedBlob(original)
      const objectUrl = URL.createObjectURL(blob)
      objectUrls.add(objectUrl)
      img.src = objectUrl
      img.dataset.generatedImageLoaded = '1'
    } catch (e) {
      console.warn('[useGlobalGeneratedImageBlob] Failed to load generated image:', original, e)
    } finally {
      delete img.dataset.generatedImageLoading
    }
  }

  function scan(root: ParentNode = document) {
    root.querySelectorAll<HTMLImageElement>('img[data-generated-image]').forEach(loadImage)
  }

  onMounted(() => {
    scan()
    observer = new MutationObserver((mutations) => {
      for (const mutation of mutations) {
        mutation.addedNodes.forEach((node) => {
          if (node instanceof HTMLImageElement && node.matches('img[data-generated-image]')) {
            loadImage(node)
          } else if (node instanceof HTMLElement) {
            scan(node)
          }
        })
      }
    })
    observer.observe(document.body, { childList: true, subtree: true })
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
    objectUrls.forEach((url) => URL.revokeObjectURL(url))
    objectUrls.clear()
  })
}
