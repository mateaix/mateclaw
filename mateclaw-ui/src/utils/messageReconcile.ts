/**
 * 消息 reconcile 工具 — 防止 poorer DB 快照覆盖 local rich message。
 *
 * 问题场景：流结束后 onStreamEnd → refreshCurrentConversationMessages() 从 DB 拉消息，
 * 但此时后端 API 返回的 metadata 可能因 JSON 编码问题丢失 segments，
 * 整表替换会把本地包含完整 segments 的 rich message 覆盖为 poorer 版本。
 *
 * 解决：逐条按 id 比较"丰富度"，只接受更完整的版本。
 */
import type { Message } from '@/types'

/** 安全 parse metadata（处理 string / double-encoded / object / null） */
function safeParseMeta(metadata: any): Record<string, any> {
  if (!metadata) return {}
  if (typeof metadata === 'object') return metadata
  if (typeof metadata === 'string') {
    try {
      let parsed = JSON.parse(metadata)
      if (typeof parsed === 'string') {
        try { parsed = JSON.parse(parsed) } catch { /* ignore */ }
      }
      return typeof parsed === 'object' && parsed !== null ? parsed : {}
    } catch {
      return {}
    }
  }
  return {}
}

/**
 * 计算消息丰富度分数。分数越高，消息包含的展示信息越完整。
 * 只对 assistant 消息有意义。
 */
export function messageRichness(msg: Message): number {
  if (msg.role !== 'assistant') return 0
  let score = 0
  const meta = safeParseMeta(msg.metadata)

  // segments 是分段渲染的权威数据源，权重最高
  const segs = Array.isArray(meta.segments) ? meta.segments : []
  score += segs.length * 120

  // toolCalls
  const tcs = Array.isArray(meta.toolCalls) ? meta.toolCalls : []
  score += tcs.length * 40

  // contentParts 中的 thinking / tool_call
  const parts = Array.isArray(msg.contentParts) ? msg.contentParts : []
  if (parts.some(p => p.type === 'thinking')) score += 120
  score += parts.filter(p => p.type === 'tool_call').length * 40

  // content 文本长度只作为弱信号，避免“最终总结更长”把 richer timeline 冲掉
  score += Math.min((msg.content?.length || 0), 80)

  return score
}

function partKey(part: any): string {
  if (!part || !part.type) return ''
  return [
    part.type,
    part.text || '',
    part.fileUrl || '',
    part.fileName || '',
    part.path || '',
  ].join('::')
}

function mergeContentParts(localParts: any[], fetchedParts: any[]): any[] {
  if (!localParts.length) return fetchedParts
  if (!fetchedParts.length) return localParts

  const merged = [...fetchedParts]
  const seen = new Set(merged.map(partKey))

  for (const part of localParts) {
    const key = partKey(part)
    if (!key || seen.has(key)) continue
    merged.push(part)
    seen.add(key)
  }

  const order: Record<string, number> = {
    thinking: 0,
    tool_call: 1,
    text: 2,
    image: 3,
    file: 4,
    audio: 5,
    video: 6,
  }

  return merged.sort((a, b) => (order[a.type] ?? 99) - (order[b.type] ?? 99))
}

/** 审批的已决状态（前端通过 SSE 实时更新，比后端持久化更新） */
const RESOLVED_APPROVAL = new Set(['expired', 'approved', 'denied'])

