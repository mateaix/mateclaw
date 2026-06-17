<template>
  <div class="entity-graph">
    <div ref="chartEl" class="graph-canvas" />

    <!-- Entity detail panel -->
    <div v-if="selected" class="entity-panel">
      <button class="entity-panel__close" @click="selected = null">×</button>
      <h3 class="entity-panel__title">{{ selected.canonicalName }}</h3>
      <span class="entity-panel__type" :style="{ background: typeColor(selected.type) }">
        {{ selected.type }}
      </span>
      <span class="entity-panel__count">{{ selected.mentionCount || 0 }} {{ t('wiki.graph.mentions') }}</span>

      <p v-if="selected.description" class="entity-panel__desc">{{ selected.description }}</p>

      <div v-if="selected.aliases && selected.aliases.length" class="entity-panel__section">
        <h4>{{ t('wiki.graph.aliases') }}</h4>
        <div class="entity-panel__tags">
          <span v-for="a in selected.aliases" :key="a" class="entity-panel__tag">{{ a }}</span>
        </div>
      </div>

      <div v-if="egoEdges.length" class="entity-panel__section">
        <h4>{{ t('wiki.graph.relations') }}</h4>
        <ul class="entity-panel__list">
          <li v-for="(r, i) in egoEdges" :key="i">
            <span class="entity-panel__pred">{{ r.predicate }}</span>
            <span class="entity-panel__rel-target">{{ r.label }}</span>
          </li>
        </ul>
      </div>

      <div v-if="egoPages.length" class="entity-panel__section">
        <h4>{{ t('wiki.graph.mentionedIn') }}</h4>
        <ul class="entity-panel__list">
          <li v-for="p in egoPages" :key="p.pageId">
            <a class="entity-panel__link" @click="emit('open-page', p.slug)">{{ p.title }}</a>
          </li>
        </ul>
      </div>
    </div>

    <!-- Empty state -->
    <div v-if="!loading && nodes.length === 0" class="graph-empty">
      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
        <circle cx="7" cy="7" r="3"/><circle cx="17" cy="17" r="3"/><line x1="9.5" y1="9.5" x2="14.5" y2="14.5"/>
      </svg>
      <p>{{ t('wiki.graph.entityEmpty') }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { wikiApi } from '@/api'

echarts.use([GraphChart, TooltipComponent, LegendComponent, CanvasRenderer])

const { t } = useI18n()

interface EntityNode {
  id: number | string
  canonicalName: string
  type: string
  description?: string
  aliases?: string[]
  salience?: number
  mentionCount?: number
}
interface EntityEdge {
  id?: number | string
  subjectEntityId: number | string
  predicate: string
  objectEntityId: number | string
}

const props = defineProps<{ kbId: number | string | null; isFullscreen?: boolean }>()
const emit = defineEmits<{
  (e: 'open-page', slug: string): void
  (e: 'stats', v: { nodes: number; edges: number }): void
}>()

const chartEl = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

const nodes = ref<EntityNode[]>([])
const edges = ref<EntityEdge[]>([])
const loading = ref(false)
const selected = ref<EntityNode | null>(null)
const egoEdges = ref<{ predicate: string; label: string }[]>([])
const egoPages = ref<{ pageId: number | string; slug: string; title: string }[]>([])

// Stable color per entity type via a small hash → palette.
const PALETTE = ['#5b8ff9', '#5ad8a6', '#f6bd16', '#e8684a', '#6dc8ec', '#9270ca', '#ff9d4d', '#269a99']
function typeColor(type: string): string {
  let h = 0
  for (let i = 0; i < (type || '').length; i++) h = (h * 31 + type.charCodeAt(i)) >>> 0
  return PALETTE[h % PALETTE.length]
}

async function load() {
  if (props.kbId == null) return
  loading.value = true
  try {
    // The axios response interceptor returns the raw body for non-enveloped
    // responses, so `res` is already { center, nodes, edges, pages }.
    const res: any = await wikiApi.getEntityGraph(props.kbId, 150)
    nodes.value = res?.nodes || []
    edges.value = res?.edges || []
    emit('stats', { nodes: nodes.value.length, edges: edges.value.length })
    renderChart()
  } finally {
    loading.value = false
  }
}

function buildOption() {
  // Keep IDs as strings throughout — backend issues Snowflake IDs that lose
  // precision if coerced to Number.
  const idSet = new Set(nodes.value.map(n => String(n.id)))
  const nodeList = nodes.value.map(n => {
    const size = Math.max(12, Math.min(46, 12 + (n.mentionCount || 0) * 3))
    return {
      id: String(n.id),
      name: n.canonicalName,
      symbolSize: size,
      itemStyle: { color: typeColor(n.type) },
      label: { show: size > 22, position: 'right' as const, fontSize: 10, color: 'var(--mc-text-secondary)', distance: 4 },
    }
  })
  const edgeList = edges.value
    .filter(e => idSet.has(String(e.subjectEntityId)) && idSet.has(String(e.objectEntityId)))
    .map(e => ({
      source: String(e.subjectEntityId),
      target: String(e.objectEntityId),
      value: e.predicate,
      lineStyle: { color: 'rgba(150,150,150,0.3)', width: 1 },
    }))

  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: (params: any) => {
        if (params.dataType === 'edge') return params.data.value || ''
        const node = nodes.value.find(n => String(n.id) === String(params.data.id))
        if (!node) return ''
        const desc = (node.description || '').substring(0, 80)
        return `<div style="max-width:220px;white-space:normal"><strong>${node.canonicalName}</strong>`
          + `<small style="color:#999;display:block">${node.type}</small>`
          + (desc ? `<span style="font-size:11px">${desc}</span>` : '') + '</div>'
      },
    },
    series: [{
      type: 'graph',
      layout: 'force',
      data: nodeList,
      links: edgeList,
      roam: true,
      force: { repulsion: 240, gravity: 0.05, edgeLength: [70, 200], friction: 0.55 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 2 } },
      lineStyle: { color: 'rgba(150,150,150,0.3)', curveness: 0.1 },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 6,
    }],
  }
}

