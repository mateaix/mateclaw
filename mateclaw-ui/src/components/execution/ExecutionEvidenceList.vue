<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { executionEvidenceApi, type ExecutionEvidence, type ArtifactJsonCheck } from '@/api/executionEvidence'

const props = defineProps<{ conversationId: string; goalId?: string; teamTaskId?: string }>()
const { t } = useI18n()
const expanded = ref(false)
const loaded = ref(false)
const loading = ref(false)
const errorKey = ref('')
const items = ref<ExecutionEvidence[]>([])
const nextCursor = ref<string | null>(null)
const detailLoading = ref<Record<string, boolean>>({})
const detailErrors = ref<Record<string, string>>({})
const checkFields = ref<Record<string, string>>({})
const checkLoading = ref<Record<string, boolean>>({})
const checkErrors = ref<Record<string, string>>({})
const checkResults = ref<Record<string, ArtifactJsonCheck>>({})
let checkGeneration: Record<string, number> = {}
function invalidateCheck(id: string) {
  checkGeneration[id] = (checkGeneration[id] ?? 0) + 1
  delete checkLoading.value[id]
  delete checkErrors.value[id]
  delete checkResults.value[id]
}
function clearChecks() {
  checkGeneration = {}
  checkFields.value = {}
  checkLoading.value = {}
  checkErrors.value = {}
  checkResults.value = {}
}
let generation = 0

