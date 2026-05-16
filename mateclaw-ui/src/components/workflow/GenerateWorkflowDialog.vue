<template>
  <Teleport to="body">
    <div v-if="visible" class="modal-overlay" @click.self="close">
      <div class="modal" role="dialog" aria-modal="true">
        <div class="modal-header">
          <h3>{{ t('workflows.generate.title') }}</h3>
          <button class="modal-close" @click="close" aria-label="close">×</button>
        </div>
        <div class="modal-body">
          <p class="hint">{{ t('workflows.generate.hint') }}</p>

          <!-- Template picker — for the common patterns the LLM
               roundtrip is overkill. Selecting one drops the canonical
               draft + triggerDrafts straight into the result panel,
               skipping the model call entirely. -->
          <div v-if="templates.length" class="template-picker">
            <span class="picker-label">{{ t('workflows.generate.applyTemplate') }}</span>
            <select v-model="selectedTemplateId" class="picker-select" @change="onTemplatePick">
              <option value="">{{ t('workflows.generate.applyTemplatePlaceholder') }}</option>
              <option v-for="tpl in templates" :key="tpl.id" :value="tpl.id">
                {{ tpl.label }}
              </option>
            </select>
            <p v-if="selectedTemplate" class="picker-desc">{{ selectedTemplate.description }}</p>
          </div>

          <textarea
            ref="descRef"
            v-model="description"
            class="form-input form-textarea"
            rows="5"
            spellcheck="false"
            :placeholder="t('workflows.generate.placeholder')"
          />

          <div v-if="result" class="generate-result">
            <div class="result-summary">
              <strong>{{ result.name }}</strong>
              <span class="confidence" v-if="result.confidence != null">
                · confidence {{ Math.round((result.confidence ?? 0) * 100) }}%
              </span>
              <span v-if="previewCompiling" class="status-pill pending">
                … {{ t('workflows.generate.compileChecking') }}
              </span>
              <span v-else-if="result.compileOk" class="status-pill ok">
                ✓ {{ t('workflows.generate.compileOk') }}
              </span>
              <span v-else class="status-pill err">
                ⚠ {{ t('workflows.generate.compileFail', { count: result.compileErrors.length }) }}
              </span>
            </div>
            <p v-if="result.description" class="result-desc">{{ result.description }}</p>
            <ul v-if="result.missingFields.length" class="result-list">
              <li v-for="(m, i) in result.missingFields" :key="`miss-${i}`">
                <span class="result-tag">{{ t('workflows.generate.missing') }}</span> {{ m }}
              </li>
            </ul>
            <ul v-if="result.warnings.length" class="result-list">
              <li v-for="(w, i) in result.warnings" :key="`warn-${i}`">
                <span class="result-tag warn">{{ t('workflows.generate.warning') }}</span> {{ w }}
              </li>
            </ul>
            <ul v-if="!previewCompiling && !result.compileOk && result.compileErrors.length"
                class="result-list">
              <li v-for="(err, i) in result.compileErrors" :key="`cerr-${i}`">
                <span class="result-tag err">{{ t('workflows.generate.compileError') }}</span>
                <code class="err-path">{{ err.path }}</code>
                {{ err.message }}
              </li>
            </ul>
            <div v-if="result.triggerDrafts.length" class="trigger-drafts">
              <header>{{ t('workflows.generate.triggersTitle') }} ({{ result.triggerDrafts.length }})</header>
              <ul>
                <li v-for="(td, i) in result.triggerDrafts" :key="`td-${i}`">
                  <code>{{ td.patternType }}</code>
                  <span class="td-name">{{ td.name || '—' }}</span>
                </li>
              </ul>
              <p class="trigger-hint">{{ t('workflows.generate.triggersHint') }}</p>
            </div>
            <details class="result-raw">
              <summary>{{ t('workflows.generate.previewDraft') }}</summary>
              <pre>{{ result.draftJson }}</pre>
            </details>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="close">{{ t('common.cancel') }}</button>
          <button
            v-if="!result"
            class="btn-primary"
            :disabled="loading || !description.trim()"
            @click="onGenerate"
          >
            {{ loading ? t('workflows.generate.running') : t('workflows.generate.submit') }}
          </button>
          <template v-else>
            <button class="btn-secondary" @click="onRetry">
              {{ t('workflows.generate.retry') }}
            </button>
            <button class="btn-primary" :disabled="loading || previewCompiling" @click="onAccept">
              {{ previewCompiling ? t('workflows.generate.compileChecking') : t('workflows.generate.accept') }}
            </button>
          </template>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import {
  workflowApi,
  type GeneratedDraft,
  type WorkflowCompileError,
  type WorkflowCompileFailure,
  type WorkflowDraftTemplate,
} from '@/api'

