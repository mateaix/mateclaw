<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { goalJsonAcceptanceApi as api, type GoalJsonRequirement, type ManagedJsonArtifact, type ManagedJsonSnapshot } from '@/api/goalJsonAcceptance'
const props = defineProps<{ goalId: string; status: string; requirements: GoalJsonRequirement[] }>()
const emit = defineEmits<{ accessLost: [] }>()
const { t } = useI18n()
const expanded = ref(false)
const snapshot = ref<ManagedJsonSnapshot | null>(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const loadedAt = ref('')
const conflict = ref(false)
const selectedSlot = ref('')
const draft = ref('')
const inspected = ref<{ artifact: ManagedJsonArtifact; jsonContent: string } | null>(null)
let epoch = 0
const writable = computed(() => ['active', 'paused'].includes(props.status) && !!snapshot.value && ['active', 'paused'].includes(snapshot.value.status))
const selected = computed(() => snapshot.value?.slots.find(slot => slot.artifactSlot === selectedSlot.value))
const current = (slot: string) => snapshot.value?.slots.find(item => item.artifactSlot === slot)?.current
const checkState = (key: string) => snapshot.value?.checks.find(item => item.criterionKey === key)
const states = new Set(['MATCH', 'MISSING_FIELDS', 'INVALID_JSON', 'UNKNOWN', 'NO_ARTIFACT', 'EXPIRED', 'CORRUPT', 'UNBOUND', 'REQUIREMENT_CHANGED', 'GOAL_CHANGED', 'SUPERSEDED', 'RECIPE_CHANGED'])
const stateLabel = (key: string) => { const state = checkState(key)?.status ?? 'UNKNOWN'; return t(`goalJsonArtifacts.state.${states.has(state) ? state : 'UNKNOWN'}`) }
const time = (value: string) => { const date = new Date(value); return Number.isNaN(date.getTime()) ? t('goalJsonArtifacts.unknownTime') : date.toLocaleString() }
function clearContent() { snapshot.value = null; inspected.value = null; draft.value = ''; selectedSlot.value = ''; notice.value = ''; loadedAt.value = '' }
function failed(failure: unknown) {
  const e = failure as { code?: number; response?: { status?: number } }
  const code = e.response?.status ?? e.code
  if ([401, 403, 404].includes(code ?? 0)) { clearContent(); error.value = 'goalJsonAcceptance.accessError'; emit('accessLost') }
  else if (code === 409) { conflict.value = true; inspected.value = null; error.value = 'goalJsonArtifacts.conflict' }
  else error.value = code === 400 ? 'goalJsonArtifacts.invalid' : 'goalJsonArtifacts.loadError'
}
async function fetchSnapshot(request: number, goalId: string) {
  const { data } = await api.snapshot(goalId)
  if (request !== epoch) return
  snapshot.value = data
  loadedAt.value = new Date().toISOString()
  if (!data.slots.some(slot => slot.artifactSlot === selectedSlot.value)) selectedSlot.value = data.slots[0]?.artifactSlot ?? ''
}
async function reload() {
  if (busy.value) return
  const request = ++epoch, goalId = props.goalId
  busy.value = true; error.value = ''; conflict.value = false; clearContent()
  try { await fetchSnapshot(request, goalId) }
  catch (failure) { if (request === epoch) failed(failure) }
  finally { if (request === epoch) busy.value = false }
}
async function publish() {
  if (busy.value || conflict.value || !writable.value || !selected.value || (snapshot.value?.versionCount ?? 32) >= 32) return
  try {
    if (new TextEncoder().encode(draft.value).length > 1_048_576) throw new Error()
    const value: unknown = JSON.parse(draft.value)
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error()
  } catch { error.value = 'goalJsonArtifacts.invalid'; return }
  const request = epoch, goalId = props.goalId
  const slot = selected.value
  busy.value = true; error.value = ''; notice.value = ''; inspected.value = null
  try {
    await api.publish(goalId, slot.artifactSlot, { expectedGeneration: slot.generation, jsonContent: draft.value })
    if (request !== epoch) return
    draft.value = ''; notice.value = 'goalJsonArtifacts.published'
    await fetchSnapshot(request, goalId)
  } catch (failure) { if (request === epoch) failed(failure) }
  finally { if (request === epoch) busy.value = false }
}
async function check(requirement: GoalJsonRequirement) {
  const artifact = current(requirement.artifactSlot)
  if (busy.value || conflict.value || !writable.value || !artifact) return
  const request = epoch, goalId = props.goalId
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const { data } = await api.check(goalId, requirement.criterionKey, { expectedRequirementRevision: requirement.revision, artifactId: artifact.artifactId, expectedGeneration: artifact.generation })
    if (request !== epoch) return
    notice.value = data.acceptanceEligible ? 'goalJsonArtifacts.checked' : 'goalJsonArtifacts.notMatched'
    await fetchSnapshot(request, goalId)
  } catch (failure) { if (request === epoch) failed(failure) }
  finally { if (request === epoch) busy.value = false }
}
async function inspect(artifact: ManagedJsonArtifact) {
  if (busy.value) return
  const request = epoch, goalId = props.goalId
  busy.value = true; error.value = ''; inspected.value = null
  try {
    const { data } = await api.version(goalId, artifact.artifactId)
    if (request === epoch && data.artifact.artifactId === artifact.artifactId) inspected.value = data
  } catch (failure) { if (request === epoch) failed(failure) }
  finally { if (request === epoch) busy.value = false }
}
function toggle() { expanded.value = !expanded.value; if (expanded.value && !snapshot.value) void reload() }
watch(() => [props.goalId, props.status, props.requirements], () => {
  epoch++; busy.value = false; error.value = ''; conflict.value = false; clearContent()
  if (expanded.value) void reload()
}, { deep: true })
onBeforeUnmount(() => { epoch++ })
</script>

