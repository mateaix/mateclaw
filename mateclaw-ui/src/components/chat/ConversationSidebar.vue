<template>
  <div
    class="conversation-panel"
    :class="{ 'mobile-open': mobileOpen, 'conv-collapsed': collapsed && !isMobile }"
  >
    <div class="panel-header">
      <div v-if="!collapsed || isMobile" class="panel-header-copy">
        <div class="panel-kicker">{{ t('nav.chat') }}</div>
        <h2 class="panel-title">{{ t('chat.conversations') }}</h2>
      </div>
      <div class="panel-header-actions">
        <button
          v-if="(!collapsed || isMobile) && conversations.length > 0"
          class="panel-icon-btn"
          :class="{ active: selectMode }"
          @click="toggleSelectMode"
          :title="selectMode ? t('chat.exitSelectMode') : t('chat.selectMode')"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 11 12 14 22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg>
        </button>
        <button class="new-chat-btn" @click="emit('new-chat')" :title="`${t('chat.newChat')} (⌘N)`">
          <el-icon><Plus /></el-icon>
        </button>
      </div>
    </div>
    <!-- Collapse toggle -->
    <button
      v-if="!isMobile"
      class="conv-collapse-btn"
      @click="emit('toggle-collapse')"
      :title="collapsed ? t('common.expandSidebar') : t('common.collapseSidebar')"
    >
      <svg v-if="!collapsed" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
      <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
    </button>

    <div class="agent-selector">
      <AgentPickerDialog
        block
        :model-value="selectedAgentId"
        :agents="agents"
        :compact="collapsed && !isMobile"
        :placeholder="t('chat.selectAgent')"
        @change="onAgentChange"
      />
    </div>

    <div
      v-if="(!collapsed || isMobile) && agentFilterOptions.length > 1"
      class="conv-filter"
    >
      <select v-model="convAgentFilter" class="conv-filter-select">
        <option value="">{{ t('chat.allAgents') }}</option>
        <option v-for="opt in agentFilterOptions" :key="opt.id" :value="opt.id">{{ opt.name }}</option>
      </select>
    </div>

    <div class="conversation-list">
      <template v-for="group in groupedConversations" :key="group.label">
        <div v-if="!collapsed || isMobile" class="conv-group-title">{{ group.label }}</div>
        <McTooltip
          v-for="conv in group.items"
          :key="conv.conversationId"
          :content="conv.title"
          placement="right"
          :disabled="!collapsed || isMobile || !conv.title"
        >
          <div
            class="conv-item"
            :class="{
              active: !selectMode && currentConversationId === conv.conversationId,
              'is-running': conv.streamStatus === 'running',
              'is-selected': selectMode && selectedConvIds.includes(conv.conversationId),
              'menu-open': menuConvId === conv.conversationId,
            }"
            @click="onConvClick(conv)"
          >
            <label
              v-if="selectMode && (!collapsed || isMobile)"
              class="conv-checkbox"
              @click.stop
            >
              <input
                type="checkbox"
                :checked="selectedConvIds.includes(conv.conversationId)"
                @change="toggleConvSelection(conv)"
              />
            </label>
            <div class="conv-icon">
              <img :src="channelIconUrl(conv.source)" width="14" height="14" alt="" />
              <span
                v-if="conv.streamStatus === 'running'"
                class="conv-running-dot"
                :title="t('chat.streamGenerating')"
              ></span>
            </div>
            <div v-if="!collapsed || isMobile" class="conv-info">
              <input
                v-if="renamingConvId === conv.conversationId"
                v-model="renameText"
                class="conv-title-input"
                @keydown.enter="confirmRename(conv)"
                @keydown.escape="cancelRename"
                @blur="confirmRename(conv)"
                @click.stop
              />
              <div v-else class="conv-title" @dblclick.stop="startRename(conv)">
                <span>{{ conv.title }}</span>
                <span
                  v-if="convGoalStatus(conv.conversationId) === 'active'"
                  class="conv-goal-dot"
                  :title="t('goal.sidebarActive', '此对话有正在进行的目标')"
                ></span>
                <span
                  v-if="hasUnread(conv)"
                  class="conv-unread-dot"
                  :title="t('chat.hasUnread', '有新内容')"
                ></span>
                <span
                  v-if="conv.streamStatus === 'running'"
                  class="conv-running-badge"
                  :title="t('chat.streamGenerating')"
                >
                  <span class="conv-running-badge-pulse"></span>
                  {{ t('chat.streamGenerating') }}
                </span>
              </div>
              <div class="conv-meta">
                <span>{{ t('chat.messages', { count: conv.messageCount }) }}</span>
                <span class="conv-dot">·</span>
                <span>{{ formatConversationTime(conv.lastActiveTime) }}</span>
              </div>
            </div>
            <!-- Single overflow ("⋮") button — opens the conversation context menu. -->
            <div v-if="!selectMode && (!collapsed || isMobile)" class="conv-kebab-wrap">
              <button
                class="conv-kebab"
                :class="{ open: menuConvId === conv.conversationId }"
                @click.stop="openMenu(conv, $event)"
                :title="t('common.more')"
              >
                <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="19" r="1.6"/></svg>
              </button>
            </div>
          </div>
        </McTooltip>
      </template>

      <div v-if="conversations.length === 0" class="empty-convs">
        <p>{{ t('chat.noConversations') }}</p>
        <p>{{ t('chat.startNewChat') }}</p>
      </div>
      <div v-else-if="groupedConversations.length === 0" class="empty-convs">
        <p>{{ t('chat.noConversations') }}</p>
      </div>
    </div>

    <div v-if="selectMode && (!collapsed || isMobile)" class="conv-select-bar">
      <button class="conv-select-all" @click="toggleSelectAll">
        {{ allVisibleSelected ? t('chat.deselectAll') : t('chat.selectAll') }}
      </button>
      <span class="conv-select-count">{{ t('chat.selectedCount', { count: selectedConvIds.length }) }}</span>
      <button
        class="conv-batch-delete"
        :disabled="selectedConvIds.length === 0"
        @click="batchDeleteSelected"
      >
        {{ t('chat.batchDelete') }}
      </button>
    </div>

    <!-- Conversation context menu (kebab). -->
    <DropdownMenu
      :open="!!menuConv"
      :anchor="menuAnchor"
      :items="menuItems"
      @select="onMenuSelect"
      @close="closeMenu"
    >
      <template #item-icon="{ item }">
        <svg v-if="item.key === 'pin'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="17" x2="12" y2="22"/><path d="M9 10.76V5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v5.76a2 2 0 0 0 .59 1.41L17 14H7l1.41-1.83A2 2 0 0 0 9 10.76z"/></svg>
        <svg v-else-if="item.key === 'rename'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
        <svg v-else-if="item.key === 'delete'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
      </template>
    </DropdownMenu>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { Plus } from '@element-plus/icons-vue'
