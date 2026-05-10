<template>
  <div
    class="message-wrapper"
    :class="[role, { 'is-last': isLast }]"
    :data-role="role"
    :data-status="status"
    @mouseenter="hovered = true"
    @mouseleave="hovered = false"
  >
    <!-- 头像 -->
    <div class="msg-avatar" :class="`${role}-avatar`">
      <slot name="avatar">
        <img v-if="role === 'assistant'" src="/logo/mateclaw_logo_s.png" alt="" class="avatar-logo" />
        <span v-else>{{ avatarIcon }}</span>
      </slot>
    </div>

    <!-- 消息体 -->
    <div class="msg-body" :class="`${role}-body`">
      <div class="msg-bubble" :class="`${role}-bubble`">
        <!-- ===== 分段式渲染模式（Claude Code 风格）===== -->
        <template v-if="useSegmentedView">
          <div class="segments-view">
            <!-- 计划步骤面板（始终显示在 segments 之上） -->
            <PlanStepsPanel v-if="planMeta" :plan="planMeta" :is-generating="isGenerating" />
            <template v-for="iter in groupedIterations" :key="iter.key">
              <!-- Iteration interrupted before any output landed — surface a chip
                   so the user knows the agent moved on instead of silently
                   skipping a turn. -->
              <div v-if="iter.empty" class="iter-empty-chip">
                <el-icon><WarningFilled /></el-icon>
                <span>{{ $t('chat.iterationEmpty', { index: iter.index + 1 }) }}</span>
              </div>
              <template v-else>
                <ThinkingSegment v-for="t in iter.thinkings" :key="t.id" :segment="t" />
                <ToolCallSegment v-for="tool in iter.tools" :key="tool.id" :segment="tool" />
                <template v-for="c in iter.contents" :key="c.id">
                  <div v-if="c.repetitionWarning" class="repetition-warning">
                    <el-icon><WarningFilled /></el-icon>
                    <span class="repetition-warning__text">{{ $t('chat.contentRepetitionWarning') }}</span>
                    <span v-if="c.truncatedChars" class="repetition-warning__meta">({{ c.truncatedChars }} chars)</span>
                  </div>
                  <ContentSegment :segment="c" :show-cursor="showCursor && c.status === 'running'" />
                </template>
              </template>
            </template>
          </div>
        </template>

        <!-- ===== 传统合并渲染模式（降级兼容）===== -->
        <template v-else>

        <!-- 思考面板 -->
        <div v-if="showThinkingPanel" class="thinking-section">
          <button class="thinking-toggle" type="button" @click="toggleThinking">
            <span class="thinking-toggle__indicator" :class="{ active: isGenerating && !hasContent }">
              <el-icon><Opportunity /></el-icon>
            </span>
            <span class="thinking-toggle__label">{{ $t('chat.thinking') }}</span>
            <span class="thinking-toggle__duration" v-if="thinkingDuration">{{ thinkingDuration }}</span>
            <span class="thinking-toggle__arrow" :class="{ expanded: localThinkingExpanded }">
              <el-icon><ArrowDown /></el-icon>
            </span>
          </button>

          <!-- 思考内容（带折叠动画） -->
          <Transition name="thinking-slide">
            <div
              v-if="localThinkingExpanded"
              class="thinking-content markdown-body"
              v-html="renderedThinkingContent"
            ></div>
          </Transition>
        </div>

        <!-- 执行过程面板（折叠式） -->
        <div v-if="showExecutionPanel" class="execution-section">
          <button class="execution-toggle" type="button" @click="executionExpanded = !executionExpanded">
            <span class="execution-toggle__indicator" :class="{ active: isGenerating }">
              <el-icon><Tools /></el-icon>
            </span>
            <span class="execution-toggle__label">{{ executionPhaseLabel }}</span>
            <span class="execution-toggle__count" v-if="toolCallsMeta.length">{{ toolCallsMeta.length }} calls</span>
            <span class="execution-toggle__arrow" :class="{ expanded: executionExpanded }">
              <el-icon><ArrowDown /></el-icon>
            </span>
          </button>

          <Transition name="thinking-slide">
            <div v-if="executionExpanded" class="execution-content">
              <!-- Plan 步骤进度 -->
              <PlanStepsPanel v-if="planMeta" :plan="planMeta" :is-generating="isGenerating" />

              <!-- 工具调用列表 -->
              <div v-if="toolCallsMeta.length" class="tool-calls">
                <div
                  v-for="(tc, i) in toolCallsMeta"
                  :key="i"
                  class="tool-call"
                  :class="{ 'tool-call--running': tc.status === 'running', 'tool-call--awaiting': tc.status === 'awaiting_approval', 'tool-call--error': tc.status === 'completed' && tc.success === false }"
                >
                  <span class="tool-call__status">
                    <el-icon v-if="tc.status === 'running'" class="spin"><Loading /></el-icon>
                    <el-icon v-else-if="tc.status === 'awaiting_approval'" class="tc-icon--warning"><WarningFilled /></el-icon>
                    <el-icon v-else-if="tc.success !== false" class="tc-icon--success"><Select /></el-icon>
                    <el-icon v-else class="tc-icon--error"><CloseBold /></el-icon>
                  </span>
                  <span class="tool-call__name">{{ getToolLabel(tc.name) }}</span>
                  <span class="tool-call__args" v-if="tc.arguments">{{ truncateArgs(tc.arguments) }}</span>
                </div>
              </div>

              <div v-if="!toolCallsMeta.length && !planMeta" class="execution-empty">
                {{ currentPhaseName }}...
              </div>
            </div>
          </Transition>
        </div>

        <!-- 浏览器执行时间线 -->
        <BrowserTimeline v-if="browserActionsMeta.length" :actions="browserActionsMeta" />

        <!-- 工具审批状态（极简一行，操作在输入栏） -->
        <div v-if="pendingApproval" class="approval-inline">
          <el-icon class="approval-inline__icon"><WarningFilled /></el-icon>
          <span v-if="pendingApproval.status === 'pending_approval'" class="approval-inline__text">
            {{ $t('chat.approvalWaiting') }} <code>{{ getToolLabel(pendingApproval.toolName) }}</code>
          </span>
          <span v-else-if="pendingApproval.status === 'approved'" class="approval-inline__text approval-inline--approved">
            {{ $t('chat.approved') }}: <code>{{ getToolLabel(pendingApproval.toolName) }}</code>
          </span>
          <span v-else class="approval-inline__text approval-inline--denied">
            {{ $t('chat.denied') }}: <code>{{ getToolLabel(pendingApproval.toolName) }}</code>
          </span>
        </div>

        <!-- 主要内容 -->
        <div
          v-if="displayContent"
          class="msg-content"
          :class="{ 'with-cursor': showCursor }"
        >
          <!--
            User-authored messages render as plain text (no markdown) and
            auto-collapse beyond 8 lines. Assistant content goes through the
            normal markdown pipeline.
          -->
          <UserMessageContent v-if="role === 'user'" :content="displayContent" />
          <template v-else>
            <div class="markdown-body" v-html="renderedContent"></div>
            <TypingCursor v-if="showCursor" :typing="isGenerating" />
          </template>
        </div>


        <!-- 停止指示器 -->
        <div v-if="status === 'stopped' || status === 'interrupted'" class="stopped-indicator">
          <el-icon><CloseBold /></el-icon>
          <span>{{ status === 'interrupted' ? $t('chat.interrupted') : $t('chat.stopped') }}</span>
        </div>

        <!-- parse_error content block -->
        <div v-if="parseErrorText" class="parse-error-card">
          <el-icon class="parse-error-card__icon"><WarningFilled /></el-icon>
          <span class="parse-error-card__text">{{ parseErrorText }}</span>
        </div>

        <!-- 错误卡片 -->
        <div v-if="status === 'failed'" class="error-card">
          <div class="error-card__header">
            <el-icon class="error-card__icon"><WarningFilled /></el-icon>
            <span class="error-card__title">{{ errorTitle }}</span>
          </div>
          <p class="error-card__description">{{ errorDescription }}</p>
          <p class="error-card__action">{{ errorAction }}</p>
          <div class="error-card__footer">
            <span v-if="errorCode" class="error-card__code">{{ errorCode }}</span>
            <button v-if="errorRetryable" class="error-card__retry" type="button" @click="$emit('regenerate')">
              <el-icon><RefreshRight /></el-icon>
              {{ $t('chat.retry') }}
            </button>
          </div>
        </div>

        </template><!-- /传统合并渲染模式 -->

        <!--
          INCOMPLETE banner: graph emitted finishReason=incomplete after
          the thinking-only soft cap stopped the stream.
          Lives outside the segmented/traditional fork so both rendering
          modes show it. Click "regenerate" reuses the existing emit path
          that the error-card already relies on.
        -->
        <div v-if="isIncomplete" class="incomplete-card">
          <div class="incomplete-card__header">
            <el-icon class="incomplete-card__icon"><WarningFilled /></el-icon>
            <span class="incomplete-card__title">{{ $t('chat.incompleteTitle') }}</span>
          </div>
          <p class="incomplete-card__description">{{ $t('chat.incompleteDescription') }}</p>
          <div class="incomplete-card__footer">
            <button class="incomplete-card__retry" type="button" @click="$emit('regenerate')">
              <el-icon><RefreshRight /></el-icon>
              {{ $t('chat.incompleteRetry') }}
            </button>
          </div>
        </div>

        <!--
          EVIDENCE_INSUFFICIENT banner (info color, not warning). Run completed
          fully — the answer text is preserved above; the model just cited
          source files / classes it didn't actually open. Without an explicit
          card the trailing "[证据不足] …" line reads like a mid-answer cut.
          No regenerate button: the user typically wants to either accept
          the gap or follow up asking the model to read the listed files.
        -->
        <div v-if="isEvidenceInsufficient" class="evidence-card">
          <div class="evidence-card__header">
            <el-icon class="evidence-card__icon"><InfoFilled /></el-icon>
            <span class="evidence-card__title">{{ $t('chat.evidenceTitle') }}</span>
          </div>
          <p class="evidence-card__description">{{ $t('chat.evidenceDescription') }}</p>
        </div>

        <!--
          feedback_event card: recovery affordances for turns that ended
          in a non-transient error. Backend's NodeStreamingChatHelper
          handles transient TLS / IO retries silently; this card only
          appears for the residue (auth, billing, model-not-found, raw
          parse failures, etc.) that no amount of retry can fix without
          user input. Buttons are data-driven from the event's `actions`
          array so the backend can narrow the offering per error type
          without a frontend release.
        -->
        <div v-if="feedbackInfo" class="feedback-card">
          <div class="feedback-card__header">
            <el-icon class="feedback-card__icon"><WarningFilled /></el-icon>
            <span class="feedback-card__title">{{ $t('chat.feedback.title') }}</span>
          </div>
          <p class="feedback-card__description">{{ $t('chat.feedback.description') }}</p>
          <div class="feedback-card__actions">
            <button
              v-for="action in feedbackInfo.actions"
              :key="action"
              class="feedback-card__btn"
              :class="`feedback-card__btn--${action}`"
              type="button"
              @click="handleFeedbackAction(action)"
            >
              <el-icon v-if="action === 'retry' || action === 'regenerate'"><RefreshRight /></el-icon>
              {{ feedbackActionLabel(action) }}
            </button>
          </div>
        </div>

        <!-- 附件列表 -->
        <div v-if="attachments?.length" class="message-attachments">
          <div
            v-for="attachment in imageAttachments"
            :key="attachment.storedName"
            class="message-attachment-image"
          >
            <img
              :src="getDisplayUrl(attachment)"
              :alt="attachment.name"
              loading="lazy"
              @click="openImage(getDisplayUrl(attachment))"
            />
            <span class="message-attachment-image__name">{{ attachment.name }}</span>
          </div>
          <div
            v-for="attachment in videoAttachments"
            :key="attachment.storedName"
            class="message-attachment-video"
          >
            <video
              :src="getDisplayUrl(attachment)"
              controls
              preload="metadata"
              playsinline
            />
            <span class="message-attachment-video__name">{{ attachment.name }}</span>
          </div>
          <div
            v-for="attachment in audioAttachments"
            :key="'audio-' + attachment.storedName"
            class="message-attachment-audio"
          >
            <audio
              :src="getDisplayUrl(attachment)"
              controls
              preload="metadata"
            />
            <span class="message-attachment-audio__name">{{ attachment.name }}</span>
          </div>
          <!-- 3D model preview via @google/model-viewer Web Component
               (registered globally in src/main.ts; renders &lt;model-viewer&gt;
               as a custom HTML element). -->
          <div
            v-for="attachment in model3dAttachments"
            :key="'model3d-' + attachment.storedName"
            class="message-attachment-model3d"
          >
            <model-viewer
              :src="getDisplayUrl(attachment)"
              camera-controls
              auto-rotate
              shadow-intensity="1"
              exposure="1"
              alt="Generated 3D model"
              class="message-attachment-model3d__viewer"
            />
            <span class="message-attachment-model3d__name">{{ attachment.name }}</span>
          </div>
          <button
            v-for="attachment in fileAttachments"
            :key="attachment.storedName"
            class="message-attachment"
            type="button"
            @click="downloadFile(attachment)"
          >
            <el-icon class="message-attachment__icon"><Document /></el-icon>
            <span class="message-attachment__name">{{ attachment.name }}</span>
            <span class="message-attachment__meta">{{ formatFileSize(attachment.size) }}</span>
          </button>
        </div>
      </div>

      <!-- 消息操作栏：始终占位，hover 时显示 -->
        <div
          class="msg-actions"
          :class="{
            'msg-actions--right': role === 'user',
            'msg-actions--visible': showActions
          }"
        >
          <!-- 复制 -->
          <button
            class="action-btn"
            :class="{ copied: copyState === 'copied' }"
            type="button"
            :title="copyState === 'copied' ? $t('chat.copied') : $t('chat.copy')"
            @click="copyMessage"
          >
            <el-icon v-if="copyState !== 'copied'"><CopyDocument /></el-icon>
            <el-icon v-else><Select /></el-icon>
          </button>
          <!-- 朗读 TTS（仅 assistant） -->
          <button
            v-if="role === 'assistant' && !isGenerating"
            class="action-btn"
            :class="{ 'tts-playing': ttsState === 'playing' }"
            type="button"
            :title="ttsState === 'playing' ? $t('chat.ttsStop') : $t('chat.ttsPlay')"
            :disabled="ttsState === 'loading'"
            @click="handleTts"
          >
            <el-icon v-if="ttsState === 'loading'" class="tts-loading-icon"><Loading /></el-icon>
            <el-icon v-else-if="ttsState === 'playing'"><VideoPause /></el-icon>
            <el-icon v-else><Microphone /></el-icon>
          </button>
          <!-- 重新生成（仅 assistant） -->
          <button
            v-if="role === 'assistant' && !isGenerating"
            class="action-btn"
            type="button"
            :title="$t('chat.regenerate')"
            @click="$emit('regenerate')"
          >
            <el-icon><RefreshRight /></el-icon>
          </button>
          <!-- Reply model attribution (assistant only) -->
          <span
            v-if="role === 'assistant' && replyModel"
            class="action-model"
            :title="replyModelTitle"
          >{{ replyModel }}</span>
          <!-- Multimodal sidecar routing badge (assistant only, when sidecar fired) -->
          <span
            v-if="role === 'assistant' && routingBadge"
            class="action-routing"
            :title="routingBadge.tooltip"
          >🔀 {{ routingBadge.label }}</span>
          <!-- 时间戳（inline） -->
          <span class="action-time">{{ formattedTime }}</span>
        </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  ArrowDown,
  CloseBold,
  CopyDocument,
  Document,
  InfoFilled,
  Loading,
  Microphone,
  Opportunity,
  RefreshRight,
  Select,
  Tools,
  VideoPause,
  WarningFilled,
} from '@element-plus/icons-vue'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'
import { useAuthenticatedAttachment } from '@/composables/useAuthenticatedAttachment'
import { useToolLabel } from '@/composables/useToolLabel'
import { http } from '@/api'
import { copyToClipboard } from '@/utils/clipboard'
import TypingCursor from './TypingCursor.vue'
import BrowserTimeline from './BrowserTimeline.vue'
import ToolCallSegment from './ToolCallSegment.vue'
import ThinkingSegment from './ThinkingSegment.vue'
import ContentSegment from './ContentSegment.vue'
import PlanStepsPanel from './PlanStepsPanel.vue'
import UserMessageContent from './UserMessageContent.vue'
import type { BrowserAction } from './BrowserTimeline.vue'
import type { Message, MessageSegment, ChatAttachment, ToolCallMeta, PlanMeta } from '@/types'
import type { ChatErrorInfo } from '@/types/chatError'

