import { onActivated, onDeactivated, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'
import { readAnswerQuote, type AnswerQuote } from '@/utils/answerQuote'

export function useAnswerQuote(root: Ref<HTMLElement | null>, readonly: Ref<boolean>, emit: (quote: AnswerQuote) => void) {
  const quote = ref<AnswerQuote | null>(null)
  const position = ref({ left: 8, top: 8 })
  let active = true
  const clear = () => { quote.value = null }
  const update = () => {
    const selection = window.getSelection()
    quote.value = active && !readonly.value && root.value ? readAnswerQuote(selection,root.value) : null
    if (!quote.value || !selection) return
    const rect = selection.getRangeAt(0).getBoundingClientRect()
    position.value = { left: Math.max(8,Math.min(rect.left,window.innerWidth-160)),
      top: Math.max(8,Math.min(rect.bottom+6,window.innerHeight-44)) }
  }
  const apply = () => {
    const live = active && !readonly.value && root.value ? readAnswerQuote(window.getSelection(),root.value) : null
    if (live && quote.value && live.text === quote.value.text && live.messageId === quote.value.messageId
      && live.conversationId === quote.value.conversationId) {
      emit(live)
      window.getSelection()?.removeAllRanges()
    }
    clear()
  }
  const keydown = (event: KeyboardEvent) => { if (event.key === 'Escape') clear() }
  onMounted(() => {
    document.addEventListener('selectionchange',update)
    document.addEventListener('keydown',keydown)
    window.addEventListener('resize',clear)
  })
  onUnmounted(() => {
    document.removeEventListener('selectionchange',update)
    document.removeEventListener('keydown',keydown)
    window.removeEventListener('resize',clear)
  })
  onDeactivated(() => { active = false; clear() })
  onActivated(() => { active = true })
  watch(readonly,clear)
  return { quote, position, apply, clear }
}
