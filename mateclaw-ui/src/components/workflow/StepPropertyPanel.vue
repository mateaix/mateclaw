<template>
  <aside class="step-panel" v-if="step">
    <header class="panel-header">
      <span class="panel-title">{{ t('workflows.canvas.inspector.title') }}</span>
      <div class="panel-actions">
        <button class="panel-btn" :title="t('workflows.canvas.inspector.duplicate')" @click="onDuplicate">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round">
            <rect x="9" y="9" width="13" height="13" rx="2"/>
            <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
          </svg>
        </button>
        <button class="panel-btn danger" :title="t('workflows.canvas.inspector.delete')" @click="onDelete">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round">
            <polyline points="3 6 5 6 21 6"/>
            <path d="M19 6l-2 14H7L5 6"/>
            <path d="M10 11v6"/>
            <path d="M14 11v6"/>
            <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
          </svg>
        </button>
      </div>
    </header>

    <!-- Shared fields every mode honors. -->
    <fieldset class="panel-section">
      <legend>{{ t('workflows.canvas.inspector.shared') }}</legend>
      <label class="panel-field">
        <span class="field-label">{{ t('workflows.canvas.fields.name') }}</span>
        <input
          class="mc-input"
          :value="step.name ?? ''"
          @input="patch({ name: ($event.target as HTMLInputElement).value })"
          spellcheck="false"
          :placeholder="t('workflows.canvas.fields.namePlaceholder')"
        />
      </label>

      <label class="panel-field" v-if="modeNeedsAgent">
        <span class="field-label">{{ t('workflows.canvas.nodeAgent') }}</span>
        <!-- Agent steps persist agentId so the workflow cannot drift when an
             employee is renamed. -->
        <AgentPickerDialog
          block
          clearable
          :model-value="agentModelValue"
          :agents="availableAgents"
          :placeholder="t('workflows.canvas.fields.agentPlaceholder')"
          :unknown-label="t('workflows.canvas.fields.agentMissing', { name: step?.agentName || step?.agentId })"
          @change="onAgentPicked"
        />
      </label>

      <label class="panel-field" v-if="modeNeedsAgent">
        <span class="field-label">{{ t('workflows.canvas.fields.promptTemplate') }}</span>
        <textarea
          class="mc-textarea"
          :value="step.promptTemplate ?? ''"
          @input="patch({ promptTemplate: ($event.target as HTMLTextAreaElement).value })"
          spellcheck="false"
          rows="3"
          :placeholder="PROMPT_PLACEHOLDER"
        />
      </label>

      <div class="panel-row" v-if="modeNeedsAgent">
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.outputVar') }}</span>
          <input
            class="mc-input"
            :value="step.outputVar ?? ''"
            @input="patch({ outputVar: ($event.target as HTMLInputElement).value })"
            spellcheck="false"
            :placeholder="t('workflows.canvas.fields.outputVarPlaceholder')"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.outputContentType') }}</span>
          <select
            class="mc-input"
            :value="step.outputContentType ?? 'text'"
            @change="patch({ outputContentType: ($event.target as HTMLSelectElement).value })"
          >
            <option value="text">text</option>
            <option value="json">json</option>
          </select>
        </label>
      </div>
    </fieldset>

    <!-- Per-mode editor. -->
    <fieldset class="panel-section">
      <legend>{{ t('workflows.canvas.inspector.modeFields', { mode: localizedModeLabel }) }}</legend>

      <label class="panel-field">
        <span class="field-label">{{ t('workflows.canvas.modeLabel') }}</span>
        <select class="mc-input" :value="modeType" @change="onModeChange">
          <option value="sequential">{{ modeOptionLabel('sequential') }}</option>
          <option value="fan_out">{{ modeOptionLabel('fan_out') }}</option>
          <option value="collect">{{ modeOptionLabel('collect') }}</option>
          <option value="conditional">{{ modeOptionLabel('conditional') }}</option>
          <option value="await_approval">{{ modeOptionLabel('await_approval') }}</option>
          <option value="dispatch_channel">{{ modeOptionLabel('dispatch_channel') }}</option>
          <option value="write_memory">{{ modeOptionLabel('write_memory') }}</option>
        </select>
      </label>

      <!-- conditional -->
      <label class="panel-field" v-if="modeType === 'conditional'">
        <span class="field-label">{{ t('workflows.canvas.nodeExpression') }}</span>
        <input
          class="mc-input mono"
          :value="modeField('expression', '')"
          @input="patchMode({ expression: ($event.target as HTMLInputElement).value })"
          spellcheck="false"
          placeholder="{{ inputs.payload != null }}"
        />
      </label>

      <!-- await_approval -->
      <template v-if="modeType === 'await_approval'">
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.approvalKind') }}</span>
          <input
            class="mc-input"
            :value="modeField('approvalKind', '')"
            @input="patchMode({ approvalKind: ($event.target as HTMLInputElement).value })"
            spellcheck="false"
            placeholder="manual / manager / oncall"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.approverChannels') }}</span>
          <input
            class="mc-input"
            :value="(modeField('approverChannels', []) as string[]).join(', ')"
            @change="patchMode({ approverChannels: parseList(($event.target as HTMLInputElement).value) })"
            spellcheck="false"
            placeholder="web, feishu"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.approvalMessage') }}</span>
          <input
            class="mc-input"
            :value="modeField('approvalMessage', '')"
            @input="patchMode({ approvalMessage: ($event.target as HTMLInputElement).value })"
            spellcheck="false"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.timeoutSecs') }}</span>
          <input
            type="number"
            class="mc-input"
            :value="modeField('timeoutSecs', 3600)"
            @input="patchMode({ timeoutSecs: parseInt(($event.target as HTMLInputElement).value, 10) || null })"
            min="0"
          />
        </label>
      </template>

      <!-- dispatch_channel -->
      <template v-if="modeType === 'dispatch_channel'">
        <div class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.channels') }}</span>
          <div class="channel-picker" v-if="availableDispatchChannels.length">
            <label
              v-for="channel in availableDispatchChannels"
              :key="channel.channelType"
              class="channel-option"
            >
              <input
                type="checkbox"
                :checked="selectedChannelTypes.includes(channel.channelType)"
                @change="onDispatchChannelToggle(channel.channelType, ($event.target as HTMLInputElement).checked)"
              />
              <span class="channel-name">{{ channel.name }}</span>
              <code class="channel-type">{{ channel.channelType }}</code>
            </label>
          </div>
          <p v-else class="panel-hint">{{ t('workflows.canvas.fields.channelsEmptyHint') }}</p>
          <p v-if="unknownSelectedChannels.length" class="panel-hint danger">
            {{ t('workflows.canvas.fields.channelsUnknown', { channels: unknownSelectedChannels.join(', ') }) }}
          </p>
        </div>
        <label
          v-for="channelType in selectedChannelTypes"
          :key="`target-${channelType}`"
          class="panel-field"
        >
          <span class="field-label">{{ t('workflows.canvas.fields.dispatchTarget', { channel: channelLabel(channelType) }) }}</span>
          <input
            class="mc-input"
            :value="dispatchTarget(channelType)"
            @input="onDispatchTargetInput(channelType, ($event.target as HTMLInputElement).value)"
            spellcheck="false"
            :placeholder="t('workflows.canvas.fields.dispatchTargetPlaceholder')"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.dispatchContent') }}</span>
          <textarea
            class="mc-textarea"
            :value="modeField('content', '')"
            @input="patchMode({ content: ($event.target as HTMLTextAreaElement).value })"
            rows="3"
            spellcheck="false"
            placeholder="Notification: {{ inputs.payload }}"
          />
        </label>
      </template>

      <!-- write_memory -->
      <template v-if="modeType === 'write_memory'">
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.employeeId') }}</span>
          <input
            class="mc-input"
            :value="modeField('employeeId', '')"
            @input="patchMode({ employeeId: ($event.target as HTMLInputElement).value })"
            spellcheck="false"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.memoryFile') }}</span>
          <input
            class="mc-input"
            :value="modeField('file', '')"
            @input="patchMode({ file: ($event.target as HTMLInputElement).value })"
            spellcheck="false"
            placeholder="workspace.md"
          />
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.nodeMergeStrategy') }}</span>
          <select
            class="mc-input"
            :value="modeField('mergeStrategy', 'append')"
            @change="patchMode({ mergeStrategy: ($event.target as HTMLSelectElement).value })"
          >
            <option value="append">append</option>
            <option value="replace_section">replace_section</option>
            <option value="upsert_kv">upsert_kv</option>
            <option value="overwrite">overwrite</option>
          </select>
        </label>
        <label class="panel-field">
          <span class="field-label">{{ t('workflows.canvas.fields.memoryContent') }}</span>
          <textarea
            class="mc-textarea"
            :value="modeField('content', '')"
            @input="patchMode({ content: ($event.target as HTMLTextAreaElement).value })"
            rows="3"
            spellcheck="false"
          />
        </label>
      </template>

      <p v-if="modeType === 'fan_out' || modeType === 'collect' || modeType === 'sequential'" class="panel-hint">
        {{ t('workflows.canvas.inspector.modeNoFields') }}
      </p>
    </fieldset>

    <details class="panel-section raw-section">
      <summary>{{ t('workflows.canvas.inspector.rawHeader') }}</summary>
      <pre class="raw-json">{{ rawJson }}</pre>
    </details>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { RawStep } from '@/composables/useWorkflowGraph'
