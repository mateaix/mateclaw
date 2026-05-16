<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner channels-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">Connect</div>
            <h1 class="mc-page-title">{{ t('channels.title') }}</h1>
            <p class="mc-page-desc">{{ t('channels.desc') }}</p>
          </div>
          <button v-if="channels.length > 0" class="btn-primary" @click="openCreateModal">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            {{ t('channels.newChannel') }}
          </button>
        </div>

        <!-- Loading skeleton (initial fetch) -->
        <div v-if="isInitialLoading" class="channel-grid">
          <div v-for="i in 3" :key="i" class="channel-card mc-surface-card channel-card-skeleton">
            <el-skeleton :rows="3" animated />
          </div>
        </div>

        <!-- 0-channel hero empty state.
             Replaces the 11-card "catalog soup" with a single CTA. The
             type catalog moves into ChannelTypePicker (one click away),
             so the default view is silent until the user has actually
             configured something. -->
        <div v-else-if="channels.length === 0" class="empty-hero mc-surface-card">
          <div class="empty-hero-icon">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/>
            </svg>
          </div>
          <h2 class="empty-hero-title">{{ t('channels.empty.title') }}</h2>
          <p class="empty-hero-desc">{{ t('channels.empty.desc') }}</p>
          <button class="btn-primary empty-hero-cta" @click="openCreateModal">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            {{ t('channels.empty.cta') }}
          </button>
        </div>

        <!-- Configured-channels view: stats + cards -->
        <template v-else>
          <!-- Stats bar — small one-liner so "is anything broken?" is
               answerable at a glance without scanning every card. -->
          <div class="stats-bar">
            <span class="stat" :class="{ ok: stats.active > 0 }">
              <span class="stat-dot conn-connected"></span>
              {{ t('channels.stats.active', { n: stats.active }) }}
            </span>
            <span v-if="stats.reconnecting > 0" class="stat warn">
              <span class="stat-dot conn-reconnecting"></span>
              {{ t('channels.stats.reconnecting', { n: stats.reconnecting }) }}
            </span>
            <span v-if="stats.errors > 0" class="stat danger">
              <span class="stat-dot conn-error"></span>
              {{ t('channels.stats.errors', { n: stats.errors }) }}
            </span>
            <span v-if="stats.disabled > 0" class="stat muted">
              {{ t('channels.stats.disabled', { n: stats.disabled }) }}
            </span>
          </div>

          <div class="channel-grid">
            <div v-for="channel in channels" :key="channel.id" class="channel-card mc-surface-card">
              <div class="channel-header">
                <div class="channel-icon-wrap">
                  <img class="channel-icon-img" :src="getChannelIconPath(channel.channelType)" :alt="channel.channelType" />
                </div>
                <div class="channel-meta">
                  <h3 class="channel-name">{{ channel.name }}</h3>
                  <span class="channel-type">{{ channel.channelType }}</span>
                </div>
                <div class="channel-status-group">
                  <div
                    v-if="channel.enabled"
                    class="connection-indicator"
                    :class="getConnectionClass(channel)"
                    :title="getConnectionTooltip(channel)"
                  >
                    {{ getConnectionIcon(channel) }} {{ getConnectionLabel(channel) }}
                  </div>
                  <div v-else class="channel-status status-off">
                    {{ t('channels.status.inactive') }}
                  </div>
                </div>
              </div>
              <!-- Identity-driven description: "Connected as @MyBot in TeamX".
                   Falls back to the type-level description for legacy rows
                   that have not been re-verified yet. -->
              <p class="channel-desc">{{ getChannelDescription(channel) }}</p>
              <div class="channel-footer">
                <button class="card-btn" @click="openEditModal(channel)">
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                  </svg>
                  {{ t('channels.configure') }}
                </button>
                <button class="card-btn" @click="toggleChannel(channel)">
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <circle cx="12" cy="12" r="10"/>
                    <line v-if="channel.enabled" x1="8" y1="12" x2="16" y2="12"/>
                    <polyline v-else points="10 8 16 12 10 16"/>
                  </svg>
                  {{ channel.enabled ? t('channels.disable') : t('channels.enable') }}
                </button>
                <button class="card-btn danger" @click="deleteChannel(channel.id)">
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                  </svg>
                  {{ t('common.delete') }}
                </button>
              </div>
            </div>

            <!-- Compact "+ add another" tail card. iOS Mail pattern: it
                 stays visible only when the user already has channels, so
                 it's a familiar repeat-action shortcut, not a hero CTA. -->
            <button class="add-card-compact mc-surface-card" @click="openCreateModal">
              <div class="add-icon-compact">+</div>
              <span class="add-label-compact">{{ t('channels.addChannel') }}</span>
            </button>
          </div>
        </template>
      </div>
    </div>

    <!-- Edit modal: async-loaded the first time it's opened, so the route
         chunk doesn't carry the modal's ~30KB form/auth UI on initial load.
         Used for editing existing channels and for OAuth-style channel types
         (weixin/wecom/dingtalk/feishu) whose existing scan flows are the
         verification — those continue to live in the legacy modal until
         migrated per RFC-084. -->
    <ChannelEditModal
      v-if="showModal"
      v-model="showModal"
      :editing-channel="editingChannel"
      :agents="agents"
      :default-type="modalDefaults.type"
      :default-name="modalDefaults.name"
      @save="handleSave"
      @add-new-weixin="handleAddNewWeixin"
    />

    <!-- RFC-084 onboarding wizard: paste-token channel types use the new
         3-step Configure → Verify → Ready flow. Type picker decides which
         is shown when the user starts a new channel. -->
    <ChannelTypePicker
      v-if="showTypePicker"
      v-model="showTypePicker"
      @pick="onTypePicked"
    />
    <ChannelOnboardingWizard
      v-if="showWizard && wizardType"
      v-model="showWizard"
      :channel-type="wizardType"
      :agents="agents"
      @created="onWizardCreated"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, defineAsyncComponent, onMounted, onUnmounted, onActivated, onDeactivated } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import { channelApi, agentApi } from '@/api'
