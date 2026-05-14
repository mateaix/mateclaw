<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner workflows-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('workflows.kicker') }}</div>
            <h1 class="mc-page-title">{{ t('workflows.title') }}</h1>
            <p class="mc-page-desc">{{ t('workflows.desc') }}</p>
          </div>
          <div class="header-actions">
            <button class="btn-ghost" @click="openGenerate">{{ t('workflows.generate.entryButton') }}</button>
            <button class="btn-primary" @click="openCreate">{{ t('workflows.newWorkflow') }}</button>
          </div>
        </div>

        <div class="workflows-grid">
          <!-- left: list -->
          <aside class="workflows-list">
            <div class="list-header">
              <span>{{ t('workflows.defined', { count: workflows.length }) }}</span>
              <button class="btn-ghost" @click="reload">{{ t('workflows.refresh') }}</button>
            </div>
            <ul class="list-body">
              <li
                v-for="wf in workflows"
                :key="wf.id"
                class="list-row"
                :class="{ active: selectedId === wf.id }"
                @click="select(wf.id)"
              >
                <div class="list-row-name">
                  {{ wf.name || t('workflows.unnamed') }}
                  <span v-if="wf.latestRevisionId" class="badge published">{{ t('workflows.publishedBadge', { rev: wf.latestRevisionId }) }}</span>
                  <span v-else class="badge draft">{{ t('workflows.draftBadge') }}</span>
                </div>
                <div class="list-row-desc">{{ wf.description || '-' }}</div>
              </li>
              <li v-if="!workflows.length" class="list-empty">{{ t('workflows.listEmpty') }}</li>
            </ul>
          </aside>

          <!-- middle: editor -->
          <section class="workflows-editor" v-if="selected">
            <header class="editor-header">
              <input v-model="selected.name" class="editor-name" :placeholder="t('workflows.namePlaceholder')" />
              <input v-model="selected.description" class="editor-desc" :placeholder="t('workflows.descPlaceholder')" />
              <div class="editor-actions">
                <button class="btn-ghost" :disabled="busy" @click="saveMeta">{{ t('workflows.actions.saveMeta') }}</button>
                <button class="btn-ghost" :disabled="busy" @click="saveDraft">{{ t('workflows.actions.saveDraft') }}</button>
                <button class="btn-ghost" :disabled="busy" @click="compile">{{ t('workflows.actions.compile') }}</button>
                <button class="btn-primary" :disabled="busy" @click="publish">{{ t('workflows.actions.publish') }}</button>
                <button class="btn-danger" :disabled="busy" @click="remove">{{ t('workflows.actions.delete') }}</button>
              </div>
            </header>

            <div class="editor-tabs">
              <button class="tab-btn" :class="{ active: editorTab === 'canvas' }" @click="editorTab = 'canvas'">
                {{ t('workflows.tabs.canvas') }}
              </button>
              <button class="tab-btn" :class="{ active: editorTab === 'json' }" @click="editorTab = 'json'">
                {{ t('workflows.tabs.json') }}
              </button>
              <button class="tab-btn" :class="{ active: editorTab === 'runs' }" @click="onSwitchToRuns">
                {{ t('workflows.tabs.runs', { count: runs.length + pausedRuns.length }) }}
              </button>
            </div>

            <div v-if="editorTab === 'canvas'" class="canvas-pane" :class="{ 'canvas-fullscreen': canvasFullscreen }">
              <WorkflowCanvas
                v-model="canvasModel"
                :canvas-id="`wf-${selected.id}`"
                @select-step="onCanvasSelect"
                @insert-step="onInsertStep"
                @update:fullscreen="canvasFullscreen = $event"
              />
              <StepPropertyPanel
                v-if="canvasSelection"
                :step="selectedStep"
                :index="canvasSelection.index"
                :available-agents="availableAgents"
                :available-channels="availableChannels"
                @patch="onStepPatch"
                @duplicate="onStepDuplicate"
                @delete="onStepDelete"
              />
            </div>

            <div v-else-if="editorTab === 'json'" class="json-pane">
              <div class="editor-toolbar">
                <label class="template-picker">
                  <span>{{ t('workflows.templates.label') }}</span>
                  <select v-model="templateChoice" @change="insertTemplate">
                    <option value="">{{ t('workflows.templates.placeholder') }}</option>
                    <option value="sequential">{{ t('workflows.templates.sequential') }}</option>
                    <option value="fan_out">{{ t('workflows.templates.fan_out') }}</option>
                    <option value="collect">{{ t('workflows.templates.collect') }}</option>
                    <option value="conditional">{{ t('workflows.templates.conditional') }}</option>
                    <option value="await_approval">{{ t('workflows.templates.await_approval') }}</option>
                    <option value="dispatch_channel">{{ t('workflows.templates.dispatch_channel') }}</option>
                    <option value="write_memory">{{ t('workflows.templates.write_memory') }}</option>
                  </select>
                </label>
                <span class="json-hint" :class="jsonHintKind">{{ jsonHint }}</span>
              </div>
              <WorkflowJsonEditor
                v-model="draftJson"
                :compile-errors="compileErrors"
                :file-path="`inmemory://workflow-${selected.id}.json`"
              />
            </div>

            <div v-else class="runs-pane">
              <section v-if="pausedRuns.length" class="paused-section">
                <header class="paused-header">
                  <span>{{ t('workflows.paused.header', { count: pausedRuns.length }) }}</span>
                  <button class="btn-ghost" @click="reloadPausedRuns">{{ t('workflows.refresh') }}</button>
                </header>
                <ul class="paused-list">
                  <li v-for="entry in pausedRuns" :key="entry.run.id" class="paused-row">
                    <div class="paused-row-line">
                      <span class="run-state state-paused">paused</span>
                      <span class="paused-run-hash">{{ t('workflows.paused.runHash', { id: entry.run.id }) }}</span>
                    </div>
                    <div class="paused-meta" v-if="entry.pause">
                      <div><span>{{ t('workflows.paused.pauseTokenLabel') }}:</span>
                        <code class="pause-token">{{ truncateToken(entry.pause.pauseToken) }}</code></div>
                      <div v-if="entry.pause.pausedAt">
                        <span>{{ t('workflows.paused.pausedAtLabel') }}:</span> {{ formatTime(entry.pause.pausedAt) }}
                      </div>
                      <div v-if="entry.pause.resumeDeadline">
                        <span>{{ t('workflows.paused.deadlineLabel') }}:</span> {{ formatTime(entry.pause.resumeDeadline) }}
                      </div>
                    </div>
                    <div class="paused-actions" v-if="entry.pause">
                      <button class="resume-btn approve" :disabled="resumingId === entry.run.id"
                              @click="onResume(entry, 'approved')">
                        {{ t('workflows.paused.resumeApproved') }}
                      </button>
                      <button class="resume-btn reject" :disabled="resumingId === entry.run.id"
                              @click="onResume(entry, 'rejected')">
                        {{ t('workflows.paused.resumeRejected') }}
                      </button>
                      <button class="resume-btn neutral" :disabled="resumingId === entry.run.id"
                              @click="onResume(entry, 'timeout')">
                        {{ t('workflows.paused.resumeTimeout') }}
                      </button>
                      <button class="resume-btn neutral" :disabled="resumingId === entry.run.id"
                              @click="onResume(entry, 'cancelled')">
                        {{ t('workflows.paused.resumeCancelled') }}
                      </button>
                    </div>
                  </li>
                </ul>
              </section>

              <header class="runs-header">
                <span>{{ t('workflows.runs.header', { count: runs.length }) }}</span>
                <button class="btn-ghost" @click="reloadRuns">{{ t('workflows.refresh') }}</button>
              </header>
              <ul class="runs-list">
                <li v-for="run in runs" :key="run.id" class="run-row" @click="loadRun(run.id)">
                  <div class="run-row-line">
                    <span class="run-state" :class="'state-' + run.state">{{ run.state }}</span>
                    <span class="run-time">{{ formatTime(run.startedAt) }}</span>
                  </div>
                  <div class="run-row-meta">
                    <span>{{ t('workflows.runs.runHash', { id: run.id }) }}</span>
                    <span v-if="run.triggeredBy">· {{ run.triggeredBy }}</span>
                    <span v-if="run.errorMessage" class="run-err">· {{ run.errorMessage }}</span>
                  </div>
                </li>
                <li v-if="!runs.length" class="runs-empty">{{ t('workflows.runs.empty') }}</li>
              </ul>
              <section v-if="runDetail" class="run-detail">
                <div class="run-detail-title">{{ t('workflows.runs.detailTitle', { id: runDetail.run.id, state: runDetail.run.state }) }}</div>
                <ol class="run-steps">
                  <li v-for="step in runDetail.steps" :key="step.id">
                    <span class="step-state" :class="'state-' + step.state">{{ step.state }}</span>
                    <span class="step-name">{{ step.stepName || t('workflows.unnamed') }}</span>
                    <span v-if="step.durationMs != null" class="step-duration">{{ step.durationMs }} ms</span>
                    <span v-if="step.errorMessage" class="step-err">{{ step.errorMessage }}</span>
                  </li>
                </ol>
              </section>
            </div>

            <div v-if="compileErrors.length" class="errors-panel">
              <div class="errors-title">{{ t('workflows.compileErrorsTitle', { count: compileErrors.length }) }}</div>
              <ul>
                <li v-for="(err, idx) in compileErrors" :key="idx">
                  <code>{{ err.code }}</code>
                  <span class="err-path">@ {{ err.path }}</span>
                  <span class="err-msg">— {{ err.message }}</span>
                </li>
              </ul>
            </div>
            <div v-else-if="lastStatus" class="status-panel" :class="lastStatusKind">
              {{ lastStatus }}
            </div>
          </section>

          <section class="workflows-empty" v-else>
            <p>{{ t('workflows.selectHint') }}</p>
          </section>
        </div>
      </div>
    </div>

    <CreateWorkflowDialog v-model="createDialogOpen" :loading="busy" @submit="onCreateSubmit" />
    <PublishDialog v-model="publishDialogOpen" :loading="busy" @submit="onPublishSubmit" />
    <GenerateWorkflowDialog v-model="generateDialogOpen" @accept="onGenerateAccept" />
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcConfirm } from '@/components/common/useConfirm'
import { ElMessage } from 'element-plus'
import {
  agentApi,
  channelApi,
  workflowApi,
  triggerApi,
  type TriggerSummary,
  type WorkflowSummary,
  type WorkflowRun,
  type WorkflowRunStep,
  type WorkflowCompileError,
  type WorkflowCompileFailure,
  type PausedRunSummary,
  type ResumeOutcome,
  type GeneratedDraft,
} from '@/api'
import type { Channel } from '@/types'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
// Lazy-load the canvas + Monaco editor — each pulls a heavy vendor chunk
// (@vue-flow/* and monaco-editor respectively). Keeping them out of the
// route's main chunk means /settings/workflows starts as ~500 KB instead
// of 4 MB, and the Docker production build no longer needs the
// --max-old-space-size=6144 escape hatch.
const WorkflowCanvas = defineAsyncComponent(
  () => import('@/components/workflow/WorkflowCanvas.vue'),
)
const WorkflowJsonEditor = defineAsyncComponent(
  () => import('@/components/workflow/WorkflowJsonEditor.vue'),
)
import StepPropertyPanel from '@/components/workflow/StepPropertyPanel.vue'
import CreateWorkflowDialog from '@/components/workflow/CreateWorkflowDialog.vue'
import PublishDialog from '@/components/workflow/PublishDialog.vue'
import GenerateWorkflowDialog from '@/components/workflow/GenerateWorkflowDialog.vue'
import type { StepNodeData, RawStep } from '@/composables/useWorkflowGraph'
import {
  readStepAtIndex,
  updateStepAtIndex,
  deleteStepAtIndex,
  duplicateStepAtIndex,
} from '@/composables/useWorkflowDraft'

