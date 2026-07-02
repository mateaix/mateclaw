<template>
  <div class="mc-page-shell docs-shell">
    <div class="mc-page-frame docs-frame">
      <div class="docs-page">
        <aside class="docs-sidebar">
      <div class="docs-sidebar__title">{{ t('docs.title') }}</div>
      <nav class="docs-nav">
        <div v-for="group in groupedDocs" :key="group.label" class="docs-nav__group">
          <div class="docs-nav__group-title">{{ group.label }}</div>
          <button
            v-for="doc in group.items"
            :key="doc.slug"
            class="docs-nav__item"
            :class="{ 'docs-nav__item--active': doc.slug === activeSlug }"
            @click="selectDoc(doc.slug)"
          >
            {{ doc.title }}
          </button>
        </div>
      </nav>
    </aside>

    <main class="docs-content">
      <div v-if="loading" class="docs-state">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="docs-state docs-state--error">{{ error }}</div>
      <article
        v-else
        ref="contentEl"
        class="markdown-body docs-article"
        v-html="rendered"
        @click="onContentClick"
      />
    </main>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { docsApi, type DocMeta } from '@/api'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const { renderMarkdown } = useMarkdownRenderer()

const docs = ref<DocMeta[]>([])
const activeSlug = ref<string>('')
const content = ref<string>('')
const loading = ref(false)
const error = ref<string>('')
const contentEl = ref<HTMLElement | null>(null)

// 后端文档目录只分 zh / en，取 app locale 的主语言段。
const lang = computed(() => (locale.value.startsWith('en') ? 'en' : 'zh'))

// Wikilink 替换是 chat 专用语义，文档里不需要；关掉避免误伤 [[...]] 文本。
const rendered = computed(() => renderMarkdown(content.value, { wikilink: 'none' }))

// 侧栏按后端返回的分组（开始 / 使用 / 扩展 …）聚合，保持顺序，镜像文档站结构。
const groupedDocs = computed(() => {
  const groups: { label: string; items: DocMeta[] }[] = []
  for (const doc of docs.value) {
    const last = groups[groups.length - 1]
    if (last && last.label === doc.group) {
      last.items.push(doc)
    } else {
      groups.push({ label: doc.group, items: [doc] })
    }
  }
  return groups
})

