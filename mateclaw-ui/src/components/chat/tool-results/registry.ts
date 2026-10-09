export { safeHttpUrl, safeChartOption } from './safety'
import { markRaw, type Component } from 'vue'
import ToolResultTable from './ToolResultTable.vue'
import ToolResultProducts from './ToolResultProducts.vue'
import ToolResultChart from './ToolResultChart.vue'

export const MAX_TOOL_UI_BYTES = 100 * 1024
export const MAX_TABLE_ROWS = 200
export const MAX_PRODUCTS = 24
interface Renderer { component: Component; validate: (data: unknown) => boolean }
export interface ResolvedToolBlock { title?: string; component?: Component; data?: unknown; fallback?: string }
const registry = new Map<string, Renderer>()
const record = (value: unknown): value is Record<string, any> => !!value && typeof value === 'object' && !Array.isArray(value)

/** Only explicit UI payloads belong below the assistant answer; ordinary MCP data stays in tool details. */
export function hasToolResultUi(input: unknown): boolean {
  const ui = record(input) ? input.mateclawUi : undefined
  return record(ui) && ui.version === 1 && Array.isArray(ui.blocks) && ui.blocks.length <= 8
}

/** Registration is limited to trusted application code; payloads only name a renderer. */
export function registerToolResultRenderer(type: string, renderer: Renderer): void {
  registry.set(type, { ...renderer, component: markRaw(renderer.component) })
}

export function resolveToolResult(input: unknown): ResolvedToolBlock[] {
  let json: string
  try { json = JSON.stringify(input, null, 2) ?? String(input) } catch { return [{ fallback: 'Invalid structured tool result' }] }
  const bounded = (text: string) => text.length > MAX_TOOL_UI_BYTES ? text.slice(0, MAX_TOOL_UI_BYTES) + '\n… [truncated]' : text
  const fallback = () => [{ fallback: bounded(json) }]
  if (new TextEncoder().encode(JSON.stringify(input)).length > MAX_TOOL_UI_BYTES) return fallback()
  const ui = record(input) ? input.mateclawUi : undefined
  if (!hasToolResultUi(input)) return fallback()
  return ui.blocks.map((block: unknown) => {
    if (!record(block)) return { fallback: bounded(JSON.stringify(block, null, 2)) }
    const title = typeof block.title === 'string' ? block.title : undefined
    const renderer = registry.get(block.type)
    try {
      if (renderer && renderer.validate(block.data)) return { title, component: renderer.component, data: block.data, fallback: bounded(JSON.stringify(block, null, 2)) }
    } catch { /* A faulty trusted validator must not interrupt the conversation. */ }
    return { title, fallback: bounded(JSON.stringify(block, null, 2)) }
  })
}

registerToolResultRenderer('table', {
  component: ToolResultTable,
  validate: data => record(data) && Array.isArray(data.columns) && data.columns.length > 0 && data.columns.length <= 32
    && data.columns.every((c: unknown) => record(c) && typeof c.key === 'string' && typeof c.label === 'string')
    && Array.isArray(data.rows) && data.rows.length <= MAX_TABLE_ROWS && data.rows.every(record),
})
registerToolResultRenderer('product-cards', {
  component: ToolResultProducts,
  validate: data => Array.isArray(data) && data.length <= MAX_PRODUCTS && data.every(item => record(item)
    && typeof item.name === 'string'
    && ['url', 'imageUrl', 'platformLabel', 'shopName', 'purchaseAdvice'].every(k => item[k] == null || typeof item[k] === 'string')
    && ['price', 'originalPrice', 'lowestPrice'].every(k => item[k] == null || typeof item[k] === 'string' || typeof item[k] === 'number')),
})
registerToolResultRenderer('echarts', {
  component: ToolResultChart,
  validate: data => record(data) && (record(data.series) || (Array.isArray(data.series) && data.series.length > 0 && data.series.every(record))),
})