import AgentPickerDialog from '@/components/common/AgentPickerDialog.vue'
import DropdownMenu, { type DropdownMenuItem } from '@/components/common/DropdownMenu.vue'
import McTooltip from '@/components/common/McTooltip.vue'
import { conversationApi } from '@/api/index'
import { channelIconUrl } from '@/utils/channelSource'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import type { Conversation, Agent } from '@/types'
import { useGoalStore } from '@/stores/useGoalStore'

const goalStore = useGoalStore()
/** Sidebar marker: a 6 px dot next to the conv title when that conv
 *  has an active goal. Color tracks status (orange = in progress).
 *  Falls back silently when the store hasn't loaded this conv yet. */
function convGoalStatus(cid: string): string | null {
  const g = goalStore.activeGoalByConv?.[cid]
  return g?.status ?? null
}

const props = defineProps<{
  conversations: Conversation[]
  currentConversationId: string
  agents: Agent[]
  selectedAgentId: string | number
  collapsed: boolean
  mobileOpen: boolean
  isMobile: boolean
}>()

const emit = defineEmits<{
  (e: 'select', conv: Conversation): void
  (e: 'new-chat'): void
  (e: 'agent-picked', value: string | number | null): void
  (e: 'toggle-collapse'): void
  (e: 'refresh'): void
  (e: 'deleted', ids: string[]): void
}>()

const { t } = useI18n()