function mergeMetadata(localMetaRaw: any, fetchedMetaRaw: any): Record<string, any> {
  const localMeta = safeParseMeta(localMetaRaw)
  const fetchedMeta = safeParseMeta(fetchedMetaRaw)
  const merged: Record<string, any> = { ...localMeta, ...fetchedMeta }

  const localSegs = Array.isArray(localMeta.segments) ? localMeta.segments : []
  const fetchedSegs = Array.isArray(fetchedMeta.segments) ? fetchedMeta.segments : []
  if (localSegs.length > fetchedSegs.length) {
    merged.segments = localSegs
  } else if (fetchedSegs.length > 0) {
    merged.segments = fetchedSegs
  }

  const localToolCalls = Array.isArray(localMeta.toolCalls) ? localMeta.toolCalls : []
  const fetchedToolCalls = Array.isArray(fetchedMeta.toolCalls) ? fetchedMeta.toolCalls : []
  if (localToolCalls.length > fetchedToolCalls.length) {
    merged.toolCalls = localToolCalls
  } else if (fetchedToolCalls.length > 0) {
    merged.toolCalls = fetchedToolCalls
  }

  // pendingApproval：前端已决/过期状态不被后端的 pending 状态回退
  const localApprovalStatus = localMeta.pendingApproval?.status
  if (RESOLVED_APPROVAL.has(localApprovalStatus)) {
    merged.pendingApproval = localMeta.pendingApproval
    // 清除已过期审批关联的 phase 字段，防止 UI 残留
    if (localApprovalStatus === 'expired') {
      delete merged.currentPhase
      delete merged.runningToolName
    }
  } else if (!merged.pendingApproval && localMeta.pendingApproval) {
    merged.pendingApproval = localMeta.pendingApproval
  }

  return merged
}

function mergeAssistantMessages(localMsg: Message, fetchedMsg: Message): Message {
  const localRichness = messageRichness(localMsg)
  const fetchedRichness = messageRichness(fetchedMsg)
  const richer = localRichness >= fetchedRichness ? localMsg : fetchedMsg
  const poorer = richer === localMsg ? fetchedMsg : localMsg

  // 取 richer 的 contentParts 而非合并两边，避免 tool_call 因序列化差异导致重复
  const localParts = Array.isArray(localMsg.contentParts) ? localMsg.contentParts : []
  const fetchedParts = Array.isArray(fetchedMsg.contentParts) ? fetchedMsg.contentParts : []
  const contentParts = localParts.length >= fetchedParts.length ? localParts : fetchedParts

  return {
    ...poorer,
    ...richer,
    // 用 fetched 的持久化字段覆盖（id、status、tokens 等）
    id: fetchedMsg.id || localMsg.id,
    content: (fetchedMsg.content?.length || 0) >= (localMsg.content?.length || 0)
      ? fetchedMsg.content
      : localMsg.content,
    contentParts,
    metadata: mergeMetadata(localMsg.metadata, fetchedMsg.metadata),
    // 不要把已终结的状态（failed/completed/stopped）回退为 awaiting_approval
    status: (fetchedMsg.status === 'awaiting_approval' && localMsg.status && localMsg.status !== 'awaiting_approval')
      ? localMsg.status
      : (fetchedMsg.status || localMsg.status),
    promptTokens: fetchedMsg.promptTokens ?? localMsg.promptTokens,
    completionTokens: fetchedMsg.completionTokens ?? localMsg.completionTokens,
    createTime: fetchedMsg.createTime || localMsg.createTime,
    conversationId: fetchedMsg.conversationId || localMsg.conversationId,
    thinkingExpanded: localMsg.thinkingExpanded ?? fetchedMsg.thinkingExpanded,
  }
}

/**
 * 逐条 reconcile：对每条消息按 id 匹配，只接受更丰富的版本。
 *
 * - 新消息（fetched 有但 local 没有）：接受 fetched
 * - 非 assistant 消息：直接用 fetched
 * - assistant 消息：比较 richness，取更高分的版本
 * - 本地有但 fetched 没有的 assistant 消息：保留（防止 lagging snapshot 丢消息）
 */
/**
 * 判断是否为"客户端临时 id"（UUID / 带字母的非数字 id）。
 * 服务端 id 是雪花算法生成的 Long（全数字字符串）。
 */
function isClientId(id: any): boolean {
  if (id === null || id === undefined) return true
  const s = String(id)
  return s === '' || !/^\d+$/.test(s)
}

