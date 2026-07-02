/**
 * 打字机效果 Composable
 * 提供流畅的逐字打字显示效果
 */
import { ref, computed, watch, nextTick } from 'vue'

export interface UseTypingOptions {
  /** 是否启用打字机效果 */
  enabled?: boolean
  /** 打字速度（毫秒/字符） */
  speed?: number
  /** 每帧最大字符数 */
  charsPerFrame?: number
  /** 内容更新回调 */
  onUpdate?: (visibleContent: string) => void
  /** 打字完成回调 */
  onComplete?: () => void
}

export interface UseTypingReturn {
  /** 当前可见内容 */
  visibleContent: import('vue').Ref<string>
  /** 是否正在打字 */
  isTyping: import('vue').ComputedRef<boolean>
  /** 打字进度 0-1 */
  progress: import('vue').ComputedRef<number>
  /** 开始打字 */
  start: (content: string) => void
  /** 停止打字 */
  stop: () => void
  /** 立即完成 */
  complete: () => void
  /** 重置状态 */
  reset: () => void
}

export function useTyping(options: UseTypingOptions = {}): UseTypingReturn {
  const {
    enabled = true,
    speed = 16, // 60fps
    charsPerFrame = 2,
    onUpdate,
    onComplete,
  } = options

  // 内部状态
  const fullContent = ref('')
  const visibleLength = ref(0)
  const isRunning = ref(false)
  let animationFrameId: number | null = null
  let lastFrameTime = 0

  // 计算属性
  const visibleContent = computed(() => {
    return fullContent.value.slice(0, visibleLength.value)
  })

  const isTyping = computed(() => {
    return isRunning.value && visibleLength.value < fullContent.value.length
  })

  const progress = computed(() => {
    if (fullContent.value.length === 0) return 0
    return visibleLength.value / fullContent.value.length
  })

  // 打字动画循环
  const tick = (timestamp: number) => {
    if (!isRunning.value) return

    const elapsed = timestamp - lastFrameTime

    if (elapsed >= speed) {
      const remaining = fullContent.value.length - visibleLength.value
      
      if (remaining <= 0) {
        // 打字完成
        isRunning.value = false
        onComplete?.()
        return
      }

      // 计算本次显示的字符数
      const charsToAdd = Math.min(remaining, charsPerFrame)
      visibleLength.value += charsToAdd
      lastFrameTime = timestamp

      // 触发更新回调
      nextTick(() => {
        onUpdate?.(visibleContent.value)
      })
    }

    // 继续下一帧
    if (isRunning.value) {
      animationFrameId = requestAnimationFrame(tick)
    }
  }

  // 开始打字
  const start = (content: string) => {
    // 如果正在打字，先停止
    stop()
    
    fullContent.value = content
    visibleLength.value = 0
    
    if (!enabled || !content) {
      // 不启用打字机效果，直接显示全部
      visibleLength.value = content.length
      onComplete?.()
      return
    }

    isRunning.value = true
    lastFrameTime = performance.now()
    animationFrameId = requestAnimationFrame(tick)
  }

  // 停止打字
  const stop = () => {
    isRunning.value = false
    if (animationFrameId !== null) {
      cancelAnimationFrame(animationFrameId)
      animationFrameId = null
    }
  }

  // 立即完成
  const complete = () => {
    stop()
    visibleLength.value = fullContent.value.length
    onComplete?.()
  }

  // 重置状态
  const reset = () => {
    stop()
    fullContent.value = ''
    visibleLength.value = 0
  }

  // 监听内容变化，自动开始打字
  watch(fullContent, (newContent) => {
    if (newContent && !isRunning.value && visibleLength.value < newContent.length) {
      isRunning.value = true
      lastFrameTime = performance.now()
      animationFrameId = requestAnimationFrame(tick)
    }
  })

  return {
    visibleContent,
    isTyping,
    progress,
    start,
    stop,
    complete,
    reset,
  }
}

export default useTyping
