import { describe, it, expect } from 'vitest'
import type { MessageSegment } from '@/types'
import {
  SUPERSEDED_REASON_PRE_TOOL,
  markSuperseded,
  supersedesProvisionalNarration,
} from '../supersede'

function content(over: Partial<MessageSegment> = {}): MessageSegment {
  return {
    id: 'seg-1',
    type: 'content',
    status: 'completed',
    text: '我先查一下会议室占用情况：',
    timestamp: 0,
    ...over,
  } as MessageSegment
}

describe('supersedesProvisionalNarration', () => {
  it('replaces a provisional narration once an observation landed after it', () => {
    const prev = content({ kind: 'pre_tool_narration' })
    // opened at 0 observations, one tool completed since
    expect(supersedesProvisionalNarration(prev, 1, 0)).toBe(true)
  })

  it('keeps narration that no observation followed', () => {
    // The reported false-collapse: a phase boundary splits one round's text, so
    // a second content span opens with no tool having run in between. Nothing
    // replaced the first span — it must stay visible.
    const prev = content({ kind: 'pre_tool_narration' })
    expect(supersedesProvisionalNarration(prev, 0, 0)).toBe(false)
  })

  it('keeps narration when the only observations predate it', () => {
    // Span opened after two observations; a third span opens with the count
    // unchanged — the later text is not a replacement, just more narration.
    const prev = content({ kind: 'pre_tool_narration' })
    expect(supersedesProvisionalNarration(prev, 2, 2)).toBe(false)
  })

  it('never touches grounded narration or final answers', () => {
    expect(supersedesProvisionalNarration(content({ kind: 'grounded_narration' }), 3, 0)).toBe(false)
    expect(supersedesProvisionalNarration(content({ kind: 'final_answer' }), 3, 0)).toBe(false)
  })

  it('never touches an untagged span', () => {
    // `segment_kind` has not arrived (or the producer predates the tag) — the
    // persisted-metadata pass decides those, not the live rule.
    expect(supersedesProvisionalNarration(content(), 3, 0)).toBe(false)
  })

  it('is idempotent — an already-collapsed span is not re-marked', () => {
    const prev = content({ kind: 'pre_tool_narration', superseded: true })
    expect(supersedesProvisionalNarration(prev, 5, 0)).toBe(false)
  })

  it('handles the turn-opening span with no predecessor', () => {
    expect(supersedesProvisionalNarration(undefined, 1, 0)).toBe(false)
  })

  it('ignores non-content predecessors', () => {
    const toolSeg = { id: 'to-1', type: 'tool_call', status: 'completed', kind: 'pre_tool_narration' } as any
    expect(supersedesProvisionalNarration(toolSeg, 1, 0)).toBe(false)
  })
})

describe('markSuperseded', () => {
  it('writes the three annotation fields renderers read', () => {
    const seg = content({ kind: 'pre_tool_narration' })
    markSuperseded(seg, 'seg-2')

    expect(seg.superseded).toBe(true)
    expect(seg.supersededBySegmentId).toBe('seg-2')
    expect(seg.supersededReason).toBe(SUPERSEDED_REASON_PRE_TOOL)
  })
})

describe('multi-round timeline', () => {
  /**
   * Walks the guard across a full ReAct turn, the way useChat drives it: each
   * content span records the observation count it opened at, and only the
   * immediately preceding span is ever a candidate.
   */
  it('collapses only the span an observation actually replaced', () => {
    const marks = new Map<string, number>()
    const spans: MessageSegment[] = []
    let observations = 0

    /** Mirrors the content_delta branch that opens a new content span. */
    const openSpan = (id: string): MessageSegment => {
      const prev = spans.at(-1)
      if (prev && supersedesProvisionalNarration(prev, observations, marks.get(String(prev.id)) ?? 0)) {
        markSuperseded(prev, id)
      }
      const seg = content({ id, status: 'running' })
      marks.set(id, observations)
      spans.push(seg)
      return seg
    }

    // Round 0: narration written before any tool ran. `segment_kind` lands at
    // the end of the round, after the span opened.
    const s0 = openSpan('seg-0')
    s0.kind = 'pre_tool_narration'
    observations++ // load_skill observed — an iteration-refunded round still counts

    // Round 1: narration written with that observation in hand. It replaces s0
    // and is itself tagged provisional (its completion calls tools again).
    const s1 = openSpan('seg-1')
    s1.kind = 'pre_tool_narration'

    // A phase boundary splits round 1's text — no tool ran in between.
    openSpan('seg-2')

    expect(s0.superseded).toBe(true)
    expect(s0.supersededBySegmentId).toBe('seg-1')
    expect(s1.superseded).toBeUndefined()
  })
})
