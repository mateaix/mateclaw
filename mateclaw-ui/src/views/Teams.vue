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
              <button class="btn-secondary" @click="closeTeam">← {{ t('teams.back') }}</button>
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
                  :class="{ 'is-active': activeTab === 'runs' }"
                  @click="setActiveTab('runs')"
                >{{ t('teams.runs') }}</button>
                <button
                  class="view-seg"
                  :class="{ 'is-active': activeTab === 'board' }"
                  @click="setActiveTab('board')"
                >{{ t('teams.board') }}</button>
                <button
                  class="view-seg"
                  :class="{ 'is-active': activeTab === 'members' }"
                  @click="setActiveTab('members')"
                >{{ t('teams.members') }}</button>
              </div>
              <select
                v-if="activeTab === 'board'"
                class="form-input board-run-filter"
                :aria-label="t('teams.boardScope')"
                :value="store.taskRunId || ''"
                @change="changeBoardRunFilter"
              >
                <option value="">{{ t('teams.boardAllRuns') }}</option>
                <option v-for="run in runHistory.runs.value" :key="run.id" :value="run.id">
                  {{ run.title }}
                </option>
              </select>
              <button
                v-if="activeTab === 'board'"
                class="btn-primary"
                @click="openTaskCreateDialog"
              >+ {{ t('teams.createTask') }}</button>
              <button
                class="btn-secondary detail-action"
                :aria-label="t('common.refresh')"
                :title="t('common.refresh')"
                @click="refreshCurrentView"
              >
                <RefreshIcon class="detail-action-icon" />
                <span class="detail-action-label">{{ t('common.refresh') }}</span>
              </button>
              <button
                class="btn-danger detail-action"
                :aria-label="t('common.delete')"
                :title="t('common.delete')"
                @click="removeTeam"
              >
                <DeleteIcon class="detail-action-icon" />
                <span class="detail-action-label">{{ t('common.delete') }}</span>
              </button>
            </div>
          </div>

          <!-- Live activity feed -->
          <transition-group
            v-if="activeTab === 'board' && activityFeed.length > 0"
            name="activity"
            tag="div"
            class="activity-feed"
          >
            <div v-for="item in activityFeed" :key="item.key" class="activity-line">
              {{ item.text }}
            </div>
          </transition-group>

          <TeamRunsPanel
            v-if="activeTab === 'runs'"
            :runs="runHistory.runs.value"
            :loading="runHistory.loading.value"
            :error="runHistory.error.value"
            :selected-run-id="runHistory.selectedRunId.value"
            :has-more="Boolean(runHistory.nextCursor.value)"
            :loading-more="runHistory.loadingMore.value"
            @refresh="runHistory.refresh"
            @load-more="runHistory.loadMore"
            @select-run="selectRun"
          />

          <!-- Kanban board -->
          <div v-else-if="activeTab === 'board'" class="board-grid">
            <div v-for="col in boardColumns" :key="col.key" class="board-col">
              <div class="board-col__head">
                <span class="board-col__dot" :class="`dot--${col.key}`"></span>
                <span class="board-col__label">{{ col.label }}</span>
                <span class="board-col__count">{{ col.total }}</span>
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
                  <div
                    v-if="vo.task.status === 'in_progress' && vo.task.progressPercent === 100"
                    class="task-card__settling"
                  >{{ t('teams.status.settling') }}</div>
                </div>
                <button
                  v-if="col.hasMore"
                  class="board-col__more"
                  @click="loadMoreColumn(col.key)"
                >{{ t('teams.loadMore', { loaded: col.tasks.length, total: col.total }) }}</button>
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

    <TeamRunDrawer
      :open="Boolean(runHistory.selectedRun.value)"
      :run="runHistory.selectedRun.value"
      :selected-task-id="runHistory.selectedTaskId.value"
      :detail-loading="runHistory.detailLoading.value"
      :detail-error="runHistory.detailError.value"
      can-cancel
      :management-actions="canManageSelectedRun"
      :pending-actions="attentionPendingActions"
      @close="closeRun"
      @cancel="cancelRun"
      @select-task="openRunTask"
      @navigate="router.push"
      @view-task="openAttentionTask"
      @retry-task="retryAttentionTask"
      @approve-task="approveAttentionTask"
      @retry-detail="runHistory.ensureSelectedRunDetail(runHistory.selectedRunId.value!, runHistory.selectedTaskId.value)"
    />

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

    <!-- ==================== Create task dialog ==================== -->
    <Teleport to="body">
      <div v-if="taskCreateDialogVisible" class="modal-overlay" @click.self="taskCreateDialogVisible = false">
        <div class="modal modal--wide">
          <div class="modal-header">
            <h3>{{ t('teams.createTask') }}</h3>
            <button class="modal-close" @click="taskCreateDialogVisible = false">&times;</button>
          </div>
          <div class="modal-body">
            <div class="form-group">
              <label>{{ t('teams.taskSubject') }} <i>*</i></label>
              <input v-model.trim="taskForm.subject" class="form-input" maxlength="256" />
            </div>
            <div class="form-group">
              <label>{{ t('teams.taskDescription') }}</label>
              <textarea v-model="taskForm.description" class="form-input form-textarea" rows="3"></textarea>
            </div>
            <div class="form-group">
              <label>{{ t('teams.assignee') }} <i>*</i></label>
              <div class="agent-picker">
                <button
                  v-for="m in assigneeCandidates"
                  :key="m.agentId"
                  class="agent-pill"
                  :class="{ 'is-selected': taskForm.assigneeAgentId === String(m.agentId) }"
                  @click="taskForm.assigneeAgentId = String(m.agentId)"
                >
                  <span v-if="taskForm.assigneeAgentId === String(m.agentId)" class="agent-pill__check">✓</span>
                  <span class="agent-pill__icon" :style="{ color: agentIconColor(agentIcon(m.agentId, m.icon)) }">
                    <SkillIcon :value="agentIcon(m.agentId, m.icon)" :size="14" />
                  </span>
                  {{ m.name }}
                </button>
              </div>
            </div>
            <div v-if="blockerCandidates.length > 0" class="form-group">
              <label>{{ t('teams.blockedBy') }}</label>
              <div class="agent-picker">
                <button
                  v-for="vo in blockerCandidates"
                  :key="vo.task.id"
                  class="agent-pill"
                  :class="{ 'is-selected': taskForm.blockedBy.includes(String(vo.task.id)) }"
                  @click="toggleBlocker(String(vo.task.id))"
                >
                  <span v-if="taskForm.blockedBy.includes(String(vo.task.id))" class="agent-pill__check">✓</span>
                  #{{ vo.task.taskNumber }} {{ vo.task.subject }}
                </button>
              </div>
              <p class="form-hint">{{ t('teams.blockedByHint') }}</p>
            </div>
            <div class="form-group form-group--inline">
              <label>{{ t('teams.priority') }}</label>
              <input
                v-model.number="taskForm.priority"
                type="number"
                class="form-input form-input--narrow"
                min="0"
                max="99"
              />
              <label class="form-check">
                <input v-model="taskForm.requireApproval" type="checkbox" />
                {{ t('teams.requireApprovalField') }}
              </label>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="taskCreateDialogVisible = false">{{ t('common.cancel') }}</button>
            <button class="btn-primary" :disabled="taskCreating" @click="submitTaskCreate">
              {{ taskCreating ? t('common.processing') : t('common.confirm') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ==================== Task detail dialog ==================== -->
    <Teleport to="body">
      <div v-if="taskDialogVisible && currentTask" class="modal-overlay" @click.self="closeTaskDetail">
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
            <button class="modal-close" @click="closeTaskDetail">&times;</button>
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
              <div class="task-detail__text task-detail__markdown markdown-body" v-html="renderedCurrentTaskDescription" />
            </div>
            <div v-if="currentTask.task.result" class="task-detail__block">
              <div class="task-detail__label">{{ t('teams.result') }}</div>
              <div
                class="task-detail__text task-detail__text--boxed task-detail__markdown markdown-body"
                v-html="renderedCurrentTaskResult"
              />
            </div>
            <div v-if="currentTask.task.reason" class="task-detail__reason">
              {{ currentTask.task.reason }}
            </div>
            <div v-if="currentTask.task.blockedBy" class="task-detail__block">
              <div class="task-detail__label">{{ t('teamRuns.dependencies') }}</div>
              <div class="task-detail__text">{{ currentTask.task.blockedBy }}</div>
            </div>
            <div v-if="currentDeliverables.length > 0" class="task-detail__block">
              <div class="task-detail__label">{{ t('teams.deliverables') }}</div>
              <div class="deliverable-list">
                <a
                  v-for="(file, idx) in currentDeliverables"
                  :key="idx"
                  class="deliverable-row"
                  :href="file.url"
                  target="_blank"
                  rel="noopener"
                >
                  <span class="deliverable-row__icon">📄</span>
                  <span class="deliverable-row__name">{{ file.name }}</span>
                </a>
              </div>
            </div>
            <div v-if="taskEvents.length > 0" class="task-detail__block">
              <div class="task-detail__label">{{ t('teams.timeline') }}</div>
              <div class="timeline">
                <div v-for="ev in taskEvents" :key="ev.id" class="timeline-row">
                  <span class="timeline-row__dot" :class="`dot-ev--${ev.eventType}`"></span>
                  <span class="timeline-row__time">{{ (ev.createTime || '').slice(5, 16) }}</span>
                  <span class="timeline-row__type">{{ t(`teams.eventType.${ev.eventType}`, ev.eventType) }}</span>
                  <span v-if="ev.actorType === 'agent'" class="timeline-row__actor">
                    {{ agentStore.agents.find(a => String(a.id) === String(ev.actorId))?.name || ev.actorId }}
                  </span>
                  <span v-else-if="ev.actorId" class="timeline-row__actor">{{ ev.actorId }}</span>
                  <div
                    v-if="ev.detail"
                    class="timeline-row__detail timeline-row__markdown markdown-body"
                    v-html="renderTaskMarkdown(ev.detail)"
                  />
                </div>
              </div>
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
              v-if="currentTask.task.conversationId"
              class="btn-secondary modal-footer__left"
              @click="openTaskRun"
            >{{ t('teams.viewRun') }}</button>
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
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete as DeleteIcon, Refresh as RefreshIcon } from '@element-plus/icons-vue'
import { teamApi, teamRunApi } from '@/api/index'
import type { TeamMemberVO, TeamRun, TeamRunTask, TeamTaskComment, TeamTaskDeliverable, TeamTaskEvent, TeamTaskVO } from '@/api/index'
import { subscribeTeamEvents } from '@/composables/useTeamEvents'
import { discoveredTeamTaskKey, shouldShowInGlobalTeamFeed } from '@/composables/chat/teamEventOwnership'
import {
  buildTeamsRouteQuery,
  clearTeamsRunSelection,
  parseTeamsRouteQuery,
  reconcileTeamsRoute,
  type TeamsDetailView,
  type TeamsRouteState,
} from '@/composables/teamsRouteState'
import { useTeamRunHistory } from '@/composables/useTeamRunHistory'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import { buildWorkerChatRoute } from '@/components/team-run/teamRunPresentation'
import TeamRunDrawer from '@/components/team-run/TeamRunDrawer.vue'
import TeamRunsPanel from '@/components/team-run/TeamRunsPanel.vue'
import {
  canManageTeamRunAttention,
  refreshAttentionTaskContext,
  runAttentionTaskAction,
  type TeamAttentionAction,
  type TeamAttentionActionContext,
} from '@/components/team-run/teamRunAttentionHandlers'
import SkillIcon from '@/components/common/SkillIcon.vue'
import { agentIconColor } from '@/utils/agentIconColor'
import { useAgentStore } from '@/stores/useAgentStore'
import { useTeamStore } from '@/stores/useTeamStore'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const store = useTeamStore()
const agentStore = useAgentStore()
const workspaceStore = useWorkspaceStore()
const runHistory = useTeamRunHistory()
const { renderMarkdown } = useMarkdownRenderer()

const activeTab = ref<TeamsDetailView>('runs')
const taskDialogVisible = ref(false)
const currentTask = ref<TeamTaskVO | null>(null)
const comments = ref<TeamTaskComment[]>([])
const newComment = ref('')
const taskEvents = ref<TeamTaskEvent[]>([])
const renderedCurrentTaskDescription = computed(() => renderMarkdown(currentTask.value?.task.description || ''))
const renderedCurrentTaskResult = computed(() => renderMarkdown(currentTask.value?.task.result || ''))
const pendingAttentionActions = reactive(new Set<string>())
const canManageSelectedRun = computed(() => workspaceStore.accessLoaded
  && canManageTeamRunAttention(
    workspaceStore.currentRole,
    workspaceStore.currentWorkspaceId,
    runHistory.selectedRun.value?.workspaceId ?? null,
  ))
const attentionPendingActions = computed(() => {
  const run = runHistory.selectedRun.value
  const team = store.currentTeam
  if (!run || !team) return []
  const prefix = `${team.team.id}:${run.id}:`
  return [...pendingAttentionActions]
    .filter(key => key.startsWith(prefix))
    .map(key => key.slice(prefix.length))
})

function renderTaskMarkdown(value: string | null | undefined): string {
  return value ? renderMarkdown(value) : ''
}
let previousRouteState: TeamsRouteState | null = null
let routeReconciliationRevision = 0

// ==================== board columns ====================

const COLUMN_DEFS = [
  { key: 'todo', statuses: ['pending', 'blocked'], terminal: false },
  { key: 'in_progress', statuses: ['in_progress'], terminal: false },
  { key: 'in_review', statuses: ['in_review'], terminal: false },
  { key: 'completed', statuses: ['completed'], terminal: true },
  { key: 'closed', statuses: ['failed', 'cancelled', 'stale'], terminal: true },
] as const

const boardColumns = computed(() =>
  COLUMN_DEFS.map((col) => {
    const colTasks = store.tasks.filter((vo) =>
      (col.statuses as readonly string[]).includes(vo.task.status),
    )
    // Terminal columns are windowed: the header shows the true database
    // total and the column body ends with a load-more control.
    const total = col.key === 'completed' ? store.completedTotal
      : col.key === 'closed' ? store.closedTotal
      : colTasks.length
    const hasMore = col.key === 'completed' ? store.completedHasMore
      : col.key === 'closed' ? store.closedHasMore
      : false
    return {
      key: col.key,
      label: t(`teams.column.${col.key}`),
      tasks: colTasks,
      total,
      hasMore,
    }
  }),
)

async function loadMoreColumn(key: string) {
  if (!store.currentTeam) return
  const teamId = store.currentTeam.team.id
  if (key === 'completed') await store.loadMoreCompleted(teamId)
  else if (key === 'closed') await store.loadMoreClosed(teamId)
}

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

// ==================== live board events ====================

/** Recent activity lines shown above the board; each expires after a few seconds. */
const activityFeed = ref<{ key: number; text: string }[]>([])
let activityKey = 0
let unsubscribeEvents: (() => void) | null = null
let refreshDebounce: ReturnType<typeof setTimeout> | null = null
const incrementalTaskKeys = ref<Set<string>>(new Set())

const baseEventOwnershipContext = computed(() => {
  const runs = runHistory.runs.value
  const boardTasks = store.tasks.map(entry => entry.task)
  const projectedTasks = runs.flatMap(run => run.tasks)
  return {
    runIds: new Set([
      ...runs.map(run => run.id),
      ...boardTasks.flatMap(task => task.runId ? [task.runId] : []),
    ]),
    taskKeys: new Set([...boardTasks, ...projectedTasks].flatMap(task =>
      task.runId ? [`${task.runId}:${task.id}`] : [])),
    conversationIds: new Set([
      ...runs.flatMap(run => run.leadConversationId ? [run.leadConversationId] : []),
      ...[...boardTasks, ...projectedTasks].flatMap(task =>
        task.conversationId ? [task.conversationId] : []),
    ]),
  }
})

const eventOwnershipContext = computed(() => ({
  runIds: baseEventOwnershipContext.value.runIds,
  conversationIds: baseEventOwnershipContext.value.conversationIds,
  taskKeys: new Set([
    ...baseEventOwnershipContext.value.taskKeys,
    ...incrementalTaskKeys.value,
  ]),
}))

function onBoardEvent(e: { event: string; data: Record<string, unknown> }) {
  if (!e.event.startsWith('team_task_')) return
  // Event-driven refresh, debounced so bursts collapse into one fetch.
  if (refreshDebounce) clearTimeout(refreshDebounce)
  refreshDebounce = setTimeout(() => {
    refreshDebounce = null
    refreshBoard()
  }, 300)

  const discoveredKey = discoveredTeamTaskKey(e, baseEventOwnershipContext.value.runIds)
  if (discoveredKey && !incrementalTaskKeys.value.has(discoveredKey)) {
    incrementalTaskKeys.value = new Set([...incrementalTaskKeys.value, discoveredKey])
  }
  if (!shouldShowInGlobalTeamFeed(e, eventOwnershipContext.value)) return

  const type = e.event.slice('team_task_'.length)
  const subject = String(e.data.subject ?? '')
  const taskNumber = e.data.taskNumber != null ? `#${e.data.taskNumber} ` : ''
  const key = ++activityKey
  activityFeed.value.push({
    key,
    text: `${taskNumber}${subject} · ${t(`teams.eventType.${type}`, type)}`,
  })
  if (activityFeed.value.length > 3) activityFeed.value.shift()
  setTimeout(() => {
    activityFeed.value = activityFeed.value.filter((item) => item.key !== key)
  }, 8000)
}

function startEventSubscription(teamId: string) {
  stopEventSubscription()
  if (document.hidden) return
  unsubscribeEvents = subscribeTeamEvents(teamId, onBoardEvent)
}

function stopEventSubscription() {
  if (unsubscribeEvents) {
    unsubscribeEvents()
    unsubscribeEvents = null
  }
  activityFeed.value = []
  incrementalTaskKeys.value = new Set()
}

// ==================== polling ====================

let pollTimer: ReturnType<typeof setInterval> | null = null

function startPolling() {
  stopPolling()
  if (document.hidden) return
  pollTimer = setInterval(() => {
    const team = store.currentTeam
    if (team && store.hasActiveTasks) {
      store.fetchTasks(team.team.id)
    }
  }, 3000)
}

/**
 * A team SSE stream occupies one HTTP/1.1 connection for the life of the
 * page. Pause background tabs so several open team pages cannot consume all
 * same-origin connection slots and starve ordinary board API requests.
 */
function handleVisibilityChange() {
  const team = store.currentTeam
  if (document.hidden || !team) {
    stopPolling()
    stopEventSubscription()
    return
  }
  startPolling()
  startEventSubscription(String(team.team.id))
  void store.fetchTasks(String(team.team.id))
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(
  () => store.currentTeam,
  (team) => {
    if (team) {
      startPolling()
      startEventSubscription(String(team.team.id))
    } else {
      stopPolling()
      stopEventSubscription()
    }
  },
)

watch(
  () => route.query,
  async (query) => {
    const reconciliationRevision = ++routeReconciliationRevision
    const routeIsCurrent = () => reconciliationRevision === routeReconciliationRevision
    const state = parseTeamsRouteQuery(query)
    const reconciliation = reconcileTeamsRoute(previousRouteState, state)
    previousRouteState = state
    if (!state.teamId) {
      dismissTaskDetail()
      if (store.currentTeam) store.closeTeam()
      runHistory.close()
      activeTab.value = 'runs'
      return
    }
    try {
      if (String(store.currentTeam?.team.id ?? '') !== state.teamId) {
        await store.openTeam(state.teamId)
        if (!routeIsCurrent()) return
        await runHistory.open(state.teamId)
        if (!routeIsCurrent()) return
        await store.setTaskRunId(state.teamId, runHistory.runs.value[0]?.id ?? null)
        if (!routeIsCurrent()) return
      }
      activeTab.value = state.view ?? 'runs'
      runHistory.select(reconciliation.selectedRunId, reconciliation.selectedTaskId)
      if (reconciliation.selectedRunId && !runHistory.selectedRun.value) {
        const loaded = await runHistory.refreshRun(reconciliation.selectedRunId, state.teamId)
        if (!routeIsCurrent()) return
        if (!loaded) {
          dismissTaskDetail()
          runHistory.select(null)
          await router.replace({
            path: '/teams',
            query: clearTeamsRunSelection(state),
          })
          return
        }
      }
      if (reconciliation.selectedRunId
        && runHistory.selectedRun.value?.projectionCompleteness !== 'full') {
        await runHistory.ensureSelectedRunDetail(
          reconciliation.selectedRunId,
          reconciliation.selectedTaskId,
          state.teamId,
        )
        if (!routeIsCurrent()) return
      }
      if (reconciliation.taskAction === 'close') dismissTaskDetail()
      if (reconciliation.taskAction === 'load'
        && reconciliation.selectedTaskId
        && currentTask.value?.task.id !== reconciliation.selectedTaskId) {
        const task = runHistory.selectedRun.value?.tasks.find(item => item.id === reconciliation.selectedTaskId)
        if (task) {
          await openRunTask(task, false, routeIsCurrent)
        } else {
          dismissTaskDetail()
          runHistory.select(reconciliation.selectedRunId, null)
          await router.replace({
            path: '/teams',
            query: buildTeamsRouteQuery(state.teamId, 'runs', reconciliation.selectedRunId),
          })
        }
      }
    } catch (e: any) {
      if (routeIsCurrent()) ElMessage.error(e?.message || 'failed')
    }
  },
  { immediate: true, deep: true },
)

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  store.fetchTeams()
  if (agentStore.agents.length === 0) {
    agentStore.fetchAgents()
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  stopPolling()
  stopEventSubscription()
})

// ==================== team actions ====================

async function openTeam(teamId: string) {
  try {
    await store.openTeam(teamId)
    await runHistory.open(teamId)
    await store.setTaskRunId(teamId, runHistory.runs.value[0]?.id ?? null)
    activeTab.value = 'runs'
    runHistory.select(null)
    await router.push({ path: '/teams', query: buildTeamsRouteQuery(teamId) })
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.msg || e?.msg || e?.message || t('common.failed'))
  }
}

