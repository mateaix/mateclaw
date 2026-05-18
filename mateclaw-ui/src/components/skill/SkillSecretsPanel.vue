<template>
  <div class="skill-secrets">
    <p class="detail-hint">{{ t('skills.detail.secretsHint') }}</p>

    <div v-if="loading" class="detail-empty">{{ t('common.loading') }}</div>

    <table v-else-if="rows.length > 0" class="secrets-table">
      <thead>
        <tr>
          <th class="secrets-table__key">{{ t('skills.detail.secretKey') }}</th>
          <th class="secrets-table__preview">{{ t('skills.detail.secretValue') }}</th>
          <th class="secrets-table__updated">{{ t('skills.detail.secretUpdatedAt') }}</th>
          <th class="secrets-table__actions"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.key">
          <td class="secrets-table__key"><code>{{ row.key }}</code></td>
          <td class="secrets-table__preview">{{ row.preview }}</td>
          <td class="secrets-table__updated">{{ formatTime(row.updatedAt) }}</td>
          <td class="secrets-table__actions">
            <button class="secrets-row-btn" @click="openEdit(row.key)">
              {{ t('skills.detail.secretEdit') }}
            </button>
            <button class="secrets-row-btn secrets-row-btn--danger" @click="removeSecret(row.key)">
              {{ t('skills.detail.secretDelete') }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <p v-else class="detail-empty">{{ t('skills.detail.secretsEmpty') }}</p>

    <div class="secrets-footer">
      <button class="secret-btn secret-btn-primary" @click="openAdd">
        {{ t('skills.detail.secretAdd') }}
      </button>
    </div>

    <!-- Add / edit dialog. Plaintext is required to overwrite — the backend's
         POST endpoint treats empty value as a delete, so we surface that
         clearly rather than silently swapping verbs. -->
    <Teleport to="body">
      <div v-if="dialogVisible" class="secret-modal-overlay" @click.self="closeDialog">
        <div class="secret-modal">
          <div class="secret-modal-header">
            <h2>
              {{ dialogMode === 'add'
                ? t('skills.detail.secretAddTitle')
                : t('skills.detail.secretEditTitle') }}
            </h2>
            <button class="secret-modal-close" @click="closeDialog">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
          <div class="secret-modal-body">
            <p class="secret-modal-hint">{{ t('skills.detail.secretDialogHint') }}</p>
            <div class="secret-form-group">
              <label class="secret-form-label">{{ t('skills.detail.secretKey') }} *</label>
              <input
                v-model="form.key"
                class="secret-form-input"
                :disabled="dialogMode === 'edit'"
                :placeholder="t('skills.detail.secretKeyPlaceholder')"
                @keydown.enter="save"
              />
              <p class="secret-form-hint">{{ t('skills.detail.secretKeyRule') }}</p>
            </div>
            <div class="secret-form-group">
              <label class="secret-form-label">{{ t('skills.detail.secretValue') }} *</label>
              <input
                v-model="form.value"
                class="secret-form-input"
                type="password"
                autocomplete="off"
                :placeholder="t('skills.detail.secretValuePlaceholder')"
                @keydown.enter="save"
              />
            </div>
          </div>
          <div class="secret-modal-footer">
            <button class="secret-btn secret-btn-secondary" @click="closeDialog">
              {{ t('common.cancel') }}
            </button>
            <button
              class="secret-btn secret-btn-primary"
              :disabled="!canSave || saving"
              @click="save"
            >
              {{ saving ? t('common.loading') : t('common.save') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import { skillApi, type SkillSecretSummary } from '@/api'

const props = defineProps<{
  skillId: number | string | null
  /** Bumped by the parent when the panel becomes visible so we re-fetch. */
  visible: boolean
}>()

const { t } = useI18n()

const rows = ref<SkillSecretSummary[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const form = ref({ key: '', value: '' })
const saving = ref(false)

// POSIX-ish env-var key shape — same regex the backend's KEY_PATTERN
// enforces. We pre-validate client-side so the user sees the rule instead
// of a generic 4xx from the server.
const KEY_RE = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/

const canSave = computed(() =>
  form.value.value.length > 0 && KEY_RE.test(form.value.key.trim()))

async function load() {
  if (props.skillId == null) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const res: any = await skillApi.listSecrets(props.skillId)
    rows.value = (res?.data ?? []) as SkillSecretSummary[]
  } catch (e: any) {
    rows.value = []
    mcToast.error(typeof e === 'string' ? e : e?.message || t('skills.detail.secretLoadFailed'))
  } finally {
    loading.value = false
  }
}

watch(() => [props.skillId, props.visible], () => {
  if (props.visible && props.skillId != null) load()
}, { immediate: true })

function openAdd() {
  dialogMode.value = 'add'
  form.value = { key: '', value: '' }
  dialogVisible.value = true
}

function openEdit(key: string) {
  dialogMode.value = 'edit'
  // Plaintext is never returned from the server, so the value field starts
  // empty — saving overwrites whatever was there. Surface this in the hint.
  form.value = { key, value: '' }
  dialogVisible.value = true
}

function closeDialog() {
  dialogVisible.value = false
}

async function save() {
  if (!canSave.value || props.skillId == null) return
  saving.value = true
  try {
    await skillApi.putSecret(props.skillId, form.value.key.trim(), form.value.value)
    mcToast.success(t('skills.detail.secretSaveSuccess'))
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    mcToast.error(typeof e === 'string' ? e : e?.message || t('skills.detail.secretSaveFailed'))
  } finally {
    saving.value = false
  }
}

async function removeSecret(key: string) {
  if (props.skillId == null) return
  const ok = await mcConfirm({
    title: t('common.confirm'),
    message: t('skills.detail.secretDeleteConfirm', { key }),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await skillApi.deleteSecret(props.skillId, key)
    mcToast.success(t('skills.detail.secretDeleteSuccess'))
    await load()
  } catch (e: any) {
    mcToast.error(typeof e === 'string' ? e : e?.message || t('skills.detail.secretDeleteFailed'))
  }
}

function formatTime(s?: string): string {
  if (!s) return '—'
  // Backend ships LocalDateTime as ISO-ish "2026-05-12T16:23:33". The Date
  // ctor handles that, and the user's locale formatting is good enough for
  // a "last updated" column.
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s
  return d.toLocaleString()
}
</script>

<style scoped>
/* Panel layout */
.skill-secrets { display: flex; flex-direction: column; gap: 12px; }

/* Reuse the drawer's .detail-hint sizing locally so the panel doesn't
   depend on parent-scoped styles leaking in (Vue scoped CSS doesn't
   cross component boundaries). */
.detail-hint {
  font-size: 13px;
  color: var(--mc-text-secondary);
  line-height: 1.6;
  margin: 0;
}
.detail-empty {
  font-size: 13px;
  color: var(--mc-text-tertiary);
  padding: 24px 12px;
  text-align: center;
}

/* Secrets table */
.secrets-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  background: var(--mc-bg);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  overflow: hidden;
}
.secrets-table th,
.secrets-table td {
  text-align: left;
  padding: 10px 14px;
  border-bottom: 1px solid var(--mc-border-light);
}
.secrets-table tr:last-child td { border-bottom: none; }
.secrets-table th {
  font-weight: 600;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-sunken);
  font-size: 12px;
  letter-spacing: 0.02em;
}
.secrets-table__key { width: 36%; }
.secrets-table__preview {
  width: 26%;
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--mc-text-primary);
}
.secrets-table__updated {
  width: 22%;
  color: var(--mc-text-tertiary);
  font-size: 12px;
}
.secrets-table__actions {
  width: 16%;
  white-space: nowrap;
  text-align: right;
}
.secrets-table code {
  font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace;
  background: var(--mc-bg-sunken);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  color: var(--mc-text-primary);
}

/* Per-row action buttons (compact, secondary) */
.secrets-row-btn {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  color: var(--mc-text-primary);
  padding: 4px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  margin-left: 6px;
  transition: background 120ms, border-color 120ms;
}
.secrets-row-btn:hover { background: var(--mc-bg-sunken); }
.secrets-row-btn--danger {
  color: var(--el-color-danger);
  border-color: var(--el-color-danger-light-5);
}
.secrets-row-btn--danger:hover {
  background: var(--el-color-danger-light-9);
  border-color: var(--el-color-danger);
}

.secrets-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 4px;
}

