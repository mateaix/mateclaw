<template>
  <div
    ref="scrollRef"
    class="message-list"
    :class="{ 'is-scrolling': !isAtBottom }"
  >
    <div ref="contentRef" class="message-list-content">
      <!-- 空状态 -->
      <div v-if="messages.length === 0 && !loading" class="empty-state">
        <slot name="empty" :title="title" :subtitle="subtitle" :suggestions="suggestions">
          <div class="welcome-screen">
            <div class="welcome-logo">
              <div class="welcome-logo__glow"></div>
              <img src="/logo/mateclaw_logo_s.png" alt="MateClaw" class="welcome-logo__icon" />
            </div>
            <h2 class="welcome-title">Mate<span class="welcome-title-highlight">Claw</span></h2>
            <p class="welcome-subtitle">{{ subtitle }}</p>
            <div v-if="suggestions.length" class="welcome-suggestions">
              <button
                v-for="(s, i) in suggestions"
                :key="s"
                class="suggestion-card"
                @click="$emit('suggestion-click', s)"
              >
                <span class="suggestion-card__icon">
                  <el-icon v-if="i % 4 === 0"><ChatDotRound /></el-icon>
                  <el-icon v-else-if="i % 4 === 1"><EditPen /></el-icon>
                  <el-icon v-else-if="i % 4 === 2"><Monitor /></el-icon>
                  <el-icon v-else><DataLine /></el-icon>
                </span>
                <span class="suggestion-card__text">{{ s }}</span>
                <el-icon class="suggestion-card__arrow"><Right /></el-icon>
              </button>
            </div>
          </div>
        </slot>
      </div>

      <!-- 消息列表 -->
      <template v-else>
        <!-- 上拉加载更早消息触发器 -->
        <div v-if="hasMore" ref="loadMoreRef" class="load-more-trigger text-center py-3">
          <div v-if="loadingOlder" class="text-gray-400 dark:text-gray-500 text-sm flex items-center justify-center gap-2">
            <span class="animate-spin inline-block w-4 h-4 border-2 border-gray-300 border-t-gray-500 rounded-full"></span>
            {{ t('chat.loadingOlder') }}
          </div>
          <button v-else class="text-gray-400 dark:text-gray-500 text-sm hover:text-gray-600 dark:hover:text-gray-300 transition-colors" @click="$emit('load-more')">
            {{ t('chat.loadOlderMessages') }}
          </button>
        </div>

        <template v-for="(msg, index) in messages" :key="msg.id || index">
          <!-- 压缩摘要消息特殊渲染 -->
          <CompressionSummary
            v-if="isCompressionSummary(msg)"
            :message="msg"
          />
          <!-- Cron-run 头部分隔卡（system 消息且以 📋 开头）—— 在
               tasks_<wsId> / IM 镜像会话里把"这是哪个 cron 跑的"清晰标出来。
               LLM 历史读取时会跳过 system 消息，所以不污染下次提示词。 -->
          <div v-else-if="isCronHeader(msg)" class="cron-divider">
            <div class="cron-divider__line"></div>
            <span class="cron-divider__label">{{ msg.content }}</span>
            <div class="cron-divider__line"></div>
          </div>
          <!-- 普通消息气泡 -->
          <MessageBubble
            v-else
            :message="msg"
            :is-last="index === messages.length - 1"
            :assistant-icon="assistantIcon"
            :user-icon="userIcon"
            :show-cursor="showCursorForMessage(msg)"
            @regenerate="$emit('regenerate', msg)"
            @toggle-thinking="(expanded) => $emit('toggle-thinking', msg, expanded)"
            @approve="(pendingId) => $emit('approve', pendingId)"
            @deny="(pendingId) => $emit('deny', pendingId)"
          />
        </template>
      </template>

      <!-- 加载指示器：只在无消息时显示（有消息时由输入框显示停止按钮） -->
      <div v-if="loading && messages.length === 0" class="loading-more">
        <slot name="loading">
          <div class="typing-indicator">
            <span></span><span></span><span></span>
          </div>
        </slot>
      </div>
    </div>

    <!-- Floating "back to bottom" button: appears when the user scrolls up to
         read history, auto-docks to the edge after 15s of inactivity. -->
    <Transition name="fade-up">
      <div
        v-if="!isAtBottom"
        class="scroll-to-bottom-wrapper"
        :class="{ docked: isButtonDocked }"
        @mouseenter="undockButton"
      >
        <button
          class="scroll-to-bottom"
          type="button"
          :title="$t('chat.scrollToBottom')"
          @click="goToBottom"
        >
          <el-icon><ArrowDown /></el-icon>
        </button>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowDown, ChatDotRound, DataLine, EditPen, Monitor, Right } from '@element-plus/icons-vue'

