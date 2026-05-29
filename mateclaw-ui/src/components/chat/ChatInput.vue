<template>
  <div
    ref="containerRef"
    class="chat-input-wrapper"
    :class="{
      'is-focused': isFocused,
      'is-disabled': disabled,
      'is-loading': loading,
    }"
  >
    <!-- 附件列表 -->
    <div v-if="attachments.length" class="attachment-list">
      <div
        v-for="attachment in attachments"
        :key="attachment.storedName || attachment.path"
        class="attachment-chip"
        :class="{
          'attachment-chip--dir': attachment.contentType === 'inode/directory',
          'attachment-chip--image': attachment.contentType?.startsWith('image/'),
          'attachment-chip--video': attachment.contentType?.startsWith('video/'),
        }"
      >
        <!-- 图片缩略图预览（优先用本地 previewUrl，避免 JWT 认证问题） -->
        <img
          v-if="attachment.contentType?.startsWith('image/') && (attachment.previewUrl || attachment.url)"
          :src="attachment.previewUrl || attachment.url"
          :alt="attachment.name"
          class="attachment-chip__thumbnail"
          loading="lazy"
        />
        <!-- 视频缩略图预览 -->
        <video
          v-else-if="attachment.contentType?.startsWith('video/') && (attachment.previewUrl || attachment.url)"
          :src="attachment.previewUrl || attachment.url"
          class="attachment-chip__thumbnail"
          preload="metadata"
          muted
        />
        <component
          :is="attachment.url ? 'a' : 'span'"
          :href="attachment.url || undefined"
          target="_blank"
          rel="noreferrer"
          class="attachment-chip__label"
        >
          <span>{{ attachment.contentType === 'inode/directory' ? '📁 ' : '' }}{{ attachment.name }}</span>
          <span v-if="attachment.size">{{ formatFileSize(attachment.size) }}</span>
          <span v-else-if="attachment.contentType === 'inode/directory'" class="attachment-chip__path">{{ attachment.path }}</span>
        </component>
        <button
          type="button"
          class="attachment-chip__remove"
          @click="removeAttachment(attachment.storedName || attachment.path)"
        >
          ×
        </button>
      </div>
    </div>

    <!-- 审批栏：有待审批时替换输入区域 -->
    <div v-if="pendingApproval?.status === 'pending_approval'" class="approval-bar">
      <div class="approval-bar__info">
        <span class="approval-bar__icon">
          <el-icon><WarningFilled /></el-icon>
        </span>
        <span class="approval-bar__label">{{ t('chat.approvalAllow') }}</span>
        <span class="approval-bar__tool">{{ getToolLabel(pendingApproval.toolName) }}</span>
        <span class="approval-bar__label">{{ t('chat.approvalExecute') }}</span>
      </div>
      <div class="approval-bar__actions">
        <button
          type="button"
          class="approval-bar__btn approval-bar__btn--deny"
          @click="emit('deny', pendingApproval.pendingId)"
        >
          <el-icon><CloseBold /></el-icon>
          {{ t('chat.deny') }}
        </button>
        <button
          type="button"
          class="approval-bar__btn approval-bar__btn--approve"
          @click="emit('approve', pendingApproval.pendingId)"
        >
          <el-icon><Select /></el-icon>
          {{ t('chat.approve') }}
        </button>
        <!-- Always-approve dropdown — creates an auto-approve grant of the
             selected scope before continuing with the regular /approve.
             Workspace-wide grants are intentionally NOT exposed here; they
             require the password-protected red button in Security >
             自动批准策略. -->
        <div class="approval-bar__always-wrap">
          <button
            type="button"
            class="approval-bar__btn approval-bar__btn--always"
            @click="alwaysApproveOpen = !alwaysApproveOpen"
          >
            {{ t('chat.approveAlways') }}
            <el-icon><ArrowDown /></el-icon>
          </button>
          <div v-if="alwaysApproveOpen" class="approval-bar__menu">
            <button type="button" class="approval-bar__menu-item" @click="chooseAlwaysApprove('CONVERSATION')">
              {{ t('chat.approveAlwaysConversation') }}
            </button>
            <button type="button" class="approval-bar__menu-item" @click="chooseAlwaysApprove('AGENT')">
              {{ t('chat.approveAlwaysAgent') }}
            </button>
            <button type="button" class="approval-bar__menu-item" @click="chooseAlwaysApprove('USER')">
              {{ t('chat.approveAlwaysUser') }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区域（运行中也可输入） -->
    <div v-else class="input-area-container">
      <!-- 排队消息指示器 -->
      <div v-if="queuedMessage && queuedMessage.status !== 'cancelled'" class="queued-indicator">
        <div class="queued-indicator__info">
          <el-icon><Timer /></el-icon>
          <span class="queued-indicator__text">
            {{ queuedMessage.status === 'sending' ? t('chat.queuedSending') : t('chat.queuedWillSend') }}
            <span v-if="queueSize > 1" class="queued-indicator__count">({{ queueSize }})</span>
          </span>
        </div>
        <button
          v-if="queuedMessage.status === 'queued'"
          type="button"
          class="queued-indicator__cancel"
          @click="emit('cancel-queued')"
        >{{ t('chat.queuedCancel') }}</button>
      </div>

      <div class="input-area">
      <SkillSlashMenu
        v-if="slashActive"
        ref="slashMenuRef"
        :query="slashQuery"
        @select="handleSkillSelect"
        @close="handleSlashClose"
      />
      <textarea
        ref="textareaRef"
        v-model="inputValue"
        class="chat-textarea"
        :placeholder="inputPlaceholder"
        :disabled="disabled"
        :maxlength="maxLength"
        rows="1"
        @keydown="handleSlashKeydown"
        @keydown.enter.exact.prevent="handleEnter"
        @compositionstart="isComposing = true"
        @compositionend="isComposing = false"
        @focus="onTextareaFocus"
        @blur="onTextareaBlur"
        @input="autoResize"
        @paste="handlePaste"
      ></textarea>

      <div class="input-actions">
        <!-- 附件按钮 -->
        <button
          v-if="enableAttachments"
          type="button"
          class="action-btn attach-btn"
          :disabled="disabled || loading || uploading"
          @click="openFilePicker"
        >
          <el-icon><Paperclip /></el-icon>
        </button>

        <!-- 深度思考开关 (RFC-049 PR-1-UI: 不支持 reasoning_effort 的模型灰态) -->
        <button
          type="button"
          class="action-btn thinking-btn"
          :class="{ active: thinkingEnabled && thinkingSupported, unsupported: !thinkingSupported }"
          :disabled="disabled || !thinkingSupported"
          @click="thinkingSupported && emit('toggle-thinking')"
          :title="!thinkingSupported
            ? t('chat.thinkingUnsupported')
            : (thinkingEnabled ? t('chat.thinkingOn') : t('chat.thinkingOff'))"
        >
          <el-icon><MagicStick /></el-icon>
        </button>

        <!-- Talk Mode 按钮 -->
        <button
          v-if="enableTalkMode"
          type="button"
          class="action-btn talk-btn"
          :disabled="disabled || loading"
          @click="emit('talk')"
          :title="t('talk.title')"
        >
          <el-icon><Microphone /></el-icon>
        </button>

        <!-- 发送/停止/中断按钮 -->
        <button
          type="button"
          class="action-btn send-btn"
          :class="sendBtnClass"
          :disabled="!canSend && !loading"
          @click="handleSubmit"
        >
          <!-- 有输入时始终显示发送图标（运行中发送 = interrupt） -->
          <el-icon v-if="canSend"><Promotion /></el-icon>
          <!-- 运行中无输入：停止图标 -->
          <el-icon v-else-if="loading"><CloseBold /></el-icon>
          <!-- 空闲无输入 -->
          <el-icon v-else><Promotion /></el-icon>
        </button>
      </div>
    </div>
    </div>

    <!-- 底部信息 -->
    <div class="input-footer">
      <span class="input-hint">{{ hint }}</span>
      <span v-if="maxLength" class="input-length">
        {{ inputValue.length }}/{{ maxLength }}
      </span>
    </div>

    <!-- 隐藏的文件输入 -->
    <input
      ref="fileInputRef"
      type="file"
      class="hidden-file-input"
      multiple
      :accept="acceptedFileTypes"
      @change="handleFileChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowDown, CloseBold, MagicStick, Microphone, Paperclip, Promotion, Select, Timer, WarningFilled } from '@element-plus/icons-vue'
import { useToolLabel } from '@/composables/useToolLabel'
import SkillSlashMenu from '@/components/chat/SkillSlashMenu.vue'
import type { ChatAttachment, PendingApprovalMeta, StreamPhase, QueuedMessage, Skill } from '@/types'

interface Props {
  /** 输入值 */
  modelValue?: string
  /** 占位符 */
  placeholder?: string
  /** 是否加载中（AI 正在运行） */
  loading?: boolean
  /** 是否禁用（无法输入） */
  disabled?: boolean
  /** 最大长度 */
  maxLength?: number
  /** 是否启用附件 */
  enableAttachments?: boolean
  /** 接受文件类型 */
  acceptedFileTypes?: string
  /** 提示文字 */
  hint?: string
  /** 附件列表 */
  attachments?: ChatAttachment[]
  /** 是否上传中 */
  uploading?: boolean
  /** 待审批数据：存在时将输入框替换为审批栏 */
  pendingApproval?: PendingApprovalMeta | null
  /** 当前流阶段 */
  streamPhase?: StreamPhase
  /** 排队的消息（队首） */
  queuedMessage?: QueuedMessage | null
  /** 排队消息总数 */
  queueSize?: number
  /** 是否启用 Talk Mode 按钮 */
  enableTalkMode?: boolean
  /** 深度思考开关状态 */
  thinkingEnabled?: boolean
  /**
   * RFC-049 PR-1-UI: 当前 runtime 模型是否支持 reasoning_effort。false 时按钮灰掉，
   * 不响应点击，tooltip 提示当前模型不支持深度思考。默认 true 以保持向后兼容。
   */
  thinkingSupported?: boolean
  /**
   * Whether the current agent can use skills. When false the skill slash menu
   * is suppressed — a skills-disabled agent has no `load_skill` tool, so naming
   * a skill would be a dead end.
   */
  skillsEnabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: '',
  placeholder: '',
  loading: false,
  disabled: false,
  enableAttachments: true,
  acceptedFileTypes: '*/*',
  hint: '',
  attachments: () => [],
  uploading: false,
  pendingApproval: null,
  streamPhase: 'idle',
  queuedMessage: null,
  queueSize: 0,
  enableTalkMode: false,
  thinkingEnabled: false,
  thinkingSupported: true,
  skillsEnabled: true,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: [value: string]
  stop: []
  'cancel-queued': []
  'file-select': [files: File[]]
  'attachment-remove': [storedName: string]
  approve: [pendingId: string]
  deny: [pendingId: string]
  /**
   * Always-approve dropdown: ChatConsole creates an auto-approve grant for the
   * matching scope, then forwards the regular /approve command. The scope
   * vocabulary mirrors mate_approval_grant.scope_type minus WORKSPACE (the
   * banner deliberately excludes the workspace-wide path; that lives in
   * Security > 自动批准策略 with password confirmation).
   */
  'approve-always': [payload: { pendingId: string; scope: 'CONVERSATION' | 'AGENT' | 'USER' }]
  talk: []
  'toggle-thinking': []
}>()

const { t } = useI18n()
const { getToolLabel } = useToolLabel()

// 内部状态
const containerRef = ref<HTMLElement | null>(null)
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const isFocused = ref(false)
const isComposing = ref(false)

// ---- Skill slash-command menu ----
// The menu opens when the whole input is a single "/<query>" token (no spaces
// yet). Picking a skill rewrites the input to a directive that names the skill,
// which the agent recognises and loads via `load_skill`.
const slashMenuRef = ref<InstanceType<typeof SkillSlashMenu> | null>(null)
const slashDismissed = ref(false)
const slashMatch = computed(() => {
  const m = /^\/([^\s/]*)$/.exec(props.modelValue)
  return m ? m[1] : null
})
const slashQuery = computed(() => slashMatch.value ?? '')
const slashActive = computed(
  () =>
    slashMatch.value !== null &&
    !slashDismissed.value &&
    !props.disabled &&
    !props.pendingApproval &&
    props.skillsEnabled,
)
// Leaving slash mode (cleared the "/" token) re-arms the menu for next time.
watch(slashMatch, (val) => {
  if (val === null) slashDismissed.value = false
})

function handleSlashKeydown(e: KeyboardEvent) {
  if (!slashActive.value) return
  const menu = slashMenuRef.value
  if (!menu) return
  switch (e.key) {
    case 'ArrowDown':
      e.preventDefault()
      menu.next()
      break
    case 'ArrowUp':
      e.preventDefault()
      menu.prev()
      break
    case 'Enter':
      if (!isComposing.value && menu.count() > 0) {
        e.preventDefault()
        menu.confirm()
      }
      break
    case 'Tab':
      if (menu.count() > 0) {
        e.preventDefault()
        menu.confirm()
      }
      break
    case 'Escape':
      e.preventDefault()
      slashDismissed.value = true
      break
  }
}

function handleSkillSelect(skill: Skill) {
  inputValue.value = t('chat.useSkillDirective', { name: skill.name })
  slashDismissed.value = false
  nextTick(() => {
    const el = textareaRef.value
    if (el) {
      el.focus()
      const end = el.value.length
      el.setSelectionRange(end, end)
    }
    autoResize()
  })
}

// On open, the menu autofocuses its search box, which blurs the textarea. That
// is expected focus movement — keep the menu open. Only dismiss when focus
// actually leaves the input+menu (clicked elsewhere). The relatedTarget check
// covers direct focus moves; the deferred activeElement check covers browsers
// that report a null relatedTarget for programmatic focus.
function onTextareaBlur(e: FocusEvent) {
  isFocused.value = false
  const next = e.relatedTarget as HTMLElement | null
  if (next && next.closest && next.closest('.skill-slash-menu')) return
  setTimeout(() => {
    const ae = document.activeElement as HTMLElement | null
    if (ae && ae.closest && ae.closest('.skill-slash-menu')) return
    slashDismissed.value = true
  }, 0)
}

function onTextareaFocus() {
  isFocused.value = true
}

// The menu asked to close (Escape, or focus left the menu). Suppress it until
// the "/" token is cleared and retyped.
function handleSlashClose() {
  slashDismissed.value = true
}

// Always-approve dropdown — collapsed by default; opens on the chevron click,
// closes on outside click or after the user picks a scope.
const alwaysApproveOpen = ref(false)
function chooseAlwaysApprove(scope: 'CONVERSATION' | 'AGENT' | 'USER') {
  if (!props.pendingApproval) return
  emit('approve-always', { pendingId: props.pendingApproval.pendingId, scope })
  alwaysApproveOpen.value = false
}

// 输入值处理
const inputValue = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

// 是否可以发送
const canSend = computed(() => {
  return inputValue.value.trim().length > 0 || props.attachments.length > 0
})

// 运行中输入的占位符
const inputPlaceholder = computed(() => {
  if (props.loading) {
    if (props.queuedMessage) return t('chat.queuedReplace')
    return props.placeholder
  }
  return props.placeholder
})

// 处理提交
const handleSubmit = () => {
  // 有排队消息时，点击按钮取消排队
  if (props.queuedMessage && props.queuedMessage.status === 'queued') {
    // 如果输入框为空，取消排队；如果有新输入，替换排队消息
    if (!inputValue.value.trim()) {
      emit('cancel-queued')
      return
    }
  }

  // 运行中且输入为空时，停止生成 —— 但当用户刚刚追加了一条 queued 消息时，
  // 第二次点击发送/按 Enter 通常是误操作（双击 / 输入法回车 / 连击）。这种情况下
  // 触发 stop 会把用户预期会跑的当前 turn + queued 一起杀掉，前端给出"任务直接结束"
  // 的错觉。检测到 sending 状态的 queued 消息时静默吞掉这次空提交，让用户必须明确
  // 点 cancel-queued 或专用 stop 按钮才能终止。
  if (props.loading && !canSend.value) {
    if (props.queuedMessage && (props.queuedMessage.status === 'queued' || props.queuedMessage.status === 'sending')) {
      return
    }
    emit('stop')
    return
  }

  if (!canSend.value || props.disabled) return

  // 运行中有内容：发送（useChat 会走 interrupt/queue 逻辑）
  // 非运行中有内容：正常发送
  emit('submit', inputValue.value)
}

// 发送按钮样式
const sendBtnClass = computed(() => ({
  'is-loading': props.loading && !canSend.value,
  'is-empty': !canSend.value && !props.loading,
  'is-interrupt': props.loading && canSend.value,
}))

// 处理回车键
const handleEnter = () => {
  if (isComposing.value) return
  // When the slash menu is showing matches, Enter confirms the highlighted
  // skill (handled in handleSlashKeydown) instead of submitting the message.
  if (slashActive.value && (slashMenuRef.value?.count() ?? 0) > 0) return
  handleSubmit()
}

// 自动调整高度
const autoResize = () => {
  nextTick(() => {
    const textarea = textareaRef.value
    if (!textarea) return

    textarea.style.height = 'auto'
    const newHeight = Math.min(textarea.scrollHeight, 160)
    textarea.style.height = newHeight + 'px'
  })
}

// 监听输入值变化，调整高度
watch(inputValue, autoResize)

// 文件处理
const openFilePicker = () => {
  fileInputRef.value?.click()
}

const handleFileChange = (event: Event) => {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  
  if (files.length) {
    emit('file-select', files)
  }
  
  // 清空 input 以便重复选择同一文件
  input.value = ''
}

const removeAttachment = (storedName: string) => {
  emit('attachment-remove', storedName)
}

// 粘贴处理
const handlePaste = (event: ClipboardEvent) => {
  if (!props.enableAttachments) return

  const items = Array.from(event.clipboardData?.items || [])
  const files = items
    .filter(item => item.kind === 'file')
    .map(item => item.getAsFile())
    .filter((file): file is File => file !== null)

  if (files.length > 0) {
    emit('file-select', files)
    event.preventDefault()
  }
}

// 文件大小格式化
const formatFileSize = (size: number) => {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / (1024 * 1024)).toFixed(1)} MB`
}

// 暴露方法给父组件
defineExpose({
  focus: () => textareaRef.value?.focus(),
  blur: () => textareaRef.value?.blur(),
  clear: () => {
    emit('update:modelValue', '')
    nextTick(() => {
      if (textareaRef.value) {
        textareaRef.value.style.height = 'auto'
      }
    })
  },
})
</script>

<style scoped>
.chat-input-wrapper {
  padding: 10px 14px 12px;
  background: var(--mc-bg-elevated, #f8fafc);
  flex-shrink: 0;
}

.chat-input-wrapper.is-focused {
  /* focus state handled on .input-area */
}

/* 附件列表 */
.attachment-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 8px;
}

.attachment-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  background: var(--mc-attachment-bg, #f1f5f9);
  border: 1px solid var(--mc-attachment-border, #e2e8f0);
  border-radius: 999px;
  padding: 6px 8px 6px 12px;
}

.attachment-chip--image,
.attachment-chip--video {
  padding: 4px 6px;
}

.attachment-chip__thumbnail {
  width: 36px;
  height: 36px;
  object-fit: cover;
  border-radius: 4px;
  flex-shrink: 0;
}

.attachment-chip__label {
  display: inline-flex;
  gap: 8px;
  min-width: 0;
  color: var(--mc-attachment-color, #1e293b);
  text-decoration: none;
  font-size: 13px;
}

.attachment-chip__label span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 280px;
}

.attachment-chip__label span:last-child {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--mc-primary, #D97757);
}

.attachment-chip__remove {
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 999px;
  background: rgba(217, 119, 87, 0.16);
  color: var(--mc-primary-hover, #C1572B);
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.attachment-chip__remove:hover {
  background: rgba(217, 119, 87, 0.24);
}

.attachment-chip--dir {
  border-style: dashed;
}

.attachment-chip__path {
  font-size: 11px;
  color: var(--mc-text-tertiary, #94a3b8);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 输入区域 */
.input-area {
  position: relative;
  display: flex;
  gap: 10px;
  align-items: flex-end;
  background: var(--mc-input-bg, #ffffff);
  border: none;
  border-radius: 16px;
  padding: 8px 10px 8px 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08), 0 0 0 1px rgba(0, 0, 0, 0.04);
  transition: box-shadow 0.15s;
}

.chat-input-wrapper.is-focused .input-area {
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1), 0 0 0 2px rgba(217, 119, 87, 0.25);
}

.chat-textarea {
  flex: 1;
  border: none;
  background: transparent;
  resize: none;
  outline: none;
  font-size: 14px;
  line-height: 1.6;
  color: var(--mc-input-text, #1e293b);
  min-height: 38px;
  max-height: 160px;
  font-family: inherit;
}

.chat-textarea::placeholder {
  color: var(--mc-text-tertiary, #94a3b8);
}

.chat-textarea:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 操作按钮 */
.input-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}

.action-btn {
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
  background: transparent;
  color: var(--mc-text-secondary, #64748b);
}

.action-btn:hover:not(:disabled) {
  background: var(--mc-bg-sunken, #f1f5f9);
  color: var(--mc-text-primary, #1e293b);
}

.action-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.thinking-btn {
  position: relative;
}
.thinking-btn.active {
  color: var(--el-color-primary, #409eff);
}
.thinking-btn.active::after {
  content: '';
  position: absolute;
  bottom: 4px;
  left: 50%;
  transform: translateX(-50%);
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--el-color-primary, #409eff);
}
/* RFC-049 PR-1-UI: model doesn't support reasoning_effort — stronger grayed state */
.thinking-btn.unsupported {
  opacity: 0.35;
  cursor: not-allowed;
}
.thinking-btn:hover:not(:disabled) {
  color: var(--el-color-primary, #409eff);
  background: var(--el-color-primary-light-9, rgba(64, 158, 255, 0.08));
}

.talk-btn:hover:not(:disabled) {
  color: var(--mc-primary, #D97757);
  background: var(--mc-primary-light, rgba(217, 119, 87, 0.08));
}

.send-btn {
  background: var(--mc-primary, #D97757);
  color: white;
}

.send-btn:hover:not(:disabled) {
  background: var(--mc-primary-hover, #C1572B);
}

.send-btn.is-loading {
  background: var(--mc-danger, #ef4444);
}

.send-btn.is-loading:hover:not(:disabled) {
  background: var(--mc-danger-hover, #dc2626);
}

.send-btn.is-empty:not(.is-loading) {
  opacity: 0.4;
  cursor: not-allowed;
}

/* 底部信息 */
.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 6px;
  padding: 0 4px;
}

.input-hint {
  font-size: 12px;
  color: var(--mc-text-tertiary, #94a3b8);
}

.input-length {
  font-size: 12px;
  color: var(--mc-text-tertiary, #94a3b8);
}

/* 隐藏的文件输入 */
.hidden-file-input {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  pointer-events: none;
}

/* 审批栏 */
.approval-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: var(--mc-input-bg, #ffffff);
  border-radius: 16px;
  padding: 8px 8px 8px 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08), 0 0 0 1px rgba(217, 119, 87, 0.3);
  min-height: 50px;
}

.approval-bar__info {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: var(--mc-text-secondary, #64748b);
}

.approval-bar__icon {
  display: flex;
  align-items: center;
  color: var(--mc-primary, #D97757);
  flex-shrink: 0;
}

.approval-bar__label {
  flex-shrink: 0;
}

.approval-bar__tool {
  font-weight: 600;
  color: var(--mc-text-primary, #1e293b);
  font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace;
  font-size: 13px;
  background: var(--mc-bg-sunken, #f1f5f9);
  padding: 1px 7px;
  border-radius: 5px;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 1;
}

.approval-bar__actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-shrink: 0;
}

.approval-bar__btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 14px;
  border: none;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  line-height: 1;
}

.approval-bar__btn--approve {
  background: var(--mc-primary, #D97757);
  color: #fff;
}

.approval-bar__btn--approve:hover {
  background: var(--mc-primary-hover, #C1572B);
}

/* Always-approve dropdown: orange-red border to signal it's a security-reducing
   action vs the regular approve button (solid primary). The dropdown menu is
   absolutely positioned above the banner so it never gets clipped. */
.approval-bar__always-wrap {
  position: relative;
}
.approval-bar__btn--always {
  background: transparent;
  color: #b91c1c;
  border: 1px solid #ef4444;
}
.approval-bar__btn--always:hover {
  background: #fef2f2;
}
.approval-bar__menu {
  position: absolute;
  bottom: calc(100% + 6px);
  right: 0;
  min-width: 160px;
  background: var(--mc-surface-primary, #fff);
  border: 1px solid var(--mc-border-light, #e5e7eb);
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  z-index: 20;
  overflow: hidden;
}
.approval-bar__menu-item {
  display: block;
  width: 100%;
  padding: 8px 12px;
  background: none;
  border: none;
  text-align: left;
  font-size: 13px;
  color: var(--mc-text-primary, #0f172a);
  cursor: pointer;
}
.approval-bar__menu-item:hover {
  background: var(--mc-surface-tertiary, #f1f5f9);
}

.approval-bar__btn--deny {
  background: var(--mc-bg-sunken, #f1f5f9);
  color: var(--mc-text-secondary, #64748b);
  border: 1px solid var(--mc-border, #e2e8f0);
}

.approval-bar__btn--deny:hover {
  background: var(--mc-danger-bg, #fee2e2);
  color: var(--mc-danger, #ef4444);
  border-color: var(--mc-danger-border, #fca5a5);
}

/* 输入区域容器 */
.input-area-container {
  display: flex;
  flex-direction: column;
  gap: 0;
}

/* 排队指示器 */
.queued-indicator {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 12px;
  margin-bottom: 3px;
  border-radius: 10px;
  background: rgba(59, 130, 246, 0.06);
  border: 1px solid rgba(59, 130, 246, 0.15);
}

.queued-indicator__info {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--mc-info, #3b82f6);
}

@media (max-width: 768px) {
  .chat-input-wrapper {
    padding: 8px 10px 10px;
  }

  .input-area {
    gap: 8px;
    padding: 7px 8px 7px 10px;
    border-radius: 14px;
  }

  .chat-textarea {
    min-height: 34px;
    line-height: 1.55;
  }

  .action-btn {
    width: 32px;
    height: 32px;
  }

  .attachment-chip__label span:first-child {
    max-width: 180px;
  }
}

.queued-indicator__text {
  font-weight: 500;
}

.queued-indicator__cancel {
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
  background: none;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 2px 8px;
  cursor: pointer;
  transition: all 0.15s;
}

.queued-indicator__cancel:hover {
  color: var(--mc-danger, #ef4444);
  border-color: var(--mc-danger-border, #fca5a5);
  background: var(--mc-danger-bg, #fee2e2);
}

/* 中断发送按钮样式 */
.send-btn.is-interrupt {
  background: var(--mc-warning, #f59e0b);
  color: white;
}

.send-btn.is-interrupt:hover:not(:disabled) {
  background: var(--mc-warning-hover, #d97706);
}

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  .chat-input-wrapper {
    padding: 10px 12px 14px;
  }

  .approval-bar {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    padding: 10px 12px;
  }

  .approval-bar__info {
    flex-wrap: wrap;
  }

  .approval-bar__actions {
    justify-content: flex-end;
  }

  .approval-bar__tool {
    max-width: 180px;
  }

  .attachment-chip__label span:first-child {
    max-width: 180px;
  }
}
</style>