const { renderMarkdown } = useMarkdownRenderer()
const { t, locale } = useI18n()
const { getToolLabel } = useToolLabel()
const { blobUrls, loadAllImages, loadAllVideos, loadAllAudios, loadAllModels, downloadFile, openImage, getDisplayUrl, revokeAll } = useAuthenticatedAttachment()

interface Props {
  message: Message
  isLast?: boolean
  assistantIcon?: string
  userIcon?: string
  showCursor?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isLast: false,
  assistantIcon: '🤖',
  userIcon: 'U',
  showCursor: false,
})

const emit = defineEmits<{
  regenerate: []
  'toggle-thinking': [expanded: boolean]
  approve: [pendingId: string]
  deny: [pendingId: string]
}>()

// --- 基础计算 ---
const role = computed(() => props.message.role)
const status = computed(() => props.message.status)
const isGenerating = computed(() => status.value === 'generating' || status.value === 'awaiting_approval')
const hovered = ref(false)

const avatarIcon = computed(() => {
  return role.value === 'user' ? props.userIcon : props.assistantIcon
})

// --- 错误卡片 ---
const errorInfo = computed<ChatErrorInfo | undefined>(() => props.message.errorInfo)

const errorTitle = computed(() => {
  if (!errorInfo.value) return t('chat.failed')
  return t(`chat.error.${errorInfo.value.category}.title`)
})