const { t } = useI18n()
import MessageBubble from './MessageBubble.vue'
import CompressionSummary from './CompressionSummary.vue'
import { useStickToBottom } from '@/composables/chat/useStickToBottom'
import type { Message } from '@/types'

interface Props {
  /** 消息列表 */
  messages: Message[]
  /** 是否加载中 */
  loading?: boolean
  /** 助手图标 */
  assistantIcon?: string
  /** 用户图标 */
  userIcon?: string
  /** 标题（空状态） */
  title?: string
  /** 副标题（空状态） */
  subtitle?: string
  /** 建议提示（空状态） */
  suggestions?: string[]
  /** 是否自动滚动到底部 */
  autoScroll?: boolean
  /** 是否还有更早的消息可加载 */
  hasMore?: boolean
  /** 是否正在加载更早消息 */
  loadingOlder?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  assistantIcon: '🤖',
  userIcon: 'U',
  title: 'MateClaw',
  subtitle: '',
  suggestions: () => [],
  autoScroll: true,
  hasMore: false,
  loadingOlder: false,
})

const emit = defineEmits<{
  regenerate: [message: Message]
  'toggle-thinking': [message: Message, expanded: boolean]
  'suggestion-click': [suggestion: string]
  scroll: [event: Event]
  approve: [pendingId: string]
  deny: [pendingId: string]
  'load-more': []
}>()

// 判断消息是否为压缩摘要
const isCompressionSummary = (msg: Message) => {
  if (msg.role !== 'system') return false
  try {
    const metadata = typeof msg.metadata === 'string' ? JSON.parse(msg.metadata) : msg.metadata
    return metadata?.type === 'compression_summary'
  } catch {
    return false
  }
}

// Cron-run header — system message inserted by CronJobLifecycleService.startRun
// to label which job's run starts here. Pattern: leading "📋 ". Renders as a
// labeled divider so users browsing tasks_<wsId> can distinguish runs.
const isCronHeader = (msg: Message) => {
  return msg.role === 'system' && typeof msg.content === 'string' && msg.content.startsWith('📋 ')
}

// 智能滚动
const { scrollRef, contentRef, isAtBottom, escapedFromLock, scrollToBottom, resetLock } = useStickToBottom({
  enabled: props.autoScroll,
  offset: 70,
  smooth: true,
})

// 判断消息是否显示光标
const showCursorForMessage = (msg: Message) => {
  // 只有正在生成的最后一条助手消息显示光标
  const isLastAssistant = msg.role === 'assistant' && 
    msg.id === props.messages[props.messages.length - 1]?.id
  return isLastAssistant && msg.status === 'generating'
}

// 监听消息变化，自动滚动。流式生成时 token 逐字变更会高频触发本 watcher，
// 用一个 rAF 合并同一帧内的多次变更为一次即时滚动，避免每 token 动画抖动；
// 用户上滚脱离贴底后（escapedFromLock）完全不滚动，读历史不被打断。
let scrollRaf = 0
watch(
  () => props.messages,
  () => {
    if (escapedFromLock.value) return
    if (scrollRaf) return
    scrollRaf = requestAnimationFrame(async () => {
      scrollRaf = 0
      await nextTick()
      if (escapedFromLock.value) return
      scrollToBottom({ smooth: false })
    })
  },
  { deep: true }
)

// 监听生成状态
watch(
  () => props.loading,
  async (isLoading) => {
    if (isLoading) {
      await nextTick()
      scrollToBottom()
    }
  }
)

// Explicit "jump to bottom" request (button click / End key): force past the
// escape lock and clear it so sticky auto-scroll resumes following new content.
function goToBottom() {
  // force ignores the escape lock; the composable re-pins (isAtBottom /
  // escapedFromLock stay in sync) once the scroll settles.
  scrollToBottom({ force: true, smooth: true })
}

// ========== Back-to-bottom button: auto-dock to the edge after 15s idle ==========
const DOCK_DELAY_MS = 15000
let dockTimer: ReturnType<typeof setTimeout> | null = null
const isButtonDocked = ref(false)

