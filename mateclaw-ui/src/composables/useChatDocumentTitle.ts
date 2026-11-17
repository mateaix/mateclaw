import { computed, onActivated, onBeforeUnmount, onDeactivated, ref, shallowRef, watch, type Ref } from 'vue'
import type { Message } from '@/types'
import type { StreamEndMeta } from './chat/useChat'

export type ChatTitleStatus = 'idle' | 'running' | 'completed' | 'incomplete' | 'failed' | 'stopped' | 'awaiting_approval'
export interface ChatTitleContext { conversationId: string; name: string; status: ChatTitleStatus }
// App.vue is the single document.title writer, including locale changes.
export const chatDocumentTitle = shallowRef<ChatTitleContext | null>(null)
let titleOwner: symbol | null = null

export interface ChatTitleOptions {
  conversationId: Ref<string>; name: Ref<string>; generating: Ref<boolean>; phase: Ref<string>;
  serverRunning: Ref<boolean>; messages: Ref<Message[]>
}

export function useChatDocumentTitle(options: ChatTitleOptions) {
  const pendingApproval = computed(() => options.messages.value.some(message =>
    message.conversationId === options.conversationId.value && message.role === 'assistant' && message.metadata?.pendingApproval?.status === 'pending_approval'))
  const owner = Symbol('chat-title')
  const active = ref(true)
  const revision = ref(0)
  const outcomes = new Map<string, ChatTitleStatus>()
  watch(options.generating, running => {
    if (running) {
      outcomes.delete(options.conversationId.value)
      revision.value++
    }
  }, { flush: 'sync' })

  watch(options.serverRunning, (running, wasRunning) => {
    if (running && !wasRunning) {
      // A newly observed server run supersedes a previous local outcome.
      outcomes.delete(options.conversationId.value)
      revision.value++
    }
  }, { flush: 'sync' })

  const publish = () => {
    if (!active.value) return
    const id = options.conversationId.value
    const status: ChatTitleStatus = pendingApproval.value || options.phase.value === 'awaiting_approval' ? 'awaiting_approval'
      : options.generating.value ? 'running'
      : outcomes.get(id) ?? (options.serverRunning.value ? 'running' : 'idle')
    titleOwner = owner
    chatDocumentTitle.value = { conversationId: id, name: options.name.value, status }
  }
  watch([options.conversationId, options.name, options.generating, options.phase,
    options.serverRunning, pendingApproval, active, revision], publish, { immediate: true, flush: 'post' })

  const release = () => {
    active.value = false
    if (titleOwner === owner) {
      titleOwner = null
      chatDocumentTitle.value = null
    }
  }
  onActivated(() => { active.value = true; publish() })
  onDeactivated(release)
  onBeforeUnmount(release)

  const finish = (meta: StreamEndMeta) => {
    if (!meta.conversationId) return
    const status: ChatTitleStatus = meta.reason === 'completed' ? 'completed'
      : meta.reason === 'incomplete' ? 'incomplete'
      : meta.reason === 'awaiting_approval' ? 'awaiting_approval'
      : meta.reason === 'error' || meta.reason === 'failed' ? 'failed' : 'stopped'
    outcomes.set(meta.conversationId, status)
    revision.value++
  }
  return { finish }
}

export function formatChatDocumentTitle(context: ChatTitleContext | null, appName: string,
  newChat: string, labels: Record<Exclude<ChatTitleStatus, 'idle'>, string>) {
  if (!context) return appName
  const name = context.name.trim() || newChat
  const prefix = context.status === 'idle' ? '' : `[${labels[context.status]}] `
  return `${prefix}${name} · ${appName}`
}