function onAgentChange(value: string | number | null) {
  emit('agent-picked', value)
}

// ==================== Agent filter ====================
// Narrow the list down to a single agent's conversations.
const convAgentFilter = ref('')

// Distinct agents present in the conversation list — drives the filter
// dropdown. Hidden when fewer than two agents have conversations.
const agentFilterOptions = computed(() => {
  const seen = new Map<string, string>()
  for (const conv of props.conversations) {
    if (conv.agentId == null || conv.agentId === '') continue
    const id = String(conv.agentId)
    if (!seen.has(id)) seen.set(id, conv.agentName || id)
  }
  return [...seen.entries()].map(([id, name]) => ({ id, name }))
})

// ==================== Grouping ====================
const groupedConversations = computed(() => {
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const yesterdayStart = todayStart - 86400000
  const last7Start = todayStart - 7 * 86400000

  // Pinned group sits at the top: the unified cron output (tasks_<wsId>) plus
  // any conversation the user has explicitly pinned, so important threads stay
  // reachable in one glance even after a busy day pushes others ahead.
  const pinned: Conversation[] = []
  const groups: { label: string; items: Conversation[] }[] = [
    { label: t('chat.datePinned', '置顶'), items: pinned },
    { label: t('chat.dateToday'), items: [] },
    { label: t('chat.dateYesterday'), items: [] },
    { label: t('chat.dateLast7Days'), items: [] },
    { label: t('chat.dateEarlier'), items: [] },
  ]

  const agentFilter = convAgentFilter.value
  for (const conv of props.conversations) {
    if (agentFilter && String(conv.agentId ?? '') !== agentFilter) continue
    if ((conv.conversationId && conv.conversationId.startsWith('tasks_')) || conv.pinned) {
      pinned.push(conv)
      continue
    }
    const ts = conv.lastActiveTime ? new Date(conv.lastActiveTime).getTime() : 0
    if (ts >= todayStart) groups[1].items.push(conv)
    else if (ts >= yesterdayStart) groups[2].items.push(conv)
    else if (ts >= last7Start) groups[3].items.push(conv)
    else groups[4].items.push(conv)
  }

  return groups.filter(g => g.items.length > 0)
})

// ==================== Multi-select ====================
const selectMode = ref(false)
const selectedConvIds = ref<string[]>([])

function toggleSelectMode() {
  selectMode.value = !selectMode.value
  selectedConvIds.value = []
  closeMenu()
}

function toggleConvSelection(conv: Conversation) {
  const id = conv.conversationId
  const idx = selectedConvIds.value.indexOf(id)
  if (idx >= 0) selectedConvIds.value.splice(idx, 1)
  else selectedConvIds.value.push(id)
}

function onConvClick(conv: Conversation) {
  if (selectMode.value) toggleConvSelection(conv)
  else emit('select', conv)
}

const visibleConvIds = computed(() =>
  groupedConversations.value.flatMap(g => g.items.map(i => i.conversationId)))

const allVisibleSelected = computed(() =>
  visibleConvIds.value.length > 0 &&
  visibleConvIds.value.every(id => selectedConvIds.value.includes(id)))

function toggleSelectAll() {
  selectedConvIds.value = allVisibleSelected.value ? [] : [...visibleConvIds.value]
}