import type { Channel, Agent } from '@/types'

// Async-loaded modal: separate chunk, only fetched when the user first clicks
// "create" or "edit". The /channels initial load is a list page only.
const ChannelEditModal = defineAsyncComponent(() => import('@/components/channels/ChannelEditModal.vue'))
const ChannelTypePicker = defineAsyncComponent(() => import('@/components/channels/ChannelTypePicker.vue'))
const ChannelOnboardingWizard = defineAsyncComponent(() => import('@/components/channels/ChannelOnboardingWizard.vue'))

const { t } = useI18n()

const channels = ref<Channel[]>([])
const agents = ref<Agent[]>([])
const showModal = ref(false)
const editingChannel = ref<Channel | null>(null)
const modalDefaults = ref<{ type?: string; name?: string }>({})

// RFC-084 onboarding wizard state. The picker is the entry for "+ New
// Channel"; based on the picked type, we either open the new wizard
// (paste-token types) or fall back to the legacy modal (OAuth/QR types
// whose existing flows already cover Configure + Verify in one step).
const showTypePicker = ref(false)
const showWizard = ref(false)
const wizardType = ref<string>('')

// Channel types that the new 3-step wizard handles end-to-end. All four
// OAuth-style flows (wecom popup-SDK, weixin/dingtalk/feishu QR scans) now
// run inside the wizard — see OAUTH_STYLE_TYPES in
// ChannelOnboardingWizard.vue. The legacy modal stays only for editing
// existing channels (where 3-step would feel like ceremony).
const WIZARD_TYPES = new Set([
  'telegram', 'discord', 'slack', 'qq',
  'web', 'webchat', 'webhook',
  'wecom', 'weixin', 'dingtalk', 'feishu',
])

const channelStatusMap = ref<Record<string | number, {
  connectionState: string
  lastError: string | null
  reconnectAttempts: number
  identity: Record<string, any>
}>>({})

