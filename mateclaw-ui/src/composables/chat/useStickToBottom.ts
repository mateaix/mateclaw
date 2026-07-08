/**
 * 智能滚动 Composable
 * 提供"贴底/脱离锁定"的智能自动滚动体验：内容增长时自动贴底，用户上滚后释放锁定。
 *
 * Pin model: a single "pinned" state derived purely from the absolute distance
 * from the bottom (scrollHeight - scrollTop - clientHeight). The view is pinned
 * (auto-follows new content) only while the user is within `offset` of the
 * bottom; scrolling up past `offset` unpins, and scrolling back within `offset`
 * re-pins. No scroll-direction deltas are used — those flicker during scrollbar
 * drags and caused the view to snap back to the newest message while reading
 * history (issue #498).
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'

export interface StickToBottomOptions {
  /** 是否启用 */
  enabled?: boolean
  /** 触发滚动的偏移阈值（像素） */
  offset?: number
  /** 是否使用平滑滚动 */
  smooth?: boolean
  /** 滚动持续时间（毫秒） */
  duration?: number
}

export interface StickToBottomReturn {
  /** 是否贴底（自动跟随新内容） */
  isAtBottom: import('vue').Ref<boolean>
  /** 是否在底部附近 */
  isNearBottom: import('vue').ComputedRef<boolean>
  /** 是否被用户滚动中断（= 未贴底） */
  escapedFromLock: import('vue').Ref<boolean>
  /** 滚动元素引用 */
  scrollRef: import('vue').Ref<HTMLElement | null>
  /** 内容元素引用 */
  contentRef: import('vue').Ref<HTMLElement | null>
  /** 滚动到底部 */
  scrollToBottom: (options?: { force?: boolean; smooth?: boolean }) => Promise<void>
  /** 停止自动滚动 */
  stopScroll: () => void
  /** 检查是否在底部 */
  checkIsAtBottom: () => boolean
  resetLock: () => void
}

// 默认配置
const DEFAULT_OPTIONS: Required<StickToBottomOptions> = {
  enabled: true,
  offset: 70,
  smooth: true,
  duration: 350,
}

