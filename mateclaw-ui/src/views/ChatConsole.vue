<template>
  <div class="mc-page-shell chat-console-shell">
    <div class="mc-page-frame chat-console-frame">
      <div class="chat-layout mc-surface-card">
        <!-- 移动端会话面板遮罩 -->
        <Transition name="fade">
          <div v-if="isMobile && convPanelOpen" class="conv-backdrop" @click="convPanelOpen = false"></div>
        </Transition>

    <!-- 会话侧边栏 -->
    <ConversationSidebar
      :conversations="conversations"
      :current-conversation-id="currentConversationId"
      :agents="agents"
      :selected-agent-id="selectedAgentId"
      :collapsed="convPanelCollapsed"
      :mobile-open="convPanelOpen"
      :is-mobile="isMobile"
      @select="selectConversation"
      @new-chat="newConversation"
      @agent-picked="onAgentPicked"
      @toggle-collapse="toggleConvPanel"
      @refresh="loadConversations"
      @deleted="onConversationsDeleted"
    />

    <!-- 主聊天区域 -->
    <div
      class="chat-area"
      @dragenter.prevent="onDragEnter"
      @dragover.prevent
      @dragleave="onDragLeave"
      @drop.prevent="onDrop"
    >
      <!-- 拖拽上传遮罩 -->
      <Transition name="fade">
        <div v-if="isDragging" class="drop-overlay">
          <div class="drop-overlay__content">
            <el-icon><UploadFilled /></el-icon>
            <span>{{ $t('chat.dropToUpload') }}</span>
          </div>
        </div>
      </Transition>
      <!-- 头部 -->
      <div class="chat-header">
        <div class="chat-header-left">
          <button v-if="isMobile" class="conv-toggle-btn" @click="convPanelOpen = !convPanelOpen" :title="$t('chat.conversations')">
            <el-icon><ChatDotRound /></el-icon>
          </button>
          <div class="chat-stage-copy" v-if="currentAgent">
            <div class="chat-stage-kicker">{{ $t('nav.chat') }}</div>
            <!--
              Header reads as "who is this employee" — name + tagline.
              The runtime mode (ReAct / Plan-Execute) is technical jargon
              to end users and lives in the badge tooltip instead, so the
              header doesn't get polluted.
            -->
            <div
              class="agent-badge"
              :title="`${currentAgent.name}${currentAgentRuntimeMode ? ' · ' + currentAgentRuntimeMode : ''}`"
            >
              <span class="agent-badge-icon" :style="{ color: agentIconColor(currentAgent.icon) }"><SkillIcon :value="currentAgent.icon" :size="22" :fallback="'🤖'" /></span>
              <div class="agent-badge-text">
                <span class="agent-badge-name">{{ currentAgent.name }}</span>
              </div>
              <span class="status-dot" :class="connectionStatusClass" :title="connectionStatusLabel"></span>
            </div>
          </div>
          <div v-else class="no-agent-hint">{{ $t('chat.selectAgent') }}</div>
        </div>
        <div class="chat-header-right">
          <!-- Model selector — Issue #81 v2 R3: always pass full providers + show-all-states
               so unhealthy rows render as dimmed entries with status chips and a Fix
               button instead of disappearing entirely. -->
          <ModelSelector
            :providers="providers"
            :active-value="activeModelValue"
            :active-label="activeModelLabel"
            :saving="modelSaving"
            :show-all-states="true"
            @select="selectModel"
            @navigate-fix="onModelSelectorFix"
          />
          <!-- Overflow menu -->
          <div class="header-overflow-wrap">
            <button ref="headerBtnRef" class="header-btn" @click="headerMenuOpen = !headerMenuOpen" :title="$t('common.more')">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="12" cy="19" r="1.5"/></svg>
            </button>
            <DropdownMenu
              :open="headerMenuOpen"
              :anchor="headerBtnRef"
              :items="headerMenuItems"
              @select="onHeaderMenuSelect"
              @close="headerMenuOpen = false"
            >
              <template #item-icon="{ item }">
                <el-icon v-if="item.key === 'config'"><Setting /></el-icon>
                <el-icon v-else-if="item.key === 'clear'"><Delete /></el-icon>
              </template>
            </DropdownMenu>
          </div>
        </div>
      </div>

      <!-- 使用组件化的 MessageList -->
      <MessageList
        ref="messageListRef"
        :messages="messages"
        :loading="isGenerating"
        :assistant-icon="currentAgent?.icon || '🤖'"
        :user-icon="userInitial"
        :title="blockingPrompt ? modelPromptText.title : $t('app.title')"
        :subtitle="blockingPrompt ? modelPromptText.desc : $t('chat.subtitle')"
        :suggestions="blockingPrompt ? [] : suggestions"
        @regenerate="handleRegenerate"
        @suggestion-click="sendSuggestion"
        @toggle-thinking="handleToggleThinking"
        @approve="handleApprove"
        @approve-always="handleApproveAlways"
        @deny="handleDeny"
      >
        <!-- Issue #81 v2 R2: blocking-only popup. Recoverable cases use the
             non-blocking <RecoverableModelBanner> below instead. -->
        <template v-if="blockingPrompt" #empty>
          <div class="model-prompt">
            <div class="model-prompt-title">{{ modelPromptText.title }}</div>
            <div class="model-prompt-desc">{{ modelPromptText.desc }}</div>
            <div class="model-prompt-actions">
              <button class="btn-primary" @click="handlePrimaryAction">
                {{ primaryActionLabel }}
              </button>
              <button
                v-if="bestSwitchTarget"
                class="btn-secondary"
                @click="switchToBestTarget"
              >
                {{ $t('chat.promptAction.switchToModel', { name: bestSwitchTarget.label }) }}
              </button>
            </div>
          </div>
        </template>
      </MessageList>

      <!-- Issue #81: non-blocking banner — active provider is unhealthy but the
           backend fallback chain has a LIVE provider to take over. -->
      <RecoverableModelBanner
        v-if="recoverablePrompt && activeProvider && bestFallbackName"
        :provider-name="activeProvider.name"
        :fallback-name="bestFallbackName"
        @dismiss="recoverableDismissed = true"
      />

      <!-- Cron job in-flight placeholder — visible while T2 hasn't committed
           the assistant message yet. Populated by pollActivity → /cron-jobs/active-runs. -->
      <div v-if="activeCronRuns.length > 0" class="cron-running-bar">
        <div v-for="run in activeCronRuns" :key="run.runId" class="cron-running-item">
          <span class="cron-running-spinner">🌀</span>
          <span class="cron-running-text">
            <strong>{{ run.jobName || $t('chat.cronRunning.fallbackName') }}</strong>
            <span class="cron-running-meta">
              · {{ $t('chat.cronRunning.executing') }}
              <template v-if="run.startedAt"> · {{ elapsedLabel(run.startedAt) }}</template>
            </span>
          </span>
        </div>
      </div>

      <!-- Terminal-state announcement after a goal completed or exhausted
           in this conversation. Auto-dismisses when the user clicks × or
           starts a new goal. -->
      <GoalSystemLine
        v-if="goalTerminalForCurrent && currentConversationId"
        :variant="goalTerminalForCurrent.status"
        :title="goalSystemLineTitle"
        :detail="goalSystemLineDetail"
        class="goal-system-line-slot"
        @click.stop="onGoalSystemLineDismiss"
      />

      <!-- Inline "set a goal?" invitation shown after the first assistant
           reply when the conversation has no active goal and the user
           hasn't dismissed it for this conv. -->
      <GoalSetInlinePrompt
        v-if="showGoalSetPrompt"
        :conversation-id="currentConversationId"
        :agent-id="String(selectedAgentId)"
        :workspace-id="String(currentWorkspaceId || '1')"
        :suggested-title="goalSuggestedTitle"
        class="goal-set-prompt-slot"
        @dismiss="onGoalPromptDismiss"
      />

      <!-- 流式处理 Loading 栏（消息和输入框之间） -->
      <StreamLoadingBar
        :is-loading="isGenerating && !blockingPrompt"
        :tool-count="toolCallCount"
        :completion-tokens="currentGeneratingTokens"
        :prompt-tokens="currentPromptTokens"
        :phase="streamPhase"
        :phase-info="phaseInfo"
        :running-tool-name="currentRunningToolName"
        :has-queued="hasQueued"
        :lifecycle-stage="lifecycleStage"
        :compact-status="compactStatus"
      />

      <!-- Multimodal routing hint: shown when pending attachments require a
           modality the primary model lacks. -->
      <MultimodalRoutingHint
        :attachments="pendingAttachments"
        :capabilities="agentCapabilities"
      />

      <!-- 使用组件化的 ChatInput -->
      <ChatInput
        ref="chatInputRef"
        v-model="inputText"
        :loading="isGenerating && !hasPendingApproval"
        :disabled="blockingPrompt || !currentAgent"
        :skills-enabled="!!currentAgent && !currentAgent.skillsDisabled"
        :placeholder="$t('chat.messagePlaceholder')"
        :hint="currentRuntimeModel"
        :attachments="pendingAttachments"
        :uploading="uploadingAttachment"
        :max-length="10240"
        :pending-approval="activePendingApproval"
        :stream-phase="streamPhase"
        :queued-message="queuedMessage"
        :queue-size="queueSize"
        @submit="handleSendMessage"
        @stop="handleStopStream"
        @cancel-queued="handleCancelQueued"
        @file-select="handleFileSelect"
        @attachment-remove="removeAttachment"
        @approve="handleApprove"
        @approve-always="handleApproveAlways"
        @deny="handleDeny"
        :enable-talk-mode="!!selectedAgentId"
        :thinking-enabled="thinkingEnabled"
        :thinking-supported="currentModelSupportsThinking"
        @toggle-thinking="thinkingEnabled = !thinkingEnabled"
        @talk="showTalkMode = true"
      />
    </div>

        <!-- Talk Mode 覆盖层 -->
        <TalkMode
          v-if="showTalkMode"
          :visible="showTalkMode"
          :agent-id="selectedAgentId"
          :conversation-id="currentConversationId"
          @close="showTalkMode = false"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { ChatDotRound, Delete, Setting, UploadFilled } from '@element-plus/icons-vue'
import { conversationApi, agentApi, modelApi, chatApi, cronJobApi, approvalApi } from '@/api/index'
import { ElMessage } from 'element-plus'
import { copyToClipboard } from '@/utils/clipboard'
import { useFileDrop } from '@/composables/useFileDrop'
import { useIsMobile, useMediaQuery, BREAKPOINTS } from '@/composables/useBreakpoint'
import { useChat } from '@/composables/chat/useChat'
import { reconstructErrorInfo } from '@/types/chatError'
import { reconcileMessages, extractMessages } from '@/utils/messageReconcile'
import type { Conversation, Agent, ModelConfig, ProviderInfo, ActiveModelsInfo, ChatAttachment, MessageContentPart, Message, ToolCallMeta, StreamPhase } from '@/types'

