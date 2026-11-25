<template>
  <section class="team-coordinator mc-surface-card">
    <label class="team-coordinator__toggle">
      <input type="checkbox" :checked="enabled" :disabled="saving || (!enabled && unsupported)" @change="changeMode" />
      <span>{{ t('teamCoordinator.title') }}</span>
    </label>
    <p>{{ t('teamCoordinator.hint') }}</p>
    <p>{{ t('teamCoordinator.nextTurn') }}</p>
    <p v-if="unsupported">{{ t('teamCoordinator.nativeOnly') }}</p>
    <p v-if="saving" role="status">{{ t('teamCoordinator.saving') }}</p>
    <p v-if="error" role="alert" class="team-coordinator__error">{{ error }}</p>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { teamApi, type TeamVO } from '@/api'

const props = defineProps<{ teamId: string; settings: string | null; leadRuntimeType?: string }>()
const emit = defineEmits<{ saved: [team: TeamVO] }>()
const { t } = useI18n()
const enabled = ref(false)
const saving = ref(false)
const error = ref('')
const unsupported = computed(() => (props.leadRuntimeType || '').toLowerCase() !== 'native')
let active = true
onBeforeUnmount(() => { active = false })
watch(() => props.settings, settings => {
  if (saving.value) return
  try { enabled.value = JSON.parse(settings || '{}')?.coordinatorMode === true } catch { enabled.value = false }
}, { immediate: true })

async function changeMode(event: Event) {
  const next = (event.target as HTMLInputElement).checked
  if (saving.value || (next && unsupported.value)) return
  const previous = enabled.value
  enabled.value = next; saving.value = true; error.value = ''
  try {
    // Send a patch; the backend merges it with the latest team settings.
    const response = await teamApi.update(props.teamId, { settings: JSON.stringify({ coordinatorMode: next }) })
    if (active) emit('saved', response.data)
  } catch (e) {
    if (active) {
      enabled.value = previous
      error.value = e instanceof Error ? e.message : t('teamCoordinator.saveFailed')
    }
  } finally {
    if (active) saving.value = false
  }
}
</script>

<style scoped>
.team-coordinator { padding: 18px; margin-bottom: 18px; }
.team-coordinator__toggle { display: flex; align-items: center; gap: 10px; font-weight: 600; }
.team-coordinator p { margin: 8px 0 0; font-size: 13px; line-height: 1.6; color: var(--mc-text-secondary); }
.team-coordinator .team-coordinator__error { color: var(--mc-danger); }
</style>
