<template>
  <div class="triggers-panel">
    <div v-if="!triggers.length" class="trigger-empty mc-surface-card">
      <div class="trigger-empty-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="M13 2 3 14h7l-1 8 10-12h-7l1-8Z" />
        </svg>
      </div>
      <h2 class="trigger-empty-title">{{ t('triggers.empty') }}</h2>
      <p class="trigger-empty-desc">{{ t('triggers.emptyDesc') }}</p>
      <button class="btn-primary" @click="openCreate">{{ t('triggers.newTrigger') }}</button>
    </div>

    <div v-else class="trigger-list">
      <article
        v-for="row in triggers"
        :key="row.id"
        class="trigger-card mc-surface-card"
        :class="{ 'is-disabled': !row.enabled }"
      >
        <span
          class="trigger-status-dot"
          :class="row.enabled ? 'is-on' : 'is-off'"
          :title="row.enabled ? t('triggers.enabled') : t('triggers.disabled')"
        />
        <div class="trigger-main">
          <div class="trigger-headline">
            <span class="trigger-name">{{ row.name || t('triggers.unnamed') }}</span>
            <span v-if="row.lastError" class="trigger-error-chip" :title="row.lastError">
              ⚠ {{ truncateError(row.lastError) }}
            </span>
          </div>
          <div class="trigger-rule">
            <span class="trigger-type-pill" :title="row.patternType">
              {{ patternTypeLabel(row.patternType) }}
            </span>
            <span class="trigger-rule-arrow" aria-hidden="true">→</span>
            <span class="trigger-target" :title="'#' + row.targetId">{{ targetName(row) }}</span>
          </div>
          <div class="trigger-meta">
            <span class="trigger-meta-summary" :title="row.patternJson">{{ patternSummary(row) }}</span>
            <span class="trigger-meta-sep" aria-hidden="true">·</span>
            <span>{{ activityLine(row) }}</span>
          </div>
        </div>
        <div class="trigger-controls">
          <button
            type="button"
            class="trigger-toggle"
            :class="{ 'is-on': row.enabled }"
            :title="row.enabled ? t('triggers.enabled') : t('triggers.disabled')"
            @click="toggleEnabled(row)"
          >
            <span class="trigger-toggle-knob" />
          </button>
          <button class="row-btn" :title="t('triggers.actions.edit')" @click="openEdit(row)">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 20h9" />
              <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4Z" />
            </svg>
          </button>
          <button class="row-btn danger" :title="t('triggers.actions.delete')" @click="remove(row)">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 6h18" />
              <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
            </svg>
          </button>
        </div>
      </article>
    </div>

    <Teleport to="body">
      <div v-if="formOpen" class="trigger-modal-overlay" @click.self="closeForm">
        <section class="trigger-modal" role="dialog" aria-modal="true">
          <header class="trigger-modal-header">
            <strong>{{ editing?.id ? t('triggers.formTitleEdit') : t('triggers.formTitleNew') }}</strong>
            <button class="btn-ghost" @click="closeForm">{{ t('triggers.actions.close') }}</button>
          </header>

          <div class="trigger-modal-body">
            <div class="form-grid">
              <label>{{ t('triggers.fields.name') }}
                <input v-model="formState.name" :placeholder="t('triggers.fields.namePlaceholder')" />
              </label>
              <label>{{ t('triggers.fields.patternType') }}
                <!-- v0 ships four pattern types in the manual create UI:
                     cron, channel_message (which now carries the keyword
                     filter that content_match used to provide),
                     workflow_completion, webhook. content_match and
                     agent_lifecycle remain supported by the matcher for
                     legacy rows but are hidden from the new-trigger
                     dropdown so the manual entry matches what the
                     natural-language generator surfaces. They re-appear
                     only when editing a row that already uses one, so
                     the operator can tweak/disable/delete it without
                     being stuck on a dropdown that excludes its type. -->
                <select v-model="formState.patternType">
                  <option value="cron">cron</option>
                  <option value="channel_message">channel_message</option>
                  <option value="workflow_completion">workflow_completion</option>
                  <option value="webhook">webhook</option>
                  <option v-if="showLegacyContentMatch" value="content_match">content_match (legacy)</option>
                  <option v-if="showLegacyAgentLifecycle" value="agent_lifecycle">agent_lifecycle (legacy)</option>
                </select>
              </label>
              <div class="span-2">
                <span class="form-grid-label">{{ t('triggers.fields.patternJson') }}</span>
                <TriggerPatternForm
                  v-model="formState.patternJson"
                  :pattern-type="formState.patternType"
                  :available-workflows="availableWorkflows"
                  @validation="onPatternValidation"
                />
              </div>
              <label>{{ t('triggers.fields.targetType') }}
                <!-- v0 only ships the workflow dispatcher; agent target is
                     reserved for v1 and rejected by the API to avoid the
                     "looks enabled, never fires" trap. -->
                <select v-model="formState.targetType" disabled>
                  <option value="workflow">workflow</option>
                </select>
              </label>
              <label>{{ t('triggers.fields.targetId') }}
                <!-- Keep targetId as a string so 19-digit Snowflake IDs survive
                     the v-model round trip; .number would truncate to JS's
                     53-bit safe-integer ceiling. -->
                <select v-model="formState.targetId">
                  <option v-if="!availableWorkflows.length" :value="0">
                    {{ t('triggers.targetWorkflowEmpty') }}
                  </option>
                  <option v-else v-for="wf in availableWorkflows" :key="wf.id" :value="wf.id">
                    #{{ wf.id }} — {{ wf.name || t('triggers.unnamed') }}
                  </option>
                </select>
              </label>
              <label>{{ t('triggers.fields.ratePerMin') }}
                <input v-model.number="formState.rateLimitPerMin" type="number" />
              </label>
              <label>{{ t('triggers.fields.dedupWindowSecs') }}
                <input v-model.number="formState.dedupWindowSecs" type="number" />
              </label>
              <label>{{ t('triggers.fields.maxFires') }}
                <input v-model.number="formState.maxFires" type="number" />
              </label>
              <label class="checkbox-field">
                <input type="checkbox" v-model="formState.botSelfFilter" />
                <span>{{ t('triggers.fields.botSelfFilter') }}</span>
              </label>
              <label class="span-2">{{ t('triggers.fields.payloadTemplate') }}
                <textarea v-model="formState.payloadTemplate" rows="3"
                  :placeholder="t('triggers.fields.payloadTemplatePlaceholder')" />
              </label>
              <label class="span-2 checkbox-field">
                <input type="checkbox" v-model="formState.enabled" />
                <span>{{ t('triggers.fields.enabled') }}</span>
              </label>
            </div>
          </div>

          <footer class="trigger-modal-footer">
            <button class="btn-ghost" @click="closeForm">{{ t('triggers.actions.cancel') }}</button>
            <button
              class="btn-primary"
              :disabled="busy || patternHasErrors"
              :title="patternHasErrors ? Object.values(patternErrors).join('; ') : ''"
              @click="save"
            >{{ t('triggers.actions.save') }}</button>
          </footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import { triggerApi, type TriggerSummary, workflowApi, type WorkflowSummary } from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import TriggerPatternForm from '@/components/workflow/TriggerPatternForm.vue'

