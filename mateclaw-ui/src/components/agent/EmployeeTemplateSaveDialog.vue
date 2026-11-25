<template>
  <div class="employee-template-overlay" @keydown.esc="!saving && emit('close')">
    <section class="employee-template-dialog" role="dialog" aria-modal="true" aria-labelledby="employee-template-title">
      <header class="employee-template-heading">
        <h2 id="employee-template-title">{{ t('employeeTemplates.saveTitle') }}</h2>
        <button type="button" :disabled="saving" :aria-label="t('common.close')" @click="emit('close')">×</button>
      </header>
      <form @submit.prevent="save">
        <p class="employee-template-hint">{{ t('employeeTemplates.snapshotHint') }}</p>
        <label class="employee-template-field">
          {{ t('employeeTemplates.name') }}
          <input v-model="name" data-template-name required maxlength="128" autofocus />
        </label>
        <label class="employee-template-field">
          {{ t('employeeTemplates.description') }}
          <textarea v-model="description" maxlength="1024" rows="3" />
        </label>
        <fieldset class="employee-template-files">
          <legend>{{ t('employeeTemplates.documents') }}</legend>
          <p class="employee-template-hint">{{ t('employeeTemplates.documentsHint') }}</p>
          <p v-if="loading">{{ t('employeeTemplates.loading') }}</p>
          <p v-else-if="filesError" role="alert">{{ filesError }} <button type="button" @click="loadFiles">{{ t('common.retry') }}</button></p>
          <p v-else-if="files.length === 0" class="employee-template-hint">{{ t('employeeTemplates.noDocuments') }}</p>
          <label v-for="file in files" :key="file" class="employee-template-file">
            <input v-model="selectedFiles" type="checkbox" :value="file" :data-template-file="file" />{{ file }}
          </label>
        </fieldset>
        <p v-if="error" role="alert" class="employee-template-error">{{ error }}</p>
        <footer class="employee-template-actions">
          <button type="button" :disabled="saving" @click="emit('close')">{{ t('common.cancel') }}</button>
          <button data-template-save type="submit" :disabled="saving || loading || !name.trim()">{{ saving ? t('employeeTemplates.saving') : t('employeeTemplates.save') }}</button>
        </footer>
      </form>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { agentContextApi, customTemplateApi } from '@/api'
import './employeeTemplates.css'

const props = defineProps<{ source: { id: string | number; name: string; description?: string | null } }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const { t } = useI18n()
const name = ref(props.source.name)
const description = ref(props.source.description || '')
const selectedFiles = ref<string[]>([])
const files = ref<string[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const filesError = ref('')
const seedFiles = new Set(['AGENTS.md', 'SOUL.md', 'TOOLS.md', 'IDENTITY.md', 'HEARTBEAT.md', 'PROACTIVE.md'])
let active = true
onBeforeUnmount(() => { active = false })

async function loadFiles() {
  loading.value = true
  filesError.value = ''
  try {
    const response = await agentContextApi.listFiles(props.source.id)
    if (!active) return
    const rows = response.data as Array<{ filename: string; scope?: string }>
    files.value = [...new Set(rows.filter(row => (row.scope === 'TEAM' || row.scope === 'GLOBAL') && seedFiles.has(row.filename)).map(row => row.filename))]
    selectedFiles.value = selectedFiles.value.filter(file => files.value.includes(file))
  } catch (e) {
    if (active) filesError.value = e instanceof Error ? e.message : t('employeeTemplates.loadFailed')
  } finally {
    if (active) loading.value = false
  }
}

async function save() {
  if (saving.value || loading.value || !name.value.trim()) return
  saving.value = true
  error.value = ''
  try {
    await customTemplateApi.save({ sourceAgentId: String(props.source.id), name: name.value.trim(), description: description.value.trim(), workspaceFileNames: [...selectedFiles.value] })
    if (active) emit('saved')
  } catch (e) {
    if (active) error.value = e instanceof Error ? e.message : t('employeeTemplates.saveFailed')
  } finally {
    if (active) saving.value = false
  }
}
onMounted(loadFiles)
</script>
