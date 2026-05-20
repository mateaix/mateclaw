<template>
  <div class="page-container">
    <div class="page-header">
      <div class="page-header-title">
        <button class="back-btn" :title="t('sessions.back')" @click="goBack">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="15 18 9 12 15 6"/>
          </svg>
        </button>
        <div class="title-block">
          <h1 class="page-title">{{ t('sessions.title') }}</h1>
          <p class="page-desc">{{ t('sessions.desc') }}</p>
        </div>
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
      <div class="sessions-table-scroll">
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
          <tr v-for="session in sessions" :key="session.conversationId" class="session-row">
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
          <tr v-if="sessions.length === 0">
            <td colspan="8" class="empty-row">
              <div class="empty-state">
                <div class="empty-icon-ring">
                  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
                  </svg>
                </div>
                <h3 class="empty-heading">{{ t('sessions.emptyHeading') }}</h3>
                <p class="empty-desc">{{ t('sessions.emptyDesc') }}</p>
                <button class="empty-cta" @click="router.push('/chat')">
                  {{ t('sessions.emptyCta') }}
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <line x1="5" y1="12" x2="19" y2="12"/>
                    <polyline points="12 5 19 12 12 19"/>
                  </svg>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
    </div>

    <div v-if="total > 0" class="sessions-pager-row">
      <McPagination
        v-model:page="currentPage"
        v-model:size="pageSize"
        :total="total"
        :sizes="[10, 20, 50, 100]"
        @change="onPagerChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import { conversationApi, modelApi } from '@/api/index'
import { channelIconUrl, sourceLabel } from '@/utils/channelSource'
import type { Conversation, ProviderInfo } from '@/types/index'
import SkillIcon from '@/components/common/SkillIcon.vue'
import ModelSelector from '@/components/chat/ModelSelector.vue'
import McPagination from '@/components/common/McPagination.vue'

const router = useRouter()
const { t } = useI18n()
const sessions = ref<Conversation[]>([])
const searchText = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

// Per-conversation model selection state (closes #183). Loaded once on mount,
// not per-row, because providers don't change during a session-list view.
const providers = ref<ProviderInfo[]>([])
const modelEditingId = ref<string | null>(null)
const modelSavingId = ref<string | null>(null)

onMounted(async () => {
  await loadSessions()
  await loadProviders()
})

// Debounce keyword changes so we don't fire a request on every keystroke.
// 300ms matches the existing search-on-type rhythm used elsewhere in the UI.
let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(searchText, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    currentPage.value = 1
    loadSessions()
  }, 300)
})

function onPagerChange() {
  loadSessions()
}

function goBack() {
  // Prefer history when we arrived from ChatConsole's overflow menu, fall
  // back to /chat for direct deep links.
  if (window.history.length > 1) router.back()
  else router.push('/chat')
}

async function loadSessions() {
  try {
    const res: any = await conversationApi.page({
      page: currentPage.value,
      size: pageSize.value,
      keyword: searchText.value || undefined,
    })
    const body = res.data || {}
    sessions.value = body.records || []
    total.value = Number(body.total) || 0
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
/* ==========================================================================
   Sessions admin — three-depth design system
   --------------------------------------------------------------------------
   Layer 1 (canvas):  page-container — atmospheric gradient anchored on the
                      page edges so the eye has somewhere to breathe.
   Layer 2 (surface): sessions-table-wrap — the floating "data island",
                      lifted with multi-step soft shadow.
   Layer 3 (accent):  primary-tinted CTAs, the title rail, focus glows.
   All colors flow through theme tokens (--mc-bg / --mc-primary-bg /
   --mc-shadow-soft …) so light/dark switch is automatic.
   ========================================================================== */

.page-container {
  position: relative;
  height: 100%;
  overflow-y: auto;
  padding: 32px 32px 40px;
  background:
    radial-gradient(ellipse 90% 60% at 0% 0%, var(--mc-primary-bg), transparent 55%),
    radial-gradient(ellipse 70% 50% at 100% 100%, var(--mc-accent-soft), transparent 60%),
    var(--mc-bg);
}

/* ============================== Header ============================== */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}
.page-header-title { display: flex; align-items: flex-start; gap: 14px; }

.back-btn {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  border-radius: 50%;
  color: var(--mc-text-secondary);
  cursor: pointer;
  transition: transform 0.15s ease, background 0.15s, border-color 0.15s, color 0.15s;
  margin-top: 2px;
  padding: 0;
  box-shadow: var(--mc-shadow-soft);
}
.back-btn:hover {
  background: var(--mc-bg-elevated);
  border-color: var(--mc-primary);
  color: var(--mc-primary);
  transform: translateX(-2px);
}

/* Vertical accent rail anchors the title block to the page — gives the
   eye a physical "start here" point instead of letting it float. */
.title-block {
  position: relative;
  padding-left: 14px;
}
.title-block::before {
  content: '';
  position: absolute;
  left: 0;
  top: 4px;
  bottom: 6px;
  width: 3px;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--mc-primary), var(--mc-accent));
}
.page-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 0 0 4px;
  letter-spacing: -0.01em;
}
.page-desc { font-size: 13px; color: var(--mc-text-secondary); margin: 0; }

