<template>
  <div class="mc-page-shell chat-console-shell">
    <div class="mc-page-frame chat-console-frame">
      <div class="chat-layout mc-surface-card">
        <!-- 移动端会话面板遮罩 -->
        <Transition name="fade">
          <div v-if="isMobile && convPanelOpen" class="conv-backdrop" @click="convPanelOpen = false"></div>
        </Transition>

    <!-- 会话侧边栏 -->
    <div class="conversation-panel" :class="{ 'mobile-open': convPanelOpen, 'conv-collapsed': convPanelCollapsed && !isMobile }">
      <div class="panel-header">
        <div v-if="!convPanelCollapsed || isMobile" class="panel-header-copy">
          <div class="panel-kicker">{{ $t('nav.chat') }}</div>
          <h2 class="panel-title">{{ $t('chat.conversations') }}</h2>
        </div>
        <button class="new-chat-btn" @click="newConversation" :title="`${$t('chat.newChat')} (⌘N)`">
          <el-icon><Plus /></el-icon>
        </button>
      </div>
      <!-- 折叠切换按钮 -->
      <button v-if="!isMobile" class="conv-collapse-btn" @click="toggleConvPanel" :title="convPanelCollapsed ? $t('common.expandSidebar') : $t('common.collapseSidebar')">
        <svg v-if="!convPanelCollapsed" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
        <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
      </button>

      <div class="agent-selector">
        <button class="agent-select-trigger" @click="agentDropdownOpen = !agentDropdownOpen" :title="`${$t('chat.selectAgent')} (⌘K)`">
          <span class="agent-select-trigger__icon" :style="{ color: agentIconColor(currentAgent?.icon) }"><SkillIcon :value="currentAgent?.icon" :size="24" :fallback="'🤖'" /></span>
          <span v-if="!convPanelCollapsed || isMobile" class="agent-select-trigger__name">{{ currentAgent?.name || $t('chat.selectAgent') }}</span>
          <svg v-if="!convPanelCollapsed || isMobile" class="agent-select-trigger__arrow" :class="{ open: agentDropdownOpen }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
        </button>
        <Transition name="fade">
          <div v-if="agentDropdownOpen" class="agent-dropdown-backdrop" @click="agentDropdownOpen = false"></div>
        </Transition>
        <Transition name="agent-dropdown">
          <div v-if="agentDropdownOpen" class="agent-dropdown">
            <div
              v-for="agent in agents"
              :key="agent.id"
              class="agent-dropdown-item"
              :class="{ active: String(agent.id) === String(selectedAgentId) }"
              @click="selectAgent(agent)"
            >
              <span class="agent-dropdown-item__icon" :style="{ color: agentIconColor(agent.icon) }"><SkillIcon :value="agent.icon" :size="18" :fallback="'🤖'" /></span>
              <div class="agent-dropdown-item__info">
                <span class="agent-dropdown-item__name">{{ agent.name }}</span>
                <span class="agent-dropdown-item__desc">{{ agentTagline(agent) }}</span>
              </div>
              <span v-if="String(agent.id) === String(selectedAgentId)" class="agent-dropdown-item__check">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
              </span>
            </div>
            <div v-if="agents.length === 0" class="agent-dropdown-empty">{{ $t('chat.loadingAgents') }}</div>
          </div>
        </Transition>
      </div>

      <div class="conversation-list">
        <template v-for="group in groupedConversations" :key="group.label">
          <div v-if="!convPanelCollapsed || isMobile" class="conv-group-title">{{ group.label }}</div>
          <div
            v-for="conv in group.items"
            :key="conv.conversationId"
            class="conv-item"
            :class="{
              active: currentConversationId === conv.conversationId,
              'is-running': conv.streamStatus === 'running',
            }"
            @click="selectConversation(conv)"
          >
            <div class="conv-icon">
              <img :src="channelIconUrl(conv.source)" width="14" height="14" alt="" />
              <span
                v-if="conv.streamStatus === 'running'"
                class="conv-running-dot"
                :title="$t('chat.streamGenerating')"
              ></span>
            </div>
            <div v-if="!convPanelCollapsed || isMobile" class="conv-info">
              <input
                v-if="renamingConvId === conv.conversationId"
                v-model="renameText"
                class="conv-title-input"
                @keydown.enter="confirmRename(conv)"
                @keydown.escape="cancelRename"
                @blur="confirmRename(conv)"
                @click.stop
                ref="renameInputRef"
              />
              <div v-else class="conv-title" @dblclick.stop="startRename(conv)">
                <span>{{ conv.title }}</span>
                <!-- Unread dot for the unified tasks conversation: when a
                     cron run lands but the user hasn't opened the conversation
                     since, show a small accent dot. Avoids needing a server-side
                     last-viewed table for MVP — localStorage tracks per-conv
                     last-view timestamp, sidebar compares to lastActiveTime. -->
                <span
                  v-if="hasUnread(conv)"
                  class="conv-unread-dot"
                  :title="$t('chat.hasUnread', '有新内容')"
                ></span>
                <span
                  v-if="conv.streamStatus === 'running'"
                  class="conv-running-badge"
                  :title="$t('chat.streamGenerating')"
                >
                  <span class="conv-running-badge-pulse"></span>
                  {{ $t('chat.streamGenerating') }}
                </span>
              </div>
              <div class="conv-meta">
                <span>{{ $t('chat.messages', { count: conv.messageCount }) }}</span>
                <span class="conv-dot">·</span>
                <span>{{ formatConversationTime(conv.lastActiveTime) }}</span>
              </div>
            </div>
            <button v-if="!convPanelCollapsed || isMobile" class="conv-delete" @click.stop="confirmDeleteConversation(conv.conversationId)" :title="$t('common.delete')">
              <el-icon><Delete /></el-icon>
            </button>
          </div>
        </template>

        <div v-if="conversations.length === 0" class="empty-convs">
          <p>{{ $t('chat.noConversations') }}</p>
          <p>{{ $t('chat.startNewChat') }}</p>
        </div>
      </div>
    </div>

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
                <span v-if="currentAgentTagline" class="agent-badge-tagline">{{ currentAgentTagline }}</span>
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
            <button class="header-btn" @click="headerMenuOpen = !headerMenuOpen" :title="$t('common.more') || 'More'">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="12" cy="19" r="1.5"/></svg>
            </button>
            <Transition name="fade">
              <div v-if="headerMenuOpen" class="header-menu-backdrop" @click="headerMenuOpen = false"></div>
            </Transition>
            <Transition name="agent-dropdown">
              <div v-if="headerMenuOpen" class="header-menu">
                <button class="header-menu-item" @click="headerMenuOpen = false; goToModelSettings()">
                  <el-icon><Setting /></el-icon>
                  <span>{{ $t('chat.configModel') }}</span>
                </button>
                <div class="header-menu-divider"></div>
                <button class="header-menu-item header-menu-item--danger" @click="handleClearMessages">
                  <el-icon><Delete /></el-icon>
                  <span>{{ $t('chat.clearMessages') }}</span>
                </button>
              </div>
            </Transition>
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
import { ElMessage } from 'element-plus'
import { mcConfirm } from '@/components/common/useConfirm'
import { ChatDotRound, Delete, Plus, Setting, UploadFilled } from '@element-plus/icons-vue'
import { conversationApi, agentApi, modelApi, chatApi, cronJobApi } from '@/api/index'
import { channelIconUrl } from '@/utils/channelSource'
import { copyToClipboard } from '@/utils/clipboard'
import { useChat } from '@/composables/chat/useChat'
import { reconstructErrorInfo } from '@/types/chatError'
import { reconcileMessages, extractMessages } from '@/utils/messageReconcile'
import type { Conversation, Agent, ModelConfig, ProviderInfo, ActiveModelsInfo, ChatAttachment, MessageContentPart, Message, ToolCallMeta, StreamPhase } from '@/types'