async function batchDeleteSelected() {
  const ids = [...selectedConvIds.value]
  if (ids.length === 0) return
  const ok = await mcConfirm({
    title: t('common.confirm'),
    message: t('chat.batchDeleteConfirm', { count: ids.length }),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await conversationApi.batchDelete(ids)
    emit('deleted', ids)
    selectedConvIds.value = []
    selectMode.value = false
  } catch {
    mcToast.error(t('chat.batchDeleteFailed'))
  }
}

// ==================== Rename ====================
const renamingConvId = ref('')
const renameText = ref('')

function startRename(conv: Conversation) {
  renamingConvId.value = conv.conversationId
  renameText.value = conv.title || ''
  // Only one rename input exists at a time (v-if), so a query is reliable —
  // a template ref inside v-for resolves to an array and would not focus.
  nextTick(() => {
    const el = document.querySelector<HTMLInputElement>('.conv-title-input')
    el?.focus()
    el?.select()
  })
}

async function confirmRename(conv: Conversation) {
  const newTitle = renameText.value.trim()
  renamingConvId.value = ''
  if (!newTitle || newTitle === conv.title) return
  conv.title = newTitle
  try {
    await conversationApi.rename(conv.conversationId, newTitle)
  } catch {
    // Revert by asking the parent to reload the authoritative list.
    emit('refresh')
  }
}

function cancelRename() {
  renamingConvId.value = ''
}

// ==================== Pin ====================
async function togglePin(conv: Conversation) {
  const next = !conv.pinned
  conv.pinned = next ? 1 : 0
  try {
    await conversationApi.setPinned(conv.conversationId, next)
    emit('refresh')
  } catch {
    conv.pinned = next ? 0 : 1
    mcToast.error(t('chat.pinFailed'))
  }
}

// ==================== Delete ====================
async function confirmDelete(conv: Conversation) {
  const ok = await mcConfirm({
    title: t('common.confirm'),
    message: t('chat.deleteConfirm') || 'Delete this conversation?',
    tone: 'danger',
  })
  if (!ok) return
  try {
    await conversationApi.delete(conv.conversationId)
    emit('deleted', [conv.conversationId])
  } catch {
    mcToast.error(t('chat.deleteConversationFailed'))
  }
}

// ==================== Unread / formatting ====================
// Written by ChatConsole's markConversationViewed when a conversation is opened.
const VIEWED_KEY_PREFIX = 'mc-conv-viewed:'

function hasUnread(conv: Conversation): boolean {
  // Only the unified tasks_<wsId> conversation gets the unread treatment.
  if (!conv.conversationId || !conv.conversationId.startsWith('tasks_')) return false
  if (!conv.lastActiveTime) return false
  const lastActive = new Date(conv.lastActiveTime).getTime()
  if (!Number.isFinite(lastActive)) return false
  let viewed = 0
  try {
    viewed = Number(localStorage.getItem(VIEWED_KEY_PREFIX + conv.conversationId) || '0') // snowflake-precision-ok: stored value is a last-viewed epoch-ms timestamp, not an id
  } catch {
    // Treat as never-viewed when storage is unavailable.
  }
  if (props.currentConversationId === conv.conversationId) return false
  return lastActive > viewed
}

function formatConversationTime(time?: string) {
  if (!time) return t('chat.timeJustNow')
  const date = new Date(time)
  const diff = Date.now() - date.getTime()
  if (diff < 60 * 60 * 1000) return t('chat.timeMinutesAgo', { n: Math.max(1, Math.floor(diff / (60 * 1000))) })
  if (diff < 24 * 60 * 60 * 1000) return t('chat.timeHoursAgo', { n: Math.floor(diff / (60 * 60 * 1000)) })
  return date.toLocaleDateString()
}

// ==================== Context menu ====================
// One shared DropdownMenu instance, anchored to whichever row's kebab was
// clicked. menuConv being non-null is the open state.
const menuConv = ref<Conversation | null>(null)
const menuConvId = computed(() => menuConv.value?.conversationId || '')
const menuAnchor = ref<HTMLElement | null>(null)

const menuItems = computed<DropdownMenuItem[]>(() => [
  { key: 'pin', label: menuConv.value?.pinned ? t('chat.unpin') : t('chat.pin') },
  { key: 'rename', label: t('chat.rename') },
  { divider: true },
  { key: 'delete', label: t('common.delete'), danger: true },
])

function openMenu(conv: Conversation, e: MouseEvent) {
  if (menuConv.value?.conversationId === conv.conversationId) {
    closeMenu()
    return
  }
  menuAnchor.value = e.currentTarget as HTMLElement
  menuConv.value = conv
}

function closeMenu() {
  menuConv.value = null
}

function onMenuSelect(item: DropdownMenuItem) {
  const conv = menuConv.value
  if (!conv) return
  if (item.key === 'pin') togglePin(conv)
  else if (item.key === 'rename') startRename(conv)
  else if (item.key === 'delete') confirmDelete(conv)
}
</script>

<style scoped>
.conversation-panel {
  width: 248px;
  min-width: 248px;
  background: linear-gradient(180deg, var(--mc-panel-top), var(--mc-panel-bottom));
  border-right: 1px solid var(--mc-border-light);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width 0.25s ease, min-width 0.25s ease;
}

.conversation-panel.conv-collapsed {
  width: 54px;
  min-width: 54px;
}

.conversation-panel.conv-collapsed .panel-header {
  justify-content: center;
  padding: 14px 8px 12px;
}

.conversation-panel.conv-collapsed .agent-selector {
  padding: 10px 6px 12px;
}

.conversation-panel.conv-collapsed .conv-item {
  justify-content: center;
  padding: 10px 6px;
}

.conversation-panel.conv-collapsed .conv-icon {
  margin: 0;
}

.conv-collapse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 28px;
  border: none;
  border-bottom: 1px solid var(--mc-border-light);
  background: transparent;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  transition: all 0.15s;
  flex-shrink: 0;
}

