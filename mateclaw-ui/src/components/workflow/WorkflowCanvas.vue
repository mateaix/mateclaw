<template>
  <div class="workflow-canvas" :class="{ fullscreen }" :data-canvas-id="canvasId">
    <div class="canvas-toolbar">
      <div class="canvas-toolbar-group">
        <!-- Add-node picker — inserts a fresh step after the currently
             selected node (or at the end when nothing is selected). -->
        <label class="canvas-add-picker">
          <span class="visually-hidden">{{ t('workflows.canvas.addNode') }}</span>
          <select :value="''" class="canvas-add-select" @change="onAddNode">
            <option value="" disabled>{{ t('workflows.canvas.addNode') }}</option>
            <option value="sequential">+ {{ modeOptionLabel('sequential') }}</option>
            <option value="fan_out">+ {{ modeOptionLabel('fan_out') }}</option>
            <option value="collect">+ {{ modeOptionLabel('collect') }}</option>
            <option value="conditional">+ {{ modeOptionLabel('conditional') }}</option>
            <option value="await_approval">+ {{ modeOptionLabel('await_approval') }}</option>
            <option value="dispatch_channel">+ {{ modeOptionLabel('dispatch_channel') }}</option>
            <option value="write_memory">+ {{ modeOptionLabel('write_memory') }}</option>
          </select>
        </label>
        <button class="canvas-btn" :class="{ active: direction === 'LR' }" @click="direction = 'LR'">
          {{ t('workflows.canvas.layoutLR') }}
        </button>
        <button class="canvas-btn" :class="{ active: direction === 'TB' }" @click="direction = 'TB'">
          {{ t('workflows.canvas.layoutTB') }}
        </button>
      </div>
      <div class="canvas-toolbar-group">
        <span class="canvas-stat">{{ t('workflows.canvas.stats', { nodes: graph.nodes.length, edges: graph.edges.length }) }}</span>
        <span v-if="graph.parseError" class="canvas-stat err">{{ t('workflows.canvas.parseError', { msg: graph.parseError }) }}</span>
        <button
          class="canvas-btn icon-btn"
          :title="fullscreen ? t('workflows.canvas.fullscreenExit') : t('workflows.canvas.fullscreenEnter')"
          @click="toggleFullscreen"
        >
          <svg v-if="!fullscreen" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 9V3h6"/><path d="M21 9V3h-6"/><path d="M3 15v6h6"/><path d="M21 15v6h-6"/>
          </svg>
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M9 3v6H3"/><path d="M15 3v6h6"/><path d="M9 21v-6H3"/><path d="M15 21v-6h6"/>
          </svg>
        </button>
      </div>
    </div>

    <div v-if="graph.parseError" class="canvas-error">
      <div class="canvas-error-icon">⚠</div>
      <div class="canvas-error-msg">{{ t('workflows.canvas.parseError', { msg: graph.parseError }) }}</div>
    </div>

    <div v-else-if="!graph.nodes.length" class="canvas-empty">
      <div class="canvas-empty-illustration">
        <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="3" width="7" height="7" rx="1.5"/>
          <rect x="14" y="3" width="7" height="7" rx="1.5"/>
          <rect x="3" y="14" width="7" height="7" rx="1.5"/>
          <rect x="14" y="14" width="7" height="7" rx="1.5"/>
          <path d="M10 6.5h4"/>
          <path d="M10 17.5h4"/>
        </svg>
      </div>
      <div class="canvas-empty-text">{{ t('workflows.canvas.empty') }}</div>
    </div>

    <VueFlow
      v-else
      :id="canvasId"
      class="canvas-flow"
      :nodes="graph.nodes"
      :edges="graph.edges"
      :node-types="nodeTypes"
      :nodes-draggable="false"
      :nodes-connectable="false"
      :elements-selectable="true"
      :pan-on-drag="true"
      :zoom-on-scroll="true"
      :min-zoom="0.4"
      :max-zoom="1.8"
      :default-edge-options="defaultEdgeOptions"
      fit-view-on-init
      @node-click="handleNodeClick"
      @pane-click="handlePaneClick"
    >
      <Background :pattern-color="dotColor" :gap="20" />
      <Controls show-zoom show-fit-view show-interactive />
      <MiniMap
        :node-color="miniNodeColor"
        :node-stroke-color="miniNodeStrokeColor"
        :node-class-name="(n: any) => 'mini-' + (n?.data?.modeType ?? 'sequential')"
        pannable
        zoomable
      />
    </VueFlow>

    <div v-if="$slots.panel" class="canvas-panel-slot">
      <slot name="panel" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { VueFlow, useVueFlow, type Node } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import StepNode from './StepNode.vue'
import { useWorkflowGraph, type StepNodeData } from '@/composables/useWorkflowGraph'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

