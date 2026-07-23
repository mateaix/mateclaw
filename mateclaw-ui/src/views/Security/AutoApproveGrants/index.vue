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
        <!-- Inline summary pill — same "danger-tinted info" tokens as the
             sidebar chip so the two surfaces feel like one design language.
             No el-tag here: keeps the page entirely native + token-driven.
             Bound to activeCount (from /approval/grants/active) NOT total —
             total includes revoked rows since the list endpoint returns
             history by default, and the pill messaging ("已启用") only
             makes sense for grants that are currently in force. -->
        <span v-if="activeCount > 0" class="summary-pill">
          {{ t('approval.grant.chipLabel', { count: activeCount }) }}
        </span>
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

    <!--
      Table + pagination — native HTML, no Element Plus components. Mirrors
      the ToolGuard / AuditLogs pattern (rules-table-wrapper + .rules-table
      + .severity-badge / .action-btn from shared.css) so this page reads
      like any other Security sub-view. Pagination uses the shared
      McPagination component, which is also fully native (no el-pagination).
    -->
    <div class="rules-table-wrapper">
      <table class="rules-table">
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
            <th class="col-actions">{{ t('approval.grant.columns.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.id"
            :class="{ 'row-revoked': row.revoked === 1 }"
          >
            <td>
              <span
                class="scope-badge"
                :class="`scope-${scopeI18nKey(row.scopeType)}`"
              >
                {{ t(`approval.grant.scope.${scopeI18nKey(row.scopeType)}`) }}
              </span>
              <div class="scope-id" :title="row.scopeId">{{ row.scopeId }}</div>
            </td>
            <td>
              <code v-if="row.toolName" class="mono">{{ row.toolName }}</code>
              <span v-else class="severity-badge severity-high">∗ any</span>
            </td>
            <td>
              <code v-if="row.ruleId" class="mono">{{ row.ruleId }}</code>
              <span v-else class="muted">∗</span>
            </td>
            <td>
              <span
                class="severity-badge"
                :class="`severity-${row.maxSeverity?.toLowerCase()}`"
              >
                {{ row.maxSeverity }}
              </span>
            </td>
            <td>{{ t(`approval.grant.kind.${kindI18nKey(row.grantKind)}`) }}</td>
            <td class="muted">{{ formatDate(row.expireAt) }}</td>
            <td>
              <span v-if="row.grantedByName" :title="`#${row.grantedBy}`">{{ row.grantedByName }}</span>
              <span v-else class="muted">#{{ row.grantedBy }}</span>
            </td>
            <td>
              <span class="note-cell" :title="row.note || ''">{{ row.note }}</span>
            </td>
            <td class="col-actions">
              <button
                v-if="row.revoked === 0"
                class="row-action-btn row-action-btn--danger"
                :title="t('approval.grant.revokeBtn')"
                @click="confirmRevoke(row)"
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none"
                     stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="3 6 5 6 21 6"/>
                  <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
                </svg>
                <span>{{ t('approval.grant.revokeBtn') }}</span>
              </button>
              <span v-else class="revoked-pill">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none"
                     stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="9"/>
                  <line x1="5.6" y1="5.6" x2="18.4" y2="18.4"/>
                </svg>
                {{ t('common.revoked') }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="loading" class="empty-state">{{ t('common.loading') }}</div>
      <div v-else-if="!rows.length" class="empty-state">{{ t('approval.grant.empty') }}</div>
    </div>

    <div v-if="total > 0" class="grants-pagination-row">
      <McPagination
        :page="currentPage"
        :size="pageSize"
        :total="total"
        :sizes="[10, 20, 50, 100]"
        @update:page="onPageChange"
        @update:size="onSizeChange"
      />
    </div>

    <!--
      Create dialog — matches the ToolGuard "edit rule" modal pattern:
      Teleport to body + native .modal-overlay / .modal / .modal-header /
      .modal-body / .modal-footer + .form-grid / .form-group / .form-input.
      The workspace-wide path reuses the same modal with a red warning banner
      at the top so the destructive variant looks identifiably different
      from the routine create flow.
    -->
    <Teleport to="body">
      <div v-if="dialogOpen" class="modal-overlay" @click.self="dialogOpen = false">
        <div class="modal">
          <div class="modal-header">
            <h3>
              <el-icon v-if="dialogWorkspaceWide" :size="18" class="modal-header__icon"><Unlock /></el-icon>
              {{ dialogWorkspaceWide
                  ? t('approval.grant.createWorkspaceBtn')
                  : t('approval.grant.createBtn') }}
            </h3>
            <button class="modal-close" @click="dialogOpen = false">&times;</button>
          </div>
          <div class="modal-body">
            <div v-if="dialogWorkspaceWide" class="danger-banner">
              <el-icon :size="16"><WarningFilled /></el-icon>
              <span>{{ t('approval.grant.createWorkspaceWarning') }}</span>
            </div>
            <!-- The grant is tenant-bound to the console's current workspace and
                 only matches conversations that actually live in it — IM-channel
                 conversations follow the channel's workspace binding, not the
                 console selection. Spelling this out up front prevents the
                 "configured but never fires" dead-grant trap. -->
            <div class="info-banner">
              {{ t('approval.grant.form.workspaceHint', { name: currentWorkspaceLabel }) }}
            </div>
            <div class="form-grid">
              <div class="form-group">
                <label>{{ t('approval.grant.form.scopeType') }} <span class="required">*</span></label>
                <select
                  v-model="form.scopeType"
                  class="form-input"
                  :disabled="dialogWorkspaceWide"
                >
                  <option value="CONVERSATION">CONVERSATION</option>
                  <option value="AGENT">AGENT</option>
                  <option value="USER">USER</option>
                  <option value="WORKSPACE">WORKSPACE</option>
                </select>
              </div>
              <div class="form-group">
                <label>{{ t('approval.grant.form.scopeId') }} <span class="required">*</span></label>
                <!-- Scope-typed pickers: free-text snowflake input made it far too
                     easy to paste the wrong kind of id (e.g. a workspace id into an
                     AGENT-scope grant), producing a grant that never matches.
                     WORKSPACE is locked to the current workspace — the backend
                     rejects any other id as a dead configuration. -->
                <input
                  v-if="form.scopeType === 'WORKSPACE'"
                  :value="currentWorkspaceLabel"
                  type="text"
                  class="form-input"
                  disabled
                />
                <select
                  v-else-if="form.scopeType === 'AGENT'"
                  v-model="form.scopeId"
                  class="form-input"
                >
                  <option value="" disabled>{{ t('approval.grant.form.scopeIdPickAgent') }}</option>
                  <option
                    v-for="a in agentStore.agents"
                    :key="String(a.id)"
                    :value="String(a.id)"
                  >
                    {{ a.name }} (#{{ a.id }})
                  </option>
                </select>
                <input
                  v-else-if="form.scopeType === 'USER'"
                  :value="form.scopeId"
                  type="text"
                  class="form-input"
                  disabled
                />
                <input
                  v-else
                  v-model.trim="form.scopeId"
                  type="text"
                  class="form-input mono"
                  :placeholder="t('approval.grant.form.scopeIdConversationPlaceholder')"
                />
                <p v-if="scopeIdHint" class="field-hint">{{ scopeIdHint }}</p>
              </div>
              <div class="form-group">
                <label>{{ t('approval.grant.form.toolName') }}</label>
                <input
                  v-model.trim="form.toolName"
                  class="form-input mono"
                  :disabled="dialogWorkspaceWide"
                  placeholder="(empty = any tool)"
                />
              </div>
              <div class="form-group">
                <label>{{ t('approval.grant.form.ruleId') }}</label>
                <input
                  v-model.trim="form.ruleId"
                  class="form-input mono"
                  placeholder="(optional)"
                />
              </div>
              <div class="form-group">
                <label>{{ t('approval.grant.form.maxSeverity') }}</label>
                <select v-model="form.maxSeverity" class="form-input">
                  <option value="LOW">LOW — {{ t('approval.grant.form.severityLow') }}</option>
                  <option value="MEDIUM">MEDIUM — {{ t('approval.grant.form.severityMedium') }}</option>
                  <option value="HIGH">HIGH — {{ t('approval.grant.form.severityHigh') }}</option>
                </select>
                <p class="field-hint">{{ t('approval.grant.form.severityHint') }}</p>
              </div>
              <div class="form-group">
                <label>{{ t('approval.grant.form.grantKind') }}</label>
                <select v-model="form.grantKind" class="form-input">
                  <option value="ALWAYS">{{ t('approval.grant.kind.always') }}</option>
                  <option value="UNTIL_TIMESTAMP">{{ t('approval.grant.kind.until') }}</option>
                  <option value="UNTIL_CONVERSATION_END">{{ t('approval.grant.kind.conversationEnd') }}</option>
                </select>
              </div>
              <div v-if="form.grantKind === 'UNTIL_TIMESTAMP'" class="form-group">
                <label>{{ t('approval.grant.form.expireAt') }} <span class="required">*</span></label>
                <input
                  v-model="form.expireAt"
                  type="datetime-local"
                  class="form-input"
                />
              </div>
              <div class="form-group form-group--full">
                <label>{{ t('approval.grant.form.note') }}</label>
                <input
                  v-model.trim="form.note"
                  class="form-input"
                  :placeholder="dialogWorkspaceWide ? '请说明为什么需要全工具白名单' : ''"
                />
              </div>
              <div v-if="requiresPassword" class="form-group form-group--full">
                <label>
                  {{ t('approval.grant.form.password') }}
                  <span class="required">*</span>
                </label>
                <div class="password-wrap">
                  <el-icon :size="14" class="password-wrap__icon"><Lock /></el-icon>
                  <input
                    v-model="form.password"
                    type="password"
                    class="form-input form-input--with-icon"
                    autocomplete="current-password"
                  />
                </div>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="dialogOpen = false">
              {{ t('common.cancel') }}
            </button>
            <button
              class="btn-primary"
              :disabled="creating"
              @click="submitCreate"
            >
              {{ creating ? t('common.processing') : t('common.confirm') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

  </div>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus/es/components/message/index'
import { ref, computed, onMounted, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import {
  Delete,
  Lock,
  MoreFilled,
  Plus,
  Refresh,
  Unlock,
  WarningFilled,
} from '@element-plus/icons-vue'
import { approvalApi } from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { useAgentStore } from '@/stores/useAgentStore'
import McPagination from '@/components/common/McPagination.vue'
import { mcConfirm } from '@/components/common/useConfirm'
import type {
  ApprovalGrant,
  CreateGrantPayload,
  GrantScope,
  GrantKind,
  GrantSeverity,
} from '@/types'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const workspaceStore = useWorkspaceStore()
const agentStore = useAgentStore()

const rows = ref<ApprovalGrant[]>([])
const total = ref(0)
// Active grants count for the summary pill — separate from `total` because the
// list endpoint returns revoked history by default and the pill should only
// reflect grants currently in force.
const activeCount = ref(0)
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

const currentWorkspaceLabel = computed(() => {
  const ws = workspaceStore.currentWorkspace
  return ws ? `${ws.name} (#${ws.id})` : String(workspaceStore.currentWorkspaceId ?? '')
})

const scopeIdHint = computed(() => {
  switch (form.scopeType) {
    case 'WORKSPACE': return t('approval.grant.form.scopeIdWorkspaceHint')
    case 'AGENT': return t('approval.grant.form.scopeIdAgentHint')
    case 'USER': return t('approval.grant.form.scopeIdUserHint')
    case 'CONVERSATION': return t('approval.grant.form.scopeIdConversationHint')
    default: return ''
  }
})

/**
 * Prefill scopeId whenever the scope type changes: WORKSPACE defaults to the
 * console's current workspace, USER is locked to the requesting user (the
 * backend rejects anything else), AGENT/CONVERSATION start empty for an
 * explicit pick.
 */
function prefillScopeId() {
  switch (form.scopeType) {
    case 'WORKSPACE':
      form.scopeId = String(workspaceStore.currentWorkspaceId ?? '')
      break
    case 'USER':
      form.scopeId = localStorage.getItem('userId') || ''
      break
    default:
      form.scopeId = ''
  }
}

watch(() => form.scopeType, () => {
  if (dialogOpen.value) prefillScopeId()
})

function onPageChange(p: number) {
  currentPage.value = p
  loadGrants()
}
function onSizeChange(s: number) {
  pageSize.value = s
  currentPage.value = 1
  loadGrants()
}

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
    // Refresh active-only count for the summary pill in parallel with the
    // list; non-blocking on failure so the page still renders normally.
    refreshActiveCount()
  } catch (e: any) {
    ElMessage.error(e?.message || 'Failed to load grants')
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function refreshActiveCount() {
  try {
    const res = await approvalApi.activeSummary()
    const data = (res as any).data ?? res
    activeCount.value = Number(data?.count ?? 0)
  } catch {
    activeCount.value = 0
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
  prefillScopeId()
  // Lazy-load picker data sources; both are cheap and cached in their stores.
  if (!workspaceStore.workspaces.length) workspaceStore.fetchWorkspaces()
  if (!agentStore.agents.length) agentStore.fetchAgents()
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
  // Project-wide imperative confirm — same component used by McpServers,
  // Channels, LivePanel, etc. so the destructive prompt feels consistent
  // across the app. `tone: 'danger'` paints the confirm button red.
  const target = g.toolName || t('approval.grant.anyTool')
  const ok = await mcConfirm({
    title: t('approval.grant.revokeBtn'),
    message: t('approval.grant.revokeConfirmDetailed', { tool: target }),
    confirmText: t('approval.grant.revokeBtn'),
    tone: 'danger',
  })
  if (!ok) return
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
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s
  return d.toLocaleString()
}

onMounted(() => {
  loadGrants()
  applyCreateQuery()
})

/**
 * Entry from the audit-log page's "create grant" shortcut:
 * /security/auto-approve?create=1&tool=<name>&severity=<LOW|MEDIUM|HIGH>
 * opens the create dialog prefilled with the missed invocation's tool and
 * actual severity (WORKSPACE scope, current workspace). The query is
 * consumed once and stripped so refresh/back doesn't re-open the dialog.
 */
function applyCreateQuery() {
  if (route.query.create !== '1') return
  const tool = typeof route.query.tool === 'string' ? route.query.tool : ''
  const severity = typeof route.query.severity === 'string' ? route.query.severity : ''
  openCreateDialog(false)
  form.scopeType = 'WORKSPACE'
  prefillScopeId()
  form.toolName = tool
  if (severity === 'LOW' || severity === 'MEDIUM' || severity === 'HIGH') {
    form.maxSeverity = severity
  } else if (severity === 'CRITICAL') {
    // CRITICAL is never auto-approvable; HIGH is the closest configurable ceiling.
    form.maxSeverity = 'HIGH'
  }
  router.replace({ query: {} })
}
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
/* Summary pill — reuses the same danger-tinted tokens as the sidebar chip
   so the two surfaces feel like one design language; no el-tag here. */
.summary-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--mc-danger-bg, rgba(192, 57, 43, 0.12));
  color: var(--mc-danger, #C0392B);
  border: 1px solid var(--mc-danger-border, rgba(192, 57, 43, 0.4));
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
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

/* Scope badge — same family as .severity-badge in shared.css, 4 token-driven
   color variants for CONVERSATION / AGENT / USER / WORKSPACE. */
.scope-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.5;
}
.scope-conversation { background: rgba(59, 130, 246, 0.12); color: #3b82f6; }
.scope-agent        { background: rgba(245, 158, 11, 0.12); color: #f59e0b; }
.scope-user         { background: rgba(16, 185, 129, 0.12); color: #10b981; }
.scope-workspace    { background: var(--mc-danger-bg, rgba(192, 57, 43, 0.12));
                      color: var(--mc-danger, #C0392B); }
.scope-id {
  margin-top: 2px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: var(--mc-text-tertiary, #94a3b8);
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: var(--mc-surface-tertiary, var(--mc-bg-muted, rgba(0, 0, 0, 0.04)));
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

/* Revoked rows: dim out so live rows stay visually dominant. */
.row-revoked { opacity: 0.55; }
.row-revoked .scope-badge { filter: grayscale(0.5); }

/* Actions column header sits right-aligned, matches ToolGuard. */
.col-actions { text-align: center; width: 80px; }

/* This page has more columns than ToolGuard / AuditLogs, so the wrapper needs
   to allow horizontal scroll on narrow viewports — shared.css uses
   overflow:hidden which would chop the rightmost columns off. Scoped override
   only affects this view; the shared style stays untouched. */
.rules-table-wrapper {
  overflow-x: auto;
}
.rules-table {
  min-width: 1100px; /* enough headroom so the kind / granter / date columns
                        don't collapse into vertical-text mode on a narrow viewport */
}
/* Prevent narrow columns from wrapping into stacked Chinese glyphs. */
.rules-table th,
.rules-table td {
  white-space: nowrap;
}
/* Note cell is the one cell where wrapping is fine — it's already
   ellipsis-truncated by .note-cell. Keep this explicit so adding nowrap to
   the wider scope above doesn't suppress the ellipsis. */
.rules-table td .note-cell {
  white-space: nowrap;
}

/*
  Sticky "Actions" column — pinned to the right so the revoke control stays
  reachable when the table horizontally scrolls. The cell needs an opaque
  background or rows scrolling underneath would bleed through; we pull from
  the same token the table sits on so the pinned column looks like it's
  always been part of the surface rather than a floating overlay. A faint
  left border doubles as the visual divider between the scrollable region
  and the pinned column when content actually overflows.
*/
.rules-table th.col-actions,
.rules-table td.col-actions {
  position: sticky;
  right: 0;
  background: var(--mc-surface-primary, #fff);
  box-shadow: -6px 0 8px -8px rgba(0, 0, 0, 0.08);
  z-index: 2;
}
.rules-table th.col-actions {
  /* Header sits above body when the user scrolls vertically inside a
     scrollable area. Doesn't matter today (the page itself scrolls), but
     cheap to set and future-proof. */
  z-index: 3;
  background: var(--mc-bg-sunken);
}
/* Sticky cell needs its own dim treatment for revoked rows since opacity on
   the <tr> doesn't reach a sticky child (the row's stacking context shifts). */
.rules-table tr.row-revoked td.col-actions {
  opacity: 0.55;
}
:global(html.dark .rules-table td.col-actions) {
  background: var(--mc-bg-muted, #1a130e);
  box-shadow: -6px 0 10px -8px rgba(0, 0, 0, 0.4);
}

/* Pagination row — right-aligned beneath the table. McPagination provides
   its own pill background, so just lay it out. */
.grants-pagination-row {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}

/* ─── Modal (mirrors ToolGuard / edit-rule pattern) ──────────────────── */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 24px;
}
.modal {
  background: var(--mc-surface-primary, #fff);
  border-radius: 12px;
  width: min(640px, 100%);
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.18);
  overflow: hidden;
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--mc-border-light, #e5e7eb);
}
.modal-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--mc-text-primary, #0f172a);
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.modal-header__icon {
  color: var(--mc-danger, #b91c1c);
}
.modal-close {
  background: none;
  border: none;
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  color: var(--mc-text-tertiary, #94a3b8);
}
.modal-close:hover { color: var(--mc-text-primary, #0f172a); }
.modal-body {
  padding: 20px;
  overflow-y: auto;
}
.modal-footer {
  padding: 12px 20px;
  border-top: 1px solid var(--mc-border-light, #e5e7eb);
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* Danger banner inside the workspace-wide create modal — bright red so the
   destructive variant looks instantly different from the routine flow. */
.danger-banner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 16px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
  color: #991b1b;
  font-size: 13px;
  line-height: 1.5;
}
.danger-banner .el-icon { flex-shrink: 0; margin-top: 1px; }

/* Neutral info banner — states which workspace the grant will be created in.
   Deliberately calmer than .danger-banner: informational, not a warning. */
.info-banner {
  padding: 10px 14px;
  margin-bottom: 16px;
  background: var(--mc-surface-tertiary, #f1f5f9);
  border: 1px solid var(--mc-border-light, #e5e7eb);
  border-radius: 8px;
  color: var(--mc-text-secondary, #475569);
  font-size: 12.5px;
  line-height: 1.55;
}

/* Per-field helper line under a control (scope-id semantics, severity ceiling). */
.field-hint {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--mc-text-tertiary, #94a3b8);
}

/* Form grid layout — two columns on wide modal, one column when narrow.
   form-group--full breaks across both columns (note, password). */
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px 16px;
}
.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form-group--full { grid-column: 1 / -1; }
.form-group label {
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-secondary, #475569);
}
.form-group label .required {
  color: var(--mc-danger, #b91c1c);
  margin-left: 2px;
}
.form-input {
  padding: 8px 10px;
  border: 1px solid var(--mc-border-light, #e5e7eb);
  border-radius: 6px;
  background: var(--mc-surface-primary, #fff);
  color: var(--mc-text-primary, #0f172a);
  font-size: 13px;
  width: 100%;
  box-sizing: border-box;
  transition: border-color 0.15s;
}
.form-input:focus {
  outline: none;
  border-color: var(--mc-primary, #d97757);
}
.form-input:disabled {
  background: var(--mc-surface-tertiary, #f1f5f9);
  cursor: not-allowed;
}
.form-input.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

/* Password input with the Lock icon as a visual prefix. */
.password-wrap {
  position: relative;
  display: flex;
  align-items: center;
}
.password-wrap__icon {
  position: absolute;
  left: 10px;
  color: var(--mc-text-tertiary, #94a3b8);
  pointer-events: none;
}
.form-input--with-icon { padding-left: 32px; }

@media (max-width: 560px) {
  .form-grid { grid-template-columns: 1fr; }
}
</style>
