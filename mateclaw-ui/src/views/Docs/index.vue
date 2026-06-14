<template>
  <div class="docs-page">
    <aside class="docs-sidebar">
      <div class="docs-sidebar__title">{{ t('docs.title') }}</div>
      <nav class="docs-nav">
        <button
          v-for="doc in docs"
          :key="doc.slug"
          class="docs-nav__item"
          :class="{ 'docs-nav__item--active': doc.slug === activeSlug }"
          @click="selectDoc(doc.slug)"
        >
          {{ doc.title }}
        </button>
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
.docs-page {
  display: flex;
  height: 100%;
  overflow: hidden;
}

.docs-sidebar {
  width: 240px;
  flex-shrink: 0;
  border-right: 1px solid var(--border-color, #e5e7eb);
  overflow-y: auto;
  padding: 16px 8px;
}

.docs-sidebar__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary, #6b7280);
  padding: 0 12px 8px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.docs-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.docs-nav__item {
  text-align: left;
  border: none;
  background: transparent;
  color: var(--text-primary, #111827);
  padding: 7px 12px;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.12s;
}

.docs-nav__item:hover {
  background: var(--hover-bg, #f3f4f6);
}

.docs-nav__item--active {
  background: var(--active-bg, #eef2ff);
  color: var(--primary-color, #4f46e5);
  font-weight: 600;
}

.docs-content {
  flex: 1;
  overflow-y: auto;
  padding: 28px 40px;
}

.docs-article {
  max-width: 860px;
  margin: 0 auto;
}

.docs-state {
  color: var(--text-secondary, #6b7280);
  padding: 40px;
  text-align: center;
}

.docs-state--error {
  color: var(--danger-color, #dc2626);
}
</style>