import AgentPickerDialog, { type PickableAgent } from '@/components/common/AgentPickerDialog.vue'

/** Minimal agent shape the panel needs to render the picker — kept
 *  inside the component so the panel doesn't have to import the
 *  full workspace agent type. */
export interface AgentOption {
  id: number | string
  name: string
  title?: string
  icon?: string
  description?: string
}
export interface ChannelOption {
  id: number | string
  name: string
  channelType: string
  enabled?: boolean
}

interface Props {
  /** The step the panel currently edits, or null. */
  step: RawStep | null
  /** Index of the step inside `steps[]` — used by the parent to scope patches. */
  index: number
  /** Workspace-scoped agent list rendered as a dropdown. */
  availableAgents?: AgentOption[]
  /** Workspace-scoped enabled channels rendered as a multi-select picker. */
  availableChannels?: ChannelOption[]
}

const props = withDefaults(defineProps<Props>(), {
  availableAgents: () => [],
  availableChannels: () => [],
})
const emit = defineEmits<{
  (e: 'patch', payload: { index: number; patch: Partial<RawStep> }): void
  (e: 'delete', payload: { index: number }): void
  (e: 'duplicate', payload: { index: number }): void
}>()

const { t } = useI18n()

// The Pebble example shown in the textarea placeholder is the same in every
// locale and contains literal `{{ }}`. Routing it through vue-i18n's t()
// works but the library does a second compile pass on the returned string
// looking for linked messages / nested placeholders, which trips on the
// inner braces and floods the console with parse errors. Keeping it as a
// plain const sidesteps the parser entirely.
const PROMPT_PLACEHOLDER = 'Hello {{ inputs.payload }}'