function clearDockTimer() {
  if (dockTimer) { clearTimeout(dockTimer); dockTimer = null }
}

function resetDockTimer() {
  isButtonDocked.value = false
  if (!isAtBottom.value) {
    clearDockTimer()
    dockTimer = setTimeout(() => { isButtonDocked.value = true }, DOCK_DELAY_MS)
  }
}

function undockButton() {
  isButtonDocked.value = false
  resetDockTimer()
}

watch(isAtBottom, (atBottom) => {
  if (atBottom) { clearDockTimer(); isButtonDocked.value = false }
  else resetDockTimer()
})

watch(scrollRef, (el, _, onCleanup) => {
  if (!el) return
  el.addEventListener('scroll', resetDockTimer, { passive: true })
  onCleanup(() => el.removeEventListener('scroll', resetDockTimer))
})

// ========== End key jumps to the bottom ==========
function handleGlobalKeydown(e: KeyboardEvent) {
  if (e.key !== 'End') return
  // Only respond when focus is not in an editable field (textarea / input / contentEditable).
  const el = document.activeElement
  if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || (el as HTMLElement).isContentEditable)) return
  if (isAtBottom.value) return
  e.preventDefault()
  goToBottom()
}

onMounted(() => document.addEventListener('keydown', handleGlobalKeydown))
defineExpose({ resetScrollLock: resetLock })

onUnmounted(() => {
  clearDockTimer()
  document.removeEventListener('keydown', handleGlobalKeydown)
})
</script>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 18px 20px 12px;
  scroll-behavior: smooth;
  min-height: 0;
}

.message-list::-webkit-scrollbar {
  width: 6px;
}

.message-list::-webkit-scrollbar-thumb {
  background: var(--mc-scrollbar-thumb, #cbd5e1);
  border-radius: 3px;
}

.message-list-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 100%;
  /* Allow children to shrink below their intrinsic content width so a wide
     tool-call line or table scrolls/ellipsizes internally instead of pushing
     the column wider than the (overflow-x: hidden) viewport and getting clipped. */
  min-width: 0;
  max-width: 100%;
}

/* ==================== 空状态 / 欢迎屏 ==================== */
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 320px;
}

.welcome-screen {
  text-align: center;
  padding: 24px 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  box-sizing: border-box;
}

.welcome-logo {
  position: relative;
  margin-bottom: 20px;
}

.welcome-logo__glow {
  position: absolute;
  inset: -20px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(217, 119, 87, 0.12) 0%, transparent 70%);
  animation: logo-glow 3s ease-in-out infinite;
}

@keyframes logo-glow {
  0%, 100% { opacity: 0.6; transform: scale(1); }
  50% { opacity: 1; transform: scale(1.1); }
}

.welcome-logo__icon {
  position: relative;
  width: 64px;
  height: 64px;
  object-fit: contain;
  display: block;
  filter: drop-shadow(0 4px 12px rgba(217, 119, 87, 0.2));
}