/* Modal — local styles so the panel stays self-contained.
   Mirrors the look of ImportHubDialog.vue / SkillMarket.vue's modal,
   but namespaced (.secret-*) so we don't compete with global styles. */
.secret-modal-overlay {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.45);
  display: flex; align-items: center; justify-content: center;
  z-index: 2000;
  padding: 20px;
}
.secret-modal {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 16px;
  width: 100%;
  max-width: 480px;
  max-height: 85vh;
  display: flex; flex-direction: column;
  box-shadow: 0 20px 60px rgba(0,0,0,0.18);
}
.secret-modal-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 22px;
  border-bottom: 1px solid var(--mc-border-light);
}
.secret-modal-header h2 {
  font-size: 17px; font-weight: 600;
  color: var(--mc-text-primary); margin: 0;
}
.secret-modal-close {
  width: 32px; height: 32px;
  border: none; background: none; cursor: pointer;
  color: var(--mc-text-tertiary);
  display: flex; align-items: center; justify-content: center;
  border-radius: 6px;
}
.secret-modal-close:hover {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}
.secret-modal-body {
  padding: 18px 22px;
  display: flex; flex-direction: column; gap: 14px;
  overflow-y: auto;
}
.secret-modal-hint {
  font-size: 12px;
  color: var(--mc-text-secondary);
  margin: 0;
  line-height: 1.5;
}
.secret-modal-footer {
  display: flex; justify-content: flex-end; gap: 10px;
  padding: 14px 22px;
  border-top: 1px solid var(--mc-border-light);
}

/* Form */
.secret-form-group {
  display: flex; flex-direction: column; gap: 6px;
}
.secret-form-label {
  font-size: 13px; font-weight: 500;
  color: var(--mc-text-primary);
}
.secret-form-input {
  padding: 9px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  font-size: 14px;
  color: var(--mc-text-primary);
  background: var(--mc-bg-sunken);
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
  font-family: inherit;
}
.secret-form-input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 2px rgba(217,119,87,0.15);
}
.secret-form-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  background: var(--mc-bg);
}
.secret-form-hint {
  margin: 0;
  font-size: 12px;
  color: var(--mc-text-tertiary);
}

/* Buttons */
.secret-btn {
  display: inline-flex; align-items: center; justify-content: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
  border: 1px solid transparent;
  font-family: inherit;
}
.secret-btn-primary {
  background: var(--mc-primary);
  color: white;
}
.secret-btn-primary:hover { background: var(--mc-primary-hover); }
.secret-btn-primary:disabled {
  background: var(--mc-border);
  cursor: not-allowed;
}
.secret-btn-secondary {
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border-color: var(--mc-border);
}
.secret-btn-secondary:hover { background: var(--mc-bg-sunken); }
</style>