interface Props {
  /** Workflow draft JSON — the source of truth. Canvas is read-only over it. */
  modelValue: string
  /** Stable id so multiple canvases on the page don't collide in vue-flow's state map. */
  canvasId?: string
}

const props = withDefaults(defineProps<Props>(), { canvasId: 'workflow-canvas' })

const emit = defineEmits<{
  (e: 'select-step', payload: StepNodeData | null): void
  (e: 'insert-step', payload: { afterIndex: number; modeType: string }): void
  /** Toggled by the fullscreen button. The parent uses this to float the
   *  property inspector above the fullscreen overlay (otherwise the panel,
   *  which lives as a flex sibling outside this component, gets hidden
   *  behind the z-index: 2000 fixed canvas). */
  (e: 'update:fullscreen', value: boolean): void
}>()

function onAddNode(e: Event) {
  const target = e.target as HTMLSelectElement
  const modeType = target.value
  if (!modeType) return
  // Reset the select so a re-pick of the same option re-fires the
  // change event. Without this the second click of "+ sequential"
  // would silently no-op.
  target.value = ''
  // The parent reads the current selection from canvasSelection, so
  // we just need to forward the requested mode + a hint about where
  // (-1 means "append at end" when nothing is selected).
  emit('insert-step', { afterIndex: -1, modeType })
}

const { t } = useI18n()

function modeOptionLabel(type: string): string {
  const key = `workflows.canvas.modeLabels.${type}`
  const localized = t(key, '')
  return localized && localized !== key ? localized : type
}

const direction = ref<'LR' | 'TB'>('LR')
const jsonRef = computed(() => props.modelValue)
const graph = useWorkflowGraph(jsonRef, direction)

// Register the custom node component once. markRaw prevents Vue from
// turning the component definition into a deep proxy, which vue-flow
// recommends for performance.
const nodeTypes = {
  step: markRaw(StepNode),
}

// Match the rest of the app's accent.
const defaultEdgeOptions = {
  type: 'smoothstep' as const,
  animated: false,
  style: { stroke: 'var(--mc-edge, #999999)', strokeWidth: 1.5 },
}

const dotColor = 'var(--mc-canvas-dot, rgba(0, 0, 0, 0.16))'
const miniNodeColor = (node: Node<StepNodeData>) => {
  switch (node.data?.modeType) {
    case 'fan_out': return '#b388ff'
    case 'collect': return '#7e57c2'
    case 'conditional': return '#ff8f3f'
    case 'await_approval': return '#ffb84d'
    case 'dispatch_channel': return '#2ecc71'
    case 'write_memory': return '#1abc9c'
    default: return '#4084ff'
  }
}
const miniNodeStrokeColor = () => 'transparent'

// Three independent paths for getting a node click out of vue-flow into
// the parent's inspector — at least one always works regardless of how
// the click lands inside vue-flow's internal DOM.
//
//  1. `useVueFlow().onNodeClick` — the official subscription API tied
//     to the flow instance keyed by props.canvasId.
//  2. `provide('selectStepCallback', ...)` — the StepNode injects this
//     and fires it on its own @click. Survives vue-flow re-renders.
//  3. The template `@node-click` attribute below — kept as a third
//     fallback for completeness.
function selectStep(data: StepNodeData | null) {
  emit('select-step', data)
}
provide('selectStepCallback', selectStep)

const flow = useVueFlow(props.canvasId)
function handleDomStepSelect(event: Event) {
  const detail = (event as CustomEvent<{ canvasId?: string; data?: StepNodeData }>).detail
  if (!detail?.data || detail.canvasId !== props.canvasId) return
  selectStep(detail.data)
}
onMounted(() => {
  window.addEventListener('mateclaw:workflow-step-select', handleDomStepSelect as EventListener)
  flow.onNodeClick((evt) => {
    const data = (evt?.node as Node<StepNodeData> | undefined)?.data
    if (data) selectStep(data)
  })
  flow.onPaneClick(() => selectStep(null))
})

function handleNodeClick(payload: { node: Node<StepNodeData> } | unknown) {
  const node = (payload as { node?: Node<StepNodeData> })?.node
  if (node?.data) selectStep(node.data)
}
function handlePaneClick() {
  selectStep(null)
}

// Reset direction toggle highlight when external code resets the model.
watch(jsonRef, () => {
  /* nothing to do — the graph computed re-runs automatically */
})