const { t } = useI18n()
const workspaceStore = useWorkspaceStore()
const workspaceId = computed(() => workspaceStore.currentWorkspaceId)
const triggers = ref<TriggerSummary[]>([])
const workflows = ref<WorkflowSummary[]>([])

// Report the trigger count up to the Scheduler shell for the tab badge.
const emit = defineEmits<{ count: [number] }>()

const availableWorkflows = computed(() =>
  workflows.value.filter((w) => w.latestRevisionId)
)

// Show the legacy pattern types only when the trigger being edited
// actually uses one — otherwise they stay hidden so the new-trigger
// flow matches the four-pattern v0 product surface.
const showLegacyContentMatch = computed(() => formState.value?.patternType === 'content_match')
const showLegacyAgentLifecycle = computed(() => formState.value?.patternType === 'agent_lifecycle')

const patternErrors = ref<Record<string, string>>({})
function onPatternValidation(errs: Record<string, string>) {
  patternErrors.value = errs
}
const patternHasErrors = computed(() => Object.keys(patternErrors.value).length > 0)

const formOpen = ref(false)
const editing = ref<TriggerSummary | null>(null)
const busy = ref(false)

interface FormState {
  name: string
  patternType: string
  patternJson: string
  targetType: string
  // Snowflake IDs arrive from the backend as strings (ToStringSerializer).
  // Keep that string form here so v-model on the workflow select can't
  // accidentally lose precision. The 0 sentinel covers the "no workflow
  // available" empty state.
  targetId: number | string
  rateLimitPerMin: number
  dedupWindowSecs: number
  botSelfFilter: boolean
  enabled: boolean
  maxFires: number
  payloadTemplate: string
}