/* ============================== Search ============================== */
.header-actions { display: flex; gap: 10px; align-items: center; }
.search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  padding: 9px 14px;
  box-shadow: var(--mc-shadow-soft);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.search-box:focus-within {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px var(--mc-primary-bg), var(--mc-shadow-soft);
}
.search-box svg { color: var(--mc-text-tertiary); flex-shrink: 0; }
.search-input {
  border: none;
  outline: none;
  font-size: 14px;
  color: var(--mc-text-primary);
  background: transparent;
  width: 220px;
}

/* ============================== Table card (Surface) ============================== */
.sessions-table-wrap {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 16px;
  overflow: hidden;
  box-shadow: var(--mc-shadow-medium);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
}
/* Inner scroller takes the horizontal overflow so the outer wrap can keep
   `overflow: hidden` — that's what makes the rounded corners actually clip
   the th background instead of letting it bleed into the top corners. */
.sessions-table-scroll { overflow-x: auto; }
.sessions-table { width: 100%; min-width: 920px; border-collapse: collapse; }
/* Default to no vertical-stacking of CJK; specific cells opt back into wrap
   if they truly need to (session title / id is the only multi-line cell). */
.sessions-table th,
.sessions-table td { white-space: nowrap; }
.sessions-table td:first-child { white-space: normal; }
.sessions-table th {
  padding: 14px 18px;
  text-align: left;
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  background: var(--mc-bg-muted);
  border-bottom: 1px solid var(--mc-border);
}
.session-row { border-bottom: 1px solid var(--mc-border-light); transition: background 0.12s; }
.session-row:hover { background: var(--mc-bg-muted); }
.session-row:last-child { border-bottom: none; }
.sessions-table td { padding: 16px 18px; font-size: 14px; color: var(--mc-text-primary); }
.session-title { font-weight: 600; color: var(--mc-text-primary); margin-bottom: 3px; letter-spacing: -0.005em; }
.session-id { font-size: 11px; color: var(--mc-text-tertiary); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.source-cell { display: flex; align-items: center; gap: 8px; }
.source-icon { display: flex; align-items: center; flex-shrink: 0; }
.source-name { font-size: 12px; color: var(--mc-text-secondary); white-space: nowrap; }
.agent-cell { display: flex; align-items: center; gap: 8px; }
.agent-icon-sm { font-size: 16px; }
.msg-count {
  background: var(--mc-bg-muted);
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary);
}

/* Model chip — collapsed state for the per-conversation model selector (#183) */
.model-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  font-size: 12px;
  color: var(--mc-text-secondary);
  cursor: pointer;
  max-width: 220px;
  transition: all 0.15s;
}
.model-chip:hover {
  background: var(--mc-bg-elevated);
  border-color: var(--mc-primary);
  color: var(--mc-text-primary);
}
.model-chip__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.status-badge { padding: 4px 11px; border-radius: 20px; font-size: 11px; font-weight: 600; letter-spacing: 0.02em; }
.status-active { background: var(--mc-primary-bg); color: var(--mc-primary); }
.status-closed { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.time-cell { color: var(--mc-text-tertiary); font-size: 13px; }
.row-actions { display: flex; gap: 6px; }
.row-btn {
  width: 30px;
  height: 30px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--mc-text-secondary);
  transition: all 0.15s;
}
.row-btn:hover { background: var(--mc-bg-muted); border-color: var(--mc-border); color: var(--mc-primary); }
.row-btn.danger:hover { background: var(--mc-danger-bg, rgba(220, 38, 38, 0.1)); border-color: var(--mc-danger, #dc2626); color: var(--mc-danger, #dc2626); }

/* ============================== Empty state (CTA moment) ============================== */
.empty-row { padding: 64px 24px !important; background: transparent; }
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: var(--mc-text-secondary);
  max-width: 380px;
  margin: 0 auto;
  text-align: center;
}
.empty-icon-ring {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
  margin-bottom: 4px;
  box-shadow: 0 0 0 8px var(--mc-bg-muted);
}
.empty-heading {
  font-size: 16px;
  font-weight: 600;
  color: var(--mc-text-primary);
  margin: 0;
  letter-spacing: -0.005em;
}
.empty-desc { font-size: 13px; line-height: 1.6; color: var(--mc-text-tertiary); margin: 0; }
.empty-cta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 18px;
  background: var(--mc-primary);
  color: var(--mc-text-inverse);
  border: none;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 8px;
  box-shadow: var(--mc-shadow-soft);
  transition: transform 0.15s, box-shadow 0.15s, background 0.15s;
}
.empty-cta:hover {
  background: var(--mc-primary-hover);
  transform: translateY(-1px);
  box-shadow: var(--mc-shadow-medium);
}
.empty-cta:active { transform: translateY(0); }

/* ============================== Pagination ============================== */
.sessions-pager-row { margin-top: 20px; display: flex; justify-content: flex-end; }
</style>