let statusPollTimer: ReturnType<typeof setInterval> | null = null
const isInitialLoading = ref(true)
// Tracks whether the page is currently the active route. Guards against the
// race where the user navigates away while Promise.all is still in-flight: by
// the time onMounted's tail runs, onDeactivated may have already fired —
// startStatusPolling must not start a leaked timer in the background.
let isActive = true

// ==================== Lifecycle ====================

async function loadAgents() {
  const res: any = await agentApi.list()
  agents.value = res.data || []
}

function startStatusPolling() {
  if (!isActive) return
  if (statusPollTimer) return
  statusPollTimer = setInterval(loadStatus, 10000)
}

function stopStatusPolling() {
  if (statusPollTimer) {
    clearInterval(statusPollTimer)
    statusPollTimer = null
  }
}

onMounted(async () => {
  // Parallel fan-out (was three serial awaits, ~3x latency before).
  try {
    await Promise.all([loadChannels(), loadStatus(), loadAgents()])
  } finally {
    isInitialLoading.value = false
  }
  startStatusPolling()
})

// keepAlive=true on the route: pause polling when the user navigates away,
// resume on return. onMounted/onUnmounted only fire on first mount and final
// unmount; everything else is onActivated/onDeactivated.
onActivated(() => {
  isActive = true
  if (isInitialLoading.value) return
  loadStatus()
  startStatusPolling()
})

onDeactivated(() => {
  isActive = false
  stopStatusPolling()
})

onUnmounted(() => {
  isActive = false
  stopStatusPolling()
})

// ==================== Data loading ====================

async function loadChannels() {
  try {
    const res: any = await channelApi.list()
    channels.value = (res.data || []).map((c: any) => ({ ...c }))
  } catch (e: any) {
    mcToast.error(t('channels.messages.loadFailed') + ': ' + (e?.message || ''))
    channels.value = []
  }
}

async function loadStatus() {
  try {
    // Prefer the new typed health endpoint — it surfaces OUT_OF_SERVICE
    // (enabled in DB but adapter not active, e.g. start failed silently)
    // which the legacy /status endpoint conflated with DISCONNECTED. The
    // older shape is still returned by the fallback below in case the
    // backend is mid-rollout.
    const res: any = await channelApi.healthAll()
    const list: any[] = res.data || []
    const map: Record<number, any> = {}
    for (const h of list) {
      const status: string = h.status || 'UNKNOWN'
      // Translate the typed health status onto the existing connection
      // state vocabulary the UI helpers were built against, so the rest
      // of the page (icons, tooltips, css classes) keeps working unchanged.
      const connectionState =
        status === 'UP' ? 'CONNECTED'
        : status === 'RECONNECTING' ? 'RECONNECTING'
        : status === 'DOWN' ? 'ERROR'
        : status === 'OUT_OF_SERVICE' ? 'OUT_OF_SERVICE'
        : 'DISCONNECTED'
      map[h.channelId] = {
        connectionState,
        lastError: h.detail || null,
        reconnectAttempts: 0,
        identity: h.identity || {},
      }
    }
    channelStatusMap.value = map
  } catch {
    // silent — next poll will retry
  }
}

// ==================== Stats / description ====================

// Top-of-page summary so the "is anything broken?" question is answerable
// without scanning every card. Disabled channels are surfaced too — they're
// "my channels, paused", not noise.
const stats = computed(() => {
  let active = 0, reconnecting = 0, errors = 0, disabled = 0
  for (const ch of channels.value) {
    if (!ch.enabled) { disabled += 1; continue }
    const state = channelStatusMap.value[ch.id]?.connectionState
    if (state === 'CONNECTED') active += 1
    else if (state === 'RECONNECTING') reconnecting += 1
    else if (state === 'ERROR') errors += 1
    // OUT_OF_SERVICE / DISCONNECTED are transient startup states — don't
    // pollute the top bar with them; they show on the card itself.
  }
  return { active, reconnecting, errors, disabled }
})

