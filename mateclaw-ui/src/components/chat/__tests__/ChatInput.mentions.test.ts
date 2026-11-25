import { createApp, h, ref, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { it, expect } from 'vitest'
import ChatInput from '../ChatInput.vue'
it.each(['Tab', 'Enter', 'Click'])('selects a spaced name with %s then submits to the same lead as plain text', async (selectionKey) => {
  const text = ref('')
  const sent: string[] = []
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp({
    setup: () => () =>
      h(ChatInput, {
        modelValue: text.value,
        'onUpdate:modelValue': (v: string) => (text.value = v),
        memberCandidates: [
          { agentId: '9007199254740993', name: 'Jane Doe', enabled: true },
          { agentId: '9007199254740995', name: 'Jane Second', enabled: true },
          { agentId: 'disabled', name: 'Jane Disabled', enabled: false },
          { agentId: 'unknown', name: 'Jane Unknown' },
        ],
        onSubmit: (v: string) => sent.push(v),
      }),
  })
  app.component('el-icon', { render: () => h('span') })
  app.use(
    createI18n({
      legacy: false,
      locale: 'en',
      missingWarn: false,
      fallbackWarn: false,
      messages: { en: {} },
    }),
  )
  app.mount(host)
  const input = host.querySelector('textarea')!
  input.value = '@Ja'
  input.setSelectionRange(3, 3)
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  expect(host.querySelector('[role="listbox"]')).not.toBeNull()
  expect(host.querySelectorAll('[role="option"]')).toHaveLength(2)
  input.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',bubbles:true,cancelable:true}))
  input.dispatchEvent(new KeyboardEvent('keyup', {key:'Escape',bubbles:true}))
  await nextTick()
  expect(host.querySelector('[role="listbox"]')).toBeNull()
  input.dispatchEvent(new Event('input',{bubbles:true}))
  await nextTick()
  input.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',shiftKey:true,bubbles:true,cancelable:true}))
  input.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter',ctrlKey:true,bubbles:true,cancelable:true}))
  expect(sent).toEqual([])
  expect(text.value).toBe('@Ja')
  input.dispatchEvent(
    new CompositionEvent('compositionstart', { bubbles: true }),
  )
  input.dispatchEvent(
    new KeyboardEvent('keydown', {
      key: 'Enter',
      isComposing: true,
      bubbles: true,
      cancelable: true,
    }),
  )
  expect(sent).toEqual([])
  expect(text.value).toBe('@Ja')
  input.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }))
  input.dispatchEvent(
    new KeyboardEvent('keydown', {
      key: 'ArrowDown',
      bubbles: true,
      cancelable: true,
    }),
  )
  input.dispatchEvent(new KeyboardEvent('keyup', { key: 'ArrowDown', bubbles: true }))
  if (selectionKey === 'Click') (host.querySelectorAll('[role="option"]')[1] as HTMLButtonElement).click()
  else input.dispatchEvent(
    new KeyboardEvent('keydown', {
      key: selectionKey,
      bubbles: true,
      cancelable: true,
    }),
  )
  await nextTick()
  expect(text.value).toBe('@Jane Second (agentId: 9007199254740995) ')
  expect(sent).toEqual([])
  input.dispatchEvent(
    new KeyboardEvent('keydown', {
      key: 'Enter',
      bubbles: true,
      cancelable: true,
    }),
  )
  expect(sent).toEqual([text.value])
  app.unmount()
  host.remove()
})
