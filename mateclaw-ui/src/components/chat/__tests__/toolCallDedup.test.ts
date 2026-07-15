// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import type { MessageSegment } from '@/types'

/**
 * 复刻 MessageBubble.vue segments computed 里的 tool_call 去重逻辑做纯函数测试。
 *
 * 修复的 bug（issue #521）：工具/MCP 调用在对话中显示 2 次或多次。工具实际只
 * 调 1 次——重复来自去重 key 曾用 `toolName::toolArgs`：当同一次调用被 live 流
 * 与 reload 各渲染一遍时，两侧 toolArgs 的序列化可能有空白/键序差异，逃过去重
 * → 重复显示；同一 key 还会把"同名同参的多次真实调用"（重试）误合并成一次。
 *
 * 修复：优先用端到端稳定的 toolCallId 去重，无 id 时才退回 toolName::toolArgs。
 */

function dedupeToolCalls(segs: MessageSegment[]): MessageSegment[] {
  const seen = new Set<string>()
  return segs.filter(seg => {
    if (seg.type !== 'tool_call') return true
    const key = seg.toolCallId
      ? `id::${seg.toolCallId}`
      : `na::${seg.toolName}::${seg.toolArgs || ''}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function toolCall(id: string | undefined, name: string, args: string): MessageSegment {
  return { id: `seg-${Math.random()}`, type: 'tool_call', status: 'completed',
    toolName: name, toolArgs: args, toolCallId: id }
}

describe('dedupeToolCalls — 按 toolCallId 去重', () => {
  it('同一 toolCallId、args 序列化不同（live vs reload）→ 合并为一个（修复重复显示）', () => {
    const segs = [
      toolCall('call_1', 'wiki_search', '{"query":"a"}'),
      toolCall('call_1', 'wiki_search', '{ "query": "a" }'), // 空白差异
    ]
    const out = dedupeToolCalls(segs)
    expect(out).toHaveLength(1)
    expect(out[0].toolArgs).toBe('{"query":"a"}')
  })

  it('同名同参但 toolCallId 不同（真实重试）→ 全部保留（修复误合并）', () => {
    const segs = [
      toolCall('call_1', 'execute_shell', '{"cmd":"ls"}'),
      toolCall('call_2', 'execute_shell', '{"cmd":"ls"}'),
    ]
    const out = dedupeToolCalls(segs)
    expect(out).toHaveLength(2)
  })

  it('无 toolCallId 的遗留 segment → 退回 toolName::toolArgs 去重', () => {
    const segs = [
      toolCall(undefined, 'wiki_read_page', '{"slug":"x"}'),
      toolCall(undefined, 'wiki_read_page', '{"slug":"x"}'),
      toolCall(undefined, 'wiki_read_page', '{"slug":"y"}'),
    ]
    const out = dedupeToolCalls(segs)
    expect(out).toHaveLength(2)
    expect(out.map(s => s.toolArgs)).toEqual(['{"slug":"x"}', '{"slug":"y"}'])
  })

  it('混合有/无 id：有 id 按 id、无 id 按 name+args，互不干扰', () => {
    const segs = [
      toolCall('call_1', 'search', '{"q":"1"}'),
      toolCall('call_1', 'search', '{"q":"1"}'),   // dup by id
      toolCall(undefined, 'search', '{"q":"1"}'),  // 无 id，保留（key 前缀不同）
      toolCall(undefined, 'search', '{"q":"1"}'),  // dup of 上一条
    ]
    const out = dedupeToolCalls(segs)
    expect(out).toHaveLength(2)
  })

  it('非 tool_call segment 一律保留', () => {
    const segs: MessageSegment[] = [
      { id: 't1', type: 'thinking', status: 'completed', thinkingText: '...' },
      { id: 'c1', type: 'content', status: 'completed', text: 'hello' },
      toolCall('call_1', 'search', '{}'),
      toolCall('call_1', 'search', '{}'),
    ]
    const out = dedupeToolCalls(segs)
    expect(out).toHaveLength(3)
    expect(out.filter(s => s.type === 'tool_call')).toHaveLength(1)
  })
})