export function useStickToBottom(
  options: StickToBottomOptions = {}
): StickToBottomReturn {
  const opts = { ...DEFAULT_OPTIONS, ...options }

  const scrollRef = ref<HTMLElement | null>(null)
  const contentRef = ref<HTMLElement | null>(null)

  // Single source of truth. `isAtBottom` = pinned (auto-follow on);
  // `escapedFromLock` is its inverse, kept in sync via setPinned() so the two
  // exported refs can never disagree.
  const isAtBottom = ref(true)
  const escapedFromLock = ref(false)

  // True only while we drive a programmatic (smooth) scroll toward the bottom.
  // During that window the scroll events we ourselves generate must not be
  // interpreted as user intent. A user gesture (wheel-up / pointer down) clears
  // it to hand control back immediately.
  let programmatic = false
  let isSelecting = false
  let lastScrollTop = 0

  const supportsSmooth =
    typeof document !== 'undefined' && 'scrollBehavior' in document.documentElement.style

  const distanceFromBottom = (el: HTMLElement) =>
    el.scrollHeight - el.scrollTop - el.clientHeight

  const atBottom = (el: HTMLElement) => distanceFromBottom(el) <= opts.offset

  const setPinned = (pinned: boolean) => {
    isAtBottom.value = pinned
    escapedFromLock.value = !pinned
  }

  // 是否在底部附近
  const isNearBottom = computed(() => {
    if (!scrollRef.value) return false
    return atBottom(scrollRef.value)
  })

  // 检查是否在底部
  const checkIsAtBottom = () => {
    if (!scrollRef.value) return false
    return atBottom(scrollRef.value)
  }

  // 滚动到底部
  const scrollToBottom = async (scrollOptions?: { force?: boolean; smooth?: boolean }) => {
    const { force = false, smooth = opts.smooth } = scrollOptions || {}

    const element = scrollRef.value
    if (!element) return

    // 用户已上滚脱离贴底，且非强制：不打扰
    if (!force && escapedFromLock.value) return
    // 正在选择文本：不滚动
    if (isSelecting) return

    const targetScrollTop = element.scrollHeight - element.clientHeight

    // 已在底部
    if (element.scrollTop >= targetScrollTop - 1) {
      setPinned(true)
      return
    }

    if (smooth && supportsSmooth) {
      programmatic = true
      element.scrollTo({ top: targetScrollTop, behavior: 'smooth' })

      await new Promise<void>((resolve) => {
        const startedAt = performance.now()
        const step = () => {
          // 用户中断（programmatic 被手势清除）—— 让位
          if (!programmatic) {
            resolve()
            return
          }
          const settled = Math.abs(element.scrollTop - (element.scrollHeight - element.clientHeight)) < 1
          const timedOut = performance.now() - startedAt > opts.duration * 2
          if (settled || timedOut) {
            programmatic = false
            lastScrollTop = element.scrollTop
            setPinned(true)
            resolve()
          } else {
            requestAnimationFrame(step)
          }
        }
        requestAnimationFrame(step)
      })
    } else {
      element.scrollTop = targetScrollTop
      lastScrollTop = element.scrollTop
      setPinned(true)
    }
  }

  // 停止自动滚动
  const stopScroll = () => {
    programmatic = false
    setPinned(false)
  }

  // 处理滚动事件 —— 纯绝对位置判定，无方向增量
  const handleScroll = () => {
    const element = scrollRef.value
    if (!element) return

    // 忽略我们自己的程序化滚动产生的事件
    if (programmatic) {
      lastScrollTop = element.scrollTop
      return
    }

    // 绝对判定：距底 <= offset 即贴底，否则脱离
    if (atBottom(element)) {
      if (escapedFromLock.value) setPinned(true)
    } else if (!escapedFromLock.value) {
      setPinned(false)
    }
    lastScrollTop = element.scrollTop
  }

  // 用户手势：立即接管（取消进行中的程序化滚动）
  const handleUserGestureUp = () => {
    programmatic = false
    const element = scrollRef.value
    if (element && !atBottom(element)) setPinned(false)
  }

  const handleWheel = (e: WheelEvent) => {
    if (e.deltaY < 0) handleUserGestureUp()
  }

  // 处理鼠标/触摸开始（拖动滚动条 / 选择文本）
  const handlePointerDown = () => {
    isSelecting = true
    // 抓住滚动条即视为用户接管，取消程序化滚动
    programmatic = false
  }

  const handlePointerUp = () => {
    isSelecting = false
    // 结束后按绝对位置复核贴底状态
    setTimeout(() => {
      const element = scrollRef.value
      if (element) setPinned(atBottom(element))
    }, 100)
  }

  const resetLock = () => {
    programmatic = false
    setPinned(true)
  }

  // ResizeObserver 监听内容变化
  let resizeObserver: ResizeObserver | null = null

  onMounted(() => {
    if (!scrollRef.value) return

    const element = scrollRef.value

    element.addEventListener('scroll', handleScroll, { passive: true })
    element.addEventListener('wheel', handleWheel, { passive: true })
    element.addEventListener('mousedown', handlePointerDown)
    element.addEventListener('touchstart', handlePointerDown, { passive: true })
    document.addEventListener('mouseup', handlePointerUp)
    document.addEventListener('touchend', handlePointerUp)

    // 内容变化时，仅在贴底状态下跟随（用即时滚动，避免每 token 动画抖动）
    if (contentRef.value && window.ResizeObserver) {
      resizeObserver = new ResizeObserver(() => {
        if (opts.enabled && isAtBottom.value && !escapedFromLock.value) {
          scrollToBottom({ smooth: false })
        }
      })
      resizeObserver.observe(contentRef.value)
    }

    // 初始化滚动位置
    if (opts.enabled) {
      scrollToBottom({ smooth: false })
    }
  })

  onUnmounted(() => {
    const element = scrollRef.value
    if (element) {
      element.removeEventListener('scroll', handleScroll)
      element.removeEventListener('wheel', handleWheel)
      element.removeEventListener('mousedown', handlePointerDown)
      element.removeEventListener('touchstart', handlePointerDown)
    }
    document.removeEventListener('mouseup', handlePointerUp)
    document.removeEventListener('touchend', handlePointerUp)

    if (resizeObserver) {
      resizeObserver.disconnect()
      resizeObserver = null
    }
  })

  return {
    isAtBottom,
    isNearBottom,
    escapedFromLock,
    scrollRef,
    contentRef,
    scrollToBottom,
    stopScroll,
    checkIsAtBottom,
    resetLock,
  }
}

export default useStickToBottom
