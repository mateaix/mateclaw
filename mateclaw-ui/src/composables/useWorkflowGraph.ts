import { computed, type Ref } from 'vue'
import { Position, type Edge, type Node } from '@vue-flow/core'
import dagre from 'dagre'

/**
 * Linear `{ steps: [...] }` workflow JSON ↔ vue-flow graph translation.
 *
 * The JSON shape is the source of truth (matches the workflow design's
 * "linear list + mode field" model). The canvas is a derived view, so
 * this composable rebuilds nodes + edges every time the JSON changes.
 *
 * Edge rules (v0 7-mode set):
 *  - sequential / await_approval / dispatch_channel / write_memory:
 *    one inbound edge from the previous "merge point", outbound becomes
 *    the next merge point.
 *  - fan_out: branches off the current merge point. Consecutive fan_out
 *    steps all share the same parent (parallel siblings).
 *  - collect: joins from every fan_out step in the most recent fan_out
 *    group; the join becomes the new merge point.
 *  - conditional: same shape as sequential, but the inbound edge carries
 *    the expression as a label so readers can see the gate at a glance.
 *
 * Layout is done via dagre with a left-to-right rank direction; vue-flow
 * just renders the resulting `{ x, y }` positions. We don't enable
 * draggable nodes — keeping the graph derived from JSON keeps the two
 * representations from drifting.
 */

export interface RawStep {
  name?: string
  // Snowflake IDs lose precision when round-tripped through JS Number, so
  // we treat agentId as opaque (string preferred, number tolerated for
  // legacy JSON written before the string-id convention).
  agentId?: string | number
  agentName?: string
  mode?: { type?: string; expression?: string; [k: string]: unknown }
  promptTemplate?: string
  outputVar?: string
  outputContentType?: string
  [k: string]: unknown
}

export interface RawWorkflow {
  steps?: RawStep[]
}

export interface StepNodeData {
  index: number
  name: string
  modeType: string
  agentId?: string | number
  agentName?: string
  promptTemplate?: string
  expression?: string
  approvalKind?: string
  channels?: string[]
  mergeStrategy?: string
  raw: RawStep
}

const NODE_WIDTH = 220
const NODE_HEIGHT = 88
const RANK_SEP = 80
const NODE_SEP = 40

function fallbackName(idx: number) {
  return `step-${idx + 1}`
}

/**
 * Produce sane node ids even when two steps share a name. The compiler
 * rejects duplicates at publish time, but a draft might have them; we
 * suffix with the index so vue-flow can still render the graph.
 */
function uniqueId(name: string, idx: number, seen: Set<string>): string {
  const base = name && name.trim() ? name.trim() : fallbackName(idx)
  if (!seen.has(base)) {
    seen.add(base)
    return base
  }
  const composite = `${base}__${idx}`
  seen.add(composite)
  return composite
}