const formState = ref<FormState>(emptyForm())

function emptyForm(): FormState {
  return {
    name: '',
    patternType: 'cron',
    patternJson: '{"cron":"0 0 * * * *","timezone":"UTC"}',
    targetType: 'workflow',
    targetId: 0,
    rateLimitPerMin: 60,
    dedupWindowSecs: 60,
    botSelfFilter: true,
    enabled: true,
    maxFires: 0,
    payloadTemplate: '',
  }
}

async function reload() {
  if (!workspaceId.value) return
  try {
    const res = await triggerApi.list(workspaceId.value)
    triggers.value = (res.data as unknown as TriggerSummary[]) ?? []
  } catch (e) {
    console.error('listTriggers failed', e)
  }
  try {
    const res = await workflowApi.list(workspaceId.value)
    workflows.value = (res.data as unknown as WorkflowSummary[]) ?? []
  } catch (e) {
    console.error('listWorkflows failed', e)
  }
}

function targetName(row: TriggerSummary): string {
  if (row.targetType === 'workflow') {
    const wf = workflows.value.find((w) => String(w.id) === String(row.targetId))
    if (wf?.name) return wf.name
  }
  return `${row.targetType} #${row.targetId}`
}

// Compact "MM-DD HH:mm" for the activity subline — the full timestamp would
// crowd the card without telling the operator anything they scan for.
function shortTime(iso?: string): string {
  if (!iso) return '-'
  return iso.slice(5, 16).replace('T', ' ')
}

// One muted line summarizing operational state: how many times it has fired
// and when. Replaces the former fireCount / lastFired / pattern_version
// columns — those scalars never warranted a column each.
function activityLine(row: TriggerSummary): string {
  if (!row.lastFiredAt) return t('triggers.neverFired')
  return t('triggers.firedSummary', {
    count: row.fireCount,
    time: shortTime(row.lastFiredAt),
  })
}

function patternTypeLabel(pt: string): string {
  const key = `triggers.patternTypeLabels.${pt}`
  const localized = t(key, '')
  return localized && localized !== key ? localized : pt
}

/**
 * Render a one-line, human-readable summary of the pattern_json so the
 * list reads as a product surface rather than a developer console. The
 * raw JSON is still available via the row's title attribute (tooltip)
 * and the edit form's "raw pattern_json (advanced)" details element.
 */
function patternSummary(row: TriggerSummary): string {
  if (!row.patternJson) return '-'
  let parsed: Record<string, unknown> = {}
  try { parsed = JSON.parse(row.patternJson) } catch { return row.patternJson }
  switch (row.patternType) {
    case 'cron': {
      const cron = parsed.cron ?? ''
      const tz = parsed.timezone ?? ''
      return tz ? `${cron} (${tz})` : `${cron}`
    }
    case 'channel_message': {
      const ch = parsed.channelType ? `${parsed.channelType}` : t('triggers.pattern.channelTypeAny')
      const sender = parsed.senderEquals ? ` · @${parsed.senderEquals}` : ''
      const contains = parsed.contentContains ? ` · "${parsed.contentContains}"` : ''
      return `${ch}${sender}${contains}`
    }
    case 'content_match': {
      return parsed.substring ? `"${parsed.substring}"` : '—'
    }
    case 'agent_lifecycle': {
      const aid = parsed.agentId ? `#${parsed.agentId}` : t('triggers.pattern.agentIdPlaceholder')
      const ph = parsed.phase ? ` · ${parsed.phase}` : ''
      return `${aid}${ph}`
    }
    case 'workflow_completion': {
      const src = parsed.sourceWorkflowId ? `#${parsed.sourceWorkflowId}` : t('triggers.pattern.sourceWorkflowAny')
      const st = parsed.stateFilter ? ` · ${parsed.stateFilter}` : ''
      return `${src}${st}`
    }
    case 'webhook':
      return t('triggers.pattern.webhookHeader')
    default:
      return row.patternJson
  }
}

