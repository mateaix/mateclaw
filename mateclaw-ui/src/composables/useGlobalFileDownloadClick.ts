// Global click delegator for tool-generated file download links.
//
// `useMarkdownRenderer.link()` turns a tool-returned download URL such as
// `[报告.docx](/api/v1/files/generated/<id>)` into a plain same-origin
// `<a href>`. With no consumer, clicking it lets the browser perform a
// whole-window navigation to that URL. When the file has expired, was never
// produced, or the backend restarted, the endpoint answers
// `404 {"error":"File not found or expired"}` — and because it is a full-page
// navigation, that JSON *replaces the entire SPA*. In the desktop shell there
// is no back affordance, so the user is stuck and must restart the app.
//
// This composable closes that gap. It intercepts clicks on any same-origin
// `/api/v1/files/...` anchor and downloads via an authenticated fetch → blob
// instead of navigating:
//   - success → trigger a transient `<a download>`; the SPA never unmounts.
//   - failure (404 / expired / network) → an inline toast; the user stays in
//     the conversation with the chat intact.
//
// Mounted exactly once at app root (see App.vue). Because the root component
// never unmounts, detaching the listener on unmount is a formality.

import { onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { fetchAuthenticatedBlob } from '@/api/index'
import { mcToast } from '@/composables/useMcToast'

// Matches every backend-served file path: in-memory generated files
// (`/api/v1/files/generated/<id>`) and conversation-scoped media/attachments
// (`/api/v1/files/...`, `/api/v1/chat/files/...`).
const FILE_PATH_RE = /^\/api\/v1\/(files|chat\/files)\//

function filenameFor(anchor: HTMLAnchorElement, pathname: string): string {
  const text = (anchor.textContent || '').trim()
  // The markdown link label is the human filename ("报告.docx"); prefer it
  // when it carries an extension, otherwise fall back to the URL's last segment.
  if (text && /\.[a-z0-9]{1,8}$/i.test(text)) return text
  const seg = decodeURIComponent(pathname.split('/').filter(Boolean).pop() || '')
  return seg || text || 'download'
}

export function useGlobalFileDownloadClick() {
  const { t } = useI18n()

  async function handleClick(e: MouseEvent) {
    // Honour modifier-clicks (open in new tab / window) and non-primary buttons.
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
    const target = e.target as HTMLElement | null
    if (!target) return
    const anchor = target.closest<HTMLAnchorElement>('a[href]')
    if (!anchor) return

    // Only same-origin file-API links; leave everything else to the browser.
    let url: URL
    try {
      url = new URL(anchor.href, window.location.href)
    } catch {
      return
    }
    if (url.origin !== window.location.origin || !FILE_PATH_RE.test(url.pathname)) return

    // From here the link is ours: never let it become a full-page navigation.
    e.preventDefault()
    e.stopPropagation()

    const name = filenameFor(anchor, url.pathname)
    try {
      const blob = await fetchAuthenticatedBlob(url.href)
      const objectUrl = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = objectUrl
      a.download = name
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      setTimeout(() => URL.revokeObjectURL(objectUrl), 10000)
      mcToast.success(t('chat.downloadStarted', { name }))
    } catch (err: any) {
      // A cache-miss / expiry surfaces as a non-OK fetch ("Fetch failed: 404").
      const status = /(\d{3})/.exec(err?.message || '')?.[1]
      if (status === '404' || status === '410') {
        mcToast.error(t('chat.downloadExpired'))
      } else {
        mcToast.error(t('chat.downloadFailed', { reason: err?.message || 'unknown' }))
      }
    }
  }

  onMounted(() => {
    // Capture phase so we intercept before any descendant handler, and before
    // the browser's default navigation on the anchor.
    document.addEventListener('click', handleClick, { capture: true })
  })
  onBeforeUnmount(() => {
    document.removeEventListener('click', handleClick, { capture: true })
  })
}