export function buildGraph(json: string): { nodes: Node<StepNodeData>[]; edges: Edge[]; parseError: string | null } {
  let parsed: RawWorkflow | null = null
  let parseError: string | null = null
  try {
    parsed = json && json.trim() ? (JSON.parse(json) as RawWorkflow) : { steps: [] }
  } catch (e) {
    parseError = (e as Error).message
    return { nodes: [], edges: [], parseError }
  }
  const steps = Array.isArray(parsed?.steps) ? parsed!.steps! : []
  if (steps.length === 0) {
    return { nodes: [], edges: [], parseError }
  }

  const seen = new Set<string>()
  const nodes: Node<StepNodeData>[] = steps.map((step, idx) => {
    const id = uniqueId(step?.name ?? '', idx, seen)
    const modeType = (step?.mode?.type ?? 'sequential').toString()
    const data: StepNodeData = {
      index: idx,
      name: step?.name?.trim() || fallbackName(idx),
      modeType,
      // Accept both string (post-fix, Snowflake-safe) and number (legacy)
      // forms; the canvas only uses this for display, so we don't normalize.
      agentId: step?.agentId != null && step.agentId !== '' ? step.agentId : undefined,
      agentName: step?.agentName,
      promptTemplate: step?.promptTemplate,
      expression: typeof step?.mode?.expression === 'string' ? step.mode!.expression : undefined,
      approvalKind: typeof step?.mode?.approvalKind === 'string' ? (step.mode!.approvalKind as string) : undefined,
      channels: Array.isArray(step?.mode?.channels) ? (step.mode!.channels as string[]) : undefined,
      mergeStrategy: typeof step?.mode?.mergeStrategy === 'string' ? (step.mode!.mergeStrategy as string) : undefined,
      raw: step ?? {},
    }
    return {
      id,
      type: 'step',
      position: { x: 0, y: 0 }, // placeholder; dagre fills it in
      data,
      // Disable interactions — the JSON is the source of truth.
      draggable: false,
      selectable: true,
      connectable: false,
    }
  })

  // Edge derivation walks the steps once, tracking:
  //   mergePoint: the node id whose output the next non-fan_out step should consume
  //   fanGroup:   the ids of fan_out steps in the most recent open group, ready to be joined by a collect
  const edges: Edge[] = []
  const pushEdge = (source: string, target: string, extra: Partial<Edge> = {}) => {
    edges.push({
      id: `edge-${edges.length + 1}`,
      source,
      target,
      animated: false,
      ...extra,
    })
  }
  let mergePoint: string | null = null
  let fanGroup: string[] = []
  let prevModeWasFanOut = false

  nodes.forEach((node, idx) => {
    const mode = node.data!.modeType
    if (mode === 'fan_out') {
      // Open a new fan_out group on the previous merge point.
      if (!prevModeWasFanOut) fanGroup = []
      fanGroup.push(node.id)
      if (mergePoint) {
        pushEdge(mergePoint, node.id)
      }
      prevModeWasFanOut = true
    } else if (mode === 'collect') {
      // Join every open fan_out branch into this node and reset the
      // merge point to here. If there is no open group (misconfigured
      // draft) we still draw an edge from the prior merge point so the
      // canvas surfaces the orphan visually.
      const sources = fanGroup.length ? fanGroup : (mergePoint ? [mergePoint] : [])
      for (const src of sources) {
        pushEdge(src, node.id)
      }
      fanGroup = []
      mergePoint = node.id
      prevModeWasFanOut = false
    } else {
      // Sequential / conditional / await_approval / dispatch_channel / write_memory
      // all attach to the current merge point and become the next merge point.
      if (mergePoint) {
        const edge: Partial<Edge> = {}
        if (mode === 'conditional' && node.data?.expression) {
          edge.label = `if ${node.data.expression}`
        }
        pushEdge(mergePoint, node.id, edge)
      }
      mergePoint = node.id
      fanGroup = []
      prevModeWasFanOut = false
    }
    // Quietly suppress idx/unused-warning if the runtime drops us here.
    void idx
  })

  return { nodes, edges, parseError }
}

/**
 * Run dagre over the given nodes / edges and write the computed
 * positions back onto the node objects in place. Returns the same
 * arrays so callers can chain.
 */
export function applyDagreLayout(
  nodes: Node<StepNodeData>[],
  edges: Edge[],
  direction: 'LR' | 'TB' = 'LR'
): { nodes: Node<StepNodeData>[]; edges: Edge[] } {
  if (nodes.length === 0) return { nodes, edges }
  const g = new dagre.graphlib.Graph({ compound: false })
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: direction, nodesep: NODE_SEP, ranksep: RANK_SEP })

  for (const n of nodes) {
    g.setNode(n.id, { width: NODE_WIDTH, height: NODE_HEIGHT })
  }
  for (const e of edges) {
    g.setEdge(e.source, e.target)
  }
  dagre.layout(g)

  const laidOut: Node<StepNodeData>[] = nodes.map((n) => {
    const pos = g.node(n.id)
    return {
      ...n,
      // dagre returns the node center; vue-flow expects the top-left
      // corner, so offset by half the dimensions.
      position: { x: pos.x - NODE_WIDTH / 2, y: pos.y - NODE_HEIGHT / 2 },
      sourcePosition: direction === 'LR' ? Position.Right : Position.Bottom,
      targetPosition: direction === 'LR' ? Position.Left : Position.Top,
    }
  })

  return { nodes: laidOut, edges }
}

export function useWorkflowGraph(jsonRef: Ref<string>, direction: Ref<'LR' | 'TB'>) {
  const graph = computed(() => {
    const raw = buildGraph(jsonRef.value)
    if (raw.nodes.length === 0) return raw
    const { nodes, edges } = applyDagreLayout(raw.nodes, raw.edges, direction.value)
    return { nodes, edges, parseError: raw.parseError }
  })
  return graph
}