const { t } = useI18n()
const workspaceStore = useWorkspaceStore()
const workspaceId = computed(() => workspaceStore.currentWorkspaceId)

const workflows = ref<WorkflowSummary[]>([])
const selectedId = ref<number | null>(null)
const selected = ref<WorkflowSummary | null>(null)
const draftJson = ref('')
const compileErrors = ref<WorkflowCompileError[]>([])
const lastStatus = ref('')
const lastStatusKind = ref<'ok' | 'err'>('ok')
const busy = ref(false)

const runs = ref<WorkflowRun[]>([])
const runDetail = ref<{ run: WorkflowRun; steps: WorkflowRunStep[] } | null>(null)
const pausedRuns = ref<PausedRunSummary[]>([])
const resumingId = ref<number | null>(null)

// Workspace agent list — fed to the StepPropertyPanel's agent picker
// so authors stop typing free-form agent names that don't actually
// exist. Loaded once on mount and on workspace switch.
interface AgentOption { id: number; name: string; title?: string }
const availableAgents = ref<AgentOption[]>([])
interface ChannelOption {
  id: number | string
  name: string
  channelType: string
  enabled?: boolean
}
const availableChannels = ref<ChannelOption[]>([])

const templateChoice = ref('')

// View-mode toggle between the canvas (read-only graph derived from
// the JSON) and the raw JSON editor. Canvas is the default — most
// authors visit the page to make sense of an existing flow rather
// than to type fresh JSON.
const editorTab = ref<'canvas' | 'json' | 'runs'>('canvas')

