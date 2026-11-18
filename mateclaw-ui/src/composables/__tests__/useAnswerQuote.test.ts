import { afterEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, ref } from 'vue'
import { useAnswerQuote } from '../useAnswerQuote'
let cleanup = () => {}
afterEach(() => { cleanup(); window.getSelection()?.removeAllRanges() })
async function mount() {
  const root = ref<HTMLElement|null>(null), readonly = ref(false), emitted = vi.fn()
  let controller!: ReturnType<typeof useAnswerQuote>
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp(defineComponent({ setup() {
    controller = useAnswerQuote(root,readonly,emitted)
    return () => h('div',{ref:root},[h('div',{class:'message-wrapper','data-role':'assistant','data-message-id':'42','data-conversation-id':'conv','data-status':'completed'},
      [h('p',{'data-quote-answer':''},'selected answer')])])
  } })); app.mount(host); await nextTick()
  const text = host.querySelector('p')!.firstChild!, range = document.createRange()
  range.selectNodeContents(text); window.getSelection()!.removeAllRanges(); window.getSelection()!.addRange(range)
  document.dispatchEvent(new Event('selectionchange')); await nextTick()
  cleanup = () => { app.unmount(); host.remove() }
  return {controller, readonly, emitted}
}
it('applies a live selection and clears it after emitting', async () => {
  const {controller,emitted} = await mount()
  expect(controller.quote.value?.text).toBe('selected answer')
  controller.apply()
  expect(emitted).toHaveBeenCalledWith({text:'selected answer',messageId:'42',conversationId:'conv'})
  expect(controller.quote.value).toBeNull()
})
it('rejects stale selections and readonly transitions', async () => {
  const {controller,readonly,emitted} = await mount()
  window.getSelection()!.removeAllRanges(); controller.apply(); expect(emitted).not.toHaveBeenCalled()
  readonly.value = true; await nextTick(); expect(controller.quote.value).toBeNull()
})
