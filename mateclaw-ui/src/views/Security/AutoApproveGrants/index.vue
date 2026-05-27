<template>
  <div class="settings-section">
    <!--
      Header layout follows the McpServers / ToolGuard style: section-header
      container with btn-secondary / btn-primary native buttons on the right.
      Icons come from @element-plus/icons-vue per the user's directive, even
      though the rest of the page uses native CSS buttons — el-icon renders
      inline cleanly inside a regular button.

      The three actions deliberately sit at different visual weights:
        - btn-primary "新增策略"  → the daily action, dominant.
        - btn-secondary "刷新"   → utility, equal-but-second.
        - 危险路径 "创建全工具白名单" 折到 "更多 ⋯" 下拉，红色文字 — 不会再
          以与日常操作并排同等地位的姿态出现。
    -->
    <div class="section-header">
      <div class="section-header__title">
        <h2 class="section-title">{{ t('approval.grant.title') }}</h2>
        <el-tag
          v-if="total > 0"
          type="warning"
          size="small"
          effect="light"
          round
          disable-transitions
          class="active-summary"
        >
          {{ t('approval.grant.chipLabel', { count: total }) }}
        </el-tag>
        <p class="section-desc">{{ t('approval.grant.desc') }}</p>
      </div>
      <div class="section-header__actions">
        <button class="btn-secondary" @click="loadGrants" :disabled="loading">
          <el-icon :size="14" :class="{ spin: loading }"><Refresh /></el-icon>
          {{ t('common.refresh') }}
        </button>
        <button class="btn-primary" @click="openCreateDialog(false)">
          <el-icon :size="14"><Plus /></el-icon>
          {{ t('approval.grant.createBtn') }}
        </button>
        <el-dropdown trigger="click" placement="bottom-end" @command="onMoreCommand">
          <button class="btn-secondary btn-more" :title="t('common.more')">
            <el-icon :size="16"><MoreFilled /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="workspaceWide" class="danger-item">
                <el-icon><Unlock /></el-icon>
                {{ t('approval.grant.createWorkspaceBtn') }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <div class="config-card">
      <el-table
        v-loading="loading"
        :data="rows"
        :empty-text="t('approval.grant.empty')"
        size="small"
        stripe
      >
        <el-table-column :label="t('approval.grant.columns.scope')" min-width="180">
          <template #default="{ row }">
            <el-tag
              :type="scopeTagType(row.scopeType)"
              size="small"
              effect="light"
              disable-transitions
            >
              {{ t(`approval.grant.scope.${scopeI18nKey(row.scopeType)}`) }}
            </el-tag>
            <span class="scope-id">{{ row.scopeId }}</span>
          </template>
        </el-table-column>

        <el-table-column
          :label="t('approval.grant.columns.tool')"
          prop="toolName"
          min-width="140"
        >
          <template #default="{ row }">
            <code v-if="row.toolName" class="mono">{{ row.toolName }}</code>
            <el-tag v-else type="danger" size="small" effect="dark">∗ any</el-tag>
          </template>
        </el-table-column>

        <el-table-column :label="t('approval.grant.columns.rule')" min-width="140">
          <template #default="{ row }">
            <code v-if="row.ruleId" class="mono">{{ row.ruleId }}</code>
            <span v-else class="muted">∗</span>
          </template>
        </el-table-column>

        <el-table-column
          :label="t('approval.grant.columns.severity')"
          prop="maxSeverity"
          width="110"
        >
          <template #default="{ row }">
            <el-tag :type="severityTagType(row.maxSeverity)" size="small" disable-transitions>
              {{ row.maxSeverity }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column :label="t('approval.grant.columns.kind')" width="140">
          <template #default="{ row }">
            {{ t(`approval.grant.kind.${kindI18nKey(row.grantKind)}`) }}
          </template>
        </el-table-column>

        <el-table-column :label="t('approval.grant.columns.expire')" width="160">
          <template #default="{ row }">
            <span class="muted">{{ formatDate(row.expireAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column
          :label="t('approval.grant.columns.grantedBy')"
          prop="grantedBy"
          width="120"
        />

        <el-table-column :label="t('approval.grant.columns.note')" min-width="160">
          <template #default="{ row }">
            <span :title="row.note || ''" class="note-cell">{{ row.note }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="t('approval.grant.columns.actions')" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.revoked === 0"
              :icon="Delete"
              type="danger"
              size="small"
              text
              @click="confirmRevoke(row)"
            >
              {{ t('approval.grant.revokeBtn') }}
            </el-button>
            <el-tag v-else type="info" size="small" effect="plain" disable-transitions>
              {{ t('common.revoked') }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > 0"
        class="grants-pagination"
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        small
        @size-change="loadGrants"
        @current-change="loadGrants"
      />
    </div>

    <!-- Create dialog -->
    <el-dialog
      v-model="dialogOpen"
      :title="dialogWorkspaceWide ? t('approval.grant.createWorkspaceBtn') : t('approval.grant.createBtn')"
      width="560px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-alert
        v-if="dialogWorkspaceWide"
        type="warning"
        :closable="false"
        show-icon
        class="warning-banner"
      >
        {{ t('approval.grant.createWorkspaceWarning') }}
      </el-alert>

      <el-form :model="form" label-width="120px" class="grant-form">
        <el-form-item :label="t('approval.grant.form.scopeType')">
          <el-select v-model="form.scopeType" :disabled="dialogWorkspaceWide" style="width: 100%">
            <el-option label="CONVERSATION" value="CONVERSATION" />
            <el-option label="AGENT" value="AGENT" />
            <el-option label="USER" value="USER" />
            <el-option label="WORKSPACE" value="WORKSPACE" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('approval.grant.form.scopeId')">
          <el-input
            v-model.trim="form.scopeId"
            type="text"
            inputmode="numeric"
            pattern="\d*"
            placeholder="snowflake id"
          />
        </el-form-item>
        <el-form-item :label="t('approval.grant.form.toolName')">
          <el-input v-model.trim="form.toolName" :disabled="dialogWorkspaceWide" />
        </el-form-item>
        <el-form-item :label="t('approval.grant.form.ruleId')">
          <el-input v-model.trim="form.ruleId" placeholder="(optional)" />
        </el-form-item>
        <el-form-item :label="t('approval.grant.form.maxSeverity')">
          <el-select v-model="form.maxSeverity" style="width: 100%">
            <el-option label="LOW" value="LOW" />
            <el-option label="MEDIUM" value="MEDIUM" />
            <el-option label="HIGH" value="HIGH" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('approval.grant.form.grantKind')">
          <el-select v-model="form.grantKind" style="width: 100%">
            <el-option :label="t('approval.grant.kind.always')" value="ALWAYS" />
            <el-option :label="t('approval.grant.kind.until')" value="UNTIL_TIMESTAMP" />
            <el-option :label="t('approval.grant.kind.conversationEnd')" value="UNTIL_CONVERSATION_END" />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="form.grantKind === 'UNTIL_TIMESTAMP'"
          :label="t('approval.grant.form.expireAt')"
        >
          <el-date-picker
            v-model="form.expireAt"
            type="datetime"
            style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
        </el-form-item>
        <el-form-item :label="t('approval.grant.form.note')">
          <el-input v-model.trim="form.note" />
        </el-form-item>
        <el-form-item
          v-if="requiresPassword"
          :label="t('approval.grant.form.password')"
        >
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :prefix-icon="Lock"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Delete,
  Lock,
  MoreFilled,
  Plus,
  Refresh,
  Unlock,
} from '@element-plus/icons-vue'
import { approvalApi } from '@/api'
import type {
  ApprovalGrant,
  CreateGrantPayload,
  GrantScope,
  GrantKind,
  GrantSeverity,
} from '@/types'

const { t } = useI18n()

const rows = ref<ApprovalGrant[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const loading = ref(false)

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
  const noTool = !form.toolName
  return noTool && (form.scopeType === 'WORKSPACE' || form.scopeType === 'AGENT')
})

async function loadGrants() {
  loading.value = true
  try {
    const res = await approvalApi.listGrants({
      page: currentPage.value,
      size: pageSize.value,
    })
    const data = (res as any).data ?? res
    // Backend serializes Long as string (snowflake precision convention); coerce
    // numeric page metadata at the boundary so el-pagination gets real numbers.
    rows.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total ?? 0)
  } catch (e: any) {
    ElMessage.error(e?.message || 'Failed to load grants')
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onMoreCommand(cmd: string | number | object) {
  if (cmd === 'workspaceWide') {
    openCreateDialog(true)
  }
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
    // Reset to page 1 so the just-created row is visible at the top.
    currentPage.value = 1
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

function scopeTagType(scope: GrantScope): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  switch (scope) {
    case 'CONVERSATION': return 'primary'
    case 'AGENT': return 'warning'
    case 'USER': return 'success'
    case 'WORKSPACE': return 'danger'
  }
}

function severityTagType(sev: GrantSeverity): 'success' | 'warning' | 'danger' {
  switch (sev) {
    case 'LOW': return 'success'
    case 'MEDIUM': return 'warning'
    case 'HIGH': return 'danger'
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
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s
  return d.toLocaleString()
}

onMounted(loadGrants)
</script>

<style scoped>
@import '@/views/Security/shared.css';

/* Title row: title + small summary tag on one line, description below. The
   tag sits next to the title so the daily-glance question — "is auto-approve
   active in this workspace right now?" — gets answered without scrolling. */
.section-header__title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 12px;
}
.section-header__title .section-title { margin: 0; }
.section-header__title .section-desc {
  flex-basis: 100%;
  margin: 4px 0 0;
}
.active-summary {
  font-weight: 600;
}

/* Header actions: matches McpServers / ToolGuard layout (.section-header__actions
   + native .btn-primary / .btn-secondary). Visual hierarchy intentionally
   primary > secondary > "more …" so the daily action dominates and the
   workspace-wide rule lives one click deeper. */
.section-header__actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-shrink: 0; /* keep buttons from being squeezed by the title block */
}
.section-header__actions .btn-primary,
.section-header__actions .btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap; /* prevent labels from wrapping into vertical text */
}
.btn-more {
  padding-left: 8px;
  padding-right: 8px;
}
.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Danger items inside the More dropdown — visually distinct red text so the
   workspace-wide path always feels like a dangerous action even when collapsed
   into the overflow menu. */
:global(.el-dropdown-menu__item.danger-item) {
  color: var(--mc-danger, #b91c1c);
}
:global(.el-dropdown-menu__item.danger-item:hover) {
  background: #fef2f2;
  color: #991b1b;
}

.scope-id {
  margin-left: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--mc-text-tertiary, #94a3b8);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: var(--mc-surface-tertiary, #f1f5f9);
  padding: 1px 6px;
  border-radius: 3px;
}

.note-cell {
  display: inline-block;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.muted {
  color: var(--mc-text-tertiary, #94a3b8);
  font-size: 12px;
}

.grants-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.warning-banner {
  margin-bottom: 16px;
}

.grant-form {
  padding-top: 4px;
}
</style>