const errorDescription = computed(() => {
  if (!errorInfo.value) return ''
  // 优先展示后端 extractUserFriendlyError 生成的具体消息（比泛化模板更有指向性，
  // 比如"当前模型不支持工具调用，请切换到 qwen3 / qwen2.5 ..."）。
  // 仅当 rawMessage 为空/过短时才回退到分类模板。
  // 阈值用 3：可过滤 "OK"/"fail" 之类无意义短串，又能放行 "无权操作该会话"
  // 这类 7 字中文 / "Forbidden" 这类英文短消息，避免被泛模板覆盖。
  const raw = errorInfo.value.rawMessage?.trim() || ''
  if (raw.length > 3) {
    // 去掉后端冗余前缀，错误卡标题已经表达了类别
    return raw
      .replace(/^Bad request:\s*/i, '')
      .replace(/^LLM 调用失败[:：]\s*/, '')
      .replace(/^认证失败[:：]\s*/, '')
      .replace(/^\[错误]\s*/, '')
  }
  return t(`chat.error.${errorInfo.value.category}.description`)
})

const errorAction = computed(() => {
  if (!errorInfo.value) return ''
  return t(`chat.error.${errorInfo.value.category}.action`)
})

const errorCode = computed(() => {
  if (!errorInfo.value) return ''
  const parts: string[] = []
  if (errorInfo.value.httpStatus) parts.push(`HTTP ${errorInfo.value.httpStatus}`)
  if (errorInfo.value.requestId) parts.push(`ID: ${errorInfo.value.requestId}`)
  return parts.join(' | ')
})

const errorRetryable = computed(() => errorInfo.value?.retryable ?? true)

// --- Thinking 面板 ---
const localThinkingExpanded = ref(props.message.thinkingExpanded || false)

watch(() => props.message.thinkingExpanded, (val) => {
  if (val !== undefined) localThinkingExpanded.value = val
})

const thinkingContent = computed(() => {
  const thinkingPart = props.message.contentParts?.find(p => p.type === 'thinking')
  return thinkingPart?.text || ''
})

const hasContent = computed(() => {
  const textPart = props.message.contentParts?.find(p => p.type === 'text')
  return !!(textPart?.text || props.message.content)
})

const showThinkingPanel = computed(() => !!thinkingContent.value)

// 思考耗时（生成结束后显示）
const thinkingDuration = computed(() => {
  if (isGenerating.value) return ''
  if (!thinkingContent.value) return ''
  // 优先使用 segment 真实时间戳
  const segs = (props.message as any).segments || []
  const thinkSeg = segs.find((s: any) => s.type === 'thinking')
  const contentSeg = segs.find((s: any) => s.type === 'content')
  if (thinkSeg?.timestamp && contentSeg?.timestamp) {
    const sec = Math.max(1, Math.round((contentSeg.timestamp - thinkSeg.timestamp) / 1000))
    return sec >= 60 ? `${Math.floor(sec / 60)}m ${sec % 60}s` : `${sec}s`
  }
  // 回退：从内容长度估算
  const len = thinkingContent.value.length
  if (len < 50) return ''
  const sec = Math.max(1, Math.round(len / 100))
  return sec >= 60 ? `${Math.floor(sec / 60)}m ${sec % 60}s` : `${sec}s`
})

watch(
  [thinkingContent, hasContent, isGenerating],
  ([thinking, content, generating]) => {
    if (!generating) return
    if (thinking && !content) {
      localThinkingExpanded.value = true
    } else if (content) {
      localThinkingExpanded.value = false
    }
  },
  { immediate: true }
)

const toggleThinking = () => {
  localThinkingExpanded.value = !localThinkingExpanded.value
  emit('toggle-thinking', localThinkingExpanded.value)
}

const renderedThinkingContent = computed(() => {
  if (!thinkingContent.value) return ''
  return renderMarkdown(thinkingContent.value)
})

// --- 主内容 ---
const isApprovalPlaceholder = (text: string) => {
  return text.includes('[APPROVAL_PENDING]')
    || text.includes('[⏳ 等待审批]')
    || text.includes('[等待审批]')
}

const displayContent = computed(() => {
  const textPart = props.message.contentParts?.find(p => p.type === 'text')
  const text = textPart?.text || props.message.content || ''
  if (isGenerating.value && !text) return ''
  // 过滤审批占位文本 — 这些消息由审批面板展示，不应作为正文显示
  if (text && isApprovalPlaceholder(text)) return ''
  // 有错误卡片时隐藏 [错误] 原始文本，避免重复展示
  if (status.value === 'failed' && errorInfo.value && text.startsWith('[错误]')) return ''
  return text
})

// --- parse_error detection ---
const parseErrorText = computed(() => {
  const errorPart = props.message.contentParts?.find(p => p.type === 'parse_error')
  return errorPart?.text || ''
})

const renderedContent = computed(() => {
  if (!displayContent.value) return ''
  return renderMarkdown(displayContent.value)
})

const showLoadingIndicator = computed(() => {
  return isGenerating.value && !displayContent.value
})


// --- 操作栏 ---
const showActions = computed(() => {
  if (isGenerating.value) return false
  return hovered.value && (displayContent.value || status.value === 'stopped' || status.value === 'interrupted' || status.value === 'failed')
})

const copyState = ref<'idle' | 'copied'>('idle')
let copyTimer: ReturnType<typeof setTimeout> | null = null

function copyMessage() {
  const text = displayContent.value || props.message.content || ''
  if (!text) return
  copyToClipboard(text).then(() => {
    copyState.value = 'copied'
    if (copyTimer) clearTimeout(copyTimer)
    copyTimer = setTimeout(() => { copyState.value = 'idle' }, 2000)
  }).catch(() => {})
}

// --- TTS 朗读 ---
const ttsState = ref<'idle' | 'loading' | 'playing'>('idle')
let ttsAudio: HTMLAudioElement | null = null

async function handleTts() {
  if (ttsState.value === 'playing') {
    // 停止播放
    ttsAudio?.pause()
    ttsAudio = null
    ttsState.value = 'idle'
    return
  }

  const text = displayContent.value || props.message.content || ''
  if (!text) return

  const conversationId = props.message.conversationId
  if (!conversationId) return

  ttsState.value = 'loading'
  try {
    const res: any = await http.post('/tts/synthesize', {
      conversationId,
      text,
    })
    if (res.data?.success && res.data?.audioUrl) {
      // 通过认证 fetch 获取音频 blob
      const audioRes = await fetch(res.data.audioUrl, {
        headers: { Authorization: `Bearer ${localStorage.getItem('token') || ''}` },
      })
      const blob = await audioRes.blob()
      const blobUrl = URL.createObjectURL(blob)
      ttsAudio = new Audio(blobUrl)
      ttsAudio.onended = () => {
        ttsState.value = 'idle'
        URL.revokeObjectURL(blobUrl)
        ttsAudio = null
      }
      ttsAudio.onerror = () => {
        ttsState.value = 'idle'
        URL.revokeObjectURL(blobUrl)
        ttsAudio = null
      }
      ttsState.value = 'playing'
      await ttsAudio.play()
    } else {
      ttsState.value = 'idle'
    }
  } catch {
    ttsState.value = 'idle'
  }
}

onBeforeUnmount(() => {
  if (copyTimer) clearTimeout(copyTimer)
  if (ttsAudio) { ttsAudio.pause(); ttsAudio = null }
  revokeAll()
})