// 导入组件化组件
import MessageList from '@/components/chat/MessageList.vue'
import RecoverableModelBanner from '@/components/chat/RecoverableModelBanner.vue'
import SkillIcon from '@/components/common/SkillIcon.vue'
import ConversationSidebar from '@/components/chat/ConversationSidebar.vue'
import DropdownMenu, { type DropdownMenuItem } from '@/components/common/DropdownMenu.vue'
import { agentIconColor } from '@/utils/agentIconColor'
import ChatInput from '@/components/chat/ChatInput.vue'
import MultimodalRoutingHint from '@/components/chat/MultimodalRoutingHint.vue'
import StreamLoadingBar from '@/components/chat/StreamLoadingBar.vue'
import TalkMode from '@/components/chat/TalkMode.vue'
import ModelSelector from '@/components/chat/ModelSelector.vue'
import { useEChartsRenderer } from '@/composables/useEChartsRenderer'
import { useKatexRenderer } from '@/composables/useKatexRenderer'
import { useMermaidRenderer, handleMermaidDownload } from '@/composables/useMermaidRenderer'
import { useGoalStore } from '@/stores/useGoalStore'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import GoalSetInlinePrompt from '@/components/goal/GoalSetInlinePrompt.vue'
import GoalSystemLine from '@/components/goal/GoalSystemLine.vue'

// ============ Talk Mode ============
const showTalkMode = ref(false)

// ============ 移动端 & 响应式状态 ============
const convPanelOpen = ref(false)
const convPanelCollapsed = ref(localStorage.getItem('mc-conv-collapsed') === 'true')
const userExplicitConvCollapse = ref(localStorage.getItem('mc-conv-collapsed') === 'true')

const isMobile = useIsMobile()
const compactViewport = useMediaQuery(BREAKPOINTS.compact)

// Leaving the mobile breakpoint closes the conversation drawer.
watch(isMobile, (mobile) => {
  if (!mobile) convPanelOpen.value = false
})

// Auto-collapse the conversation panel on narrow desktop unless the user
// toggled it explicitly.
watch(compactViewport, (compact) => {
  if (!userExplicitConvCollapse.value) convPanelCollapsed.value = compact
}, { immediate: true })

function toggleConvPanel() {
  convPanelCollapsed.value = !convPanelCollapsed.value
  userExplicitConvCollapse.value = convPanelCollapsed.value
  localStorage.setItem('mc-conv-collapsed', String(convPanelCollapsed.value))
}

// ============ 配置和常量 ============
const suggestions = computed(() => {
  const agent = currentAgent.value
  // If agent has custom suggestions (stored as newline-separated string in description etc.)
  const agentSuggestions = (agent as any)?.suggestions as string | undefined
  if (agentSuggestions) {
    const parsed = agentSuggestions.split('\n').filter(Boolean).slice(0, 4)
    if (parsed.length) return parsed
  }
  // Agent-type-aware defaults
  if (agent?.agentType === 'plan_execute') {
    return [
      t('chat.suggestionPlan1', '帮我制定一个完整的项目计划'),
      t('chat.suggestionPlan2', '分步骤帮我完成一个复杂任务'),
      t('chat.suggestionIntro'),
      t('chat.suggestionWeather'),
    ]
  }
  return [
    t('chat.suggestionIntro'),
    t('chat.suggestionPoem'),
    t('chat.suggestionCode'),
    t('chat.suggestionWeather'),
  ]
})

// ============ 状态 ============
const router = useRouter()
const route = useRoute()
const { t } = useI18n()

const agents = ref<Agent[]>([])
const conversations = ref<Conversation[]>([])
const selectedAgentId = ref<string | number>('')
const currentConversationId = ref<string>('')
const inputText = ref('')
const modelSaving = ref(false)
// Monotonic counter for in-flight setModel PUTs. The finally handler
// only clears modelSaving when its captured seq is still the latest, so a
// stale-finishing earlier PUT can't unlock the selector while a newer one
// is still in flight, and (crucially) switching conversations mid-PUT
// can't permanently lock the selector by leaving modelSaving stuck true.
let modelSaveSeq = 0
// Issue #81 v2 R2: split the single showModelPrompt boolean into two flags so
// the chat surface can either hard-block (blockingPrompt) or warn but let the
// backend fallback chain take over (recoverablePrompt). Driven by
// recomputePromptFlags() — see the watcher below.
const blockingPrompt = ref(false)
const recoverablePrompt = ref(false)
const recoverableDismissed = ref(false)
const defaultModel = ref<ModelConfig | null>(null)
const providers = ref<ProviderInfo[]>([])
// True when /models 403s for a viewer-level user. Provider config (API keys,
// base URLs, liveness) is admin-only, so viewers chat without it; the prompt
// flags fall back to "trust the active model" in that branch.
const providersUnavailable = ref(false)
// Mirror of /models/enabled (viewer-accessible). Used to resolve the display
// name of the active model when providers is empty for viewer-level users —
// otherwise the model selector trigger would show its 配置模型 fallback even
// though there IS an active model.
const enabledModels = ref<ModelConfig[]>([])
// The model the CURRENT conversation uses. Per-conversation — switching it
// never leaks into other conversations (see selectModel / applyConversationModel).
const activeModels = ref<ActiveModelsInfo | null>(null)
// Global default model — seeds the selector for conversations with no pin yet.
const globalDefaultModel = ref<{ providerId: string; model: string } | null>(null)
const pendingAttachments = ref<ChatAttachment[]>([])
const uploadingAttachment = ref(false)

// Per-agent capability snapshot for the multimodal routing hint above the
// input box. Refetched whenever the active agent changes; cached locally to
// avoid an extra request per attachment change.
const agentCapabilities = ref<import('@/types').AgentCapabilities | null>(null)

// 思考模式：只有两个状态 — 开或关
const thinkingEnabled = ref(localStorage.getItem('mateclaw_thinking') !== 'off')
const thinkingLevel = computed(() => thinkingEnabled.value ? 'high' : 'off')
watch(thinkingEnabled, (v) => localStorage.setItem('mateclaw_thinking', v ? 'on' : 'off'))

// Dropdowns & menus
const headerMenuOpen = ref(false)
const headerBtnRef = ref<HTMLElement | null>(null)

const headerMenuItems = computed<DropdownMenuItem[]>(() => [
  { key: 'config', label: t('chat.configModel') },
  { key: 'sessions', label: t('chat.openSessions') },
  { divider: true },
  { key: 'clear', label: t('chat.clearMessages'), danger: true },
])

function onHeaderMenuSelect(item: DropdownMenuItem) {
  if (item.key === 'config') goToModelSettings()
  else if (item.key === 'sessions') router.push('/sessions')
  else if (item.key === 'clear') clearMessages()
}

function onAgentPicked(value: string | number | null) {
  if (value == null) return
  if (String(value) !== String(selectedAgentId.value)) {
    selectedAgentId.value = value
    newConversation()
  }
}

function selectModel(value: string) {
  const [providerId, model] = value.split('::')
  if (!providerId || !model) return
  // Per-conversation model: switching here only affects THIS conversation.
  // We update the selector + the local list entry immediately so the UI is
  // responsive, then persist the pin to the server right away IF the
  // conversation already exists. Without the eager persist, IM channels
  // (Feishu / DingTalk / WeCom …) keep using whatever the conversation row
  // last had — they don't see the /chat/stream payload that the web path
  // pins on send — so the user "switches model in the chat box" but the
  // next IM inbound message still picks the old / default model.
  //
  // Snapshot the previous selection BEFORE the optimistic update so a
  // failed PUT can roll the UI back instead of stranding the user with a
  // model the backend isn't using.
  const prevLlm = activeModels.value?.activeLlm
  const prevActive: ActiveModelsInfo | null = prevLlm?.providerId && prevLlm?.model
    ? { activeLlm: { providerId: prevLlm.providerId, model: prevLlm.model } }
    : null
  const conv = conversations.value.find(c => c.conversationId === currentConversationId.value)
  const prevConvProvider = conv?.modelProvider
  const prevConvModel = conv?.modelName

  activeModels.value = { activeLlm: { providerId, model } }
  if (conv) {
    conv.modelProvider = providerId
    conv.modelName = model
  }
  // Only persist when the conversation is already in the server-side list.
  // A brand-new chat (newConversation() generated a local id that hasn't
  // been sent through /chat/stream yet) has no row to PUT against; that
  // case still relies on the first /chat/stream call writing the pin.
  if (conv && currentConversationId.value) {
    const cid = currentConversationId.value
    const mySeq = ++modelSaveSeq
    modelSaving.value = true
    conversationApi.setModel(cid, providerId, model)
      .catch((e: any) => {
        console.warn('[ChatConsole] Failed to persist model pin:', e)
        mcToast.warning(t('chat.modelSaveFailed'))
        // Roll back the visible selector + the cached conv pin so the UI
        // doesn't keep claiming a model the backend isn't using. Only do
        // it when the user is still on the same conversation AND hasn't
        // picked yet another model — otherwise we'd corrupt the more
        // recent state with this PUT's snapshot.
        const liveConv = conversations.value.find(c => c.conversationId === cid)
        if (liveConv && liveConv.modelProvider === providerId && liveConv.modelName === model) {
          liveConv.modelProvider = prevConvProvider
          liveConv.modelName = prevConvModel
        }
        if (currentConversationId.value !== cid) return
        const stillShowingFailedPick =
            activeModels.value?.activeLlm?.providerId === providerId
            && activeModels.value?.activeLlm?.model === model
        if (!stillShowingFailedPick) return
        activeModels.value = prevActive
      })
      .finally(() => {
        // Only the LATEST in-flight PUT clears the saving flag. An earlier
        // PUT finishing late must not flip saving to false while a newer
        // one is still pending (the selector would unlock during a live
        // request); and a conversation switch mid-PUT must not strand the
        // flag at true forever (which would lock the selector across
        // every conversation — the original bug this seq counter fixes).
        if (mySeq === modelSaveSeq) {
          modelSaving.value = false
        }
      })
  }
}

/**
 * Point the model selector at a conversation's pinned model, or the global
 * default when the conversation has no pin yet (fresh chat, IM, cron).
 */
function applyConversationModel(conv?: Conversation | null) {
  if (conv?.modelProvider && conv?.modelName) {
    activeModels.value = { activeLlm: { providerId: conv.modelProvider, model: conv.modelName } }
  } else if (globalDefaultModel.value) {
    activeModels.value = { activeLlm: { ...globalDefaultModel.value } }
  }
}

/**
 * After a poll refreshes the conversation list, the currently-open
 * conversation may have drifted server-side: an admin may have rebound the
 * channel to a different agent, or pinned a different model via another
 * tab / API call. Pull the new server-side state into the local selector +
 * agent header so the chat surface doesn't keep claiming the old binding.
 *
 * Skipped while a turn is generating — yanking the model / agent mid-stream
 * would orphan the active SSE subscription.
 */
function reconcileCurrentConversation() {
  if (!currentConversationId.value) return
  if (isGenerating.value) return
  // Don't fight an in-flight setModel write — the poll cycle may run BEFORE
  // the PUT lands, in which case the server still reports the old pin and
  // we'd flicker the UI back. Wait for the next tick.
  if (modelSaving.value) return
  const fresh = conversations.value.find(c => c.conversationId === currentConversationId.value)
  if (!fresh) return
  if (fresh.agentId != null && String(fresh.agentId) !== String(selectedAgentId.value)) {
    selectedAgentId.value = fresh.agentId
  }
  const pickedProvider = activeModels.value?.activeLlm?.providerId
  const pickedModel = activeModels.value?.activeLlm?.model
  const serverHasPin = !!(fresh.modelProvider && fresh.modelName)
  if (serverHasPin) {
    if (fresh.modelProvider !== pickedProvider || fresh.modelName !== pickedModel) {
      activeModels.value = { activeLlm: { providerId: fresh.modelProvider!, model: fresh.modelName! } }
    }
  } else if (pickedProvider || pickedModel) {
    // Server-side pin was cleared (admin reset, model deleted, …) but the
    // local selector still shows the old pick. Drop back to whatever the
    // global default resolves to — applyConversationModel does the right
    // thing when conv has no pin.
    applyConversationModel(fresh)
  }
}

