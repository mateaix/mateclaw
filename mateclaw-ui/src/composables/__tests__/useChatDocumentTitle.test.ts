import { afterEach, describe, expect, it } from 'vitest'
import { computed, createApp, defineComponent, h, KeepAlive, nextTick, ref, watchEffect } from 'vue'
import type { Message } from '@/types'
import { chatDocumentTitle, formatChatDocumentTitle, useChatDocumentTitle } from '../useChatDocumentTitle'

const labels = { running: '运行中', completed: '已完成', incomplete: '未完成', failed: '失败', stopped: '已停止', awaiting_approval: '等待审批' }
const cleanups: (() => void)[] = []
afterEach(() => { cleanups.splice(0).forEach(fn => fn()); chatDocumentTitle.value = null })
function mount() {
  const conversationId = ref('a'), name = ref('报告'), generating = ref(false), phase = ref('idle'), serverRunning = ref(false)
  const messages = ref<Message[]>([])
  const pendingApproval = computed({
    get: () => messages.value.length > 0,
    set: (pending: boolean) => { messages.value = pending ? [{ conversationId: conversationId.value,
      role: 'assistant', content: '', contentParts: [], metadata: { pendingApproval: {
        pendingId: 'approval', toolName: 'shell', arguments: '{}', reason: '', status: 'pending_approval',
      } } }] : [] },
  })
  const visible = ref(true), appName = ref('MateClaw')
  let controller!: ReturnType<typeof useChatDocumentTitle>
  const Chat = defineComponent({ setup() {
    controller = useChatDocumentTitle({ conversationId, name, generating, phase, serverRunning, messages })
    return () => h('div')
  } })
  const Other = defineComponent({ render: () => h('div') })
  const Root = defineComponent({ setup() {
    watchEffect(() => { document.title = formatChatDocumentTitle(chatDocumentTitle.value, appName.value, '新对话', labels) })
    return () => h(KeepAlive, null, { default: () => h(visible.value ? Chat : Other) })
  } })
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp(Root); app.mount(host)
  cleanups.push(() => { app.unmount(); host.remove() })
  return { conversationId, name, generating, phase, serverRunning, pendingApproval, visible, appName, finish: (reason: Parameters<typeof controller.finish>[0]['reason'], id = conversationId.value) => controller.finish({ conversationId: id, reason }) }
}

describe('chat document title', () => {
  it('shows active chat and server-restored running state without claiming completion on load', async () => {
    const state = mount(); await nextTick()
    expect(document.title).toBe('报告 · MateClaw')
    state.serverRunning.value = true; await nextTick()
    expect(document.title).toBe('[运行中] 报告 · MateClaw')
  })
  it('uses explicit outcomes and clears completion when a new run starts', async () => {
    const state = mount(); state.generating.value = true; await nextTick()
    expect(document.title).toContain('[运行中]')
    state.generating.value = false; state.finish('completed'); await nextTick()
    expect(document.title).toContain('[已完成]')
    state.generating.value = true; await nextTick()
    expect(document.title).toContain('[运行中]')
    state.generating.value = false; state.finish('error'); await nextTick()
    expect(document.title).toContain('[失败]')
  })
  it('shows newly observed server runs after a previous completion', async () => {
    const state = mount(); state.finish('completed'); await nextTick()
    expect(document.title).toContain('[已完成]')
    state.serverRunning.value = true; await nextTick()
    expect(document.title).toContain('[运行中]')
    state.finish('completed'); await nextTick()
    // A stale list snapshot must not override a fresh terminal stream event.
    expect(document.title).toContain('[已完成]')
  })
  it('does not label truncated answers as completed', async () => {
    const state = mount(); state.finish('incomplete'); await nextTick()
    expect(document.title).toBe('[未完成] 报告 · MateClaw')
  })
  it('does not present approval or cancellation as completion', async () => {
    const state = mount(); state.phase.value = 'awaiting_approval'; await nextTick()
    expect(document.title).toContain('[等待审批]')
    state.phase.value = 'idle'; state.finish('stopped'); await nextTick()
    expect(document.title).toContain('[已停止]')
  })
  it('recognizes restored approval cards even without a live stream', async () => {
    const state = mount(); state.pendingApproval.value = true; await nextTick()
    expect(document.title).toContain('[等待审批]')
    state.pendingApproval.value = false; await nextTick()
    expect(document.title).toBe('报告 · MateClaw')
  })
  it('ignores old conversation approval messages during switching or failed loading', async () => {
    const state = mount(); state.pendingApproval.value = true; await nextTick()
    state.conversationId.value = 'b'; state.name.value = '会话 B'; await nextTick()
    expect(document.title).toBe('会话 B · MateClaw')
  })
  it('keeps late events from another chat out of the current title', async () => {
    const state = mount(); state.conversationId.value = 'b'; state.name.value = '新会话'; state.finish('completed', 'a'); await nextTick()
    expect(document.title).toBe('新会话 · MateClaw')
    state.name.value = '重命名'; state.appName.value = 'Assistant'; await nextTick()
    expect(document.title).toBe('重命名 · Assistant')
  })
  it('restores app title off-chat and resumes on keep-alive activation', async () => {
    const state = mount(); await nextTick(); state.visible.value = false; await nextTick()
    expect(document.title).toBe('MateClaw')
    state.finish('completed'); await nextTick()
    expect(document.title).toBe('MateClaw')
    state.visible.value = true; await nextTick()
    expect(document.title).toBe('[已完成] 报告 · MateClaw')
  })
  it('falls back for empty names and leaves non-chat titles unmodified', () => {
    expect(formatChatDocumentTitle({ conversationId: '', name: ' ', status: 'idle' }, 'MateClaw', '新对话', labels)).toBe('新对话 · MateClaw')
    expect(formatChatDocumentTitle(null, 'MateClaw', '新对话', labels)).toBe('MateClaw')
  })
})