async function onSwitchToRuns() {
  editorTab.value = 'runs'
  await reloadRuns()
  await reloadPausedRuns()
}

// The canvas reads from `draftJson` directly. We keep the model write
// path on the JSON editor only — the canvas is purely a derived view
// to avoid two sources of truth drifting apart.
const canvasModel = computed({
  get: () => draftJson.value,
  set: (v: string) => { draftJson.value = v },
})

const canvasSelection = ref<StepNodeData | null>(null)
// Mirrors the WorkflowCanvas's internal fullscreen flag so we can float
// the property inspector above the fixed-position overlay. Without this,
// clicking a node in fullscreen would emit a selection but the panel —
// rendered as a flex sibling outside the canvas — sits underneath the
// z-index: 2000 overlay and reads as "panel doesn't open".
const canvasFullscreen = ref(false)
function onCanvasSelect(payload: StepNodeData | null) {
  canvasSelection.value = payload
}
// The currently-selected step, re-resolved from the JSON whenever the
// JSON changes — this is what keeps the property panel in sync after
// the user edits a field. Falls back to the StepNodeData snapshot the
// canvas captured if the JSON parse fails (e.g. mid-edit invalid JSON).
const selectedStep = computed<RawStep | null>(() => {
  const sel = canvasSelection.value
  if (!sel) return null
  const live = readStepAtIndex(draftJson.value, sel.index)
  return live ?? (sel.raw as RawStep)
})

function onStepPatch(payload: { index: number; patch: Partial<RawStep> }) {
  draftJson.value = updateStepAtIndex(draftJson.value, payload.index, payload.patch)
  // Keep the selection alive so the panel doesn't blink between renders.
  // The re-derived selectedStep computed will pick up the new fields.
}

function onStepDuplicate(payload: { index: number }) {
  draftJson.value = duplicateStepAtIndex(draftJson.value, payload.index)
}

function onStepDelete(payload: { index: number }) {
  draftJson.value = deleteStepAtIndex(draftJson.value, payload.index)
  canvasSelection.value = null
}

const createDialogOpen = ref(false)
const publishDialogOpen = ref(false)
const generateDialogOpen = ref(false)

function openGenerate() {
  if (!workspaceId.value) return
  generateDialogOpen.value = true
}

