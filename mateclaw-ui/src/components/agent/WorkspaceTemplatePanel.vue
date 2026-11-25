<template>
  <section class="employee-template-panel" aria-labelledby="workspace-template-title">
    <header class="employee-template-heading">
      <h3 id="workspace-template-title">{{ t('employeeTemplates.workspaceTitle') }}</h3>
      <button type="button" :disabled="loading || !!pending" @click="load">{{ t('common.refresh') }}</button>
    </header>
    <p class="employee-template-hint">{{ t('employeeTemplates.workspaceHint') }}</p>
    <p v-if="error" role="alert" class="employee-template-error">{{ error }}</p>
    <p v-if="loading">{{ t('employeeTemplates.loading') }}</p>
    <p v-else-if="!templates.length" class="employee-template-hint">{{ t('employeeTemplates.empty') }}</p>
    <article v-for="template in templates" :key="template.id" data-workspace-template class="employee-template-card">
      <h4>{{ template.name }}</h4>
      <p>{{ template.description }}</p>
      <label class="employee-template-field">
        {{ t('employeeTemplates.employeeName') }}
        <input v-model="hireNames[template.id]" data-template-hire-name required maxlength="128" />
      </label>
      <div class="employee-template-actions">
        <button data-template-hire type="button" :disabled="!!pending || !hireNames[template.id]?.trim()" @click="hire(template)">{{ t('employeeTemplates.hire') }}</button>
        <button v-if="template.canDelete && confirmingDelete !== template.id" data-template-delete type="button" :disabled="!!pending" @click="confirmingDelete = template.id">{{ t('common.delete') }}</button>
      </div>
      <div v-if="confirmingDelete === template.id" class="employee-template-actions">
        <span>{{ t('employeeTemplates.confirmDelete') }}</span>
        <button type="button" :disabled="!!pending" @click="confirmingDelete = ''">{{ t('common.cancel') }}</button>
        <button data-template-confirm-delete type="button" :disabled="!!pending" @click="remove(template)">{{ t('common.delete') }}</button>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { customTemplateApi, type WorkspaceEmployeeTemplate } from '@/api'
import './employeeTemplates.css'

const emit = defineEmits<{ hired: [] }>()
const { t } = useI18n()
const templates = ref<WorkspaceEmployeeTemplate[]>([])
const hireNames = ref<Record<string, string>>({})
const loading = ref(true)
const pending = ref('')
const confirmingDelete = ref('')
const error = ref('')
let active = true
onBeforeUnmount(() => { active = false })

async function load() {
  if (pending.value) return
  loading.value = true; error.value = ''
  try {
    const response = await customTemplateApi.list()
    if (!active) return
    templates.value = response.data
    for (const template of templates.value) hireNames.value[template.id] ||= t('employeeTemplates.copyName', { name: template.name })
  } catch (e) {
    if (active) error.value = e instanceof Error ? e.message : t('employeeTemplates.loadFailed')
  } finally {
    if (active) loading.value = false
  }
}
async function hire(template: WorkspaceEmployeeTemplate) {
  const name = hireNames.value[template.id]?.trim()
  if (pending.value || !name) return
  pending.value = template.id; error.value = ''
  try {
    await customTemplateApi.apply(template.id, { name })
    if (active) emit('hired')
  } catch (e) {
    if (active) error.value = e instanceof Error ? e.message : t('employeeTemplates.hireFailed')
  } finally {
    if (active) pending.value = ''
  }
}
async function remove(template: WorkspaceEmployeeTemplate) {
  if (pending.value || !template.canDelete || confirmingDelete.value !== template.id) return
  pending.value = template.id; error.value = ''
  try {
    await customTemplateApi.delete(template.id)
    if (active) {
      templates.value = templates.value.filter(item => item.id !== template.id)
      confirmingDelete.value = ''
    }
  } catch (e) {
    if (active) error.value = e instanceof Error ? e.message : t('employeeTemplates.deleteFailed')
  } finally {
    if (active) pending.value = ''
  }
}
onMounted(load)
</script>
