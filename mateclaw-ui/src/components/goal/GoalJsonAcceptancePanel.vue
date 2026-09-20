<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import ManagedGoalJsonVersions from './ManagedGoalJsonVersions.vue'
import { goalJsonAcceptanceApi, type GoalJsonAcceptanceView, type GoalJsonRequirement } from '@/api/goalJsonAcceptance'

const props = defineProps<{ goalId: string; status: string }>()
const { t } = useI18n()
const expanded = ref(false)
const view = ref<GoalJsonAcceptanceView | null>(null)
const loading = ref(false)
const saving = ref(false)
const conflict = ref(false)
const error = ref('')
const key = ref('')
const slot = ref('')
const fields = ref('')
const revision = ref('0')
let generation = 0
const currentStatus = computed(() => ['active', 'paused'].includes(props.status) ? view.value?.status : props.status)
const editable = computed(() => !!view.value && ['active', 'paused'].includes(currentStatus.value ?? ''))
const busy = computed(() => loading.value || saving.value)

function resetForm() { key.value = ''; slot.value = ''; fields.value = ''; revision.value = '0'; conflict.value = false }
function edit(requirement: GoalJsonRequirement) {
  if (busy.value) return
  key.value = requirement.criterionKey
  slot.value = requirement.artifactSlot
  fields.value = requirement.requiredFields.join('\n')
  revision.value = requirement.revision
  conflict.value = false
  error.value = ''
}
function failureCode(failure: unknown) {
  const e = failure as { code?: number; response?: { status?: number } }
  return e.response?.status ?? e.code
}
function showFailure(failure: unknown) {
  const code = failureCode(failure)
  if (code === 401 || code === 403 || code === 404) {
    view.value = null
    resetForm()
    error.value = 'goalJsonAcceptance.accessError'
  } else if (code === 409) {
    conflict.value = true
    error.value = 'goalJsonAcceptance.conflict'
  } else error.value = code === 400 ? 'goalJsonAcceptance.inputError' : 'goalJsonAcceptance.loadError'
}
async function load() {
  if (busy.value || !props.goalId) return
  const request = ++generation
  loading.value = true
  error.value = ''
  view.value = null
  resetForm()
  try {
    const { data } = await goalJsonAcceptanceApi.get(props.goalId)
    if (request === generation) view.value = data
  } catch (failure) {
    if (request === generation) showFailure(failure)
  } finally { if (request === generation) loading.value = false }
}
async function save() {
  if (busy.value || conflict.value || !view.value || !editable.value) return
  const required = fields.value.split(/\r?\n/).filter(field => field.length > 0)
  const validKey = (value: string) => /^[a-z][a-z0-9_-]{0,63}$/.test(value)
  if (!validKey(key.value) || !validKey(slot.value) || required.length < 1 || required.length > 16
      || new Set(required).size !== required.length || required.some(field => !field.trim() || field.length > 128 || /[\x00-\x1f\x7f-\x9f]/.test(field))) {
    error.value = 'goalJsonAcceptance.inputError'
    return
  }
  const request = generation
  saving.value = true
  error.value = ''
  try {
    const { data } = await goalJsonAcceptanceApi.configure(props.goalId, key.value, {
      expectedRevision: revision.value, artifactSlot: slot.value, requiredFields: required,
    })
    if (request !== generation || !view.value) return
    view.value = { required: true, status: view.value.status, requirements: [...view.value.requirements.filter(r => r.criterionKey !== data.criterionKey), data]
      .sort((a, b) => a.criterionKey.localeCompare(b.criterionKey)) }
    resetForm()
  } catch (failure) {
    if (request === generation) showFailure(failure)
  } finally { if (request === generation) saving.value = false }
}
function toggle() { expanded.value = !expanded.value; if (expanded.value && !view.value) void load() }
watch(() => props.goalId, () => {
  generation++
  view.value = null
  loading.value = false
  saving.value = false
  error.value = ''
  resetForm()
  if (expanded.value) void load()
})
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <section class="json-acceptance">
    <button type="button" data-json-acceptance-toggle :aria-expanded="expanded" @click="toggle">{{ t('goalJsonAcceptance.title') }}</button>
    <div v-if="expanded" class="json-acceptance__body" :aria-busy="busy">
      <p>{{ t('goalJsonAcceptance.scope') }}</p>
      <button type="button" data-json-acceptance-refresh :disabled="busy" @click="load">{{ t('goalJsonAcceptance.refresh') }}</button>
      <p v-if="error" role="alert">{{ t(error) }}</p>
      <p v-if="loading" role="status">{{ t('common.loading') }}</p>
      <template v-if="view">
        <p v-if="currentStatus" data-json-acceptance-status>{{ t('goalJsonAcceptance.historyStatus.' + currentStatus) }}</p>
        <p data-json-acceptance-mode>{{ t(view.required ? 'goalJsonAcceptance.required' : 'goalJsonAcceptance.notSelected') }}</p>
        <ul v-if="view.requirements.length">
          <li v-for="requirement in view.requirements" :key="requirement.criterionKey" data-json-requirement>
            <strong>{{ requirement.criterionKey }}</strong> · {{ requirement.artifactSlot }}
            <p>{{ requirement.requiredFields.join(', ') }}</p>
            <button v-if="editable" type="button" :disabled="busy" data-json-requirement-edit @click="edit(requirement)">{{ t('goalJsonAcceptance.edit') }}</button>
          </li>
        </ul>
        <ManagedGoalJsonVersions v-if="view.required" :goal-id="goalId" :status="currentStatus || status" :requirements="view.requirements" @access-lost="showFailure({ code: 403 })" />
        <form v-if="editable" @submit.prevent="save">
          <p>{{ t('goalJsonAcceptance.selectionNotice') }}</p>
          <label>{{ t('goalJsonAcceptance.key') }}<input v-model="key" data-json-requirement-key required maxlength="64" :disabled="busy || revision !== '0'" placeholder="report-fields" /></label>
          <label>{{ t('goalJsonAcceptance.slot') }}<input v-model="slot" data-json-requirement-slot required maxlength="64" :disabled="busy" placeholder="report" /></label>
          <label>{{ t('goalJsonAcceptance.fields') }}<textarea v-model="fields" data-json-requirement-fields required rows="3" maxlength="2064" :disabled="busy" :placeholder="t('goalJsonAcceptance.fieldsPlaceholder')" /></label>
          <p>{{ t('goalJsonAcceptance.inputHelp') }}</p>
          <div class="json-acceptance__actions">
            <button type="submit" data-json-requirement-save :disabled="busy || conflict || (revision === '0' && view.requirements.length >= 8)">{{ t(saving ? 'common.loading' : 'goalJsonAcceptance.save') }}</button>
            <button v-if="revision !== '0'" type="button" :disabled="busy" @click="resetForm">{{ t('goalJsonAcceptance.newRequirement') }}</button>
          </div>
        </form>
      </template>
    </div>
  </section>
</template>

<style scoped>
.json-acceptance { margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--mc-border-light); font-size: 12px; color: var(--mc-text-secondary); }
.json-acceptance__body, form, label { display: grid; gap: 8px; }
p { margin: 6px 0; line-height: 1.6; overflow-wrap: anywhere; }
ul { padding-left: 18px; display: grid; gap: 8px; }
button, input, textarea { border: 1px solid var(--mc-border-light); border-radius: 6px; background: transparent; color: var(--mc-text-primary); font: inherit; padding: 6px 9px; }
button { cursor: pointer; justify-self: start; }
button:disabled, input:disabled, textarea:disabled { opacity: .55; }
input, textarea { width: 100%; box-sizing: border-box; }
textarea { resize: vertical; }
button:focus-visible, input:focus-visible, textarea:focus-visible { outline: 2px solid var(--mc-primary); outline-offset: 2px; }
.json-acceptance__actions { display: flex; gap: 8px; flex-wrap: wrap; }
[role="alert"] { color: var(--mc-danger, #b53535); }
</style>
