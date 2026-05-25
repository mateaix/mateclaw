<template>
  <div class="pattern-form">
    <!-- cron — schedule expression + timezone -->
    <template v-if="patternType === 'cron'">
      <div class="pf-field">
        <span class="pf-label">{{ t('triggers.pattern.cronExpression') }}</span>
        <CronExpressionField
          :model-value="form.cron || ''"
          with-seconds
          @update:model-value="onCronChange"
        />
        <small v-if="errors.cron" class="pf-err">{{ errors.cron }}</small>
      </div>
      <div class="pf-field">
        <span class="pf-label">{{ t('triggers.pattern.cronTimezone') }}</span>
        <input
          v-model.trim="form.timezone"
          class="pf-input"
          :placeholder="t('triggers.pattern.cronTimezonePlaceholder')"
          spellcheck="false"
          @input="emitFromForm"
        />
      </div>
    </template>

    <!-- channel_message — adapter dropdown + optional senderEquals -->
    <template v-else-if="patternType === 'channel_message'">
      <div class="pf-field">
        <span class="pf-label">{{ t('triggers.pattern.channelType') }}</span>
        <select v-model="form.channelType" class="pf-input" @change="emitFromForm">
          <option value="">{{ t('triggers.pattern.channelTypeAny') }}</option>
          <option value="feishu">feishu</option>
          <option value="dingtalk">dingtalk</option>
          <option value="wecom">wecom</option>
          <option value="telegram">telegram</option>
          <option value="discord">discord</option>
          <option value="slack">slack</option>
          <option value="qq">qq</option>
          <option value="web">web</option>
        </select>
      </div>
      <div class="pf-field">
        <span class="pf-label">{{ t('triggers.pattern.senderEquals') }}</span>
        <input
          v-model.trim="form.senderEquals"
          class="pf-input"
          :placeholder="t('triggers.pattern.senderEqualsPlaceholder')"
          spellcheck="false"
          @input="emitFromForm"
        />
        <small class="pf-hint">{{ t('triggers.pattern.senderEqualsHint') }}</small>
      </div>
      <div class="pf-field">
        <span class="pf-label">{{ t('triggers.pattern.contentContains') }}</span>
        <input
          v-model.trim="form.contentContains"
          class="pf-input"
          :placeholder="t('triggers.pattern.contentContainsPlaceholder')"
          spellcheck="false"
          @input="emitFromForm"
        />
        <small class="pf-hint">{{ t('triggers.pattern.contentContainsHint') }}</small>
      </div>
    </template>

    <!-- content_match — substring (required) -->
    <template v-else-if="patternType === 'content_match'">
      <div class="pf-field">
        <span class="pf-label">{{ t('triggers.pattern.substring') }} *</span>
        <input
          v-model.trim="form.substring"
          class="pf-input"
          :placeholder="t('triggers.pattern.substringPlaceholder')"
          spellcheck="false"
          @input="emitFromForm"
        />
        <small v-if="errors.substring" class="pf-err">{{ errors.substring }}</small>
        <small v-else class="pf-hint">{{ t('triggers.pattern.substringHint') }}</small>
      </div>
    </template>

    <!-- agent_lifecycle — agentId + phase -->
    <template v-else-if="patternType === 'agent_lifecycle'">
      <div class="pf-field">
        <span class="pf-label">{{ t('triggers.pattern.agentId') }}</span>
        <!-- type=text + inputmode=numeric so 19-digit Snowflake IDs aren't
             coerced through JS Number (input[type=number] silently truncates
             values past 2^53-1 even when v-model has no .number modifier). -->
        <input
          v-model.trim="form.agentId"
          type="text"
          inputmode="numeric"
          pattern="\d*"
          class="pf-input"
          :placeholder="t('triggers.pattern.agentIdPlaceholder')"
          @input="emitFromForm"
        />
        <small class="pf-hint">{{ t('triggers.pattern.agentIdHint') }}</small>
      </div>
      <div class="pf-field">
        <span class="pf-label">{{ t('triggers.pattern.phase') }}</span>
        <select v-model="form.phase" class="pf-input" @change="emitFromForm">
          <option value="">{{ t('triggers.pattern.phaseAny') }}</option>
          <option value="spawned">spawned</option>
          <option value="enabled">enabled</option>
          <option value="disabled">disabled</option>
          <option value="terminated">terminated</option>
          <option value="crashed">crashed</option>
        </select>
      </div>
    </template>

    <!-- workflow_completion — sourceWorkflowId + stateFilter -->
    <template v-else-if="patternType === 'workflow_completion'">
      <div class="pf-field">
        <span class="pf-label">{{ t('triggers.pattern.sourceWorkflowId') }}</span>
        <select v-model="form.sourceWorkflowId" class="pf-input" @change="emitFromForm">
          <option :value="undefined">{{ t('triggers.pattern.sourceWorkflowAny') }}</option>
          <option v-for="wf in availableWorkflows" :key="wf.id" :value="wf.id">
            #{{ wf.id }} — {{ wf.name || '(unnamed)' }}
          </option>
        </select>
      </div>
      <div class="pf-field">
        <span class="pf-label">{{ t('triggers.pattern.stateFilter') }}</span>
        <select v-model="form.stateFilter" class="pf-input" @change="emitFromForm">
          <option value="">{{ t('triggers.pattern.stateFilterAny') }}</option>
          <option value="completed">completed</option>
          <option value="succeeded">succeeded</option>
          <option value="failed">failed</option>
        </select>
        <small class="pf-hint">{{ t('triggers.pattern.stateFilterHint') }}</small>
      </div>
    </template>

    <!-- webhook — secret hint, no fields -->
    <template v-else-if="patternType === 'webhook'">
      <div class="pf-field">
        <span class="pf-label">{{ t('triggers.pattern.webhookHeader') }}</span>
        <p class="pf-prose">{{ t('triggers.pattern.webhookBody') }}</p>
      </div>
    </template>

    <!-- raw JSON escape hatch — collapsible details so it doesn't get
         in the way for the typical operator who lets the form generate
         everything. -->
    <details class="pf-raw" :open="expanded">
      <summary @click.prevent="expanded = !expanded">
        {{ t('triggers.pattern.rawHeader') }}
      </summary>
      <textarea
        :value="modelValue"
        class="pf-input mono"
        rows="4"
        spellcheck="false"
        @input="onRawInput"
      />
      <small v-if="rawError" class="pf-err">{{ rawError }}</small>
    </details>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { WorkflowSummary } from '@/api'