// 拖拽上传 — useFileDrop owns the hover/counter state; the directory-aware
// payload handling (electron paths vs web FileSystem entries) stays here.
const { isDragging, onDragEnter, onDragLeave, onDrop } = useFileDrop(processDroppedItems)

async function processDroppedItems(e: DragEvent) {
  const dtFiles = Array.from(e.dataTransfer?.files || [])
  const items = Array.from(e.dataTransfer?.items || [])

  const electronDirs: File[] = []
  const webDirEntries: FileSystemDirectoryEntry[] = []
  const regularFiles: File[] = []

  for (let i = 0; i < items.length; i++) {
    const entry = items[i].webkitGetAsEntry?.()
    const file = dtFiles[i]
    if (entry?.isDirectory) {
      if ((file as any)?.path) {
        // Electron: has absolute path
        electronDirs.push(file)
      } else {
        // Web: need to recursively collect files
        webDirEntries.push(entry as FileSystemDirectoryEntry)
      }
    } else if (file) {
      regularFiles.push(file)
    }
  }

  // Electron directories → record path reference
  if (electronDirs.length) {
    handleDirectoryAttach(electronDirs)
  }
  // Web directories → recursively collect files and upload
  if (webDirEntries.length) {
    const collected = await collectFilesFromEntries(webDirEntries)
    if (collected.length) {
      handleFileSelect(collected)
    }
  }
  // Regular files → normal upload
  if (regularFiles.length) {
    handleFileSelect(regularFiles)
  }
}

function handleDirectoryAttach(dirFiles: File[]) {
  if (!currentConversationId.value) {
    newConversation()
  }
  for (const dir of dirFiles) {
    const dirPath = (dir as any).path as string
    pendingAttachments.value.push({
      name: dirPath.split('/').pop() || dir.name,
      size: 0,
      url: '',
      storedName: '',
      path: dirPath,
      contentType: 'inode/directory',
    })
  }
}

async function collectFilesFromEntries(dirEntries: FileSystemDirectoryEntry[]): Promise<File[]> {
  const files: File[] = []

  async function readDir(dir: FileSystemDirectoryEntry) {
    const reader = dir.createReader()
    let batch: FileSystemEntry[]
    do {
      batch = await new Promise<FileSystemEntry[]>((resolve, reject) => {
        reader.readEntries(resolve, reject)
      })
      for (const entry of batch) {
        if (entry.isFile) {
          const file = await new Promise<File>((resolve, reject) => {
            (entry as FileSystemFileEntry).file(resolve, reject)
          })
          files.push(file)
        } else if (entry.isDirectory) {
          await readDir(entry as FileSystemDirectoryEntry)
        }
      }
    } while (batch.length > 0)  // readEntries returns empty when done
  }

  for (const dir of dirEntries) {
    await readDir(dir)
  }
  return files
}

const messageListRef = ref<InstanceType<typeof MessageList> | null>(null)
const chatInputRef = ref<InstanceType<typeof ChatInput> | null>(null)

// Post-render augmentations (ECharts, KaTeX, Mermaid) all watch the same
// MessageList container — placeholders emitted by useMarkdownRenderer get
// upgraded in place after Vue paints the rendered Markdown HTML.
const echartsContainerRef = computed(() => messageListRef.value?.$el as HTMLElement | null)
const { startObserving: startECharts, dispose: disposeECharts } = useEChartsRenderer(echartsContainerRef)
const { startObserving: startKatex, dispose: disposeKatex } = useKatexRenderer(echartsContainerRef)
const { startObserving: startMermaid, dispose: disposeMermaid } = useMermaidRenderer(echartsContainerRef)

// Last-attempt draft, restored into the input box when the SSE error event
// arrives async (sendChatMessage resolves on connect, the error fires later,
// so the catch in handleSendMessage cannot recover input by itself).
const pendingSendDraft = ref<{ input: string; attachments: any[] } | null>(null)

// 使用 useChat composable
const {
  messages,
  isGenerating,
  streamPhase,
  phaseInfo,
  queuedMessage,
  hasQueued,
  queueSize,
  heartbeat,
  compactStatus,
  lifecycleStage,
  sendMessage: sendChatMessage,
  stopGeneration: stopChatGeneration,
  cancelQueued,
  reconnectStream: reconnectChatStream,
  resetForNewConversation,
} = useChat({
  baseUrl: '',
  thinkingLevel,
  onStreamEnd: async (meta) => {
    // Restore the input/attachments if the turn ended in an error and the
    // user hasn't typed something else in the meantime.
    if (meta.reason === 'error' && pendingSendDraft.value) {
      const draft = pendingSendDraft.value
      if (!inputText.value) inputText.value = draft.input
      if (pendingAttachments.value.length === 0) pendingAttachments.value = draft.attachments
    }
    if (meta.reason !== 'error') {
      pendingSendDraft.value = null
    }
    // 流结束后刷新会话列表（更新 lastActiveTime / 标题等）
    await loadConversations()
    if (meta.conversationId && meta.conversationId === currentConversationId.value) {
      // Skip DB refresh for awaiting_approval / interrupted / error:
      //  - awaiting_approval / interrupted: avoids overwriting local-only state
      //    or breaking message ordering.
      //  - error: the failed turn (e.g. SSE setup failure like "无权操作该会话")
      //    was never persisted, so refreshing would wipe the user's just-sent
      //    bubble and the failed assistant placeholder, leaving no trace of
      //    the attempt in the chat window.
      if (meta.reason !== 'awaiting_approval'
          && meta.reason !== 'interrupted'
          && meta.reason !== 'error') {
        await refreshCurrentConversationMessages(meta.conversationId)
      }
    }
  },
})

// ============ 连接状态 ============
const connectionStatusClass = computed(() => {
  if (isGenerating.value) return 'status-streaming'
  if (streamPhase.value === 'failed') return 'status-error'
  return 'status-idle'
})
const connectionStatusLabel = computed(() => {
  if (isGenerating.value) return t('chat.status.streaming', 'Generating...')
  if (streamPhase.value === 'failed') return t('chat.status.error', 'Disconnected')
  return t('chat.status.idle', 'Ready')
})

// ============ 计算属性 ============
const currentAgent = computed(() => agents.value.find(a => String(a.id) === String(selectedAgentId.value)))

/** Human label for the agent's runtime mode — surfaces in the badge tooltip
 *  only, never in the visible header. */
const currentAgentRuntimeMode = computed(() => {
  const a = currentAgent.value
  if (!a) return ''
  return a.agentType === 'react' ? t('agents.types.react') : t('agents.types.planExecute')
})

// Per-conversation last-viewed timestamp store (localStorage-backed, MVP).
// Keyed by conversationId. Updated when the user opens a conversation; the
// sidebar reads it (ConversationSidebar.hasUnread) to render the accent dot.
// Will move to a server-side table once we want cross-device read state.
const VIEWED_KEY_PREFIX = 'mc-conv-viewed:'
function markConversationViewed(conversationId: string | undefined, lastActiveTime?: string) {
  if (!conversationId) return
  const ts = lastActiveTime ? new Date(lastActiveTime).getTime() : Date.now()
  try {
    localStorage.setItem(VIEWED_KEY_PREFIX + conversationId, String(ts))
  } catch {
    // localStorage full / disabled — degrade silently; dot just stays on.
  }
}

const currentRuntimeModel = computed(() => {
  // Bind to the per-conversation active model — the same source the model
  // selector reads — so the indicator updates the instant the user switches.
  // Reading the global defaultModel here left the indicator frozen on the
  // default while the selector moved.
  const providerId = activeModels.value?.activeLlm?.providerId
  const modelName = activeModels.value?.activeLlm?.model
  if (providerId && modelName) {
    const provider = providers.value.find((p) => p.id === providerId)
    const all = provider ? [...(provider.models || []), ...(provider.extraModels || [])] : []
    const hit = all.find((m) => m.id === modelName || m.name === modelName)
    if (hit) return `${hit.name || hit.id} (${hit.id})`
    // Viewer-level users get an empty providers list — resolve via /models/enabled.
    const em = enabledModels.value.find(
      (m) => m.provider === providerId && (m.modelName === modelName || m.name === modelName)
    )
    if (em) return em.name ? `${em.name} (${em.modelName})` : em.modelName
    return modelName
  }
  // No active model resolved yet — fall back to the global default, then the agent.
  if (defaultModel.value?.name && defaultModel.value?.modelName) {
    return `${defaultModel.value.name} (${defaultModel.value.modelName})`
  }
  return currentAgent.value?.modelName || 'default'
})

/**
 * RFC-049 PR-1-UI: whether the active runtime model supports <em>any</em> form
 * of deep thinking (OpenAI reasoning_effort / Kimi native / DeepSeek-Reasoner
 * native / Anthropic extended thinking). Drives the enable/disable state of
 * the thinking-depth toggle in ChatInput.
 *
 * Reads the broad capability (`supportsThinking`) from ProviderModelInfo,
 * populated server-side in ModelInfoDTO. The narrow `supportsReasoningEffort`
 * only covers OpenAI gpt-5/o1/o3/o4 and would wrongly gray out Kimi K2.x,
 * DeepSeek-Reasoner, and Claude — all of which legitimately support thinking.
 */
const currentModelSupportsThinking = computed<boolean>(() => {
  const providerId = activeModels.value?.activeLlm?.providerId
  const modelName = activeModels.value?.activeLlm?.model
  if (!providerId || !modelName) return false
  const provider = providers.value.find((p) => p.id === providerId)
  if (!provider) return false
  const all = [...(provider.models || []), ...(provider.extraModels || [])]
  const hit = all.find((m) => m.id === modelName || m.name === modelName)
  return Boolean(hit?.supportsThinking)
})

const userInitial = computed(() => (localStorage.getItem('username') || 'U').charAt(0).toUpperCase())

const activeModelValue = computed(() => {
  const providerId = activeModels.value?.activeLlm?.providerId
  const model = activeModels.value?.activeLlm?.model
  return providerId && model ? `${providerId}::${model}` : ''
})

const activeModelLabel = computed(() => {
  if (!activeModelValue.value) return ''
  const match = eligibleModels.value.find(m => m.value === activeModelValue.value)
  if (match?.label) return match.label
  // Viewer-level users have an empty providers list (admin-only endpoint), so
  // eligibleModels is empty even when there IS an active model. Fall back to
  // the viewer-readable /models/enabled list to resolve a display name —
  // otherwise the trigger button would read "配置模型" forever.
  const providerId = activeModels.value?.activeLlm?.providerId
  const modelName = activeModels.value?.activeLlm?.model
  if (!providerId || !modelName) return ''
  const hit = enabledModels.value.find(m =>
    m.provider === providerId && (m.modelName === modelName || m.name === modelName))
  if (hit) return hit.name ? `${hit.name} (${hit.modelName})` : hit.modelName
  return `${providerId} / ${modelName}`
})

