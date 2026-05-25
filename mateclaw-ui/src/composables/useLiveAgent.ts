/**
 * Shared display helpers for the Live runtime view and its child components.
 * Pure formatting and classification — no API calls, no shared state.
 *
 * The live panel and the focus panel both render agent cards with avatars,
 * status rings, human sentences, and elapsed-time strings; keeping these in
 * one place avoids drift between the two surfaces when a status string or
 * ring rule changes.
 */
import { useI18n } from 'vue-i18n'
import type { LiveRunCard, LiveSubagentCard } from '@/api'

type AnyRun = LiveRunCard | LiveSubagentCard

export function useLiveAgent() {
  const { t } = useI18n()

  function avatarLetter(run: AnyRun): string {
    const name = run.agentName || (run as any).username || (run as any).subagentId || '?'
    return name.charAt(0).toUpperCase()
  }

  /**
   * Soft, low-saturation gradient seeded from agent name. Even when an icon
   * is present we use this as the avatar surface — Apple's wallpaper-behind-
   * icon trick, not a billboard.
   */
  function avatarBgStyle(run: AnyRun) {
    const seed = run.agentName || (run as any).conversationId || (run as any).subagentId || 'x'
    let h = 0
    for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) | 0
    const hue = Math.abs(h) % 360
    return {
      background: `linear-gradient(140deg, hsl(${hue}, 55%, 92%), hsl(${(hue + 30) % 360}, 50%, 86%))`,
      color: `hsl(${hue}, 45%, 28%)`,
    }
  }

  /**
   * Status ring classification — four moods:
   *   - thinking: alive but no first token yet (and still young) — spinner arc
   *   - stuck: explicit stuckReason — slow, saturated breathing
   *   - orphan: nobody listening — faint, almost-still
   *   - healthy: streaming or settled — gentle 3s breath
   *
   * Subagent cards lack stuck/orphan/firstToken flags, so they fall through
   * to healthy — which is what you'd want for a helper that's still around.
   */
  function ringClass(run: AnyRun): Record<string, boolean> {
    const r = run as LiveRunCard
    if (r.stuckReason) return { 'ring-stuck': true }
    if (r.orphan) return { 'ring-orphan': true }
    if (r.firstTokenReceived === false && r.ageMs < 30_000) return { 'ring-thinking': true }
    return { 'ring-healthy': true }
  }

  function dotTitle(run: AnyRun): string {
    const r = run as LiveRunCard
    if (r.stuckReason) return t('live.dotTitle.stuck')
    if (r.orphan) return t('live.dotTitle.orphan')
    return t('live.dotTitle.healthy')
  }

  /**
   * One-sentence status. Deliberately does NOT inline the tool name —
   * the tool deserves its own monospace chip in the UI, and embedding
   * it inside a sentence both gets ellipsis-truncated on narrow cards
   * AND duplicates information when the chip is also rendered.
   * stuckCallout (used in the focus panel) is the one place that still
   * mentions the tool name, because there it lives in a long-form alert.
   */
  function humanSentence(run: LiveRunCard): string {
    if (run.stuckReason === 'tool_silent') return t('live.saying.toolSilentBare')
    if (run.stuckReason === 'idle_silent') return t('live.saying.idleSilent')
    if (run.stuckReason === 'hard_cap') return t('live.saying.hardCap')
    if (run.currentPhase === 'awaiting_approval') return t('live.saying.awaitingApproval')
    if (run.runningToolName) return t('live.saying.usingToolBare')
    if (run.currentPhase === 'executing_tool') return t('live.saying.usingSomething')
    if (run.currentPhase === 'summarizing') return t('live.saying.wrappingUp')
    if (run.currentPhase === 'planning') return t('live.saying.planning')
    if (!run.firstTokenReceived) return t('live.saying.thinking')
    return t('live.saying.replying')
  }

  function formatAge(ms: number): string {
    if (ms < 1500) return t('live.time.justNow')
    const sec = Math.floor(ms / 1000)
    if (sec < 60) return t('live.time.seconds', { n: sec })
    const min = Math.floor(sec / 60)
    if (min < 60) return t('live.time.minutes', { n: min, s: sec % 60 })
    const hr = Math.floor(min / 60)
    return t('live.time.hours', { n: hr, m: min % 60 })
  }

  function stuckCallout(run: LiveRunCard): string {
    if (run.stuckReason === 'tool_silent') return t('live.callout.toolSilent', { tool: run.runningToolName || t('live.aTool'), time: formatAge(run.msSinceLastEvent) })
    if (run.stuckReason === 'idle_silent') return t('live.callout.idleSilent', { time: formatAge(run.msSinceLastEvent) })
    return t('live.callout.hardCap', { time: formatAge(run.ageMs) })
  }

  return {
    avatarLetter,
    avatarBgStyle,
    ringClass,
    dotTitle,
    humanSentence,
    stuckCallout,
    formatAge,
  }
}