async function load(more = false) {
  if (loading.value || !props.conversationId) return
  const request = ++generation
  detailLoading.value = {}
  detailErrors.value = {}
  clearChecks()
  loading.value = true
  errorKey.value = ''
  try {
    const { data } = await executionEvidenceApi.list({
      conversationId: props.conversationId, goalId: props.goalId, teamTaskId: props.teamTaskId,
      cursor: more ? nextCursor.value ?? undefined : undefined, limit: 20,
    })
    if (request !== generation) return
    const merged = new Map((more ? items.value : []).map(item => [item.id, item]))
    data.items.forEach(item => merged.set(item.id, item))
    items.value = [...merged.values()]
    nextCursor.value = data.nextCursor
    loaded.value = true
  } catch (error) {
    if (request !== generation) return
    const failure = error as { code?: number; response?: { status?: number } }
    const code = failure.response?.status ?? failure.code
    const denied = code === 401 || code === 403 || code === 404
    if (denied) {
      items.value = []
      nextCursor.value = null
      loaded.value = false
      detailLoading.value = {}
      detailErrors.value = {}
      clearChecks()
    }
    errorKey.value = denied ? 'executionEvidence.accessError' : 'executionEvidence.loadError'
  } finally {
    if (request === generation) loading.value = false
  }
}
async function loadDetail(id: string, event: Event) {
  if (!(event.target as HTMLDetailsElement).open || detailLoading.value[id]) return
  const request = generation
  detailLoading.value[id] = true
  invalidateCheck(id)
  delete detailErrors.value[id]
  try {
    const { data } = await executionEvidenceApi.get(id)
    if (request !== generation) return
    items.value = items.value.map(item => item.id === id ? data : item)
  } catch (error) {
    if (request !== generation) return
    const failure = error as { code?: number; response?: { status?: number } }
    const code = failure.response?.status ?? failure.code
    if (code === 401 || code === 403 || code === 404) {
      items.value = items.value.filter(item => item.id !== id)
      errorKey.value = 'executionEvidence.accessError'
    } else {
      detailErrors.value[id] = 'executionEvidence.loadError'
    }
  } finally {
    if (request === generation) delete detailLoading.value[id]
  }
}
async function checkJson(id: string) {
  if (loading.value || detailLoading.value[id] || checkLoading.value[id]) return
  const fields = (checkFields.value[id] ?? '').split(/\r?\n/).filter(field => field.length > 0)
  delete checkResults.value[id]
  delete checkErrors.value[id]
  if (!fields.length || fields.length > 16 || new Set(fields).size !== fields.length
      || fields.some(field => !field.trim() || field.length > 128 || /[\x00-\x1f\x7f]/.test(field))) {
    checkErrors.value[id] = 'executionEvidence.jsonCheck.inputError'
    return
  }
  const request = generation
  const checkRequest = checkGeneration[id] ?? 0
  const isCurrent = () => request === generation && checkRequest === (checkGeneration[id] ?? 0)
  checkLoading.value[id] = true
  try {
    const { data } = await executionEvidenceApi.checkJson(id, fields)
    if (!isCurrent() || !items.value.some(item => item.id === id)) return
    checkResults.value[id] = data
    if (data.status === 'UNAVAILABLE') {
      items.value = items.value.map(item => item.id === id
        ? { ...item, artifactRef: null, artifactDigest: null, summary: null, validity: 'UNAVAILABLE' } : item)
    }
  } catch (error) {
    if (!isCurrent()) return
    const failure = error as { code?: number; response?: { status?: number } }
    const code = failure.response?.status ?? failure.code
    if (code === 401 || code === 403 || code === 404) {
      items.value = items.value.filter(item => item.id !== id)
      delete checkFields.value[id]
      errorKey.value = 'executionEvidence.accessError'
    } else {
      checkErrors.value[id] = code === 400 ? 'executionEvidence.jsonCheck.inputError' : 'executionEvidence.loadError'
    }
  } finally {
    if (isCurrent()) delete checkLoading.value[id]
  }
}
function toggle() {
  expanded.value = !expanded.value
  if (expanded.value && !loaded.value) void load()
}
watch(() => [props.conversationId, props.goalId, props.teamTaskId], () => {
  generation++
  items.value = []
  detailLoading.value = {}
  detailErrors.value = {}
  clearChecks()
  nextCursor.value = null
  errorKey.value = ''
  loaded.value = false
  loading.value = false
  if (expanded.value) void load()
})
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <section class="execution-evidence">
    <button type="button" data-evidence-toggle :aria-expanded="expanded" @click="toggle">
      {{ expanded ? '▾' : '▸' }} {{ t('executionEvidence.title') }}
    </button>
    <div v-if="expanded" class="execution-evidence__body" :aria-busy="loading">
      <p class="execution-evidence__notice">{{ t('executionEvidence.observationOnly') }}</p>
      <button type="button" :disabled="loading" @click="load()">{{ t('executionEvidence.refresh') }}</button>
      <p v-if="errorKey" role="alert">{{ t(errorKey) }}</p>
      <p v-if="loading" role="status">{{ t('common.loading') }}</p>
      <p v-else-if="!errorKey && loaded && !items.length">{{ t('executionEvidence.empty') }}</p>
      <ol class="execution-evidence__list">
        <li v-for="item in items" :key="item.id" :data-evidence-item="item.id">
          <strong>{{ item.toolName }}</strong>
          <span class="execution-evidence__kind">{{ t(`executionEvidence.kind.${item.kind}`) }}</span>
          <p v-if="item.summary" class="execution-evidence__summary">{{ item.summary }}</p>
          <dl>
            <div><dt>{{ t('executionEvidence.stateLabel') }}</dt><dd>{{ t(`executionEvidence.state.${item.state}`) }}</dd></div>
            <div><dt>{{ t('executionEvidence.resultLabel') }}</dt><dd>{{ t(`executionEvidence.result.${item.result}`) }}</dd></div>
            <div><dt>{{ t('executionEvidence.effectLabel') }}</dt><dd>{{ t(`executionEvidence.effect.${item.effectOutcome}`) }}</dd></div>
            <div><dt>{{ t('executionEvidence.validityLabel') }}</dt><dd>{{ t(`executionEvidence.validity.${item.validity}`) }}</dd></div>
            <div v-if="item.checkScope"><dt>{{ t('executionEvidence.scope') }}</dt><dd>{{ item.checkScope }}</dd></div>
            <div><dt>{{ t('executionEvidence.source') }}</dt><dd>{{ item.sourceLevel }}</dd></div>
            <div><dt>{{ t('executionEvidence.observedAt') }}</dt><dd><time :datetime="item.observedAt">{{ item.observedAt }}</time></dd></div>
            <div v-if="item.expiresAt"><dt>{{ t('executionEvidence.expiresAt') }}</dt><dd><time :datetime="item.expiresAt">{{ item.expiresAt }}</time></dd></div>
          </dl>
          <details @toggle="loadDetail(item.id, $event)" :aria-busy="!!detailLoading[item.id]">
            <summary>{{ t('executionEvidence.details') }}</summary>
            <p v-if="detailLoading[item.id]" role="status">{{ t('common.loading') }}</p>
            <p v-if="detailErrors[item.id]" role="alert">{{ t(detailErrors[item.id]) }}</p>
            <dl>
              <div><dt>{{ t('executionEvidence.id') }}</dt><dd>{{ item.id }}</dd></div>
              <div><dt>{{ t('executionEvidence.attemptId') }}</dt><dd>{{ item.attemptId }}</dd></div>
              <div v-if="item.artifactRef"><dt>{{ t('executionEvidence.artifactRef') }}</dt><dd>{{ item.artifactRef }}</dd></div>
              <div v-if="item.artifactDigest"><dt>{{ t('executionEvidence.digest') }}</dt><dd>{{ item.artifactDigest }}</dd></div>
            </dl>
            <form v-if="item.kind === 'ARTIFACT_SNAPSHOT' && item.artifactRef" class="json-check" @submit.prevent="checkJson(item.id)">
              <label :for="`json-fields-${item.id}`">{{ t('executionEvidence.jsonCheck.label') }}</label>
              <textarea :id="`json-fields-${item.id}`" v-model="checkFields[item.id]" data-json-fields rows="3" maxlength="2064"
                :disabled="loading || !!detailLoading[item.id] || !!checkLoading[item.id]" :placeholder="t('executionEvidence.jsonCheck.placeholder')"
                @input="delete checkResults[item.id]" />
              <p>{{ t('executionEvidence.jsonCheck.scope') }}</p>
              <button type="submit" data-json-check :disabled="loading || !!detailLoading[item.id] || !!checkLoading[item.id]">{{ t(checkLoading[item.id] ? 'common.loading' : 'executionEvidence.jsonCheck.run') }}</button>
            </form>
            <p v-if="checkErrors[item.id]" role="alert">{{ t(checkErrors[item.id]) }}</p>
            <div v-if="checkResults[item.id]" data-json-result role="status">
              <p>{{ t(`executionEvidence.jsonCheck.status.${checkResults[item.id]!.status}`) }}</p>
              <p v-if="checkResults[item.id]!.missingFields.length">{{ checkResults[item.id]!.missingFields.join(', ') }}</p>
              <p>{{ t('executionEvidence.jsonCheck.limitation') }}</p>
              <time :datetime="checkResults[item.id]!.checkedAt">{{ checkResults[item.id]!.checkedAt }}</time>
            </div>
          </details>
        </li>
      </ol>
      <button v-if="nextCursor" type="button" data-evidence-more :disabled="loading" @click="load(true)">{{ t('executionEvidence.loadMore') }}</button>
    </div>
  </section>