const modeType = computed(() => (props.step?.mode?.type ?? 'sequential') as string)

const localizedModeLabel = computed(() => {
  return modeOptionLabel(modeType.value)
})

function modeOptionLabel(type: string): string {
  const key = `workflows.canvas.modeLabels.${type}`
  const localized = t(key, '')
  return localized && localized !== key ? localized : type
}

const modeNeedsAgent = computed(() => {
  // collect / await_approval / dispatch_channel / write_memory
  // don't take an agent — the runtime calls a service adapter instead.
  return ['sequential', 'fan_out', 'conditional'].includes(modeType.value)
})

const rawJson = computed(() => {
  try { return JSON.stringify(props.step ?? {}, null, 2) } catch { return '' }
})

function modeField<T>(key: string, fallback: T): T {
  const v = props.step?.mode?.[key as keyof typeof props.step.mode]
  return (v ?? fallback) as T
}

function parseList(raw: string): string[] {
  return raw.split(',').map((s) => s.trim()).filter(Boolean)
}

function patch(p: Partial<RawStep>) {
  emit('patch', { index: props.index, patch: p })
}

function patchMode(modePatch: Record<string, unknown>) {
  emit('patch', {
    index: props.index,
    patch: { mode: { ...(props.step?.mode ?? {}), ...modePatch } as RawStep['mode'] },
  })
}

function onModeChange(e: Event) {
  const next = (e.target as HTMLSelectElement).value
  // Reset mode-only fields when the type changes — keep `type` as the
  // single carry-over so the schema validator doesn't complain about
  // stale fields like `expression` lingering on a sequential step.
  emit('patch', {
    index: props.index,
    patch: { mode: { type: next } as RawStep['mode'] },
  })
}

function onDelete() {
  emit('delete', { index: props.index })
}
function onDuplicate() {
  emit('duplicate', { index: props.index })
}

// Resolve the picker's selected id. Newer steps persist agentId; legacy
// steps stored only agentName, so fall back to a name lookup.
const agentModelValue = computed<string | number | null>(() => {
  if (props.step?.agentId != null) return props.step.agentId
  const byName = props.availableAgents.find((a) => a.name === props.step?.agentName)
  if (byName) return byName.id
  return props.step?.agentName ?? null
})
function onAgentPicked(value: string | number | null, agent: PickableAgent | null) {
  if (value == null) {
    patch({ agentId: undefined, agentName: undefined })
    return
  }
  // Keep the id as a string — Snowflake IDs exceed Number.MAX_SAFE_INTEGER,
  // so a numeric round-trip would silently truncate.
  patch({
    agentId: String(value),
    // Denormalized label for the canvas node; runtime resolves by agentId.
    agentName: agent?.name,
  })
}

