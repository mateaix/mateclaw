<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner">
        <!-- ==================== Team list ==================== -->
        <template v-if="!store.currentTeam">
          <div class="mc-page-header">
            <div>
              <div class="mc-page-kicker">{{ t('teams.kicker') }}</div>
              <h1 class="mc-page-title">{{ t('teams.title') }}</h1>
              <p class="mc-page-desc">{{ t('teams.subtitle') }}</p>
            </div>
            <div class="header-right">
              <button class="btn-primary" @click="openCreateDialog">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="12" y1="5" x2="12" y2="19" />
                  <line x1="5" y1="12" x2="19" y2="12" />
                </svg>
                {{ t('teams.create') }}
              </button>
            </div>
          </div>

          <div v-if="!store.loading && store.teams.length === 0" class="empty-state mc-surface-card">
            <div class="empty-state__icon">
              <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                <circle cx="9" cy="7" r="4" />
                <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
                <path d="M16 3.13a4 4 0 0 1 0 7.75" />
              </svg>
            </div>
            <p>{{ t('teams.empty') }}</p>
          </div>

          <div class="team-grid">
            <div
              v-for="vo in store.teams"
              :key="vo.team.id"
              class="team-card mc-surface-card"
              @click="openTeam(vo.team.id)"
            >
              <div class="team-card__top">
                <h3 class="team-card__name">{{ vo.team.name }}</h3>
                <span class="status-pill" :class="vo.team.status === 'active' ? 'is-active' : 'is-paused'">
                  <span class="status-pill__dot"></span>{{ vo.team.status }}
                </span>
              </div>
              <p class="team-card__desc">{{ vo.team.description || '—' }}</p>
              <div class="team-card__foot">
                <span class="lead-chip">
                  <span class="lead-chip__icon" :style="{ color: agentIconColor(agentIcon(vo.team.leadAgentId, vo.leadIcon)) }">
                    <SkillIcon :value="agentIcon(vo.team.leadAgentId, vo.leadIcon)" :size="15" />
                  </span>
                  {{ vo.leadName }}
                </span>
                <span class="team-card__count">{{ t('teams.memberCount', { count: vo.memberCount }) }}</span>
              </div>
            </div>
          </div>
        </template>

        <!-- ==================== Team detail ==================== -->
        <template v-else>
          <div class="detail-header">
            <div class="detail-header__left">
              <button class="btn-secondary" @click="store.closeTeam()">← {{ t('teams.back') }}</button>
              <h1 class="detail-header__title">{{ store.currentTeam.team.name }}</h1>
              <span class="lead-chip">
                <span
                  class="lead-chip__icon"
                  :style="{ color: agentIconColor(agentIcon(store.currentTeam.team.leadAgentId, store.currentTeam.leadIcon)) }"
                >
                  <SkillIcon
                    :value="agentIcon(store.currentTeam.team.leadAgentId, store.currentTeam.leadIcon)"
                    :size="15"
                  />
                </span>
                {{ store.currentTeam.leadName }}
              </span>
            </div>
            <div class="detail-header__right">
              <div class="view-switch">
                <button
                  class="view-seg"
                  :class="{ 'is-active': activeTab === 'board' }"
                  @click="activeTab = 'board'"
                >{{ t('teams.board') }}</button>
                <button
                  class="view-seg"
                  :class="{ 'is-active': activeTab === 'members' }"
                  @click="activeTab = 'members'"
                >{{ t('teams.members') }}</button>
              </div>
              <button class="btn-secondary" @click="refreshBoard">{{ t('common.refresh') }}</button>
              <button class="btn-danger" @click="removeTeam">{{ t('common.delete') }}</button>
            </div>
          </div>

          <!-- Kanban board -->
          <div v-if="activeTab === 'board'" class="board-grid">
            <div v-for="col in boardColumns" :key="col.key" class="board-col">
              <div class="board-col__head">
                <span class="board-col__dot" :class="`dot--${col.key}`"></span>
                <span class="board-col__label">{{ col.label }}</span>
                <span class="board-col__count">{{ col.tasks.length }}</span>
              </div>
              <div class="board-col__body">
                <div
                  v-for="vo in col.tasks"
                  :key="vo.task.id"
                  class="task-card"
                  @click="openTask(vo)"
                >
                  <div class="task-card__num">#{{ vo.task.taskNumber }}</div>
                  <div class="task-card__subject">{{ vo.task.subject }}</div>
                  <div class="task-card__meta">
                    <span class="assignee-chip">{{ vo.assigneeName || '—' }}</span>
                    <span v-if="vo.task.requireApproval" class="task-card__lock">🔒</span>
                  </div>
                  <div
                    v-if="vo.task.status === 'in_progress' && vo.task.progressPercent != null"
                    class="task-card__progress"
                  >
                    <div class="task-card__progress-bar" :style="{ width: vo.task.progressPercent + '%' }"></div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Members -->
          <div v-else class="members-panel mc-surface-card">
            <div class="members-panel__toolbar">
              <button class="btn-secondary" @click="memberDialogVisible = true">
                + {{ t('teams.addMember') }}
              </button>
            </div>
            <div class="member-list">
              <div v-for="m in store.members" :key="m.agentId" class="member-row">
                <span
                  class="member-row__avatar"
                  :style="{ color: agentIconColor(agentIcon(m.agentId, m.icon)) }"
                >
                  <SkillIcon :value="agentIcon(m.agentId, m.icon)" :size="20" />
                </span>
                <span class="member-row__name">{{ m.name }}</span>
                <span class="role-chip" :class="{ 'is-lead': m.role === 'lead' }">
                  {{ t(`teams.roles.${m.role}`, m.role) }}
                </span>
                <button
                  v-if="m.role !== 'lead'"
                  class="member-row__remove"
                  @click="removeMember(m)"
                >{{ t('common.delete') }}</button>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- ==================== Create team dialog ==================== -->
    <Teleport to="body">
      <div v-if="createDialogVisible" class="modal-overlay" @click.self="createDialogVisible = false">
        <div class="modal modal--wide">
          <div class="modal-header">
            <h3>{{ t('teams.create') }}</h3>
            <button class="modal-close" @click="createDialogVisible = false">&times;</button>
          </div>
          <div class="modal-body">
            <div class="form-group">
              <label>{{ t('teams.name') }} <i>*</i></label>
              <input v-model.trim="createForm.name" class="form-input" maxlength="128" />
            </div>
            <div class="form-group">
              <label>{{ t('teams.description') }}</label>
              <textarea v-model="createForm.description" class="form-input form-textarea" rows="2"></textarea>
            </div>
            <div class="form-group">
              <label>{{ t('teams.lead') }} <i>*</i></label>
              <div class="agent-picker">
                <button
                  v-for="agent in agentStore.agents"
                  :key="String(agent.id)"
                  class="agent-pill"
                  :class="{ 'is-selected is-lead': createForm.leadAgentId === String(agent.id) }"
                  @click="selectLead(String(agent.id))"
                >
                  <span class="agent-pill__icon" :style="{ color: agentIconColor(agent.icon) }">
                    <SkillIcon :value="agent.icon || 'pi:user'" :size="14" />
                  </span>
                  {{ agent.name }}
                </button>
              </div>
            </div>
            <div class="form-group">
              <label>{{ t('teams.membersField') }} <i>*</i></label>
              <div class="agent-picker">
                <button
                  v-for="agent in memberCandidates"
                  :key="String(agent.id)"
                  class="agent-pill"
                  :class="{ 'is-selected': createForm.memberAgentIds.includes(String(agent.id)) }"
                  @click="toggleMember(String(agent.id))"
                >
                  <span v-if="createForm.memberAgentIds.includes(String(agent.id))" class="agent-pill__check">✓</span>
                  <span class="agent-pill__icon" :style="{ color: agentIconColor(agent.icon) }">
                    <SkillIcon :value="agent.icon || 'pi:user'" :size="14" />
                  </span>
                  {{ agent.name }}
                </button>
              </div>
              <p class="form-hint">{{ t('teams.pickHint') }}</p>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="createDialogVisible = false">{{ t('common.cancel') }}</button>
            <button class="btn-primary" :disabled="creating" @click="submitCreate">
              {{ creating ? t('common.processing') : t('common.confirm') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ==================== Add member dialog ==================== -->
    <Teleport to="body">
      <div v-if="memberDialogVisible" class="modal-overlay" @click.self="memberDialogVisible = false">
        <div class="modal">
          <div class="modal-header">
            <h3>{{ t('teams.addMember') }}</h3>
            <button class="modal-close" @click="memberDialogVisible = false">&times;</button>
          </div>
          <div class="modal-body">
            <div class="form-group">
              <label>{{ t('teams.memberName') }} <i>*</i></label>
              <div class="agent-picker">
                <button
                  v-for="agent in addMemberCandidates"
                  :key="String(agent.id)"
                  class="agent-pill"
                  :class="{ 'is-selected': memberForm.agentId === String(agent.id) }"
                  @click="memberForm.agentId = String(agent.id)"
                >
                  <span v-if="memberForm.agentId === String(agent.id)" class="agent-pill__check">✓</span>
                  <span class="agent-pill__icon" :style="{ color: agentIconColor(agent.icon) }">
                    <SkillIcon :value="agent.icon || 'pi:user'" :size="14" />
                  </span>
                  {{ agent.name }}
                </button>
                <p v-if="addMemberCandidates.length === 0" class="form-hint">{{ t('teams.noCandidates') }}</p>
              </div>
            </div>
            <div class="form-group">
              <label>{{ t('teams.role') }}</label>
              <div class="view-switch">
                <button
                  class="view-seg"
                  :class="{ 'is-active': memberForm.role === 'member' }"
                  @click="memberForm.role = 'member'"
                >{{ t('teams.roles.member') }}</button>
                <button
                  class="view-seg"
                  :class="{ 'is-active': memberForm.role === 'reviewer' }"
                  @click="memberForm.role = 'reviewer'"
                >{{ t('teams.roles.reviewer') }}</button>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="memberDialogVisible = false">{{ t('common.cancel') }}</button>
            <button class="btn-primary" :disabled="!memberForm.agentId" @click="submitAddMember">
              {{ t('common.confirm') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ==================== Task detail dialog ==================== -->
    <Teleport to="body">
      <div v-if="taskDialogVisible && currentTask" class="modal-overlay" @click.self="taskDialogVisible = false">
        <div class="modal modal--wide">
          <div class="modal-header">
            <div class="task-dialog__head">
              <span class="task-dialog__title">
                #{{ currentTask.task.taskNumber }} {{ currentTask.task.subject }}
              </span>
              <span class="status-pill" :class="`pill--${currentTask.task.status}`">
                {{ statusLabel(currentTask.task.status) }}
              </span>
            </div>
            <button class="modal-close" @click="taskDialogVisible = false">&times;</button>
          </div>
          <div class="modal-body task-detail">
            <div class="task-detail__meta">
              <span>{{ t('teams.assignee') }}: {{ currentTask.assigneeName || '—' }}</span>
              <span v-if="currentTask.task.progressStep">
                {{ currentTask.task.progressPercent }}% — {{ currentTask.task.progressStep }}
              </span>
            </div>
            <div v-if="currentTask.task.description" class="task-detail__block">
              <div class="task-detail__label">{{ t('teams.taskDescription') }}</div>
              <div class="task-detail__text">{{ currentTask.task.description }}</div>
            </div>
            <div v-if="currentTask.task.result" class="task-detail__block">
              <div class="task-detail__label">{{ t('teams.result') }}</div>
              <div class="task-detail__text task-detail__text--boxed">{{ currentTask.task.result }}</div>
            </div>
            <div v-if="currentTask.task.reason" class="task-detail__reason">
              {{ currentTask.task.reason }}
            </div>
            <div class="task-detail__block">
              <div class="task-detail__label">{{ t('teams.comments') }}</div>
              <div v-if="comments.length === 0" class="task-detail__muted">—</div>
              <div
                v-for="c in comments"
                :key="c.id"
                class="comment-row"
                :class="{ 'is-blocker': c.commentType === 'blocker' }"
              >
                <span class="comment-row__author">[{{ c.authorType }} {{ c.authorId }}]</span>
                <span>{{ c.content }}</span>
              </div>
              <div class="comment-input">
                <input
                  v-model.trim="newComment"
                  class="form-input"
                  :placeholder="t('teams.commentPlaceholder')"
                  @keyup.enter="submitComment"
                />
                <button class="btn-secondary" @click="submitComment">{{ t('common.confirm') }}</button>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button
              v-if="currentTask.task.status === 'in_review'"
              class="btn-success"
              @click="approveTask"
            >{{ t('teams.approve') }}</button>
            <button
              v-if="currentTask.task.status === 'in_review'"
              class="btn-danger"
              @click="rejectTask"
            >{{ t('teams.reject') }}</button>
            <button
              v-if="['failed', 'stale'].includes(currentTask.task.status)"
              class="btn-primary"
              @click="retryTask"
            >{{ t('teams.retry') }}</button>
            <button
              v-if="['pending', 'blocked', 'in_progress'].includes(currentTask.task.status)"
              class="btn-danger"
              @click="cancelTask"
            >{{ t('common.cancel') }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { teamApi } from '@/api/index'
import type { TeamMemberVO, TeamTaskComment, TeamTaskVO } from '@/api/index'
import SkillIcon from '@/components/common/SkillIcon.vue'
import { agentIconColor } from '@/utils/agentIconColor'
import { useAgentStore } from '@/stores/useAgentStore'
import { useTeamStore } from '@/stores/useTeamStore'

const { t } = useI18n()
const store = useTeamStore()
const agentStore = useAgentStore()

const activeTab = ref('board')

// ==================== board columns ====================

const COLUMN_DEFS = [
  { key: 'todo', statuses: ['pending', 'blocked'] },
  { key: 'in_progress', statuses: ['in_progress'] },
  { key: 'in_review', statuses: ['in_review'] },
  { key: 'completed', statuses: ['completed'] },
  { key: 'closed', statuses: ['failed', 'cancelled', 'stale'] },
] as const

const boardColumns = computed(() =>
  COLUMN_DEFS.map((col) => ({
    key: col.key,
    label: t(`teams.column.${col.key}`),
    tasks: store.tasks.filter((vo) => (col.statuses as readonly string[]).includes(vo.task.status)),
  })),
)

function statusLabel(status?: string) {
  return status ? t(`teams.status.${status}`, status) : ''
}

/**
 * Resolve an agent's icon: prefer the value the teams API returned, fall
 * back to the agent store (covers a backend that predates the icon field),
 * and finally a neutral pixel icon so no emoji placeholder ever renders.
 */
function agentIcon(agentId?: string | null, apiIcon?: string | null): string {
  if (apiIcon) return apiIcon
  const agent = agentId
    ? agentStore.agents.find((a) => String(a.id) === String(agentId))
    : undefined
  return agent?.icon || 'pi:user'
}

// ==================== polling ====================

let pollTimer: ReturnType<typeof setInterval> | null = null

function startPolling() {
  stopPolling()
  pollTimer = setInterval(() => {
    const team = store.currentTeam
    if (team && store.hasActiveTasks) {
      store.fetchTasks(team.team.id)
    }
  }, 3000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(
  () => store.currentTeam,
  (team) => (team ? startPolling() : stopPolling()),
)

onMounted(() => {
  store.fetchTeams()
  if (agentStore.agents.length === 0) {
    agentStore.fetchAgents()
  }
})

onBeforeUnmount(() => stopPolling())

// ==================== team actions ====================

async function openTeam(teamId: string) {
  try {
    await store.openTeam(teamId)
    activeTab.value = 'board'
  } catch (e: any) {
    ElMessage.error(e?.message || 'failed')
  }
}

function refreshBoard() {
  if (store.currentTeam) {
    store.fetchTasks(store.currentTeam.team.id)
  }
}

async function removeTeam() {
  if (!store.currentTeam) return
  try {
    await ElMessageBox.confirm(t('teams.deleteConfirm'), { type: 'warning' })
  } catch {
    return
  }
  await store.deleteTeam(store.currentTeam.team.id)
  ElMessage.success(t('common.success'))
}

// ==================== create team ====================

const createDialogVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  name: '',
  description: '',
  leadAgentId: '',
  memberAgentIds: [] as string[],
})

const memberCandidates = computed(() =>
  agentStore.agents.filter((a) => String(a.id) !== createForm.leadAgentId),
)

function openCreateDialog() {
  createForm.name = ''
  createForm.description = ''
  createForm.leadAgentId = ''
  createForm.memberAgentIds = []
  createDialogVisible.value = true
}

function selectLead(agentId: string) {
  createForm.leadAgentId = createForm.leadAgentId === agentId ? '' : agentId
  // The lead cannot double as a member.
  createForm.memberAgentIds = createForm.memberAgentIds.filter((id) => id !== agentId)
}

function toggleMember(agentId: string) {
  const idx = createForm.memberAgentIds.indexOf(agentId)
  if (idx >= 0) {
    createForm.memberAgentIds.splice(idx, 1)
  } else {
    createForm.memberAgentIds.push(agentId)
  }
}

async function submitCreate() {
  if (!createForm.name || !createForm.leadAgentId || createForm.memberAgentIds.length === 0) {
    ElMessage.warning(t('teams.createIncomplete'))
    return
  }
  creating.value = true
  try {
    await store.createTeam({ ...createForm })
    createDialogVisible.value = false
    ElMessage.success(t('common.success'))
  } catch (e: any) {
    ElMessage.error(e?.message || 'failed')
  } finally {
    creating.value = false
  }
}

// ==================== members ====================

const memberDialogVisible = ref(false)
const memberForm = reactive({ agentId: '', role: 'member' })

const addMemberCandidates = computed(() => {
  const existing = new Set(store.members.map((m) => m.agentId))
  return agentStore.agents.filter((a) => !existing.has(String(a.id)))
})

async function submitAddMember() {
  if (!store.currentTeam || !memberForm.agentId) return
  try {
    await teamApi.addMember(store.currentTeam.team.id, memberForm.agentId, memberForm.role)
    memberDialogVisible.value = false
    memberForm.agentId = ''
    await store.openTeam(store.currentTeam.team.id)
  } catch (e: any) {
    ElMessage.error(e?.message || 'failed')
  }
}

async function removeMember(row: TeamMemberVO) {
  if (!store.currentTeam) return
  try {
    await teamApi.removeMember(store.currentTeam.team.id, row.agentId)
    await store.openTeam(store.currentTeam.team.id)
  } catch (e: any) {
    ElMessage.error(e?.message || 'failed')
  }
}

// ==================== task detail ====================

const taskDialogVisible = ref(false)
const currentTask = ref<TeamTaskVO | null>(null)
const comments = ref<TeamTaskComment[]>([])
const newComment = ref('')

async function openTask(vo: TeamTaskVO) {
  if (!store.currentTeam) return
  try {
    const res: any = await teamApi.getTask(store.currentTeam.team.id, vo.task.id)
    currentTask.value = res.data?.task || vo
    comments.value = res.data?.comments || []
    taskDialogVisible.value = true
  } catch (e: any) {
    ElMessage.error(e?.message || 'failed')
  }
}

async function reloadTask() {
  if (currentTask.value) {
    const vo = currentTask.value
    await openTask(vo)
    refreshBoard()
  }
}

async function submitComment() {
  if (!store.currentTeam || !currentTask.value || !newComment.value) return
  await teamApi.commentTask(store.currentTeam.team.id, currentTask.value.task.id, newComment.value)
  newComment.value = ''
  await reloadTask()
}

async function approveTask() {
  if (!store.currentTeam || !currentTask.value) return
  await teamApi.approveTask(store.currentTeam.team.id, currentTask.value.task.id)
  ElMessage.success(t('teams.approved'))
  await reloadTask()
}

async function rejectTask() {
  if (!store.currentTeam || !currentTask.value) return
  let reason = ''
  try {
    const input = await ElMessageBox.prompt(t('teams.rejectReason'), { type: 'warning' })
    reason = input.value || ''
  } catch {
    return
  }
  await teamApi.rejectTask(store.currentTeam.team.id, currentTask.value.task.id, reason)
  await reloadTask()
}

async function retryTask() {
  if (!store.currentTeam || !currentTask.value) return
  await teamApi.retryTask(store.currentTeam.team.id, currentTask.value.task.id)
  await reloadTask()
}

async function cancelTask() {
  if (!store.currentTeam || !currentTask.value) return
  try {
    await ElMessageBox.confirm(t('teams.cancelConfirm'), { type: 'warning' })
  } catch {
    return
  }
  await teamApi.cancelTask(store.currentTeam.team.id, currentTask.value.task.id)
  await reloadTask()
}
</script>

<style scoped>
.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 18px;
  background: var(--mc-primary);
  color: #fff;
  border: none;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: filter 0.15s;
}
.btn-primary:hover {
  filter: brightness(1.06);
}

.btn-secondary {
  padding: 8px 16px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s;
}
.btn-secondary:hover {
  background: var(--mc-bg-sunken);
}

.btn-danger {
  padding: 8px 16px;
  background: transparent;
  color: var(--mc-danger, #d9534f);
  border: 1px solid var(--mc-danger, #d9534f);
  border-radius: 12px;
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-danger:hover {
  background: var(--mc-danger, #d9534f);
  color: #fff;
}

/* ==================== empty state ==================== */

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 56px 20px;
  color: var(--mc-text-secondary);
  font-size: 14px;
}
.empty-state__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 20px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  color: var(--mc-text-tertiary);
}

/* ==================== team cards ==================== */

.team-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.team-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.15s;
}
.team-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
}

.team-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.team-card__name {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.team-card__desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--mc-text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 2.2em;
}
.team-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--mc-border-light);
}
.team-card__count {
  font-size: 12.5px;
  color: var(--mc-text-tertiary);
}

.lead-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  font-size: 12.5px;
  font-weight: 600;
  color: var(--mc-text-primary);
}
.lead-chip__icon,
.agent-pill__icon {
  display: inline-flex;
  align-items: center;
  line-height: 1;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
}
.status-pill__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.status-pill.is-active {
  color: #16a34a;
  background: rgba(22, 163, 74, 0.08);
  border-color: rgba(22, 163, 74, 0.25);
}
.status-pill.is-paused {
  color: var(--mc-text-tertiary);
}
.pill--completed { color: #16a34a; background: rgba(22, 163, 74, 0.08); border-color: rgba(22, 163, 74, 0.25); }
.pill--in_progress { color: var(--mc-primary); background: var(--mc-primary-bg); border-color: var(--mc-primary); }
.pill--in_review { color: #d97706; background: rgba(217, 119, 6, 0.08); border-color: rgba(217, 119, 6, 0.3); }
.pill--failed, .pill--cancelled { color: #dc2626; background: rgba(220, 38, 38, 0.07); border-color: rgba(220, 38, 38, 0.25); }

/* ==================== detail header ==================== */

.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 22px;
}
.detail-header__left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.detail-header__title {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.detail-header__right {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* Segmented switch — same pattern as the employees page view switch. */
.view-switch {
  display: inline-flex;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border-light);
  border-radius: 999px;
  padding: 4px;
  gap: 2px;
}
.view-seg {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 18px;
  border-radius: 999px;
  border: none;
  background: transparent;
  color: var(--mc-text-secondary);
  font-size: 13.5px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, box-shadow 0.2s ease;
}
.view-seg:hover {
  color: var(--mc-text-primary);
}
.view-seg.is-active {
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

/* ==================== kanban board ==================== */

.board-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
}
@media (max-width: 1280px) {
  .board-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 768px) {
  .board-grid {
    grid-template-columns: 1fr;
  }
}

.board-col {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--mc-border-light);
  border-radius: 18px;
  background: var(--mc-bg-muted);
  min-height: 260px;
}
.board-col__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--mc-border-light);
}
.board-col__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.dot--todo { background: var(--mc-text-tertiary); }
.dot--in_progress { background: var(--mc-primary); }
.dot--in_review { background: #d97706; }
.dot--completed { background: #16a34a; }
.dot--closed { background: #dc2626; }

.board-col__label {
  font-size: 13px;
  font-weight: 700;
  color: var(--mc-text-primary);
  flex: 1;
}
.board-col__count {
  min-width: 22px;
  text-align: center;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary);
}
.board-col__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  overflow-y: auto;
}

.task-card {
  padding: 12px;
  border-radius: 14px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  cursor: pointer;
  transition: all 0.15s;
}
.task-card:hover {
  transform: translateY(-1px);
  border-color: var(--mc-primary);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.07);
}
.task-card__num {
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-text-tertiary);
}
.task-card__subject {
  margin-top: 2px;
  font-size: 13.5px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--mc-text-primary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.task-card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
}
.task-card__lock {
  font-size: 12px;
}
.assignee-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  font-size: 11.5px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-card__progress {
  margin-top: 8px;
  height: 4px;
  border-radius: 999px;
  background: var(--mc-bg-sunken);
  overflow: hidden;
}
.task-card__progress-bar {
  height: 100%;
  border-radius: inherit;
  background: var(--mc-primary);
  transition: width 0.4s ease;
}