const activeProvider = computed(() => {
  const providerId = activeModels.value?.activeLlm?.providerId
  return providerId ? providers.value.find((provider) => provider.id === providerId) || null : null
})

// Issue #81: liveness-aware popup state machine. modelPromptKind picks one of
// six branches; modelPromptText derives title + desc; primaryActionLabel +
// handlePrimaryAction map to the suggestedAction the backend computed.
type ModelPromptKind = 'no-active' | 'unconfigured' | 'removed' | 'cooldown' | 'unprobed' | 'no-models'

const modelPromptKind = computed<ModelPromptKind>(() => {
  if (!activeModels.value?.activeLlm?.providerId) return 'no-active'
  const p = activeProvider.value
  if (!p) return 'no-active'
  switch (p.liveness) {
    case 'UNCONFIGURED': return 'unconfigured'
    case 'REMOVED':      return 'removed'
    case 'COOLDOWN':     return 'cooldown'
    case 'UNPROBED':     return 'unprobed'
    case 'LIVE':         return 'no-models'
    default:             return 'no-active'
  }
})

const hintText = computed(() => {
  const p = activeProvider.value
  if (!p?.suggestedActionHintKey) return ''
  return t(p.suggestedActionHintKey, (p.suggestedActionHintArgs || {}) as Record<string, unknown>)
})

const modelPromptText = computed<{ title: string; desc: string }>(() => {
  const p = activeProvider.value
  switch (modelPromptKind.value) {
    case 'no-active':
      return { title: t('chat.prompt.noActive.title'), desc: t('chat.prompt.noActive.desc') }
    case 'unconfigured':
      return {
        title: t('chat.prompt.unconfigured.title', { name: p?.name || '' }),
        desc:  t('chat.prompt.unconfigured.desc', { fields: p?.missingFields || '', hint: hintText.value }),
      }
    case 'removed':
      return {
        title: t('chat.prompt.removed.title', { name: p?.name || '' }),
        desc:  p?.unavailableReason || t('chat.prompt.removed.descFallback'),
      }
    case 'cooldown':
      return {
        title: t('chat.prompt.cooldown.title', { name: p?.name || '' }),
        desc:  t('chat.prompt.cooldown.desc', {
          seconds: Math.max(1, Math.ceil((p?.cooldownRemainingMs || 0) / 1000)),
        }),
      }
    case 'unprobed':
      return { title: t('chat.prompt.unprobed.title'), desc: t('chat.prompt.unprobed.desc') }
    case 'no-models':
      return {
        title: t('chat.prompt.noModels.title', { name: p?.name || '' }),
        desc:  t('chat.prompt.noModels.desc'),
      }
  }
})

const primaryActionLabel = computed(() => {
  const action = activeProvider.value?.suggestedAction || 'configure_required_fields'
  switch (action) {
    case 'fill_base_url':              return t('chat.promptAction.fillBaseUrl')
    case 'fill_api_key':               return t('chat.promptAction.fillApiKey')
    case 'start_oauth':                return t('chat.promptAction.startOAuth')
    case 'test_connection':            return t('chat.promptAction.testConnection')
    case 'pull_model':                 return t('chat.promptAction.pullModel')
    case 'wait_cooldown':              return t('chat.promptAction.waitCooldown')
    case 'reprobe':                    return t('chat.promptAction.reprobe')
    case 'configure_required_fields':
    default:                           return t('chat.goToModelSettings')
  }
})

function handlePrimaryAction() {
  goToModelSettings(activeProvider.value?.id)
}

/** First eligible model that is NOT the active one — what the secondary button switches to. */
const bestSwitchTarget = computed<{ value: string; label: string } | null>(() => {
  for (const m of eligibleModels.value) {
    if (m.value !== activeModelValue.value) return m
  }
  return null
})

/** First LIVE provider name — used by RecoverableModelBanner. */
const bestFallbackName = computed<string>(() => {
  const target = bestSwitchTarget.value
  if (!target) return ''
  const [providerId] = target.value.split('::')
  return providers.value.find(p => p.id === providerId)?.name || ''
})

function switchToBestTarget() {
  const t = bestSwitchTarget.value
  if (t) selectModel(t.value)
}

function onModelSelectorFix(provider: { id: string }) {
  goToModelSettings(provider.id)
}

const availableProviders = computed(() =>
  providers.value.filter((p) => p.available && [...(p.models || []), ...(p.extraModels || [])].length > 0)
)

const eligibleModels = computed(() => {
  return availableProviders.value.flatMap((provider) => {
    const allModels = [...(provider.models || []), ...(provider.extraModels || [])]
    return allModels.map((model) => ({
      value: `${provider.id}::${model.id}`,
      label: `${provider.name} / ${model.name || model.id}`,
    }))
  })
})

// ============ 生命周期 ============
// Global shortcuts (Ctrl+K agents, Ctrl+N new chat) live in MainLayout so they
// work from any page; this view reacts to the dispatched event when mounted.
function handleChatShortcut(e: Event) {
  const action = (e as CustomEvent).detail as 'newChat' | undefined
  if (action === 'newChat') {
    newConversation()
    nextTick(() => chatInputRef.value?.focus?.())
  }
}

// Cross-page hand-off from MainLayout's global shortcuts: read once on mount
// (before loadAgents triggers syncRouteState, which would wipe the action key)
// and apply after agents are loaded so the dropdown actually has something to show.
let pendingRouteAction: 'newChat' | '' = ''

function captureRouteAction() {
  const action = route.query.action
  if (action === 'newChat') {
    pendingRouteAction = action
  }
}

function applyPendingRouteAction() {
  const action = pendingRouteAction
  pendingRouteAction = ''
  if (action === 'newChat') {
    newConversation()
    nextTick(() => chatInputRef.value?.focus?.())
  }
}

// 轮询定时器：让 ChatConsole 能实时感知外部渠道（WeChat/DingTalk/…）推进来的新消息，
// 无需 F5 即可看到侧栏列表更新和选中会话的消息/流状态。
let activityPollTimer: number | null = null
// Reentrancy guard: setInterval fires every ACTIVITY_POLL_MS regardless of
// whether the previous async pollActivity has finished. If one cycle runs long
// (slow reconnectStream / sluggish backend), unguarded ticks stack up and run
// concurrently, multiplying in-flight requests. This flag keeps one cycle at a
// time — late ticks become no-ops until the running cycle returns.
let activityPolling = false
const ACTIVITY_POLL_MS = 4000

// Cron progress placeholder: when a cron job is mid-run on the currently
// visible conversation (tasks_<wsId> / cron_<id>) the assistant bubble only
// appears after T2 commits, which can be 1–5 minutes for tool-heavy ReAct
// loops. activeCronRuns is filled by the same pollActivity tick so the user
// sees a "executing…" placeholder instead of staring at a blank screen.
interface ActiveCronRun {
  runId: number | string
  jobId: number | string
  jobName?: string
  triggerType?: string
  conversationId?: string
  startedAt?: string
}
const activeCronRuns = ref<ActiveCronRun[]>([])
function isCronConversation(cid: string | null | undefined): boolean {
  return !!cid && (cid.startsWith('tasks_') || cid.startsWith('cron_'))
}
async function refreshActiveCronRuns(cid: string) {
  if (!isCronConversation(cid)) {
    activeCronRuns.value = []
    return
  }
  try {
    const res: any = await cronJobApi.activeRuns(cid)
    if (currentConversationId.value !== cid) return
    const next: ActiveCronRun[] = res?.data ?? []
    const wasRunning = activeCronRuns.value.length > 0
    activeCronRuns.value = next
    // Transition from "had runs" to "no runs" → assistant bubble was just
    // persisted by T2; fetch messages so it shows up without waiting for the
    // next pollActivity tick to align.
    if (wasRunning && next.length === 0) {
      await refreshCurrentConversationMessages(cid)
    }
  } catch {
    // Network blip — keep the previous state, next tick will retry.
  }
}
// Reactive ticker so the elapsed label updates without depending on a poll.
const elapsedNow = ref(Date.now())
let elapsedTickTimer: number | null = null
function elapsedLabel(startedAt?: string): string {
  if (!startedAt) return ''
  const ms = elapsedNow.value - new Date(startedAt).getTime()
  if (ms < 0 || !Number.isFinite(ms)) return ''
  const sec = Math.floor(ms / 1000)
  if (sec < 60) return `${sec}s`
  const min = Math.floor(sec / 60)
  const rem = sec % 60
  return `${min}m${rem > 0 ? rem + 's' : ''}`
}

/**
 * 判断当前消息列表的末尾是不是一条"本地仅有的失败气泡"。
 * 典型场景：SSE setup 阶段就抛错（如"无权操作该会话"），
 * 这次 turn 的 user / assistant 消息从未持久化进 DB。
 * 数据库快照不知道它们存在，pollActivity 的对齐会把它们冲掉。
 *
 * 识别条件：末尾 assistant 状态为 failed、带 errorInfo、且 id 不是 DB 数值 id（client uuid）。
 */
function hasLocalOnlyFailedTail(): boolean {
  const last = messages.value[messages.value.length - 1] as any
  if (!last || last.role !== 'assistant') return false
  if (last.status !== 'failed') return false
  if (!last.errorInfo) return false
  return !/^\d+$/.test(String(last.id))
}

async function pollActivity() {
  // 页面不可见时不轮询，避免切到别的标签还在空耗
  if (typeof document !== 'undefined' && document.hidden) return
  // Skip when the previous cycle is still running so slow polls can't stack.
  if (activityPolling) return
  activityPolling = true
  try {
    try {
      await loadConversations()
      reconcileCurrentConversation()
    } catch {
      // 静默失败，下一轮再试
    }
    // 自己没在生成时才刷新当前选中会话的消息 + 探测是否该接入流
    if (currentConversationId.value && !isGenerating.value && streamPhase.value !== 'awaiting_approval') {
      const cid = currentConversationId.value
      try {
        const statusRes: any = await conversationApi.getStatus(cid)
        if (currentConversationId.value !== cid) return
        const running = statusRes?.data?.streamStatus === 'running'
        if (running) {
          // 外部渠道正在跑：
          // 1. 先从 DB 拉消息，把刚插入的 user 消息（"你在干什么"之类）带进来，
          //    否则只接入流的话前端只能看到 assistant content_delta，看不到用户问题。
          // 2. 再接入流，让后续 content_delta 实时累积到 assistant 气泡。
          await refreshCurrentConversationMessages(cid)
          if (currentConversationId.value !== cid || isGenerating.value) return
          await reconnectStream(cid)
        } else if (!hasLocalOnlyFailedTail()) {
          // 不在跑：从 DB 对齐消息（新 user 消息 / 刚落库 assistant 会合并进来）。
          // 但若末尾是本地失败气泡（SSE setup 失败一类，后端从未持久化过），
          // 就跳过对齐 —— 不然这次的 user/失败 assistant 会被 DB 快照覆盖掉，
          // 用户除了上面的 toast 看不到任何痕迹。
          await refreshCurrentConversationMessages(cid)
        }
      } catch {
        // 忽略探测失败
      }
      // Cron progress placeholder — independent of streamStatus because cron
      // runs use the non-streaming chat() path, so streamStatus stays idle.
      await refreshActiveCronRuns(cid)
    }
  } finally {
    activityPolling = false
  }
}