function truncateError(msg: string | undefined): string {
  if (!msg) return ''
  return msg.length <= 64 ? msg : msg.slice(0, 60) + '…'
}

function openCreate() {
  editing.value = null
  formState.value = emptyForm()
  formOpen.value = true
}

function openEdit(row: TriggerSummary) {
  editing.value = row
  formState.value = {
    name: row.name ?? '',
    patternType: row.patternType,
    patternJson: row.patternJson,
    targetType: row.targetType,
    targetId: row.targetId,
    rateLimitPerMin: row.rateLimitPerMin ?? 60,
    dedupWindowSecs: row.dedupWindowSecs ?? 60,
    botSelfFilter: row.botSelfFilter ?? true,
    enabled: row.enabled,
    maxFires: row.maxFires ?? 0,
    payloadTemplate: row.payloadTemplate ?? '',
  }
  formOpen.value = true
}

function closeForm() {
  formOpen.value = false
}

async function save() {
  if (!workspaceId.value) return
  busy.value = true
  try {
    const payload: Partial<TriggerSummary> = {
      workspaceId: workspaceId.value,
      ...formState.value,
    }
    if (editing.value?.id) {
      await triggerApi.update(editing.value.id, payload)
    } else {
      await triggerApi.create(payload)
    }
    formOpen.value = false
    await reload()
  } catch (e) {
    mcToast.error(t('triggers.saveFailed', { msg: (e as Error).message }))
  } finally {
    busy.value = false
  }
}

async function toggleEnabled(row: TriggerSummary) {
  try {
    await triggerApi.update(row.id, { ...row, enabled: !row.enabled })
    await reload()
  } catch (e) {
    mcToast.error(t('triggers.toggleFailed', { msg: (e as Error).message }))
  }
}

