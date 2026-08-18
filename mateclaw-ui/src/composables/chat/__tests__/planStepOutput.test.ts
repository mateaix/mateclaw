import { describe, expect, it } from 'vitest'
import type { MessageContentPart, MessageSegment } from '@/types'
import { stripCompletedPlanStepOutput } from '../planStepOutput'

describe('stripCompletedPlanStepOutput', () => {
  it('removes completed step text while preserving diagnostics', () => {
    const segments: MessageSegment[] = [
      { id: 'thinking', type: 'thinking', status: 'completed', thinkingText: 'reasoning' },
      { id: 'step', type: 'content', status: 'completed', text: 'TP-01' },
      { id: 'tool', type: 'tool_call', status: 'completed', toolName: 'search' },
    ]
    const parts: MessageContentPart[] = [
      { type: 'thinking', text: 'reasoning' },
      { type: 'text', text: 'TP-01' },
    ]

    const result = stripCompletedPlanStepOutput(segments, parts)

    expect(result.segments.map(segment => segment.type)).toEqual(['thinking', 'tool_call'])
    expect(result.contentParts.map(part => part.type)).toEqual(['thinking'])
    expect(result.segments).not.toBe(segments)
    expect(result.contentParts).not.toBe(parts)
  })

  it('removes every prior step content segment before the final summary starts', () => {
    const segments: MessageSegment[] = [
      { id: 'step-1', type: 'content', status: 'completed', text: 'first result' },
      { id: 'step-2', type: 'content', status: 'running', text: 'second result' },
    ]

    const result = stripCompletedPlanStepOutput(segments, [])

    expect(result.segments).toEqual([])
  })
})