async function onGenerateAccept(draft: GeneratedDraft) {
  if (!workspaceId.value) return
  busy.value = true
  try {
    // Create a workflow row + save the generated draft, then jump
    // straight into the canvas so the operator can finish the TODOs.
    const res = await workflowApi.create({
      workspaceId: workspaceId.value,
      name: draft.name,
      description: draft.description || undefined,
      enabled: true,
    })
    const created = res.data as unknown as WorkflowSummary
    if (!created?.id) {
      ElMessage.error(t('workflows.generate.failed', { msg: 'workflow row not returned' }))
      return
    }
    await workflowApi.saveDraft(created.id, draft.draftJson)

    // Persist suggested trigger drafts. The generator returned them with
    // enabled=false and (typically) targetId placeholders — bind each to
    // the new workflow id and persist as a real trigger row. Operators
    // see them appear in the Triggers list and turn them on after they
    // finish the workflow's TODOs.
    let triggersCreated = 0
    let triggersSkipped = 0
    if (Array.isArray(draft.triggerDrafts)) {
      for (const td of draft.triggerDrafts) {
        try {
          await triggerApi.create({
            workspaceId: workspaceId.value,
            name: typeof td.name === 'string' ? td.name : `from-${draft.name}`,
            patternType: typeof td.patternType === 'string' ? td.patternType : '',
            // patternJson can come from the LLM as either a string or
            // a nested object — normalise to the string the API wants.
            patternJson: typeof td.patternJson === 'string'
                ? td.patternJson
                : JSON.stringify(td.patternJson ?? {}),
            targetType: 'workflow',
            // Bind to the just-created workflow regardless of what the
            // generator put in targetId (it usually inserted a TODO_*).
            targetId: created.id,
            enabled: false,
            payloadTemplate: typeof td.payloadTemplate === 'string'
                ? td.payloadTemplate : undefined,
          } as unknown as TriggerSummary)
          triggersCreated++
        } catch (e) {
          console.warn('triggerDraft create failed', td, e)
          triggersSkipped++
        }
      }
    }

    await reload()
    await select(created.id)
    // If the generator's preview compile failed, surface the errors
    // inline so the operator sees them immediately on first load.
    if (!draft.compileOk && draft.compileErrors.length) {
      compileErrors.value = draft.compileErrors
    }
    if (triggersCreated > 0 && triggersSkipped === 0) {
      ElMessage.success(t('workflows.generate.acceptedWithTriggers', { count: triggersCreated }))
    } else if (triggersSkipped > 0) {
      ElMessage.warning(t('workflows.generate.acceptedSomeTriggersFailed',
          { ok: triggersCreated, fail: triggersSkipped }))
    } else {
      ElMessage.success(t('workflows.generate.compileOk'))
    }
  } catch (e) {
    ElMessage.error(t('workflows.generate.failed', { msg: (e as Error).message }))
  } finally {
    busy.value = false
  }
}

// Live JSON-syntax check on the textarea so the operator sees parse errors
// immediately, instead of waiting for compile to round-trip.
const jsonHint = computed(() => {
  if (!draftJson.value.trim()) return ''
  try {
    JSON.parse(draftJson.value)
    return t('workflows.jsonOk')
  } catch (e) {
    return t('workflows.jsonInvalid', { msg: (e as Error).message })
  }
})
const jsonHintKind = computed(() => (jsonHint.value === t('workflows.jsonOk') ? 'ok' : 'err'))

const STEP_TEMPLATES: Record<string, object> = {
  sequential: {
    name: 'step-sequential',
    mode: { type: 'sequential' },
    promptTemplate: 'Process: {{ inputs.payload }}',
  },
  fan_out: {
    name: 'step-fan',
    mode: { type: 'fan_out' },
    promptTemplate: 'Branch task',
  },
  collect: {
    name: 'step-collect',
    mode: { type: 'collect' },
  },
  conditional: {
    name: 'step-conditional',
    mode: { type: 'conditional', expression: '{{ inputs.payload != null }}' },
    promptTemplate: 'Run only when condition holds',
  },
  await_approval: {
    name: 'step-approval',
    mode: {
      type: 'await_approval',
      approvalKind: 'manual',
      // approverChannels is required by the schema validator. Default
      // to ['web'] — the operator UI surface — so an inserted template
      // compiles right away. Authors can swap to feishu / dingtalk /
      // wecom etc. once they wire those channels.
      approverChannels: ['web'],
      approvalMessage: 'Please review and approve',
      timeoutSecs: 3600,
    },
  },
  dispatch_channel: {
    name: 'step-dispatch',
    mode: {
      type: 'dispatch_channel',
      channels: [],
      targets: {},
      content: 'Notification: {{ inputs.payload }}',
    },
  },
  write_memory: {
    name: 'step-memory',
    mode: {
      type: 'write_memory',
      employeeId: 'employee-id',
      file: 'workspace.md',
      mergeStrategy: 'append',
      content: '{{ inputs.payload }}',
    },
  },
}

function insertTemplate() {
  const choice = templateChoice.value
  if (!choice) return
  appendStepTemplate(choice)
  templateChoice.value = ''
}

/**
 * Insert a step from the canvas toolbar's "+ add node" picker. When a
 * canvas node is selected we splice the new step in right after it; on
 * an empty selection we append at the end. The same STEP_TEMPLATES
 * skeletons the JSON-tab dropdown uses keep the two entry points
 * consistent.
 */
