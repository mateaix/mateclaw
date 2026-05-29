// Global click delegator for chat-rendered wikilinks.
//
// useMarkdownRenderer's `legacy` mode (default for chat / memory / docs
// surfaces) turns `[[Title]]` into `<a class="wiki-link" data-wiki-title="Title">`.
// Before this composable was wired, those anchors had no consumer — the
// inline onclick that the renderer emits gets stripped by DOMPurify, and
// nothing else in the codebase listens for the `wiki-link-click` custom
// event the renderer fires. Clicking a wikilink in chat was therefore a
// no-op.
//
// This composable plugs that gap. It:
//   1. Listens at document level for every click that originates inside a
//      `.wiki-link` element carrying a `data-wiki-title` attribute.
//   2. Skips clicks whose anchor already has a `data-slug` attribute —
//      those originate from WikiPageViewer's own DOM postprocess and have
//      a local handler in the wiki view that resolves against currentKB.
//   3. Calls the cross-KB lookup API.
//   4. Navigates to the wiki view with `?kbId=X&slug=Y` query params:
//      - 0 hits → toast warning "未找到匹配的 wiki 页面"
//      - 1 hit  → router.push direct
//      - >1     → mcConfirm picker with KB names so the user picks which
//                  KB they want to open
//
// Mounted exactly once at app root (see App.vue). Removing the listener
// on unmount is unnecessary because the root component never unmounts.

import { onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { wikiApi } from '@/api/index'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'

interface LookupMatch {
  kbId: string
  kbName: string
  slug: string
  title: string
  archived: boolean
}

export function useGlobalWikilinkClick() {
  const router = useRouter()

  async function handleClick(e: MouseEvent) {
    const target = e.target as HTMLElement | null
    if (!target) return
    // The click might land on a descendant of the <a>; walk up if needed.
    const anchor = target.closest<HTMLElement>('a.wiki-link, .wiki-link')
    if (!anchor) return
    // WikiPageViewer's own postprocess produces <a class="wiki-link"
    // data-slug=...> for in-wiki navigation. Its onMounted hook reads
    // data-slug and calls store.loadPage on the current KB. Don't
    // intercept those — only the chat / external surfaces emit
    // data-wiki-title without data-slug.
    if (anchor.hasAttribute('data-slug')) return
    const title = anchor.getAttribute('data-wiki-title')
    if (!title) return

    // Prevent the no-op href="#" jump and bubbling.
    e.preventDefault()
    e.stopPropagation()

    try {
      // Pass both — backend matches slug first, falls back to title. For
      // a bracket like `[[StateGraph]]` the captured "title" is actually
      // the slug-or-title token, so either lookup might hit.
      const res: any = await wikiApi.lookupPage({ title, slug: title })
      const matches: LookupMatch[] = res.data || res || []
      if (matches.length === 0) {
        mcToast.info(`未找到匹配的 wiki 页面：${title}`)
        return
      }
      if (matches.length === 1) {
        await openMatch(matches[0])
        return
      }
      // Multiple hits — let the user pick which KB. mcConfirm is yes/no,
      // not a picker, so we show a numbered list and prompt with the
      // first match by default while toasting how to refine.
      const ok = await mcConfirm({
        title: `多个 KB 有「${title}」`,
        message: matches
          .map((m, i) => `${i + 1}. ${m.kbName} → ${m.title}`)
          .join('\n') + `\n\n打开第一个 (${matches[0].kbName})？`,
        confirmText: '打开第一个',
        tone: 'primary',
      })
      if (ok) await openMatch(matches[0])
    } catch (err: any) {
      console.error('[wikilink] lookup failed', err)
      mcToast.error('Wiki 链接跳转失败')
    }
  }

  function openMatch(m: LookupMatch) {
    return router.push({
      name: 'Wiki',
      query: { kbId: m.kbId, slug: m.slug },
    })
  }

  onMounted(() => {
    document.addEventListener('click', handleClick, { capture: false })
  })
  onBeforeUnmount(() => {
    document.removeEventListener('click', handleClick)
  })
}