async function closeTeam() {
  store.closeTeam()
  runHistory.close()
  await router.push({ path: '/teams', query: {} })
}

async function setActiveTab(view: TeamsDetailView) {
  if (!store.currentTeam) return
  activeTab.value = view
  if (view !== 'runs') runHistory.select(null)
  await router.push({
    path: '/teams',
    query: buildTeamsRouteQuery(String(store.currentTeam.team.id), view),
  })
}

async function selectRun(run: TeamRun) {
  runHistory.select(run.id)
  await router.push({ path: '/teams', query: buildTeamsRouteQuery(run.teamId, 'runs', run.id) })
}

async function closeRun() {
  if (!store.currentTeam) return
  runHistory.select(null)
  await router.push({
    path: '/teams',
    query: buildTeamsRouteQuery(String(store.currentTeam.team.id), 'runs'),
  })
}

async function cancelRun(runId: string) {
  try {
    await ElMessageBox.confirm(t('teamRuns.cancelConfirm'), { type: 'warning' })
  } catch {
    return
  }
  await teamRunApi.cancel(runId)
  await Promise.all([runHistory.refreshRun(runId), refreshBoard()])
}

function refreshBoard() {
  if (store.currentTeam) {
    return store.fetchTasks(store.currentTeam.team.id)
  }
  return Promise.resolve()
}