// Fullscreen toggle. We use a CSS-driven overlay rather than the
// Fullscreen API so the rest of the page (sidebar utilities, app
// header) stays styled consistently and the operator can still drag a
// dialog over the canvas if needed.
const fullscreen = ref(false)
function toggleFullscreen() {
  fullscreen.value = !fullscreen.value
}
function handleEsc(e: KeyboardEvent) {
  if (e.key === 'Escape' && fullscreen.value) fullscreen.value = false
}
watch(fullscreen, (on) => {
  if (on) {
    document.addEventListener('keydown', handleEsc)
    // Lock page scroll behind the overlay.
    document.body.style.overflow = 'hidden'
    // The settings layout's mc-page-frame uses backdrop-filter, which
    // promotes it to a containing block for position:fixed descendants
    // (CSS containment spec). That makes our `inset: 0` anchor to the
    // frame instead of the viewport, leaving the canvas pinned to the
    // top-left of the page rather than truly fullscreen. Suppress the
    // filter while fullscreen is active — the frames sit behind the
    // overlay anyway, so dropping the blur has no visible cost.
    document.body.classList.add('workflow-canvas-fullscreen')
  } else {
    document.removeEventListener('keydown', handleEsc)
    document.body.style.overflow = ''
    document.body.classList.remove('workflow-canvas-fullscreen')
  }
  emit('update:fullscreen', on)
})
onBeforeUnmount(() => {
  window.removeEventListener('mateclaw:workflow-step-select', handleDomStepSelect as EventListener)
  document.removeEventListener('keydown', handleEsc)
  document.body.style.overflow = ''
  // Unmounting while in fullscreen (route change, panel close) must
  // restore the page frame's backdrop blur — otherwise we leave the
  // rest of the app permanently transparent.
  document.body.classList.remove('workflow-canvas-fullscreen')
})

</script>

<style scoped>
.workflow-canvas {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  min-height: 320px;
  flex: 1;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.1));
  border-radius: 8px;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.02));
  color: var(--mc-text-primary, inherit);
  overflow: hidden;
}
.workflow-canvas.fullscreen {
  position: fixed;
  inset: 0;
  z-index: 2000;
  border-radius: 0;
  border: none;
  min-height: 100vh;
  background: var(--mc-bg, #ffffff);
}
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  padding: 0;
  height: 26px;
}
.canvas-add-picker {
  display: inline-flex;
  align-items: center;
}
.canvas-add-select {
  padding: 4px 8px;
  border-radius: 6px;
  border: 1px solid var(--mc-primary, #4084ff);
  background: var(--mc-primary-bg, rgba(64, 132, 255, 0.14));
  color: var(--mc-primary, #4084ff);
  font-size: 12px;
  cursor: pointer;
  font-weight: 500;
}
.canvas-add-select:hover {
  background: var(--mc-primary, #4084ff);
  color: var(--mc-text-inverse, #ffffff);
}
.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.canvas-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
  font-size: 12px;
  background: var(--mc-bg-elevated, transparent);
}
.canvas-toolbar-group {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.canvas-btn {
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  background: transparent;
  color: inherit;
  font-size: 12px;
  cursor: pointer;
}
.canvas-btn.active {
  background: var(--mc-primary, #4084ff);
  border-color: var(--mc-primary, #4084ff);
  color: #ffffff;
}
.canvas-stat {
  opacity: 0.75;
}
.canvas-stat.err {
  color: #c0392b;
  font-family: 'JetBrains Mono', Consolas, monospace;
}
.canvas-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 13px;
  padding: 32px 24px;
  color: var(--mc-text-secondary, #666);
  background: var(--mc-bg-sunken, transparent);
  min-height: 280px;
}
.canvas-empty-illustration {
  color: var(--mc-text-tertiary, #999);
  opacity: 0.85;
}
.canvas-empty-text {
  text-align: center;
  max-width: 320px;
  line-height: 1.5;
}
.canvas-error {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 32px 24px;
  background: var(--mc-danger-bg, rgba(255, 80, 80, 0.08));
  color: var(--mc-danger, #c0392b);
  min-height: 280px;
}
.canvas-error-icon {
  font-size: 36px;
}
.canvas-error-msg {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  text-align: center;
  max-width: 480px;
  word-break: break-word;
}
.canvas-flow {
  flex: 1;
  width: 100%;
  height: 100%;
  min-height: 280px;
}
.canvas-flow :deep(.vue-flow__edge-path) {
  stroke: var(--mc-edge, rgba(74, 85, 104, 0.78));
  stroke-width: 2;
}
.canvas-flow :deep(.vue-flow__edge-textbg) {
  fill: var(--mc-bg-elevated, #ffffff);
}
.canvas-flow :deep(.vue-flow__edge-text) {
  fill: var(--mc-text-secondary, #666666);
  font-size: 11px;
}
.canvas-panel-slot {
  position: absolute;
  top: 56px;
  right: 14px;
  bottom: 14px;
  width: min(340px, calc(100% - 28px));
  z-index: 12;
  pointer-events: none;
}
.canvas-panel-slot :deep(.step-panel) {
  height: 100%;
  max-height: none;
  pointer-events: auto;
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.18);
}
@media (max-width: 760px) {
  .canvas-panel-slot {
    top: auto;
    left: 10px;
    right: 10px;
    bottom: 10px;
    width: auto;
    height: min(56vh, 420px);
  }
}
</style>