/**
 * Identity-driven description. Shows what THIS bot is, not what the
 * channel type is. Three-stage fallback so the middle of the card is
 * never visually empty:
 *
 *   1. {@code identity.accountName} (+ optional team) — set by the
 *      adapter once the channel is bound; produces "Connected as @bot
 *      in TeamX". Most accurate / current.
 *   2. {@code channel.description} — user-edited blurb from the modal,
 *      or the seeded type-level description for the default Web row.
 *   3. {@code channels.cardDesc.typeFallback[channelType]} — i18n
 *      static string per channel type (dingtalk / wecom / …) so even
 *      a brand-new user-created row has a sensible one-liner.
 *
 * If all three miss (unknown channelType, no description, no identity)
 * we surface a "click Configure to add a description" hint so the
 * empty space remains explainable.
 */
function getChannelDescription(channel: Channel): string {
  const identity = channelStatusMap.value[channel.id]?.identity || {}
  const accountName = identity.accountName as string | undefined
  const team = identity.team as string | undefined
  if (accountName && team) {
    return t('channels.cardDesc.connectedAsIn', { account: accountName, team })
  }
  if (accountName) {
    return t('channels.cardDesc.connectedAs', { account: accountName })
  }
  if (channel.description && channel.description.trim()) {
    return channel.description
  }
  return getTypeFallbackDescription(channel.channelType)
}

/**
 * i18n type-level fallback for channels with no description and no
 * identity yet. Returns the empty-state hint when the channelType
 * isn't recognised in the i18n table (forward-compat for new types).
 */
function getTypeFallbackDescription(channelType?: string | null): string {
  if (!channelType) return t('channels.cardDesc.empty')
  const key = `channels.cardDesc.typeFallback.${channelType}`
  const translated = t(key)
  // vue-i18n returns the key itself when there's no translation;
  // detect that and fall back to the generic empty-state copy.
  if (!translated || translated === key) {
    return t('channels.cardDesc.empty')
  }
  return translated
}

// ==================== Connection state helpers ====================

function getConnectionState(channel: Channel): string {
  return channelStatusMap.value[channel.id]?.connectionState || 'DISCONNECTED'
}

function getConnectionIcon(channel: Channel): string {
  switch (getConnectionState(channel)) {
    case 'CONNECTED': return '🟢'
    case 'RECONNECTING': return '🟡'
    case 'ERROR': return '🔴'
    case 'OUT_OF_SERVICE': return '🟠'
    default: return '⚪'
  }
}

function getConnectionLabel(channel: Channel): string {
  switch (getConnectionState(channel)) {
    case 'CONNECTED': return t('channels.connection.connected')
    case 'RECONNECTING': return t('channels.connection.reconnecting')
    case 'ERROR': return t('channels.connection.error')
    case 'OUT_OF_SERVICE': return t('channels.connection.outOfService')
    case 'DISCONNECTED': return t('channels.connection.disconnected')
    default: return ''
  }
}

function getConnectionClass(channel: Channel): string {
  switch (getConnectionState(channel)) {
    case 'CONNECTED': return 'conn-connected'
    case 'RECONNECTING': return 'conn-reconnecting'
    case 'ERROR': return 'conn-error'
    case 'OUT_OF_SERVICE': return 'conn-out-of-service'
    default: return 'conn-disconnected'
  }
}

function getConnectionTooltip(channel: Channel): string {
  const status = channelStatusMap.value[channel.id]
  if (!status) return ''
  let tip = getConnectionLabel(channel)
  if (status.reconnectAttempts > 0) {
    tip += ` (${t('channels.connection.retryCount', { n: status.reconnectAttempts })})`
  }
  if (status.lastError) {
    tip += `\n${t('channels.connection.errorLabel')}: ${status.lastError}`
  }
  return tip
}

// ==================== Modal control ====================

function openCreateModal() {
  // RFC-084: New channel always starts with the type picker so the wizard
  // can specialize Step 1 to the picked service. Editing an existing
  // channel still goes straight to the legacy modal via openEditModal.
  editingChannel.value = null
  modalDefaults.value = {}
  showTypePicker.value = true
}

