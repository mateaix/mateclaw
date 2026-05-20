<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ t('sessions.title') }}</h1>
        <p class="page-desc">{{ t('sessions.desc') }}</p>
      </div>
      <div class="header-actions">
        <div class="search-box">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <input v-model="searchText" :placeholder="t('sessions.search')" class="search-input" />
        </div>
      </div>
    </div>

    <!-- 会话列表 -->
    <div class="sessions-table-wrap">
      <table class="sessions-table">
        <thead>
          <tr>
            <th>{{ t('sessions.columns.session') }}</th>
            <th>{{ t('sessions.columns.source') }}</th>
            <th>{{ t('sessions.columns.agent') }}</th>
            <th>{{ t('sessions.columns.model') }}</th>
            <th>{{ t('sessions.columns.messages') }}</th>
            <th>{{ t('sessions.columns.status') }}</th>
            <th>{{ t('sessions.columns.lastActive') }}</th>
            <th>{{ t('sessions.columns.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="session in filteredSessions" :key="session.conversationId" class="session-row">
            <td>
              <div class="session-info">
                <div class="session-title">{{ session.title }}</div>
                <div class="session-id">{{ session.conversationId }}</div>
              </div>
            </td>
            <td>
              <div class="source-cell" :title="sourceLabel(session.source)">
                <img class="source-icon" :src="channelIconUrl(session.source)" width="16" height="16" :alt="sourceLabel(session.source)" />
                <span class="source-name">{{ sourceLabel(session.source) }}</span>
              </div>
            </td>
            <td>
              <div class="agent-cell">
                <span class="agent-icon-sm"><SkillIcon :value="session.agentIcon" :size="16" :fallback="'🤖'" /></span>
                <span>{{ session.agentName || '-' }}</span>
              </div>
            </td>
            <td>
              <!-- Closes #183: per-conversation model selector available for
                   IM channels too, not just Web. The selector mounts only
                   when this row is expanded so the table stays light. -->
              <ModelSelector
                v-if="modelEditingId === session.conversationId"
                :providers="providers"
                :active-value="modelValue(session)"
                :active-label="modelLabel(session)"
                :saving="modelSavingId === session.conversationId"
                :show-all-states="true"
                @select="(val) => onModelSelect(session, val)"
                @navigate-fix="onProviderFix"
              />
              <button v-else class="model-chip" :title="t('sessions.switchModel')"
                      @click="openModelEditor(session)">
                <span class="model-chip__name">{{ modelLabel(session) || t('sessions.model.default') }}</span>
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <polyline points="6 9 12 15 18 9"/>
                </svg>
              </button>
            </td>
            <td>
              <span class="msg-count">{{ session.messageCount }}</span>
            </td>
            <td>
              <span class="status-badge" :class="session.status === 'active' ? 'status-active' : 'status-closed'">
                {{ session.status === 'active' ? t('sessions.status.active') : t('sessions.status.closed') }}
              </span>
            </td>
            <td class="time-cell">{{ formatTime(session.updateTime) }}</td>
            <td>
              <div class="row-actions">
                <button class="row-btn" @click="viewSession(session)" :title="t('common.view')">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                    <circle cx="12" cy="12" r="3"/>
                  </svg>
                </button>
                <button class="row-btn danger" @click="deleteSession(session.conversationId)" :title="t('common.delete')">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                  </svg>
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="filteredSessions.length === 0">
            <td colspan="8" class="empty-row">
              <div class="empty-state">
                <span class="empty-icon">💬</span>
                <p>{{ t('sessions.empty') }}</p>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import { conversationApi, modelApi } from '@/api/index'
import { channelIconUrl, sourceLabel } from '@/utils/channelSource'
import type { Conversation, ProviderInfo } from '@/types/index'
import SkillIcon from '@/components/common/SkillIcon.vue'
import ModelSelector from '@/components/chat/ModelSelector.vue'

const router = useRouter()
const { t } = useI18n()
const sessions = ref<Conversation[]>([])
const searchText = ref('')

// Per-conversation model selection state (closes #183). Loaded once on mount,
// not per-row, because providers don't change during a session-list view.
const providers = ref<ProviderInfo[]>([])
const modelEditingId = ref<string | null>(null)
const modelSavingId = ref<string | null>(null)

const filteredSessions = computed(() => {
  if (!searchText.value) return sessions.value
  const q = searchText.value.toLowerCase()
  return sessions.value.filter(s =>
    s.title?.toLowerCase().includes(q) ||
    s.conversationId?.toLowerCase().includes(q)
  )
})

onMounted(async () => {
  await loadSessions()
  await loadProviders()
})

async function loadSessions() {
  try {
    const res: any = await conversationApi.list()
    sessions.value = res.data || []
  } catch (e: any) { mcToast.error(t('sessions.loadFailed')) }
}

async function loadProviders() {
  try {
    const res: any = await modelApi.listEnabled()
    providers.value = res.data || []
  } catch (e: any) {
    // Non-fatal: model selector just falls back to the "no providers"
    // empty state; session list still works.
    providers.value = []
  }
}

function viewSession(session: Conversation) {
  router.push({ path: '/chat', query: { agentId: String(session.agentId), conversationId: session.conversationId } })
}

async function deleteSession(conversationId: string) {
  const ok = await mcConfirm({
    title: t('sessions.deleteTitle'),
    message: t('sessions.deleteConfirm'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await conversationApi.delete(conversationId)
    await loadSessions()
  } catch (e: any) { mcToast.error(t('sessions.deleteFailed')) }
}

// ==================== Model selection (#183) ====================

/** Composite key consumed by ModelSelector: "{providerId}::{modelName}". */
function modelValue(session: Conversation): string {
  const p = session.modelProvider
  const m = session.modelName
  return p && m ? `${p}::${m}` : ''
}

/**
 * Human-friendly label shown in the chip and the selector trigger.
 * Pre-resolves the provider name from the loaded providers list; falls
 * back to the raw id when providers haven't loaded yet (rare race) or
 * the provider was since removed.
 */
function modelLabel(session: Conversation): string {
  const p = session.modelProvider
  const m = session.modelName
  if (!p || !m) return ''
  const provider = providers.value.find(x => x.id === p)
  const providerName = provider?.name || p
  return `${providerName} / ${m}`
}

function openModelEditor(session: Conversation) {
  modelEditingId.value = session.conversationId
}

async function onModelSelect(session: Conversation, value: string) {
  // ModelSelector emits the same "{providerId}::{modelName}" composite key
  // we hand it back in :active-value. Split + persist.
  const sep = value.indexOf('::')
  if (sep <= 0) {
    modelEditingId.value = null
    return
  }
  const providerId = value.slice(0, sep)
  const modelName = value.slice(sep + 2)
  if (!providerId || !modelName) {
    modelEditingId.value = null
    return
  }
  // Skip the round-trip when nothing changed (user re-picked current model).
  if (session.modelProvider === providerId && session.modelName === modelName) {
    modelEditingId.value = null
    return
  }
  modelSavingId.value = session.conversationId
  try {
    await conversationApi.setModel(session.conversationId, providerId, modelName)
    // Local mutation: avoid a full reload — the table is sorted by lastActive
    // and a re-list would jump the row out from under the user's cursor.
    session.modelProvider = providerId
    session.modelName = modelName
    mcToast.success(t('sessions.modelSwitched'))
  } catch (e: any) {
    mcToast.error(e?.response?.data?.message || t('sessions.modelSwitchFailed'))
  } finally {
    modelSavingId.value = null
    modelEditingId.value = null
  }
}

function onProviderFix(provider: { id: string }) {
  // Match ChatConsole's behaviour: jump to the model-settings page with the
  // provider deep-linked so the user can fix credentials / enable it, then
  // come back and switch model.
  modelEditingId.value = null
  router.push({ path: '/settings/models', query: { providerId: provider.id } })
}


function formatTime(time?: string) {
  if (!time) return '-'
  const d = new Date(time)
  const now = new Date()
  const diff = now.getTime() - d.getTime()
  if (diff < 60000) return t('sessions.time.justNow')
  if (diff < 3600000) return t('sessions.time.minutesAgo', { n: Math.floor(diff / 60000) })
  if (diff < 86400000) return t('sessions.time.hoursAgo', { n: Math.floor(diff / 3600000) })
  return d.toLocaleDateString()
}
</script>

<style scoped>
.page-container { height: 100%; overflow-y: auto; padding: 24px; background: var(--mc-bg); }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 24px; }
.page-title { font-size: 20px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 4px; }
.page-desc { font-size: 14px; color: var(--mc-text-secondary); margin: 0; }
.header-actions { display: flex; gap: 10px; }
.search-box { display: flex; align-items: center; gap: 8px; background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 8px; padding: 8px 12px; }
.search-box svg { color: var(--mc-text-tertiary); flex-shrink: 0; }
.search-input { border: none; outline: none; font-size: 14px; color: var(--mc-text-primary); background: transparent; width: 200px; }
.sessions-table-wrap { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 12px; overflow: hidden; }
.sessions-table { width: 100%; border-collapse: collapse; }
.sessions-table th { padding: 12px 16px; text-align: left; font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); text-transform: uppercase; letter-spacing: 0.05em; background: var(--mc-bg-sunken); border-bottom: 1px solid var(--mc-border); }
.session-row { border-bottom: 1px solid var(--mc-border-light); transition: background 0.1s; }
.session-row:hover { background: var(--mc-bg-sunken); }
.session-row:last-child { border-bottom: none; }
.sessions-table td { padding: 14px 16px; font-size: 14px; color: var(--mc-text-primary); }
.session-info {}
.session-title { font-weight: 500; color: var(--mc-text-primary); margin-bottom: 2px; }
.session-id { font-size: 11px; color: var(--mc-text-tertiary); font-family: monospace; }
.source-cell { display: flex; align-items: center; gap: 6px; }
.source-icon { display: flex; align-items: center; flex-shrink: 0; }
.source-name { font-size: 12px; color: var(--mc-text-secondary); white-space: nowrap; }
.agent-cell { display: flex; align-items: center; gap: 6px; }
.agent-icon-sm { font-size: 16px; }
.msg-count { background: var(--mc-bg-sunken); padding: 2px 8px; border-radius: 10px; font-size: 12px; font-weight: 500; }
/* Model chip: collapsed state for the per-conversation model selector
   (issue #183). Click opens the inline ModelSelector dropdown. */
.model-chip { display: inline-flex; align-items: center; gap: 4px; padding: 3px 8px; background: var(--mc-bg-sunken); border: 1px solid var(--mc-border); border-radius: 6px; font-size: 12px; color: var(--mc-text-secondary); cursor: pointer; max-width: 220px; transition: all 0.15s; }
.model-chip:hover { background: var(--mc-bg-elevated); border-color: var(--mc-primary); color: var(--mc-text-primary); }
.model-chip__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-badge { padding: 3px 10px; border-radius: 20px; font-size: 12px; font-weight: 500; }
.status-active { background: var(--mc-primary-bg); color: var(--mc-primary); }
.status-closed { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.time-cell { color: var(--mc-text-tertiary); font-size: 13px; }
.row-actions { display: flex; gap: 4px; }
.row-btn { width: 28px; height: 28px; border: 1px solid var(--mc-border); background: var(--mc-bg-elevated); border-radius: 6px; cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--mc-text-secondary); transition: all 0.15s; }
.row-btn:hover { background: var(--mc-bg-sunken); }
.row-btn.danger:hover { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }
.empty-row { padding: 40px !important; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 8px; color: var(--mc-text-tertiary); }
.empty-icon { font-size: 32px; }
.empty-state p { font-size: 14px; margin: 0; }
</style>