onMounted(async () => {
  captureRouteAction()
  window.addEventListener('mc:chat-shortcut', handleChatShortcut)
  document.addEventListener('click', handleCodeCopy)
  startECharts()
  startKatex()
  startMermaid()
  await Promise.all([loadAgents(), loadModelState(), loadConversations()])
  await hydrateStateFromRoute()
  applyPendingRouteAction()
  activityPollTimer = window.setInterval(pollActivity, ACTIVITY_POLL_MS)
  elapsedTickTimer = window.setInterval(() => {
    if (activeCronRuns.value.length > 0) elapsedNow.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  window.removeEventListener('mc:chat-shortcut', handleChatShortcut)
  document.removeEventListener('click', handleCodeCopy)
  disposeECharts()
  disposeKatex()
  disposeMermaid()
  if (activityPollTimer !== null) {
    clearInterval(activityPollTimer)
    activityPollTimer = null
  }
  if (elapsedTickTimer !== null) {
    clearInterval(elapsedTickTimer)
    elapsedTickTimer = null
  }
  // Switching tabs / route changes / mouse-detach unmount this component, but the
  // backend agent should keep running so the user can reconnect later. Use
  // resetForNewConversation (front-end SSE disconnect only) instead of
  // stopChatGeneration which would POST /stop and abort the in-flight turn.
  resetForNewConversation()
  // 释放所有附件的 ObjectURL，防止内存泄漏
  revokeAllPreviewUrls()
})

watch(() => route.query, () => {
  // If a fresh action arrives (e.g. user re-fires Ctrl+K via the URL while
  // the view is already alive), pick it up immediately.
  captureRouteAction()
  if (pendingRouteAction) applyPendingRouteAction()
  void hydrateStateFromRoute()
})

watch([selectedAgentId, currentConversationId], () => {
  syncRouteState()
})

// Load the active goal whenever the user switches conversation. The
// avatar ring listens on goalStore.activeGoalByConv[cid]; without this
// fetch the ring would only appear after an SSE event mutated the store.
const goalStore = useGoalStore()
const workspaceStoreForGoal = useWorkspaceStore()
const currentWorkspaceId = computed(() => workspaceStoreForGoal.currentWorkspaceId ?? '1')
watch(currentConversationId, async (cid) => {
  if (cid) {
    await goalStore.loadActiveForConversation(cid)
  }
}, { immediate: true })

// Derive props for the inline prompt + system-line slots that sit
// between MessageList and ChatInput. The prompt shows only when:
//   1) there's a current conversation, agent, and at least one assistant
//      reply (otherwise the prompt is premature);
//   2) there's no active goal (the ring already covers active state);
//   3) the user hasn't dismissed the prompt on this conv;
//   4) we're not mid-stream (don't pop suggestions while the agent
//      is still typing).
const goalTerminalForCurrent = computed(() =>
  currentConversationId.value
    ? goalStore.recentTerminal(currentConversationId.value)
    : null,
)
const goalSystemLineTitle = computed(() => {
  const t = goalTerminalForCurrent.value
  if (!t) return ''
  // The leading icon is owned by GoalSystemLine (✦ / ⚠) so we don't
  // prepend one here — doing so produced "✦ 🎉 …" double-glyph titles.
  return t.status === 'completed' ? `目标达成 · ${t.title}` : `这次的预算用完了 · ${t.title}`
})
const goalSystemLineDetail = computed(() => {
  const t = goalTerminalForCurrent.value
  if (!t) return ''
  if (t.status === 'completed') {
    return t.score != null
      ? `已完成 · final score ${t.score.toFixed(2)}`
      : '已完成 · 总结已存入长期记忆'
  }
  // exhausted
  if (t.reason === 'turn_budget') return '预算轮数用完。'
  if (t.reason === 'llm_call_budget') return 'LLM 调用预算用完。'
  return '预算耗尽。'
})

const showGoalSetPrompt = computed(() => {
  if (!currentConversationId.value || !selectedAgentId.value) return false
  if (isGenerating.value) return false
  // Active goal? The ring covers that — no need for a prompt.
  if (goalStore.activeGoal(currentConversationId.value)) return false
  // Recent terminal still showing? Let the user dismiss that first.
  if (goalTerminalForCurrent.value) return false
  if (goalStore.isPromptDismissed(currentConversationId.value)) return false
  // Need at least one user → assistant exchange so the prompt has
  // context to derive a suggested title from.
  const hasAssistantReply = messages.value.some(m => m.role === 'assistant')
  if (!hasAssistantReply) return false
  // Heuristic: don't claim "this looks multi-turn" without evidence. Fire
  // the prompt only when at least one signal of a real ongoing task is
  // present. Without these the prompt fires after one-shot Q&A like
  // "三句话告诉我 X" and the copy lies to the user.
  const userTurns = messages.value.filter(m => m.role === 'user').length
  if (userTurns >= 2) return true // multiple user messages = ongoing thread
  // Single-turn case: only suggest if the agent did non-trivial work.
  return messages.value.some(m => {
    if (m.role !== 'assistant') return false
    const md: any = (m as any).metadata
    if (!md) return false
    // Tool calls — strongest signal of real work.
    if (Array.isArray(md.toolCalls) && md.toolCalls.length > 0) return true
    // Plan steps — Plan-Execute agent ran a multi-step plan.
    if (md.plan && Array.isArray(md.plan.steps) && md.plan.steps.length > 1) return true
    // Multiple ReAct iterations / segments — agent looped.
    if (Array.isArray(md.segments) && md.segments.length > 3) return true
    return false
  })
})

// Build a sensible default title from the conversation's first user
// message. The user can always edit later via the goal page.
const goalSuggestedTitle = computed(() => {
  const firstUser = messages.value.find(m => m.role === 'user')
  const raw = (firstUser?.content || '').trim()
  if (!raw) return '新目标'
  // 80 char clip mirrors GoalController.create validation.
  return raw.length > 80 ? raw.slice(0, 77) + '...' : raw
})

function onGoalPromptDismiss() {
  if (currentConversationId.value) {
    goalStore.dismissPrompt(currentConversationId.value)
  }
}

function onGoalSystemLineDismiss() {
  if (currentConversationId.value) {
    goalStore.clearRecentTerminal(currentConversationId.value)
  }
}

// Refetch agent capabilities (modalities + sidecar config) on agent change so
// the multimodal routing hint above the input box can react synchronously when
// the user attaches an image / video.
watch(selectedAgentId, async (id) => {
  if (!id) { agentCapabilities.value = null; return }
  try {
    const res: any = await agentApi.getCapabilities(id)
    agentCapabilities.value = res.data || null
  } catch {
    agentCapabilities.value = null
  }
}, { immediate: true })

// ============ 方法 ============
async function loadAgents() {
  try {
    // Hide disabled agents from the picker — they cannot be chatted with
    // (the chat endpoints reject disabled agents), so showing them invites
    // a confusing failure path. The admin Agents view passes no filter.
    const res: any = await agentApi.list({ enabled: true })
    agents.value = res.data || []
    // 只有在 URL 没有指定 agentId 且当前无选中时，才默认选第一个
    if (agents.value.length > 0 && !selectedAgentId.value && !route.query.agentId) {
      selectedAgentId.value = agents.value[0].id
    }
  } catch (e) {
    mcToast.error(t('chat.loadAgentsFailed'))
  }
}

async function loadModelState() {
  // /default + /active + /enabled are viewer-accessible and required to chat.
  // /models (provider list) is admin-only because it returns API keys + base
  // URLs; viewers degrade to "trust the active model, skip the liveness
  // banner" and resolve the label via /enabled instead.
  try {
    const [defaultRes, activeRes, enabledRes]: any = await Promise.all([
      modelApi.getDefault(),
      modelApi.getActive(),
      modelApi.listEnabled(),
    ])
    defaultModel.value = defaultRes.data || null
    const ga = activeRes.data?.activeLlm
    globalDefaultModel.value = ga?.providerId && ga?.model
      ? { providerId: ga.providerId, model: ga.model }
      : null
    // Seed the selector when no conversation has set it yet (fresh chat, or
    // before a conversation is selected). A conversation that already has a
    // model keeps it — selectConversation/applyConversationModel own that.
    if (!activeModels.value && globalDefaultModel.value) {
      activeModels.value = { activeLlm: { ...globalDefaultModel.value } }
    }
    enabledModels.value = enabledRes.data || []
  } catch (e) {
    mcToast.error(t('chat.loadModelFailed'))
    blockingPrompt.value = true
    recoverablePrompt.value = false
    return
  }
  try {
    const providersRes: any = await modelApi.listProviders()
    providers.value = providersRes.data || []
    providersUnavailable.value = false
  } catch (e: any) {
    if (e?.response?.status === 403) {
      providers.value = []
      providersUnavailable.value = true
    } else {
      // Non-403 failure is still a real problem worth surfacing.
      mcToast.error(t('chat.loadModelFailed'))
    }
  }
  recomputePromptFlags()
}

/**
 * Issue #81 v2 R2: derive blocking / recoverable prompt flags from the current
 * providers + active model snapshot. Called from loadModelState after every
 * /providers refresh, and from a watcher when the user switches model. The
 * runtime fallback chain in NodeStreamingChatHelper picks the first LIVE
 * provider regardless of which one is "active", so as long as ANY provider is
 * LIVE we should NOT block — we just hint with a banner.
 */
function recomputePromptFlags() {
  const active = activeModels.value?.activeLlm
  if (!active?.providerId || !active?.model) {
    blockingPrompt.value = true
    recoverablePrompt.value = false
    recoverableDismissed.value = false
    return
  }
  // Viewer-level users cannot read the provider list (admin-only because it
  // returns API keys), so we can't compute liveness. Trust the active model
  // and let the agent runtime surface any per-call failure instead of
  // blocking the entire chat surface.
  if (providersUnavailable.value) {
    blockingPrompt.value = false
    recoverablePrompt.value = false
    recoverableDismissed.value = false
    return
  }
  const ap = providers.value.find(p => p.id === active.providerId) || null
  const apHasModels = ap
    ? ((ap.models?.length || 0) + (ap.extraModels?.length || 0)) > 0
    : false
  const activeUsable = ap?.liveness === 'LIVE' && apHasModels
  if (activeUsable) {
    blockingPrompt.value = false
    recoverablePrompt.value = false
    recoverableDismissed.value = false
    return
  }
  const anyUsable = providers.value.some(p =>
    p.liveness === 'LIVE'
    && ((p.models?.length || 0) + (p.extraModels?.length || 0)) > 0)
  blockingPrompt.value = !anyUsable
  recoverablePrompt.value = anyUsable && !recoverableDismissed.value
}

// Issue #81 v2 R2: keep blocking/recoverable in sync with the providers list
// and the active model selection without forcing every mutation site to call
// recomputePromptFlags() manually.
watch([providers, activeModels], recomputePromptFlags, { deep: true })

async function loadConversations() {
  try {
    const res: any = await conversationApi.list()
    conversations.value = res.data || []
  } catch (e) {
    mcToast.error(t('chat.loadConversationsFailed'))
  }
}

async function refreshCurrentConversationMessages(conversationId: string) {
  if (!conversationId) return
  if (isGenerating.value) return
  if (streamPhase.value === 'awaiting_approval') return
  try {
    const res: any = await conversationApi.listMessages(conversationId)
    // Stale guard：await 返回后确认仍是当前会话
    if (currentConversationId.value !== conversationId) return
    // 二次 isGenerating 检查：如果 await 期间用户已发新消息，不覆盖本地状态
    if (isGenerating.value) return
    const fetched = extractMessages(res).messages.map((msg: Message) => normalizeMessage(msg))
    // 严格过滤：只保留 conversationId 完全匹配的本地消息，orphan（空 conversationId）直接丢弃
    const currentMessages = messages.value.filter(
      (m: any) => m.conversationId === conversationId
    )
    messages.value = reconcileMessages(currentMessages, fetched)
  } catch (e) {
    console.warn('[ChatConsole] Failed to refresh current conversation messages:', e)
  }
}

async function hydrateStateFromRoute() {
  const agentId = route.query.agentId ? String(route.query.agentId) : ''
  const conversationId = String(route.query.conversationId || '')

  if (agentId && agentId !== String(selectedAgentId.value)) {
    selectedAgentId.value = agentId
  }

  if (conversationId && conversationId !== currentConversationId.value) {
    const matchedConversation = conversations.value.find(conv => conv.conversationId === conversationId)
    if (matchedConversation) {
      await selectConversation(matchedConversation)
    } else {
      // 会话不在已加载列表中（可能来自 Sessions 页面跳转），尝试加载消息
      currentConversationId.value = conversationId
      messages.value = []
      try {
        const res: any = await conversationApi.listMessages(conversationId)
        if (currentConversationId.value !== conversationId) return
        messages.value = extractMessages(res).messages.map((msg: Message) => normalizeMessage(msg))
      } catch {
        // 消息加载失败，保持空
      }
      try {
        if (currentConversationId.value !== conversationId) return
        const statusRes: any = await conversationApi.getStatus(conversationId)
        if (currentConversationId.value === conversationId && statusRes.data?.streamStatus === 'running') {
          await reconnectStream(conversationId)
        }
      } catch {
        // 忽略
      }
    }
  }

  // 如果仍然没选中 agent，默认选第一个
  if (!selectedAgentId.value && agents.value.length > 0) {
    selectedAgentId.value = agents.value[0].id
  }
}

function syncRouteState() {
  const query: Record<string, string> = {}
  if (selectedAgentId.value) query.agentId = String(selectedAgentId.value)
  if (currentConversationId.value) query.conversationId = currentConversationId.value
  router.replace({ path: '/chat', query })
}

async function selectConversation(conv: Conversation) {
  if (isMobile.value) convPanelOpen.value = false
  // 切换到不同会话：只清理本地 UI/SSE（resetForNewConversation 会 stream.disconnect + 清变量），
  // 但不 POST /chat/{A}/stop —— 让 A 的后台 agent run 跑到完成。
  // 用户之后回到 A：pollActivity / selectConversation 的 /status 探测会自动 reconnect 接回实时流；
  // 若 A 已完成，refreshCurrentConversationMessages 会从 DB 拉完整结果。
  // 点同一个会话则完全不 reset，避免打断正在观察的流。
  const switchingAway = currentConversationId.value !== conv.conversationId
  if (switchingAway) {
    resetForNewConversation()
  }
  currentConversationId.value = conv.conversationId
  selectedAgentId.value = conv.agentId || selectedAgentId.value
  // Restore this conversation's pinned model into the selector.
  applyConversationModel(conv)
  // Reset cron placeholder state up front; the immediate fetch below repopulates
  // it for cron conversations so the user doesn't wait up to 4s for the next tick.
  activeCronRuns.value = []
  if (isCronConversation(conv.conversationId)) {
    void refreshActiveCronRuns(conv.conversationId)
  }
  // Mark as read when opened — clears the unread dot on tasks_<wsId> after
  // the user actually visits the cron output. localStorage-only for MVP;
  // server-side last-viewed table is a future enhancement.
  markConversationViewed(conv.conversationId, conv.lastActiveTime)
  const requestedConvId = conv.conversationId
  try {
    const res: any = await conversationApi.listMessages(requestedConvId)
    // Stale guard：await 返回后确认仍是当前会话，否则丢弃
    if (currentConversationId.value !== requestedConvId) return
    // 点同一个会话时，若已有 SSE 在跑就不要覆盖本地消息状态
    if (switchingAway || !isGenerating.value) {
      messages.value = extractMessages(res).messages.map((msg: Message) => normalizeMessage(msg))
    }

    // Hydrate pending approvals：恢复刷新后丢失的审批卡片（RFC-067 §4.9）
    //
    // Two-way reconciliation between the server's pending list and each
    // message's metadata.pendingApproval:
    //   1. Forward — server pending → align onto the message that already
    //      carries the same pendingId (so multi-pending convs don't have
    //      every banner overwrite the same row); fallback to last assistant
    //      only when no message has that id yet.
    //   2. Reverse — local message metadata still says pending_approval but
    //      the server no longer lists that pendingId → flip to 'expired'
    //      locally. This closes the GC/timeout loop without requiring an
    //      extra server-side broadcast: the next refresh sees a clean state.
    try {
      const approvalRes: any = await chatApi.getPendingApprovals(requestedConvId)
      if (currentConversationId.value !== requestedConvId) return
      const pendingApprovals: any[] = approvalRes.data || []

      // Index existing messages by their embedded pendingId (assistant only).
      const indexById = new Map<string, Message>()
      for (const m of messages.value) {
        if (m.role !== 'assistant') continue
        const pid = (m as any).metadata?.pendingApproval?.pendingId
        if (typeof pid === 'string' && pid) indexById.set(pid, m)
      }

      // Forward direction: align server-known pending onto its owning message.
      for (const pa of pendingApprovals) {
        const enriched = {
          pendingId: pa.pendingId,
          toolName: pa.toolName,
          arguments: pa.toolArguments,
          reason: pa.reason,
          status: 'pending_approval' as const,
          findings: pa.findingsJson ? JSON.parse(pa.findingsJson) : undefined,
          maxSeverity: pa.maxSeverity || undefined,
          summary: pa.summary || undefined,
        }
        const target = indexById.get(pa.pendingId)
        if (target) {
          (target as any).metadata = {
            ...(target as any).metadata,
            currentPhase: 'awaiting_approval',
            pendingApproval: enriched,
          }
        } else {
          // Fallback: no message in the loaded history claims this pendingId
          // (typical when the assistant message hasn't been persisted yet —
          // e.g., approval fired before doOnComplete). Append to the last
          // assistant; same as pre-RFC behavior, but logged so a regression
          // where multiple unmatched pendings collide is observable.
          const assistantMessages = messages.value.filter(m => m.role === 'assistant')
          const lastAssistant = assistantMessages[assistantMessages.length - 1]
          if (lastAssistant) {
            console.warn('[hydrate] pendingId %s has no owning message — falling back to last assistant', pa.pendingId)
            ;(lastAssistant as any).metadata = {
              ...(lastAssistant as any).metadata,
              currentPhase: 'awaiting_approval',
              pendingApproval: enriched,
            }
          }
        }
      }

      // Reverse direction: any local pending_approval whose pendingId is not
      // in the server's list got resolved (timeout / consume) without a UI
      // event — flip to expired so MessageBubble hides the banner.
      const serverIds = new Set<string>(pendingApprovals.map((p: any) => p.pendingId))
      for (const m of messages.value) {
        if (m.role !== 'assistant') continue
        const meta = (m as any).metadata
        const local = meta?.pendingApproval
        if (local?.status === 'pending_approval'
            && local.pendingId
            && !serverIds.has(local.pendingId)) {
          (m as any).metadata = {
            ...meta,
            pendingApproval: { ...local, status: 'expired' },
          }
        }
      }
    } catch {
      // hydration 失败不影响正常使用
    }

    // 决定是否重连 SSE：
    // - 快照 streamStatus==='running' → 直接重连
    // - 否则探测实时状态（兜底处理：渠道消息进入后侧栏快照未刷新时，仍能接入运行中的流）
    let shouldReconnect = conv.streamStatus === 'running'
    if (!shouldReconnect) {
      try {
        const statusRes: any = await conversationApi.getStatus(requestedConvId)
        if (currentConversationId.value !== requestedConvId) return
        shouldReconnect = statusRes?.data?.streamStatus === 'running'
      } catch {
        // 探测失败不阻断主流程
      }
    }
    if (currentConversationId.value === requestedConvId && shouldReconnect) {
      await reconnectStream(requestedConvId)
    }
  } catch (e) {
    mcToast.error(t('chat.loadMessagesFailed'))
  }
}

function newConversation() {
  // Creating a new chat is just local navigation. Keep any previous backend
  // run alive so the user can return and reconnect to it later.
  resetForNewConversation()
  currentConversationId.value = `conv_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  messages.value = []
  // A fresh conversation starts on the global default model.
  applyConversationModel()
}

// The sidebar performs the delete API call(s) and emits the removed ids.
// Drop them from the local list and reset the chat area if the conversation
// currently open was among those deleted.
function onConversationsDeleted(ids: string[]) {
  conversations.value = conversations.value.filter(c => !ids.includes(c.conversationId))
  if (ids.includes(currentConversationId.value)) {
    resetStreamingState()
    messages.value = []
    currentConversationId.value = ''
  }
}

async function clearMessages() {
  if (!currentConversationId.value) return
  try {
    resetStreamingState()
    await conversationApi.clearMessages(currentConversationId.value)
    messages.value = []
  } catch {
    messages.value = []
  }
}

// onModelChange removed — replaced by selectModel()

function goToModelSettings(providerId?: string) {
  // Issue #81: when called with a providerId (e.g. from the unhealthy popup or
  // ModelSelector's Fix button), pass it as a query param so a follow-up PR can
  // scroll/focus the right card on the settings page. Today the consumer just
  // ignores it; harmless meanwhile.
  router.push({ path: '/settings/models', query: providerId ? { focus: providerId } : {} })
}

// ============ 计算属性：是否有待审批 ============
const hasPendingApproval = computed(() => {
  return messages.value.some(
    m => m.role === 'assistant' && (m as any).metadata?.pendingApproval?.status === 'pending_approval'
  )
})

// 当前待审批的那条数据（传给 ChatInput 用于渲染审批栏）
const activePendingApproval = computed(() => {
  const msg = messages.value.findLast(
    m => m.role === 'assistant' && (m as any).metadata?.pendingApproval?.status === 'pending_approval'
  )
  return (msg as any)?.metadata?.pendingApproval ?? null
})

// 当前工具调用数
const toolCallCount = computed(() => {
  const lastMsg = messages.value.findLast(m => m.role === 'assistant')
  return lastMsg?.metadata?.toolCalls?.length ?? 0
})

// 当前正在执行的工具名称
const currentRunningToolName = computed(() => {
  if (!isGenerating.value) return ''
  const lastMsg = messages.value.findLast(m => m.role === 'assistant')
  const metadata = lastMsg?.metadata
  if (metadata?.runningToolName) return metadata.runningToolName
  const runningTool = metadata?.toolCalls?.findLast((tc: any) => tc.status === 'running')
  return runningTool?.name || heartbeat.value?.runningToolName || ''
})

// 当前正在生成的消息的 token 数据
const currentGeneratingTokens = computed(() => {
  if (!isGenerating.value) return 0
  // 找到最后一条 assistant 消息（可能仍在生成）
  const lastMsg = messages.value.findLast(m => m.role === 'assistant')
  // 返回 completionTokens（从服务器响应中获取）
  return (lastMsg as any)?.completionTokens ?? 0
})

const currentPromptTokens = computed(() => {
  if (!isGenerating.value) return 0
  const lastMsg = messages.value.findLast(m => m.role === 'assistant')
  return (lastMsg as any)?.promptTokens ?? 0
})

// ============ 消息发送和处理 ============
async function handleSendMessage(content: string) {
  // 允许在等待审批时发送审批命令
  const isApprovalCommand = /^\/(approve|deny)$/i.test(content.trim())

  if ((!content && pendingAttachments.value.length === 0) || !selectedAgentId.value || blockingPrompt.value) return
  // 不再阻止运行中发送 — useChat 会自动走 interrupt/queue 路径

  // 拦截 /approve 和 /deny 命令 —— 通过 SSE 流发送（和普通消息相同通道）
  const trimmed = content.trim().toLowerCase()
  if (trimmed === '/approve' || trimmed === '/deny') {
    if (!currentConversationId.value) {
      mcToast.warning('No active conversation')
      inputText.value = ''
      chatInputRef.value?.clear?.()
      return
    }

    // 检查是否有 pending approval
    const pendingMsg = messages.value.findLast(
      m => m.role === 'assistant' && (m as any).metadata?.pendingApproval?.status === 'pending_approval'
    )
    if (!pendingMsg) {
      mcToast.warning('No pending approval to process')
      inputText.value = ''
      chatInputRef.value?.clear?.()
      return
    }

    // 乐观更新审批状态
    const decision = trimmed === '/approve' ? 'approved' : 'denied'
    ;(pendingMsg as any).metadata.pendingApproval.status = decision

    inputText.value = ''
    chatInputRef.value?.clear?.()

    // 通过正常 SSE 流发送（复用聊天通道，replay 结果实时流式推送）
    try {
      await sendChatMessage(trimmed, {
        conversationId: currentConversationId.value,
        agentId: selectedAgentId.value,
        contentParts: [],
      })
    } catch (e: any) {
      console.error('Approval stream failed:', e)
      // 回滚乐观更新
      ;(pendingMsg as any).metadata.pendingApproval.status = 'pending_approval'
      mcToast.error(e?.message || 'Approval failed')
    }
    return
  }

  if (!currentConversationId.value) {
    currentConversationId.value = `conv_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  }

  // Issue #81 v2 R2: only abort when there is genuinely no usable provider.
  // If the active provider is unhealthy but another is LIVE, let the request
  // through — NodeStreamingChatHelper's fallback walker will pick it up and
  // emit a "warning" SSE delta which the input handler surfaces as a toast.
  if (!activeModels.value?.activeLlm?.providerId || !activeModels.value?.activeLlm?.model) {
    blockingPrompt.value = true
    return
  }
  if (blockingPrompt.value) {
    return
  }

  const outgoingAttachments = pendingAttachments.value.map((attachment) => ({ ...attachment }))
  const contentParts = buildOutgoingParts(content, outgoingAttachments)

  // 先暂存，发送成功后再清空（失败时恢复）
  const savedInput = inputText.value
  const savedAttachments = [...pendingAttachments.value]
  // Stash for async-error recovery in onStreamEnd (sync catch can't reach this).
  pendingSendDraft.value = { input: savedInput, attachments: savedAttachments }
  inputText.value = ''
  chatInputRef.value?.clear?.()
  pendingAttachments.value = []

  try {
    await sendChatMessage(content, {
      conversationId: currentConversationId.value,
      agentId: selectedAgentId.value,
      contentParts,
      thinkingLevel: thinkingLevel.value,
      modelProvider: activeModels.value?.activeLlm?.providerId,
      modelName: activeModels.value?.activeLlm?.model,
      attachments: outgoingAttachments.map(a => ({
        type: 'file' as const,
        fileUrl: a.url,
        fileName: a.name,
        storedName: a.storedName,
        contentType: a.contentType,
        fileSize: a.size,
        path: a.path,
      })),
    })
    // 发送成功后释放 ObjectURL
    revokeAllPreviewUrls()
  } catch (e) {
    console.error('Send message failed:', e)
    // 发送失败：恢复输入和附件，用户不丢失已上传的文件
    if (!inputText.value) inputText.value = savedInput
    if (pendingAttachments.value.length === 0) pendingAttachments.value = savedAttachments
  }
}

function handleStopStream() {
  stopChatGeneration()
}

function handleRegenerate(message: Message) {
  if (isGenerating.value) return
  const idx = messages.value.indexOf(message)
  if (idx >= 0) {
    messages.value.splice(idx, 1)
  }
  const lastUserMsg = messages.value.findLast(m => m.role === 'user')
  if (!lastUserMsg) return

  const text = lastUserMsg.contentParts
    .filter(p => p.type === 'text')
    .map(p => p.text || '')
    .join('\n') || lastUserMsg.content || ''

  handleSendMessage(text)
}

function sendSuggestion(text: string) {
  inputText.value = text
  handleSendMessage(text)
}

function handleToggleThinking(message: import('@/types').Message, expanded: boolean) {
  message.thinkingExpanded = expanded
}

// ============ 审批处理 ============
async function handleApprove(pendingId: string) {
  if (!currentConversationId.value) return
  await handleSendMessage('/approve')
}

async function handleDeny(pendingId: string) {
  if (!currentConversationId.value) return
  await handleSendMessage('/deny')
}

// Always-approve: create the matching grant first, then send /approve as usual.
// Failure to create the grant doesn't block the approval — we still forward
// /approve so the user's click isn't lost, just toast the error.
async function handleApproveAlways(
  payload: { pendingId: string; scope: 'CONVERSATION' | 'AGENT' | 'USER' },
) {
  if (!currentConversationId.value) return
  const pa = activePendingApproval.value
  if (!pa) return

  // Resolve scope_id from the scope dimension.
  let scopeId = ''
  if (payload.scope === 'CONVERSATION') {
    scopeId = currentConversationId.value
  } else if (payload.scope === 'AGENT') {
    scopeId = String(currentAgent.value?.id ?? '')
  } else if (payload.scope === 'USER') {
    const me = localStorage.getItem('mc-user-id')
    if (me) scopeId = me
  }
  if (!scopeId) {
    ElMessage.error('Cannot resolve scope id for always-approve')
    await handleSendMessage('/approve')
    return
  }

  try {
    const sev = pa.maxSeverity ?? 'LOW'
    // Severity ceiling = at-or-above the current finding's severity. CRITICAL
    // never enters this path (the backend rejects it), so HIGH covers the rest.
    const ceiling = sev === 'HIGH' || sev === 'CRITICAL' ? 'HIGH'
      : sev === 'MEDIUM' ? 'MEDIUM' : 'LOW'
    const ruleId = pa.findings?.find((f: { ruleId?: string }) => !!f.ruleId)?.ruleId ?? null
    await approvalApi.createGrant({
      scopeType: payload.scope,
      scopeId,
      toolName: pa.toolName,
      ruleId,
      maxSeverity: ceiling,
      grantKind: payload.scope === 'CONVERSATION' ? 'UNTIL_CONVERSATION_END' : 'ALWAYS',
      note: `created from approval banner (${pa.toolName})`,
    })
    ElMessage.success(
      t('chat.approveAlwaysCreated', { tool: pa.toolName }) as string,
    )
  } catch (e: any) {
    ElMessage.error(e?.message || 'Failed to create auto-approve rule')
  }
  await handleSendMessage('/approve')
}

// 重连到运行中的流
async function reconnectStream(conversationId: string) {
  if (isGenerating.value) return
  try {
    await reconnectChatStream(conversationId)
  } catch (e) {
    console.error('[ChatConsole] Reconnect failed:', e)
    mcToast.warning(t('chat.reconnectFailed') || 'Stream reconnection failed')
  }
}

function handleCancelQueued() {
  cancelQueued()
}

// 简化版重置函数
function resetStreamingState() {
  // 先通知后端停止旧流（fire-and-forget），再彻底清理前端状态
  stopChatGeneration()
  resetForNewConversation()
}

// ============ 附件处理 ============
async function handleFileSelect(files: File[]) {
  if (!currentConversationId.value) {
    newConversation()
  }

  uploadingAttachment.value = true
  try {
    for (const file of files) {
      const res: any = await chatApi.uploadFile(currentConversationId.value, file)
      const data = res.data || {}
      // 图片/视频使用本地 ObjectURL 预览（避免 /api/v1/chat/files/ 需要 JWT 认证导致加载失败）
      const ct = data.contentType || file.type || ''
      const isPreviewable = ct.startsWith('image/') || ct.startsWith('video/')
      const previewUrl = isPreviewable ? URL.createObjectURL(file) : data.url
      pendingAttachments.value.push({
        name: data.fileName || file.name,
        size: data.size || file.size,
        url: data.url,
        storedName: data.storedName,
        path: data.path,
        contentType: data.contentType || file.type,
        previewUrl,
      })
    }
  } catch (e) {
    mcToast.error(t('chat.uploadFailed'))
  } finally {
    uploadingAttachment.value = false
  }
}

function removeAttachment(key: string) {
  // revoke 被移除附件的 ObjectURL，防止内存泄漏
  const removed = pendingAttachments.value.find(a => a.storedName === key || a.path === key)
  if (removed?.previewUrl?.startsWith('blob:')) {
    URL.revokeObjectURL(removed.previewUrl)
  }
  pendingAttachments.value = pendingAttachments.value.filter(
    a => a.storedName !== key && a.path !== key
  )
}

/** 释放所有 pending 附件的 ObjectURL */
function revokeAllPreviewUrls() {
  for (const a of pendingAttachments.value) {
    if (a.previewUrl?.startsWith('blob:')) {
      URL.revokeObjectURL(a.previewUrl)
    }
  }
}

function buildOutgoingParts(text: string, attachments: ChatAttachment[]): MessageContentPart[] {
  const parts: MessageContentPart[] = []
  if (text) parts.push({ type: 'text', text })
  for (const attachment of attachments) {
    const ct = attachment.contentType || ''
    const partType: MessageContentPart['type'] = ct.startsWith('video/') ? 'video'
      : ct.startsWith('image/') ? 'image'
      : 'file'
    parts.push({
      type: partType,
      fileUrl: attachment.url,
      fileName: attachment.name,
      storedName: attachment.storedName,
      contentType: attachment.contentType,
      fileSize: attachment.size,
      path: attachment.path,
    })
  }
  return parts
}

// ============ 工具函数 ============
function normalizeMessage(raw: Message): Message {
  const msg: Message = { ...raw, contentParts: raw.contentParts ? [...raw.contentParts] : [] }

  // 统一解析 metadata：确保是对象而非 JSON 字符串
  // 注意：后端 metadata 在 DB 中是 JSON 字符串，Jackson 序列化时可能双重编码
  if (typeof msg.metadata === 'string') {
    try {
      let parsed = JSON.parse(msg.metadata)
      // 处理双重编码：parse 后仍然是字符串的情况
      if (typeof parsed === 'string') {
        try { parsed = JSON.parse(parsed) } catch { /* ignore */ }
      }
      msg.metadata = parsed
    } catch { msg.metadata = {} as any }
  }

  // 保留后端返回的 token 字段（MessageVO 新增）
  if ((raw as any).promptTokens) msg.promptTokens = (raw as any).promptTokens
  if ((raw as any).completionTokens) msg.completionTokens = (raw as any).completionTokens
  if ((raw as any).runtimeModel) msg.runtimeModel = (raw as any).runtimeModel
  if ((raw as any).runtimeProvider) msg.runtimeProvider = (raw as any).runtimeProvider

  if (msg.contentParts.length === 0 && msg.content) {
    if (msg.role === 'assistant') {
      const parsed = parseThinkingContent(msg.content)
      // thinkingLevel=off 时不展示 thinking 内容，直接剥离 <think> 标签
      if (parsed.thinking && thinkingLevel.value !== 'off') {
        msg.contentParts.push({ type: 'thinking', text: parsed.thinking })
      }
      if (parsed.content) msg.contentParts.push({ type: 'text', text: parsed.content })
      msg.content = parsed.content
    } else {
      msg.contentParts.push({ type: 'text', text: msg.content })
    }
  }

  // 从 tool_call contentParts 还原 metadata.toolCalls
  const toolCallParts = msg.contentParts.filter(p => p.type === 'tool_call')
  if (toolCallParts.length > 0) {
    const toolCalls: ToolCallMeta[] = []
    for (const part of toolCallParts) {
      try {
        const parsed = JSON.parse(part.text || '{}')
        toolCalls.push({
          name: parsed.name || '',
          arguments: parsed.arguments,
          result: parsed.result,
          success: parsed.success,
          // 历史消息中不应有 running 状态的工具调用（流已结束）
          status: 'completed',
        })
      } catch {
        // skip malformed tool_call parts
      }
    }
    if (toolCalls.length > 0) {
      msg.metadata = { ...msg.metadata, toolCalls }
    }
    msg.contentParts = msg.contentParts.filter(p => p.type !== 'tool_call')
  }

  // 历史消息的 metadata.toolCalls 也需要清理 running 状态
  if (msg.metadata?.toolCalls) {
    const cleaned = msg.metadata.toolCalls.map((tc: ToolCallMeta) => ({
      ...tc,
      status: tc.status === 'running' ? 'completed' as const : tc.status,
    }))
    msg.metadata = { ...msg.metadata, toolCalls: cleaned }
  }

  if (msg.status === 'generating') msg.status = 'failed'
  // interrupted 是合法的历史状态（interrupt-with-followup），不映射为 stopped
  if (!msg.status) msg.status = 'completed'

  // 从 file/image/video contentParts 恢复 attachments（历史消息 API 不返回单独的 attachments 字段）
  const fileParts = msg.contentParts.filter(p => (p.type === 'file' || p.type === 'image' || p.type === 'video') && p.fileUrl)
  if (fileParts.length > 0 && (!msg.attachments || msg.attachments.length === 0)) {
    msg.attachments = fileParts.map(p => ({
      name: p.fileName || 'unknown',
      size: typeof p.fileSize === 'number' ? p.fileSize : Number(p.fileSize) || 0,
      url: p.fileUrl!,
      storedName: p.storedName || '',
      path: p.path || '',
      contentType: p.contentType,
    }))
  }

  // 从持久化的 [错误] 文本重建 errorInfo，使刷新后也能显示错误卡片
  if (msg.role === 'assistant' && !msg.errorInfo) {
    const text = msg.content || msg.contentParts?.find(p => p.type === 'text')?.text || ''
    const rebuilt = reconstructErrorInfo(text)
    if (rebuilt) {
      msg.errorInfo = rebuilt
      msg.status = 'failed'
    }
  }

  return msg
}

function parseThinkingContent(raw: string): { content: string; thinking: string; hasThinking: boolean } {
  if (!raw) return { content: '', thinking: '', hasThinking: false }

  const normalized = raw.replace(/<thinking>/gi, '<think>').replace(/<\/thinking>/gi, '</think>')
  const thinkingParts: string[] = []
  const content = normalized.replace(/<think>([\s\S]*?)<\/think>/gi, (_, thinkingText: string) => {
    const cleanText = thinkingText.trim()
    if (cleanText) thinkingParts.push(cleanText)
    return ''
  }).trim()

  return {
    content,
    thinking: thinkingParts.join('\n\n').trim(),
    hasThinking: thinkingParts.length > 0,
  }
}

function handleCodeCopy(e: MouseEvent) {
  // Mermaid download button shares the same global click delegation. Handle
  // it first so the SVG export beats the copy-button selector below if the
  // user happens to click in an area where both ancestors are reachable.
  if (handleMermaidDownload(e)) return
  const btn = (e.target as HTMLElement).closest('.code-block__copy') as HTMLElement | null
  if (!btn) return
  // The copy button now sits inside <details><summary> for collapsible code
  // blocks. Without preventDefault the click would also toggle the details
  // open state — a regression introduced when we wrapped long blocks in
  // <details>. stopPropagation guards against any future ancestor handlers.
  e.preventDefault()
  e.stopPropagation()
  const encoded = btn.getAttribute('data-code')
  if (!encoded) return
  const code = decodeURIComponent(encoded)
  copyToClipboard(code).then(() => {
    btn.classList.add('copied')
    const textEl = btn.querySelector('.code-block__copy-text')
    if (textEl) textEl.textContent = t('chat.copied')
    setTimeout(() => {
      btn.classList.remove('copied')
      if (textEl) textEl.textContent = t('chat.copy')
    }, 1500)
  }).catch(() => {
    mcToast.error(t('chat.copyFailed'))
  })
}
</script>

<style scoped>
.cron-running-bar {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 16px;
  margin: 0 12px;
  background: var(--mc-warning-bg, rgba(255, 159, 67, 0.08));
  border: 1px solid var(--mc-warning, rgba(255, 159, 67, 0.35));
  border-radius: 10px;
  color: var(--mc-text-primary);
  font-size: 13px;
}
.cron-running-item {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cron-running-spinner {
  display: inline-block;
  font-size: 16px;
  animation: cron-spin 1.6s linear infinite;
}
@keyframes cron-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.cron-running-text { line-height: 1.4; }
.cron-running-meta { color: var(--mc-text-secondary); margin-left: 4px; }

.chat-console-shell {
  background: transparent;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.chat-console-frame {
  height: min(calc(100vh - 28px), 100%);
  min-height: 0;
  overflow: hidden;
}

.chat-layout {
  display: flex;
  height: 100%;
  overflow: hidden;
  min-height: 0;
}

.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: linear-gradient(180deg, var(--mc-chat-header-bg), var(--mc-chat-bg));
  position: relative;
  min-height: 0;
}

/* 拖拽上传遮罩 */
.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(217, 119, 87, 0.06);
  backdrop-filter: blur(2px);
}

.drop-overlay__content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 40px 60px;
  border: 2px dashed var(--mc-primary, #D97757);
  border-radius: 16px;
  background: var(--mc-bg-elevated, #f8fafc);
  color: var(--mc-primary, #D97757);
  font-size: 16px;
  font-weight: 500;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: linear-gradient(180deg, var(--mc-panel-raised), var(--mc-surface-overlay));
  border-bottom: 1px solid var(--mc-border);
  min-height: 52px;
  backdrop-filter: blur(12px);
  gap: 10px;
}

.chat-header-left {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 10px;
}

.chat-header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.chat-stage-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.chat-stage-kicker {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--mc-accent);
}

.agent-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: var(--mc-primary-bg);
  border-radius: 999px;
  max-width: 100%;
}

.agent-badge-icon {
  display: flex;
  align-items: center;
  font-size: 14px;
}

.agent-badge-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.agent-badge-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--mc-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.2;
}


