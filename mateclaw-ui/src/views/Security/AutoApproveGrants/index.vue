<template>
  <div class="settings-section">
    <div class="section-header">
      <div>
        <h2 class="section-title">{{ t('approval.grant.title') }}</h2>
        <p class="section-desc">{{ t('approval.grant.desc') }}</p>
      </div>
      <div class="header-actions">
        <button class="btn-secondary" @click="openCreateDialog(false)">
          {{ t('approval.grant.createBtn') }}
        </button>
        <button class="btn-danger" @click="openCreateDialog(true)">
          🔓 {{ t('approval.grant.createWorkspaceBtn') }}
        </button>
      </div>
    </div>

    <!-- Grants table -->
    <div class="config-card">
      <table v-if="grants.length" class="grants-table">
        <thead>
          <tr>
            <th>{{ t('approval.grant.columns.scope') }}</th>
            <th>{{ t('approval.grant.columns.tool') }}</th>
            <th>{{ t('approval.grant.columns.rule') }}</th>
            <th>{{ t('approval.grant.columns.severity') }}</th>
            <th>{{ t('approval.grant.columns.kind') }}</th>
            <th>{{ t('approval.grant.columns.expire') }}</th>
            <th>{{ t('approval.grant.columns.grantedBy') }}</th>
            <th>{{ t('approval.grant.columns.note') }}</th>
            <th>{{ t('approval.grant.columns.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="g in grants" :key="g.id" :class="{ 'row-revoked': g.revoked === 1 }">
            <td>
              <span class="scope-badge" :class="`scope-${g.scopeType.toLowerCase()}`">
                {{ t(`approval.grant.scope.${scopeI18nKey(g.scopeType)}`) }}
              </span>
              <span class="scope-id">{{ g.scopeId }}</span>
            </td>
            <td>{{ g.toolName ?? '∗' }}</td>
            <td>{{ g.ruleId ?? '∗' }}</td>
            <td>{{ g.maxSeverity }}</td>
            <td>{{ t(`approval.grant.kind.${kindI18nKey(g.grantKind)}`) }}</td>
            <td>{{ formatDate(g.expireAt) }}</td>
            <td>{{ g.grantedBy }}</td>
            <td class="note-cell" :title="g.note ?? ''">{{ g.note }}</td>
            <td>
              <button
                v-if="g.revoked === 0"
                class="btn-link"
                @click="confirmRevoke(g)">
                {{ t('approval.grant.revokeBtn') }}
              </button>
              <span v-else class="muted">{{ t('common.revoked') }}</span>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">
        {{ t('approval.grant.empty') }}
      </div>
    </div>

    <!-- Create dialog -->
    <div v-if="dialogOpen" class="modal-backdrop" @click.self="dialogOpen = false">
      <div class="modal-card">
        <div class="modal-header">
          <h3>{{ t('approval.grant.createBtn') }}</h3>
          <button class="modal-close" @click="dialogOpen = false">&times;</button>
        </div>

        <div v-if="dialogWorkspaceWide" class="warning-banner">
          ⚠️ {{ t('approval.grant.createWorkspaceWarning') }}
        </div>

        <div class="modal-body">
          <div class="form-row">
            <label>{{ t('approval.grant.form.scopeType') }}</label>
            <select v-model="form.scopeType" :disabled="dialogWorkspaceWide">
              <option value="CONVERSATION">{{ t('approval.grant.scope.conversation') }}</option>
              <option value="AGENT">{{ t('approval.grant.scope.agent') }}</option>
              <option value="USER">{{ t('approval.grant.scope.user') }}</option>
              <option value="WORKSPACE">{{ t('approval.grant.scope.workspace') }}</option>
            </select>
          </div>
          <div class="form-row">
            <label>{{ t('approval.grant.form.scopeId') }}</label>
            <input
              v-model.trim="form.scopeId"
              type="text"
              inputmode="numeric"
              pattern="\d*"
              placeholder="snowflake id" />
          </div>
          <div class="form-row">
            <label>{{ t('approval.grant.form.toolName') }}</label>
            <input v-model.trim="form.toolName" type="text" :disabled="dialogWorkspaceWide" />
          </div>
          <div class="form-row">
            <label>{{ t('approval.grant.form.ruleId') }}</label>
            <input v-model.trim="form.ruleId" type="text" placeholder="(optional)" />
          </div>
          <div class="form-row">
            <label>{{ t('approval.grant.form.maxSeverity') }}</label>
            <select v-model="form.maxSeverity">
              <option value="LOW">LOW</option>
              <option value="MEDIUM">MEDIUM</option>
              <option value="HIGH">HIGH</option>
            </select>
          </div>
          <div class="form-row">
            <label>{{ t('approval.grant.form.grantKind') }}</label>
            <select v-model="form.grantKind">
              <option value="ALWAYS">{{ t('approval.grant.kind.always') }}</option>
              <option value="UNTIL_TIMESTAMP">{{ t('approval.grant.kind.until') }}</option>
              <option value="UNTIL_CONVERSATION_END">{{ t('approval.grant.kind.conversationEnd') }}</option>
            </select>
          </div>
          <div v-if="form.grantKind === 'UNTIL_TIMESTAMP'" class="form-row">
            <label>{{ t('approval.grant.form.expireAt') }}</label>
            <input v-model="form.expireAt" type="datetime-local" />
          </div>
          <div class="form-row">
            <label>{{ t('approval.grant.form.note') }}</label>
            <input v-model.trim="form.note" type="text" />
          </div>
          <div v-if="requiresPassword" class="form-row">
            <label>{{ t('approval.grant.form.password') }}</label>
            <input v-model="form.password" type="password" />
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="dialogOpen = false">{{ t('common.cancel') }}</button>
          <button
            class="btn-primary"
            :disabled="creating"
            @click="submitCreate">
            {{ creating ? t('common.processing') : t('common.confirm') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { approvalApi } from '@/api'
import type {
  ApprovalGrant,
  CreateGrantPayload,
  GrantScope,
  GrantKind,
  GrantSeverity,
} from '@/types'

const { t } = useI18n()

const grants = ref<ApprovalGrant[]>([])
const dialogOpen = ref(false)
const dialogWorkspaceWide = ref(false)
const creating = ref(false)

interface FormState {
  scopeType: GrantScope
  scopeId: string
  toolName: string
  ruleId: string
  maxSeverity: GrantSeverity
  grantKind: GrantKind
  expireAt: string
  note: string
  password: string
}

const form = reactive<FormState>(emptyForm())

function emptyForm(): FormState {
  return {
    scopeType: 'CONVERSATION',
    scopeId: '',
    toolName: '',
    ruleId: '',
    maxSeverity: 'LOW',
    grantKind: 'ALWAYS',
    expireAt: '',
    note: '',
    password: '',
  }
}

const requiresPassword = computed(() => {
  // Backend §2.4.5: password is required for (WORKSPACE/AGENT + tool=null).
  const noTool = !form.toolName
  return noTool && (form.scopeType === 'WORKSPACE' || form.scopeType === 'AGENT')
})

async function loadGrants() {
  const res = await approvalApi.listGrants({ mine: false })
  const payload = (res as any).data ?? res
  grants.value = Array.isArray(payload) ? payload : []
}

function openCreateDialog(workspaceWide: boolean) {
  Object.assign(form, emptyForm())
  if (workspaceWide) {
    form.scopeType = 'WORKSPACE'
    form.toolName = ''
    form.maxSeverity = 'HIGH'
    dialogWorkspaceWide.value = true
  } else {
    dialogWorkspaceWide.value = false
  }
  dialogOpen.value = true
}

async function submitCreate() {
  if (!form.scopeId) {
    ElMessage.warning(t('approval.grant.form.scopeId'))
    return
  }
  creating.value = true
  try {
    const payload: CreateGrantPayload = {
      scopeType: form.scopeType,
      scopeId: form.scopeId,
      toolName: form.toolName || null,
      ruleId: form.ruleId || null,
      maxSeverity: form.maxSeverity,
      grantKind: form.grantKind,
      expireAt: form.expireAt || null,
      note: form.note || null,
    }
    if (requiresPassword.value) {
      if (!form.password) {
        ElMessage.warning(t('approval.grant.form.password'))
        creating.value = false
        return
      }
      payload.password = form.password
    }
    await approvalApi.createGrant(payload)
    ElMessage.success(t('common.success'))
    dialogOpen.value = false
    await loadGrants()
  } catch (e: any) {
    ElMessage.error(e?.message || 'Failed to create grant')
  } finally {
    creating.value = false
  }
}

async function confirmRevoke(g: ApprovalGrant) {
  try {
    await ElMessageBox.confirm(
      t('approval.grant.revokeConfirm'),
      t('approval.grant.revokeBtn'),
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await approvalApi.revokeGrant(g.id)
    ElMessage.success(t('common.success'))
    await loadGrants()
  } catch (e: any) {
    ElMessage.error(e?.message || 'Failed to revoke')
  }
}

function scopeI18nKey(scope: GrantScope): string {
  switch (scope) {
    case 'CONVERSATION': return 'conversation'
    case 'AGENT': return 'agent'
    case 'USER': return 'user'
    case 'WORKSPACE': return 'workspace'
  }
}

function kindI18nKey(kind: GrantKind): string {
  switch (kind) {
    case 'ALWAYS': return 'always'
    case 'UNTIL_TIMESTAMP': return 'until'
    case 'UNTIL_CONVERSATION_END': return 'conversationEnd'
  }
}

function formatDate(s: string | null): string {
  if (!s) return '—'
  return new Date(s).toLocaleString()
}

onMounted(loadGrants)
</script>

<style scoped>
@import '@/views/Security/shared.css';

.header-actions { display: flex; gap: 8px; align-items: center; }

.btn-danger {
  padding: 8px 14px;
  border-radius: 6px;
  border: 1px solid #ef4444;
  background: #fef2f2;
  color: #b91c1c;
  font-weight: 500;
  cursor: pointer;
}
.btn-danger:hover { background: #fee2e2; }

.grants-table { width: 100%; border-collapse: collapse; }
.grants-table th,
.grants-table td {
  padding: 10px 12px;
  font-size: 13px;
  border-bottom: 1px solid var(--mc-border-light, #e5e7eb);
  text-align: left;
}
.grants-table th { font-weight: 600; color: var(--mc-text-secondary, #64748b); }
.row-revoked { opacity: 0.5; }
.scope-badge {
  display: inline-block;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  margin-right: 6px;
}
.scope-conversation { background: #e0f2fe; color: #075985; }
.scope-agent { background: #fef3c7; color: #92400e; }
.scope-user { background: #ddd6fe; color: #5b21b6; }
.scope-workspace { background: #fee2e2; color: #991b1b; }
.scope-id { font-family: ui-monospace, SFMono-Regular, monospace; font-size: 12px; color: var(--mc-text-tertiary, #94a3b8); }
.note-cell { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.btn-link { background: none; border: none; color: #ef4444; cursor: pointer; padding: 0; }
.empty-state { padding: 32px; text-align: center; color: var(--mc-text-tertiary, #94a3b8); }
.muted { color: var(--mc-text-tertiary, #94a3b8); font-size: 12px; }

.modal-backdrop {
  position: fixed; inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex; align-items: center; justify-content: center;
  z-index: 1000;
}
.modal-card {
  background: var(--mc-surface-primary, #fff);
  border-radius: 8px;
  width: min(540px, 92vw);
  max-height: 88vh;
  display: flex; flex-direction: column;
}
.modal-header { padding: 16px 20px; border-bottom: 1px solid var(--mc-border-light, #e5e7eb); display: flex; justify-content: space-between; align-items: center; }
.modal-header h3 { margin: 0; font-size: 16px; }
.modal-close { background: none; border: none; font-size: 20px; cursor: pointer; color: var(--mc-text-tertiary, #94a3b8); }
.modal-body { padding: 16px 20px; overflow-y: auto; }
.modal-footer { padding: 12px 20px; border-top: 1px solid var(--mc-border-light, #e5e7eb); display: flex; justify-content: flex-end; gap: 8px; }
.warning-banner { background: #fef2f2; color: #991b1b; padding: 12px 20px; margin: 0; font-size: 13px; line-height: 1.5; border-bottom: 1px solid #fecaca; }
.form-row { display: grid; grid-template-columns: 140px 1fr; gap: 12px; align-items: center; margin-bottom: 12px; }
.form-row label { font-size: 13px; color: var(--mc-text-secondary, #64748b); }
.form-row input, .form-row select {
  padding: 6px 10px;
  border: 1px solid var(--mc-border-light, #e5e7eb);
  border-radius: 4px;
  font-size: 13px;
  background: var(--mc-surface-primary, #fff);
  color: var(--mc-text-primary, #0f172a);
}
.form-row input:disabled, .form-row select:disabled { background: var(--mc-surface-tertiary, #f1f5f9); cursor: not-allowed; }
</style>