import CronExpressionField from '@/components/common/CronExpressionField.vue'

interface Props {
  /** Pattern type (cron / channel_message / …) drives which form is shown. */
  patternType: string
  /** Current pattern_json string — also the only thing emitted back. */
  modelValue: string
  /** Optional list of published workflows for the workflow_completion form. */
  availableWorkflows?: WorkflowSummary[]
}

const props = withDefaults(defineProps<Props>(), {
  availableWorkflows: () => [],
})
const emit = defineEmits<{
  (e: 'update:modelValue', json: string): void
  (e: 'validation', err: Record<string, string>): void
}>()

const { t } = useI18n()

interface FormState {
  cron?: string
  timezone?: string
  channelType?: string
  senderEquals?: string
  contentContains?: string
  substring?: string
  // IDs are kept as strings to survive 19-digit Snowflake values through
  // v-model without precision loss; matcher's longOrNull accepts both
  // numeric and string forms in pattern_json.
  agentId?: string | null
  phase?: string
  sourceWorkflowId?: string | number
  stateFilter?: string
}

const form = reactive<FormState>({})
const errors = reactive<Record<string, string>>({})
const rawError = ref<string | null>(null)
const expanded = ref(false)

// Parse the incoming JSON into the form state. Anything we can't parse
// is treated as an empty draft — the operator can either fix the raw
// JSON (escape hatch) or fill the form anew, which will re-serialize
// over the broken text.
function loadFromJson(json: string) {
  rawError.value = null
  if (!json || !json.trim()) {
    Object.keys(form).forEach((k) => delete (form as Record<string, unknown>)[k])
    return
  }
  try {
    const parsed = JSON.parse(json) as Record<string, unknown>
    Object.keys(form).forEach((k) => delete (form as Record<string, unknown>)[k])
    if (typeof parsed.cron === 'string') form.cron = parsed.cron
    if (typeof parsed.timezone === 'string') form.timezone = parsed.timezone
    if (typeof parsed.channelType === 'string') form.channelType = parsed.channelType
    if (typeof parsed.senderEquals === 'string') form.senderEquals = parsed.senderEquals
    if (typeof parsed.contentContains === 'string') form.contentContains = parsed.contentContains
    if (typeof parsed.substring === 'string') form.substring = parsed.substring
    // Accept both number and string — pattern_json may store IDs either way
    // depending on who wrote it (matcher's longOrNull accepts both too).
    if (typeof parsed.agentId === 'number' || typeof parsed.agentId === 'string') { // snowflake-precision-ok: explicit dual-form handling, then String()
      form.agentId = String(parsed.agentId)
    }
    if (typeof parsed.phase === 'string') form.phase = parsed.phase
    if (typeof parsed.sourceWorkflowId === 'number' || typeof parsed.sourceWorkflowId === 'string') { // snowflake-precision-ok: explicit dual-form handling, then String()
      form.sourceWorkflowId = String(parsed.sourceWorkflowId)
    }
    if (typeof parsed.stateFilter === 'string') form.stateFilter = parsed.stateFilter
  } catch (e) {
    rawError.value = (e as Error).message
  }
}

