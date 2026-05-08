<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner triggers-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('triggers.kicker') }}</div>
            <h1 class="mc-page-title">{{ t('triggers.title') }}</h1>
            <p class="mc-page-desc">{{ t('triggers.desc') }}</p>
          </div>
          <button class="btn-primary" @click="openCreate">{{ t('triggers.newTrigger') }}</button>
        </div>

        <table class="triggers-table mc-surface-card">
          <thead>
            <tr>
              <th>{{ t('triggers.columns.name') }}</th>
              <th>{{ t('triggers.columns.pattern') }}</th>
              <th>{{ t('triggers.columns.target') }}</th>
              <th>{{ t('triggers.columns.rate') }}</th>
              <th>{{ t('triggers.columns.fires') }}</th>
              <th>{{ t('triggers.columns.patternVersion') }}</th>
              <th>{{ t('triggers.columns.lastFired') }}</th>
              <th>{{ t('triggers.columns.state') }}</th>
              <th>{{ t('triggers.columns.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in triggers" :key="row.id">
              <td>{{ row.name || t('triggers.unnamed') }}</td>
              <td>
                <code>{{ row.patternType }}</code>
                <div class="pattern-detail">{{ row.patternJson }}</div>
              </td>
              <td>{{ formatTarget(row) }}</td>
              <td>{{ t('triggers.rateUnit', { count: row.rateLimitPerMin }) }}</td>
              <td>{{ row.fireCount }}<span v-if="row.maxFires > 0"> / {{ row.maxFires }}</span></td>
              <td>{{ row.patternVersion }}</td>
              <td>{{ formatTime(row.lastFiredAt) }}</td>
              <td>
                <label class="toggle">
                  <input type="checkbox" :checked="row.enabled" @change="toggleEnabled(row)" />
                  <span>{{ row.enabled ? t('triggers.enabled') : t('triggers.disabled') }}</span>
                </label>
              </td>
              <td class="actions">
                <button class="btn-ghost" @click="openEdit(row)">{{ t('triggers.actions.edit') }}</button>
                <button class="btn-danger" @click="remove(row)">{{ t('triggers.actions.delete') }}</button>
              </td>
            </tr>
            <tr v-if="!triggers.length">
              <td colspan="9" class="empty-row">{{ t('triggers.empty') }}</td>
            </tr>
          </tbody>
        </table>

        <!-- Inline form (replaces modal for v0 simplicity) -->
        <section v-if="formOpen" class="form-card mc-surface-card">
          <header>
            <strong>{{ editing?.id ? t('triggers.formTitleEdit') : t('triggers.formTitleNew') }}</strong>
            <button class="btn-ghost" @click="closeForm">{{ t('triggers.actions.close') }}</button>
          </header>
          <div class="form-grid">
            <label>{{ t('triggers.fields.name') }}
              <input v-model="formState.name" :placeholder="t('triggers.fields.namePlaceholder')" />
            </label>
            <label>{{ t('triggers.fields.patternType') }}
              <select v-model="formState.patternType">
                <option value="cron">cron</option>
                <option value="channel_message">channel_message</option>
                <option value="content_match">content_match</option>
                <option value="agent_lifecycle">agent_lifecycle</option>
                <option value="workflow_completion">workflow_completion</option>
                <option value="webhook">webhook</option>
              </select>
            </label>
            <label class="span-2">{{ t('triggers.fields.patternJson') }}
              <textarea v-model="formState.patternJson" rows="3"
                :placeholder="t('triggers.fields.patternJsonPlaceholder')" />
              <small class="pattern-hint">{{ patternHint }}</small>
            </label>
            <label>{{ t('triggers.fields.targetType') }}
              <!-- v0 only ships the workflow dispatcher; agent target is
                   reserved for v1 and rejected by the API to avoid the
                   "looks enabled, never fires" trap. -->
              <select v-model="formState.targetType" disabled>
                <option value="workflow">workflow</option>
              </select>
            </label>
            <label>{{ t('triggers.fields.targetId') }}
              <select v-model.number="formState.targetId">
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
            <label>
              <input type="checkbox" v-model="formState.botSelfFilter" />
              {{ t('triggers.fields.botSelfFilter') }}
            </label>
            <label class="span-2">{{ t('triggers.fields.payloadTemplate') }}
              <textarea v-model="formState.payloadTemplate" rows="3"
                :placeholder="t('triggers.fields.payloadTemplatePlaceholder')" />
            </label>
            <label class="span-2">
              <input type="checkbox" v-model="formState.enabled" />
              {{ t('triggers.fields.enabled') }}
            </label>
          </div>
          <footer>
            <button class="btn-ghost" @click="closeForm">{{ t('triggers.actions.cancel') }}</button>
            <button class="btn-primary" :disabled="busy" @click="save">{{ t('triggers.actions.save') }}</button>
          </footer>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { mcConfirm } from '@/components/common/useConfirm'
import { triggerApi, type TriggerSummary, workflowApi, type WorkflowSummary } from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

const { t } = useI18n()
const workspaceStore = useWorkspaceStore()
const workspaceId = computed(() => workspaceStore.currentWorkspaceId)
const triggers = ref<TriggerSummary[]>([])
const workflows = ref<WorkflowSummary[]>([])

const availableWorkflows = computed(() =>
  workflows.value.filter((w) => w.latestRevisionId)
)

const patternHint = computed(() => {
  const key = `triggers.patternHints.${formState.value.patternType}`
  return t(key, '')
})

const formOpen = ref(false)
const editing = ref<TriggerSummary | null>(null)
const busy = ref(false)

interface FormState {
  name: string
  patternType: string
  patternJson: string
  targetType: string
  targetId: number
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

function formatTarget(row: TriggerSummary) {
  if (row.targetType === 'workflow') {
    const wf = workflows.value.find((w) => w.id === row.targetId)
    if (wf?.name) return `${wf.name} (#${row.targetId})`
  }
  return `${row.targetType}#${row.targetId}`
}

function formatTime(iso?: string) {
  if (!iso) return '-'
  return iso.replace('T', ' ').slice(0, 19)
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
    ElMessage.error(t('triggers.saveFailed', { msg: (e as Error).message }))
  } finally {
    busy.value = false
  }
}

async function toggleEnabled(row: TriggerSummary) {
  try {
    await triggerApi.update(row.id, { ...row, enabled: !row.enabled })
    await reload()
  } catch (e) {
    ElMessage.error(t('triggers.toggleFailed', { msg: (e as Error).message }))
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
    ElMessage.error(t('triggers.deleteFailed', { msg: (e as Error).message }))
  }
}

onMounted(reload)
watch(workspaceId, reload)
</script>

<style scoped>
.triggers-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.triggers-table {
  width: 100%;
  border-collapse: collapse;
  padding: 0;
  overflow: hidden;
}
.triggers-table th,
.triggers-table td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--mc-border, rgba(0, 0, 0, 0.06));
  text-align: left;
  font-size: 13px;
  vertical-align: top;
}
.triggers-table th {
  font-weight: 600;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.04));
}
.pattern-detail {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  opacity: 0.7;
  margin-top: 2px;
}
.empty-row {
  text-align: center;
  opacity: 0.6;
  padding: 24px;
}
.toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.actions {
  display: flex;
  gap: 6px;
}
.form-card {
  padding: 16px;
}
.form-card header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
  align-items: center;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
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
}
.form-grid textarea {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
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
.form-card footer {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
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

/* Mobile / tablet: the trigger table has 9 columns and breaks on
   phones. Wrap it in a horizontal-scroll container, collapse the
   form to one column, and stack the page header. */
@media (max-width: 900px) {
  .triggers-table {
    display: block;
    overflow-x: auto;
    white-space: nowrap;
  }
  .triggers-table thead,
  .triggers-table tbody,
  .triggers-table tr {
    display: table;
    width: 100%;
    table-layout: auto;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
  .form-grid .span-2 {
    grid-column: span 1;
  }
  .mc-page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}
</style>
