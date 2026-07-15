<template>
  <div class="json-editor-shell" :class="{ dark: isDark }">
    <VueMonacoEditor
      :value="modelValue"
      language="json"
      :theme="isDark ? 'vs-dark' : 'vs'"
      :options="editorOptions"
      :path="filePath"
      class="json-editor-monaco"
      @update:value="(v: string | undefined) => emit('update:modelValue', v ?? '')"
      @mount="onEditorMount"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue'
import { VueMonacoEditor, loader } from '@guolao/vue-monaco-editor'
// Precise imports replace `import * as monaco from 'monaco-editor'` — the
// barrel pulled in TypeScript / CSS / HTML language workers (~8.7 MB) that
// this JSON editor never touches. The editor.api entry exposes the same
// `monaco` namespace surface we need (registerLanguage, KeyMod, etc.).
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api.js'
import 'monaco-editor/esm/vs/language/json/monaco.contribution.js'
// Vite's ?worker imports — Monaco loads its language workers as separate
// JS bundles, and without this MonacoEnvironment shim the editor falls
// back to fetching from a CDN URL ('vs/base/worker/workerMain.js') that
// our private deploys don't have. Loading them here means the JSON
// editor still works offline.
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import JsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker'
import { useThemeStore } from '@/stores/useThemeStore'
import type { WorkflowCompileError } from '@/api'

// Monaco reads MonacoEnvironment.getWorker once on first model load.
// Set it on the global so multiple editor instances share the same
// worker factory.
declare global {
  interface Window {
    MonacoEnvironment?: { getWorker(_moduleId: string, label: string): Worker }
  }
}
if (typeof window !== 'undefined' && !window.MonacoEnvironment) {
  window.MonacoEnvironment = {
    getWorker(_moduleId: string, label: string) {
      // Workflow editor only needs json + the base editor worker.
      // Other languages (typescript / css / html) are not used in this
      // page so we keep the bundle slim by not registering them.
      if (label === 'json') return new JsonWorker()
      return new EditorWorker()
    },
  }
}

interface Props {
  modelValue: string
  /** Compile errors from the backend, surfaced as inline markers. */
  compileErrors?: WorkflowCompileError[]
  /** Stable monaco "file path" so multiple editors don't share a model. */
  filePath?: string
}

const props = withDefaults(defineProps<Props>(), {
  compileErrors: () => [],
  filePath: 'inmemory://workflow-draft.json',
})
const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
}>()

// Make the bundled npm module the source of truth for monaco assets,
// rather than letting vue-monaco-editor pull a CDN copy at runtime.
// Without this you get a 5MB CDN fetch on every cold visit, which is
// painful for offline / private deploys.
loader.config({ monaco })

const themeStore = useThemeStore()
const isDark = computed(() => themeStore.isDark)

const editorOptions = {
  // `automaticLayout` lets the editor relayout when its container resizes
  // (canvas / json tab toggles, sidebar collapse, fullscreen).
  automaticLayout: true,
  fontSize: 13,
  fontFamily: 'JetBrains Mono, Consolas, monospace',
  minimap: { enabled: false },
  // Tabular indent matches the templates the picker inserts.
  tabSize: 2,
  // Workflow JSON is small (a few hundred lines tops); bracketPairs +
  // word-wrap save more screen than they cost.
  wordWrap: 'on' as const,
  bracketPairColorization: { enabled: true },
  // Keep operators focused — the bottom status bar adds noise.
  scrollBeyondLastLine: false,
  // Show the right-margin guide so authors notice when prompt
  // templates start wrapping.
  rulers: [100],
  // Render whitespace only on selection so it doesn't flood the view.
  renderWhitespace: 'selection' as const,
}

// Workflow JSON schema — gives Monaco hover docs + completion + inline
// validation for the structural fields. Rendered from the same shape
// the backend WorkflowSchemaValidator enforces; modes are an enum so
// typos like "sequencial" surface as a marker before compile.
const WORKFLOW_SCHEMA = {
  $id: 'mateclaw://workflow.schema.json',
  $schema: 'http://json-schema.org/draft-07/schema#',
  title: 'MateClaw Workflow',
  type: 'object',
  required: ['steps'],
  properties: {
    steps: {
      type: 'array',
      minItems: 1,
      items: {
        type: 'object',
        required: ['name', 'mode'],
        properties: {
          name: { type: 'string', maxLength: 64, description: 'Unique step name within the workflow.' },
          agentName: { type: 'string', description: 'Workspace-scoped agent name; required for sequential / conditional.' },
          agentId: { type: 'integer', description: 'Workspace-scoped agent id; alternative to agentName.' },
          promptTemplate: { type: 'string', description: 'Pebble template threaded with `inputs` and `outputs`.' },
          outputVar: { type: 'string', description: 'Stash this step\'s output under `outputs.<name>` for later steps.' },
          outputContentType: { type: 'string', enum: ['text', 'json'], description: 'How later steps decode this output.' },
          timeoutSecs: { type: 'integer', minimum: 0 },
          mode: {
            type: 'object',
            required: ['type'],
            properties: {
              type: {
                type: 'string',
                enum: ['sequential', 'fan_out', 'collect', 'conditional',
                       'await_approval', 'dispatch_channel', 'write_memory'],
              },
              expression: { type: 'string', description: 'Pebble expression for `conditional`.' },
              approvalKind: { type: 'string', description: 'Approval label, e.g. manager / oncall.' },
              approverChannels: { type: 'array', items: { type: 'string' }, minItems: 1 },
              approvalMessage: { type: 'string' },
              channels: { type: 'array', items: { type: 'string' } },
              targets: { type: 'object', additionalProperties: { type: 'string' } },
              content: { type: 'string' },
              employeeId: { type: 'string' },
              file: { type: 'string' },
              mergeStrategy: { type: 'string', enum: ['append', 'replace_section', 'upsert_kv', 'overwrite'] },
            },
            additionalProperties: true,
          },
        },
        additionalProperties: true,
      },
    },
  },
  additionalProperties: false,
} as const