// --- 附件 ---
// MessageContentPart media (image/audio/video produced by generation tools) live
// in `contentParts` rather than `attachments`. Synthesize virtual attachment
// entries so the existing render + auth-blob loader works for them too.
//
// Dedup against `props.message.attachments` by URL — user-uploaded images often
// land in BOTH lists (the upload endpoint registers them as ChatAttachment AND
// the message persistence echoes them back as a `type: 'image'` MessageContentPart).
// Without this guard each user image shows twice in the bubble.
const mediaPartAttachments = computed<ChatAttachment[]>(() => {
  const parts = (props.message as any).contentParts as Array<any> | undefined
  if (!parts || !parts.length) return []
  const existingUrls = new Set(
    (props.message.attachments || []).map(a => a.url).filter(Boolean)
  )
  const out: ChatAttachment[] = []
  const seen = new Set<string>()
  for (const p of parts) {
    if (!p || !p.fileUrl) continue
    if (p.type !== 'image' && p.type !== 'audio' && p.type !== 'video' && p.type !== 'model3d') continue
    if (existingUrls.has(p.fileUrl) || seen.has(p.fileUrl)) continue
    seen.add(p.fileUrl)
    const fileName = p.fileName || p.fileUrl.split('/').pop() || `${p.type}-${out.length}`
    const ct = p.contentType
        || (p.type === 'image' ? 'image/png'
            : p.type === 'audio' ? 'audio/mpeg'
            : p.type === 'video' ? 'video/mp4'
            : 'model/gltf-binary')
    out.push({
      name: fileName,
      size: 0,
      url: p.fileUrl,
      storedName: fileName,
      path: p.fileUrl,
      contentType: ct,
    })
  }
  return out
})

const attachments = computed(() => [
  ...(props.message.attachments || []),
  ...mediaPartAttachments.value,
])
const imageAttachments = computed(() => attachments.value.filter(a => a.contentType?.startsWith('image/')))
const videoAttachments = computed(() => attachments.value.filter(a => a.contentType?.startsWith('video/')))
const audioAttachments = computed(() => attachments.value.filter(a => a.contentType?.startsWith('audio/')))
const model3dAttachments = computed(() => attachments.value.filter(a => a.contentType?.startsWith('model/')))
const fileAttachments = computed(() => attachments.value.filter(a =>
  !a.contentType?.startsWith('image/')
    && !a.contentType?.startsWith('video/')
    && !a.contentType?.startsWith('audio/')
    && !a.contentType?.startsWith('model/')
))

// 增量加载图片/视频/音频附件的鉴权 blob URL（watch 覆盖首次 + 后续变化）
watch(imageAttachments, (atts) => {
  if (atts.length > 0) loadAllImages(atts)
}, { immediate: true })
watch(videoAttachments, (atts) => {
  if (atts.length > 0) loadAllVideos(atts)
}, { immediate: true })
watch(audioAttachments, (atts) => {
  if (atts.length > 0) loadAllAudios(atts)
}, { immediate: true })
// 3D models also need the auth-blob loader — <model-viewer src> doesn't carry
// the Authorization header any more than <img>/<audio> do.
watch(model3dAttachments, (atts) => {
  if (atts.length > 0) loadAllModels(atts)
}, { immediate: true })

// --- 时间 ---
const formattedTime = computed(() => {
  const createTime = props.message.createTime
  if (!createTime) return ''

  const date = new Date(createTime)
  if (Number.isNaN(date.getTime())) return ''

  const now = new Date()

  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()

  const currentLocale = locale.value

  const time = date.toLocaleTimeString(currentLocale, {
    hour: '2-digit',
    minute: '2-digit',
  })

  if (sameDay(date, now)) return time

  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)

  if (sameDay(date, yesterday)) {
    return `${t('security.activity.yesterday')} ${time}`
  }

  return date.toLocaleString(currentLocale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
})

// Reply model attribution: shows which model produced the assistant message.
// Empty for streaming (server only emits runtimeModel after persistence) and
// historical messages prior to MessageVO carrying the field.
const replyModel = computed(() => props.message.runtimeModel || '')
const replyModelTitle = computed(() => {
  const provider = props.message.runtimeProvider
  const base = t('chat.replyModel', { model: replyModel.value })
  return provider ? `${base} (${provider})` : base
})

// Multimodal routing badge: rendered only when a sidecar actually fired this
// turn (strategy=sidecar, sidecarModel populated). Skipped for the legacy
// "primary handled it natively" case so non-routed turns stay clean.
const routingBadge = computed(() => {
  const r = props.message.metadata?.routing
  if (!r || r.strategy !== 'sidecar' || !r.sidecarModel) return null
  // Count routed attachments by required modality. We summarize as "1 image"
  // / "2 images" rather than naming each file to keep the chip compact.
  const required = r.requiredModalities || []
  const kind = required.includes('VISION')
    ? t('chat.routing.kind.image')
    : required.includes('VIDEO')
      ? t('chat.routing.kind.video')
      : t('chat.routing.kind.media')
  const label = `${r.sidecarModel} (${kind})`
  const tooltip = t('chat.routing.tooltip', {
    primary: replyModel.value || '?',
    sidecar: r.sidecarModel,
    sidecarProvider: r.sidecarProvider || '',
    kind,
  })
  return { label, tooltip }
})