</template>

<style scoped>
.execution-evidence { margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--mc-border-light); font-size: 12px; color: var(--mc-text-secondary); }
button { background: transparent; color: var(--mc-text-primary); border: 1px solid var(--mc-border-light); border-radius: 6px; padding: 6px 9px; cursor: pointer; font: inherit; }
button:disabled { opacity: .5; cursor: wait; }
button:focus-visible, summary:focus-visible { outline: 2px solid var(--mc-primary); outline-offset: 2px; }
.execution-evidence__notice { color: var(--mc-text-tertiary); line-height: 1.6; }
.execution-evidence__list { list-style: none; padding: 0; margin: 10px 0; display: grid; gap: 10px; }
li { padding: 10px; border: 1px solid var(--mc-border-light); border-radius: 8px; min-width: 0; overflow-wrap: anywhere; }
.execution-evidence__kind { display: block; color: var(--mc-text-tertiary); margin-top: 4px; }
.execution-evidence__summary { white-space: pre-wrap; }
dl { margin: 8px 0; display: grid; gap: 5px; }
dl > div { display: grid; grid-template-columns: minmax(75px, 1fr) minmax(0, 2fr); gap: 8px; }
dt { color: var(--mc-text-tertiary); }
dd { margin: 0; overflow-wrap: anywhere; }
summary { cursor: pointer; }
.json-check { display: grid; gap: 6px; margin-top: 12px; }
.json-check p { margin: 0; line-height: 1.5; }
.json-check textarea { width: 100%; box-sizing: border-box; resize: vertical; font: inherit; color: var(--mc-text-primary); background: transparent; border: 1px solid var(--mc-border-light); border-radius: 6px; padding: 6px; }
.json-check textarea:focus-visible { outline: 2px solid var(--mc-primary); }
.json-check button { justify-self: start; }
[role="alert"] { color: var(--mc-danger, #b53535); }
</style>
