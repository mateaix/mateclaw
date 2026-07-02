<template>
  <div ref="graphViewEl" class="graph-view" :class="{ 'graph-view--fullscreen': isFullscreen }">
    <!-- Toolbar sub-component -->
    <WikiGraphToolbar
      :node-count="graphMode === 'entities' ? entityStats.nodes : nodes.length"
      :edge-count="graphMode === 'entities' ? entityStats.edges : edges.length"
      :orphan-count="orphanCount"
      v-model:show-orphans="showOrphans"
      v-model:type-filter="typeFilter"
      v-model:mode="graphMode"
      :available-types="availableTypes"
      :is-fullscreen="isFullscreen"
      @reset="resetChart"
      @toggle-fullscreen="toggleFullscreen"
    />

    <!-- Entity-level knowledge graph -->
    <WikiEntityGraphView
      v-if="graphMode === 'entities'"
      :kb-id="kbId"
      :is-fullscreen="isFullscreen"
      @open-page="emit('open-page', $event)"
      @stats="entityStats = $event"
    />

    <!-- Page-level graph (ECharts canvas) -->
    <div v-show="graphMode === 'pages'" class="graph-canvas-wrap">
      <!-- Find a node by name: highlights it (dimming the rest) and opens its panel -->
      <GraphNodeSearch
        v-if="nodes.length"
        class="graph-search"
        :nodes="pageSearchNodes"
        @focus="focusPageNode"
        @clear="clearHighlight"
      />
      <div ref="chartEl" class="graph-canvas" />
    </div>

    <!-- Node detail panel sub-component -->
    <WikiGraphNodePanel
      v-if="graphMode === 'pages' && selectedNode"
      :page="selectedNode"
      :linked-pages="selectedNodeLinks"
      @close="selectedNode = null"
      @open-page="emit('open-page', $event)"
    />

    <!-- Empty state -->
    <div v-if="graphMode === 'pages' && nodes.length === 0" class="graph-empty">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
        <circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="3"/>
        <line x1="5" y1="5" x2="19" y2="19" stroke-width="0.5"/>
      </svg>
      <p>{{ t('wiki.graph.empty') }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { WikiPage } from '@/stores/useWikiStore'
import { useWikiPageType } from '@/composables/useWikiPageType'
import WikiGraphToolbar from './WikiGraphToolbar.vue'
import WikiGraphNodePanel from './WikiGraphNodePanel.vue'
import WikiEntityGraphView from './WikiEntityGraphView.vue'
import GraphNodeSearch from './GraphNodeSearch.vue'

echarts.use([GraphChart, TooltipComponent, LegendComponent, CanvasRenderer])

const { t } = useI18n()
const { typeColor, formatPageTypeLabel } = useWikiPageType()
const props = defineProps<{ pages: WikiPage[] }>()
const emit = defineEmits<{ (e: 'open-page', slug: string): void }>()

const graphViewEl = ref<HTMLDivElement | null>(null)
const chartEl = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

const isFullscreen = ref(false)
const showOrphans = ref(true)
const typeFilter = ref('')
const selectedNode = ref<WikiPage | null>(null)

// 'pages' = page-link graph (default); 'entities' = named-entity graph.
const graphMode = ref<'pages' | 'entities'>('pages')
const entityStats = ref<{ nodes: number; edges: number }>({ nodes: 0, edges: 0 })

// KB id for entity-graph fetches — every page carries its kbId; keep it a
// string to avoid Snowflake precision loss.
const kbId = computed<string | number | null>(() => {
  const id = props.pages[0]?.kbId
  return id != null ? id : null
})


// Resolve a CSS custom property to its computed value so ECharts (canvas, which
// can't read CSS vars) renders labels in the active light/dark theme color.
function cssVar(name: string, fallback: string): string {
  if (typeof document === 'undefined') return fallback
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

// Parse outgoing links JSON string → slug[]
function parseLinks(outgoingLinks: string | null | undefined): string[] {
  if (!outgoingLinks) return []
  try {
    const arr = JSON.parse(outgoingLinks)
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
}

// Canonical slug: strip hyphens + underscores, lowercase — mirrors Java WikiPageService.canonicalSlug
function canonicalSlug(s: string): string {
  return s.toLowerCase().replace(/-/g, '').replace(/_/g, '')
}

// Map canonical slug → actual page slug (for edge resolution)
const canonicalToSlug = computed(() => {
  const map = new Map<string, string>()
  for (const p of props.pages) map.set(canonicalSlug(p.slug), p.slug)
  return map
})

const slugToPage = computed(() => {
  const map = new Map<string, WikiPage>()
  for (const p of props.pages) map.set(p.slug, p)
  return map
})

// Map page title (lowercased) → slug.
// The backend's extractLinksAsJson runs toSlug() on [[link text]], which keeps Chinese
// characters as-is (e.g. [[八维四十九因]] → "八维四十九因"). Page slugs, however, are
// pinyin (e.g. "bawei-sishijiu-yin"). This map bridges that gap.
const titleToSlug = computed(() => {
  const map = new Map<string, string>()
  for (const p of props.pages) {
    map.set(p.title.toLowerCase(), p.slug)
    // Also index the toSlug() equivalent: strip non-alphanum/non-CJK, lowercase
    const titleSlug = p.title.toLowerCase().replace(/[^\p{Script=Han}a-z0-9\s-]/gu, '').replace(/\s+/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '')
    if (titleSlug) map.set(titleSlug, p.slug)
  }
  return map
})

// Pages filtered by type
const filteredPages = computed(() => {
  if (!typeFilter.value) return props.pages
  return props.pages.filter(p => (p.pageType || 'other').toLowerCase() === typeFilter.value)
})

// Resolve a link token to a real page slug. Resolution order:
// 1. Direct slug match
// 2. Canonical slug match (handles pinyin segmentation differences)
// 3. Title match (for [[中文标题]] style links stored by extractLinksAsJson)
function resolveLink(link: string): string | null {
  if (!link) return null
  // 1. Direct slug match
  if (slugToPage.value.has(link)) return link
  // 2. Canonical slug match
  const canon = canonicalSlug(link)
  const bySlug = canonicalToSlug.value.get(canon)
  if (bySlug) return bySlug
  // 3. Title-based match (Chinese link text like "八维四十九因" → pinyin slug)
  const byTitle = titleToSlug.value.get(link.toLowerCase())
  if (byTitle) return byTitle
  return null
}

// Build edges from outgoing links with canonical resolution
const edges = computed(() => {
  const filteredSet = new Set(filteredPages.value.map(p => p.slug))
  const result: { source: string; target: string }[] = []
  for (const page of filteredPages.value) {
    for (const rawLink of parseLinks(page.outgoingLinks)) {
      const target = resolveLink(rawLink)
      if (target && target !== page.slug && filteredSet.has(target)) {
        result.push({ source: page.slug, target })
      }
    }
  }
  return result
})

// Compute in-degree for each node
const inDegree = computed(() => {
  const map = new Map<string, number>()
  for (const e of edges.value) {
    map.set(e.target, (map.get(e.target) || 0) + 1)
  }
  return map
})

const orphanCount = computed(() =>
  filteredPages.value.filter(
    p => (inDegree.value.get(p.slug) || 0) === 0 && parseLinks(p.outgoingLinks).length === 0
  ).length
)

const nodes = computed(() => {
  let ps = filteredPages.value
  if (!showOrphans.value) {
    ps = ps.filter(p =>
      (inDegree.value.get(p.slug) || 0) > 0 || parseLinks(p.outgoingLinks).length > 0
    )
  }
  return ps
})

const availableTypes = computed(() => {
  const types = new Set(props.pages.map(p => (p.pageType || 'other').toLowerCase()))
  return [...types].sort()
})

const selectedNodeLinks = computed(() => {
  if (!selectedNode.value) return []
  return parseLinks(selectedNode.value.outgoingLinks)
    .map(link => {
      const slug = resolveLink(link)
      return slug ? slugToPage.value.get(slug) : undefined
    })
    .filter(Boolean) as WikiPage[]
})

// Candidates for the node search box: page title + type label + node color.
const pageSearchNodes = computed(() =>
  nodes.value.map(p => ({
    id: p.slug,
    name: p.title,
    type: formatPageTypeLabel(p.pageType || 'other'),
    color: typeColor(p.pageType),
  })),
)

// Search hit: select the page (opens its panel) and emphasize its node —
// focus:'adjacency' dims the rest so the match is easy to spot.
function focusPageNode(slug: string) {
  const page = slugToPage.value.get(slug)
  if (page) selectedNode.value = page
  if (!chart) return
  const idx = nodes.value.findIndex(p => p.slug === slug)
  chart.dispatchAction({ type: 'downplay', seriesIndex: 0 })
  if (idx >= 0) chart.dispatchAction({ type: 'highlight', seriesIndex: 0, dataIndex: idx })
}

function clearHighlight() {
  chart?.dispatchAction({ type: 'downplay', seriesIndex: 0 })
}

function buildOption() {
  const labelColor = cssVar('--mc-text-secondary', '#665245')
  const nodeSet = new Set(nodes.value.map(p => p.slug))
  const nodeList = nodes.value.map(p => {
    const outDeg = parseLinks(p.outgoingLinks).filter(l => {
      const resolved = resolveLink(l)
      return resolved && resolved !== p.slug && nodeSet.has(resolved)
    }).length
    const deg = (inDegree.value.get(p.slug) || 0) + outDeg
    const size = Math.max(10, Math.min(44, 10 + deg * 4))
    return {
      id: p.slug,
      name: p.title,
      symbolSize: size,
      itemStyle: { color: typeColor(p.pageType) },
      // Show label only for well-connected nodes; position to the right to avoid overlap
      label: {
        show: size > 22,
        position: 'right' as const,
        fontSize: 10,
        color: labelColor,
        distance: 4,
      },
      // Do NOT embed Vue reactive proxies here — ECharts normalizes data and strips them.
      // Use slugToPage lookup in event handlers instead.
    }
  })

  const edgeList = edges.value
    .filter(e => nodeSet.has(e.source) && nodeSet.has(e.target))
    .map(e => ({
      source: e.source,
      target: e.target,
      lineStyle: { color: 'rgba(150,150,150,0.28)', width: 1 },
    }))

  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: (params: any) => {
        if (params.dataType !== 'node') return ''
        // Look up from slugToPage instead of relying on _page in ECharts data
        const page = slugToPage.value.get(params.data.id)
        if (!page) return ''
        const typeLabel = formatPageTypeLabel(page.pageType || 'other')
        const summary = (page.summary || '').substring(0, 80)
        const ellipsis = (page.summary || '').length > 80 ? '…' : ''
        return [
          `<div style="max-width:220px;word-break:break-all;white-space:normal">`,
          `<strong style="display:block;margin-bottom:2px">${page.title}</strong>`,
          `<small style="color:#999;display:block;margin-bottom:4px">${typeLabel}</small>`,
          `<span style="font-size:11px;line-height:1.5;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden">${summary}${ellipsis}</span>`,
          `</div>`,
        ].join('')
      },
    },
    series: [{
      type: 'graph',
      layout: 'force',
      data: nodeList,
      links: edgeList,
      roam: true,
      force: {
        repulsion: 220,
        gravity: 0.06,
        edgeLength: [60, 180],
        friction: 0.55,
      },
      emphasis: {
        focus: 'adjacency',
        lineStyle: { width: 2 },
      },
      lineStyle: { color: 'rgba(150,150,150,0.28)', curveness: 0.08 },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 6,
    }],
  }
}

// Use notMerge:true only for the initial render or explicit reset.
// Incremental updates use notMerge:false so ECharts matches nodes by id,
// keeping existing positions stable and only animating new nodes in.
function renderChart(fullReset = false) {
  if (!chartEl.value) return
  if (!chart) {
    chart = echarts.init(chartEl.value, undefined, { renderer: 'canvas' })
    chart.on('click', (params: any) => {
      if (params.dataType === 'node') {
        // Look up page by slug (node id) — don't rely on _page in ECharts data
        const page = slugToPage.value.get(params.data.id)
        if (page) selectedNode.value = page
      }
    })
    fullReset = true // always full-reset on first init
  }
  chart.setOption(buildOption(), { notMerge: fullReset, lazyUpdate: true })
}

function resetChart() {
  selectedNode.value = null
  renderChart(true)
}

async function toggleFullscreen() {
  if (!document.fullscreenElement) {
    await graphViewEl.value?.requestFullscreen()
  } else {
    await document.exitFullscreen()
  }
}

function onFullscreenChange() {
  isFullscreen.value = !!document.fullscreenElement
  // Let the DOM settle after fullscreen resize, then notify ECharts
  nextTick(() => {
    chart?.resize()
  })
}

// Debounce incremental re-renders to avoid jitter when pages stream in rapidly
let renderTimer: ReturnType<typeof setTimeout> | null = null
function scheduleRender() {
  if (renderTimer) clearTimeout(renderTimer)
  renderTimer = setTimeout(() => {
    renderTimer = null
    renderChart(false)
  }, 300)
}

const resizeObserver = new ResizeObserver(() => {
  chart?.resize()
})

onMounted(async () => {
  await nextTick()
  renderChart(true)
  if (chartEl.value) resizeObserver.observe(chartEl.value)
  document.addEventListener('fullscreenchange', onFullscreenChange)
})

onBeforeUnmount(() => {
  if (renderTimer) clearTimeout(renderTimer)
  resizeObserver.disconnect()
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  // Exit fullscreen if component is unmounted while in fullscreen mode
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
  chart?.dispose()
  chart = null
})

// Single watcher on nodes+edges; the page-length watcher is redundant and removed.
watch([nodes, edges], () => {
  scheduleRender()
})

// Returning to the page graph: the canvas was hidden (v-show), so let the DOM
// settle then tell ECharts to re-measure.
watch(graphMode, (mode) => {
  if (mode === 'pages') {
    nextTick(() => chart?.resize())
  }
})
</script>

<style scoped>
.graph-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  position: relative;
}

/* Native fullscreen: fill the entire screen with proper background */
.graph-view:fullscreen,
.graph-view:-webkit-full-screen {
  background: var(--mc-bg-base, #fff);
  width: 100vw;
  height: 100vh;
}

.dark .graph-view:fullscreen,
.dark .graph-view:-webkit-full-screen {
  background: var(--mc-bg-base, #1a1a1a);
}

.graph-canvas-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
}
.graph-canvas {
  flex: 1;
  min-height: 0;
  width: 100%;
}
/* Search overlay anchored to the canvas top-left, clear of the detail panel. */
.graph-search {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 5;
}

.graph-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--mc-text-tertiary);
  pointer-events: none;
}
.graph-empty p { font-size: 14px; }
</style>