interface Props {
  modelValue: boolean
}
const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /** User accepted the draft — parent should create the workflow + save the draft. */
  (e: 'accept', payload: GeneratedDraft): void
}>()

const { t } = useI18n()
const visible = ref(props.modelValue)
const description = ref('')
const loading = ref(false)
const result = ref<GeneratedDraft | null>(null)
const descRef = ref<HTMLTextAreaElement | null>(null)

const templates = ref<WorkflowDraftTemplate[]>([])
const selectedTemplateId = ref('')
const selectedTemplate = computed(() =>
  templates.value.find((t) => t.id === selectedTemplateId.value) ?? null
)
// While the preview-compile roundtrip is in flight after a template apply
// we suppress the green/red pill and show a neutral "checking" state, so
// the operator never sees the optimistic "compile OK" before the server
// has actually evaluated the draft against schema + ACL.
const previewCompiling = ref(false)

async function loadTemplates() {
  try {
    const res = await workflowApi.listDraftTemplates()
    templates.value = (res.data as unknown as WorkflowDraftTemplate[]) ?? []
  } catch (e) {
    // Templates are a nice-to-have — falling back to free-form description
    // is fine if the endpoint isn't available (e.g. older deployment).
    console.warn('listDraftTemplates failed', e)
    templates.value = []
  }
}

/**
 * Apply a template directly without going through the LLM. The template
 * already carries a known-good {steps:[...]} JSON + triggerDraftsJson;
 * we synthesise a GeneratedDraft so the rest of the result-panel UI
 * (compile pill / missing fields / triggers) keeps working.
 *
 * <p>Compile state is verified by hitting /workflows/draft/preview-compile
 * after the template is applied — the LLM path returns compileOk straight
 * from the generator, so the template path needs the same guarantee, not
 * an optimistic "compileOk: true" that hides ACL / schema breakage until
 * publish time.
 */
async function onTemplatePick() {
  const tpl = selectedTemplate.value
  if (!tpl) {
    result.value = null
    return
  }
  let triggerDrafts: Array<Record<string, unknown>> = []
  try {
    const parsed = JSON.parse(tpl.triggerDraftsJson || '[]')
    if (Array.isArray(parsed)) triggerDrafts = parsed
  } catch (e) {
    console.warn('template triggerDraftsJson parse failed', e)
  }
  // Templates ship with TODO_* placeholders both in the workflow draft
  // body and inside trigger patternJson (e.g. workflow_completion's
  // TODO_WORKFLOW_ID lives on the trigger, not the workflow). Scan both
  // and dedupe so the operator sees every placeholder that needs filling
  // before clicking Accept.
  const todoSet = new Set<string>()
  for (const m of tpl.draftJson.match(/TODO_[A-Z_]+/g) ?? []) todoSet.add(m)
  for (const m of (tpl.triggerDraftsJson || '').match(/TODO_[A-Z_]+/g) ?? []) todoSet.add(m)
  const missing: string[] = []
  for (const m of todoSet) missing.push(`${m} (template placeholder)`)
  // Set the pending flag first so the pill renders the "checking" state
  // immediately on the same tick we publish the result; otherwise the
  // template would flash the red "compile failed" pill for one frame
  // because compileOk starts at false.
  previewCompiling.value = true
  result.value = {
    name: tpl.id,
    description: tpl.description,
    draftJson: tpl.draftJson,
    triggerDrafts,
    warnings: [],
    missingFields: missing,
    confidence: 1.0,
    compileOk: false,
    compileErrors: [],
  }
  if (!description.value.trim()) description.value = tpl.description
  // Run the actual compile. Templates with TODO placeholders that resolve
  // to ACL-protected ids (agent / channel / employee) will fail here, and
  // that failure has to surface — the user can either edit the draft to
  // fill placeholders before Accept, or accept the failed draft and fix
  // it in the editor with the diagnostics shown.
  try {
    await workflowApi.previewCompileDraft(tpl.draftJson)
    if (result.value && result.value.draftJson === tpl.draftJson) {
      result.value.compileOk = true
      result.value.compileErrors = []
    }
  } catch (e) {
    const err = e as { response?: { data?: { data?: WorkflowCompileFailure } } }
    const failure = err.response?.data?.data
    const errors: WorkflowCompileError[] = failure?.errors ?? []
    if (result.value && result.value.draftJson === tpl.draftJson) {
      result.value.compileOk = false
      result.value.compileErrors = errors
    }
  } finally {
    previewCompiling.value = false
  }
}