const formatFileSize = (size: number) => {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / (1024 * 1024)).toFixed(1)} MB`
}

// openAttachment 已由 useAuthenticatedAttachment 的 openImage / downloadFile 替代

// --- 执行过程面板 ---
const executionExpanded = ref(false)

// --- 分段式渲染（Claude Code 风格） ---
const parsedMetadata = computed(() => {
  const raw = props.message.metadata
  if (!raw) return {} as any
  if (typeof raw === 'string') {
    try {
      let parsed = JSON.parse(raw)
      // 处理双重 JSON 编码（DB 中 metadata 是字符串，Jackson 序列化时可能再次转义）
      if (typeof parsed === 'string') {
        try { parsed = JSON.parse(parsed) } catch { /* ignore */ }
      }
      return parsed
    } catch { return {} }
  }
  return raw
})

const segments = computed<MessageSegment[]>(() => {
  if (props.message.role !== 'assistant') return []
  const meta = parsedMetadata.value

  // 优先：使用 metadata.segments（流式时由前端写入，历史时由后端持久化）
  if (meta?.segments && Array.isArray(meta.segments) && meta.segments.length > 0
      && typeof meta.segments[0] === 'object' && meta.segments[0]?.type) {
    const segs = [...meta.segments] as MessageSegment[]

    // 补充：如果后端 segments 没有 thinking 但 contentParts 有（非原生 thinking 模型）
    const hasThinking = segs.some(s => s.type === 'thinking')
    if (!hasThinking) {
      const thinkingPart = props.message.contentParts?.find(p => p.type === 'thinking')
      if (thinkingPart?.text) {
        // Tag with iterationIndex=0 so groupedIterations puts it in the FIRST
        // iteration's thinking bucket instead of the default-zero bucket
        // colliding with later iteration content. Without this, the fallback
        // thinking renders below the answer for any conversation that has
        // multi-iteration RFC-22 segments tagged elsewhere.
        const firstIter = segs.find(s => typeof s.iterationIndex === 'number')?.iterationIndex ?? 0
        segs.unshift({ id: 'th-fb', type: 'thinking', status: 'completed', thinkingText: thinkingPart.text, iterationIndex: firstIter })
      }
    }

    // 去重：相同 toolName + toolArgs 的 tool_call segment 只保留第一个
    const seenToolCalls = new Set<string>()
    const deduped = segs.filter(seg => {
      if (seg.type !== 'tool_call') return true
      const key = `${seg.toolName}::${seg.toolArgs || ''}`
      if (seenToolCalls.has(key)) return false
      seenToolCalls.add(key)
      return true
    })
    segs.length = 0
    segs.push(...deduped)

    // 修复历史消息顺序：如果 thinking 被落在 content 后面，提到首个 content 前
    // 只处理单个 thinking 段的常见场景，避免破坏复杂交错时间线
    const thinkingIndices = segs
      .map((seg, index) => seg.type === 'thinking' ? index : -1)
      .filter(index => index >= 0)
    const firstNonThinkingIdx = segs.findIndex((seg: MessageSegment) => seg.type !== 'thinking')
    if (thinkingIndices.length === 1 && firstNonThinkingIdx >= 0 && thinkingIndices[0] > firstNonThinkingIdx) {
      const [thinkingSeg] = segs.splice(thinkingIndices[0], 1)
      segs.splice(0, 0, thinkingSeg)
    }

    return segs
  }

  // Fallback：从 toolCalls + contentParts 做 best-effort 重建（旧消息兼容）
  // 注意：这会丢失事件交错顺序（所有 thinking 在前，所有 tool calls 在中，content 在后）
  const segs: MessageSegment[] = []
  const thinkingPart = props.message.contentParts?.find(p => p.type === 'thinking')
  if (thinkingPart?.text) {
    segs.push({ id: 'th-0', type: 'thinking', status: 'completed', thinkingText: thinkingPart.text })
  }
  const toolCalls = meta?.toolCalls || []
  toolCalls.forEach((tc: ToolCallMeta, i: number) => {
    segs.push({
      id: `tc-${i}`, type: 'tool_call', status: 'completed',
      toolName: tc.name, toolArgs: tc.arguments,
      toolResult: tc.result, toolSuccess: tc.success,
    })
  })
  if (props.message.content) {
    segs.push({ id: 'ct-0', type: 'content', status: 'completed', text: props.message.content })
  }
  return segs
})

/** 是否使用分段模式渲染（有 segments 数据且包含多个分段） */
const useSegmentedView = computed(() => segments.value.length > 1)

/**
 * Group segments by iterationIndex so each ReAct iteration renders as its own
 * thinking/tool-calls/content cluster. Falls back to a single ungrouped bucket
 * for legacy messages (no iterationIndex tagged) so historical conversations
 * keep rendering as before — including the existing "single-thinking reorder"
 * normalization done in the `segments` computed above.
 */
const groupedIterations = computed(() => {
  const segs = segments.value || []
  const anyTagged = segs.some(s => typeof s.iterationIndex === 'number')
  if (!anyTagged) {
    return [{
      key: 'all',
      index: 0,
      empty: false,
      thinkings: segs.filter(s => s.type === 'thinking'),
      tools: segs.filter(s => s.type === 'tool_call'),
      contents: segs.filter(s => s.type === 'content'),
    }]
  }
  const buckets = new Map<number, { thinkings: MessageSegment[]; tools: MessageSegment[]; contents: MessageSegment[] }>()
  for (const s of segs) {
    const idx = s.iterationIndex ?? 0
    if (!buckets.has(idx)) buckets.set(idx, { thinkings: [], tools: [], contents: [] })
    const b = buckets.get(idx)!
    if (s.type === 'thinking') b.thinkings.push(s)
    else if (s.type === 'tool_call') b.tools.push(s)
    else if (s.type === 'content') b.contents.push(s)
  }
  return [...buckets.entries()]
    .sort(([a], [b]) => a - b)
    .map(([index, b]) => ({
      key: `iter-${index}`,
      index,
      empty: b.thinkings.length === 0 && b.tools.length === 0 && b.contents.length === 0,
      ...b,
    }))
})

const toolCallsMeta = computed<ToolCallMeta[]>(() => {
  return parsedMetadata.value?.toolCalls || []
})

/**
 * True when the assistant turn was auto-truncated by the backend's
 * thinking-only soft-cap and ended in INCOMPLETE.
 * Surfaced as a banner with a "continue / regenerate" affordance so the
 * user knows the answer ended early on purpose, not silently skipped.
 *
 * Reads `metadata.finishReason` set by the graph's FinalAnswerNode via
 * the finish_reason GraphEvent → StreamDelta → accumulator pipeline.
 */
const isIncomplete = computed<boolean>(() => {
  if (props.message.role !== 'assistant') return false
  return parsedMetadata.value?.finishReason === 'incomplete'
})

/**
 * True when the graph completed normally but {@code SourceEvidenceLedger}
 * found unsupported references. The visible answer is full and persisted;
 * the trailing "[证据不足] …" line just lists which file/class citations
 * were never confirmed by an actual tool result. Without this banner the
 * user often misreads that single line as a mid-answer cut.
 */
const isEvidenceInsufficient = computed<boolean>(() => {
  if (props.message.role !== 'assistant') return false
  return parsedMetadata.value?.finishReason === 'evidence_insufficient'
})

/**
 * Recovery-affordance payload from the graph's feedback_event. Populated
 * for assistant turns that ended in a non-transient error (after the
 * helper's TLS / IO retry loop has already given up). Shape mirrors
 * GraphEventPublisher.feedback: { errorType, errorMessage, actions }.
 *
 * <p>Surfaces a card with buttons for each action: "retry" and
 * "regenerate" both replay the last user message; "report" copies the
 * error details for a bug report. The card sits right under the red
 * "[错误] …" content so users see the recovery options inline rather
 * than having to retype the whole prompt.
 */
interface FeedbackInfo {
  errorType: string
  errorMessage: string
  actions: string[]
  timestamp?: number
}
const feedbackInfo = computed<FeedbackInfo | undefined>(() => {
  if (props.message.role !== 'assistant') return undefined
  const raw = parsedMetadata.value?.feedbackEvent as FeedbackInfo | undefined
  if (!raw || !Array.isArray(raw.actions) || raw.actions.length === 0) return undefined
  return raw
})

function handleFeedbackAction(action: string) {
  if (action === 'retry' || action === 'regenerate') {
    emit('regenerate')
    return
  }
  if (action === 'report') {
    // Copy error details for a bug report. Lower-friction than a modal
    // and works offline; users paste the result into wherever they file
    // issues. Uses the clipboard helper with execCommand fallback for
    // non-HTTPS contexts (e.g. internal IPs without TLS).
    const lines = [
      `Error type: ${feedbackInfo.value?.errorType || 'UNKNOWN'}`,
      `Message: ${feedbackInfo.value?.errorMessage || ''}`,
      `Conversation: ${(props.message as any).conversationId || ''}`,
      `Message id: ${(props.message as any).id || ''}`,
      `Timestamp: ${new Date(feedbackInfo.value?.timestamp || Date.now()).toISOString()}`,
    ].join('\n')
    copyToClipboard(lines).then(() => {
      ElMessage.success(t('chat.feedback.reportCopied'))
    }).catch(() => {
      console.error('[feedback_event] copy failed:\n' + lines)
      ElMessage.error(t('chat.feedback.reportFailed'))
    })
  }
}

function feedbackActionLabel(action: string): string {
  // Action labels go through i18n so the same data-driven button list
  // renders correctly in zh-CN / en-US. Falls back to the raw action
  // key if a future backend introduces a label we haven't translated.
  const key = `chat.feedback.${action}`
  const localized = t(key)
  return localized === key ? action : localized
}

const browserActionsMeta = computed<BrowserAction[]>(() => {
  return parsedMetadata.value?.browserActions || []
})

const planMeta = computed<PlanMeta | undefined>(() => {
  return parsedMetadata.value?.plan
})

const currentPhaseName = computed(() => {
  const phase = parsedMetadata.value?.currentPhase
  switch (phase) {
    case 'reasoning': return 'Reasoning'
    case 'action': return 'Executing tools'
    case 'planning': return 'Planning'
    case 'summarizing': return 'Summarizing'
    case 'awaiting_approval': return 'Waiting for approval'
    case 'executing': return 'Executing'
    case 'replaying': return 'Resuming execution'
    case 'resumed_execution': return 'Resumed'
    default: return 'Processing'
  }
})

const truncateArgs = (args: string) => {
  if (!args) return ''
  const clean = args.replace(/\s+/g, ' ').trim()
  return clean.length > 60 ? clean.slice(0, 60) + '...' : clean
}

// --- 审批面板 ---
const pendingApproval = computed(() => {
  const approval = parsedMetadata.value?.pendingApproval
  if (!approval || approval.status === 'expired') return null
  return approval
})

const approvalSeverityClass = computed(() => {
  const sev = pendingApproval.value?.maxSeverity?.toLowerCase()
  if (!sev) return ''
  return 'approval-severity-' + sev
})

const executionPhaseLabel = computed(() => {
  if (pendingApproval.value?.status === 'pending_approval') {
    return 'Waiting for approval'
  }
  if (pendingApproval.value?.status === 'approved') {
    return 'Approved - Resuming'
  }
  if (planMeta.value) {
    const done = planMeta.value.stepResults?.filter(r => r?.status === 'completed').length || 0
    return `Plan-Execute (${done}/${planMeta.value.steps.length})`
  }
  if (toolCallsMeta.value.length) {
    const done = toolCallsMeta.value.filter(t => t.status === 'completed').length
    return `Tool Calls (${done}/${toolCallsMeta.value.length})`
  }
  return currentPhaseName.value
})

const showExecutionPanel = computed(() => {
  if (role.value !== 'assistant') return false
  // 审批卡片有独立的渲染区域，但 execution panel 也应该在审批阶段展示上下文
  return toolCallsMeta.value.length > 0 || !!planMeta.value
    || (isGenerating.value && parsedMetadata.value?.currentPhase)
    || !!pendingApproval.value
})

// 自动展开执行面板（工具调用时、审批时或计划创建时）
watch(toolCallsMeta, (calls) => {
  if (calls.length > 0 && isGenerating.value) {
    executionExpanded.value = true
  }
}, { deep: true })

watch(planMeta, (plan) => {
  if (plan && plan.steps?.length > 0 && isGenerating.value) {
    executionExpanded.value = true
  }
})

watch(pendingApproval, (approval) => {
  if (approval?.status === 'pending_approval') {
    executionExpanded.value = true
  }
})

// 生成结束后自动折叠（但审批等待中不折叠）
watch(isGenerating, (generating) => {
  if (!generating && executionExpanded.value && !pendingApproval.value) {
    executionExpanded.value = false
  }
})
</script>

<style scoped>
/* 分段式渲染容器 */
.segments-view {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
}

/* Iteration "no output" chip (interrupted iteration). */
.iter-empty-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  padding: 4px 10px;
  margin: 4px 0;
  font-size: 12px;
  color: var(--mc-text-tertiary, #94a3b8);
  background: var(--mc-bg-elevated, #f8fafc);
  border: 1px dashed var(--mc-border, #e2e8f0);
  border-radius: 12px;
}

/* Inline informational banner shown when the backend trimmed a repetitive
   tail off a content segment. Amber, not red — this is informational. */
.repetition-warning {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  margin: 6px 0 2px;
  font-size: 12px;
  color: #92400e;
  background: rgba(245, 158, 11, 0.08);
  border-left: 3px solid var(--mc-warning, #f59e0b);
  border-radius: 4px;
}
.repetition-warning__text {
  flex: 1;
}
.repetition-warning__meta {
  color: var(--mc-text-tertiary, #94a3b8);
  font-size: 11px;
}

.message-wrapper {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  max-width: 920px;
  margin-bottom: 6px;
}

.message-wrapper.user {
  flex-direction: row-reverse;
  margin-left: auto;
}

.message-wrapper.assistant {
  margin-right: auto;
}

/* 头像 */
.msg-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex-shrink: 0;
  margin-top: 2px;
}

.assistant-avatar {
  background: transparent;
}

.avatar-logo {
  width: 30px;
  height: 30px;
  object-fit: contain;
  border-radius: 50%;
}

.user-avatar {
  background: linear-gradient(135deg, var(--mc-success), #3D7A3D);
  color: white;
  font-size: 14px;
  font-weight: 600;
}

/* 消息体 */
.msg-body {
  max-width: calc(100% - 44px);
  min-width: 0;
}

.user-body {
  align-items: flex-end;
  display: flex;
  flex-direction: column;
}

/* 气泡 */
.msg-bubble {
  padding: 14px 16px;
  border-radius: 16px;
  font-size: 15px;
  line-height: 1.7;
  word-break: break-word;
}

.assistant-bubble {
  background: none;
  border: none;
  border-radius: 0;
  padding: 4px 0;
  color: var(--mc-assistant-bubble-color, #1e293b);
}

.user-bubble {
  background: var(--mc-user-bubble-bg, #D97757);
  color: var(--mc-user-bubble-color, white);
  border-radius: 18px 4px 18px 18px;
}

/* ==================== Thinking 面板 ==================== */
.thinking-section {
  margin-bottom: 12px;
}

.thinking-toggle {
  width: 100%;
  border: 0;
  background: var(--mc-thinking-bg, rgba(217, 119, 87, 0.06));
  border-radius: 10px;
  padding: 10px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--mc-thinking-text, #475569);
  cursor: pointer;
  transition: background 0.15s ease;
  font-family: inherit;
}

.thinking-toggle:hover {
  background: var(--mc-thinking-hover, rgba(217, 119, 87, 0.1));
}

.thinking-toggle__indicator {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: var(--mc-thinking-icon-bg, rgba(217, 119, 87, 0.12));
  color: var(--mc-primary, #D97757);
  flex-shrink: 0;
  transition: all 0.3s ease;
}

.thinking-toggle__indicator.active {
  animation: think-pulse 1.5s ease-in-out infinite;
}

@keyframes think-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.6; transform: scale(0.92); }
}

.thinking-toggle__label {
  font-size: 13px;
  font-weight: 600;
  flex: 1;
  text-align: left;
}

.thinking-toggle__duration {
  font-size: 11px;
  color: var(--mc-text-tertiary, #94a3b8);
  font-weight: 400;
}

.thinking-toggle__arrow {
  display: flex;
  align-items: center;
  color: var(--mc-text-tertiary, #94a3b8);
  transition: transform 0.2s ease;
}

.thinking-toggle__arrow.expanded {
  transform: rotate(180deg);
}

/* Thinking 内容折叠动画 */
.thinking-slide-enter-active,
.thinking-slide-leave-active {
  transition: all 0.25s ease;
  overflow: hidden;
}

.thinking-slide-enter-from,
.thinking-slide-leave-to {
  opacity: 0;
  max-height: 0;
  padding-top: 0;
  padding-bottom: 0;
}

.thinking-slide-enter-to,
.thinking-slide-leave-from {
  opacity: 1;
  max-height: 2000px;
}

.thinking-content {
  padding: 10px 14px 6px;
  color: var(--mc-text-secondary, #64748b);
  font-size: 13px;
  line-height: 1.65;
  border-left: 2px solid var(--mc-thinking-border, rgba(217, 119, 87, 0.2));
  margin-left: 12px;
  margin-top: 8px;
}

.thinking-content :deep(*) {
  max-width: 100%;
}

/* ==================== 执行过程面板 ==================== */
.execution-section {
  margin-bottom: 12px;
}

.execution-toggle {
  width: 100%;
  border: 0;
  background: var(--mc-thinking-bg, rgba(217, 119, 87, 0.06));
  border-radius: 10px;
  padding: 8px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--mc-thinking-text, #475569);
  cursor: pointer;
  transition: background 0.15s ease;
  font-family: inherit;
  font-size: 13px;
}

.execution-toggle:hover {
  background: var(--mc-thinking-hover, rgba(217, 119, 87, 0.1));
}

.execution-toggle__indicator {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: var(--mc-thinking-icon-bg, rgba(217, 119, 87, 0.12));
  color: var(--mc-primary, #D97757);
  flex-shrink: 0;
}

.execution-toggle__indicator.active {
  animation: think-pulse 1.5s ease-in-out infinite;
}

.execution-toggle__label {
  font-weight: 600;
  flex: 1;
  text-align: left;
}

.execution-toggle__count {
  font-size: 11px;
  color: var(--mc-text-tertiary, #94a3b8);
}

.execution-toggle__arrow {
  display: flex;
  align-items: center;
  color: var(--mc-text-tertiary, #94a3b8);
  transition: transform 0.2s ease;
}

.execution-toggle__arrow.expanded {
  transform: rotate(180deg);
}

.execution-content {
  padding: 10px 14px 6px;
  margin-top: 8px;
  border-left: 2px solid rgba(217, 119, 87, 0.2);
  margin-left: 12px;
}

.tool-calls {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tool-call {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 12px;
  background: var(--mc-bg-elevated, #f8fafc);
}

.tool-call--running {
  background: rgba(217, 119, 87, 0.06);
}

.tool-call--awaiting {
  background: rgba(245, 158, 11, 0.06);
}

.tool-call--error {
  background: rgba(239, 68, 68, 0.06);
}

.tool-call__status {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.tc-icon--warning { color: var(--mc-warning, #f59e0b); }
.tc-icon--success { color: var(--mc-success, #10b981); }
.tc-icon--error { color: var(--mc-danger, #ef4444); }

.tool-call__name {
  font-weight: 600;
  color: var(--mc-text-primary, #1e293b);
}

.tool-call__args {
  color: var(--mc-text-tertiary, #94a3b8);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

/* plan-steps 样式已迁移到 PlanStepsPanel.vue 组件 */

.execution-empty {
  font-size: 12px;
  color: var(--mc-text-tertiary, #94a3b8);
  padding: 4px 0;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ==================== parse_error card ==================== */
.parse-error-card {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 14px;
  margin-bottom: 8px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--mc-warning, #f59e0b) 8%, var(--mc-bg-elevated, #f8fafc));
  border: 1px solid color-mix(in srgb, var(--mc-warning, #f59e0b) 25%, transparent);
  font-size: 13px;
  line-height: 1.5;
  color: var(--mc-text-secondary, #64748b);
}

.parse-error-card__icon {
  flex-shrink: 0;
  color: var(--mc-warning, #f59e0b);
  margin-top: 1px;
}

.parse-error-card__text {
  word-break: break-word;
}

/* ==================== 审批面板 ==================== */
/* 极简审批状态（一行式） */
.approval-inline {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--mc-text-secondary, #64748b);
  background: var(--mc-bg-muted, #f9f7f5);
  border-radius: 8px;
}

.approval-inline__icon {
  color: var(--mc-warning, #f59e0b);
  flex-shrink: 0;
}

.approval-inline__text code {
  font-size: 12px;
  background: var(--mc-inline-code-bg, #f1f5f9);
  padding: 1px 5px;
  border-radius: 4px;
  font-weight: 500;
}

.approval-inline--approved {
  color: var(--mc-success, #10b981);
}
.approval-inline--approved .approval-inline__icon {
  color: var(--mc-success, #10b981);
}

.approval-inline--denied {
  color: var(--mc-danger, #ef4444);
}
.approval-inline--denied .approval-inline__icon {
  color: var(--mc-danger, #ef4444);
}

/* ==================== 操作栏 ==================== */
.msg-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px 0 0 4px;
  /* 始终占位，防止出现/消失时引起布局抖动 */
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s ease;
}

.msg-actions--visible {
  opacity: 1;
  pointer-events: auto;
}

.msg-actions--right {
  justify-content: flex-end;
  padding: 4px 4px 0 0;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--mc-text-tertiary, #94a3b8);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.action-btn:hover {
  background: var(--mc-bg-tertiary, rgba(0, 0, 0, 0.05));
  color: var(--mc-text-secondary, #64748b);
}

.action-btn.copied {
  color: #10b981;
}
.action-btn.tts-playing {
  color: var(--mc-primary);
}
.tts-loading-icon {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.action-time {
  font-size: 11px;
  color: var(--mc-text-tertiary, #94a3b8);
  margin-left: 4px;
  user-select: none;
}

.action-model {
  font-size: 11px;
  color: var(--mc-text-secondary, #64748b);
  margin-left: 4px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--mc-fill-2, rgba(100, 116, 139, 0.08));
  font-family: var(--mc-mono-font, ui-monospace, "SF Mono", Menlo, monospace);
  user-select: text;
  white-space: nowrap;
}

.action-routing {
  font-size: 11px;
  color: var(--mc-primary, #d96d46);
  margin-left: 4px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--mc-primary-bg, rgba(217, 109, 70, 0.1));
  font-family: var(--mc-mono-font, ui-monospace, "SF Mono", Menlo, monospace);
  user-select: text;
  white-space: nowrap;
  font-weight: 500;
}

/* ==================== 主内容区域 ==================== */
.msg-content {
  position: relative;
}

.msg-content.with-cursor {
  display: inline;
}

/* 状态指示器 */
.stopped-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 0 2px;
  font-size: 12px;
  color: var(--mc-text-tertiary, #94a3b8);
}

/* 错误卡片 */
.error-card {
  margin-top: 8px;
  padding: 12px 16px;
  border-radius: 8px;
  background: var(--mc-danger-bg);
  border: 1px solid color-mix(in srgb, var(--mc-danger) 25%, transparent);
  font-size: 13px;
  max-width: 480px;
  line-height: 1.5;
}

.error-card__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.error-card__icon {
  flex-shrink: 0;
  color: var(--mc-danger);
}

.error-card__title {
  font-weight: 600;
  color: var(--mc-danger);
  font-size: 14px;
}

.error-card__description {
  margin: 4px 0;
  color: var(--mc-text-primary);
  font-size: 13px;
  opacity: 0.85;
}

.error-card__action {
  margin: 4px 0 8px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.error-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.error-card__code {
  font-family: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, monospace;
  font-size: 11px;
  color: var(--mc-danger);
  opacity: 0.6;
}

.error-card__retry {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border-radius: 6px;
  border: 1px solid color-mix(in srgb, var(--mc-danger) 30%, transparent);
  background: color-mix(in srgb, var(--mc-danger) 8%, var(--mc-bg-elevated));
  color: var(--mc-danger);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}

.error-card__retry:hover {
  background: color-mix(in srgb, var(--mc-danger) 15%, var(--mc-bg-elevated));
  border-color: color-mix(in srgb, var(--mc-danger) 50%, transparent);
}

/* ==================== INCOMPLETE 截断卡片（重复检测 / thinking-only 软上限） ==================== */
.incomplete-card {
  margin-top: 8px;
  padding: 12px 16px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--mc-warning, #d97706) 8%, var(--mc-bg-elevated));
  border: 1px solid color-mix(in srgb, var(--mc-warning, #d97706) 30%, transparent);
  font-size: 13px;
  max-width: 480px;
  line-height: 1.5;
}

.incomplete-card__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.incomplete-card__icon {
  flex-shrink: 0;
  color: var(--mc-warning, #d97706);
}

.incomplete-card__title {
  font-weight: 600;
  color: var(--mc-warning, #d97706);
  font-size: 14px;
}

.incomplete-card__description {
  margin: 4px 0 8px;
  color: var(--mc-text-primary);
  font-size: 13px;
  opacity: 0.85;
}

.incomplete-card__footer {
  display: flex;
  justify-content: flex-end;
}

.incomplete-card__retry {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border-radius: 6px;
  border: 1px solid color-mix(in srgb, var(--mc-warning, #d97706) 35%, transparent);
  background: color-mix(in srgb, var(--mc-warning, #d97706) 10%, var(--mc-bg-elevated));
  color: var(--mc-warning, #d97706);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}

.incomplete-card__retry:hover {
  background: color-mix(in srgb, var(--mc-warning, #d97706) 18%, var(--mc-bg-elevated));
  border-color: color-mix(in srgb, var(--mc-warning, #d97706) 55%, transparent);
}

/* ==================== EVIDENCE_INSUFFICIENT 提示卡（info 调，非警告） ==================== */
.evidence-card {
  margin-top: 8px;
  padding: 10px 14px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--mc-info, #0891b2) 6%, var(--mc-bg-elevated));
  border: 1px solid color-mix(in srgb, var(--mc-info, #0891b2) 25%, transparent);
  font-size: 12.5px;
  max-width: 480px;
  line-height: 1.5;
}

.evidence-card__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.evidence-card__icon {
  flex-shrink: 0;
  color: var(--mc-info, #0891b2);
}

.evidence-card__title {
  font-weight: 600;
  color: var(--mc-info, #0891b2);
  font-size: 13.5px;
}

.evidence-card__description {
  margin: 4px 0 0;
  color: var(--mc-text-primary);
  font-size: 12.5px;
  opacity: 0.85;
}

/* ==================== feedback_event recovery card (ERROR_FALLBACK) ==================== */
.feedback-card {
  margin-top: 8px;
  padding: 12px 16px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--mc-danger, #dc2626) 8%, var(--mc-bg-elevated));
  border: 1px solid color-mix(in srgb, var(--mc-danger, #dc2626) 30%, transparent);
  font-size: 13px;
  max-width: 480px;
  line-height: 1.5;
}

.feedback-card__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.feedback-card__icon {
  flex-shrink: 0;
  color: var(--mc-danger, #dc2626);
}

.feedback-card__title {
  font-weight: 600;
  color: var(--mc-danger, #dc2626);
  font-size: 14px;
}

.feedback-card__description {
  margin: 4px 0 8px;
  color: var(--mc-text-primary);
  font-size: 13px;
  opacity: 0.85;
}

.feedback-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  flex-wrap: wrap;
}

.feedback-card__btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border-radius: 6px;
  border: 1px solid color-mix(in srgb, var(--mc-danger, #dc2626) 35%, transparent);
  background: color-mix(in srgb, var(--mc-danger, #dc2626) 10%, var(--mc-bg-elevated));
  color: var(--mc-danger, #dc2626);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}

.feedback-card__btn:hover {
  background: color-mix(in srgb, var(--mc-danger, #dc2626) 18%, var(--mc-bg-elevated));
  border-color: color-mix(in srgb, var(--mc-danger, #dc2626) 55%, transparent);
}

/* Report button is secondary action — muted neutral palette so the
   primary "retry" stays visually emphasized. */
.feedback-card__btn--report {
  border-color: var(--mc-border);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
}

.feedback-card__btn--report:hover {
  background: var(--mc-bg-sunken);
  border-color: var(--mc-border-strong, var(--mc-border));
  color: var(--mc-text-primary);
}

/* ==================== 附件 ==================== */
.message-attachments {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.message-attachment-image {
  border-radius: 12px;
  overflow: hidden;
}

.message-attachment-image img {
  max-width: 280px;
  max-height: 200px;
  border-radius: 12px;
  cursor: pointer;
  object-fit: cover;
}

.message-attachment-image__name {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.76;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-attachment-video {
  border-radius: 12px;
  overflow: hidden;
}

.message-attachment-video video {
  max-width: 400px;
  max-height: 280px;
  border-radius: 12px;
}

.message-attachment-video__name {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.76;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-attachment-audio {
  border-radius: 12px;
  overflow: hidden;
}

.message-attachment-audio audio {
  width: 100%;
  max-width: 400px;
  display: block;
}

.message-attachment-audio__name {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.76;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-attachment-model3d {
  border-radius: 12px;
  overflow: hidden;
  background: var(--bg-soft, #f5f5f5);
}

.message-attachment-model3d__viewer {
  width: 100%;
  max-width: 480px;
  height: 360px;
  display: block;
  border-radius: 12px;
  /* model-viewer renders nothing until the .glb finishes loading;
     keep the box sized so layout doesn't jump. */
  background: linear-gradient(135deg, #fafafa, #ececec);
}

.message-attachment-model3d__name {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.76;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-attachment {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.14);
  color: inherit;
  text-decoration: none;
  border: none;
  font: inherit;
  cursor: pointer;
  width: 100%;
}

.user-bubble .message-attachment {
  background: rgba(255, 255, 255, 0.2);
}

.message-attachment__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.message-attachment__meta {
  flex-shrink: 0;
  opacity: 0.76;
  font-size: 12px;
}

.message-attachment__icon {
  flex-shrink: 0;
  opacity: 0.76;
}

/* ==================== Markdown 样式 ==================== */
.markdown-body :deep(p) {
  margin: 0 0 10px;
  line-height: 1.7;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 10px 0;
  padding-left: 24px;
}

.markdown-body :deep(ul) { list-style-type: disc; }
.markdown-body :deep(ol) { list-style-type: decimal; }

.markdown-body :deep(li) {
  margin: 4px 0;
  line-height: 1.7;
}

.markdown-body :deep(li > ul),
.markdown-body :deep(li > ol) {
  margin: 4px 0;
}

.markdown-body :deep(input[type="checkbox"]) {
  margin-right: 8px;
  vertical-align: middle;
}

.markdown-body :deep(blockquote) {
  margin: 14px 0;
  padding: 12px 16px;
  border-left: 4px solid var(--mc-primary, #D97757);
  background: var(--mc-bg-elevated, #f8fafc);
  border-radius: 0 8px 8px 0;
  color: var(--mc-text-secondary, #64748b);
}

.markdown-body :deep(blockquote p) { margin: 0; }

.markdown-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 14px 0;
  font-size: 14px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 10px 12px;
  border: 1px solid var(--mc-border, #e2e8f0);
  text-align: left;
}

.markdown-body :deep(th) {
  background: var(--mc-bg-elevated, #f8fafc);
  font-weight: 600;
  color: var(--mc-text-primary, #1e293b);
}

.markdown-body :deep(tr:nth-child(even)) {
  background: var(--mc-bg-sunken, #f1f5f9);
}

.markdown-body :deep(a) {
  color: var(--mc-primary, #D97757);
  text-decoration: none;
  border-bottom: 1px solid transparent;
  transition: border-color 0.15s ease;
}

.markdown-body :deep(a:hover) {
  border-bottom-color: var(--mc-primary, #D97757);
}

.user-bubble .markdown-body :deep(a) {
  color: var(--mc-user-bubble-color, white);
  border-bottom: 1px solid rgba(255, 255, 255, 0.5);
}

.user-bubble .markdown-body :deep(a:hover) {
  border-bottom-color: var(--mc-user-bubble-color, white);
}

.markdown-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--mc-border, #e2e8f0);
  margin: 20px 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  margin: 20px 0 12px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--mc-text-primary, #1e293b);
}

.markdown-body :deep(h1) { font-size: 1.5em; }
.markdown-body :deep(h2) { font-size: 1.3em; }
.markdown-body :deep(h3) { font-size: 1.15em; }
.markdown-body :deep(h4) { font-size: 1em; }

/* 代码块 */
.markdown-body :deep(pre) {
  background: var(--mc-code-bg, #1e293b);
  border-radius: 12px;
  padding: 16px;
  overflow-x: auto;
  margin: 14px 0;
}

.markdown-body :deep(code) {
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, monospace;
  font-size: 13px;
  line-height: 1.7;
}

.markdown-body :deep(pre code) {
  color: #e2e8f0;
  background: transparent;
  padding: 0;
}

.markdown-body :deep(:not(pre) > code) {
  background: var(--mc-inline-code-bg, #f1f5f9);
  color: var(--mc-inline-code-color, #ef4444);
  padding: 2px 6px;
  border-radius: 6px;
  font-size: 0.92em;
}

/* Code-block CSS lives globally in main.css now (.markdown-body .code-block*)
   so the rules apply consistently across MessageBubble, AgentContext, and
   any future markdown-body context, and don't depend on Vue's per-component
   scope hash. Keep this comment as a breadcrumb so future edits don't get
   re-added here by reflex. */

/* ===== Mermaid block ===== */
.markdown-body :deep(.mermaid-block) {
  margin: 14px 0;
  border-radius: 12px;
  background: var(--mc-mermaid-bg, #f8fafc);
  border: 1px solid var(--mc-mermaid-border, #e2e8f0);
  overflow: hidden;
}
.markdown-body :deep(.mermaid-block__header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 38px;
  padding: 0 14px;
  background: var(--mc-code-header-bg);
  border-bottom: 1px solid var(--mc-mermaid-border, #e2e8f0);
  font-size: 12px;
  line-height: 1;
  color: var(--mc-code-lang-color);
  /* Prevent the header label/buttons from being swept into a text selection
     that starts in the surrounding markdown — the highlighted-grey selection
     band would otherwise extend across the whole header row. */
  user-select: none;
  -webkit-user-select: none;
}
.markdown-body :deep(.mermaid-block__lang) {
  font-weight: 500;
  letter-spacing: 0.02em;
}
.markdown-body :deep(.mermaid-block__actions) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.markdown-body :deep(.mermaid-block__download) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: var(--mc-code-copy-color);
  font-size: 12px;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.markdown-body :deep(.mermaid-block__download:hover) {
  background: var(--mc-code-copy-hover-bg);
  color: var(--mc-code-copy-hover-color);
}
/* Pin EVERY icon inside the header to 14×14. Without this, DOMPurify can
   normalise away the `width="14" height="14"` attrs from the markdown HTML,
   leaving the SVG to fall back to the UA default 300×150. The button is
   inline-flex so it grows to fit the icon, and `:hover` then paints a
   gigantic grey rectangle (which is what user issue #67's follow-up screen-
   shot showed). Same defence-in-depth as `.code-block__header svg`. */
.markdown-body :deep(.mermaid-block__header svg) {
  width: 14px !important;
  height: 14px !important;
  flex-shrink: 0;
  display: inline-block;
  vertical-align: middle;
}
.markdown-body :deep(.mermaid-block__header > *) {
  flex-shrink: 0;
  min-width: 0;
}
.markdown-body :deep(.mermaid-block__body) {
  padding: 16px;
  text-align: center;
  overflow-x: auto;
  /* Reserve a stable height so the box doesn't collapse to 0px before the
     SVG paints — keeps layout stable across the streaming cache-miss →
     render cycle. */
  min-height: 96px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.markdown-body :deep(.mermaid-block__body svg) {
  max-width: 100%;
  height: auto;
}
.markdown-body :deep(.mermaid-block.mermaid-error .mermaid-block__body) {
  background: #fef2f2;
  color: #b91c1c;
  text-align: left;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  display: block;
}
/* Streaming placeholder: three pulsing dots inside the empty body. The dots
   render the same DOM string on every v-html update (stable innerHTML) so
   the box stops "shaking" during streaming. Once the async render fires
   after stream end, this gets replaced with the actual SVG. */
.markdown-body :deep(.mermaid-block__loader) {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}
.markdown-body :deep(.mermaid-block__loader-dot) {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--mc-mermaid-border, #cbd5e1);
  animation: mc-mermaid-pulse 1.4s ease-in-out infinite;
}
.markdown-body :deep(.mermaid-block__loader-dot:nth-child(2)) {
  animation-delay: 0.2s;
}
.markdown-body :deep(.mermaid-block__loader-dot:nth-child(3)) {
  animation-delay: 0.4s;
}
@keyframes mc-mermaid-pulse {
  0%, 80%, 100% { opacity: 0.3; transform: scale(0.85); }
  40% { opacity: 1; transform: scale(1); }
}
.markdown-body :deep(.mermaid-block__download.is-flash) {
  background: var(--mc-warning-bg, rgba(255, 159, 67, 0.15));
  color: var(--mc-warning, #f59e0b);
}

/* ===== KaTeX inline / block ===== */
.markdown-body :deep(.katex-inline) {
  font-size: 1em;
}
.markdown-body :deep(.katex-block) {
  display: block;
  margin: 12px 0;
  text-align: center;
  overflow-x: auto;
}
.markdown-body :deep(.katex-error) {
  color: #b91c1c;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.92em;
}

.markdown-body :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 10px 0;
}

.markdown-body :deep(del),
.markdown-body :deep(s) {
  text-decoration: line-through;
  opacity: 0.7;
}

.markdown-body :deep(strong),
.markdown-body :deep(b) {
  font-weight: 600;
  color: var(--mc-text-primary, #1e293b);
}

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  .message-wrapper {
    max-width: 100%;
    gap: 8px;
  }

  .msg-body {
    max-width: calc(100% - 40px);
  }

  .msg-avatar {
    width: 28px;
    height: 28px;
    font-size: 12px;
  }

  .error-card {
    max-width: 100%;
  }
}
</style>
