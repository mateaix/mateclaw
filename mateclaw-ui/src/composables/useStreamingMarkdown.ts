import { ref, watch, onUnmounted, type Ref } from 'vue'
import { useMarkdownRenderer, type RenderMarkdownOptions } from '@/composables/useMarkdownRenderer'

/**
 * Throttle interval (ms) for mid-stream markdown re-renders.
 *
 * Streaming emits one `content_delta` per token; rendering the full accumulated
 * markdown on every delta is O(n²) over the message and re-runs marked +
 * highlight.js + DOMPurify each time — the dominant source of streaming jank.
 * Throttling to ~7 renders/sec keeps the text feeling live while cutting the
 * render count ~10× on a fast model. The final render (when streaming stops)
 * always runs immediately at full fidelity.
 */
const STREAM_RENDER_INTERVAL_MS = 140

/**
 * Reactive, throttled markdown rendering for streaming message content.
 *
 * While `streaming()` is true the rendered HTML is refreshed at most once per
 * {@link STREAM_RENDER_INTERVAL_MS} and uses the renderer's `streaming` mode
 * (skips code auto-highlight, bypasses the cache). The moment `streaming()`
 * flips to false — or the source changes while already idle — it renders once
 * immediately with full fidelity (auto-highlight + cache).
 *
 * @param source     getter for the raw markdown text
 * @param streaming  getter that is true while the text is still being streamed
 * @param opts       passed through to `renderMarkdown` (e.g. `wikilink`)
 */
export function useStreamingMarkdown(
  source: () => string,
  streaming: () => boolean,
  opts?: RenderMarkdownOptions,
): { html: Ref<string> } {
  const { renderMarkdown } = useMarkdownRenderer()
  const html = ref('')

  let throttleTimer: ReturnType<typeof setTimeout> | null = null
  let pendingText: string | null = null
  let lastRenderMs = 0

  const clearTimer = () => {
    if (throttleTimer) {
      clearTimeout(throttleTimer)
      throttleTimer = null
    }
  }

  const renderNow = (text: string, stream: boolean) => {
    html.value = text ? renderMarkdown(text, { ...opts, streaming: stream }) : ''
    lastRenderMs = Date.now()
  }

  watch(
    [source, streaming],
    ([text, isStreaming]) => {
      if (!isStreaming) {
        // Idle / completed: full-fidelity render now, drop any pending throttle.
        clearTimer()
        pendingText = null
        renderNow(text, false)
        return
      }
      // Streaming: leading-edge render if we're past the interval, otherwise
      // coalesce into a single trailing render.
      pendingText = text
      const elapsed = Date.now() - lastRenderMs
      if (elapsed >= STREAM_RENDER_INTERVAL_MS) {
        clearTimer()
        renderNow(text, true)
        pendingText = null
      } else if (!throttleTimer) {
        throttleTimer = setTimeout(() => {
          throttleTimer = null
          if (pendingText !== null) {
            renderNow(pendingText, true)
            pendingText = null
          }
        }, STREAM_RENDER_INTERVAL_MS - elapsed)
      }
    },
    { immediate: true },
  )

  onUnmounted(clearTimer)

  return { html }
}