export function reconcileMessages(local: Message[], fetched: Message[]): Message[] {
  if (!local.length) return fetched
  if (!fetched.length) return local

  const localMap = new Map<string, Message>()
  for (const m of local) {
    localMap.set(String(m.id), m)
  }

  const matchedLocalIds = new Set<string>()
  const result: Message[] = []

  // 收集未被 id 匹配过的本地 assistant（通常是流式产生的 client-uuid placeholder），
  // 供 fetched 端新 assistant"认领"它们的 timeline，避免两条并排。
  const unclaimedLocalAssistants: Message[] = []
  // Optimistic user bubbles carry a client temp id; the DB copy comes back
  // with a Snowflake id, so id-matching never pairs them. Without claiming,
  // the temp copy survives to the tail-preserve loop below and the question
  // renders twice (once from DB, once appended after the answer).
  const unclaimedLocalUsers: Message[] = []
  for (const lm of local) {
    if (!isClientId(lm.id)) continue
    if (lm.role === 'assistant') {
      unclaimedLocalAssistants.push(lm)
    } else if (lm.role === 'user') {
      unclaimedLocalUsers.push(lm)
    }
  }

  for (const fm of fetched) {
    const fid = String(fm.id)
    const lm = localMap.get(fid)

    if (lm) {
      if (fm.role !== 'assistant') {
        result.push(fm)
      } else {
        // assistant 消息：不要整条覆盖，合并 fetched 的持久化字段与 local 的 richer timeline
        result.push(mergeAssistantMessages(lm, fm))
      }
      matchedLocalIds.add(fid)
      continue
    }

    // fetched 里这条本地没有
    if (fm.role === 'assistant' && unclaimedLocalAssistants.length > 0) {
      // 尝试"认领"本地一个 client-uuid placeholder：
      // 取队首（最早的未认领），合并 richness 后采用 fetched 的持久化 id/时间。
      // 这消除了"本地流式气泡"+"刚落库的 DB assistant"同时存在的重复。
      const claimed = unclaimedLocalAssistants.shift()!
      matchedLocalIds.add(String(claimed.id))
      result.push(mergeAssistantMessages(claimed, fm))
    } else if (fm.role === 'user') {
      // 认领内容相同的本地乐观 user 消息（按内容匹配，重复文本时先到先领），
      // 使其不会落入尾部保留循环造成问题气泡重复。
      const idx = unclaimedLocalUsers.findIndex(
        u => (u.content || '') === (fm.content || '')
      )
      if (idx >= 0) {
        matchedLocalIds.add(String(unclaimedLocalUsers[idx].id))
        unclaimedLocalUsers.splice(idx, 1)
      }
      result.push(fm)
    } else {
      result.push(fm)
    }
  }

  // 保留 fetched 中不存在的本地消息（user + assistant），防止 lagging snapshot 丢弃刚发送的消息
  const fetchedConversationId = fetched.length > 0 ? (fetched[0] as any).conversationId : ''
  for (const lm of local) {
    const lid = String(lm.id)
    if (!matchedLocalIds.has(lid)) {
      // 跳过不属于当前对话的本地消息，防止跨对话污染
      const lmConvId = (lm as any).conversationId
      if (!lmConvId || (fetchedConversationId && lmConvId !== fetchedConversationId)) {
        continue
      }
      // 只保留在 fetched 末尾之后的消息（刚发送/刚完成，DB 还没返回）
      const lastFetchedTime = result.length > 0 ? result[result.length - 1].createTime : ''
      if (!lastFetchedTime || (lm.createTime && lm.createTime >= lastFetchedTime)) {
        result.push(lm)
      }
    }
  }

  return result
}

/**
 * 统一解析 API 响应中的消息列表。
 * 后端有两种返回模式：
 * - 不传 limit：R.ok(List<MessageVO>) → res.data 是数组
 * - 传 limit：R.ok({messages, hasMore}) → res.data 是对象
 */
export function extractMessages(res: any): { messages: any[], hasMore: boolean } {
  const data = res?.data
  if (Array.isArray(data)) {
    return { messages: data, hasMore: false }
  }
  if (data && Array.isArray(data.messages)) {
    return { messages: data.messages, hasMore: !!data.hasMore }
  }
  return { messages: [], hasMore: false }
}