.conv-collapse-btn:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-text-primary);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 14px 12px;
  border-bottom: 1px solid var(--mc-border-light);
}

.panel-header-copy {
  min-width: 0;
}

.panel-kicker {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--mc-accent);
  margin-bottom: 4px;
}

.panel-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 0;
  letter-spacing: -0.03em;
}

.panel-header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.panel-icon-btn {
  width: 28px;
  height: 28px;
  border: 1px solid var(--mc-border);
  background: var(--mc-panel-raised);
  border-radius: 10px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--mc-text-secondary);
  transition: all 0.15s;
}

.panel-icon-btn:hover {
  color: var(--mc-text-primary);
  border-color: var(--mc-text-tertiary);
}

.panel-icon-btn.active {
  background: var(--mc-primary);
  border-color: var(--mc-primary);
  color: white;
}

.new-chat-btn {
  width: 28px;
  height: 28px;
  border: 1px solid var(--mc-border);
  background: var(--mc-panel-raised);
  border-radius: 10px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--mc-text-primary);
  transition: all 0.15s;
}

.new-chat-btn:hover {
  background: var(--mc-primary);
  border-color: var(--mc-primary);
  color: white;
}

.agent-selector {
  padding: 10px 12px 12px;
  border-bottom: 1px solid var(--mc-border-light);
  position: relative;
}

/* Agent filter dropdown above the conversation list. */
.conv-filter {
  padding: 8px 12px 0;
}

.conv-filter-select {
  width: 100%;
  font-size: 12px;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  padding: 6px 8px;
  cursor: pointer;
  outline: none;
}

.conv-filter-select:focus {
  border-color: var(--mc-primary);
}

.conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.conv-group-title {
  padding: 10px 10px 6px;
  font-size: 10px;
  font-weight: 700;
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.12em;
}

.conv-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 11px;
  border-radius: 14px;
  cursor: pointer;
  transition: all 0.15s;
}

.conv-item:hover {
  background: var(--mc-bg-sunken);
  transform: translateY(-1px);
}

.conv-item.active {
  background: var(--mc-primary-bg);
}

.conv-icon {
  color: var(--mc-text-tertiary);
  flex-shrink: 0;
  position: relative;
}

.conv-item.active .conv-icon {
  color: var(--mc-primary);
}

/* Running indicator: pulsing dot on the icon corner (visible collapsed too). */
.conv-running-dot {
  position: absolute;
  top: -2px;
  right: -2px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #fbbf24;
  box-shadow: 0 0 4px rgba(251, 191, 36, 0.6), 0 0 0 2px var(--mc-bg-primary, #fff);
  animation: pulse-dot 1.2s infinite;
  pointer-events: none;
}

/* Inline unread accent dot — shown next to title when a conversation has
   activity since the user's last view (currently scoped to tasks_<wsId>). */
.conv-unread-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--mc-primary, #d97757);
  margin-left: 6px;
  flex-shrink: 0;
  vertical-align: middle;
}

/* Sidebar marker for "this conversation has an active goal". Same hue
 * family as the avatar ring; sits at 60% opacity so it whispers rather
 * than competes with unread / running indicators on the same row. */
