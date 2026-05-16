<template>
  <div class="settings-section">
    <div class="section-header">
      <div>
        <h2 class="section-title">{{ t('security.workspaces.title') }}</h2>
        <p class="section-desc">{{ t('security.workspaces.desc') }}</p>
      </div>
      <button class="btn-primary" @click="openCreateDialog">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        {{ t('security.workspaces.newWorkspace') }}
      </button>
    </div>

    <!-- Workspaces Table -->
    <div class="rules-table-wrapper">
      <div v-if="loading" class="empty-state">{{ t('security.workspaces.loading') }}</div>
      <div v-else-if="workspaces.length === 0" class="empty-state">{{ t('security.workspaces.noWorkspaces') }}</div>
      <table v-else class="rules-table">
        <thead>
          <tr>
            <th>{{ t('security.workspaces.columns.name') }}</th>
            <th>{{ t('security.workspaces.columns.slug') }}</th>
            <th>{{ t('security.workspaces.columns.description') }}</th>
            <th>{{ t('security.workspaces.columns.basePath') }}</th>
            <th>{{ t('security.workspaces.columns.created') }}</th>
            <th style="width: 120px;">{{ t('security.workspaces.columns.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="ws in workspaces" :key="ws.id" :class="{ 'current-ws': ws.id === currentWorkspaceId }">
            <td>
              <div class="ws-name-cell">
                <span class="ws-name">{{ ws.name }}</span>
                <span v-if="ws.id === currentWorkspaceId" class="ws-badge">{{ t('security.workspaces.current') }}</span>
              </div>
            </td>
            <td class="slug-cell">{{ ws.slug }}</td>
            <td class="desc-cell">{{ ws.description || '-' }}</td>
            <td class="slug-cell">{{ ws.basePath || '-' }}</td>
            <td class="date-cell">{{ formatDate(ws.createTime) }}</td>
            <td>
              <div class="action-btns">
                <button class="action-btn" @click="openEditDialog(ws)" :title="t('security.workspaces.actions.edit')">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                  </svg>
                </button>
                <button
                  v-if="ws.slug !== 'default'"
                  class="action-btn danger"
                  @click="confirmDelete(ws)"
                  :title="t('security.workspaces.actions.delete')"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="3 6 5 6 21 6"/><path d="M19 6l-2 14H7L5 6"/>
                    <path d="M10 11v6"/><path d="M14 11v6"/>
                    <path d="M9 6V4h6v2"/>
                  </svg>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Create / Edit Dialog -->
    <Teleport to="body">
      <div v-if="showDialog" class="modal-overlay">
        <div class="modal">
          <div class="modal-header">
            <h3>{{ editingWs ? t('security.workspaces.editDialog.title') : t('security.workspaces.createDialog.title') }}</h3>
            <button class="modal-close" @click="showDialog = false">&times;</button>
          </div>
          <div class="modal-body">
            <div class="form-grid" style="grid-template-columns: 1fr;">
              <div class="form-group">
                <label>{{ t('security.workspaces.createDialog.name') }} <span class="required">*</span></label>
                <input v-model="form.name" class="form-input" :placeholder="t('security.workspaces.createDialog.namePlaceholder')" @input="autoSlug" />
              </div>
              <div class="form-group">
                <label>{{ t('security.workspaces.createDialog.slug') }} <span class="required">*</span></label>
                <input
                  v-model="form.slug"
                  class="form-input mono"
                  :placeholder="t('security.workspaces.createDialog.slugPlaceholder')"
                  :disabled="!!editingWs"
                />
                <span v-if="editingWs" class="form-hint">{{ t('security.workspaces.createDialog.slugHint') }}</span>
              </div>
              <div class="form-group">
                <label>{{ t('security.workspaces.createDialog.description') }}</label>
                <input v-model="form.description" class="form-input" :placeholder="t('security.workspaces.createDialog.descriptionPlaceholder')" />
              </div>
              <div class="form-group">
                <label>{{ t('security.workspaces.createDialog.basePath') }}</label>
                <input v-model="form.basePath" class="form-input mono" :placeholder="t('security.workspaces.createDialog.basePathPlaceholder')" />
                <span class="form-hint">{{ t('security.workspaces.createDialog.basePathHint') }}</span>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="showDialog = false">{{ t('security.workspaces.actions.cancel') }}</button>
            <button class="btn-primary" @click="saveWorkspace" :disabled="!form.name || !form.slug">
              {{ editingWs ? t('security.workspaces.actions.save') : t('security.workspaces.actions.create') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- Delete Confirmation -->
    <Teleport to="body">
      <div v-if="showDeleteConfirm" class="modal-overlay">
        <div class="modal">
          <div class="modal-header">
            <h3>{{ t('security.workspaces.deleteDialog.title') }}</h3>
            <button class="modal-close" @click="showDeleteConfirm = false">&times;</button>
          </div>
          <div class="modal-body">
            <p class="delete-warning">
              {{ t('security.workspaces.deleteDialog.confirm', { name: deletingWs?.name }) }}
            </p>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="showDeleteConfirm = false">{{ t('security.workspaces.actions.cancel') }}</button>
            <button class="btn-primary btn-danger-fill" @click="deleteWorkspace">{{ t('security.workspaces.actions.delete') }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { workspaceTeamApi } from '@/api/index'
import { useWorkspaceStore, type Workspace } from '@/stores/useWorkspaceStore'

const { t } = useI18n()
const wsStore = useWorkspaceStore()
const currentWorkspaceId = computed(() => wsStore.currentWorkspaceId)

const workspaces = ref<Workspace[]>([])
const loading = ref(false)
const showDialog = ref(false)
const showDeleteConfirm = ref(false)
const editingWs = ref<Workspace | null>(null)
const deletingWs = ref<Workspace | null>(null)

const form = ref({
  name: '',
  slug: '',
  description: '',
  basePath: '',
})

onMounted(() => {
  fetchWorkspaces()
})

async function fetchWorkspaces() {
  loading.value = true
  try {
    const res: any = await workspaceTeamApi.list()
    workspaces.value = res.data || []
  } catch (e: any) {
    mcToast.error(e.message || 'Failed to fetch workspaces')
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  editingWs.value = null
  form.value = { name: '', slug: '', description: '', basePath: '' }
  showDialog.value = true
}

function openEditDialog(ws: Workspace) {
  editingWs.value = ws
  form.value = {
    name: ws.name,
    slug: ws.slug,
    description: ws.description || '',
    basePath: ws.basePath || '',
  }
  showDialog.value = true
}

function autoSlug() {
  if (!editingWs.value) {
    form.value.slug = form.value.name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '')
  }
}

async function saveWorkspace() {
  try {
    if (editingWs.value) {
      await workspaceTeamApi.update(editingWs.value.id, {
        name: form.value.name,
        description: form.value.description,
        basePath: form.value.basePath || null,
      })
    } else {
      await workspaceTeamApi.create({
        name: form.value.name,
        slug: form.value.slug,
        description: form.value.description,
        basePath: form.value.basePath || null,
      })
    }
    showDialog.value = false
    mcToast.success(t('security.workspaces.messages.saveSuccess'))
    await fetchWorkspaces()
    wsStore.fetchWorkspaces()
  } catch (e: any) {
    mcToast.error(t('security.workspaces.messages.saveFailed'))
  }
}

function confirmDelete(ws: Workspace) {
  deletingWs.value = ws
  showDeleteConfirm.value = true
}

async function deleteWorkspace() {
  if (!deletingWs.value) return
  try {
    await workspaceTeamApi.delete(deletingWs.value.id)
    showDeleteConfirm.value = false
    deletingWs.value = null
    mcToast.success(t('security.workspaces.messages.deleteSuccess'))
    await fetchWorkspaces()
    wsStore.fetchWorkspaces()
  } catch (e: any) {
    mcToast.error(t('security.workspaces.messages.deleteFailed'))
  }
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString()
}
</script>

<style>
@import '../shared.css';
</style>

<style scoped>
.current-ws {
  background: rgba(217, 119, 87, 0.06);
}

.ws-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ws-name { font-weight: 500; }

.ws-badge {
  font-size: 11px;
  padding: 2px 8px;
  background: var(--mc-primary, #D97757);
  color: #fff;
  border-radius: 10px;
  font-weight: 500;
}

.slug-cell {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 12px;
  color: var(--mc-text-secondary);
}

.desc-cell {
  color: var(--mc-text-secondary);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.date-cell {
  color: var(--mc-text-tertiary);
  font-size: 13px;
}

.required { color: var(--mc-danger, #ef4444); }

.form-hint {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin-top: 4px;
}

.delete-warning {
  font-size: 14px;
  color: var(--mc-text-primary);
  line-height: 1.6;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.btn-danger-fill {
  background: var(--mc-danger, #ef4444) !important;
}
.btn-danger-fill:hover { opacity: 0.9; }
</style>