watch(
  () => props.modelValue,
  async (open) => {
    visible.value = open
    if (open) {
      description.value = ''
      result.value = null
      loading.value = false
      previewCompiling.value = false
      selectedTemplateId.value = ''
      await loadTemplates()
      await nextTick()
      descRef.value?.focus()
      document.addEventListener('keydown', onKey)
    } else {
      document.removeEventListener('keydown', onKey)
    }
  }
)
watch(visible, (v) => emit('update:modelValue', v))

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && visible.value) close()
}
function close() {
  visible.value = false
}

async function onGenerate() {
  const desc = description.value.trim()
  if (!desc) return
  loading.value = true
  try {
    const res = await workflowApi.generateDraft(desc)
    result.value = res.data as unknown as GeneratedDraft
  } catch (e) {
    mcToast.error(t('workflows.generate.failed', { msg: (e as Error).message }))
  } finally {
    loading.value = false
  }
}

function onRetry() {
  result.value = null
}

function onAccept() {
  if (!result.value) return
  emit('accept', result.value)
  close()
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 10, 8, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  z-index: 2100;
}
.modal {
  width: 640px;
  max-width: 100%;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 14px;
  overflow: hidden;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
}
.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--mc-border-light);
}
.modal-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.modal-close {
  width: 26px;
  height: 26px;
  border: none;
  background: none;
  color: var(--mc-text-tertiary);
  font-size: 22px;
  cursor: pointer;
  border-radius: 6px;
}
.modal-close:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
}
.modal-body {
  padding: 18px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--mc-text-secondary);
  line-height: 1.5;
}
.template-picker {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
}
.picker-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
:lang(zh-CN) .picker-label {
  text-transform: none;
  letter-spacing: 0;
}
.picker-select {
  padding: 7px 9px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  font-size: 13px;
}
.picker-desc {
  margin: 4px 0 0;
  font-size: 11.5px;
  color: var(--mc-text-tertiary);
  line-height: 1.4;
}
.form-input,
.form-textarea {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
  font-size: 13.5px;
  box-sizing: border-box;
  font-family: inherit;
}
.form-textarea { resize: vertical; }
.form-input:focus,
.form-textarea:focus {
  outline: none;
  border-color: var(--mc-primary);
}
.generate-result {
  margin-top: 6px;
  padding: 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg-sunken);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.result-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
}
.confidence { font-size: 11.5px; opacity: 0.7; }
.status-pill {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  font-weight: 500;
}
.status-pill.ok { background: rgba(46, 204, 113, 0.18); color: #1e8449; }
.status-pill.err { background: rgba(231, 76, 60, 0.14); color: var(--mc-danger); }
.status-pill.pending { background: var(--mc-bg-muted); color: var(--mc-text-secondary); }
.result-desc { margin: 0; font-size: 12.5px; color: var(--mc-text-secondary); }
.result-list { margin: 4px 0 0; padding: 0 0 0 4px; list-style: none; font-size: 12px; }
.result-list li { padding: 3px 0; line-height: 1.5; }
.result-tag {
  display: inline-block;
  padding: 1px 6px;
  margin-right: 5px;
  border-radius: 3px;
  background: var(--mc-bg-muted);
  font-size: 10px;
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.result-tag.warn { background: rgba(245, 158, 11, 0.16); color: #b45309; }
.result-tag.err { background: rgba(231, 76, 60, 0.14); color: var(--mc-danger); }
.err-path {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  background: var(--mc-bg-muted);
  padding: 1px 5px;
  border-radius: 3px;
  margin-right: 5px;
}
.trigger-drafts {
  border-top: 1px dashed var(--mc-border-light);
  padding-top: 8px;
  font-size: 12px;
}
.trigger-drafts header {
  font-weight: 600;
  font-size: 12px;
  margin-bottom: 4px;
}
.trigger-drafts ul {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.trigger-drafts li {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.trigger-drafts code {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  background: var(--mc-bg-muted);
  padding: 1px 6px;
  border-radius: 3px;
  flex: 0 0 auto;
}
.trigger-drafts .td-name {
  opacity: 0.85;
}
.trigger-hint {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}
.result-raw summary {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  cursor: pointer;
}
.result-raw pre {
  margin: 6px 0 0;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  padding: 8px;
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow: auto;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 18px 16px;
  border-top: 1px solid var(--mc-border-light);
}
.btn-primary,
.btn-secondary {
  padding: 8px 16px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid transparent;
}
.btn-primary {
  background: var(--mc-primary);
  color: var(--mc-text-inverse, #ffffff);
  border-color: var(--mc-primary);
}
.btn-primary:disabled { opacity: 0.55; cursor: not-allowed; }
.btn-primary:hover:not(:disabled) { background: var(--mc-primary-hover, var(--mc-primary)); }
.btn-secondary {
  background: transparent;
  color: var(--mc-text-secondary);
  border-color: var(--mc-border);
}
.btn-secondary:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
}
</style>