// 导入组件化组件
import MessageList from '@/components/chat/MessageList.vue'
import RecoverableModelBanner from '@/components/chat/RecoverableModelBanner.vue'
import SkillIcon from '@/components/common/SkillIcon.vue'
import { parsePrompt, deriveTagline } from '@/utils/agentPromptProfile'
import { agentIconColor } from '@/utils/agentIconColor'
import ChatInput from '@/components/chat/ChatInput.vue'
import MultimodalRoutingHint from '@/components/chat/MultimodalRoutingHint.vue'
import StreamLoadingBar from '@/components/chat/StreamLoadingBar.vue'
import TalkMode from '@/components/chat/TalkMode.vue'
import ModelSelector from '@/components/chat/ModelSelector.vue'
import { useEChartsRenderer } from '@/composables/useEChartsRenderer'
import { useKatexRenderer } from '@/composables/useKatexRenderer'
import { useMermaidRenderer, handleMermaidDownload } from '@/composables/useMermaidRenderer'

// ============ Talk Mode ============
const showTalkMode = ref(false)

// ============ 移动端 & 响应式状态 ============
const isMobile = ref(false)
const convPanelOpen = ref(false)
const convPanelCollapsed = ref(localStorage.getItem('mc-conv-collapsed') === 'true')
let mobileQuery: MediaQueryList | null = null
let mediumQuery: MediaQueryList | null = null
const userExplicitConvCollapse = ref(localStorage.getItem('mc-conv-collapsed') === 'true')

