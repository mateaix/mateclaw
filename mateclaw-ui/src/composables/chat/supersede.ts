import type { MessageSegment } from '@/types'

/**
 * Live counterpart of the backend's provisional-narration policy.
 *
 * A `pre_tool_narration` span is text the model wrote in a completion that went
 * on to call tools, before any of this turn's observations landed — it may be
 * process narration or a rehearsal of a result the tool had not produced yet.
 * It is replaced only when a later content span was actually written with an
 * observation in hand. Anything else (a phase boundary splitting one round's
 * text, a second span inside the same completion, an unrelated earlier span)
 * leaves it standing: nothing has superseded it, and collapsing it there hides
 * narration the user needs.
 */

/** Wire value shared with the backend so renderers need no new vocabulary. */
export const SUPERSEDED_REASON_PRE_TOOL = 'pre_tool_content_replaced_by_post_tool_answer'

/**
 * Whether a content span opening now replaces `prev`.
 *
 * @param prev              the content span immediately preceding the new one,
 *                          or undefined when this is the turn's first
 * @param observationCount  tool observations completed so far this turn
 * @param prevObservationMark observation count when `prev` was opened
 */
export function supersedesProvisionalNarration(
  prev: MessageSegment | undefined,
  observationCount: number,
  prevObservationMark: number,
): boolean {
  if (!prev || prev.type !== 'content') return false
  // Untagged spans (pre-tag producers, or a span whose `segment_kind` event has
  // not arrived yet) are never collapsed live — the persisted-metadata pass
  // decides those.
  if (prev.kind !== 'pre_tool_narration') return false
  if (prev.superseded) return false
  return observationCount > prevObservationMark
}

/** Apply the three annotation fields renderers read. */
export function markSuperseded(seg: MessageSegment, bySegmentId: string): void {
  seg.superseded = true
  seg.supersededBySegmentId = bySegmentId
  seg.supersededReason = SUPERSEDED_REASON_PRE_TOOL
}