<template>
  <section class="managed-json">
    <button type="button" data-json-versions-toggle :aria-expanded="expanded" @click="toggle">{{ t('goalJsonArtifacts.title') }}</button>
    <div v-if="expanded" class="managed-json__content" :aria-busy="busy">
      <p>{{ t('goalJsonArtifacts.scope') }}</p>
      <button type="button" data-json-versions-refresh :disabled="busy" @click="reload">{{ t('goalJsonArtifacts.refresh') }}</button>
      <p v-if="error" role="alert">{{ t(error) }}</p>
      <p v-if="busy" role="status">{{ t('common.loading') }}</p>
      <p v-if="notice" role="status">{{ t(notice) }}</p>
      <template v-if="snapshot">
        <p>{{ t('goalJsonArtifacts.loaded', { time: time(loadedAt) }) }}</p>
        <p>{{ t('goalJsonArtifacts.quota', { count: snapshot.versionCount }) }}</p>
        <p v-if="snapshot.versionCount >= 32" role="status">{{ t('goalJsonArtifacts.quotaFull') }}</p>
        <article v-for="requirement in snapshot.requirements" :key="requirement.criterionKey" data-json-managed-requirement>
          <strong>{{ requirement.criterionKey }}</strong>
          <p>{{ t('goalJsonArtifacts.requirement', { revision: requirement.revision, fields: requirement.requiredFields.join(', ') }) }}</p>
          <p data-json-binding-status :class="{ 'managed-json__matched': checkState(requirement.criterionKey)?.acceptanceEligible }">{{ stateLabel(requirement.criterionKey) }}</p>
          <template v-if="current(requirement.artifactSlot)">
            <p>{{ t('goalJsonArtifacts.version', { slot: requirement.artifactSlot, generation: current(requirement.artifactSlot)!.generation }) }}</p>
            <p>{{ t('goalJsonArtifacts.expires', { time: time(current(requirement.artifactSlot)!.expiresAt) }) }}</p>
            <div class="managed-json__actions">
              <button type="button" data-json-version-inspect :disabled="busy" @click="inspect(current(requirement.artifactSlot)!)">{{ t('goalJsonArtifacts.inspect') }}</button>
              <button v-if="writable" type="button" data-json-managed-check :disabled="busy || conflict" @click="check(requirement)">{{ t('goalJsonArtifacts.check') }}</button>
            </div>
          </template>
        </article>
        <div v-if="inspected" data-json-version-content>
          <p>{{ t('goalJsonArtifacts.immutable') }} <code>{{ inspected.artifact.artifactId }}</code></p>
          <p>SHA-256: <code>{{ inspected.artifact.sha256 }}</code></p>
          <pre>{{ inspected.jsonContent }}</pre>
        </div>
        <form v-if="writable && snapshot.slots.length" @submit.prevent="publish">
          <label>{{ t('goalJsonArtifacts.slot') }}<select v-model="selectedSlot" data-json-publish-slot :disabled="busy || conflict"><option v-for="slot in snapshot.slots" :key="slot.artifactSlot" :value="slot.artifactSlot">{{ slot.artifactSlot }}</option></select></label>
          <label>{{ t('goalJsonArtifacts.content') }}<textarea v-model="draft" data-json-publish-content rows="7" maxlength="1048576" :disabled="busy || conflict" placeholder='{"summary":"…"}' /></label>
          <button type="submit" data-json-publish :disabled="busy || conflict || snapshot.versionCount >= 32">{{ t('goalJsonArtifacts.publish') }}</button>
        </form>
      </template>
    </div>
  </section>
</template>

<style scoped>
.managed-json { border-top: 1px solid var(--mc-border-light); padding-top: 10px; margin-top: 10px; }
.managed-json__content, form, label { display: grid; gap: 8px; }
article { border: 1px solid var(--mc-border-light); border-radius: 7px; padding: 10px; }
p { margin: 4px 0; line-height: 1.6; overflow-wrap: anywhere; }
button, select, textarea { border: 1px solid var(--mc-border-light); border-radius: 6px; padding: 6px 9px; background: transparent; color: var(--mc-text-primary); font: inherit; }
button { cursor: pointer; justify-self: start; }
button:disabled, select:disabled, textarea:disabled { opacity: .55; }
select, textarea { width: 100%; box-sizing: border-box; }
textarea { resize: vertical; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 280px; overflow: auto; padding: 10px; background: var(--mc-bg-secondary); border-radius: 6px; }
code { overflow-wrap: anywhere; }
.managed-json__actions { display: flex; flex-wrap: wrap; gap: 8px; }
.managed-json__matched { color: var(--mc-success, #27804a); }
[role="alert"] { color: var(--mc-danger, #b53535); }
button:focus-visible, select:focus-visible, textarea:focus-visible { outline: 2px solid var(--mc-primary); outline-offset: 2px; }
</style>