async function loadList() {
  try {
    const res: any = await docsApi.list(lang.value)
    docs.value = res.data || []
  } catch (e) {
    docs.value = []
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function loadContent(slug: string) {
  if (!slug) return
  loading.value = true
  error.value = ''
  try {
    const res: any = await docsApi.content(lang.value, slug)
    content.value = res.data?.content || ''
    activeSlug.value = slug
    contentEl.value?.scrollTo({ top: 0 })
  } catch (e) {
    content.value = ''
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function selectDoc(slug: string) {
  if (slug === activeSlug.value) return
  router.push({ name: 'Docs', params: { slug } })
}

// 拦截文档间的相对链接（如 ./security、models），转成 SPA 内导航，避免整页刷新。
// 外链（target=_blank，由 renderer 标注）保持默认行为。
function onContentClick(e: MouseEvent) {
  const anchor = (e.target as HTMLElement)?.closest('a')
  if (!anchor) return
  if (anchor.target === '_blank') return
  const href = anchor.getAttribute('href') || ''
  if (!href || href.startsWith('#')) return
  const m = /^(?:\.\/|\.\.\/)?([a-z0-9_-]+)(?:\.md)?(?:[#?].*)?$/i.exec(href)
  if (!m) return
  const slug = m[1].toLowerCase()
  if (!docs.value.some((d) => d.slug === slug)) return
  e.preventDefault()
  selectDoc(slug)
}

// 路由 slug 变化 → 加载对应文档；无 slug 时回退到第一篇。
watch(
  () => route.params.slug,
  (slug) => {
    const target = (slug as string) || docs.value[0]?.slug
    if (target && target !== activeSlug.value) {
      loadContent(target)
    }
  },
)

// 切换 app 语言 → 重新拉目录并重载当前文档。
watch(lang, async () => {
  await loadList()
  const target = activeSlug.value || docs.value[0]?.slug
  if (target) await loadContent(target)
})

onMounted(async () => {
  await loadList()
  const slug = route.params.slug as string
  if (slug) {
    await loadContent(slug)
  } else if (docs.value[0]) {
    // 落到第一篇：改写 URL，由 slug watcher 负责加载（避免重复请求）。
    router.replace({ name: 'Docs', params: { slug: docs.value[0].slug } })
  }
})
</script>

<style scoped>
/* Wrap the viewer in the shared bordered page frame (same as Agents/Chat),
   but full-height with internal scrolling rather than a scrolling shell. */
.docs-shell {
  background: transparent;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.docs-frame {
  height: min(calc(100vh - 28px), 100%);
  min-height: 0;
  overflow: hidden;
}

.docs-page {
  position: relative;
  z-index: 1;
  display: flex;
  height: 100%;
  overflow: hidden;
}

.docs-sidebar {
  width: 248px;
  flex-shrink: 0;
  border-right: 1px solid var(--mc-border-light);
  overflow-y: auto;
  padding: 18px 10px 24px;
}

.docs-sidebar__title {
  font-size: 12px;
  font-weight: 700;
  color: var(--mc-text-secondary);
  padding: 2px 12px 14px;
  letter-spacing: 0.02em;
}

.docs-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.docs-nav__group {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.docs-nav__group + .docs-nav__group {
  margin-top: 16px;
}

.docs-nav__group-title {
  font-size: 11px;
  font-weight: 700;
  color: var(--mc-text-tertiary);
  padding: 4px 12px 6px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.docs-nav__item {
  position: relative;
  text-align: left;
  border: none;
  background: transparent;
  color: var(--mc-text-secondary);
  padding: 8px 12px;
  border-radius: var(--mc-radius-md);
  font-size: 13.5px;
  line-height: 1.45;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.docs-nav__item:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
}

.docs-nav__item--active,
.docs-nav__item--active:hover {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  font-weight: 600;
  box-shadow: inset 0 0 0 1px rgba(217, 109, 70, 0.1);
}

.docs-content {
  flex: 1;
  overflow-y: auto;
  padding: 36px 56px 80px;
}

.docs-article {
  max-width: 784px;
  margin: 0 auto;
  font-size: 15px;
  line-height: 1.78;
  color: var(--mc-text-secondary);
}

/* —— 文档阅读排版：scoped :deep 仅作用于文档页，不影响聊天共用的 .markdown-body —— */
.docs-article :deep(h1) {
  font-size: 1.9em;
  font-weight: 800;
  letter-spacing: -0.01em;
  line-height: 1.25;
  color: var(--mc-text-primary);
  margin: 0 0 20px;
}
.docs-article :deep(h2) {
  font-size: 1.42em;
  font-weight: 700;
  line-height: 1.35;
  color: var(--mc-text-primary);
  margin: 42px 0 14px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--mc-border-light);
  scroll-margin-top: 16px;
}
.docs-article :deep(h3) {
  font-size: 1.18em;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 28px 0 10px;
  scroll-margin-top: 16px;
}
.docs-article :deep(h4) {
  font-size: 1.02em;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 22px 0 8px;
}
.docs-article :deep(h1:first-child),
.docs-article :deep(h2:first-child),
.docs-article :deep(h3:first-child) {
  margin-top: 0;
}
.docs-article :deep(p) {
  margin: 14px 0;
}
.docs-article :deep(ul),
.docs-article :deep(ol) {
  margin: 14px 0;
  padding-left: 1.5em;
}
.docs-article :deep(li) {
  margin: 6px 0;
}
.docs-article :deep(li > ul),
.docs-article :deep(li > ol) {
  margin: 6px 0;
}
.docs-article :deep(a) {
  color: var(--mc-primary);
  font-weight: 500;
  text-decoration: none;
}
.docs-article :deep(a:hover) {
  color: var(--mc-primary-hover);
  text-decoration: underline;
}
.docs-article :deep(strong) {
  color: var(--mc-text-primary);
  font-weight: 700;
}
.docs-article :deep(hr) {
  border: none;
  border-top: 1px solid var(--mc-border-light);
  margin: 36px 0;
}
.docs-article :deep(blockquote) {
  margin: 18px 0;
  padding: 4px 16px;
  border-left: 3px solid var(--mc-primary);
  background: var(--mc-bg-muted);
  border-radius: 0 var(--mc-radius-md) var(--mc-radius-md) 0;
  color: var(--mc-text-secondary);
}
.docs-article :deep(pre) {
  margin: 18px 0;
  border: 1px solid var(--mc-border-light);
}
.docs-article :deep(table) {
  margin: 18px 0;
  font-size: 0.95em;
}
.docs-article :deep(img) {
  max-width: 100%;
  border-radius: var(--mc-radius-md);
}

@media (max-width: 768px) {
  .docs-content {
    padding: 24px 20px 64px;
  }
}

.docs-state {
  color: var(--mc-text-secondary);
  padding: 40px;
  text-align: center;
}

.docs-state--error {
  color: var(--mc-danger);
}
</style>