.welcome-title {
  font-size: 26px;
  font-weight: 700;
  color: var(--mc-text-primary, #1e293b);
  margin: 0 0 8px;
  letter-spacing: -0.02em;
}

.welcome-title-highlight {
  color: var(--mc-primary);
}

.welcome-subtitle {
  font-size: 14px;
  color: var(--mc-text-secondary, #64748b);
  margin: 0 0 24px;
  max-width: 360px;
  line-height: 1.6;
}

.welcome-suggestions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  max-width: 720px;
  width: 100%;
}

.suggestion-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  background: var(--mc-bg-elevated, #f8fafc);
  border: 1px solid var(--mc-border, #e2e8f0);
  border-radius: 12px;
  font-size: 13px;
  color: var(--mc-text-primary, #1e293b);
  cursor: pointer;
  transition: all 0.2s ease;
  text-align: left;
}

.suggestion-card:hover {
  border-color: var(--mc-primary, #D97757);
  background: var(--mc-primary-bg, rgba(217, 119, 87, 0.06));
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(217, 119, 87, 0.08);
}

.suggestion-card__icon {
  font-size: 18px;
  flex-shrink: 0;
}

.suggestion-card__text {
  flex: 1;
  overflow: hidden;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.suggestion-card__arrow {
  flex-shrink: 0;
  color: var(--mc-text-tertiary, #94a3b8);
  opacity: 0;
  transform: translateX(-4px);
  transition: all 0.2s ease;
}

.suggestion-card:hover .suggestion-card__arrow {
  opacity: 1;
  transform: translateX(0);
  color: var(--mc-primary, #D97757);
}

/* 加载更多 */
.loading-more {
  display: flex;
  justify-content: center;
  padding: 20px;
}

.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}

.typing-indicator span {
  width: 6px;
  height: 6px;
  background: var(--mc-text-tertiary, #94a3b8);
  border-radius: 50%;
  animation: bounce 1.2s infinite;
}

.typing-indicator span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-indicator span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes bounce {
  0%, 60%, 100% {
    transform: translateY(0);
  }
  30% {
    transform: translateY(-6px);
  }
}

/* Back-to-bottom floating button */
.scroll-to-bottom-wrapper {
  position: absolute;
  bottom: 20%;
  right: 0;
  z-index: 10;
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 6px 8px;
}

.scroll-to-bottom {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--mc-border, #e2e8f0);
  background: var(--mc-bg-elevated, rgba(255, 255, 255, 0.92));
  backdrop-filter: blur(6px);
  color: var(--mc-text-secondary, #64748b);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  opacity: 1;
  pointer-events: auto;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  transform: translateX(0);
  transition: transform 0.35s cubic-bezier(0.4, 0, 0.2, 1),
              opacity 0.35s ease,
              box-shadow 0.2s ease,
              background 0.2s ease;
  flex-shrink: 0;
}

.scroll-to-bottom:hover {
  background: var(--mc-panel-raised, #f1f5f9);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  transform: scale(1.08);
  color: var(--mc-primary, #D97757);
  border-color: var(--mc-primary, #D97757);
}

/* Click feedback: shrink slightly on press, then spring back. */
.scroll-to-bottom:active {
  transform: scale(0.88);
  transition: transform 0.1s ease;
}

/* Docked to the edge: only ~8px stays visible. */
.scroll-to-bottom-wrapper.docked .scroll-to-bottom {
  transform: translateX(28px);
  opacity: 0.45;
  /* Subtle breathing pulse to remind the user the button is still there. */
  animation: dock-breathe 3s ease-in-out infinite;
}

@keyframes dock-breathe {
  0%, 100% { box-shadow: 0 0 0 0 rgba(217, 119, 87, 0); }
  50%      { box-shadow: 0 0 8px 2px rgba(217, 119, 87, 0.18); }
}

/* Restore as soon as the pointer approaches. */
.scroll-to-bottom-wrapper.docked:hover .scroll-to-bottom {
  transform: translateX(0);
  opacity: 1;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  animation: none;
}

/* Transition: fade + slide up */
.fade-up-enter-active,
.fade-up-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.fade-up-enter-from,
.fade-up-leave-to {
  opacity: 0;
  transform: translateY(16px);
}

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  .message-list {
    padding: 12px 10px 8px;
  }

  .message-list-content {
    gap: 10px;
  }

  .empty-state {
    min-height: 240px;
  }

  /* Mobile: larger button positioned lower for the thumb reach zone. */
  .scroll-to-bottom-wrapper {
    bottom: 12%;
  }

  .scroll-to-bottom {
    width: 44px;
    height: 44px;
    font-size: 18px;
  }

  .scroll-to-bottom-wrapper.docked .scroll-to-bottom {
    transform: translateX(36px); /* 44px - 8px = 36px */
  }

  .welcome-screen {
    padding: 16px 10px;
  }

  .welcome-logo__icon {
    width: 48px;
    height: 48px;
  }

  .welcome-title {
    font-size: 22px;
  }

  .welcome-subtitle {
    margin-bottom: 24px;
    max-width: 100%;
  }

  .welcome-suggestions {
    grid-template-columns: 1fr;
    max-width: 100%;
  }

  .suggestion-card {
    padding: 10px 12px;
  }
}

/* Cron-run header divider — labeled separator between runs in tasks_<wsId>. */
.cron-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 24px 6px;
  user-select: none;
}
.cron-divider__line {
  flex: 1;
  height: 1px;
  background: var(--mc-border-light, rgba(0, 0, 0, 0.08));
}
.cron-divider__label {
  font-size: 12px;
  color: var(--mc-text-tertiary, #999);
  white-space: nowrap;
  font-weight: 500;
  letter-spacing: 0.2px;
}
</style>