// Build the JSON string from the form, dropping empty / undefined fields
// so the matcher's per-field "narrow if present" logic stays clean.
function buildJsonFromForm(): string {
  const out: Record<string, unknown> = {}
  switch (props.patternType) {
    case 'cron':
      if (form.cron) out.cron = form.cron
      if (form.timezone) out.timezone = form.timezone
      break
    case 'channel_message':
      if (form.channelType) out.channelType = form.channelType
      if (form.senderEquals) out.senderEquals = form.senderEquals
      if (form.contentContains) out.contentContains = form.contentContains
      break
    case 'content_match':
      if (form.substring) out.substring = form.substring
      break
    case 'agent_lifecycle':
      // Emit IDs as JSON strings to preserve Snowflake precision. The
      // matcher's longOrNull parses either form, so this stays compatible
      // with any pattern_json written previously as a JSON number.
      if (form.agentId != null && String(form.agentId).trim() !== '') {
        out.agentId = String(form.agentId).trim()
      }
      if (form.phase) out.phase = form.phase
      break
    case 'workflow_completion':
      if (form.sourceWorkflowId != null && String(form.sourceWorkflowId) !== '') {
        out.sourceWorkflowId = String(form.sourceWorkflowId)
      }
      if (form.stateFilter) out.stateFilter = form.stateFilter
      break
    case 'webhook':
      // Webhook is opaque pass-through; no fields.
      break
  }
  return JSON.stringify(out)
}

// Local validation — required-field checks that mirror the matcher's
// fail-closed rules so the operator hears about a mistake before the
// trigger silently misses every event.
function validate(): Record<string, string> {
  const errs: Record<string, string> = {}
  if (props.patternType === 'cron') {
    if (!form.cron || !form.cron.trim()) {
      errs.cron = t('triggers.pattern.errCronRequired')
    } else {
      // Quartz-style 6-field cron is what the scheduler accepts; allow
      // 5- or 6-field for ergonomics, just check field count.
      const parts = form.cron.trim().split(/\s+/)
      if (parts.length < 5 || parts.length > 7) {
        errs.cron = t('triggers.pattern.errCronShape')
      }
    }
  }
  if (props.patternType === 'content_match') {
    if (!form.substring || !form.substring.trim()) {
      errs.substring = t('triggers.pattern.errSubstringRequired')
    }
  }
  return errs
}

function emitFromForm() {
  Object.keys(errors).forEach((k) => delete errors[k])
  Object.assign(errors, validate())
  emit('update:modelValue', buildJsonFromForm())
  emit('validation', { ...errors })
}

// Cron is edited through the shared CronExpressionField; mirror its value
// into the form and re-run the standard emit + validation pipeline.
function onCronChange(value: string) {
  form.cron = value
  emitFromForm()
}

function onRawInput(e: Event) {
  const next = (e.target as HTMLTextAreaElement).value
  rawError.value = null
  if (next.trim()) {
    try { JSON.parse(next) } catch (err) { rawError.value = (err as Error).message }
  }
  emit('update:modelValue', next)
  // Refresh form state to reflect whatever the operator typed.
  loadFromJson(next)
}

// Re-derive form state when the parent flips patternType or the JSON
// changes from outside (e.g. after save or when editing a different
// trigger). We don't deep-watch form back into the JSON on these — that's
// what emitFromForm covers.
watch(
  () => [props.patternType, props.modelValue] as const,
  () => loadFromJson(props.modelValue),
  { immediate: true }
)

// Surface the no-op-when-empty error map at mount so the parent can
// disable the save button right away if a required field is missing.
watch(() => props.patternType, () => {
  Object.keys(errors).forEach((k) => delete errors[k])
  Object.assign(errors, validate())
  emit('validation', { ...errors })
}, { immediate: true })

// Expose computed only so the template can read availableWorkflows
// without verbosity.
const availableWorkflows = computed(() => props.availableWorkflows ?? [])
</script>

<style scoped>
.pattern-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.pf-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}
.pf-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-text-secondary, #555);
  letter-spacing: 0.02em;
}
.pf-input {
  padding: 6px 8px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 5px;
  background: var(--mc-bg, transparent);
  color: var(--mc-text-primary, inherit);
  font: inherit;
  font-size: 12.5px;
  outline: none;
  transition: border-color 0.12s ease;
  width: 100%;
}
.pf-input:focus {
  border-color: var(--mc-primary, #4084ff);
}
.pf-input.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11.5px;
}
.pf-hint {
  font-size: 11px;
  opacity: 0.65;
  font-family: 'JetBrains Mono', Consolas, monospace;
}
.pf-err {
  font-size: 11px;
  color: var(--mc-danger, #c0392b);
}
.pf-prose {
  font-size: 12px;
  margin: 0;
  line-height: 1.5;
  color: var(--mc-text-secondary, #555);
}
.pf-raw {
  margin-top: 6px;
  border-top: 1px dashed var(--mc-border-light, rgba(0, 0, 0, 0.08));
  padding-top: 8px;
}
.pf-raw summary {
  font-size: 11px;
  color: var(--mc-text-tertiary, #888);
  cursor: pointer;
  margin-bottom: 6px;
}
.pf-raw textarea {
  resize: vertical;
}
</style>
