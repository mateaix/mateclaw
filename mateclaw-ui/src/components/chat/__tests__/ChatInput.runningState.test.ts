// @vitest-environment happy-dom
import { createApp } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it } from 'vitest'
import ChatInput from '../ChatInput.vue'

const apps: Array<ReturnType<typeof createApp>> = []

const messages = {
  chat: {
    messagePlaceholder: 'Type a message',
    streamStopAction: 'Stop generation',
    streamQueueAction: 'Send after current response',
    streamReplaceQueuedAction: 'Replace queued message',
    streamQueuedWaitingAction: 'Queued message waiting for current response',
    streamInterrupting: 'Interrupting...',
    queuedSending: 'Sending queued message...',
    queuedWillSend: 'Queued, will send after current step',
    queuedCancel: 'Cancel',
    queuedReplace: 'Message queued. Press Enter to replace...',
    thinkingOn: 'Deep thinking enabled',
    thinkingOff: 'Click to enable deep thinking',
    thinkingUnsupported: 'Current model does not support deep thinking',
  },
}

function mountChatInput(props: Record<string, unknown>) {
  const events: string[] = []
  const payloads: unknown[] = []
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(ChatInput, {
    placeholder: messages.chat.messagePlaceholder,
    ...props,
    onSubmit: (value: string) => {
      events.push('submit')
      payloads.push(value)
    },
    onStop: () => {
      events.push('stop')
    },
  })
  app.use(createI18n({ legacy: false, locale: 'en', messages: { en: messages } }))
  app.mount(host)
  apps.push(app)
  return { host, events, payloads }
}

afterEach(() => {
  apps.splice(0).forEach(app => app.unmount())
  document.body.innerHTML = ''
})

describe('ChatInput running-state actions', () => {
  it('labels and emits stop when running with empty input', () => {
    const { host, events } = mountChatInput({ loading: true, modelValue: '' })
    const send = host.querySelector<HTMLButtonElement>('.send-btn')

    expect(host.textContent).toContain('Stop generation')
    expect(send?.title).toBe('Stop generation')
    send?.click()

    expect(events).toEqual(['stop'])
  })

  it('labels and submits queued content when running with text', () => {
    const { host, events, payloads } = mountChatInput({ loading: true, modelValue: 'next task' })
    const send = host.querySelector<HTMLButtonElement>('.send-btn')

    expect(host.textContent).toContain('Send after current response')
    expect(send?.title).toBe('Send after current response')
    send?.click()

    expect(events).toEqual(['submit'])
    expect(payloads).toEqual(['next task'])
  })

  it('labels queued waiting state and does not stop on an empty repeated submit', () => {
    const { host, events } = mountChatInput({
      loading: true,
      modelValue: '',
      queuedMessage: { id: 'q1', text: 'queued task', status: 'sending' },
      queueSize: 1,
    })
    const send = host.querySelector<HTMLButtonElement>('.send-btn')

    expect(host.textContent).toContain('Queued message waiting for current response')
    expect(send?.title).toBe('Queued message waiting for current response')
    send?.click()

    expect(events).toEqual([])
  })

  it('labels replacement when running with queued content and new text', () => {
    const { host, events, payloads } = mountChatInput({
      loading: true,
      modelValue: 'replacement task',
      queuedMessage: { id: 'q1', text: 'queued task', status: 'queued' },
      queueSize: 1,
    })
    const send = host.querySelector<HTMLButtonElement>('.send-btn')

    expect(host.textContent).toContain('Replace queued message')
    expect(send?.title).toBe('Replace queued message')
    send?.click()

    expect(events).toEqual(['submit'])
    expect(payloads).toEqual(['replacement task'])
  })
})