const availableDispatchChannels = computed(() => {
  const seen = new Set<string>()
  const rows: ChannelOption[] = []
  for (const channel of props.availableChannels) {
    if (!channel?.channelType || channel.enabled === false) continue
    if (seen.has(channel.channelType)) continue
    seen.add(channel.channelType)
    rows.push({
      id: channel.id,
      name: channel.name || channel.channelType,
      channelType: channel.channelType,
      enabled: channel.enabled,
    })
  }
  return rows
})
const selectedChannelTypes = computed(() => {
  const raw = modeField('channels', []) as unknown
  if (!Array.isArray(raw)) return []
  return raw.map((v) => String(v)).filter(Boolean)
})
const unknownSelectedChannels = computed(() => {
  const known = new Set(availableDispatchChannels.value.map((c) => c.channelType))
  return selectedChannelTypes.value.filter((c) => !known.has(c))
})
function channelLabel(channelType: string): string {
  const row = availableDispatchChannels.value.find((c) => c.channelType === channelType)
  return row ? `${row.name} (${row.channelType})` : channelType
}
function dispatchTargets(): Record<string, string> {
  const raw = modeField('targets', {}) as unknown
  return raw && typeof raw === 'object' && !Array.isArray(raw)
    ? { ...(raw as Record<string, string>) }
    : {}
}
function dispatchTarget(channelType: string): string {
  return dispatchTargets()[channelType] ?? ''
}
function onDispatchChannelToggle(channelType: string, checked: boolean) {
  const next = new Set(selectedChannelTypes.value)
  const targets = dispatchTargets()
  if (checked) {
    next.add(channelType)
  } else {
    next.delete(channelType)
    delete targets[channelType]
  }
  patchMode({ channels: Array.from(next), targets })
}
function onDispatchTargetInput(channelType: string, raw: string) {
  const targets = dispatchTargets()
  const value = raw.trim()
  if (value) targets[channelType] = value
  else delete targets[channelType]
  patchMode({ targets })
}
</script>

<style scoped>
.step-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 10px 12px;
  background: var(--mc-bg-elevated, #ffffff);
  border: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
  border-radius: 8px;
  color: var(--mc-text-primary, inherit);
  overflow-y: auto;
  font-size: 12.5px;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border-bottom: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.06));
  padding-bottom: 8px;
}
.panel-title {
  font-weight: 600;
  font-size: 13px;
}
.panel-actions {
  display: flex;
  gap: 4px;
}
.panel-btn {
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 5px;
  border: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
  background: transparent;
  color: var(--mc-text-secondary, inherit);
  cursor: pointer;
}
.panel-btn:hover {
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.04));
  color: var(--mc-text-primary, inherit);
}
.panel-btn.danger:hover {
  color: var(--mc-danger, #c0392b);
  border-color: var(--mc-danger-border, rgba(231, 76, 60, 0.4));
}
.panel-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 0 0;
  border: none;
  margin: 0;
}
.panel-section legend {
  font-size: 10.5px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--mc-text-tertiary, #888);
  font-weight: 600;
  padding: 0;
  margin-bottom: 2px;
}
:lang(zh-CN) .panel-section legend {
  text-transform: none;
}
.panel-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 12px;
}
.panel-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.field-label {
  font-size: 10.5px;
  letter-spacing: 0.04em;
  color: var(--mc-text-tertiary, #888);
}
:lang(en) .field-label {
  text-transform: uppercase;
}
.mc-input,
.mc-textarea {
  padding: 6px 8px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 5px;
  background: var(--mc-bg, transparent);
  color: inherit;
  font: inherit;
  font-size: 12.5px;
  outline: none;
  transition: border-color 0.12s ease;
  width: 100%;
}
.mc-input:focus,
.mc-textarea:focus {
  border-color: var(--mc-primary, #4084ff);
}
.mc-textarea {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11.5px;
  resize: vertical;
}
.mc-input.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11.5px;
}
.channel-picker {
  display: grid;
  gap: 6px;
}
.channel-option {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 30px;
  padding: 6px 8px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 6px;
  background: var(--mc-bg, transparent);
  cursor: pointer;
}
.channel-option input {
  flex: 0 0 auto;
}
.channel-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.channel-type {
  font-size: 10.5px;
  color: var(--mc-text-tertiary, #888);
}
.agent-toggle {
  width: 24px;
  height: 28px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 5px;
  background: transparent;
  color: var(--mc-text-tertiary, #888);
  cursor: pointer;
  font-size: 14px;
}
.agent-toggle:hover {
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.04));
  color: var(--mc-text-primary, inherit);
}
.panel-hint {
  font-size: 11.5px;
  color: var(--mc-text-tertiary, #888);
  font-style: italic;
  margin: 0;
}
.panel-hint.danger {
  color: var(--mc-danger, #c0392b);
}
.raw-section summary {
  font-size: 11px;
  color: var(--mc-text-tertiary, #888);
  cursor: pointer;
  padding: 4px 0;
}
.raw-json {
  margin: 4px 0 0;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 10.5px;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.04));
  border-radius: 4px;
  padding: 8px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow: auto;
}
</style>