async function remove(row: TriggerSummary) {
  const ok = await mcConfirm({
    title: t('triggers.actions.delete'),
    message: t('triggers.deleteConfirm', { name: row.name || String(row.id) }),
    confirmText: t('triggers.actions.delete'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await triggerApi.delete(row.id)
    await reload()
  } catch (e) {
    mcToast.error(t('triggers.deleteFailed', { msg: (e as Error).message }))
  }
}

onMounted(reload)
watch(workspaceId, reload)
watch(() => triggers.value.length, (n) => emit('count', n), { immediate: true })

// The Scheduler shell owns the "+ New Trigger" header button; expose the
// modal opener so it can drive this panel when the Event Triggers tab is active.
defineExpose({ openCreate })
</script>

<style scoped>
.triggers-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
/* ── Trigger list — one card per rule, reads as "when X → run Y" ── */
.trigger-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.trigger-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 18px;
  transition: opacity 0.15s ease, border-color 0.15s ease;
}
.trigger-card.is-disabled {
  opacity: 0.6;
}
.trigger-status-dot {
  flex: 0 0 auto;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.trigger-status-dot.is-on {
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.16);
}
.trigger-status-dot.is-off {
  background: var(--mc-text-tertiary, #b0a89e);
}
.trigger-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.trigger-headline {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.trigger-name {
  font-weight: 600;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.trigger-error-chip {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--mc-danger, #c0392b);
  background: var(--mc-danger-bg, rgba(192, 57, 43, 0.1));
  padding: 1px 8px;
  border-radius: 999px;
  cursor: help;
}
/* Line 2 — the rule at its purest: "[type] → [workflow]". Both ends are
   short, so this never needs to truncate; detail lives on the meta line. */
.trigger-rule {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.trigger-type-pill {
  flex: 0 0 auto;
  display: inline-block;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 500;
  border-radius: 999px;
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.06));
  color: var(--mc-text-secondary, inherit);
  text-transform: lowercase;
  letter-spacing: 0.02em;
}
:lang(zh-CN) .trigger-type-pill { text-transform: none; }
.trigger-rule-arrow {
  flex: 0 0 auto;
  color: var(--mc-text-tertiary, #b0a89e);
  font-size: 13px;
}
.trigger-target {
  flex: 0 1 auto;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* Line 3 — muted detail: pattern summary + activity. Wraps instead of
   truncating so neither the schedule nor the fire stats get amputated. */
.trigger-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 2px 7px;
  font-size: 11.5px;
  color: var(--mc-text-tertiary, #9a938b);
}
.trigger-meta-summary {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
}
.trigger-meta-sep {
  opacity: 0.5;
}
.trigger-controls {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
/* Toggle switch — enabled state is user-controlled, so it earns a switch */
.trigger-toggle {
  flex: 0 0 auto;
  position: relative;
  width: 38px;
  height: 22px;
  padding: 0;
  border: none;
  border-radius: 999px;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.16));
  cursor: pointer;
  transition: background 0.18s ease;
}
.trigger-toggle.is-on {
  background: #22c55e;
}
.trigger-toggle-knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
  transition: transform 0.18s ease;
}
.trigger-toggle.is-on .trigger-toggle-knob {
  transform: translateX(16px);
}
.row-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 8px;
  background: var(--mc-bg-elevated, transparent);
  color: var(--mc-text-secondary, inherit);
  cursor: pointer;
  transition: background 0.14s ease, color 0.14s ease, border-color 0.14s ease;
}
.row-btn svg {
  width: 15px;
  height: 15px;
}
.row-btn:hover {
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.05));
  color: var(--mc-text-primary, inherit);
}
.row-btn.danger:hover {
  background: var(--mc-danger-bg, rgba(192, 57, 43, 0.1));
  border-color: var(--mc-danger, #c0392b);
  color: var(--mc-danger, #c0392b);
}
/* Empty state — a brand-new page should invite, not show a blank table */
.trigger-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 10px;
  padding: 56px 24px;
}
.trigger-empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: var(--mc-bg-muted, rgba(0, 0, 0, 0.06));
  color: var(--mc-text-tertiary, #9a938b);
}
.trigger-empty-icon svg {
  width: 26px;
  height: 26px;
}
.trigger-empty-title {
  margin: 4px 0 0;
  font-size: 15px;
  font-weight: 600;
}
.trigger-empty-desc {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--mc-text-secondary, inherit);
  max-width: 380px;
}
.trigger-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(17, 12, 8, 0.42);
  backdrop-filter: blur(6px);
}
.trigger-modal {
  width: min(1120px, calc(100vw - 48px));
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 12px;
  background: var(--mc-bg-elevated, #fff);
  color: var(--mc-text-primary, inherit);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.28);
}
:global(html.dark) .trigger-modal {
  background: #1f1713;
}
.trigger-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--mc-border, rgba(0, 0, 0, 0.08));
  flex: 0 0 auto;
}
.trigger-modal-body {
  padding: 16px 20px 20px;
  overflow: auto;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px 16px;
}
.form-grid .span-2 {
  grid-column: span 2;
}
.form-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  font-weight: 500;
}
.form-grid input,
.form-grid select,
.form-grid textarea {
  padding: 6px 8px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 6px;
  background: transparent;
  color: inherit;
  font-family: inherit;
  font-size: 13px;
  min-height: 38px;
}
.form-grid textarea {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  min-height: 96px;
  resize: vertical;
}
.pattern-hint {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  font-weight: 400;
  font-family: 'JetBrains Mono', Consolas, monospace;
  opacity: 0.7;
  white-space: pre-wrap;
}
.form-grid-label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  margin-bottom: 4px;
  color: var(--mc-text-secondary, inherit);
}
.checkbox-field {
  flex-direction: row !important;
  align-items: center;
  justify-content: flex-start;
  min-height: 38px;
  padding-top: 20px;
}
.checkbox-field input[type='checkbox'] {
  width: 16px;
  height: 16px;
  min-height: 0;
  flex: 0 0 auto;
}
.trigger-modal-footer {
  flex: 0 0 auto;
  padding: 14px 20px;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  border-top: 1px solid var(--mc-border, rgba(0, 0, 0, 0.08));
  background: var(--mc-bg-elevated, #fff);
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

/* Mobile / tablet: let the rule line wrap instead of truncating and collapse
   the form to one column. */
@media (max-width: 720px) {
  .trigger-rule {
    flex-wrap: wrap;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
  .form-grid .span-2 {
    grid-column: span 1;
  }
}
</style>