/* ==================== members panel ==================== */

.members-panel {
  padding: 18px;
}
.members-panel__toolbar {
  margin-bottom: 14px;
}
.member-list {
  display: flex;
  flex-direction: column;
}
.member-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 6px;
  border-bottom: 1px solid var(--mc-border-light);
}
.member-row:last-child {
  border-bottom: none;
}
.member-row__avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 12px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  font-size: 14px;
  font-weight: 700;
  color: var(--mc-text-primary);
  flex-shrink: 0;
}
.member-row__name {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.role-chip {
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary);
}
.role-chip.is-lead {
  color: #d97706;
  background: rgba(217, 119, 6, 0.08);
  border-color: rgba(217, 119, 6, 0.3);
}
.member-row__remove {
  border: none;
  background: transparent;
  color: var(--mc-danger, #d9534f);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 8px;
}
.member-row__remove:hover {
  background: rgba(220, 38, 38, 0.07);
}

/* ==================== task detail dialog ==================== */

.task-dialog__head {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.task-dialog__title {
  font-weight: 700;
  color: var(--mc-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
  font-size: 13.5px;
}
.task-detail__meta {
  display: flex;
  gap: 18px;
  color: var(--mc-text-secondary);
}
.task-detail__label {
  font-weight: 700;
  margin-bottom: 4px;
  color: var(--mc-text-primary);
}
.task-detail__text {
  white-space: pre-wrap;
  color: var(--mc-text-secondary);
  line-height: 1.65;
}
.task-detail__text--boxed {
  max-height: 240px;
  overflow: auto;
  border-radius: 12px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  padding: 10px 12px;
}
.task-detail__reason {
  border-radius: 12px;
  border: 1px solid rgba(217, 119, 6, 0.3);
  background: rgba(217, 119, 6, 0.07);
  color: #b45309;
  padding: 10px 12px;
}
.task-detail__muted {
  color: var(--mc-text-tertiary);
}
.comment-row {
  border-left: 2px solid var(--mc-border);
  padding-left: 10px;
  margin-bottom: 8px;
  color: var(--mc-text-secondary);
  line-height: 1.6;
}
.comment-row.is-blocker {
  border-left-color: #dc2626;
}
.comment-row__author {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin-right: 6px;
}
.comment-input {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.comment-input .form-input {
  flex: 1;
}

/* ==================== custom modal ==================== */

.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(124, 63, 30, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  z-index: 1000;
  animation: team-fade-in 0.15s ease;
}

.modal {
  width: 420px;
  max-width: 100%;
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 16px;
  box-shadow: 0 16px 48px rgba(25, 14, 8, 0.18);
  overflow: hidden;
  animation: team-slide-up 0.2s ease;
}
.modal--wide {
  width: 620px;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--mc-border-light);
}
.modal-header h3 {
  font-size: 17px;
  font-weight: 600;
  color: var(--mc-text-primary);
  margin: 0;
}
.modal-close {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  color: var(--mc-text-tertiary);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  flex-shrink: 0;
  transition: background 0.15s;
}
.modal-close:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
}

.modal-body {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--mc-border-light);
}

@keyframes team-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes team-slide-up {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

/* ==================== form controls ==================== */

.form-group {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.form-group label {
  font-size: 13px;
  font-weight: 600;
  color: var(--mc-text-secondary);
}
.form-group label i {
  color: var(--mc-danger, #d9534f);
  font-style: normal;
}
.form-input {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
  font-size: 14px;
  font-family: inherit;
  transition: border-color 0.15s, box-shadow 0.15s;
  box-sizing: border-box;
}
.form-input:focus {
  outline: none;
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.12);
}
.form-textarea {
  resize: vertical;
  min-height: 56px;
  line-height: 1.5;
}
.form-hint {
  margin: 0;
  font-size: 12px;
  color: var(--mc-text-tertiary);
}

/* ==================== agent chip picker ==================== */

.agent-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
  background: var(--mc-bg-muted);
  max-height: 180px;
  overflow-y: auto;
}
.agent-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
}
.agent-pill:hover {
  border-color: var(--mc-primary);
  color: var(--mc-text-primary);
}
.agent-pill.is-selected {
  background: var(--mc-primary-bg);
  border-color: var(--mc-primary);
  color: var(--mc-primary);
  font-weight: 600;
}
.agent-pill.is-lead {
  background: rgba(217, 119, 6, 0.1);
  border-color: rgba(217, 119, 6, 0.45);
  color: #b45309;
}
.agent-pill__check {
  font-size: 11px;
  font-weight: 700;
}

.btn-success {
  padding: 8px 16px;
  background: #16a34a;
  color: #fff;
  border: none;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: filter 0.15s;
}
.btn-success:hover {
  filter: brightness(1.06);
}
.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
</style>