function handleMobileChange(e: MediaQueryListEvent | MediaQueryList) {
  isMobile.value = e.matches
  if (!e.matches) convPanelOpen.value = false
}

function handleConvMediumChange(e: MediaQueryListEvent | MediaQueryList) {
  if (e.matches && !userExplicitConvCollapse.value) {
    convPanelCollapsed.value = true
  } else if (!e.matches && !userExplicitConvCollapse.value) {
    convPanelCollapsed.value = false
  }
}

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
// Issue #81 v2 R2: split the single showModelPrompt boolean into two flags so
// the chat surface can either hard-block (blockingPrompt) or warn but let the
// backend fallback chain take over (recoverablePrompt). Driven by
// recomputePromptFlags() — see the watcher below.
const blockingPrompt = ref(false)
const recoverablePrompt = ref(false)
const recoverableDismissed = ref(false)
const defaultModel = ref<ModelConfig | null>(null)
const providers = ref<ProviderInfo[]>([])
const activeModels = ref<ActiveModelsInfo | null>(null)
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
const agentDropdownOpen = ref(false)

const headerMenuOpen = ref(false)

function selectAgent(agent: Agent) {
  agentDropdownOpen.value = false
  if (String(agent.id) !== String(selectedAgentId.value)) {
    selectedAgentId.value = agent.id
    newConversation()
  }
}

async function selectModel(value: string) {
  const [providerId, model] = value.split('::')
  if (!providerId || !model) return
  modelSaving.value = true
  try {
    const res: any = await modelApi.setActive({ providerId, model })
    activeModels.value = res.data || { activeLlm: { providerId, model } }
    await loadModelState()
  } catch (e) {
    ElMessage.error(t('chat.switchModelFailed'))
  } finally {
    modelSaving.value = false
  }
}

function handleClearMessages() {
  headerMenuOpen.value = false
  clearMessages()
}

// Conversation rename
const renamingConvId = ref('')
const renameText = ref('')
const renameInputRef = ref<HTMLInputElement | null>(null)

