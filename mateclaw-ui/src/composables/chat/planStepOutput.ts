import type { MessageContentPart, MessageSegment } from '@/types'

/**
 * Move completed Plan-Execute step output out of the assistant body.
 *
 * The plan panel owns completed step results. The main body is reserved for
 * FINAL_SUMMARY, while diagnostic thinking/tool/delegation segments remain in
 * their original order. Returning fresh arrays also prevents Vue metadata and
 * the live segment buffer from sharing a mutable reference.
 */
export function stripCompletedPlanStepOutput(
  segments: MessageSegment[],
  contentParts: MessageContentPart[],
): { segments: MessageSegment[]; contentParts: MessageContentPart[] } {
  return {
    segments: segments.filter(segment => segment.type !== 'content'),
    contentParts: contentParts.filter(part => part.type !== 'text'),
  }
}
