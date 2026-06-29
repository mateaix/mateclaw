/**
 * 消息状态管理 Composable
 */
import { ref, computed } from 'vue'
import type { Message, MessageContentPart } from '@/types'

export type MessageStatus = 'generating' | 'completed' | 'stopped' | 'failed' | 'awaiting_approval' | 'interrupted'

export interface UseMessagesOptions {
  /** 初始消息列表 */
  initialMessages?: Message[]
  /** 消息更新回调 */
  onUpdate?: (messages: Message[]) => void
  /** 消息完成回调 */
  onComplete?: (message: Message) => void
}

export interface UseMessagesReturn {
  /** 消息列表 */
  messages: import('vue').Ref<Message[]>
  /** 是否正在生成 */
  isGenerating: import('vue').ComputedRef<boolean>
  /** 是否还有更早的消息可加载 */
  hasMore: import('vue').Ref<boolean>
  /** 是否正在加载更早消息 */
  loadingOlder: import('vue').Ref<boolean>
  /** 最后一条消息 */
  lastMessage: import('vue').ComputedRef<Message | undefined>
  /** 最后一条用户消息 */
  lastUserMessage: import('vue').ComputedRef<Message | undefined>
  /** 最后一条助手消息 */
  lastAssistantMessage: import('vue').ComputedRef<Message | undefined>
  /** 添加消息 */
  addMessage: (message: Omit<Message, 'id' | 'createTime'> & { id?: string | number }) => Message
  /** 更新消息 */
  updateMessage: (id: string | number, updates: Partial<Message>) => void
  /** 追加消息内容 */
  appendMessageContent: (id: string | number, content: string, type?: 'text' | 'thinking') => void
  /** 删除消息 */
  removeMessage: (id: string | number) => void
  /** 清空消息 */
  clearMessages: () => void
  /** 设置消息状态 */
  setMessageStatus: (id: string | number, status: MessageStatus) => void
  /** 获取消息 */
  getMessage: (id: string | number) => Message | undefined
  /** 创建用户消息 */
  createUserMessage: (content: string, contentParts?: MessageContentPart[], conversationId?: string) => Message
  /** 创建助手消息 */
  createAssistantMessage: (content?: string, conversationId?: string) => Message
  /** 在消息列表头部插入更早的消息（分页加载） */
  prependMessages: (olderMessages: Message[]) => void
  /** 设置 hasMore 状态 */
  setHasMore: (value: boolean) => void
}

// 生成唯一 ID
const generateId = () => `${Date.now()}_${Math.random().toString(36).slice(2, 9)}`

export function useMessages(options: UseMessagesOptions = {}): UseMessagesReturn {
  const { initialMessages = [], onUpdate, onComplete } = options

  const messages = ref<Message[]>([...initialMessages])
  const hasMore = ref(false)
  const loadingOlder = ref(false)

  // 是否正在生成
  const isGenerating = computed(() => {
    return messages.value.some(m => m.status === 'generating')
  })

  // 最后一条消息
  const lastMessage = computed(() => {
    return messages.value[messages.value.length - 1]
  })

  // 最后一条用户消息
  const lastUserMessage = computed(() => {
    return messages.value.findLast(m => m.role === 'user')
  })

  // 最后一条助手消息
  const lastAssistantMessage = computed(() => {
    return messages.value.findLast(m => m.role === 'assistant')
  })

  // 添加消息
  const addMessage = (
    message: Omit<Message, 'id' | 'createTime'> & { id?: string | number }
  ): Message => {
    const newMessage: Message = {
      ...message,
      id: message.id || generateId(),
      createTime: new Date().toISOString(),
      contentParts: message.contentParts?.length ? message.contentParts : [],
    }
    messages.value.push(newMessage)
    onUpdate?.(messages.value)
    return newMessage
  }

  // 更新消息
  const updateMessage = (id: string | number, updates: Partial<Message>) => {
    const index = messages.value.findIndex(m => m.id === id)
    if (index === -1) return

    const prevMessage = messages.value[index]
    const nextMessage = { ...prevMessage, ...updates }
    
    // 替换消息
    messages.value = [
      ...messages.value.slice(0, index),
      nextMessage,
      ...messages.value.slice(index + 1),
    ]

    // 如果状态变为完成，触发回调
    if (updates.status === 'completed' && prevMessage.status !== 'completed') {
      onComplete?.(nextMessage)
    }

    onUpdate?.(messages.value)
  }

  // 追加消息内容（用于流式输出）
  const appendMessageContent = (
    id: string | number,
    content: string,
    type: 'text' | 'thinking' = 'text'
  ) => {
    const message = messages.value.find(m => m.id === id)
    if (!message) return

    // 获取或创建对应类型的 contentPart
    let contentParts = [...(message.contentParts || [])]
    const partIndex = contentParts.findLastIndex(p => p.type === type)

    if (partIndex === -1) {
      // 创建新的 part
      contentParts.push({
        type,
        text: content,
        visibleLength: content.length,
      })
    } else {
      // 追加到现有 part
      const part = contentParts[partIndex]
      contentParts[partIndex] = {
        ...part,
        text: (part.text || '') + content,
        visibleLength: ((part.text || '') + content).length,
      }
    }

    // 更新消息内容
    const textContent = contentParts
      .filter(p => p.type === 'text')
      .map(p => p.text || '')
      .join('\n')

    updateMessage(id, {
      contentParts,
      content: textContent,
    })
  }

  // 删除消息
  const removeMessage = (id: string | number) => {
    const index = messages.value.findIndex(m => m.id === id)
    if (index === -1) return
    
    messages.value = messages.value.filter(m => m.id !== id)
    onUpdate?.(messages.value)
  }

  // 清空消息
  const clearMessages = () => {
    messages.value = []
    onUpdate?.(messages.value)
  }

  // 设置消息状态
  const setMessageStatus = (id: string | number, status: MessageStatus) => {
    updateMessage(id, { status })
  }

  // 获取消息
  const getMessage = (id: string | number) => {
    return messages.value.find(m => m.id === id)
  }

  // 创建用户消息
  const createUserMessage = (content: string, contentParts?: MessageContentPart[], conversationId?: string): Message => {
    const parts: MessageContentPart[] = contentParts || [
      { type: 'text', text: content },
    ]

    return addMessage({
      role: 'user',
      conversationId: conversationId || '',
      content,
      contentParts: parts,
      status: 'completed',
    })
  }

  // 创建助手消息
  const createAssistantMessage = (content: string = '', conversationId?: string): Message => {
    return addMessage({
      role: 'assistant',
      conversationId: conversationId || '',
      content,
      contentParts: content ? [{ type: 'text', text: content, visibleLength: 0 }] : [],
      status: 'generating',
      thinkingExpanded: false,
    })
  }

  // 在消息列表头部插入更早的消息（分页加载用）
  const prependMessages = (olderMessages: Message[]) => {
    messages.value = [...olderMessages, ...messages.value]
    onUpdate?.(messages.value)
  }

  // 设置是否有更多消息
  const setHasMore = (value: boolean) => {
    hasMore.value = value
  }

  return {
    messages,
    isGenerating,
    hasMore,
    loadingOlder,
    lastMessage,
    lastUserMessage,
    lastAssistantMessage,
    addMessage,
    updateMessage,
    appendMessageContent,
    removeMessage,
    clearMessages,
    setMessageStatus,
    getMessage,
    createUserMessage,
    createAssistantMessage,
    prependMessages,
    setHasMore,
  }
}

export default useMessages