function onTypePicked(type: string) {
  if (WIZARD_TYPES.has(type)) {
    wizardType.value = type
    showWizard.value = true
  } else {
    // Fall back to legacy modal for OAuth/QR types until they're migrated.
    modalDefaults.value = { type }
    showModal.value = true
  }
}

async function onWizardCreated(_channel: Channel) {
  await loadChannels()
  await loadStatus()
}

function openEditModal(channel: Channel) {
  editingChannel.value = channel
  modalDefaults.value = {}
  showModal.value = true
}

/** Modal asked us to switch to a fresh weixin-create flow. Close current,
 *  open a new one in create mode with weixin pre-selected and a numbered name. */
function handleAddNewWeixin() {
  showModal.value = false
  const existingCount = channels.value.filter((c) => c.channelType === 'weixin').length
  const newName = t('channels.weixin.newAccountName') + ' ' + (existingCount + 1)
  setTimeout(() => {
    editingChannel.value = null
    modalDefaults.value = { type: 'weixin', name: newName }
    showModal.value = true
  }, 200)
}

// ==================== Save ====================

async function handleSave(payload: Partial<Channel>) {
  try {
    if (editingChannel.value) {
      await channelApi.update(editingChannel.value.id, payload)
    } else {
      await channelApi.create(payload)
    }
    showModal.value = false
    editingChannel.value = null
    await loadChannels()
  } catch (e: any) {
    mcToast.error(e?.message || t('channels.messages.saveFailed'))
  }
}

// ==================== Toggle / delete ====================

async function deleteChannel(id: string | number) {
  const ok = await mcConfirm({
    title: t('channels.messages.deleteTitle'),
    message: t('channels.messages.deleteConfirm'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await channelApi.delete(id)
    await loadChannels()
  } catch (e: any) {
    mcToast.error(e?.message || t('channels.messages.deleteFailed'))
  }
}

async function toggleChannel(channel: Channel) {
  try {
    await channelApi.toggle(channel.id, !channel.enabled)
    await loadChannels()
    await loadStatus()
  } catch (e: any) {
    mcToast.error(e?.message || t('channels.messages.toggleFailed'))
  }
}

// ==================== Channel icon ====================

const CHANNEL_ICON_TYPES = ['web', 'dingtalk', 'feishu', 'wecom', 'weixin', 'telegram', 'discord', 'qq', 'slack', 'webchat', 'webhook']
function getChannelIconPath(type: string) {
  const name = CHANNEL_ICON_TYPES.includes(type) ? type : 'default'
  return `/icons/channels/${name}.svg`
}
</script>

<style scoped>
.channels-page { gap: 18px; }

.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: var(--mc-shadow-soft); }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }

/* Channel cards */
.channel-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 18px; }
/* Card sized to content — header + 2-line desc + footer ≈ 160px. Grid
   `align-items: stretch` keeps a row's cards visually aligned without
   forcing the 80px of empty space the old 238px floor produced. */
.channel-card { padding: 16px 18px 14px; transition: all 0.15s; display: flex; flex-direction: column; }
.channel-card-skeleton { pointer-events: none; opacity: 0.85; }
.channel-card:hover { border-color: var(--mc-primary-light); box-shadow: var(--mc-shadow-medium); transform: translateY(-2px); }
.channel-header { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 12px; }
.channel-icon-wrap { width: 48px; height: 48px; border-radius: 14px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; overflow: hidden; background: linear-gradient(135deg, rgba(217,109,87,0.12), rgba(24,74,69,0.08)); }
.channel-icon-img { width: 42px; height: 42px; border-radius: 12px; object-fit: cover; }
.channel-meta { flex: 1; }
.channel-name { font-size: 16px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 2px; }
.channel-type { font-size: 12px; color: var(--mc-text-tertiary); }