async function changeBoardRunFilter(event: Event) {
  if (!store.currentTeam) return
  const value = (event.target as HTMLSelectElement).value || null
  await store.setTaskRunId(String(store.currentTeam.team.id), value)
}

function refreshCurrentView() {
  return activeTab.value === 'runs' ? runHistory.refresh() : refreshBoard()
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

// ==================== create task ====================

const TERMINAL_TASK_STATUSES = ['completed', 'failed', 'cancelled']

const taskCreateDialogVisible = ref(false)
const taskCreating = ref(false)
const taskForm = reactive({
  subject: '',
  description: '',
  assigneeAgentId: '',
  priority: 0,
  requireApproval: false,
  blockedBy: [] as string[],
})

// The lead orchestrates and cannot execute tasks — assignable members only.
const assigneeCandidates = computed(() => store.members.filter((m) => m.role !== 'lead'))

const blockerCandidates = computed(() =>
  store.tasks.filter((vo) => !TERMINAL_TASK_STATUSES.includes(vo.task.status)),
)

function openTaskCreateDialog() {
  taskForm.subject = ''
  taskForm.description = ''
  taskForm.assigneeAgentId = ''
  taskForm.priority = 0
  taskForm.requireApproval = false
  taskForm.blockedBy = []
  taskCreateDialogVisible.value = true
}

function toggleBlocker(taskId: string) {
  const idx = taskForm.blockedBy.indexOf(taskId)
  if (idx >= 0) {
    taskForm.blockedBy.splice(idx, 1)
  } else {
    taskForm.blockedBy.push(taskId)
  }
}

async function submitTaskCreate() {
  if (!store.currentTeam) return
  if (!taskForm.subject || !taskForm.assigneeAgentId) {
    ElMessage.warning(t('teams.taskCreateIncomplete'))
    return
  }
  taskCreating.value = true
  try {
    await teamApi.createTask(store.currentTeam.team.id, {
      subject: taskForm.subject,
      description: taskForm.description || undefined,
      assigneeAgentId: taskForm.assigneeAgentId,
      priority: taskForm.priority,
      requireApproval: taskForm.requireApproval,
      blockedBy: taskForm.blockedBy.length ? [...taskForm.blockedBy] : undefined,
    })
    taskCreateDialogVisible.value = false
    ElMessage.success(t('common.success'))
    await Promise.all([refreshBoard(), runHistory.refresh()])
  } catch (e: any) {
    ElMessage.error(e?.message || 'failed')
  } finally {
    taskCreating.value = false
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

/** Deliverables live under the "deliverables" key of the task's metadata JSON. */
const currentDeliverables = computed<TeamTaskDeliverable[]>(() => {
  const raw = currentTask.value?.task.metadata
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed?.deliverables) ? parsed.deliverables : []
  } catch {
    return []
  }
})

/** Open the member's execution transcript (its child conversation) in the chat console. */
function openTaskRun() {
  const taskVO = currentTask.value
  const task = taskVO?.task
  if (!task?.conversationId) return
  router.push(buildWorkerChatRoute({
    conversationId: task.conversationId,
    agentId: task.assigneeAgentId,
    runId: task.runId ?? taskVO?.runId,
    taskId: task.id,
    teamId: task.teamId,
    leadConversationId: task.leadConversationId,
  }))
}

function taskVoFromRun(task: TeamRunTask): TeamTaskVO {
  const { createTime, updateTime, ...taskFields } = task
  return {
    task: {
      ...taskFields,
      ...(createTime ? { createTime } : {}),
      ...(updateTime ? { updateTime } : {}),
      dispatchCount: 0,
      leadConversationId: runHistory.selectedRun.value?.leadConversationId ?? null,
    },
    assigneeName: store.members.find(member => member.agentId === task.assigneeAgentId)?.name ?? null,
    ownerName: null,
    runId: task.runId,
  }
}

async function openRunTask(
  task: TeamRunTask,
  updateRoute = true,
  shouldApply: () => boolean = () => true,
) {
  runHistory.select(task.runId, task.id)
  await openTask(taskVoFromRun(task), shouldApply)
  if (!shouldApply()) return
  if (updateRoute) {
    await router.push({
      path: '/teams',
      query: buildTeamsRouteQuery(task.teamId, 'runs', task.runId, task.id),
    })
  }
}

function selectedRunTask(taskId: string) {
  return runHistory.selectedRun.value?.tasks.find(task => task.id === taskId) ?? null
}

async function openAttentionTask(taskId: string) {
  const task = selectedRunTask(taskId)
  if (task) runHistory.select(task.runId, task.id)
}

function captureAttentionContext(taskId: string): TeamAttentionActionContext | null {
  const run = runHistory.selectedRun.value
  const teamId = String(store.currentTeam?.team.id ?? '')
  if (!run || !teamId || run.teamId !== teamId || !run.tasks.some(task => task.id === taskId)) return null
  return { teamId, runId: run.id, taskId }
}

async function refreshAfterTaskAction(context: TeamAttentionActionContext) {
  await refreshAttentionTaskContext({
    context,
    currentTeamId: () => store.currentTeam ? String(store.currentTeam.team.id) : null,
    currentTaskId: () => currentTask.value?.task.id ?? null,
    reloadTask: () => reloadTask(false),
    refreshBoard: teamId => store.fetchTasks(teamId),
    refreshRun: (runId, teamId) => runHistory.refreshRun(runId, teamId),
  })
}

async function performAttentionAction(taskId: string, action: TeamAttentionAction) {
  const context = captureAttentionContext(taskId)
  if (!context || !canManageSelectedRun.value) return false
  return runAttentionTaskAction({
    context,
    action,
    pending: pendingAttentionActions,
    execute: () => action === 'approve'
      ? teamApi.approveTask(context.teamId, context.taskId)
      : teamApi.retryTask(context.teamId, context.taskId),
    refresh: () => refreshAfterTaskAction(context),
    onError: cause => ElMessage.error(
      cause instanceof Error && cause.message ? cause.message : t('teams.actionFailed', 'Operation failed'),
    ),
  })
}

async function approveTaskById(taskId: string) {
  if (!store.currentTeam) return
  await teamApi.approveTask(store.currentTeam.team.id, taskId)
  ElMessage.success(t('teams.approved'))
  await reloadTask()
}

async function retryTaskById(taskId: string) {
  if (!store.currentTeam) return
  await teamApi.retryTask(store.currentTeam.team.id, taskId)
  await reloadTask()
}

async function approveAttentionTask(taskId: string) {
  if (await performAttentionAction(taskId, 'approve')) ElMessage.success(t('teams.approved'))
}

async function retryAttentionTask(taskId: string) {
  await performAttentionAction(taskId, 'retry')
}

async function openTask(vo: TeamTaskVO, shouldApply: () => boolean = () => true) {
  if (!store.currentTeam) return
  try {
    const res: any = await teamApi.getTask(store.currentTeam.team.id, vo.task.id)
    if (!shouldApply()) return
    currentTask.value = res.data?.task || vo
    comments.value = res.data?.comments || []
    taskDialogVisible.value = true
    // Timeline loads after the dialog opens; a failure just leaves it empty.
    taskEvents.value = []
    teamApi.listTaskEvents(store.currentTeam.team.id, vo.task.id)
      .then((eventsRes: any) => {
        if (shouldApply() && currentTask.value?.task.id === vo.task.id) {
          taskEvents.value = eventsRes.data || []
        }
      })
      .catch(() => {})
  } catch (e: any) {
    if (!shouldApply()) return
    ElMessage.error(e?.message || 'failed')
  }
}

async function reloadTask(refreshContext = true) {
  if (currentTask.value) {
    const vo = currentTask.value
    await openTask(vo)
    if (!refreshContext) return
    await Promise.all([
      refreshBoard(),
      vo.task.runId ? runHistory.refreshRun(vo.task.runId) : Promise.resolve(),
    ])
  }
}

async function closeTaskDetail() {
  dismissTaskDetail()
  if (!store.currentTeam || activeTab.value !== 'runs') return
  runHistory.select(runHistory.selectedRunId.value, null)
  await router.push({
    path: '/teams',
    query: buildTeamsRouteQuery(
      String(store.currentTeam.team.id),
      'runs',
      runHistory.selectedRunId.value,
    ),
  })
}

function dismissTaskDetail() {
  taskDialogVisible.value = false
  currentTask.value = null
  comments.value = []
  taskEvents.value = []
  newComment.value = ''
}

async function submitComment() {
  if (!store.currentTeam || !currentTask.value || !newComment.value) return
  await teamApi.commentTask(store.currentTeam.team.id, currentTask.value.task.id, newComment.value)
  newComment.value = ''
  await reloadTask()
}

async function approveTask() {
  if (!currentTask.value) return
  await approveTaskById(currentTask.value.task.id)
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
  if (!currentTask.value) return
  await retryTaskById(currentTask.value.task.id)
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
.detail-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  white-space: nowrap;
}
.detail-action-icon {
  width: 15px;
  height: 15px;
  flex: none;
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
.board-run-filter {
  width: auto;
  max-width: 220px;
  flex: none;
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
  .detail-header {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .detail-header__right {
    width: 100%;
    min-width: 0;
    overflow-x: auto;
    scrollbar-width: none;
  }
  .detail-header__right::-webkit-scrollbar {
    display: none;
  }
  .view-switch {
    flex: none;
  }
  .view-seg {
    padding: 6px 13px;
    white-space: nowrap;
  }
  .detail-action {
    width: 40px;
    height: 40px;
    padding: 0;
    flex: none;
  }
  .detail-action-label {
    display: none;
  }
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
.task-card__settling {
  margin-top: 5px;
  color: var(--mc-text-tertiary);
  font-size: 11px;
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
.task-detail__markdown {
  white-space: normal;
  overflow-wrap: anywhere;
}
.task-detail__markdown :deep(p),
.timeline-row__markdown :deep(p) {
  margin: 0 0 8px;
}
.task-detail__markdown :deep(p:last-child),
.timeline-row__markdown :deep(p:last-child) {
  margin-bottom: 0;
}
.task-detail__markdown :deep(h1),
.task-detail__markdown :deep(h2),
.task-detail__markdown :deep(h3),
.timeline-row__markdown :deep(h1),
.timeline-row__markdown :deep(h2),
.timeline-row__markdown :deep(h3) {
  margin: 10px 0 6px;
  color: var(--mc-text-primary);
  line-height: 1.35;
}
.task-detail__markdown :deep(ul),
.task-detail__markdown :deep(ol),
.timeline-row__markdown :deep(ul),
.timeline-row__markdown :deep(ol) {
  margin: 6px 0;
  padding-left: 20px;
}
.task-detail__markdown :deep(table),
.timeline-row__markdown :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
  margin: 8px 0;
}
.task-detail__markdown :deep(th),
.task-detail__markdown :deep(td),
.timeline-row__markdown :deep(th),
.timeline-row__markdown :deep(td) {
  border: 1px solid var(--mc-border);
  padding: 5px 8px;
  text-align: left;
  white-space: nowrap;
}
.task-detail__markdown :deep(th),
.timeline-row__markdown :deep(th) {
  background: var(--mc-bg-subtle, rgba(0, 0, 0, 0.04));
  color: var(--mc-text-primary);
}
.task-detail__markdown :deep(blockquote),
.timeline-row__markdown :deep(blockquote) {
  margin: 8px 0;
  padding-left: 10px;
  border-left: 3px solid var(--mc-primary);
  color: var(--mc-text-secondary);
}
.task-detail__markdown :deep(pre),
.timeline-row__markdown :deep(pre) {
  max-width: 100%;
  overflow-x: auto;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--mc-bg-subtle, rgba(0, 0, 0, 0.05));
}
.task-detail__markdown :deep(code),
.timeline-row__markdown :deep(code) {
  overflow-wrap: anywhere;
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
.activity-feed {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
}
.activity-line {
  font-size: 12px;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  padding: 5px 10px;
}
.activity-enter-active,
.activity-leave-active {
  transition: opacity 0.3s, transform 0.3s;
}
.activity-enter-from,
.activity-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
.timeline {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.timeline-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  color: var(--mc-text-secondary);
}
.timeline-row__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--mc-border);
  flex-shrink: 0;
  align-self: center;
}
.dot-ev--completed,
.dot-ev--approved { background: var(--mc-success, #67c23a); }
.dot-ev--failed,
.dot-ev--blocker,
.dot-ev--cancelled,
.dot-ev--rejected { background: var(--mc-danger, #d9534f); }
.dot-ev--dispatched,
.dot-ev--progress { background: var(--mc-primary); }
.timeline-row__time {
  font-variant-numeric: tabular-nums;
  color: var(--mc-text-tertiary);
  flex-shrink: 0;
}
.timeline-row__type {
  font-weight: 600;
  color: var(--mc-text-primary);
  flex-shrink: 0;
}
.timeline-row__actor {
  color: var(--mc-text-secondary);
  flex-shrink: 0;
}
.timeline-row__detail {
  flex: 1;
  min-width: 0;
  color: var(--mc-text-secondary);
  overflow-wrap: anywhere;
}
.timeline-row__markdown {
  white-space: normal;
}
.board-col__more {
  width: 100%;
  padding: 8px 0;
  margin-top: 2px;
  border: 1px dashed var(--mc-border);
  border-radius: 10px;
  background: transparent;
  color: var(--mc-text-secondary);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
}
.board-col__more:hover {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
}
.deliverable-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.deliverable-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
  font-size: 13px;
  text-decoration: none;
  transition: border-color 0.15s;
}
.deliverable-row:hover {
  border-color: var(--mc-primary);
}
.deliverable-row__name {
  font-weight: 600;
}
.modal-footer__left {
  margin-right: auto;
}
.form-group--inline {
  flex-direction: row;
  align-items: center;
  gap: 12px;
}
.form-input--narrow {
  width: 90px;
}
.form-check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--mc-text-secondary);
  cursor: pointer;
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