function onInsertStep(payload: { afterIndex: number; modeType: string }) {
  const stepBlock = STEP_TEMPLATES[payload.modeType]
  if (!stepBlock) return
  // The canvas hands us afterIndex=-1 because it doesn't know about
  // canvasSelection on this side. Resolve "after the currently selected
  // step" here so the toolbar UX feels consistent with the inspector.
  const selIndex = canvasSelection.value?.index ?? -1
  let next: string
  try {
    const parsed = JSON.parse(draftJson.value || '{}') as { steps?: unknown[] }
    if (!Array.isArray(parsed.steps)) parsed.steps = []
    const insertAt = selIndex >= 0 && selIndex < parsed.steps.length
        ? selIndex + 1
        : parsed.steps.length
    parsed.steps.splice(insertAt, 0, stepBlock)
    next = JSON.stringify(parsed, null, 2)
  } catch {
    next = JSON.stringify({ steps: [stepBlock] }, null, 2)
  }
  draftJson.value = next
}

function appendStepTemplate(choice: string) {
  const stepBlock = STEP_TEMPLATES[choice]
  if (!stepBlock) return
  let next: string
  try {
    const parsed = JSON.parse(draftJson.value || '{}') as { steps?: unknown[] }
    if (!Array.isArray(parsed.steps)) parsed.steps = []
    parsed.steps.push(stepBlock)
    next = JSON.stringify(parsed, null, 2)
  } catch {
    next = JSON.stringify({ steps: [stepBlock] }, null, 2)
  }
  draftJson.value = next
}

async function reload() {
  if (!workspaceId.value) return
  try {
    const res = await workflowApi.list(workspaceId.value)
    workflows.value = (res.data as unknown as WorkflowSummary[]) ?? []
  } catch (e) {
    console.error('listWorkflows failed', e)
  }
}

async function select(id: number) {
  selectedId.value = id
  try {
    const res = await workflowApi.get(id)
    selected.value = res.data as unknown as WorkflowSummary
    draftJson.value = selected.value?.draftJson ?? ''
    compileErrors.value = []
    lastStatus.value = ''
    await reloadRuns()
  } catch (e) {
    console.error('getWorkflow failed', e)
  }
}

async function reloadRuns() {
  if (!selectedId.value) return
  try {
    const res = await workflowApi.runs(selectedId.value, 50)
    runs.value = (res.data as unknown as WorkflowRun[]) ?? []
  } catch (e) {
    console.error('listRuns failed', e)
  }
  // Paused-run list spans the whole workspace, not just the selected
  // workflow — operators usually want the global queue when they reach
  // for "what's blocked right now". Refresh on every workflow switch
  // so the count accurately reflects the post-publish state.
  await reloadPausedRuns()
}

async function reloadPausedRuns() {
  try {
    const res = await workflowApi.listPausedRuns(50)
    pausedRuns.value = (res.data as unknown as PausedRunSummary[]) ?? []
  } catch (e) {
    console.error('listPausedRuns failed', e)
  }
}

async function reloadAgents() {
  try {
    const res = await agentApi.list()
    const rows = (res.data as unknown as AgentOption[]) ?? []
    // The agent list endpoint already scopes to the caller's workspace
    // via the X-Workspace-Id header, so no client-side filter is needed.
    availableAgents.value = rows
        .filter((a) => a && a.name)
        .map((a) => ({ id: a.id, name: a.name, title: a.title }))
  } catch (e) {
    console.error('listAgents failed', e)
  }
}

async function reloadChannels() {
  try {
    const res = await channelApi.list()
    const rows = (res.data as unknown as Channel[]) ?? []
    availableChannels.value = rows
      .filter((c) => c && c.channelType && c.enabled !== false)
      .map((c) => ({
        id: c.id,
        name: c.name || c.channelType,
        channelType: c.channelType,
        enabled: c.enabled,
      }))
  } catch (e) {
    console.error('listChannels failed', e)
  }
}

function truncateToken(token: string | undefined): string {
  if (!token) return '-'
  return token.length <= 14 ? token : token.slice(0, 6) + '…' + token.slice(-4)
}

async function onResume(entry: PausedRunSummary, outcome: ResumeOutcome) {
  if (!entry.pause?.pauseToken) return
  resumingId.value = entry.run.id
  try {
    await workflowApi.resumeRun(entry.run.id, entry.pause.pauseToken, outcome)
    ElMessage.success(t('workflows.paused.resumeOk', { outcome }))
    await reloadPausedRuns()
    await reloadRuns()
  } catch (e) {
    ElMessage.error(t('workflows.paused.resumeFailed', { msg: (e as Error).message }))
  } finally {
    resumingId.value = null
  }
}

async function loadRun(runId: number) {
  try {
    const res = await workflowApi.runDetail(runId)
    runDetail.value = res.data as unknown as { run: WorkflowRun; steps: WorkflowRunStep[] }
  } catch (e) {
    console.error('getRun failed', e)
  }
}

function openCreate() {
  if (!workspaceId.value) return
  createDialogOpen.value = true
}