function startRename(conv: Conversation) {
  renamingConvId.value = conv.conversationId
  renameText.value = conv.title || ''
  nextTick(() => {
    renameInputRef.value?.focus()
    renameInputRef.value?.select()
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
    // revert on fail — reload
    await loadConversations()
  }
}

function cancelRename() {
  renamingConvId.value = ''
}

// Delete with confirmation
async function confirmDeleteConversation(conversationId: string) {
  const ok = await mcConfirm({
    title: t('common.confirm'),
    message: t('chat.deleteConfirm') || 'Delete this conversation?',
    tone: 'danger',
  })
  if (ok) deleteConversation(conversationId)
}

// 拖拽上传
const isDragging = ref(false)
let dragCounter = 0

function onDragEnter(e: DragEvent) {
  dragCounter++
  if (e.dataTransfer?.types.includes('Files')) {
    isDragging.value = true
  }
}

function onDragLeave() {
  dragCounter--
  if (dragCounter === 0) {
    isDragging.value = false
  }
}

async function onDrop(e: DragEvent) {
  dragCounter = 0
  isDragging.value = false

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

/** Tagline derived from the agent's role/goal — same source of truth as the
 *  Agents page card, so the chat header reads the employee identically.
 *  Falls back to the agent's description when no triad is set. */
function agentTagline(agent: Agent): string {
  const profile = parsePrompt(agent.systemPrompt)
  return deriveTagline(profile, agent.description) || (agent.description || '')
}

const currentAgentTagline = computed(() =>
  currentAgent.value ? agentTagline(currentAgent.value) : '',
)
/** Human label for the agent's runtime mode — surfaces in the badge tooltip
 *  only, never in the visible header. */
const currentAgentRuntimeMode = computed(() => {
  const a = currentAgent.value
  if (!a) return ''
  return a.agentType === 'react' ? t('agents.types.react') : t('agents.types.planExecute')
})

// 按日期分组的会话列表
// Per-conversation last-viewed timestamp store (localStorage-backed, MVP).
// Keyed by conversationId. Updated when user opens a conversation; read by
// hasUnread() to decide whether to render the small accent dot in the sidebar.
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
function hasUnread(conv: Conversation): boolean {
  // Only the unified tasks_<wsId> conversation gets the unread treatment.
  // Regular chats have explicit user attention via the streaming bubble itself,
  // and IM-mirror conversations carry their own platform badges in IM apps.
  if (!conv.conversationId || !conv.conversationId.startsWith('tasks_')) return false
  if (!conv.lastActiveTime) return false
  const lastActive = new Date(conv.lastActiveTime).getTime()
  if (!Number.isFinite(lastActive)) return false
  let viewed = 0
  try {
    viewed = Number(localStorage.getItem(VIEWED_KEY_PREFIX + conv.conversationId) || '0')
  } catch {
    // Treat as never-viewed when storage is unavailable.
  }
  // Currently-active conversation is implicitly read.
  if (currentConversationId.value === conv.conversationId) return false
  return lastActive > viewed
}

const groupedConversations = computed(() => {
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const yesterdayStart = todayStart - 86400000
  const last7Start = todayStart - 7 * 86400000

  // Pinned group always sits at the top so the unified cron output (tasks_<wsId>)
  // is reachable in one glance even after a busy day pushes other conversations
  // ahead of it. Only the unified-cron conversation pattern is pinned for now;
  // we'll generalize when we have other always-visible conversations.
  const pinned: Conversation[] = []
  const groups: { label: string; items: Conversation[] }[] = [
    { label: t('chat.datePinned', '置顶'), items: pinned },
    { label: t('chat.dateToday'), items: [] },
    { label: t('chat.dateYesterday'), items: [] },
    { label: t('chat.dateLast7Days'), items: [] },
    { label: t('chat.dateEarlier'), items: [] },
  ]

  for (const conv of conversations.value) {
    if (conv.conversationId && conv.conversationId.startsWith('tasks_')) {
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

const currentRuntimeModel = computed(() => {
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
  return match?.label || ''
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
function handleKeyboardShortcuts(e: KeyboardEvent) {
  const mod = e.metaKey || e.ctrlKey
  if (mod && e.key === 'n') {
    e.preventDefault()
    newConversation()
    chatInputRef.value?.focus?.()
  }
  if (mod && e.key === 'k') {
    e.preventDefault()
    agentDropdownOpen.value = !agentDropdownOpen.value
  }
}

// 轮询定时器：让 ChatConsole 能实时感知外部渠道（WeChat/DingTalk/…）推进来的新消息，
// 无需 F5 即可看到侧栏列表更新和选中会话的消息/流状态。
let activityPollTimer: number | null = null
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
  try {
    await loadConversations()
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
}

onMounted(async () => {
  document.addEventListener('keydown', handleKeyboardShortcuts)
  document.addEventListener('click', handleCodeCopy)
  startECharts()
  startKatex()
  startMermaid()
  mobileQuery = window.matchMedia('(max-width: 768px)')
  handleMobileChange(mobileQuery)
  mobileQuery.addEventListener('change', handleMobileChange)
  mediumQuery = window.matchMedia('(max-width: 1200px)')
  handleConvMediumChange(mediumQuery)
  mediumQuery.addEventListener('change', handleConvMediumChange)
  await Promise.all([loadAgents(), loadModelState(), loadConversations()])
  await hydrateStateFromRoute()
  activityPollTimer = window.setInterval(pollActivity, ACTIVITY_POLL_MS)
  elapsedTickTimer = window.setInterval(() => {
    if (activeCronRuns.value.length > 0) elapsedNow.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleKeyboardShortcuts)
  document.removeEventListener('click', handleCodeCopy)
  disposeECharts()
  disposeKatex()
  disposeMermaid()
  mobileQuery?.removeEventListener('change', handleMobileChange)
  mediumQuery?.removeEventListener('change', handleConvMediumChange)
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
  void hydrateStateFromRoute()
})

watch([selectedAgentId, currentConversationId], () => {
  syncRouteState()
})

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
    ElMessage.error(t('chat.loadAgentsFailed'))
  }
}

async function loadModelState() {
  try {
    const [defaultRes, providersRes, activeRes]: any = await Promise.all([
      modelApi.getDefault(),
      modelApi.listProviders(),
      modelApi.getActive(),
    ])
    defaultModel.value = defaultRes.data || null
    providers.value = providersRes.data || []
    activeModels.value = activeRes.data || null
    recomputePromptFlags()
  } catch (e) {
    ElMessage.error(t('chat.loadModelFailed'))
    blockingPrompt.value = true
    recoverablePrompt.value = false
  }
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
    ElMessage.error(t('chat.loadConversationsFailed'))
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
    ElMessage.error(t('chat.loadMessagesFailed'))
  }
}

function newConversation() {
  // Creating a new chat is just local navigation. Keep any previous backend
  // run alive so the user can return and reconnect to it later.
  resetForNewConversation()
  currentConversationId.value = `conv_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  messages.value = []
}

async function deleteConversation(conversationId: string) {
  try {
    await conversationApi.delete(conversationId)
    conversations.value = conversations.value.filter(c => c.conversationId !== conversationId)
    if (currentConversationId.value === conversationId) {
      resetStreamingState()
      messages.value = []
      currentConversationId.value = ''
    }
  } catch (e) {
    ElMessage.error(t('chat.deleteConversationFailed'))
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

// onAgentChange removed — replaced by selectAgent()

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
      ElMessage.warning('No active conversation')
      inputText.value = ''
      chatInputRef.value?.clear?.()
      return
    }

    // 检查是否有 pending approval
    const pendingMsg = messages.value.findLast(
      m => m.role === 'assistant' && (m as any).metadata?.pendingApproval?.status === 'pending_approval'
    )
    if (!pendingMsg) {
      ElMessage.warning('No pending approval to process')
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
      ElMessage.error(e?.message || 'Approval failed')
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

// 重连到运行中的流
async function reconnectStream(conversationId: string) {
  if (isGenerating.value) return
  try {
    await reconnectChatStream(conversationId)
  } catch (e) {
    console.error('[ChatConsole] Reconnect failed:', e)
    ElMessage.warning(t('chat.reconnectFailed') || 'Stream reconnection failed')
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
    ElMessage.error(t('chat.uploadFailed'))
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

function formatConversationTime(time?: string) {
  if (!time) return t('chat.timeJustNow')
  const date = new Date(time)
  const diff = Date.now() - date.getTime()
  if (diff < 60 * 60 * 1000) return t('chat.timeMinutesAgo', { n: Math.max(1, Math.floor(diff / (60 * 1000))) })
  if (diff < 24 * 60 * 60 * 1000) return t('chat.timeHoursAgo', { n: Math.floor(diff / (60 * 60 * 1000)) })
  return date.toLocaleDateString()
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
    ElMessage.error(t('chat.copyFailed'))
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

.conversation-panel.conv-collapsed .agent-select-trigger {
  justify-content: center;
  padding: 8px;
}

.conversation-panel.conv-collapsed .agent-dropdown {
  position: fixed;
  top: auto;
  left: 62px;
  right: auto;
  min-width: 260px;
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

.agent-select-trigger {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  font-size: 13px;
  color: var(--mc-text-primary);
  background: var(--mc-bg-sunken);
  cursor: pointer;
  outline: none;
  transition: all 0.15s;
}

.agent-select-trigger:hover {
  border-color: var(--mc-primary);
  background: var(--mc-bg-elevated);
}

.agent-select-trigger__icon {
  font-size: 18px;
  line-height: 1;
}

.agent-select-trigger__name {
  flex: 1;
  text-align: left;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.agent-select-trigger__arrow {
  flex-shrink: 0;
  color: var(--mc-text-tertiary);
  transition: transform 0.2s;
}

.agent-select-trigger__arrow.open {
  transform: rotate(180deg);
}

.agent-dropdown-backdrop,
.model-dropdown-backdrop,
.header-menu-backdrop {
  position: fixed;
  inset: 0;
  z-index: 99;
}

.agent-dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 12px;
  right: 12px;
  min-width: 240px;
  z-index: 100;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 14px;
  padding: 6px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
  max-height: 320px;
  overflow-y: auto;
}

.agent-dropdown-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.12s;
}

.agent-dropdown-item:hover {
  background: var(--mc-bg-sunken);
}

.agent-dropdown-item.active {
  background: var(--mc-primary-bg);
}

.agent-dropdown-item__icon {
  font-size: 24px;
  line-height: 1;
  flex-shrink: 0;
}

.agent-dropdown-item__info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.agent-dropdown-item__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--mc-text-primary);
}

.agent-dropdown-item__desc {
  font-size: 11px;
  color: var(--mc-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.agent-dropdown-item__check {
  flex-shrink: 0;
  color: var(--mc-primary);
}

.agent-dropdown-empty {
  padding: 16px;
  text-align: center;
  font-size: 13px;
  color: var(--mc-text-tertiary);
}

.agent-dropdown-enter-active {
  transition: all 0.15s ease-out;
}
.agent-dropdown-leave-active {
  transition: all 0.1s ease-in;
}
.agent-dropdown-enter-from {
  opacity: 0;
  transform: translateY(-6px) scale(0.97);
}
.agent-dropdown-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.98);
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

.conv-item:hover .conv-delete {
  opacity: 1;
}

.conv-icon {
  color: var(--mc-text-tertiary);
  flex-shrink: 0;
  position: relative;
}

.conv-item.active .conv-icon {
  color: var(--mc-primary);
}

/* 正在执行：图标右上角脉冲小点（折叠与展开态均可见） */
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

.conv-item.is-running {
  background: color-mix(in srgb, #fbbf24 8%, transparent);
}

.conv-item.is-running:hover {
  background: color-mix(in srgb, #fbbf24 14%, var(--mc-bg-sunken));
}

.conv-item.is-running.active {
  background: var(--mc-primary-bg);
}

/* 展开态：标题右侧"生成中..."小徽章 */
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

/* 标题文本本身承担省略号；flex 父级上的 overflow:hidden 会阻止 ellipsis 正常工作 */
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

.conv-delete {
  opacity: 0;
  width: 22px;
  height: 22px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  padding: 0;
  flex-shrink: 0;
  transition: all 0.15s;
}

.conv-delete:hover {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}

.empty-convs {
  text-align: center;
  padding: 32px 16px;
  color: var(--mc-text-tertiary);
  font-size: 13px;
  line-height: 1.8;
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

/* The tagline answers "what does this employee do" — runtime mode lives in
   the badge tooltip, not on screen, so we don't compete for attention. */
.agent-badge-tagline {
  font-size: 11px;
  color: var(--mc-text-secondary);
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

.header-menu {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  z-index: 100;
  min-width: 180px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  padding: 4px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}

.header-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 9px 12px;
  border: none;
  background: none;
  border-radius: 8px;
  font-size: 13px;
  color: var(--mc-text-primary);
  cursor: pointer;
  transition: background 0.12s;
}

.header-menu-item:hover {
  background: var(--mc-bg-sunken);
}

.header-menu-item--danger:hover {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}

.header-menu-divider {
  height: 1px;
  background: var(--mc-border-light);
  margin: 2px 8px;
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