.channel-status { padding: 3px 10px; border-radius: 20px; font-size: 12px; font-weight: 500; }
.channel-status-group { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.status-on { background: var(--mc-primary-bg); color: var(--mc-primary); }
.status-off { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.connection-indicator { font-size: 11px; padding: 2px 8px; border-radius: 12px; white-space: nowrap; cursor: default; }
.conn-connected { color: var(--mc-primary); background: var(--mc-primary-bg); }
.conn-reconnecting { color: var(--mc-primary-hover); background: var(--mc-primary-bg); animation: pulse-reconnecting 1.5s ease-in-out infinite; }
.conn-error { color: var(--mc-danger); background: var(--mc-danger-bg); }
.conn-out-of-service { color: var(--mc-warning, #f59e0b); background: var(--mc-warning-bg, rgba(245, 158, 11, 0.1)); }
.conn-disconnected { color: var(--mc-text-tertiary); background: var(--mc-bg-sunken); }
@keyframes pulse-reconnecting { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }

/* 2-line clamp with ellipsis. The fixed line count keeps a row's
   cards visually balanced while ensuring a long type-fallback or a
   user blurb never balloons the card. -webkit-line-clamp + -webkit-box
   is the standard cross-browser combo (works in Chromium, Safari,
   Firefox 68+). */
.channel-desc {
    font-size: 13px;
    color: var(--mc-text-secondary);
    margin: 0 0 12px;
    line-height: 1.55;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    text-overflow: ellipsis;
}
/* Footer hugs the description directly — no `margin-top: auto`, so the
   card collapses to its content height. The divider above the buttons
   still gives visual separation without the old 60–80px gap. */
.channel-footer { display: flex; gap: 6px; border-top: 1px solid var(--mc-border-light); padding-top: 10px; flex-wrap: wrap; }
.card-btn { display: flex; align-items: center; gap: 4px; padding: 7px 11px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 10px; font-size: 12px; color: var(--mc-text-primary); cursor: pointer; transition: all 0.15s; font-weight: 600; }
.card-btn:hover { background: var(--mc-bg-sunken); }
.card-btn.danger:hover { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }

/* Compact add-another tail card — shown only when the user has at least
   one channel. The 0-channel hero owns the primary CTA in that case. */
/* Match the new card-content height so the dashed "+ Add" tile aligns
   in the grid row rather than towering over real channel cards. */
.add-card-compact { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 158px; padding: 16px; border: 2px dashed var(--mc-border); border-radius: 16px; cursor: pointer; background: transparent; transition: all 0.15s; font-family: inherit; }
.add-card-compact:hover { border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.add-icon-compact { font-size: 22px; color: var(--mc-text-tertiary); line-height: 1; }
.add-label-compact { font-size: 14px; color: var(--mc-text-tertiary); font-weight: 600; }
.add-card-compact:hover .add-icon-compact, .add-card-compact:hover .add-label-compact { color: var(--mc-primary); }

/* Empty hero — single CTA, no decorative grid of inactive types. */
.empty-hero { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 56px 32px; text-align: center; gap: 14px; }
.empty-hero-icon { width: 84px; height: 84px; border-radius: 24px; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, rgba(217,119,87,0.08), rgba(24,74,69,0.05)); color: var(--mc-primary); margin-bottom: 4px; }
.empty-hero-title { font-size: 22px; font-weight: 700; color: var(--mc-text-primary); margin: 0; }
.empty-hero-desc { font-size: 14px; color: var(--mc-text-secondary); margin: 0; max-width: 480px; line-height: 1.6; }
.empty-hero-cta { margin-top: 6px; padding: 12px 24px; font-size: 15px; }

/* Stats bar — small one-liner summarizing the whole list. */
.stats-bar { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; padding: 0 4px 4px; }
.stat { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 500; color: var(--mc-text-tertiary); }
.stat.ok { color: var(--mc-text-primary); }
.stat.warn { color: var(--mc-primary-hover); }
.stat.danger { color: var(--mc-danger, #ef4444); }
.stat.muted { color: var(--mc-text-tertiary); }
.stat-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.stat-dot.conn-connected { background: var(--mc-primary); }
.stat-dot.conn-reconnecting { background: var(--mc-primary-hover); animation: pulse-reconnecting 1.5s ease-in-out infinite; }
.stat-dot.conn-error { background: var(--mc-danger, #ef4444); }
</style>