let editorRef: monaco.editor.IStandaloneCodeEditor | null = null

function onEditorMount(editor: monaco.editor.IStandaloneCodeEditor) {
  editorRef = editor
  // Bind the schema to JSON files matching our virtual path. The cast
  // is needed because monaco's d.ts marks the static `languages.json`
  // namespace as deprecated even though it's still the official runtime
  // API for jsonDefaults.setDiagnosticsOptions in the v0.55.x line.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const jsonLang = (monaco.languages as any).json
  if (jsonLang?.jsonDefaults) {
    jsonLang.jsonDefaults.setDiagnosticsOptions({
      validate: true,
      allowComments: false,
      schemas: [
        {
          uri: 'mateclaw://workflow.schema.json',
          fileMatch: [props.filePath],
          schema: WORKFLOW_SCHEMA,
        },
      ],
    })
  }
  // Re-apply markers if the parent already pushed compile errors before
  // the editor mounted.
  applyCompileErrorMarkers(props.compileErrors ?? [])
}

/**
 * Map backend compile errors into Monaco markers so they show inline in
 * the gutter + as squiggles. We can't always derive a precise line
 * range from the JSON path (e.g. `steps[2].mode.expression`), so the
 * fallback is to put the marker at line 1 with the path quoted in the
 * message — at least the operator sees the path and the cause without
 * scrolling to a separate panel.
 */
function applyCompileErrorMarkers(errors: WorkflowCompileError[]) {
  if (!editorRef) return
  const model = editorRef.getModel()
  if (!model) return
  const markers: monaco.editor.IMarkerData[] = errors.map((err) => {
    const range = locatePath(model.getValue(), err.path)
    return {
      severity: monaco.MarkerSeverity.Error,
      message: `[${err.code}] ${err.message}\n${err.path}`,
      startLineNumber: range.startLine,
      startColumn: range.startCol,
      endLineNumber: range.endLine,
      endColumn: range.endCol,
    }
  })
  monaco.editor.setModelMarkers(model, 'workflow-compile', markers)
}

/**
 * Locate a JSON path like `steps[2].mode.expression` in the source text.
 * v0 implementation: walk the source line by line and look for the last
 * key segment as a token; good enough to put markers near the right
 * field for most well-formatted drafts. Falls back to (1,1)–(1,1) when
 * we can't find anything — Monaco still shows the marker in the
 * Problems panel.
 */
function locatePath(source: string, path: string): {
  startLine: number; startCol: number; endLine: number; endCol: number
} {
  const fallback = { startLine: 1, startCol: 1, endLine: 1, endCol: 1 }
  if (!source || !path) return fallback
  // Strip the array index so we search for the leaf key only.
  const leaf = path.replace(/^.*?(\.|^)([^.[]+)\]?$/, '$2')
  if (!leaf) return fallback
  const lines = source.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const idx = lines[i].indexOf(`"${leaf}"`)
    if (idx >= 0) {
      return {
        startLine: i + 1,
        startCol: idx + 1,
        endLine: i + 1,
        endCol: idx + leaf.length + 3,
      }
    }
  }
  return fallback
}

watch(
  () => props.compileErrors,
  (next) => applyCompileErrorMarkers(next ?? []),
  { deep: true }
)

onBeforeUnmount(() => {
  if (editorRef) {
    const model = editorRef.getModel()
    if (model) monaco.editor.setModelMarkers(model, 'workflow-compile', [])
  }
})
</script>

<style scoped>
.json-editor-shell {
  flex: 1;
  display: flex;
  min-height: 320px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 6px;
  overflow: hidden;
  background: var(--mc-bg, transparent);
}
.json-editor-monaco {
  width: 100%;
  height: 100%;
  min-height: 320px;
}
</style>