.status-dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; margin-left: 2px;
  transition: background 0.3s;
}
.status-idle { background: #34d399; box-shadow: 0 0 4px rgba(52, 211, 153, 0.5); }
.status-streaming { background: #fbbf24; box-shadow: 0 0 4px rgba(251, 191, 36, 0.5); animation: pulse-dot 1.2s infinite; }
.status-error { background: #f87171; box-shadow: 0 0 4px rgba(248, 113, 113, 0.5); }
@keyframes pulse-dot { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }

.no-agent-hint {
  font-size: 13px;
  color: var(--mc-text-tertiary);
}

/* Model selector */
/* Model selector styles moved to ModelSelector.vue */

/* Header overflow menu */
.header-overflow-wrap {
  position: relative;
}

.header-btn {
  width: 30px;
  height: 30px;
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

.header-btn:hover {
  border-color: var(--mc-danger);
  color: var(--mc-danger);
}

.model-prompt {
  margin: 24px auto 0;
  max-width: 540px;
  padding: 20px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 16px;
  text-align: center;
  box-shadow: 0 8px 24px rgba(124, 63, 30, 0.06);
}

.model-prompt-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin-bottom: 8px;
}

.model-prompt-desc {
  font-size: 14px;
  color: var(--mc-text-secondary);
  line-height: 1.6;
  margin-bottom: 16px;
}

.btn-primary {
  padding: 8px 16px;
  background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover));
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-primary:hover {
  background: var(--mc-primary-hover);
}

/* Issue #81: side-by-side primary + secondary actions in the model prompt. */
.model-prompt-actions {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.btn-secondary {
  padding: 8px 14px;
  background: transparent;
  color: var(--mc-text-primary);
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.btn-secondary:hover {
  background: var(--mc-panel-raised);
  border-color: var(--mc-primary);
}

/* ===== 移动端元素（桌面端隐藏） ===== */
.conv-backdrop {
  display: none;
}

.conv-toggle-btn {
  display: none;
}

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  .chat-console-shell {
    padding: 0 !important;
    height: 100dvh !important;
    height: 100vh !important;
    overflow: hidden !important;
    min-height: 0 !important;
  }

  .chat-console-frame {
    height: 100% !important;
    min-height: 0 !important;
    overflow: hidden !important;
    border-radius: 0;
    border: none;
  }

  .conv-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 99;
    background: rgba(0, 0, 0, 0.3);
  }

  .conv-toggle-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border: 1px solid var(--mc-border);
    background: var(--mc-bg-elevated);
    border-radius: 6px;
    cursor: pointer;
    color: var(--mc-text-secondary);
    flex-shrink: 0;
    transition: all 0.15s;
  }

  .conv-toggle-btn:hover {
    border-color: var(--mc-primary);
    color: var(--mc-primary);
  }

  .chat-header {
    padding: 9px 12px;
    gap: 8px;
  }

  .agent-badge {
    padding: 4px 8px;
  }

  .chat-stage-kicker {
    display: none;
  }

  .agent-badge-text {
    display: none;
  }

  .model-select-trigger {
    max-width: 160px;
  }

  .model-dropdown {
    min-width: 200px;
  }

  .drop-overlay__content {
    padding: 24px 32px;
    font-size: 14px;
  }
}

@media (max-width: 480px) {
  .chat-header {
    padding: 6px 8px;
    min-height: 44px;
  }

  .chat-header-right {
    gap: 4px;
  }

  .model-select-trigger {
    max-width: 120px;
    height: 30px;
    padding: 0 8px;
    font-size: 12px;
  }

  .header-btn {
    width: 28px;
    height: 28px;
  }
}

</style>