function renderChart() {
  if (!chartEl.value) return
  if (!chart) {
    chart = echarts.init(chartEl.value, undefined, { renderer: 'canvas' })
    chart.on('click', (params: any) => {
      if (params.dataType === 'node') {
        const node = nodes.value.find(n => String(n.id) === String(params.data.id))
        if (node) openEntity(node)
      }
    })
  }
  chart.setOption(buildOption(), { notMerge: true, lazyUpdate: true })
}

async function openEntity(node: EntityNode) {
  selected.value = node
  egoEdges.value = []
  egoPages.value = []
  if (props.kbId == null) return
  try {
    const res: any = await wikiApi.getEntityEgo(props.kbId, node.id, 50)
    const data = res || {}
    const nodeById = new Map<string, string>()
    for (const n of data.nodes || []) nodeById.set(String(n.id), n.canonicalName)
    nodeById.set(String(node.id), node.canonicalName)
    egoEdges.value = (data.edges || []).map((e: any) => {
      const otherId = String(e.subjectEntityId) === String(node.id) ? e.objectEntityId : e.subjectEntityId
      return { predicate: e.predicate, label: nodeById.get(String(otherId)) || '' }
    })
    egoPages.value = data.pages || []
  } catch {
    /* panel still shows the basic entity info */
  }
}

const resizeObserver = new ResizeObserver(() => chart?.resize())

onMounted(async () => {
  await nextTick()
  if (chartEl.value) resizeObserver.observe(chartEl.value)
  load()
})

onBeforeUnmount(() => {
  resizeObserver.disconnect()
  chart?.dispose()
  chart = null
})

watch(() => props.kbId, () => { selected.value = null; load() })
watch(() => props.isFullscreen, () => nextTick(() => chart?.resize()))

defineExpose({ reload: load })
</script>

<style scoped>
.entity-graph { position: relative; flex: 1; min-height: 0; width: 100%; display: flex; }
.graph-canvas { flex: 1; min-height: 0; width: 100%; }

.graph-empty {
  position: absolute; inset: 0; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 12px;
  color: var(--mc-text-tertiary); pointer-events: none;
}
.graph-empty p { font-size: 14px; max-width: 320px; text-align: center; }

.entity-panel {
  position: absolute; top: 12px; right: 12px; width: 280px; max-height: calc(100% - 24px);
  overflow-y: auto; padding: 16px; border-radius: 10px;
  background: var(--mc-bg-elevated, #fff); border: 1px solid var(--mc-border, #e5e7eb);
  box-shadow: 0 4px 20px rgba(0,0,0,0.12);
}
.entity-panel__close {
  position: absolute; top: 8px; right: 10px; border: none; background: none;
  font-size: 20px; line-height: 1; cursor: pointer; color: var(--mc-text-tertiary);
}
.entity-panel__title { margin: 0 20px 8px 0; font-size: 15px; font-weight: 600; }
.entity-panel__type { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; color: #fff; }
.entity-panel__count { margin-left: 8px; font-size: 11px; color: var(--mc-text-tertiary); }
.entity-panel__desc { margin: 10px 0 0; font-size: 12px; line-height: 1.5; color: var(--mc-text-secondary); }
.entity-panel__section { margin-top: 14px; }
.entity-panel__section h4 { margin: 0 0 6px; font-size: 11px; text-transform: uppercase; color: var(--mc-text-tertiary); }
.entity-panel__tags { display: flex; flex-wrap: wrap; gap: 4px; }
.entity-panel__tag { padding: 1px 6px; border-radius: 6px; font-size: 11px; background: var(--mc-bg-subtle, #f3f4f6); }
.entity-panel__list { margin: 0; padding: 0; list-style: none; font-size: 12px; }
.entity-panel__list li { padding: 2px 0; }
.entity-panel__pred { color: var(--mc-text-tertiary); margin-right: 6px; }
.entity-panel__rel-target { font-weight: 500; }
.entity-panel__link { color: var(--mc-primary, #5b8ff9); cursor: pointer; }
.entity-panel__link:hover { text-decoration: underline; }
</style>