async function onCreateSubmit(payload: { name: string; description: string }) {
  if (!workspaceId.value) return
  busy.value = true
  try {
    const res = await workflowApi.create({
      workspaceId: workspaceId.value,
      name: payload.name,
      description: payload.description || undefined,
      enabled: true,
    })
    const created = res.data as unknown as WorkflowSummary
    createDialogOpen.value = false
    await reload()
    if (created?.id) {
      await select(created.id)
      // Seed a starter step so the canvas renders something the user
      // can immediately edit, instead of opening on a blank slate.
      // The compile button also has something real to validate.
      if (!draftJson.value || !draftJson.value.trim()) {
        draftJson.value = JSON.stringify({
          steps: [
            {
              name: 'first-step',
              mode: { type: 'sequential' },
              promptTemplate: 'Hello {{ inputs.payload }}',
            },
          ],
        }, null, 2)
        try {
          await workflowApi.saveDraft(created.id, draftJson.value)
        } catch (e) {
          // Non-fatal: the user can still hit Save Draft manually.
          console.warn('seed draft save failed', e)
        }
      }
    }
  } catch (e) {
    setStatus(t('workflows.status.createFailed', { msg: (e as Error).message }), 'err')
  } finally {
    busy.value = false
  }
}

async function saveMeta() {
  if (!selected.value) return
  busy.value = true
  try {
    await workflowApi.update(selected.value.id, {
      name: selected.value.name,
      description: selected.value.description,
      enabled: selected.value.enabled,
    })
    setStatus(t('workflows.status.metaSaved'), 'ok')
    await reload()
  } catch (e) {
    setStatus(t('workflows.status.saveFailed', { msg: (e as Error).message }), 'err')
  } finally {
    busy.value = false
  }
}

async function saveDraft() {
  if (!selected.value) return
  busy.value = true
  try {
    await workflowApi.saveDraft(selected.value.id, draftJson.value)
    setStatus(t('workflows.status.draftSaved'), 'ok')
  } catch (e) {
    setStatus(t('workflows.status.saveDraftFailed', { msg: (e as Error).message }), 'err')
  } finally {
    busy.value = false
  }
}

async function compile() {
  if (!selected.value) return
  busy.value = true
  compileErrors.value = []
  try {
    await workflowApi.saveDraft(selected.value.id, draftJson.value)
    await workflowApi.compile(selected.value.id)
    setStatus(t('workflows.status.compileOk'), 'ok')
  } catch (e) {
    handleCompileError(e)
  } finally {
    busy.value = false
  }
}

function publish() {
  if (!selected.value) return
  publishDialogOpen.value = true
}

async function onPublishSubmit(payload: { note: string }) {
  if (!selected.value) return
  busy.value = true
  compileErrors.value = []
  try {
    await workflowApi.saveDraft(selected.value.id, draftJson.value)
    await workflowApi.publish(selected.value.id, payload.note || undefined)
    publishDialogOpen.value = false
    setStatus(t('workflows.status.published'), 'ok')
    await reload()
  } catch (e) {
    handleCompileError(e)
    // Close the publish dialog on failure so the operator sees the
    // errors-panel + Monaco markers underneath instead of staring at a
    // dialog that just twitched. Also surface a toast with the error
    // count so the redirect to the editor is obvious — the panel is
    // below the fold on smaller viewports.
    publishDialogOpen.value = false
    if (compileErrors.value.length) {
      ElMessage.error(t('workflows.status.compileFailed', { count: compileErrors.value.length }))
    }
  } finally {
    busy.value = false
  }
}

async function remove() {
  if (!selected.value) return
  const name = selected.value.name ?? ''
  const ok = await mcConfirm({
    title: t('workflows.dialogs.deleteTitle'),
    message: t('workflows.dialogs.deleteContent', { name }),
    confirmText: t('workflows.actions.delete'),
    tone: 'danger',
  })
  if (!ok) return
  busy.value = true
  try {
    await workflowApi.delete(selected.value.id)
    selected.value = null
    selectedId.value = null
    draftJson.value = ''
    await reload()
    setStatus(t('workflows.status.deleted'), 'ok')
  } catch (e) {
    setStatus(t('workflows.status.deleteFailed', { msg: (e as Error).message }), 'err')
  } finally {
    busy.value = false
  }
}

function handleCompileError(e: unknown) {
  // The HTTP layer rejects with an `Error` whose message is the body's
  // msg field. The structured errors list lives on the response body's
  // data field; we have to dig it out of the raw axios error if present.
  const err = e as { response?: { data?: { data?: WorkflowCompileFailure } }; message?: string }
  const failure = err.response?.data?.data
  if (failure?.errors?.length) {
    compileErrors.value = failure.errors
    setStatus(t('workflows.status.compileFailed', { count: failure.errorCount ?? failure.errors.length }), 'err')
  } else {
    setStatus(err.message || t('workflows.status.compileFallback'), 'err')
  }
}

function setStatus(msg: string, kind: 'ok' | 'err') {
  lastStatus.value = msg
  lastStatusKind.value = kind
}

function formatTime(iso?: string) {
  if (!iso) return '-'
  return iso.replace('T', ' ').slice(0, 19)
}

onMounted(async () => {
  await reload()
  await reloadPausedRuns()
  await reloadAgents()
  await reloadChannels()
})
watch(workspaceId, async () => {
  await reload()
  await reloadPausedRuns()
  await reloadAgents()
  await reloadChannels()
})
</script>