.conv-goal-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--mc-primary, #d97757);
  opacity: 0.7;
  margin-left: 6px;
  flex-shrink: 0;
  vertical-align: middle;
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--mc-primary, #d97757) 18%, transparent);
}

.conv-item.is-running {
  background: color-mix(in srgb, #fbbf24 8%, transparent);
}

.conv-item.is-running:hover {
  background: color-mix(in srgb, #fbbf24 14%, var(--mc-bg-sunken));
}

.conv-item.is-running.active {
  background: var(--mc-primary-bg);
}

/* Expanded state: small "generating..." badge to the right of the title. */
.conv-running-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 500;
  color: #b45309;
  background: rgba(251, 191, 36, 0.15);
  border: 1px solid rgba(251, 191, 36, 0.3);
  padding: 1px 6px 1px 5px;
  border-radius: 10px;
  line-height: 1.3;
  white-space: nowrap;
}

.conv-running-badge-pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #f59e0b;
  animation: pulse-dot 1.2s infinite;
}

.conv-info {
  flex: 1;
  overflow: hidden;
}

.conv-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-primary);
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

/* The title text itself carries the ellipsis; overflow:hidden on the flex
   parent would otherwise stop text-overflow from working. */
.conv-title > span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 1 1 auto;
}

.conv-item.active .conv-title {
  color: var(--mc-primary);
}

.conv-meta {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  margin-top: 1px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.conv-dot {
  color: var(--mc-text-tertiary);
}

.conv-title-input {
  width: 100%;
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-primary);
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-primary);
  border-radius: 6px;
  padding: 2px 6px;
  outline: none;
  box-shadow: 0 0 0 2px rgba(217, 119, 87, 0.15);
}

/* The kebab overlays the right edge of the row so it reserves no layout
   width — the conversation title keeps its full space. A left-fading
   gradient masks the meta text behind it when the row is hovered. */
.conv-kebab-wrap {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  padding-left: 18px;
  border-radius: 12px;
  background: linear-gradient(to right, transparent, var(--mc-bg-sunken) 42%);
  opacity: 0;
  transition: opacity 0.15s;
}

.conv-item:hover .conv-kebab-wrap,
.conv-item.active .conv-kebab-wrap,
.conv-item.menu-open .conv-kebab-wrap {
  opacity: 1;
}

.conv-item.active .conv-kebab-wrap {
  background: linear-gradient(to right, transparent, var(--mc-primary-bg) 42%);
}

.conv-kebab {
  width: 24px;
  height: 24px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  padding: 0;
  flex-shrink: 0;
  transition: background 0.15s, color 0.15s;
}

.conv-kebab:hover,
.conv-kebab.open {
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
}

.conv-checkbox {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  cursor: pointer;
}

.conv-checkbox input {
  width: 15px;
  height: 15px;
  cursor: pointer;
  accent-color: var(--mc-primary);
}

.conv-item.is-selected {
  background: var(--mc-primary-bg);
}

.conv-select-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid var(--mc-border-light);
}

.conv-select-all {
  font-size: 12px;
  color: var(--mc-text-secondary);
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 6px;
  white-space: nowrap;
}

.conv-select-all:hover {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}

.conv-select-count {
  flex: 1;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  text-align: center;
  white-space: nowrap;
}

.conv-batch-delete {
  font-size: 12px;
  font-weight: 600;
  color: white;
  background: var(--mc-danger);
  border: none;
  border-radius: 8px;
  padding: 6px 12px;
  cursor: pointer;
  white-space: nowrap;
  transition: opacity 0.15s;
}

.conv-batch-delete:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.conv-batch-delete:not(:disabled):hover {
  opacity: 0.88;
}

.empty-convs {
  text-align: center;
  padding: 32px 16px;
  color: var(--mc-text-tertiary);
  font-size: 13px;
  line-height: 1.8;
}

@keyframes pulse-dot {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* ===== Mobile ===== */
@media (max-width: 768px) {
  .conversation-panel {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 100;
    width: 272px;
    min-width: 272px;
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: none;
  }

  .conversation-panel.mobile-open {
    transform: translateX(0);
    box-shadow: 4px 0 16px rgba(0, 0, 0, 0.1);
  }
}
</style>