<style scoped>
.workflows-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  min-height: 0;
  flex: 1;
}
.workflows-page :deep(.mc-page-header) {
  align-items: center;
  margin-bottom: 18px;
  flex-shrink: 0;
}
.workflows-page :deep(.header-actions) {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
  align-self: center;
}
.workflows-grid {
  display: grid;
  grid-template-columns: minmax(220px, 260px) minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
  flex: 1;
  min-height: 0;
  min-width: 0;
}
.workflows-list,
.workflows-editor,
.workflows-empty {
  padding: 12px;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.06));
  border-radius: 10px;
  background: var(--mc-bg-elevated, rgba(255, 255, 255, 0.4));
}
.workflows-list {
  /* Slightly recessed so the editor reads as primary surface. */
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.02));
}
.runs-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 360px;
  overflow-y: auto;
}
.list-header,
.runs-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  opacity: 0.85;
}
.list-body,
.runs-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  flex: 1;
}
.list-row,
.run-row {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.12s ease;
}
.list-row:hover,
.run-row:hover {
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.05));
}
.list-row.active {
  background: var(--mc-primary-bg, rgba(64, 132, 255, 0.18));
}
.list-row-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}
.list-row-desc {
  font-size: 12px;
  opacity: 0.7;
  margin-top: 2px;
}
.badge {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 999px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.06));
}
.badge.published {
  background: #2ecc71;
  color: white;
}
.badge.draft {
  background: #ffb84d;
  color: white;
}
.list-empty,
.runs-empty {
  font-size: 13px;
  opacity: 0.6;
  padding: 12px 4px;
}
.editor-header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.editor-name {
  flex: 0 0 200px;
  font-weight: 600;
}
.editor-desc {
  flex: 1;
}
.editor-name,
.editor-desc {
  padding: 6px 8px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.1));
  border-radius: 6px;
  background: transparent;
  color: inherit;
}
.editor-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
  font-size: 12px;
}
.template-picker {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
}
.template-picker select {
  padding: 4px 6px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 6px;
  background: transparent;
  color: inherit;
  font-size: 12px;
}
.json-hint {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  opacity: 0.85;
  flex: 1;
}
.json-hint.ok { color: #1e8449; }
.json-hint.err { color: #c0392b; }
.editor-body {
  flex: 1;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  padding: 12px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.1));
  border-radius: 6px;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.02));
  color: inherit;
  resize: vertical;
  min-height: 320px;
}
.errors-panel {
  margin-top: 12px;
  padding: 10px;
  border-radius: 6px;
  background: rgba(255, 80, 80, 0.08);
  border: 1px solid rgba(255, 80, 80, 0.4);
}
.errors-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.errors-panel ul {
  margin: 0;
  padding-left: 16px;
  font-size: 12px;
  list-style: disc;
}
.errors-panel code {
  font-weight: 600;
  margin-right: 6px;
}
.err-path {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  opacity: 0.85;
}
.err-msg {
  margin-left: 4px;
}
.status-panel {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 13px;
}
.status-panel.ok {
  background: rgba(46, 204, 113, 0.12);
  border: 1px solid rgba(46, 204, 113, 0.4);
}
.status-panel.err {
  background: rgba(255, 80, 80, 0.08);
  border: 1px solid rgba(255, 80, 80, 0.4);
}
.runs-list {
  max-height: 340px;
}
.run-row-line {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}
.run-state,
.step-state {
  text-transform: uppercase;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.08);
}
.state-succeeded { background: rgba(46, 204, 113, 0.2); color: #1e8449; }
.state-failed    { background: rgba(231, 76, 60, 0.18); color: #c0392b; }
.state-paused    { background: rgba(255, 184, 77, 0.22); color: #b8730a; }
.state-skipped   { background: rgba(149, 165, 166, 0.22); color: #444; }
.state-running   { background: rgba(52, 152, 219, 0.18); color: #1a5276; }
.run-row-meta {
  font-size: 11px;
  opacity: 0.7;
  margin-top: 2px;
}
.run-err {
  color: #c0392b;
}
.run-detail {
  margin-top: 12px;
  padding: 10px;
  border-radius: 6px;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.04));
}
.run-detail-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.run-steps {
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: 12px;
}
.run-steps li {
  padding: 4px 0;
  display: flex;
  align-items: center;
  gap: 6px;
  border-top: 1px dashed rgba(0, 0, 0, 0.06);
}
.step-name {
  font-weight: 500;
}
.step-duration,
.step-err {
  margin-left: auto;
  font-size: 11px;
  opacity: 0.7;
}
.step-err {
  color: #c0392b;
}
.btn-primary,
.btn-ghost,
.btn-danger {
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  background: transparent;
  color: inherit;
}
.btn-primary {
  background: var(--mc-primary, #4084ff);
  border-color: var(--mc-primary, #4084ff);
  color: white;
}
.btn-danger {
  background: rgba(231, 76, 60, 0.12);
  border-color: rgba(231, 76, 60, 0.6);
  color: #c0392b;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.editor-tabs {
  display: flex;
  gap: 4px;
  padding: 4px 0 8px;
  border-bottom: 1px solid var(--mc-border, rgba(0, 0, 0, 0.06));
  margin-bottom: 8px;
}
.tab-btn {
  padding: 6px 14px;
  border: none;
  background: transparent;
  color: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border-radius: 6px;
  opacity: 0.7;
}
.tab-btn:hover {
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.04));
  opacity: 1;
}
.tab-btn.active {
  opacity: 1;
  background: var(--mc-primary-bg, rgba(64, 132, 255, 0.14));
  color: var(--mc-primary, #4084ff);
}
.canvas-pane {
  flex: 1;
  display: flex;
  flex-direction: row;
  gap: 12px;
  min-height: 320px;
  align-items: stretch;
}
.canvas-pane > .workflow-canvas {
  /* fill available width when no inspector is visible — earlier the
     pane reserved 240px for an inspector via grid-template-columns
     even when it was v-if hidden, leaving the canvas confined. */
  flex: 1 1 auto;
  min-width: 0;
}
.canvas-pane > .step-panel {
  flex: 0 0 280px;
  max-height: 540px;
}
@media (max-width: 1100px) {
  .canvas-pane > .step-panel {
    flex: 0 0 auto;
    max-height: 360px;
  }
}
/* Fullscreen mode: the canvas itself becomes a fixed overlay
   (z-index: 2000). Float the panel above it so the same flex sibling
   stays interactive — the previous behaviour where the panel slipped
   underneath the overlay made it look like the property panel had
   broken when fullscreen was on.

   bottom: 180px reserves room for vue-flow's MiniMap (~150px tall) at
   the bottom-right corner. Without it, the panel covers the minimap
   and the user loses overview navigation. The panel scrolls internally
   when content exceeds the shortened height. */
.canvas-pane.canvas-fullscreen {
  position: static;
}
.canvas-pane.canvas-fullscreen > .step-panel {
  position: fixed;
  top: 56px;
  right: 14px;
  bottom: 180px;
  width: min(360px, 30vw);
  max-height: none;
  z-index: 2001;
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.22);
  overflow-y: auto;
}
@media (max-width: 760px) {
  .canvas-pane.canvas-fullscreen > .step-panel {
    top: auto;
    left: 10px;
    right: 10px;
    bottom: 10px;
    width: auto;
    height: min(56vh, 420px);
  }
}
.canvas-inspector {
  background: var(--mc-bg-elevated, rgba(0, 0, 0, 0.02));
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.08));
  border-radius: 8px;
  padding: 10px;
  font-size: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: hidden;
}
.canvas-inspector header {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 4px;
}
.inspector-grid {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 4px 8px;
  margin: 0;
  font-size: 12px;
}
.inspector-grid dt {
  opacity: 0.6;
  text-transform: uppercase;
  font-size: 10px;
  letter-spacing: 0.04em;
  align-self: center;
}
.inspector-grid dd {
  margin: 0;
  word-break: break-word;
}
.inspector-grid code {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
}
.inspector-raw {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 10.5px;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.04));
  border-radius: 6px;
  padding: 8px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow: auto;
  margin-top: 6px;
}
.json-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 280px;
}
.paused-section {
  border-bottom: 1px dashed var(--mc-border-light, rgba(0, 0, 0, 0.08));
  padding-bottom: 10px;
  margin-bottom: 10px;
}
.paused-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12.5px;
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--mc-text-primary, inherit);
}
.paused-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.paused-row {
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.03));
  border: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
  border-left: 3px solid var(--node-band, #ffb84d);
  border-radius: 6px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.paused-row-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.paused-run-hash {
  font-weight: 600;
}
.paused-meta {
  font-size: 11px;
  opacity: 0.85;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.paused-meta span {
  opacity: 0.6;
  margin-right: 4px;
}
.pause-token {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  background: var(--mc-bg-elevated, rgba(0, 0, 0, 0.04));
  padding: 1px 5px;
  border-radius: 3px;
}
.paused-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}
.resume-btn {
  flex: 1 0 calc(50% - 2px);
  padding: 5px 8px;
  border-radius: 4px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  background: transparent;
  color: inherit;
  font-size: 11.5px;
  cursor: pointer;
}
.resume-btn:hover:not(:disabled) {
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.04));
}
.resume-btn.approve {
  background: rgba(46, 204, 113, 0.12);
  border-color: rgba(46, 204, 113, 0.5);
  color: #1e8449;
}
.resume-btn.approve:hover:not(:disabled) {
  background: rgba(46, 204, 113, 0.22);
}
.resume-btn.reject {
  background: rgba(231, 76, 60, 0.1);
  border-color: rgba(231, 76, 60, 0.5);
  color: var(--mc-danger, #c0392b);
}
.resume-btn.reject:hover:not(:disabled) {
  background: rgba(231, 76, 60, 0.18);
}
.resume-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Responsive: collapse to a single column below 1100px so the editor
   can breathe on tablets, and stack everything vertically below 720px
   so the page is usable on a phone. The runs panel becomes a
   collapsible details element on small screens. */
@media (max-width: 1300px) {
  .workflows-grid {
    grid-template-columns: 1fr;
  }
  .canvas-pane {
    grid-template-columns: 1fr;
  }
  .canvas-inspector {
    max-height: 240px;
  }
}
@media (max-width: 720px) {
  .mc-page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .editor-header {
    flex-direction: column;
    align-items: stretch;
  }
  .editor-name,
  .editor-desc {
    flex: none;
    width: 100%;
  }
  .editor-actions {
    justify-content: flex-end;
  }
  .editor-body {
    min-height: 240px;
  }
  .canvas-pane {
    min-height: 320px;
  }
}
</style>
